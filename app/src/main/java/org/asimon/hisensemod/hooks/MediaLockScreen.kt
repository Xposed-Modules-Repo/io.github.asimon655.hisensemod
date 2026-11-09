package org.asimon.hisensemod.hooks

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.asimon.hisensemod.Mod
import org.asimon.hisensemod.Prefs
import org.asimon.hisensemod.Prefs.on
import org.asimon.hisensemod.currentApplication
import org.asimon.hisensemod.field
import org.asimon.hisensemod.findClass
import org.asimon.hisensemod.hookAfter
import org.asimon.hisensemod.hookBefore
import org.asimon.hisensemod.installSafely
import org.asimon.hisensemod.method
import java.lang.ref.WeakReference

/**
 * Draws the playing song (cover + title + artist) on the e-ink lock screen, in the largest empty
 * gap so it never covers the clock, notification icons or motto.
 *
 * Battery: everything is event driven. We only listen to media session callbacks (which fire only
 * while something is playing) and ask for an e-ink redraw when the track, its cover or the
 * play/pause state changes — never on position updates. The drawing piggybacks on the image the
 * firmware already sends to the panel (`Util.poster`), including its own once-a-minute clock update.
 */
object MediaLockScreen {

    private const val UTIL = "com.eink.keyguard.Util"
    private const val KEYGUARD = "com.eink.keyguard.EinkKeyguardController"

    private data class Track(val title: String, val artist: String, val cover: Bitmap?)

    @Volatile
    private var track: Track? = null
    private var lastKey: String? = null
    private var keyguard: WeakReference<Any> = WeakReference(null)

    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private val controllers = mutableListOf<Pair<MediaController, MediaController.Callback>>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list.orEmpty())
    }

    fun install(cl: ClassLoader) {
        val util = cl.findClass(UTIL) ?: return
        installSafely("lock screen: track keyguard") {
            cl.findClass(KEYGUARD)?.declaredConstructors?.forEach { ctor ->
                hookAfter(ctor) { chain, r -> keyguard = WeakReference(chain.thisObject); r }
            }
        }
        installSafely("lock screen: now playing overlay") {
            hookBefore(util.method("poster", Bitmap::class.java, Int::class.javaPrimitiveType!!)) { chain ->
                val t = track
                val src = chain.args[0] as? Bitmap
                if (t == null || src == null || !enabled()) null
                else runCatching { arrayOf<Any?>(draw(src, t).also { debugDump(it) }, chain.args[1]) }
                    .onFailure { Mod.warn("now playing draw failed", it) }
                    .getOrNull()
            }
        }
        // SystemUI's Application isn't ready yet at hook time.
        main.post { if (enabled()) start() }
        Mod.onPrefsChanged { _, key ->
            when (key) {
                Prefs.LOCK_NOW_PLAYING.key -> main.post { if (enabled()) start() else stop() }
                Prefs.LOCK_NOW_PLAYING_COVER.key -> if (track != null) LockscreenNotifications.requestRedraw()
            }
        }
        Mod.onShutdown { main.post { stop() } }
    }

    /** `setprop debug.hisensemod.dump 1` saves each lock screen image to SystemUI's cache dir. */
    private fun debugDump(bmp: Bitmap) {
        val on = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.hisensemod.dump") == "1"
        }.getOrDefault(false)
        if (!on) return
        runCatching {
            val f = java.io.File(currentApplication()!!.cacheDir, "lockscreen.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun enabled() = Mod.prefs.on(Prefs.LOCK_NOW_PLAYING)
    private fun showCover() = Mod.prefs.on(Prefs.LOCK_NOW_PLAYING_COVER)

    // --- media sessions ----------------------------------------------------------------------

    private fun start() {
        if (msm != null) return
        val ctx: Context = currentApplication() ?: run { main.postDelayed({ if (enabled()) start() }, 2000); return }
        runCatching {
            val m = ctx.getSystemService(MediaSessionManager::class.java)
            // SystemUI holds MEDIA_CONTENT_CONTROL, so a null listener component sees all sessions.
            m.addOnActiveSessionsChangedListener(sessionsListener, null as ComponentName?, main)
            msm = m
            attach(m.getActiveSessions(null))
            Mod.log("now playing: listening to media sessions")
        }.onFailure { Mod.warn("now playing: cannot listen to media sessions", it) }
    }

    private fun stop() {
        msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        msm = null
        detachAll()
        update()
    }

    private fun attach(list: List<MediaController>) {
        detachAll()
        for (c in list) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = update()
                override fun onMetadataChanged(metadata: MediaMetadata?) = update()
                override fun onSessionDestroyed() = update()
            }
            c.registerCallback(cb, main)
            controllers += c to cb
        }
        update()
    }

    private fun detachAll() {
        controllers.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        controllers.clear()
    }

    /** Recompute what to show; redraw the e-ink screen only if it changed. */
    private fun update() {
        // Some apps (e.g. keysh) keep a fake "playing" session with no metadata: skip those.
        val playing = controllers.map { it.first }
            .firstOrNull { it.playbackState?.state in ACTIVE_STATES && !titleOf(it.metadata).isNullOrBlank() }
        val md = playing?.metadata
        val title = titleOf(md)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val art = md?.let { artOf(it) }
        // Players often send the title first and the cover a moment later: include the cover in the key.
        val key = if (playing == null || title.isNullOrBlank()) null
        else "${playing.packageName}|$title|$artist|${art?.let { "${it.width}x${it.height}" }}"
        if (key == lastKey) return
        lastKey = key

        track = if (key == null) null else Track(title!!, artist, art?.let { scaleCover(it) })
        LockscreenNotifications.requestRedraw()
    }

    /** Playing, or briefly between tracks (so the card doesn't flicker on song change). */
    private val ACTIVE_STATES = setOf(
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )

    private fun titleOf(md: MediaMetadata?): String? =
        md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

    private fun artOf(md: MediaMetadata): Bitmap? =
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    /** Square-crop and scale once per track (in colour: the panel is colour e-ink). */
    private fun scaleCover(art: Bitmap): Bitmap? = runCatching {
        val out = Bitmap.createBitmap(MAX_COVER_PX, MAX_COVER_PX, Bitmap.Config.ARGB_8888)
        val side = minOf(art.width, art.height)
        val src = Rect((art.width - side) / 2, (art.height - side) / 2, (art.width + side) / 2, (art.height + side) / 2)
        Canvas(out).drawBitmap(art, src, Rect(0, 0, MAX_COVER_PX, MAX_COVER_PX), Paint(Paint.FILTER_BITMAP_FLAG))
        out
    }.getOrNull()

    // --- layout: find free space -------------------------------------------------------------

    /** Vertical spans (in bitmap pixels) taken by visible lock screen content. */
    private fun occupiedSpans(bitmapW: Int): List<IntRange> {
        val root = currentStyleView() ?: return emptyList()
        if (root.width <= 0) return emptyList()
        val scale = bitmapW.toFloat() / root.width
        val screenArea = root.width.toLong() * root.height
        val spans = mutableListOf<IntRange>()

        fun walk(v: View, parentTop: Int) {
            if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return
            val top = parentTop + v.top - ((v.parent as? View)?.scrollY ?: 0)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), top)
                return
            }
            val hasContent = when (v) {
                is TextView -> !v.text.isNullOrBlank() || v.compoundDrawables.any { it != null }
                is ImageView -> v.drawable != null
                else -> true
            }
            // Skip backgrounds such as the wallpaper image.
            if (!hasContent || v.width.toLong() * v.height > screenArea / 3) return
            spans += (top * scale).toInt()..((top + v.height) * scale).toInt()
        }
        for (i in 0 until (root as? ViewGroup ?: return emptyList()).childCount) walk(root.getChildAt(i), 0)
        return spans
    }

    /** The style view the firmware just rendered (same choice as EinkKeyguardController.getCurrentStyleView). */
    private fun currentStyleView(): View? = runCatching {
        val kg = keyguard.get() ?: return null
        when (kg.field<Int>("mCurrentStyle")) {
            0 -> kg.field<View>("mFWKeyguard")
            1 -> kg.field<View>("mWWKeyguard")
            4 -> kg.field<View>("mBTKeyguard")
            else -> kg.field<View>("mVPKeyguard")
        }
    }.getOrNull()

    /** Largest vertical gap between occupied spans, as [top, bottom). */
    private fun largestGap(height: Int, spans: List<IntRange>): Pair<Int, Int> {
        var best = 0 to height
        var bestSize = -1
        var y = MARGIN
        for (s in spans.sortedBy { it.first } + listOf(height - MARGIN..height)) {
            if (s.first - y > bestSize) { bestSize = s.first - y; best = y to s.first }
            y = maxOf(y, s.last + MARGIN)
        }
        return best
    }

    // --- drawing -----------------------------------------------------------------------------

    private const val MAX_COVER_PX = 340
    private const val MIN_COVER_PX = 140
    private const val MARGIN = 24
    private const val PAD = 24f
    private const val CARD_W = MAX_COVER_PX + 2 * PAD.toInt()

    /** Copy of [src] with a white card in the largest free gap: cover, then title and artist. */
    private fun draw(src: Bitmap, t: Track): Bitmap {
        val out = src.copy(src.config ?: Bitmap.Config.RGB_565, true)
        val c = Canvas(out)
        val textW = CARD_W - 2 * PAD.toInt()

        val title = layout(t.title, 34f, bold = true, width = textW, maxLines = 2)
        val artist = if (t.artist.isBlank()) null else layout(t.artist, 28f, bold = false, width = textW, maxLines = 1)
        val textH = title.height + (artist?.let { it.height + 6f } ?: 0f)

        val (gapTop, gapBottom) = largestGap(out.height, occupiedSpans(out.width))
        val gap = (gapBottom - gapTop).toFloat()

        // Shrink the cover to fit the gap; drop it if it would be too small.
        var coverPx = 0
        if (t.cover != null && showCover()) {
            coverPx = minOf(MAX_COVER_PX, (gap - textH - 3 * PAD).toInt())
            if (coverPx < MIN_COVER_PX) coverPx = 0
        }
        val cardH = PAD + (if (coverPx > 0) coverPx + PAD else 0f) + textH + PAD
        val left = (out.width - CARD_W) / 2f
        val top = gapTop + maxOf(0f, (gap - cardH) / 2f)

        val card = RectF(left, top, left + CARD_W, top + cardH)
        c.drawRoundRect(card, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawRoundRect(card, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f
        })

        var y = top + PAD
        if (coverPx > 0) {
            val x = left + (CARD_W - coverPx) / 2f
            c.drawBitmap(t.cover!!, null, RectF(x, y, x + coverPx, y + coverPx), Paint(Paint.FILTER_BITMAP_FLAG))
            y += coverPx + PAD
        }
        c.save(); c.translate(left + PAD, y); title.draw(c); c.restore()
        y += title.height + 6f
        artist?.let { c.save(); c.translate(left + PAD, y); it.draw(c); c.restore() }
        return out
    }

    /** StaticLayout handles Hebrew/RTL text and ellipsizes long names. */
    private fun layout(text: String, size: Float, bold: Boolean, width: Int, maxLines: Int): StaticLayout {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }
}

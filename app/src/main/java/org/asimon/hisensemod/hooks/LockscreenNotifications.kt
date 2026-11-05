package org.asimon.hisensemod.hooks

import android.app.Notification
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.asimon.hisensemod.Mod
import org.asimon.hisensemod.Prefs
import org.asimon.hisensemod.Prefs.int
import org.asimon.hisensemod.Prefs.list
import org.asimon.hisensemod.Prefs.on
import org.asimon.hisensemod.field
import org.asimon.hisensemod.findClass
import org.asimon.hisensemod.hookAfter
import org.asimon.hisensemod.installSafely
import org.asimon.hisensemod.method
import org.asimon.hisensemod.methodsNamed
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Shows an icon (and count) on the e-ink lock screen for every watched app with unread
 * notifications — WhatsApp, Telegram, Gmail, ... by default.
 *
 * Replaces the old NotificationObsrv app + `su -c settings put system my_app_notify ...` +
 * Mello's WeChat/QQ slot hijacking: SystemUI already listens to every notification, so we read
 * them in-process and add our own views to the Hisense lock screen row. No root shell, no extra
 * app, no Magisk toast.
 */
object LockscreenNotifications {

    private const val CONTROLLER = "com.hmct.einklauncher.plugin.notification.NotificationController"
    private const val SYSUI_LISTENER = "com.android.systemui.statusbar.NotificationListener"
    private const val VIEW_TAG = "hisensemod.lock_icon"
    private const val REFRESH_DELAY_MS = 300L

    // Ongoing / foreground-service notifications ("Telegram is running", calls, uploads) never count.
    private const val NOT_UNREAD_FLAGS = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE

    private val controllers: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    @Volatile
    private var listener: WeakReference<Any> = WeakReference(null)
    private val iconCache = HashMap<String, Drawable>()
    private val main = Handler(Looper.getMainLooper())
    private val refresh = Runnable { refreshAll() }

    fun install(cl: ClassLoader) {
        val c = cl.findClass(CONTROLLER) ?: return

        installSafely("lock screen: track controllers") {
            c.declaredConstructors.forEach { ctor ->
                hookAfter(ctor) { chain, r -> controllers += chain.thisObject; r }
            }
        }

        // The controller's own NotificationListenerService is never registered by the firmware, so
        // use SystemUI's main notification listener as the source of truth.
        installSafely("lock screen: notification changes") {
            val nl = cl.findClass(SYSUI_LISTENER) ?: return@installSafely
            nl.methodsNamed("onListenerConnected").forEach { m ->
                hookAfter(m) { chain, r -> listener = WeakReference(chain.thisObject); scheduleRefresh(); r }
            }
            for (name in listOf("onNotificationPosted", "onNotificationRemoved")) {
                nl.methodsNamed(name).forEach { m ->
                    hookAfter(m) { chain, r ->
                        listener = WeakReference(chain.thisObject)
                        val sbn = chain.args.firstOrNull() as? StatusBarNotification
                        if (sbn != null && sbn.packageName in watched()) scheduleRefresh()
                        r
                    }
                }
            }
        }

        installSafely("lock screen: render") {
            hookAfter(c.method("updateSimpleNotiCount")) { chain, r ->
                controllers += chain.thisObject
                render(chain.thisObject); r
            }
            // updateColor() swaps black/white styling depending on the wallpaper; re-render to match.
            hookAfter(c.method("updateColor", Boolean::class.javaPrimitiveType!!)) { chain, r ->
                render(chain.thisObject); r
            }
        }

        Mod.onPrefsChanged { _, _ ->
            main.post { iconCache.clear() }
            scheduleRefresh()
        }
        Mod.onShutdown { main.removeCallbacks(refresh) }
    }

    private fun watched(): List<String> = Mod.prefs.list(Prefs.LOCK_WATCHED_APPS, Prefs.DEFAULT_WATCHED_APPS)

    private fun scheduleRefresh() {
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, REFRESH_DELAY_MS)
    }

    /**
     * Trigger the controller's own "simple notification" observer: it calls updateSimpleNotiCount()
     * (which we hook to render) and then asks the e-ink keyguard to redraw even while the screen is off.
     */
    private fun refreshAll() {
        val snapshot = synchronized(controllers) { controllers.toList() }
        for (ctrl in snapshot) {
            runCatching { ctrl.field<ContentObserver>("mSimpleNotiObserver").onChange(false) }
                .onFailure { Mod.warn("lock screen refresh failed", it) }
        }
    }

    private fun render(ctrl: Any) {
        runCatching { renderUnsafe(ctrl) }.onFailure { Mod.warn("lock screen render failed", it) }
    }

    private fun renderUnsafe(ctrl: Any) {
        val container: ViewGroup = ctrl.field("mSimpleNotiContainer") ?: return
        val ctx = container.context
        val prefs = Mod.prefs

        // Drop views from the previous render (also from an older module generation).
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == VIEW_TAG) container.removeViewAt(i)
        }

        if (prefs.on(Prefs.LOCK_CALL_SMS_COUNTS)) {
            writeStockCount(container, "phone_call_info", "com_android_contacts_hs_unread")
            writeStockCount(container, "mms_info", "com_android_mms_hs_unread")
        }

        if (!prefs.on(Prefs.LOCK_APP_ICONS)) return
        val nls = listener.get() as? NotificationListenerService ?: return
        val active = runCatching { nls.activeNotifications }.getOrNull() ?: return
        val counts = unreadCounts(active)
        if (counts.isEmpty()) return

        val textColor = idView<TextView>(container, "mis_call_num")?.currentTextColor
            ?: idView<TextView>(container, "phone_call_info")?.currentTextColor
        val template = idView<View>(container, "mis_call_container")?.layoutParams as? ViewGroup.MarginLayoutParams
        val showCounts = prefs.on(Prefs.LOCK_SHOW_COUNTS)
        val sizePx = dp(ctx, prefs.int(Prefs.LOCK_ICON_SIZE_DP, Prefs.DEFAULT_ICON_SIZE_DP))
        val gray = prefs.on(Prefs.LOCK_GRAY_ICONS)

        for ((pkg, count) in counts) {
            val icon = appIcon(ctx, pkg, sizePx, gray) ?: continue
            val tv = TextView(ctx).apply {
                tag = VIEW_TAG
                gravity = Gravity.CENTER
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(ctx, 2)
                text = if (showCounts) count.toString() else ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                textColor?.let { setTextColor(it) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            template?.let { lp.setMargins(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) }
            if (lp.leftMargin == 0 && lp.rightMargin == 0) lp.setMargins(dp(ctx, 8), 0, dp(ctx, 8), 0)
            lp.gravity = Gravity.CENTER_VERTICAL
            container.addView(tv, lp)
        }
    }

    /** Per watched package: number of conversations/emails (non-summary notifications). */
    private fun unreadCounts(active: Array<StatusBarNotification>): Map<String, Int> {
        val order = watched()
        val ignored = Mod.prefs.list(Prefs.LOCK_IGNORED_CHANNELS, Prefs.DEFAULT_IGNORED_CHANNELS).toSet()
        val counts = HashMap<String, Int>()
        for (sbn in active) {
            val pkg = sbn.packageName
            if (pkg !in order) continue
            val n = sbn.notification
            if (n.flags and NOT_UNREAD_FLAGS != 0) continue
            if ("$pkg:${n.channelId}" in ignored) continue
            val isSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
            // A summary alone still means "something unread" (count 1) but doesn't add to the count.
            counts[pkg] = (counts[pkg] ?: 0) + if (isSummary) 0 else 1
        }
        return order.filter { it in counts }.associateWith { maxOf(1, counts[it]!!) }
    }

    private fun writeStockCount(container: ViewGroup, viewId: String, settingKey: String) {
        val tv = idView<TextView>(container, viewId) ?: return
        val n = Settings.System.getInt(container.context.contentResolver, settingKey, 0)
        tv.text = if (n > 0) n.toString() else ""
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
    }

    private fun appIcon(ctx: Context, pkg: String, sizePx: Int, gray: Boolean): Drawable? {
        val key = "$pkg/$sizePx/$gray"
        iconCache[key]?.let { return it }
        val src = runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        src.setBounds(0, 0, sizePx, sizePx)
        if (gray) {
            // Render once, then redraw through a grayscale + contrast filter.
            val tmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            src.draw(Canvas(tmp))
            canvas.drawBitmap(tmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(einkMatrix()) })
            tmp.recycle()
        } else {
            src.draw(canvas)
        }
        return BitmapDrawable(ctx.resources, bmp).apply { setBounds(0, 0, sizePx, sizePx) }
            .also { iconCache[key] = it }
    }

    private fun einkMatrix(): ColorMatrix {
        val m = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.35f
        val shift = (-0.5f * contrast + 0.5f) * 255f
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, shift,
                    0f, contrast, 0f, 0f, shift,
                    0f, 0f, contrast, 0f, shift,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        return m
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> idView(container: ViewGroup, name: String): T? {
        val id = container.resources.getIdentifier(name, "id", "com.android.systemui")
        return if (id != 0) container.findViewById<View>(id) as? T else null
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}

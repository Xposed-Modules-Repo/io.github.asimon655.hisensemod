package io.github.asimon655.hisensemod.hooks

import android.app.Notification
import android.graphics.Color
import io.github.asimon655.hisensemod.Mod
import io.github.asimon655.hisensemod.Prefs
import io.github.asimon655.hisensemod.Prefs.list
import io.github.asimon655.hisensemod.Prefs.on
import io.github.asimon655.hisensemod.findClass
import io.github.asimon655.hisensemod.hookBefore
import io.github.asimon655.hisensemod.installSafely
import io.github.asimon655.hisensemod.methodsNamed

/**
 * E-ink friendly notifications (formerly the "einkify" module).
 *
 * The old module hooked Notification.Builder.build() inside each app, so every app had to be
 * added to the scope. Now the same clean-up happens once in system_server when any app posts a
 * notification, covering all apps. The per-app hook is kept for apps added to the scope manually.
 */
object EinkNotificationHooks {

    /** system_server: NotificationManagerService.enqueueNotificationInternal(pkg, opPkg, ..., Notification, ...). */
    fun installSystemServer(cl: ClassLoader) = installSafely("e-ink notifications (system)") {
        val nms = cl.findClass("com.android.server.notification.NotificationManagerService") ?: return@installSafely
        val methods = nms.methodsNamed("enqueueNotificationInternal")
        if (methods.isEmpty()) Mod.warn("enqueueNotificationInternal not found")
        methods.forEach { m ->
            hookBefore(m) { chain ->
                val args = chain.args
                val n = args.firstOrNull { it is Notification } as Notification?
                val pkg = args.firstOrNull { it is String } as String?
                if (n != null) strip(n, pkg)
                null
            }
        }
    }

    /** Fallback inside individual apps (same as the original einkify hook). */
    fun installInApp() = installSafely("e-ink notifications (app)") {
        val build = Notification.Builder::class.java.getDeclaredMethod("build")
        hookBefore(build) { chain ->
            if (Mod.prefs.on(Prefs.EINK_NOTIFICATIONS)) {
                val b = chain.thisObject as Notification.Builder
                b.setColorized(false)
                b.setColor(Color.WHITE)
                b.extras.putBoolean(Notification.EXTRA_COLORIZED, false)
            }
            null
        }
    }

    private fun strip(n: Notification, pkg: String?) {
        val prefs = Mod.prefs
        if (!prefs.on(Prefs.EINK_NOTIFICATIONS)) return
        if (pkg != null && pkg in prefs.list(Prefs.EINK_EXCLUDED, emptyList())) return
        restyle(n)
        // The redacted copy shown on the lock screen when content is hidden.
        n.publicVersion?.let { restyle(it) }
    }

    private fun restyle(n: Notification) {
        // No colourised (album art / brand colour) backgrounds, including media notifications.
        n.extras?.putBoolean(Notification.EXTRA_COLORIZED, false)
        // White accent: the notification template darkens it until it contrasts with the white
        // background, i.e. near-black text and icons on e-ink.
        if (n.color != Notification.COLOR_DEFAULT) n.color = Color.WHITE
    }
}

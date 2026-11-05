package org.asimon.hisensemod.hooks

import android.app.ActivityManager
import android.graphics.drawable.Icon
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Process
import android.service.notification.StatusBarNotification
import android.view.View
import org.asimon.hisensemod.CallOriginal
import org.asimon.hisensemod.Mod
import org.asimon.hisensemod.Prefs
import org.asimon.hisensemod.Prefs.list
import org.asimon.hisensemod.Prefs.on
import org.asimon.hisensemod.currentApplication
import org.asimon.hisensemod.field
import org.asimon.hisensemod.findClass
import org.asimon.hisensemod.hookAfter
import org.asimon.hisensemod.hookReplace
import org.asimon.hisensemod.installSafely
import org.asimon.hisensemod.method
import org.asimon.hisensemod.setField

/** Status bar and task manager tweaks inside com.android.systemui (ported from Mello). */
object SystemUiHooks {

    /** True while NotificationIconContainer.IconState.applyToView runs on this thread. */
    private val applyingStatusBarIcon = ThreadLocal<Boolean>()

    fun install(cl: ClassLoader) {
        installSafely("status bar icon whitelist") { statusBarWhitelist(cl) }
        installSafely("status bar icon layout") { statusBarIconLayout(cl) }
        installSafely("monochrome status bar icons") { monochromeIcons(cl) }
        installSafely("gentle deep clean") { deepClean(cl) }
        installSafely("memory cleaner whitelist") { protectWatchedApps(cl) }
        installSafely("step counter") { stepCounter(cl) }
    }

    /** Stock firmware only shows status bar icons for whitelisted packages. */
    private fun statusBarWhitelist(cl: ClassLoader) {
        val c = cl.findClass("com.android.systemui.statusbar.phone.NotificationIconAreaController") ?: return
        hookReplace(c.method("checkDisplayInStatusBar", String::class.java)) {
            if (Mod.prefs.on(Prefs.STATUSBAR_ALL_ICONS)) true else CallOriginal
        }
    }

    /**
     * applyToView() only refreshes icon drawables when the container isn't "static". Mello makes
     * it non-static for the duration of applyIconStates() and then leaves it static. Leaving it
     * static afterwards matters: restoring the shelf's original non-static state makes every
     * layout pass swap drawables and request another layout, an endless loop (~50% CPU).
     */
    private fun statusBarIconLayout(cl: ClassLoader) {
        val c = cl.findClass("com.android.systemui.statusbar.phone.NotificationIconContainer") ?: return
        hookReplace(c.method("applyIconStates")) { chain ->
            if (!Mod.prefs.on(Prefs.STATUSBAR_MONO_ICONS)) return@hookReplace CallOriginal
            val self = chain.thisObject
            self.setField("mIsStaticLayout", false)
            try {
                chain.proceed()
            } finally {
                self.setField("mIsStaticLayout", true)
            }
        }
    }

    private fun monochromeIcons(cl: ClassLoader) {
        val iconState = cl.findClass("com.android.systemui.statusbar.phone.NotificationIconContainer\$IconState")
        hookReplace(iconState?.method("applyToView", View::class.java)) { chain ->
            applyingStatusBarIcon.set(true)
            try {
                chain.proceed()
            } finally {
                applyingStatusBarIcon.remove()
            }
        }

        val util = cl.findClass("com.hmct.utils.AppThemeUtil") ?: return
        hookAfter(util.method("getSmallAppIcon", StatusBarNotification::class.java, Icon::class.java)) { chain, result ->
            if (applyingStatusBarIcon.get() != true || !Mod.prefs.on(Prefs.STATUSBAR_MONO_ICONS)) {
                return@hookAfter result
            }
            val app = currentApplication() ?: return@hookAfter result
            val sbn = chain.getArg(0) as StatusBarNotification
            val original = chain.getArg(1) as Icon?
            fun res(name: String): Icon? {
                val id = app.resources.getIdentifier(name, "drawable", app.packageName)
                return if (id != 0) Icon.createWithResource(app, id) else null
            }
            val pkg = sbn.packageName
            when {
                pkg == "com.android.mms" -> res("ic_status_bar_himms_icon")
                pkg == "com.android.email" -> res("ic_status_bar_hiemail_icon")
                pkg == "com.android.dialer" ->
                    if (sbn.notification.channelId == "phone_missed_call") res("ic_status_bar_dialer_missed_icon")
                    else res("ic_status_bar_dialer_icon")
                pkg == "android" || pkg.startsWith("com.android") || pkg.startsWith("com.hmct") ->
                    res("ic_notification_more")
                else -> original
            } ?: result
        }
    }

    /**
     * The task manager's "clear all" force-kills everything. Replace it with removing
     * recent tasks only, so background apps (messengers!) keep working.
     */
    private fun deepClean(cl: ClassLoader) {
        val c = cl.findClass("com.hmct.systemui.taskmanager.TaskmanagerService") ?: return
        hookReplace(c.method("doDeepClean")) {
            if (!Mod.prefs.on(Prefs.DEEP_CLEAN_SAFE)) return@hookReplace CallOriginal
            val keep = Mod.prefs.list(Prefs.DEEP_CLEAN_KEEP, Prefs.DEFAULT_DEEP_CLEAN_KEEP)
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
            val slice = atm.javaClass.getMethod(
                "getRecentTasks", Int::class.java, Int::class.java, Int::class.java
            ).invoke(atm, 1000, ActivityManager.RECENT_IGNORE_UNAVAILABLE, userId())
            @Suppress("UNCHECKED_CAST")
            val tasks = slice!!.javaClass.getMethod("getList").invoke(slice) as List<ActivityManager.RecentTaskInfo>
            val remove = atm.javaClass.getMethod("removeTask", Int::class.java)
            for (task in tasks) {
                val pkg = task.baseIntent.component?.packageName ?: continue
                if (keep.any { pkg.contains(it) }) continue
                remove.invoke(atm, task.persistentId)
            }
            Mod.log("gentle deep clean removed ${tasks.size} tasks")
            null
        }
    }

    /** Hisense's MemoryDetectService kills background apps not on its "pkgname_white" list. */
    private fun protectWatchedApps(cl: ClassLoader) {
        val c = cl.findClass("com.hmct.systemui.taskmanager.detectmemory.MemoryDetectService") ?: return
        hookAfter(c.method("getPreloadWhiteList")) { _, result ->
            if (Mod.prefs.on(Prefs.PROTECT_WATCHED_APPS)) {
                @Suppress("UNCHECKED_CAST")
                val list = result as? MutableList<String>
                Mod.prefs.list(Prefs.LOCK_WATCHED_APPS, Prefs.DEFAULT_WATCHED_APPS).forEach {
                    if (list != null && it !in list) list += it
                }
            }
            result
        }
    }

    private fun userId(): Int =
        Process.myUserHandle().javaClass.getMethod("getIdentifier").invoke(Process.myUserHandle()) as Int

    /** Optionally stop the lock screen pedometer from listening to sensors. */
    private fun stepCounter(cl: ClassLoader) {
        val c = cl.findClass("com.android.systemui.statusbar.keyguard.FootStepsCounterService") ?: return
        hookAfter(c.method("onCreate")) { chain, result ->
            if (Mod.prefs.on(Prefs.DISABLE_STEP_COUNTER)) {
                val svc = chain.thisObject
                val sm: SensorManager? = svc.field("mSensorManager")
                for (name in listOf("mSensorListener", "mFootTypeSensorListener")) {
                    runCatching { sm?.unregisterListener(svc.field<SensorEventListener>(name)) }
                }
                Mod.log("step counter sensors unregistered")
            }
            result
        }
    }
}

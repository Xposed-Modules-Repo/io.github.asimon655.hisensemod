package org.asimon.hisensemod.hooks

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.asimon.hisensemod.CallOriginal
import org.asimon.hisensemod.Mod
import org.asimon.hisensemod.Prefs
import org.asimon.hisensemod.Prefs.on
import org.asimon.hisensemod.field
import org.asimon.hisensemod.findClass
import org.asimon.hisensemod.hookAfter
import org.asimon.hisensemod.hookReplace
import org.asimon.hisensemod.installSafely
import org.asimon.hisensemod.method

/** com.hmct.vision (launcher) fixes from Mello. */
object LauncherHooks {
    fun install(cl: ClassLoader) {
        // Add the flip calendar plugin to the launcher's plugin whitelist.
        installSafely("launcher plugin whitelist") {
            val c = cl.findClass("com.hmct.vision.plugin.h") ?: return@installSafely
            hookAfter(c.method("d", Context::class.java)) { chain, r ->
                if (Mod.prefs.on(Prefs.LAUNCHER_FIXES)) {
                    chain.thisObject.field<ArrayList<String>?>("h")?.add("com.android.launcher.plugin.flipcalendar")
                }
                r
            }
        }

        // The launcher starts widget configure activities in a way that fails for third-party
        // widgets; go through AppWidgetHost like AOSP does.
        installSafely("launcher widget configure") {
            val c = cl.findClass("com.hmct.vision.Launcher") ?: return@installSafely
            val notFound = runCatching {
                cl.loadClass("com.hmct.vision.R\$string").getField("activity_not_found").getInt(null)
            }.getOrDefault(0)
            hookReplace(c.method("startActivityForResult", Intent::class.java, Int::class.javaPrimitiveType!!)) { chain ->
                val intent = chain.getArg(0) as Intent?
                if (!Mod.prefs.on(Prefs.LAUNCHER_FIXES) ||
                    intent?.action != AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                ) return@hookReplace CallOriginal
                val activity = chain.thisObject as Activity
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 1024)
                try {
                    AppWidgetHost(activity.applicationContext, widgetId)
                        .startAppWidgetConfigureActivityForResult(activity, widgetId, 0, chain.getArg(1) as Int, null)
                } catch (e: ActivityNotFoundException) {
                    if (notFound != 0) Toast.makeText(activity, notFound, Toast.LENGTH_SHORT).show()
                } catch (e: SecurityException) {
                    if (notFound != 0) Toast.makeText(activity, notFound, Toast.LENGTH_SHORT).show()
                }
                null
            }
        }
    }
}

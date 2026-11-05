package org.asimon.hisensemod.hooks

import android.content.Context
import android.preference.PreferenceManager
import org.asimon.hisensemod.CallOriginal
import org.asimon.hisensemod.Mod
import org.asimon.hisensemod.Prefs
import org.asimon.hisensemod.Prefs.on
import org.asimon.hisensemod.findClass
import org.asimon.hisensemod.hookReplace
import org.asimon.hisensemod.installSafely
import org.asimon.hisensemod.method

/**
 * com.android.powermanager: SPMgr.isLowBatteryAutoSave()/isAutoSwitchMode() are hard-coded to
 * return false in this firmware, although the settings screen stores the user's choice.
 * Return the stored value so the switches actually work.
 */
object PowerManagerHooks {
    fun install(cl: ClassLoader) = installSafely("power manager toggles") {
        val c = cl.findClass("com.android.powermanager.common.SPMgr") ?: return@installSafely
        for ((method, key) in listOf("isLowBatteryAutoSave" to "lowBatteryAutoSave", "isAutoSwitchMode" to "autoSwitchMode")) {
            hookReplace(c.method(method, Context::class.java)) { chain ->
                if (!Mod.prefs.on(Prefs.POWER_RESTORE_TOGGLES)) return@hookReplace CallOriginal
                val ctx = chain.getArg(0) as Context
                @Suppress("DEPRECATION")
                PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(key, false)
            }
        }
    }
}

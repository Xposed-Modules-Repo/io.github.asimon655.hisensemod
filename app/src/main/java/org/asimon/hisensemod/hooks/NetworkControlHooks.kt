package org.asimon.hisensemod.hooks

import android.content.Context
import org.asimon.hisensemod.CallOriginal
import org.asimon.hisensemod.Mod
import org.asimon.hisensemod.Prefs
import org.asimon.hisensemod.Prefs.on
import org.asimon.hisensemod.findClass
import org.asimon.hisensemod.hookReplace
import org.asimon.hisensemod.installSafely
import org.asimon.hisensemod.method

/**
 * com.android.networkcontrol: stop the Hisense firewall from saving/purging/applying iptables
 * rules so it doesn't fight AdAway, AFWall, VPNs etc.
 */
object NetworkControlHooks {
    fun install(cl: ClassLoader) = installSafely("network control firewall") {
        val c = cl.findClass("com.android.networkcontrol.firewall.FirewallApi") ?: return@installSafely
        val bool = Boolean::class.javaPrimitiveType!!
        fun off() = !Mod.prefs.on(Prefs.NETCTL_NO_FIREWALL)

        hookReplace(c.method("saveRules", Context::class.java)) { if (off()) CallOriginal else null }
        hookReplace(c.method("purgeIptables", Context::class.java, bool)) { if (off()) CallOriginal else true }
        hookReplace(c.method("applyIptablesRulesImpl", Context::class.java, List::class.java, List::class.java, bool)) {
            if (off()) CallOriginal else true
        }
    }
}

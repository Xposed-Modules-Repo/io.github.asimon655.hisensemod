package io.github.asimon655.hisensemod.hooks

import android.content.Context
import io.github.asimon655.hisensemod.CallOriginal
import io.github.asimon655.hisensemod.Mod
import io.github.asimon655.hisensemod.Prefs
import io.github.asimon655.hisensemod.Prefs.on
import io.github.asimon655.hisensemod.findClass
import io.github.asimon655.hisensemod.hookReplace
import io.github.asimon655.hisensemod.installSafely
import io.github.asimon655.hisensemod.method

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

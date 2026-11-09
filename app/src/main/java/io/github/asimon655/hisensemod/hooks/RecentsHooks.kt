package io.github.asimon655.hisensemod.hooks

import io.github.asimon655.hisensemod.Mod
import io.github.asimon655.hisensemod.Prefs
import io.github.asimon655.hisensemod.Prefs.on
import io.github.asimon655.hisensemod.findClass
import io.github.asimon655.hisensemod.hookBefore
import io.github.asimon655.hisensemod.installSafely
import io.github.asimon655.hisensemod.method

/**
 * com.hmct.recents: swiping an app away calls killOneTask(taskId, pkg, userId) which force-stops
 * most apps. Packages containing "com.hmct.floatwindow" only get their task removed, so (like Mello)
 * we disguise the package name. Packages with their own special handling are left alone.
 */
object RecentsHooks {
    private val SPECIAL_CASED = listOf(
        "com.hmct.music", "com.hmct.hsmemo", "com.caf.fmradio", "com.android.soundrecorder",
        "com.hmct.questionnaire", "com.android.deskclock", "com.android.mms", "com.android.contacts",
        "com.ctsi.emm", "com.hmct.health", "com.hmct.intelligent", "com.greenpoint.android.mc10086.activity",
        "com.qualcomm.uimremoteclient", "com.redteamobile.virtual.softsim", "io.vsim.bridge",
        "com.hmct.heduohaoclient", "com.hmct.memo", "com.android.calendar", "com.android.calculator2",
        "com.opera.max.global", "com.hmct.einklauncher", "com.android.smspush", "com.hmct.vlw",
        "com.zsmarter.mdm", "com.hmct.screen.record", "com.android.cts.forcestophelper", "com.hmct.floatwindow",
    )

    fun install(cl: ClassLoader) = installSafely("recents soft kill") {
        val c = cl.findClass("com.hmct.quickstep.views.RecentsView") ?: return@installSafely
        val m = c.method("killOneTask", Int::class.javaPrimitiveType!!, String::class.java, Int::class.javaPrimitiveType!!)
        hookBefore(m) { chain ->
            if (!Mod.prefs.on(Prefs.RECENTS_SOFT_KILL)) return@hookBefore null
            val pkg = chain.getArg(1)?.toString() ?: return@hookBefore null
            if (SPECIAL_CASED.any { pkg.contains(it) }) return@hookBefore null
            chain.args.toTypedArray().also { it[1] = "${pkg}_act_like_com.hmct.floatwindow" }
        }
    }
}

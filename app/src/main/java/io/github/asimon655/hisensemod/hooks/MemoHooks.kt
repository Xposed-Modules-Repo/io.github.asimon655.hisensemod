package io.github.asimon655.hisensemod.hooks

import android.app.Activity
import android.os.Bundle
import android.view.View
import io.github.asimon655.hisensemod.Prefs
import io.github.asimon655.hisensemod.Mod
import io.github.asimon655.hisensemod.Prefs.on
import io.github.asimon655.hisensemod.findClass
import io.github.asimon655.hisensemod.hookAfter
import io.github.asimon655.hisensemod.installSafely
import io.github.asimon655.hisensemod.method

/** com.hmct.hsmemo: toggle the view type once when the memo list opens (Mello behaviour). */
object MemoHooks {
    fun install(cl: ClassLoader) = installSafely("memo view type") {
        val c = cl.findClass("com.hmct.hsmemo.activities.MemoListActivity") ?: return@installSafely
        hookAfter(c.method("onCreate", Bundle::class.java)) { chain, r ->
            if (Mod.prefs.on(Prefs.MEMO_FIX)) {
                val a = chain.thisObject as Activity
                val id = a.resources.getIdentifier("tool_type", "id", "com.hmct.hsmemo")
                if (id != 0) a.findViewById<View>(id)?.performClick()
            }
            r
        }
    }
}

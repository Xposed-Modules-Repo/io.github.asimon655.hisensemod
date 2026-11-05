package org.asimon.hisensemod.hooks

import android.app.Activity
import android.os.Bundle
import android.view.View
import org.asimon.hisensemod.Prefs
import org.asimon.hisensemod.Mod
import org.asimon.hisensemod.Prefs.on
import org.asimon.hisensemod.findClass
import org.asimon.hisensemod.hookAfter
import org.asimon.hisensemod.installSafely
import org.asimon.hisensemod.method

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

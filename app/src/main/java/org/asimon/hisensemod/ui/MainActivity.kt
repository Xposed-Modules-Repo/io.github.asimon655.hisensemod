package org.asimon.hisensemod.ui

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import org.asimon.hisensemod.Prefs

/**
 * Plain, high-contrast settings screen (no animations, no colour) that suits the e-ink display.
 * Values go to Vector's remote preferences, which the hooks read live — no reboot needed except
 * where noted.
 */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private val serviceListener: (XposedService?) -> Unit = { runOnUiThread { build() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root, MATCH_PARENT, WRAP_CONTENT)
        })
        build()
    }

    override fun onStart() {
        super.onStart()
        App.listeners += serviceListener
    }

    override fun onStop() {
        App.listeners -= serviceListener
        super.onStop()
    }

    private fun build() {
        root.removeAllViews()
        title("Hisense Mod")
        val service = App.service
        if (service == null) {
            text(
                "Module not active.\n\n1. Enable \"Hisense Mod\" in Vector.\n" +
                    "2. Make sure the scope includes System Framework and SystemUI.\n3. Reboot."
            )
            return
        }
        val prefs = service.getRemotePreferences(Prefs.GROUP)
        text("${service.frameworkName} ${service.frameworkVersion} · API ${service.apiVersion}")

        val wanted = Prefs.ALL_TOGGLES.map { it.scope }.distinct()
        val missing = wanted - service.scope.toSet()
        if (missing.isNotEmpty()) {
            text("Not in scope: ${missing.joinToString()}")
            button("Add missing scope") {
                service.requestScope(missing, object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) = toast("Scope added; reboot to apply")
                    override fun onScopeRequestFailed(message: String) = toast("Scope request failed: $message")
                })
            }
        }

        section("Lock screen")
        toggles(prefs, "com.android.systemui", Prefs.LOCK_APP_ICONS, Prefs.LOCK_SHOW_COUNTS, Prefs.LOCK_GRAY_ICONS, Prefs.LOCK_CALL_SMS_COUNTS)
        val watched = listEditor(
            "Apps with lock screen icons (one package per line, in display order)",
            prefs.getString(Prefs.LOCK_WATCHED_APPS, null) ?: Prefs.DEFAULT_WATCHED_APPS.joinToString("\n")
        )
        val ignored = listEditor(
            "Ignored notification channels (package:channelId)",
            prefs.getString(Prefs.LOCK_IGNORED_CHANNELS, null) ?: Prefs.DEFAULT_IGNORED_CHANNELS.joinToString("\n")
        )
        val size = listEditor("Icon size (dp)", prefs.getInt(Prefs.LOCK_ICON_SIZE_DP, Prefs.DEFAULT_ICON_SIZE_DP).toString(), number = true)
        button("Save lock screen settings") {
            prefs.edit()
                .putString(Prefs.LOCK_WATCHED_APPS, watched.text.toString().trim())
                .putString(Prefs.LOCK_IGNORED_CHANNELS, ignored.text.toString().trim())
                .putInt(Prefs.LOCK_ICON_SIZE_DP, size.text.toString().toIntOrNull() ?: Prefs.DEFAULT_ICON_SIZE_DP)
                .apply()
            toast("Saved")
        }

        section("Status bar & SystemUI")
        toggles(prefs, "com.android.systemui", Prefs.STATUSBAR_ALL_ICONS, Prefs.STATUSBAR_MONO_ICONS, Prefs.DEEP_CLEAN_SAFE, Prefs.PROTECT_WATCHED_APPS, Prefs.DISABLE_STEP_COUNTER)

        section("Notifications")
        toggles(prefs, "system", Prefs.EINK_NOTIFICATIONS)
        val excluded = listEditor("Apps excluded from e-ink restyling (one per line)", prefs.getString(Prefs.EINK_EXCLUDED, null) ?: "")
        button("Save exclusions") {
            prefs.edit().putString(Prefs.EINK_EXCLUDED, excluded.text.toString().trim()).apply()
            toast("Saved")
        }

        section("Other Hisense apps")
        toggles(prefs, null, Prefs.RECENTS_SOFT_KILL, Prefs.LAUNCHER_FIXES, Prefs.MEMO_FIX, Prefs.NETCTL_NO_FIREWALL, Prefs.POWER_RESTORE_TOGGLES)

        text("\nChanges apply immediately. Scope changes and the step counter option need a reboot (or SystemUI restart).")
    }

    // --- tiny view helpers ------------------------------------------------------------------

    private fun toggles(prefs: SharedPreferences, scope: String?, vararg toggles: Prefs.Toggle) {
        for (t in toggles.filter { scope == null || it.scope == scope }) {
            val sw = Switch(this).apply {
                text = t.title
                textSize = 17f
                setTextColor(Color.BLACK)
                isChecked = prefs.getBoolean(t.key, t.default)
                setPadding(0, dp(12), 0, 0)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(t.key, on).apply() }
            }
            root.addView(sw, MATCH_PARENT, WRAP_CONTENT)
            text(t.summary, size = 13f)
        }
    }

    private fun title(s: String) = text(s, size = 26f, bold = true)

    private fun section(s: String) {
        root.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(2)).apply {
            topMargin = dp(24)
        })
        text(s, size = 20f, bold = true)
    }

    private fun text(s: String, size: Float = 15f, bold: Boolean = false) {
        root.addView(TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.BLACK)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        }, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun listEditor(label: String, value: String, number: Boolean = false): EditText {
        text(label, size = 14f, bold = true)
        return EditText(this).apply {
            setText(value)
            setTextColor(Color.BLACK)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            inputType = if (number) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
        }.also { root.addView(it, MATCH_PARENT, WRAP_CONTENT) }
    }

    private fun button(label: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

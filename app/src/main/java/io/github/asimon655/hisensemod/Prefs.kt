package io.github.asimon655.hisensemod

import android.content.SharedPreferences

/**
 * Preference keys shared by the settings app (writes through the Xposed service)
 * and the hooks (read-only remote preferences inside the hooked process).
 */
object Prefs {
    const val GROUP = "settings"

    class Toggle(val key: String, val default: Boolean, val title: String, val summary: String, val scope: String)

    // SystemUI
    val STATUSBAR_ALL_ICONS = Toggle(
        "sysui_statusbar_all_icons", true, "Show all notification icons in status bar",
        "Stock firmware only shows icons for a whitelist of apps.", "com.android.systemui"
    )
    val STATUSBAR_MONO_ICONS = Toggle(
        "sysui_statusbar_mono_icons", true, "Monochrome status bar icons",
        "Use the app's own small icon instead of Hisense's colour app icon; system apps get a neutral icon.",
        "com.android.systemui"
    )
    val DEEP_CLEAN_SAFE = Toggle(
        "sysui_deep_clean_safe", true, "Gentle 'clear all' in task manager",
        "Clear-all only removes recent tasks instead of killing processes (keeps messaging apps alive).",
        "com.android.systemui"
    )
    val PROTECT_WATCHED_APPS = Toggle(
        "sysui_protect_watched_apps", true, "Protect lock screen apps from memory cleaner",
        "Adds the watched apps (Telegram, Gmail, ...) to Hisense's background-kill whitelist so their notifications arrive on time.",
        "com.android.systemui"
    )
    val DISABLE_STEP_COUNTER = Toggle(
        "sysui_disable_step_counter", false, "Disable lock screen step counter",
        "Stops SystemUI from listening to the step sensor. Needs SystemUI restart.", "com.android.systemui"
    )

    // Lock screen notifications
    val LOCK_APP_ICONS = Toggle(
        "lock_app_icons", true, "App icons on lock screen",
        "Show an icon for each watched app that has unread notifications.", "com.android.systemui"
    )
    val LOCK_SHOW_COUNTS = Toggle(
        "lock_show_counts", true, "Show counts next to app icons",
        "Number of chats / emails with notifications.", "com.android.systemui"
    )
    val LOCK_GRAY_ICONS = Toggle(
        "lock_gray_icons", true, "Grayscale lock screen icons",
        "Render app icons in high-contrast grayscale (better on e-ink).", "com.android.systemui"
    )
    val LOCK_CALL_SMS_COUNTS = Toggle(
        "lock_call_sms_counts", true, "Missed call / SMS counts",
        "Write the number of missed calls and unread SMS under their icons.", "com.android.systemui"
    )

    val LOCK_NOW_PLAYING = Toggle(
        "lock_now_playing", false, "Now playing on lock screen",
        "Show the playing song's cover and name in the middle of the e-ink lock screen. Redraws only when the song changes.",
        "com.android.systemui"
    )

    val LOCK_NOW_PLAYING_COVER = Toggle(
        "lock_now_playing_cover", true, "Show album cover",
        "Off: only the song and artist name are shown.", "com.android.systemui"
    )

    // Other system apps
    val RECENTS_SOFT_KILL = Toggle(
        "recents_soft_kill", true, "Swiping a recent app doesn't force-stop it",
        "Only removes the task, so apps keep receiving notifications.", "com.hmct.recents"
    )
    val LAUNCHER_FIXES = Toggle(
        "launcher_fixes", true, "Launcher fixes",
        "Allow the flip calendar plugin and fix widget configuration screens.", "com.hmct.vision"
    )
    val MEMO_FIX = Toggle(
        "memo_fix", true, "Memo opens in list view", "Toggles the memo view type on open.", "com.hmct.hsmemo"
    )
    val NETCTL_NO_FIREWALL = Toggle(
        "netctl_no_firewall", true, "Disable Hisense firewall rules",
        "Stops Network Control from rewriting iptables (lets AdAway/AFWall work).", "com.android.networkcontrol"
    )
    val POWER_RESTORE_TOGGLES = Toggle(
        "power_restore_toggles", true, "Honour power saver toggles",
        "Firmware hard-codes 'auto power save' and 'scheduled mode' off; make the switches work.",
        "com.android.powermanager"
    )

    // E-ink notifications (einkify)
    val EINK_NOTIFICATIONS = Toggle(
        "eink_notifications", true, "E-ink friendly notifications",
        "Strip colourised backgrounds and accent colours from every app's notifications (system-wide).",
        "system"
    )

    val ALL_TOGGLES = listOf(
        STATUSBAR_ALL_ICONS, STATUSBAR_MONO_ICONS, DEEP_CLEAN_SAFE, PROTECT_WATCHED_APPS, DISABLE_STEP_COUNTER,
        LOCK_APP_ICONS, LOCK_SHOW_COUNTS, LOCK_GRAY_ICONS, LOCK_CALL_SMS_COUNTS, LOCK_NOW_PLAYING, LOCK_NOW_PLAYING_COVER,
        RECENTS_SOFT_KILL, LAUNCHER_FIXES, MEMO_FIX, NETCTL_NO_FIREWALL, POWER_RESTORE_TOGGLES,
        EINK_NOTIFICATIONS,
    )

    /** Apps whose notifications get an icon on the lock screen, in display order. */
    const val LOCK_WATCHED_APPS = "lock_watched_apps"
    val DEFAULT_WATCHED_APPS = listOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "com.google.android.gm",
    )

    /** "package:channelId" pairs that never count as unread (silent/system channels). */
    const val LOCK_IGNORED_CHANNELS = "lock_ignored_channels"
    val DEFAULT_IGNORED_CHANNELS = listOf(
        "com.whatsapp:other_notifications",
        "com.whatsapp:failure_notifications",
        "com.whatsapp:silent_notifications",
        "com.whatsapp.w4b:other_notifications",
        "com.whatsapp.w4b:failure_notifications",
        "com.whatsapp.w4b:silent_notifications",
    )

    const val LOCK_ICON_SIZE_DP = "lock_icon_size_dp"
    const val DEFAULT_ICON_SIZE_DP = 30

    /** Packages that "clear all" never removes from recents. */
    const val DEEP_CLEAN_KEEP = "deep_clean_keep"
    val DEFAULT_DEEP_CLEAN_KEEP = listOf("com.google.android.gms")

    /** Packages excluded from the e-ink notification restyling. */
    const val EINK_EXCLUDED = "eink_excluded"

    fun SharedPreferences?.on(t: Toggle): Boolean =
        this?.let { runCatching { it.getBoolean(t.key, t.default) }.getOrNull() } ?: t.default

    fun SharedPreferences?.list(key: String, default: List<String>): List<String> {
        val raw = this?.let { runCatching { it.getString(key, null) }.getOrNull() } ?: return default
        return raw.split('\n', ';', ',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun SharedPreferences?.int(key: String, default: Int): Int =
        this?.let { runCatching { it.getInt(key, default) }.getOrNull() } ?: default
}

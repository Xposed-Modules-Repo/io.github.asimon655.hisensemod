# Hisense Mod

Xposed module for the **Hisense A5 Pro CC** (Android 10, e-ink), written in Kotlin against the
modern **libxposed API 102** as implemented by [Vector](https://github.com/JingMatrix/Vector) v2.2.

It merges three earlier projects:

| Old project | What happened to it |
|---|---|
| **MelloSystemUI** v4.3 (closed source, from XDA) | Reverse-engineered and ported hook by hook, each one now behind a switch. |
| **einkify** | Moved from a per-app `Notification.Builder.build()` hook into **system_server**, so it now covers every app without adding each one to the scope. The per-app hook still runs for any extra app you add to the scope. |
| **NotificationObsrv** | Replaced. It used a notification listener plus `su -c settings put system my_app_notify ...`, which caused the Magisk toast. Now SystemUI's own notification listener is read in-process and the icons are drawn straight into the lock screen row. No root shell, no extra app, no notification-access or autorun permissions. |

## Features

**Lock screen (e-ink keyguard)**
- Icon + count for every watched app with unread notifications. Defaults: WhatsApp (+Business), Telegram (official, web build, Telegram X), Gmail. Any number of apps, in your order.
- Grayscale, high-contrast icons; configurable size.
- Ongoing/foreground notifications and WhatsApp's silent/failure channels never count (editable).
- Missed call / SMS counts under the stock icons (Mello).

**SystemUI**
- Show all notification icons in the status bar, not just Hisense's whitelist (Mello).
- Monochrome status bar icons (Mello).
- Task manager "clear all" only removes recent tasks instead of killing processes (Mello).
- Watched apps are added to Hisense's memory-cleaner whitelist so they aren't killed in the background (**new**).
- Optional: turn off the lock screen step counter's sensor listeners (**new**).

**Other Hisense apps (Mello)**
- Recents: swiping an app away no longer force-stops it.
- Launcher: flip-calendar plugin whitelist; third-party widget configuration fix.
- Memo: toggle view type on open.
- Network Control: stop the Hisense firewall from rewriting iptables.
- Power manager: the "auto power save" / "scheduled mode" switches actually work (firmware hard-codes them off).

**Notifications (einkify)**
- No colourised backgrounds (media/album art, brand colours) and near-black accents, system-wide. Per-app exclusions.

All settings live in the **Hisense Mod** app and apply immediately through Vector's remote
preferences. Scope changes need a reboot.

## Build & install

```sh
./gradlew assembleRelease          # needs Android SDK platform 37 (AGP downloads it)
adb install -r app/build/outputs/apk/release/app-release.apk
```

Enable and scope it from the computer with Vector's CLI (or use the Vector manager):

```sh
C=/data/adb/lspd/cli
adb shell su -c "'$C modules enable org.asimon.hisensemod'"
adb shell su -c "'$C scope set org.asimon.hisensemod system/0 com.android.systemui/0 com.hmct.recents/0 com.hmct.vision/0 com.hmct.hsmemo/0 com.android.networkcontrol/0 com.android.powermanager/0'"
adb shell su -c "'$C modules disable fix.systemui.mello org.asimon.einkify'"   # don't run both
adb reboot
```

Logs: `adb logcat -s HisenseMod` (SystemUI's earliest lines at boot are dropped by logd), or
`adb shell su -c "'/data/adb/lspd/cli log cat'"`.

## Scripts (`scripts/`)

| Script | What it does |
|---|---|
| `debloat.sh` | `pm disable-user` on Hisense/Tencent telemetry, OTA, antivirus, voice assistant, manuals and Tencent network location; disables Tencent "traffic correction" (texts your carrier). |
| `eink-tweaks.sh` | Animations off (every frame is an e-ink refresh); background Bluetooth scanning off. |
| `restore.sh` | Undoes both scripts. |
| `cleanup-old-modules.sh` | Uninstalls Mello, einkify and NotificationObsrv once you're happy. |

## Location fixes

- **Network location:** Android picks the network-location provider with the highest version among
  system apps. Tencent's (`com.tencent.android.location`, China-only) beat Google's, so network
  location never worked. `debloat.sh` disables it and Google's provider takes over.
- **Slow GPS lock:** this is a Unisoc ums512 (GNSS "marlin3lite"). Its A-GPS server is set in
  `/vendor/etc/supl.xml` as `supl.qxwz.com`, which is unreachable, so every start was a cold start.
  The Magisk module in `magisk/hisense-gnss-fix` points it at `supl.google.com:7275` (TLS, SUPL 2.0).
  Install: copy the folder to `/data/adb/modules/hisense_gnss_fix`, set the `supl.xml` SELinux label to
  `u:object_r:vendor_configs_file:s0`, reboot. Original: `docs/original/supl.xml.orig`.
- The `optimizedgpsconf` Magisk module only replaces Qualcomm's `gps.conf`, which this chip ignores.

## Notes / findings

- Mello's status-bar trick (`mIsStaticLayout` false during `applyIconStates`, then **true**) must stay
  exactly as is: restoring the original value causes an endless layout loop at ~50% CPU.
- The Hisense keyguard `NotificationController` has its own `NotificationListenerService`, but the
  firmware never registers it, so its notification list is always empty.
  `com.android.systemui.statusbar.NotificationListener` is used instead.
- Hook targets were checked against this firmware: `QP1A.190711.020`, build `HLTE203T`.

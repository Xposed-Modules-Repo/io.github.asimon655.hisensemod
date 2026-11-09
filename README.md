# Hisense Mod

An Xposed module for the **Hisense A5 Pro CC** (HLTE203T, Android 10, colour e-ink). It fixes
annoyances in the Hisense firmware and adds features to the e-ink lock screen.

Written in Kotlin against the modern **libxposed API 102**, tested with
[Vector](https://github.com/JingMatrix/Vector) v2.2. Every feature has its own switch in the
**Hisense Mod** app, and changes apply immediately (scope changes need a reboot).

## Features

### Lock screen (e-ink, screen off)
- **App icons with unread counts** for the apps you choose: WhatsApp (+ Business), Telegram
  (official, web build, Telegram X) and Gmail by default, in your order. The count is a badge on the
  icon's corner. Ongoing notifications and silent channels don't count; the ignore list is editable.
- **Now playing:** the current song's cover, title and artist, placed in the free space so it never
  covers the clock, icons or motto. Optional cover (text only). Hebrew and long titles are handled.
  It only redraws when the song or play/pause state changes, so it costs almost no battery.
- **Missed call / SMS counts** under the stock icons.
- Optional high-contrast grayscale icons; configurable icon size.

### Status bar and SystemUI
- Show notification icons for every app, not just Hisense's whitelist.
- Monochrome status bar icons.
- Task manager "clear all" removes recent tasks instead of killing apps.
- Apps on the lock screen list are protected from Hisense's memory cleaner, so their notifications
  arrive on time.
- Optional: stop the lock screen step counter from listening to the step sensor.

### Notifications
- E-ink friendly notifications system-wide: no coloured backgrounds or bright accent colours.
  Per-app exclusions.

### Other Hisense apps
- **Recents:** swiping an app away doesn't force-stop it.
- **Launcher:** flip-calendar plugin works; third-party widget configuration screens work.
- **Memo:** opens in list view.
- **Network Control:** stops the Hisense firewall from rewriting iptables (AdAway/AFWall work).
- **Power manager:** the "auto power save" and "scheduled mode" switches work (the firmware forces
  them off).

## Install

Requires root (Magisk) and [Vector](https://github.com/JingMatrix/Vector).

1. Download the APK from [Releases](https://github.com/asimon655/hisense-mod/releases) and install it.
2. Enable the module in Vector and select its scope: **System Framework**, **System UI**,
   `com.hmct.recents`, `com.hmct.vision`, `com.hmct.hsmemo`, `com.android.networkcontrol`,
   `com.android.powermanager`.
3. Reboot, then open **Hisense Mod** to change settings.

Or from a computer with Vector's CLI:

```sh
C=/data/adb/lspd/cli
adb shell su -c "'$C modules enable io.github.asimon655.hisensemod'"
adb shell su -c "'$C scope set io.github.asimon655.hisensemod system/0 com.android.systemui/0 com.hmct.recents/0 com.hmct.vision/0 com.hmct.hsmemo/0 com.android.networkcontrol/0 com.android.powermanager/0'"
adb reboot
```

Logs: `adb logcat -s HisenseMod`.

## Extra fixes (no module needed)

### Scripts (`scripts/`, run from a computer over adb)

| Script | What it does |
|---|---|
| `debloat.sh` | Disables Hisense/Tencent telemetry, OTA, antivirus, voice assistant, manuals and Tencent network location, plus Tencent "traffic correction" (which texts your carrier). Uses `pm disable-user`, so nothing is uninstalled. |
| `eink-tweaks.sh` | Turns animations off (every frame is an e-ink refresh) and background Bluetooth scanning off. |
| `restore.sh` | Undoes both scripts. |

### Location
- **Network location never works:** Android picks the network-location provider with the highest
  version, and Tencent's China-only provider beats Google's. `debloat.sh` disables it.
- **Slow GPS lock:** the Unisoc ums512 GNSS uses `supl.qxwz.com` for A-GPS, which is unreachable, so
  every fix is a cold start. The Magisk module in `magisk/hisense-gnss-fix` switches it to
  `supl.google.com:7275`. Copy the folder to `/data/adb/modules/hisense_gnss_fix`, set the
  `supl.xml` SELinux label to `u:object_r:vendor_configs_file:s0` and reboot. The original file is in
  `docs/original/supl.xml.orig`.
- The `optimizedgpsconf` Magisk module doesn't help: it replaces Qualcomm's `gps.conf`, which this
  chip ignores.

### VoLTE outside China
- Keep **`com.spreadtrum.ims`** installed: it is the phone's VoLTE service. Google's
  `com.google.android.ims` only does RCS chat.
- Hisense enables VoLTE only for Chinese carriers. Allow it for others with
  `adb shell settings put global volte_other 1`, then reboot.

## Build from source

```sh
./gradlew assembleRelease   # Android SDK platform 37; Gradle downloads the JDK it needs
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the key in `keystore.properties` (git-ignored) if it exists,
otherwise with your debug key. Pushing a tag like `v1.1` makes GitHub Actions build a signed APK
and publish it as a release.

## Notes

- Hook targets were checked against firmware `QP1A.190711.020` (`HLTE203T`).
- The e-ink lock screen is drawn as one image and sent to the panel by `com.eink.keyguard.Util.poster`;
  the now-playing card is drawn onto that image.
- The status bar fix sets `mIsStaticLayout` to false during `applyIconStates` and then to **true**.
  Restoring the original value instead causes an endless layout loop at ~50% CPU.
- The keyguard `NotificationController` has its own `NotificationListenerService`, but the firmware
  never registers it, so the module reads SystemUI's main `NotificationListener` instead.

## Credits

Ideas and earlier work this project builds on:
- **MelloSystemUI** (XDA): many of the SystemUI and Hisense app fixes.
- **einkify**: e-ink friendly notifications.
- **NotificationObsrv**: unread app icons on the lock screen.

#!/usr/bin/env bash
# Remove the three projects merged into Hisense Mod (Mello, einkify, NotificationObsrv).
# Only run once you're happy with Hisense Mod.
set -u
adb shell su -c "'settings delete system my_app_notify'"
for p in fix.systemui.mello org.asimon.einkify org.asimon.notificationobsrv; do
  printf '%-34s ' "$p"; adb uninstall "$p"
done

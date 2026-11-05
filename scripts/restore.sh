#!/usr/bin/env bash
# Undo debloat.sh and eink-tweaks.sh.
set -u
PKGS=$(grep -E '^\s*#?\s*com\.' "$(dirname "$0")/debloat.sh" | sed -E 's/^\s*#?\s*//; s/\s.*//')
for p in $PKGS; do printf '%-34s ' "$p"; adb shell pm enable "$p" 2>&1 | tail -1; done
adb shell su -c "'pm enable com.android.networkcontrol/com.tencent.TrafficCorrectionService'"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $k 1.0; done
adb shell settings put global wifi_scan_always_enabled 1
adb shell settings put global ble_scan_always_enabled 1

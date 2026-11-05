#!/usr/bin/env bash
# Settings that help an e-ink phone. Undo with ./restore.sh
set -u
# No animations: every animation frame is an e-ink refresh (ghosting + battery).
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global $k 0
done
# Stop background Wi-Fi/Bluetooth scanning for location when those radios are off.
adb shell settings put global wifi_scan_always_enabled 0
adb shell settings put global ble_scan_always_enabled 0
echo done

#!/usr/bin/env bash
# Settings that help an e-ink phone. Undo with ./restore.sh
set -u
# No animations: every animation frame is an e-ink refresh (ghosting + battery).
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global $k 0
done
# Stop background Bluetooth scanning. (Wi-Fi scanning stays on: network location needs it.)
adb shell settings put global ble_scan_always_enabled 0
echo done

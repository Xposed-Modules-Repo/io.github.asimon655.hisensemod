#!/usr/bin/env bash
# Disable Hisense/Tencent bloat on the Hisense A5 Pro CC (Android 10).
# Run from a computer with the phone connected over adb. Fully reversible: ./restore.sh
# Uses `pm disable-user` (no uninstall), so a factory reset or restore.sh brings everything back.
set -u

# Telemetry, feedback, OTA (useless on a rooted/patched device), Tencent antivirus,
# Chinese voice assistant/translation, manuals and online fonts.
SAFE=(
  com.hmct.userexperienceprogram   # usage telemetry
  com.hmct.userfeedback            # feedback uploader
  com.hmct.questionnaire           # survey pop-ups
  com.hmct.factorylogupload        # uploads factory logs
  com.hmct.updater                 # OTA client (OTAs break root anyway)
  com.hmct.ota.appinstall          # OTA app installer
  com.hmct.payguard                # Tencent payment "antivirus", always-running service
  com.hmct.antivirus               # Tencent antivirus
  com.hmct.voiceassist             # Chinese voice assistant
  com.hmct.voicetranslate          # Chinese voice translation
  com.hmct.semantic.analysis       # voice assistant NLP
  com.android.fontOnline           # online font store
  com.hmct.HmctService             # service-point locator
  com.hmct.hmctmanual              # manual
  com.hmct.instruction             # instructions app
  com.hisense.hiphone.payment      # Hisense payment
  com.hmct.dreams                  # "movie dream" screensaver
  com.tencent.android.location     # Tencent network location: China-only, outranks Google's, so network location never works
)

# Optional: uncomment if you don't use these.
OPTIONAL=(
  # com.hmct.clock.systemwidget    # Hisense weather widget (keeps an always-alive service)
)

for p in "${SAFE[@]}" ${OPTIONAL[@]+"${OPTIONAL[@]}"}; do
  [ -z "$p" ] && continue
  printf '%-34s ' "$p"
  adb shell pm disable-user --user 0 "$p" 2>&1 | tail -1
done

# Network Control's Tencent "traffic correction" service texts your carrier to check data usage.
adb shell su -c "'pm disable com.android.networkcontrol/com.tencent.TrafficCorrectionService'" 2>&1 | tail -1

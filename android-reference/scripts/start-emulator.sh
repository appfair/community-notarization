#!/bin/bash
# Boots the AVD with a writable /system (needed to install the verifier as a system app) and waits
# for the boot to finish. Set HEADLESS=1 to run without a window. The emulator keeps running in the
# background; stop it with `adb emu kill`.
source "$(dirname "$0")/common.sh"
[ -d "$HOME/.android/avd/$AVD.avd" ] || "$ROOT/scripts/create-avd.sh"
extra=()
[ "${HEADLESS:-0}" = 1 ] && extra+=(-no-window -no-audio -gpu swiftshader_indirect)
mkdir -p "$OUT"
log "starting $AVD (log: $OUT/emulator.log)"
nohup "$SDK/emulator/emulator" -avd "$AVD" -writable-system -no-snapshot -no-boot-anim "${extra[@]}" \
  > "$OUT/emulator.log" 2>&1 &
sleep 5
if [ -z "${ANDROID_SERIAL:-}" ]; then
  # Pick the serial of the emulator we just started, so other running emulators are left alone.
  for i in $(seq 1 30); do
    for s in $("$SDK/platform-tools/adb" devices | awk '/^emulator-/{print $1}'); do
      if [ "$("$SDK/platform-tools/adb" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ]; then
        export ANDROID_SERIAL=$s; break 2
      fi
    done
    sleep 2
  done
fi
log "waiting for boot (${ANDROID_SERIAL:-single emulator})"
wait_boot
echo "booted: $(adb shell getprop ro.build.fingerprint | tr -d '\r')"
[ -n "${ANDROID_SERIAL:-}" ] && echo "export ANDROID_SERIAL=$ANDROID_SERIAL   # for the other scripts"

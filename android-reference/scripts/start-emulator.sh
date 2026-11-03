#!/bin/bash
# Boots the AVD with a writable /system (needed to install the verifier as a system app) and waits
# for the boot to finish, or fails within BOOT_TIMEOUT seconds. Set HEADLESS=1 to run without a
# window. The emulator keeps running in the background; stop it with `adb emu kill`. The serial
# of the started emulator is written to out/emulator.serial for the other scripts.
source "$(dirname "$0")/common.sh"
avd_exists || bash "$ROOT/scripts/create-avd.sh"
extra=()
[ "${HEADLESS:-0}" = 1 ] && extra+=(-no-window -no-audio -gpu swiftshader_indirect)
mkdir -p "$OUT"
rm -f "$OUT/emulator.serial"
# Diagnostics for CI logs: which emulator, and whether hardware acceleration is available.
"$SDK/emulator/emulator" -version 2>/dev/null | head -1 || true
"$SDK/emulator/emulator" -accel-check 2>/dev/null | head -3 || true
log "starting $AVD (log: $OUT/emulator.log)"
nohup "$SDK/emulator/emulator" -avd "$AVD" -writable-system -no-snapshot -no-boot-anim -no-metrics "${extra[@]}" \
  > "$OUT/emulator.log" 2>&1 &
EMULATOR_PID=$!
sleep 5
if [ -z "${ANDROID_SERIAL:-}" ]; then
  # Pick the serial of the emulator we just started, so other running emulators are left alone.
  for i in $(seq 1 60); do
    kill -0 "$EMULATOR_PID" 2>/dev/null || { echo "the emulator exited:" >&2; tail -30 "$OUT/emulator.log" >&2; exit 1; }
    for s in $("$SDK/platform-tools/adb" devices | awk '/^emulator-/{print $1}'); do
      if [ "$("$SDK/platform-tools/adb" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ]; then
        export ANDROID_SERIAL=$s; break 2
      fi
    done
    sleep 2
  done
  [ -n "${ANDROID_SERIAL:-}" ] || { echo "the emulator never registered with adb:" >&2; tail -30 "$OUT/emulator.log" >&2; exit 1; }
fi
log "waiting for boot ($ANDROID_SERIAL, up to $BOOT_TIMEOUT s)"
wait_boot
echo "$ANDROID_SERIAL" > "$OUT/emulator.serial"
echo "booted: $(adb shell getprop ro.build.fingerprint | tr -d '\r')"
echo "export ANDROID_SERIAL=$ANDROID_SERIAL   # for the other scripts"

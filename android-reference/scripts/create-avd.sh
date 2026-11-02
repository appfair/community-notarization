#!/bin/bash
# Installs the stock AOSP ("default", no Google apps) system image for this host's CPU and creates
# the AVD the other scripts use. Safe to re-run. Set API and ARCH to pick another image.
source "$(dirname "$0")/common.sh"
find_tool() {  # the SDK's own cmdline-tools first, then whatever is on PATH (CI images)
  for c in "$SDK/cmdline-tools/latest/bin/$1" "$SDK"/cmdline-tools/*/bin/"$1"; do
    [ -x "$c" ] && { echo "$c"; return; }
  done
  command -v "$1" || true
}
SDKMANAGER=$(find_tool sdkmanager)
AVDMANAGER=$(find_tool avdmanager)
if [ ! -d "$SDK/system-images/android-$API/default/$ARCH" ]; then
  [ -n "$SDKMANAGER" ] || { echo "install cmdline-tools into $SDK first (sdkmanager not found)"; exit 1; }
  log "installing $IMAGE (and platforms;android-$API, emulator) into $SDK"
  yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK" --install "$IMAGE" "platforms;android-$API" emulator >/dev/null
fi
if [ ! -d "$HOME/.android/avd/$AVD.avd" ]; then
  [ -n "$AVDMANAGER" ] || { echo "avdmanager not found"; exit 1; }
  log "creating AVD $AVD"
  echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d pixel_7 >/dev/null
fi
echo "AVD $AVD ready: $(grep image.sysdir.1 "$HOME/.android/avd/$AVD.avd/config.ini")"

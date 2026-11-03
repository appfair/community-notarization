#!/bin/bash
# Installs the stock AOSP ("default", no Google apps) system image for this host's CPU and creates
# the AVD the other scripts use. Safe to re-run. Set API and ARCH to pick another image.
source "$(dirname "$0")/common.sh"
SDKMANAGER=$(sdk_tool sdkmanager)
AVDMANAGER=$(sdk_tool avdmanager)
if [ ! -d "$SDK/system-images/android-$API/default/$ARCH" ]; then
  [ -n "$SDKMANAGER" ] || { echo "install cmdline-tools into $SDK first (sdkmanager not found)"; exit 1; }
  log "installing $IMAGE (and platforms;android-$API, emulator) into $SDK"
  yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK" --install "$IMAGE" "platforms;android-$API" emulator >/dev/null
fi
[ -n "$AVDMANAGER" ] || { echo "avdmanager not found in $SDK/cmdline-tools or on PATH"; exit 1; }
if avd_exists; then
  echo "AVD $AVD exists: $(avd_path)"
else
  log "creating AVD $AVD with $AVDMANAGER"
  echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d pixel_7 >/dev/null
  avd_exists || { echo "avdmanager did not create $AVD"; "$AVDMANAGER" list avd; exit 1; }
  echo "AVD $AVD created: $(avd_path)"
fi

# Shared by every script: locates the Android SDK, the build tools and the target emulator.
# Source it; do not run it.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
  if [ -n "$candidate" ] && [ -d "$candidate/platform-tools" ]; then SDK=$candidate; break; fi
done
: "${SDK:?Android SDK not found; set ANDROID_HOME}"
export ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK

API=${API:-36}
ARCH=${ARCH:-$(uname -m | sed -e 's/arm64/arm64-v8a/' -e 's/aarch64/arm64-v8a/')}
IMAGE="system-images;android-$API;default;$ARCH"
AVD=${AVD:-notarization-aosp$API}
PLATFORM="$SDK/platforms/android-$API/android.jar"
FRAMEWORK_AIDL="$SDK/platforms/android-$API/framework.aidl"
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
OUT=$ROOT/out
KEYS=$ROOT/keys
COORDINATOR_PKG=org.appfair.notarization
SAMPLE_PKG=org.appfair.sample.hello
# Short IDs (the last segment of each provider ID) name the override settings and log tags.
PROVIDER_IDS="adv scan repro report media"
OPTIONAL_PROVIDERS="org.appfair.notary.scan,org.appfair.notary.repro,org.appfair.notary.report,org.appfair.notary.media"
PROVIDER_PKGS="org.appfair.notary.adv org.appfair.notary.scan org.appfair.notary.repro org.appfair.notary.report org.appfair.notary.media"
# The HTTPS notary (scripts/notary-server.py) as seen from the emulator: 10.0.2.2 is the host.
SCAN_PORT=${SCAN_PORT:-8443}
SCAN_ENDPOINT=${SCAN_ENDPOINT:-https://10.0.2.2:$SCAN_PORT/v1/assess}
# How long to wait for an emulator to appear and finish booting.
BOOT_TIMEOUT=${BOOT_TIMEOUT:-600}

# adb, pinned to one emulator. Set ANDROID_SERIAL when more than one device is attached.
adb() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then "$SDK/platform-tools/adb" -s "$ANDROID_SERIAL" "$@"
  else "$SDK/platform-tools/adb" -e "$@"; fi
}

# The newest installed cmdline-tools binary of the given name (sdkmanager, avdmanager), by
# package revision, so that an old preinstalled "latest" does not shadow a newer version.
# Falls back to whatever is on PATH.
sdk_tool() {
  local best="" best_rev=0 dir rev
  for dir in "$SDK"/cmdline-tools/*/; do
    dir=${dir%/}
    [ -x "$dir/bin/$1" ] || continue
    rev=$(sed -n 's/^Pkg.Revision=\([0-9]*\).*/\1/p' "$dir/source.properties" 2>/dev/null | head -1)
    if [ "${rev:-0}" -ge "$best_rev" ]; then best="$dir/bin/$1"; best_rev=${rev:-0}; fi
  done
  [ -n "$best" ] && { echo "$best"; return; }
  command -v "$1" || true
}

avd_exists() {  # true when avdmanager knows the AVD, wherever it keeps it
  local m; m=$(sdk_tool avdmanager); [ -n "$m" ] || return 1
  "$m" list avd -c 2>/dev/null | tr -d '\r' | grep -qx "$AVD"
}

avd_path() {  # the AVD's directory as avdmanager reports it
  local m; m=$(sdk_tool avdmanager); [ -n "$m" ] || return 0
  "$m" list avd 2>/dev/null | tr -d '\r' | awk -v name="$AVD" '
    /^ *Name:/ { current = $2 } /^ *Path:/ { if (current == name) { print $2; exit } }'
}

# Waits for the pinned device to appear and finish booting, up to BOOT_TIMEOUT seconds, and
# fails loudly instead of hanging: a lost emulator must stop the pipeline, not stall it.
wait_boot() {
  local deadline=$(( $(date +%s) + BOOT_TIMEOUT ))
  while [ "$(adb get-state 2>/dev/null | tr -d '\r')" != "device" ] \
     || [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "no booted emulator (${ANDROID_SERIAL:-any}) after $BOOT_TIMEOUT s" >&2
      "$SDK/platform-tools/adb" devices >&2 || true
      [ -f "$OUT/emulator.log" ] && tail -30 "$OUT/emulator.log" >&2
      return 1
    fi
    sleep 2
  done
  sleep 2
}

# Installs an APK without going through the coordinator (setup only: providers and the
# coordinator's own helpers are installed this way, test subjects are not).
install_quietly() {
  adb shell settings put global verifier_verify_adb_installs 0
  adb install -r --no-incremental "$1" >/dev/null
  adb shell settings put global verifier_verify_adb_installs 1
}

log() { printf '\033[1m== %s\033[0m\n' "$*"; }

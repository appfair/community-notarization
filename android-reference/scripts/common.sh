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

# adb, pinned to one emulator. Set ANDROID_SERIAL when more than one device is attached.
adb() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then "$SDK/platform-tools/adb" -s "$ANDROID_SERIAL" "$@"
  else "$SDK/platform-tools/adb" -e "$@"; fi
}

wait_boot() {
  adb wait-for-device >/dev/null
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
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

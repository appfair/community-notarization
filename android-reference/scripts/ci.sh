#!/bin/bash
# The whole pipeline, as GitHub Actions runs it (.github/workflows/android-reference.yml) and as
# you can run it locally: host tests, build, boot a fresh AOSP emulator, install the coordinator
# and the notaries, start the HTTPS notary, run every scenario, stop everything.
# Expects an SDK with platform-tools, platforms;android-$API, build-tools, the emulator and
# system-images;android-$API;default;$ARCH (scripts/create-avd.sh installs the image when a
# sdkmanager is available). Exit status is the scenarios' status.
source "$(dirname "$0")/common.sh"
export HEADLESS=${HEADLESS:-1}
status=0
cleanup() {
  "$ROOT/scripts/notary-server.sh" stop >/dev/null 2>&1 || true
  adb emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT

log "host tests"
"$ROOT/scripts/test-host.sh"
log "build"
"$ROOT/scripts/build.sh"
log "emulator"
"$ROOT/scripts/create-avd.sh"
eval "$("$ROOT/scripts/start-emulator.sh" | tee /dev/stderr | sed -n 's/^export \(ANDROID_SERIAL=[^ ]*\).*/export \1/p')"
"$ROOT/scripts/install-coordinator.sh"
"$ROOT/scripts/install-providers.sh"
"$ROOT/scripts/notary-server.sh" start
log "scenarios"
"$ROOT/scripts/run-scenario.sh" all || status=$?
exit $status

#!/bin/bash
# The whole pipeline, as GitHub Actions runs it (.github/workflows/android-reference.yml) and as
# you can run it locally: host tests, build, boot a fresh AOSP emulator, install the coordinator
# and the notaries, start the HTTPS notary, run every scenario, stop everything.
# Expects an SDK with platform-tools, platforms;android-$API, build-tools, the emulator and
# system-images;android-$API;default;$ARCH (scripts/create-avd.sh installs the image when a
# sdkmanager is available). Exit status is the scenarios' status. Every script is run through
# bash so that checkout file modes do not matter.
source "$(dirname "$0")/common.sh"
export HEADLESS=${HEADLESS:-1}
S=$ROOT/scripts
status=0
cleanup() {
  bash "$S/notary-server.sh" stop >/dev/null 2>&1 || true
  adb emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT

log "host tests"
bash "$S/test-host.sh"
log "build"
bash "$S/build.sh"
log "emulator"
bash "$S/create-avd.sh"
eval "$(bash "$S/start-emulator.sh" | tee /dev/stderr | sed -n 's/^export \(ANDROID_SERIAL=[^ ]*\).*/export \1/p')"
bash "$S/install-coordinator.sh"
bash "$S/install-providers.sh"
bash "$S/notary-server.sh" start
log "scenarios"
bash "$S/run-scenario.sh" all || status=$?
exit $status

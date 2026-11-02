#!/bin/bash
# Installs the five example notary apps (without going through the coordinator, which is a
# setup step, not a test) and enables them. The developer-verification notary is required and
# active as soon as it is installed; the others are enabled here the way the settings screen
# would. adb installs are treated as standard installs so that scripted scenarios include the
# ADV notary (the specification exempts adb installs from ADV by default).
source "$(dirname "$0")/common.sh"
[ -f "$OUT/provider-adv.apk" ] || "$ROOT/scripts/build.sh"
wait_boot
for p in adv scan repro report media; do
  log "installing provider-$p.apk"
  install_quietly "$OUT/provider-$p.apk"
done
adb shell settings put global community_notarization_providers "$OPTIONAL_PROVIDERS"
adb shell settings put global community_notarization_adb_as_standard 1
adb shell pm clear $COORDINATOR_PKG >/dev/null   # forget earlier acceptances and evidence
adb shell pm grant $COORDINATOR_PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
log "installed notaries:"
adb shell pm list packages | tr -d '\r' | grep org.appfair.notary
echo "enabled: $(adb shell settings get global community_notarization_providers | tr -d '\r')"; echo "required: org.appfair.notary.adv"

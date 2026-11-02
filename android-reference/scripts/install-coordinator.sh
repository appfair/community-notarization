#!/bin/bash
# Installs the coordinator as a system app on the running emulator and reboots it, because
# PackageManagerService builds its list of required verifiers at boot.
source "$(dirname "$0")/common.sh"
APK=$OUT/CommunityNotarization.apk
[ -f "$APK" ] || "$ROOT/scripts/build.sh"
adb root >/dev/null 2>&1 || true; sleep 1
wait_boot
# The first remount on a fresh image only disables verity and asks for a reboot.
if adb remount 2>&1 | grep -qi 'reboot'; then
  log "verity disabled; rebooting once so /system becomes writable"
  adb reboot; sleep 3; wait_boot
  adb root >/dev/null 2>&1 || true; sleep 2
  adb remount 2>&1 | tail -1
fi
log "pushing $APK to /system/app/CommunityNotarization"
adb shell mkdir -p /system/app/CommunityNotarization
adb push "$APK" /system/app/CommunityNotarization/CommunityNotarization.apk >/dev/null
adb shell chmod 644 /system/app/CommunityNotarization/CommunityNotarization.apk
adb shell sync
log "rebooting so PackageManagerService picks up the verifier"
adb reboot; sleep 3; wait_boot
# SDK images ship with adb-install verification switched off; the on-device installer is always verified.
adb shell settings put global verifier_verify_adb_installs 1
adb shell pm grant $COORDINATOR_PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
log "installed:"
adb shell pm list packages -f $COORDINATOR_PKG
adb shell dumpsys package $COORDINATOR_PKG | grep -E 'PACKAGE_VERIFICATION_AGENT|flags=\[' | head -3

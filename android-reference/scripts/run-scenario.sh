#!/bin/bash
# Drives installs through PackageInstallerService, the coordinator and the five example notaries
# on the running emulator, pressing the dialog buttons itself so it can run unattended.
# Screenshots and logs land in out/scenarios/. Needs install-coordinator.sh, install-providers.sh
# and notary-server.sh start to have run.
#
#   run-scenario.sh <name>... | all      names: see ALL near the end
source "$(dirname "$0")/common.sh"
SHOTS=$OUT/scenarios; mkdir -p "$SHOTS"
[ -f "$OUT/sample.apk" ] || "$ROOT/scripts/build.sh"
failures=0
DIALOG_TITLE='Install Notarization Sample?'
ADV=org.appfair.notary.adv; SCAN=org.appfair.notary.scan; REPRO=org.appfair.notary.repro
REPORT=org.appfair.notary.report; MEDIA=org.appfair.notary.media

# ---------------------------------------------------------------- device helpers
shot() { sleep "${2:-1}"; adb exec-out screencap -p > "$SHOTS/$1.png"; echo "  screenshot $SHOTS/$1.png"; }
# The UI dump is captured into a variable before matching: with pipefail, grep -q closing the
# pipe early would make the whole pipeline fail even though the text was there.
ui_dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null | tr '>' '\n'; }
ui_has() {  # ui_has <text>: true when some node's text starts with the argument (case-insensitive)
  local dump; dump=$(ui_dump) || true
  grep -qiF "text=\"$1" <<< "$dump"
}
wait_for_text() {  # wait until some text appears on screen
  local want=$1 tries=${2:-30}
  for ((i = 0; i < tries; i++)); do
    if ui_has "$want"; then return 0; fi
    sleep 1
  done
  echo "  text '$want' never appeared"; return 1
}
tap_text() {  # tap the centre of the first visible node whose text is exactly the argument
  local want=$1 line b dump
  dump=$(ui_dump) || true
  line=$(grep -iF "text=\"$want\"" <<< "$dump" | head -1) || true
  [ -n "$line" ] || { echo "  no '$want' on screen"; return 1; }
  b=$(echo "$line" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  read -r x1 y1 x2 y2 <<< "$b"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
  echo "  tapped '$want'"
}
set_providers() { adb shell settings put global community_notarization_providers "\"$1\""; }
override() {  # override <short id> '<json>'
  adb shell settings put global "notary_override_$1" "'$2'"
}
clear_overrides() { for id in $PROVIDER_IDS; do adb shell settings delete global "notary_override_$id" >/dev/null; done; }
scan_mode() { "$ROOT/scripts/notary-server.sh" control "{\"mode\":\"$1\"}" >/dev/null; echo "  scan server mode: $1"; }
reset_device() {
  adb uninstall $SAMPLE_PKG >/dev/null 2>&1 || true
  adb shell pm clear $COORDINATOR_PKG >/dev/null      # evidence, acceptances, notifications
  adb shell pm grant $COORDINATOR_PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  clear_overrides; scan_mode scan >/dev/null
  set_providers "$OPTIONAL_PROVIDERS"
  adb shell settings put global community_notarization_adb_as_standard 1
  adb shell settings put global verifier_verify_adb_installs 1
  adb shell cmd statusbar collapse >/dev/null 2>&1; adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true; adb shell input keyevent KEYCODE_HOME; sleep 1
  adb logcat -c
}
start_install() {  # start_install <name> [apk] [adb install flag]; blocks until the verifier answers
  local name=$1 apk=${2:-$OUT/sample.apk} flag=${3:---no-incremental}
  adb install "$flag" -r "$apk" > "$SHOTS/$name-adb.txt" 2>&1 &
  INSTALL_PID=$!
}
install_result() {  # waits for the background adb install (up to 120 s) and prints its last line
  for ((i = 0; i < 120; i++)); do kill -0 "$INSTALL_PID" 2>/dev/null || break; sleep 1; done
  if kill -0 "$INSTALL_PID" 2>/dev/null; then kill "$INSTALL_PID" 2>/dev/null; echo "still blocked after 120 s"; return 0; fi
  { tr -d '\r' < "$SHOTS/$1-adb.txt" | grep -v -e '^Performing' -e '^$' | tail -1; } || true
}
save_log() {
  adb logcat -d -s 'CommunityNotarization:*' 'Notary.adv:*' 'Notary.repro:*' 'Notary.report:*' 'Notary.media:*' \
    | tr -d '\r' | sed 's/^[0-9-]* //' > "$SHOTS/$1.log"
  echo "  log $SHOTS/$1.log ($(wc -l < "$SHOTS/$1.log" | tr -d ' ') lines)"
}
check() {  # check <description> <condition...>
  local what=$1; shift
  if "$@"; then echo "  ok   $what"; else echo "  FAIL $what"; failures=$((failures + 1)); fi
}
installed() { adb shell pm list packages $SAMPLE_PKG | tr -d '\r' | grep -q "package:$SAMPLE_PKG"; }
not_installed() { ! installed; }
log_has() { grep -qF -- "$1" "$SHOTS/$2.log"; }
log_lacks() { ! grep -qF -- "$1" "$SHOTS/$2.log"; }
rejected() { echo "$1" | grep -q 'INSTALL_FAILED_VERIFICATION_FAILURE'; }
assessed() { log_has "$2 -> $3" "$1"; }   # assessed <name> <provider id> <VERDICT>
finish_auto() {  # finish_auto <name> <ALLOW|BLOCK>: waits for the install, checks the silent outcome
  local name=$1 outcome=$2 result
  result=$(install_result "$name"); echo "  adb install: $result"
  if [ "$outcome" = ALLOW ]; then
    check "adb install succeeded" [ "$result" = Success ]; check "sample is installed" installed
  else
    check "adb install was rejected" rejected "$result"; check "sample is not installed" not_installed
  fi
  adb shell cmd statusbar expand-notifications; shot "$name-notification" 1.5; adb shell cmd statusbar collapse
  save_log "$name"
  check "policy decided $outcome" log_has "policy=$outcome" "$name"
  check "no review dialog was shown" log_lacks "asking the user" "$name"
}
finish_review() {  # finish_review <name> <Install|Cancel> [keep]: answers the dialog and checks the outcome
  local name=$1 button=$2 keep=${3:-} result
  check "review dialog appeared" wait_for_text "$DIALOG_TITLE" 40
  shot "$name-dialog" 1.5
  tap_text "$button" || true
  result=$(install_result "$name"); echo "  adb install: $result"
  if [ "$button" = Install ]; then
    check "adb install succeeded" [ "$result" = Success ]; check "sample is installed" installed
  else
    check "adb install was rejected" rejected "$result"
    [ -n "$keep" ] || check "sample is not installed" not_installed
  fi
  save_log "$name"
  check "policy required review" log_has "policy=REVIEW" "$name"
  check "user choice was reported with verifyPendingInstall" log_has "user chose $button" "$name"
  check "system acknowledged with PACKAGE_VERIFIED" log_has "PACKAGE_VERIFIED id=" "$name"
}

# ---------------------------------------------------------------- scenarios
scenario_approve() {  # every notary approves v1 -> installed silently
  log "Scenario approve: all five notaries approve -> installed without a prompt"
  reset_device
  start_install approve
  finish_auto approve ALLOW
  check "coordinator received PACKAGE_NEEDS_VERIFICATION" log_has "PACKAGE_NEEDS_VERIFICATION id=" approve
  check "five notaries were active" log_has "active notaries=[$ADV, $MEDIA, $REPORT, $REPRO, $SCAN]" approve
  for id in $ADV $SCAN $REPRO $REPORT $MEDIA; do check "$id approved" assessed approve $id APPROVE; done
  check "ADV answered live over Binder" log_has "$ADV -> APPROVE (This package and signing certificate are registered" approve
  check "VirusTotality answered live over HTTPS" log_has "$SCAN -> APPROVE (The example malware scan found no threats.; current response" approve
  check "rebuilder compared the APK through the descriptor" log_has "matches the rebuild byte for byte" approve
  check "consumer review came as a signed attestation" log_has "$REPORT -> APPROVE (The example review found consumer-friendly practices.; signed attestation" approve
  check "age rating came from the on-device database" log_has "$MEDIA -> APPROVE (The example review found this app suitable for all ages.; on-device result" approve
}

scenario_review() {  # v2: age 16+ advice and no rebuild -> review -> Install
  log "Scenario review: version 2 gets age-16 advice and no rebuild -> review dialog -> Install"
  reset_device
  start_install review "$OUT/sample-v2.apk"
  finish_review review Install
  check "age notary advised 16+" assessed review $MEDIA "ADVISORY(16+)"
  check "rebuilder abstained" assessed review $REPRO ABSTAIN
}

scenario_cancel() {
  log "Scenario cancel: the same review -> Cancel"
  reset_device
  start_install cancel "$OUT/sample-v2.apk"
  finish_review cancel Cancel
}

scenario_deny() {  # every notary denies or revokes -> blocked silently, reasons in the notification
  log "Scenario deny: certificate mismatch, malware, failed rebuild, revoked review, unsuitable -> blocked"
  reset_device
  scan_mode disapprove
  override repro '{"verdict":"deny","reason":"The staged file differs from the public rebuild."}'
  override report '{"verdict":"revoke","reason":"The example review withdrew its approval."}'
  override media '{"verdict":"deny","reason":"The example review flagged risks to minors."}'
  start_install deny "$OUT/sample-mismatch.apk"
  finish_auto deny BLOCK
  check "ADV reported a certificate mismatch" log_has "$ADV -> DISAPPROVE (The package is registered with a different signing certificate" deny
  check "scan reported malware" assessed deny $SCAN DISAPPROVE
  check "review was revoked" assessed deny $REPORT REVOKE
}

scenario_exempt() {  # adb install, ADV exempt, nothing else enabled -> nothing to check -> allowed
  log "Scenario exempt: no optional notaries, adb exempt from ADV -> no checks remain -> installed"
  reset_device
  set_providers ""; adb shell settings put global community_notarization_adb_as_standard 0
  start_install exempt
  local result; result=$(install_result exempt); echo "  adb install: $result"
  check "adb install succeeded" [ "$result" = Success ]
  save_log exempt
  check "ADV was exempt for the adb install" log_has "notary is exempt" exempt
  check "the coordinator allowed with no checks remaining" log_has "no checks remain" exempt
  check "no review dialog was shown" log_lacks "asking the user" exempt
}

scenario_advonly() {  # only ADV participates: the ADV-only baseline, no community review
  log "Scenario advonly: registered developer with no optional notaries -> installed; mismatch -> blocked"
  reset_device
  set_providers ""
  start_install advonly
  finish_auto advonly ALLOW
  check "ADV-only baseline applied" log_has "ADV-only baseline" advonly
  adb uninstall $SAMPLE_PKG >/dev/null 2>&1 || true; adb logcat -c
  start_install advonly2 "$OUT/sample-mismatch.apk"
  finish_auto advonly2 BLOCK
  check "mismatch was rejected by the baseline" log_has "requires Google's advanced flow" advonly2
}

scenario_timeout() {  # the HTTPS notary never answers in time
  log "Scenario timeout: VirusTotality does not answer within 8 s -> review"
  reset_device
  scan_mode timeout
  start_install timeout
  finish_review timeout Cancel
  check "scan timed out" assessed timeout $SCAN TIMEOUT
  check "others answered" assessed timeout $ADV APPROVE
}

scenario_cache() {  # a valid earlier answer stands in for a timed-out one
  log "Scenario cache: approve once, then the scan times out -> cached evidence -> installed silently"
  reset_device
  start_install cache1; finish_auto cache1 ALLOW
  adb uninstall $SAMPLE_PKG >/dev/null 2>&1 || true; adb logcat -c
  scan_mode timeout
  start_install cache
  finish_auto cache ALLOW
  check "cached scan evidence was used" log_has "$SCAN: using cached evidence instead (APPROVE)" cache
  check "cached source was reported with the live problem" log_has "$SCAN -> APPROVE (The example malware scan found no threats.; cached assessment · timed out" cache
}

scenario_invalid() {  # a bad signature invalidates, and cached evidence does not rescue it
  log "Scenario invalid: scan answers with a foreign signature, then for another app -> invalid, no cache"
  reset_device
  start_install invalid1; finish_auto invalid1 ALLOW
  adb uninstall $SAMPLE_PKG >/dev/null 2>&1 || true; adb logcat -c
  scan_mode invalid-signature
  start_install invalid
  finish_review invalid Cancel
  check "scan response was invalid" assessed invalid $SCAN INVALID
  check "signature failure was logged" log_has "signature did not verify" invalid
  check "cache was not used" log_lacks "using cached evidence" invalid
  adb logcat -c; adb uninstall $SAMPLE_PKG >/dev/null 2>&1 || true
  scan_mode invalid-identity
  start_install invalid2
  finish_review invalid2 Cancel
  check "wrong-app response was invalid" assessed invalid2 $SCAN INVALID
  check "identity mismatch was logged" log_has "package name mismatch" invalid2
}

scenario_crash() {
  log "Scenario crash: the rebuilder's process dies while assessing -> service stopped -> review"
  reset_device
  override repro '{"crash":true}'
  start_install crash
  finish_review crash Cancel
  check "rebuilder crash was recorded" assessed crash $REPRO CRASHED
}

scenario_offline() {  # online notaries unreachable; local ones still answer
  log "Scenario offline: HTTPS server down, ADV and rebuilder offline -> unavailable; local notaries answer"
  reset_device
  "$ROOT/scripts/notary-server.sh" stop >/dev/null
  override adv '{"offline":true}'; override repro '{"offline":true}'
  start_install offline
  finish_review offline Cancel
  check "scan unreachable" assessed offline $SCAN UNAVAILABLE
  check "ADV reported it could not check" assessed offline $ADV UNAVAILABLE
  check "ADV did not count as a finding" log_has "not a finding that the developer is unverified" offline
  check "rebuilder reported its index unreachable" assessed offline $REPRO UNAVAILABLE
  check "review attestation still answered" assessed offline $REPORT APPROVE
  check "age rating still answered" assessed offline $MEDIA APPROVE
  "$ROOT/scripts/notary-server.sh" start >/dev/null
}

scenario_disabled() {  # a declared but disabled notary is not consulted
  log "Scenario disabled: only the age notary enabled (plus required ADV) -> the others are not asked"
  reset_device
  set_providers "$MEDIA"
  start_install disabled
  finish_auto disabled ALLOW
  check "only ADV and the age notary were active" log_has "active notaries=[$ADV, $MEDIA]" disabled
  check "scan was not asked" log_lacks "$SCAN ->" disabled
}

scenario_identity() {  # a provider update with a new signer is not trusted until re-enabled
  log "Scenario identity: the review provider is replaced by one with another signing key -> invalid"
  reset_device
  start_install identity1; finish_auto identity1 ALLOW     # binds the accepted identities
  adb uninstall $SAMPLE_PKG >/dev/null 2>&1 || true
  adb uninstall $REPORT >/dev/null 2>&1 || true
  install_quietly "$OUT/provider-report-otherkey.apk"; adb logcat -c
  start_install identity
  finish_review identity Cancel
  check "replaced provider was rejected as invalid" assessed identity $REPORT INVALID
  check "the reason names the identity change" log_has "signing identity changed" identity
  adb uninstall $REPORT >/dev/null 2>&1 || true
  install_quietly "$OUT/provider-report.apk"
}

scenario_update() {  # a new APK is a new check: evidence for v1 is not reused for v2
  log "Scenario update: version 1 cached, then version 2 -> fresh check of the new file"
  reset_device
  start_install update1; finish_auto update1 ALLOW
  adb logcat -c
  start_install update "$OUT/sample-v2.apk"
  finish_review update Cancel keep
  check "no cached evidence applied to the new file" log_lacks "using cached evidence" update
  check "the new file was assessed afresh" log_has "$SCAN -> APPROVE (The example malware scan found no threats.; current response" update
  check "version 1 is still installed" installed
}

scenario_incremental() {  # a streaming install cannot be authenticated or reviewed: rejected with a report
  log "Scenario incremental: streaming install -> rejected before the short deadline, with a report"
  if ! adb shell getprop ro.incremental.enable | tr -d '\r' | grep -qE '^(1|yes|true)$'; then
    echo "  skipped: this image does not support incremental installs"; return 0
  fi
  reset_device
  start_install incremental "$OUT/sample.apk" --incremental
  local result; result=$(install_result incremental); echo "  adb install: $result"
  check "streaming install was rejected" rejected "$result"
  save_log incremental
  check "coordinator saw a streaming install" log_has "streaming install" incremental
  check "no notary was asked and no file was hashed" log_lacks "active notaries=" incremental
  check "no review dialog was shown" log_lacks "asking the user" incremental
  adb shell cmd statusbar expand-notifications; shot incremental-notification 1.5; adb shell cmd statusbar collapse
}

scenario_settings() {  # the notary settings screen
  log "Scenario settings: the notaries settings screen lists every declared notary"
  reset_device
  adb shell am start -n $COORDINATOR_PKG/.SettingsActivity >/dev/null
  check "settings screen opened" wait_for_text "Common Sensical Media" 15
  shot settings-screen 1.5
  check "required notary is listed" ui_has "Android Developer Verification  (required)"
  check "HTTPS route is described" ui_has "Has this app been scanned for malware?"
  check "APK access is described" ui_has "Can this app be rebuilt from its public source?"
  adb shell input keyevent KEYCODE_HOME
}

ALL="approve review cancel deny exempt advonly timeout cache invalid crash offline disabled identity update incremental settings"
wait_boot
"$ROOT/scripts/notary-server.sh" status >/dev/null 2>&1 || "$ROOT/scripts/notary-server.sh" start
which=${*:-approve}
[ "$which" = all ] && which=$ALL
for s in $which; do
  case " $ALL " in *" $s "*) "scenario_$s" ;; *) echo "unknown scenario $s"; exit 2 ;; esac
done
reset_device >/dev/null 2>&1
echo
if [ $failures = 0 ]; then echo "all scenario checks passed"; else echo "$failures scenario check(s) failed"; exit 1; fi

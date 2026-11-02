#!/bin/bash
# Host-side checks that need no device: the policy rule, the attestation signatures, and the
# agreement of the Java and Python canonical bytes. Compiles against the SDK stubs and runs on
# the host JVM.
source "$(dirname "$0")/common.sh"
work=$OUT/host-test; rm -rf "$work"; mkdir -p "$work"
javac --release 17 -nowarn -Xlint:-options -cp "$PLATFORM" -d "$work" \
  "$ROOT"/coordinator/src/org/appfair/notarization/{Policy,Assessment}.java \
  "$ROOT"/shared/src/org/appfair/notarization/provider/{Attestation,NotaryContract,NotaryAssessment,NotaryRequest}.java \
  "$ROOT"/coordinator/test/*.java
status=0
java -cp "$work:$PLATFORM" PolicyTest || status=1
java -cp "$work:$PLATFORM" AttestationTest "$work/canonical-java.bin" || status=1

# The HTTPS notary signs bytes produced by Python; they must equal the Java bytes exactly.
python3 - "$work/canonical-python.bin" <<'EOF'
import json, sys
a = {
    "protocolVersion": 1, "providerId": "org.example.consumerreporting",
    "app": {"packageName": "org.example.freecalculator", "versionCode": "1", "apkSha256": "0" * 64,
            "signingCertificateSha256": ["1" * 64]},
    "verdict": "approve", "reasonCode": "NO_CONCERNS",
    "explanation": "Review found no concerns: \"quoted\", back\\slash, tab\t, ünïcödé.",
    "issuedAt": "2026-11-02T18:00:00Z", "expiresAt": "2026-11-03T18:00:00Z",
}
canonical = "community-notarization/attestation/1\n" + json.dumps(a, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
open(sys.argv[1], "wb").write(canonical.encode("utf-8"))
EOF
if cmp -s "$work/canonical-java.bin" "$work/canonical-python.bin"; then
  echo "  ok   Java and Python canonical bytes agree ($(wc -c < "$work/canonical-java.bin" | tr -d ' ') bytes)"
else
  echo "  FAIL Java and Python canonical bytes differ:"; diff <(cat "$work/canonical-java.bin") <(cat "$work/canonical-python.bin") || true
  status=1
fi
exit $status

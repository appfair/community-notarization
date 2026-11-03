#!/bin/bash
# Builds everything with the bare SDK tools (aidl, aapt2, javac, d8, zipalign, apksigner) and
# openssl/keytool for keys. No Gradle, no downloads beyond the AOSP platform test key. Output:
#
#   out/CommunityNotarization.apk     the coordinator, platform-signed, for /system/app
#   out/provider-{adv,scan,repro,report,media}.apk   the five example notary apps
#   out/provider-report-otherkey.apk  the review notary signed with another key (identity change)
#   out/sample.apk                    org.appfair.sample.hello v1, the install under test
#   out/sample-v2.apk                 the same app, version 2 (a different file)
#   out/sample-mismatch.apk           version 1 signed with another developer key
#
# Keys land in keys/ (gitignored) and are reused on later builds, so digests stay stable.
source "$(dirname "$0")/common.sh"
[ -f "$PLATFORM" ] || { echo "missing $PLATFORM; install platforms;android-$API"; exit 1; }
[ -n "$BT" ] || { echo "no build-tools in $SDK"; exit 1; }
"$ROOT/scripts/fetch-platform-key.sh" >/dev/null
mkdir -p "$OUT" "$KEYS"

# ---------------------------------------------------------------- keys
gen_rsa() {  # gen_rsa <file.jks> <alias> <dname>
  [ -f "$1" ] || keytool -genkeypair -keystore "$1" -storepass pass123 -keypass pass123 -alias "$2" \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "$3" >/dev/null 2>&1
}
gen_ec() {  # gen_ec <name>: keys/<name>.pem (private), keys/<name>.pkcs8.b64, keys/<name>.pub.b64
  if [ ! -f "$KEYS/$1.pem" ]; then
    openssl ecparam -genkey -name prime256v1 -noout -out "$KEYS/$1.pem" 2>/dev/null
    openssl pkcs8 -topk8 -nocrypt -in "$KEYS/$1.pem" -outform DER 2>/dev/null | base64 | tr -d '\n' > "$KEYS/$1.pkcs8.b64"
    openssl ec -in "$KEYS/$1.pem" -pubout -outform DER 2>/dev/null | base64 | tr -d '\n' > "$KEYS/$1.pub.b64"
  fi
}
gen_rsa "$KEYS/sample.jks" sample "CN=Sample developer, O=App Fair Project"
gen_rsa "$KEYS/other.jks" other "CN=Another developer, O=Somebody Else"
gen_ec notary-report       # the review notary's attestation key
gen_ec notary-scan         # the HTTPS notary's attestation key
if [ ! -f "$KEYS/scan-tls.crt" ]; then   # the test server's self-signed TLS certificate
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
    -subj "/CN=notary-scan.test" -keyout "$KEYS/scan-tls.key" -out "$KEYS/scan-tls.crt" >/dev/null 2>&1
fi
SCAN_TLS_PIN=$(openssl x509 -in "$KEYS/scan-tls.crt" -outform DER | shasum -a 256 | cut -d' ' -f1)
SCAN_PUBKEY=$(cat "$KEYS/notary-scan.pub.b64")
REPORT_PUBKEY=$(cat "$KEYS/notary-report.pub.b64")

# ---------------------------------------------------------------- shared contract
SHARED=$OUT/work-shared
rm -rf "$SHARED"; mkdir -p "$SHARED/gen" "$SHARED/classes"
for f in $(find "$ROOT/shared/aidl" -name '*.aidl'); do
  "$BT/aidl" -I "$ROOT/shared/aidl" -p "$FRAMEWORK_AIDL" -o "$SHARED/gen" "$f"
done
javac --release 17 -nowarn -Xlint:-options -cp "$PLATFORM" -d "$SHARED/classes" \
  $(find "$ROOT/shared/src" "$SHARED/gen" -name '*.java')

# ---------------------------------------------------------------- one APK
# build_apk <srcdir> <name> [extra aapt2 link args...] -- <apksigner args...>
# Files named *.in under srcdir are copied with @TOKEN@ substitutions applied (see subst()).
subst() {
  sed -e "s|@SAMPLE_SIGNER@|${SAMPLE_SIGNER:-}|g" -e "s|@SAMPLE_V1_SHA256@|${SAMPLE_V1_SHA256:-}|g" \
      -e "s|@SCAN_ENDPOINT@|$SCAN_ENDPOINT|g" -e "s|@SCAN_PUBKEY@|$SCAN_PUBKEY|g" \
      -e "s|@SCAN_TLS_PIN@|$SCAN_TLS_PIN|g" -e "s|@REPORT_PUBKEY@|$REPORT_PUBKEY|g" "$1"
}
EXTRA_ASSETS=()
build_apk() {
  local src=$1 name=$2; shift 2
  local link=(--no-version-vectors); while [ "$1" != "--" ]; do link+=("$1"); shift; done; shift
  local work=$OUT/work-$name
  rm -rf "$work"; mkdir -p "$work/gen" "$work/classes" "$work/dex" "$work/src"
  cp -R "$src"/. "$work/src/"
  for f in $(find "$work/src" -name '*.in'); do subst "$f" > "${f%.in}"; rm "$f"; done
  for extra in "${EXTRA_ASSETS[@]:-}"; do   # name=path pairs added to the APK's assets, never to the source tree
    [ -n "$extra" ] || continue
    mkdir -p "$work/src/assets"; cp "${extra#*=}" "$work/src/assets/${extra%%=*}"
  done
  [ -d "$work/src/assets" ] && link+=(-A "$work/src/assets")
  "$BT/aapt2" compile --dir "$work/src/res" -o "$work/res.zip"
  "$BT/aapt2" link -o "$work/unsigned.apk" -I "$PLATFORM" --manifest "$work/src/AndroidManifest.xml" \
      --java "$work/gen" "${link[@]}" "$work/res.zip"
  javac --release 17 -nowarn -Xlint:-options -cp "$PLATFORM:$SHARED/classes" -d "$work/classes" \
      $(find "$work/src/src" "$work/gen" -name "*.java" 2>/dev/null)
  "$BT/d8" --release --lib "$PLATFORM" --min-api 33 --output "$work/dex" \
      $(find "$work/classes" "$SHARED/classes" -name '*.class')
  (cd "$work/dex" && zip -q "$work/unsigned.apk" classes.dex)
  "$BT/zipalign" -f -p 4 "$work/unsigned.apk" "$work/aligned.apk"
  "$BT/apksigner" sign "$@" --out "$OUT/$name.apk" "$work/aligned.apk"
  echo "built $OUT/$name.apk"
}
SAMPLE_SIGN=(--ks "$KEYS/sample.jks" --ks-pass pass:pass123 --key-pass pass:pass123)
OTHER_SIGN=(--ks "$KEYS/other.jks" --ks-pass pass:pass123 --key-pass pass:pass123)
PLATFORM_SIGN=(--key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem")

# ---------------------------------------------------------------- the apps
build_apk "$ROOT/coordinator" CommunityNotarization -- "${PLATFORM_SIGN[@]}"

build_apk "$ROOT/samples" sample -- "${SAMPLE_SIGN[@]}"
build_apk "$ROOT/samples" sample-v2 --replace-version --version-code 2 --version-name 2.0 -- "${SAMPLE_SIGN[@]}"
build_apk "$ROOT/samples" sample-mismatch -- "${OTHER_SIGN[@]}"
SAMPLE_SIGNER=$("$BT/apksigner" verify --print-certs "$OUT/sample.apk" | sed -n 's/.*SHA-256 digest: //p' | head -1)
SAMPLE_V1_SHA256=$(shasum -a 256 "$OUT/sample.apk" | cut -d' ' -f1)

# Provider apps are ordinary apps signed by their own developers; here all with the sample key.
EXTRA_ASSETS=()
build_apk "$ROOT/providers/adv" provider-adv -- "${SAMPLE_SIGN[@]}"
build_apk "$ROOT/providers/scan" provider-scan -- "${SAMPLE_SIGN[@]}"
build_apk "$ROOT/providers/repro" provider-repro -- "${SAMPLE_SIGN[@]}"
EXTRA_ASSETS=("signing.key=$KEYS/notary-report.pkcs8.b64")   # the review notary's attestation key
build_apk "$ROOT/providers/report" provider-report -- "${SAMPLE_SIGN[@]}"
build_apk "$ROOT/providers/report" provider-report-otherkey -- "${OTHER_SIGN[@]}"
EXTRA_ASSETS=()
build_apk "$ROOT/providers/media" provider-media -- "${SAMPLE_SIGN[@]}"

cat > "$OUT/build-info.txt" <<EOF
sample_signer_sha256=$SAMPLE_SIGNER
sample_v1_sha256=$SAMPLE_V1_SHA256
sample_v2_sha256=$(shasum -a 256 "$OUT/sample-v2.apk" | cut -d' ' -f1)
scan_endpoint=$SCAN_ENDPOINT
scan_tls_pin=$SCAN_TLS_PIN
EOF
cat "$OUT/build-info.txt"

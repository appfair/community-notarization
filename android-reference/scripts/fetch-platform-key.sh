#!/bin/bash
# Fetches the public AOSP *test* platform signing key (the one every SDK "default" emulator image
# is built with) into keys/. A verifier must be signed with the platform key of the image it runs
# on, or the system will not grant PACKAGE_VERIFICATION_AGENT. Set AOSP_KEYS to copy them from a
# local AOSP checkout (build/make/target/product/security) instead of downloading.
source "$(dirname "$0")/common.sh"
mkdir -p "$ROOT/keys"
for f in platform.pk8 platform.x509.pem; do
  dest=$ROOT/keys/$f
  [ -s "$dest" ] && continue
  if [ -n "${AOSP_KEYS:-}" ] && [ -f "$AOSP_KEYS/$f" ]; then
    cp "$AOSP_KEYS/$f" "$dest"
  else
    curl -fsSL "https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/$f?format=TEXT" \
      | base64 -d > "$dest"
  fi
  echo "fetched $dest"
done
openssl x509 -in "$ROOT/keys/platform.x509.pem" -noout -subject -fingerprint -sha256

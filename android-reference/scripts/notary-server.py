#!/usr/bin/env python3
"""The HTTPS notary endpoint of the VirusTotality example provider.

Implements the specification's HTTPS route with nothing but the Python standard library and
the openssl command: a POST of the request fields comes in, a portable attestation signed
with the provider's ECDSA P-256 key goes out, wrapped in a live-response envelope bound to
the request ID. The canonical bytes are the same ones shared/.../Attestation.java produces
(scripts/test-host.sh checks that).

Request and response follow the specification's examples (revision 9):

  {"protocolVersion":1,"requestId":"…","app":{"packageName":"…","versionCode":"1",
   "apkSha256":"…","signingCertificateSha256":["…"]},"language":"en"}

  {"protocolVersion":1,"requestId":"…","providerId":"…","app":{…},"verdict":"approve",
   "reasonCode":"NO_THREATS","explanation":"…","issuedAt":"…Z","expiresAt":"…Z",
   "attestationSignature":"base64","envelopeSignature":"base64"}

A /control endpoint lets scripted scenarios change its behaviour (scan, disapprove, abstain,
timeout, invalid-signature, invalid-identity, redirect, error, oversize).

  notary-server.py --port 8443 --cert keys/scan-tls.crt --key keys/scan-tls.key \
                   --sign keys/notary-scan.pem [--provider org.appfair.notary.scan]
"""
import argparse
import base64
import http.server
import json
import os
import ssl
import subprocess
import sys
import tempfile
import threading
import time

ATTESTATION_HEADER = "community-notarization/attestation/1\n"
ENVELOPE_HEADER = "community-notarization/envelope/1\n"
LIFETIME_S = 24 * 60 * 60

STATE = {"mode": "scan", "delay_ms": 0, "deny": [], "requests": 0}
LOCK = threading.Lock()
ARGS = None
BOGUS_KEY = None


def canonical_json(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def iso(t):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))


def sign(data: bytes, key_path: str) -> str:
    with tempfile.NamedTemporaryFile(delete=False) as f:
        f.write(data)
        path = f.name
    try:
        der = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key_path, path],
                             check=True, capture_output=True).stdout
    finally:
        os.unlink(path)
    return base64.b64encode(der).decode()


def attest(req, verdict, reason_code, explanation, key_path, package=None):
    now = int(time.time())
    app = dict(req["app"])
    if package:
        app["packageName"] = package
    a = {
        "protocolVersion": 1,
        "providerId": ARGS.provider,
        "app": app,
        "verdict": verdict,
        "reasonCode": reason_code,
        "explanation": explanation,
        "issuedAt": iso(now),
        "expiresAt": iso(now + LIFETIME_S),
    }
    attestation_signature = sign((ATTESTATION_HEADER + canonical_json(a)).encode("utf-8"), key_path)
    envelope = {"requestId": req["requestId"], "attestationSignature": attestation_signature}
    envelope_signature = sign((ENVELOPE_HEADER + canonical_json(envelope)).encode("utf-8"), key_path)
    out = dict(a)
    out["requestId"] = req["requestId"]
    out["attestationSignature"] = attestation_signature
    out["envelopeSignature"] = envelope_signature
    return out


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

    def _json(self, code, obj, extra_headers=None):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra_headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _read(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode("utf-8") or "{}")

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"ok": True})
        elif self.path == "/control":
            with LOCK:
                self._json(200, dict(STATE))
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/control":
            change = self._read()
            with LOCK:
                STATE.update({k: v for k, v in change.items() if k in ("mode", "delay_ms", "deny")})
                self._json(200, dict(STATE))
            return
        if self.path != "/v1/assess":
            self._json(404, {"error": "not found"})
            return
        req = self._read()
        with LOCK:
            STATE["requests"] += 1
            mode, delay, deny = STATE["mode"], STATE["delay_ms"], list(STATE["deny"])
        app = req.get("app") or {}
        self.log_message("assess %s:%s %s mode=%s", app.get("packageName"), app.get("versionCode"),
                         (app.get("apkSha256") or "")[:16], mode)
        if req.get("protocolVersion") != 1 or "requestId" not in req \
                or not all(k in app for k in ("packageName", "versionCode", "apkSha256", "signingCertificateSha256")):
            self._json(400, {"error": "bad request"})
            return
        if delay:
            time.sleep(delay / 1000.0)
        if mode == "timeout":
            time.sleep(15)
            self._json(200, {"error": "too late"})
            return
        if mode == "error":
            self._json(503, {"error": "scanner unavailable"})
            return
        if mode == "redirect":
            self._json(302, {"moved": True}, {"Location": "https://elsewhere.example/v1/assess"})
            return
        if mode == "oversize":
            self._json(200, {"padding": "x" * (70 * 1024)})
            return
        clean = ("approve", "NO_THREATS", "The example malware scan found no threats.")
        if mode == "disapprove" or app["apkSha256"] in deny:
            out = attest(req, "deny", "MALWARE", "The example scan flagged credential-stealing malware.", ARGS.sign)
        elif mode == "abstain":
            out = attest(req, "abstain", "UNSCANNED", "This file has not been scanned yet.", ARGS.sign)
        elif mode == "invalid-signature":
            out = attest(req, *clean, BOGUS_KEY)
        elif mode == "invalid-identity":
            out = attest(req, *clean, ARGS.sign, package="com.example.other")
        else:
            out = attest(req, *clean, ARGS.sign)
        self._json(200, out)


def main():
    global ARGS, BOGUS_KEY
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8443)
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--cert", required=True)
    p.add_argument("--key", required=True)
    p.add_argument("--sign", required=True, help="the provider's EC private key (PEM)")
    p.add_argument("--provider", default="org.appfair.notary.scan")
    ARGS = p.parse_args()
    bogus = tempfile.NamedTemporaryFile(delete=False, suffix=".pem")
    bogus.close()
    subprocess.run(["openssl", "ecparam", "-genkey", "-name", "prime256v1", "-noout", "-out", bogus.name],
                   check=True, capture_output=True)
    BOGUS_KEY = bogus.name
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(ARGS.cert, ARGS.key)
    server = http.server.ThreadingHTTPServer((ARGS.bind, ARGS.port), Handler)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    sys.stderr.write("notary server %s listening on https://%s:%d\n" % (ARGS.provider, ARGS.bind, ARGS.port))
    try:
        server.serve_forever()
    finally:
        os.unlink(BOGUS_KEY)


if __name__ == "__main__":
    main()

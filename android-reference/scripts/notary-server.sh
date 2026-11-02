#!/bin/bash
# Starts, stops or queries the HTTPS notary test server (scripts/notary-server.py) on the
# host, where the emulator reaches it as 10.0.2.2. Keys come from scripts/build.sh.
#
#   notary-server.sh start | stop | status | control '{"mode":"timeout"}'
source "$(dirname "$0")/common.sh"
PID=$OUT/notary-server.pid
LOG=$OUT/notary-server.log
curl_control() { curl -sk --max-time 5 "https://127.0.0.1:$SCAN_PORT/control" "$@"; }
case ${1:-status} in
  start)
    [ -f "$KEYS/scan-tls.crt" ] || "$ROOT/scripts/build.sh" >/dev/null
    if [ -f "$PID" ] && kill -0 "$(cat "$PID")" 2>/dev/null; then echo "already running (pid $(cat "$PID"))"; exit 0; fi
    nohup python3 "$ROOT/scripts/notary-server.py" --port "$SCAN_PORT" --cert "$KEYS/scan-tls.crt" \
      --key "$KEYS/scan-tls.key" --sign "$KEYS/notary-scan.pem" > "$LOG" 2>&1 &
    echo $! > "$PID"
    for i in $(seq 1 20); do curl -sk --max-time 2 "https://127.0.0.1:$SCAN_PORT/health" >/dev/null 2>&1 && break; sleep 0.5; done
    echo "notary server started (pid $(cat "$PID"), log $LOG)"
    curl_control ;;
  stop)
    if [ -f "$PID" ]; then kill "$(cat "$PID")" 2>/dev/null || true; rm -f "$PID"; echo "stopped"; fi ;;
  status)
    curl_control || echo "not running" ;;
  control)
    curl_control -d "$2" ;;
  *) echo "usage: $0 start|stop|status|control '<json>'"; exit 2 ;;
esac

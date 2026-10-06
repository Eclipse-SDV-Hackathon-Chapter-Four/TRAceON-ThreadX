#!/usr/bin/env bash
# Control pane for the REMOTE forwarding demo:
#  1. wait for the local server on :8082
#  2. loop the remote-sink pre-flight (route + TCP) until the path is green
#  3. enable forwarding to the remote sink, then poll status
#
#   bash control-remote.sh <sink-host> <sink-port>
set -u

HOST="${1:?usage: control-remote.sh <sink-host> <sink-port>}"
PORT="${2:?usage: control-remote.sh <sink-host> <sink-port>}"
BASE="http://localhost:8082"
URL="http://$HOST:$PORT/logs"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[control] waiting for local server at $BASE ..."
for _ in $(seq 1 60); do
  curl -s -o /dev/null --max-time 1 "$BASE/health" && { echo "[control] server up"; break; }
  sleep 0.5
done

echo "[control] checking the path to the remote sink $HOST:$PORT ..."
until bash "$SCRIPT_DIR/remote-preflight.sh" "$HOST" "$PORT"; do
  echo "[control] remote sink not reachable yet — fix it on the sink host, retrying in 3s"
  echo "          (sink must listen on 0.0.0.0:$PORT, firewall allow inbound, no AP isolation)"
  sleep 3
done

echo "[control] enabling forwarding -> $URL"
curl -s -X POST "$BASE/logs/forwarding/start" \
     -H 'content-type: application/json' \
     -d "{\"url\":\"$URL\"}" && echo
echo
echo "  ============================================"
echo "  ✅ FORWARDING IS ON -> $HOST:$PORT"
echo "     watch the sink ON THE REMOTE MACHINE."
echo "  ============================================"
echo
echo "[control] status (watch 'failed' — if it climbs, the remote stopped accepting):"
while true; do
  STATUS="$(curl -s --max-time 2 "$BASE/logs/forwarding" 2>/dev/null)"
  printf '\r[control] %s' "${STATUS:-<no response>}        "
  sleep 1
done

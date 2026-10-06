#!/usr/bin/env bash
# Control pane for the forwarding demo:
#  1. wait for the server to answer on :8082
#  2. enable log forwarding
#  3. poll /logs/forwarding status once a second
set -u

BASE="http://localhost:8082"

echo "[control] waiting for server at $BASE ..."
for _ in $(seq 1 60); do
  if curl -s -o /dev/null --max-time 1 "$BASE/health"; then
    echo "[control] server is up"
    break
  fi
  sleep 0.5
done

echo "[control] enabling forwarding ..."
curl -s -X POST "$BASE/logs/forwarding/start" && echo
echo
echo "  ============================================"
echo "  ✅ FORWARDING IS ON — inject logs now."
echo "     (logs sent before this line are NOT forwarded)"
echo "  ============================================"
echo
echo "[control] status (Ctrl-C to stop polling):"
while true; do
  STATUS="$(curl -s --max-time 2 "$BASE/logs/forwarding" 2>/dev/null)"
  printf '\r[control] %s' "${STATUS:-<no response>}        "
  sleep 1
done

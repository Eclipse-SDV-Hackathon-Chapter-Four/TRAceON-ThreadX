#!/usr/bin/env bash
# Publish ONE specific log line to TRAceON/logs (host-side test helper).
# Useful for triggering specific error conditions on demand.
#
#   ./scripts/send-log.sh SEVERITY CONTEXT "message text"
#   ./scripts/send-log.sh ERROR SensorTask "Humidity sensor returned no data"
#   ./scripts/send-log.sh WARN MQTT "Broker ping timeout"
#
# Env: BROKER (default localhost), PORT (1883), TOPIC (TRAceON/logs).
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: $0 SEVERITY CONTEXT \"message\"" >&2
  echo "  e.g. $0 ERROR SensorTask \"Humidity sensor returned no data\"" >&2
  exit 1
fi

BROKER="${BROKER:-localhost}"
PORT="${PORT:-1883}"
TOPIC="${TOPIC:-TRAceON/logs}"

SEV="$1"; CTX="$2"; MSG="$3"
# Minimal JSON-escape of the message (backslash and double-quote).
ESC_MSG="${MSG//\\/\\\\}"; ESC_MSG="${ESC_MSG//\"/\\\"}"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

JSON="{\"timestamp\":\"${TS}\",\"context\":\"${CTX}\",\"severity\":\"${SEV}\",\"msg\":\"${ESC_MSG}\"}"
mosquitto_pub -h "${BROKER}" -p "${PORT}" -t "${TOPIC}" -m "${JSON}"
echo "[send-log] ${JSON}"

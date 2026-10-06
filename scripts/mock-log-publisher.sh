#!/usr/bin/env bash
# Mock log publisher for TESTING the TRAceON log pipeline (host-side).
#
# Publishes a 4-field JSON log to TRAceON/logs every N seconds (default 5),
# cycling through a set of sample lines across severities/contexts. Use this to
# watch /logs/entries stream without needing the board to emit logs.
#
#   ./scripts/mock-log-publisher.sh            # every 5s to localhost:1883
#   INTERVAL=2 ./scripts/mock-log-publisher.sh # every 2s
#   BROKER=192.168.88.254 ./scripts/mock-log-publisher.sh
#
# Stop with Ctrl-C.
set -euo pipefail

BROKER="${BROKER:-localhost}"
PORT="${PORT:-1883}"
TOPIC="${TOPIC:-TRAceON/logs}"
INTERVAL="${INTERVAL:-5}"

# Sample log lines: "severity|context|msg"
SAMPLES=(
  "INFO|SensorTask|Sensor sampling started"
  "WARN|SensorTask|Humidity reading out of range (0-100%)"
  "ERROR|SensorTask|Acceleration improbable: 50000 mg"
  "INFO|MQTT|Published telemetry batch"
  "WARN|MQTT|Reconnect attempt 2"
  "DEBUG|Main|Loop tick"
)

echo "[mock-log] publishing to ${BROKER}:${PORT} topic '${TOPIC}' every ${INTERVAL}s (Ctrl-C to stop)"
i=0
while true; do
  entry="${SAMPLES[$((i % ${#SAMPLES[@]}))]}"
  sev="${entry%%|*}"; rest="${entry#*|}"; ctx="${rest%%|*}"; msg="${rest#*|}"
  # ISO-8601 UTC timestamp (same shape the firmware emits).
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  json="{\"timestamp\":\"${ts}\",\"context\":\"${ctx}\",\"severity\":\"${sev}\",\"msg\":\"${msg}\"}"
  mosquitto_pub -h "${BROKER}" -p "${PORT}" -t "${TOPIC}" -m "${json}"
  echo "[mock-log] ${json}"
  i=$((i + 1))
  sleep "${INTERVAL}"
done

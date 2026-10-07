#!/usr/bin/env bash
#
# reflash-for-network.sh — rebuild + flash the AZ3166 firmware for a new network
# in one step, and print the exact commands to start the servers.
#
# Why: the broker IP and WiFi creds are baked into the firmware at build time
# (see BUILD_AND_RUN.md "Build-time overrides"). Moving to a new network means
# rebuilding with the new values and reflashing. This wraps that so you can't
# forget `clean` and don't have to look up the Mac's IP by hand.
#
# Usage:
#   ./scripts/reflash-for-network.sh <WIFI_SSID> <WIFI_PASSWORD> [BROKER_IP] [MQTT_CLIENT_NAME]
#
#   WIFI_SSID        - the new network's SSID (2.4 GHz; the board has no 5 GHz)
#   WIFI_PASSWORD    - the new network's password
#   BROKER_IP        - optional; defaults to this Mac's en0 IP (where the broker runs)
#   MQTT_CLIENT_NAME - optional; defaults to the firmware default (TRAceON)
#
# Example:
#   ./scripts/reflash-for-network.sh 'DemoNet' 'demo-pass-123'
#   ./scripts/reflash-for-network.sh 'DemoNet' 'demo-pass-123' 192.168.1.50
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <WIFI_SSID> <WIFI_PASSWORD> [BROKER_IP] [MQTT_CLIENT_NAME]" >&2
  echo "  e.g. $0 'DemoNet' 'demo-pass-123'" >&2
  exit 1
fi

SSID="$1"
PASSWORD="$2"
# Default broker IP = this Mac's Wi-Fi (en0) address, where the Docker broker runs.
MAC_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
BROKER="${3:-$MAC_IP}"
CLIENT_NAME="${4:-}"

if [ -z "$BROKER" ]; then
  echo "[ERROR] Could not auto-detect the Mac's en0 IP and no BROKER_IP was given." >&2
  echo "        Pass it explicitly: $0 '$SSID' '<pw>' <broker-ip>" >&2
  exit 1
fi

# Toolchain (same default as build.sh expects on this Mac).
export ARM_GCC_PATH="${ARM_GCC_PATH:-/Applications/ArmGNUToolchain/14.2.rel1/arm-none-eabi/bin}"

echo "=============================================================="
echo " Reflashing firmware for a new network"
echo "   WiFi SSID        : $SSID"
echo "   WiFi password    : (${#PASSWORD} chars)"
echo "   Broker IP        : $BROKER $([ "${3:-}" = "" ] && echo '(auto: Mac en0)')"
[ -n "$CLIENT_NAME" ] && echo "   MQTT client name : $CLIENT_NAME"
echo "=============================================================="
echo
echo "Reminders:"
echo "  - The board needs a 2.4 GHz network with NO captive portal (hotel/guest"
echo "    login pages block it — the board can't complete them)."
echo "  - The board must be mounted at /Volumes/AZ3166 to flash."
echo

# Build with the new config (clean is REQUIRED so CMake re-reads the overrides).
BUILD_ENV=(WIFI_SSID="$SSID" WIFI_PASSWORD="$PASSWORD" BROKER_IP="$BROKER")
[ -n "$CLIENT_NAME" ] && BUILD_ENV+=(MQTT_CLIENT_NAME="$CLIENT_NAME")

echo "[1/2] Building (clean)…"
env "${BUILD_ENV[@]}" ./scripts/build.sh mqtt clean

echo
echo "[2/2] Deploying to the board…"
if [ ! -d /Volumes/AZ3166 ]; then
  echo "[ERROR] /Volumes/AZ3166 not mounted — plug in the board, then run:" >&2
  echo "        ./scripts/deploy.sh" >&2
  exit 1
fi
./scripts/deploy.sh

cat <<EOF

==============================================================
 Firmware flashed. The board will reboot and connect to:
   WiFi:   $SSID
   Broker: $BROKER:1883
==============================================================

Next — start a server (the broker runs in Docker on this Mac):

  # Confirm the broker is up:
  docker ps --filter name=traceon-broker

  # Java server on THIS Mac (broker on localhost), dashboard at :8082/dashboard:
  cd telemetry-server-java
  export JAVA_HOME="\$(/usr/libexec/java_home 2>/dev/null || echo /opt/homebrew/opt/openjdk)"
  mvn -B clean package   # (first time only)
  TRACEON_MQTT_HOST=localhost java -jar target/telemetry-server-java-jar-with-dependencies.jar

  # Python server on THIS Mac (broker on localhost), API/docs at :8083/docs:
  cd telemetry-server && ./setup.sh && \\
    TRACEON_MQTT_HOST=localhost ./run.sh

  # Python server on ANOTHER machine (e.g. WSL/Linux) — point it at this Mac's
  # broker IP ($BROKER). Clone the repo there, then:
  cd telemetry-server && ./setup.sh && \\
    TRACEON_MQTT_HOST=$BROKER TRACEON_HTTP_PORT=8083 ./run.sh

Verify the board is publishing:
  timeout 10 mosquitto_sub -h localhost -t 'TRAceON/logs' -C 1
EOF

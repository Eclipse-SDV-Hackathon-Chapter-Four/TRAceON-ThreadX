#!/bin/bash
# Activate the venv and run the TRAceON telemetry server.
#   ./run.sh
# Override settings via env vars, e.g.:
#   TRACEON_MQTT_HOST=192.168.88.254 TRACEON_HTTP_PORT=8080 ./run.sh
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -d ".venv" ]; then
    echo "[ERROR] .venv not found. Run ./setup.sh first."
    exit 1
fi

# shellcheck disable=SC1091
source .venv/bin/activate
exec python -m traceon_server

#!/bin/bash
# Create a Python virtualenv and install dependencies for the TRAceON server.
#
#   ./setup.sh
#
# Re-run any time requirements.txt changes. Safe to run repeatedly.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PYTHON="${PYTHON:-python3}"
VENV_DIR=".venv"

echo "[INFO] Using $($PYTHON --version)"

if [ ! -d "$VENV_DIR" ]; then
    echo "[INFO] Creating virtualenv in $VENV_DIR"
    "$PYTHON" -m venv "$VENV_DIR"
else
    echo "[INFO] Reusing existing virtualenv $VENV_DIR"
fi

# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"

echo "[INFO] Upgrading pip"
python -m pip install --upgrade pip >/dev/null

echo "[INFO] Installing dependencies"
python -m pip install -r requirements.txt

echo ""
echo "[OK] Setup complete. To run the server:"
echo "    source .venv/bin/activate"
echo "    python -m traceon_server"
echo "  or just:  ./run.sh"

#!/usr/bin/env bash
# Preflight for the tmuxinator forwarding demo. Non-fatal: prints what's missing
# so the panes still open (handy for diagnosis) but you know what to fix.
set -u

ROOT="${TRACEON_ROOT:-$HOME/repos/IEH/TRAceON-ThreadX}"
cd "$ROOT" || { echo "[preflight] cannot cd to $ROOT"; exit 0; }

ok()   { printf '  [ok]   %s\n' "$1"; }
warn() { printf '  [WARN] %s\n' "$1"; }

echo "[preflight] TRAceON log-forwarding demo"

# Broker (Docker, publishes 1883).
if docker ps --filter name=traceon-broker --format '{{.Names}}' 2>/dev/null | grep -q traceon-broker; then
  ok "broker container 'traceon-broker' is running"
else
  warn "broker not running — start it (docker start traceon-broker) or the server won't get logs"
fi

# venv for the native server.
if [ -d telemetry-server/.venv ]; then
  ok "telemetry-server/.venv present"
else
  warn "telemetry-server/.venv missing — run: (cd telemetry-server && ./setup.sh)"
fi

# Tools.
command -v python3       >/dev/null 2>&1 && ok "python3 on PATH"       || warn "python3 missing"
command -v mosquitto_pub >/dev/null 2>&1 && ok "mosquitto_pub on PATH" || warn "mosquitto_pub missing (brew install mosquitto)"

# Port 8080 (sink) and 8083 (server) free?
for p in 8080 8083; do
  if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
    warn "port $p already in use — the demo may fail to bind (lsof -nP -iTCP:$p -sTCP:LISTEN)"
  else
    ok "port $p is free"
  fi
done

echo "[preflight] done"
exit 0

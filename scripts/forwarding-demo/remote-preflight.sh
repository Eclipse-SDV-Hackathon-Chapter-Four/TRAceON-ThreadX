#!/usr/bin/env bash
# Remote-sink reachability check for the forwarding demo.
#
#   bash remote-preflight.sh <sink-host> <sink-port>
#
# Runs the two pre-flight checks from TESTING-LOG-FORWARDING.md:
#   1. route check   — the sink must route via a LAN interface, not a VPN utun
#   2. TCP check     — nc must connect to <host>:<port>
# Exit 0 only if BOTH pass. Prints a clear verdict.
set -u

HOST="${1:?usage: remote-preflight.sh <sink-host> <sink-port>}"
PORT="${2:?usage: remote-preflight.sh <sink-host> <sink-port>}"

ok()   { printf '  [ok]   %s\n' "$1"; }
bad()  { printf '  [BAD]  %s\n' "$1"; }

echo "[preflight] checking path to $HOST:$PORT"

# 1. Route: must not be captured by a VPN tunnel (utunN).
IFACE="$(route -n get "$HOST" 2>/dev/null | awk '/interface:/{print $2}')"
route_ok=1
if [ -z "$IFACE" ]; then
  bad "no route to $HOST"
  route_ok=0
elif printf '%s' "$IFACE" | grep -q '^utun'; then
  bad "route to $HOST goes via VPN '$IFACE' (will not reach a LAN sink). Disconnect the VPN or use a 192.168.x sink."
  route_ok=0
else
  ok "route to $HOST via '$IFACE' (LAN)"
fi

# 2. TCP: nc must connect.
tcp_ok=1
if nc -vz -w 3 "$HOST" "$PORT" >/dev/null 2>&1; then
  ok "TCP connect to $HOST:$PORT succeeded"
else
  bad "TCP connect to $HOST:$PORT failed (sink not listening on 0.0.0.0, firewall, or AP client-isolation). NOTE: ping is not a valid test."
  tcp_ok=0
fi

if [ "$route_ok" = 1 ] && [ "$tcp_ok" = 1 ]; then
  echo "[preflight] PATH OK"
  exit 0
fi
echo "[preflight] PATH NOT READY"
exit 1

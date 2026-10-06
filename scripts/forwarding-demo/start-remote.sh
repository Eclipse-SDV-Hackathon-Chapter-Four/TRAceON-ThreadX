#!/usr/bin/env bash
# Launch the REMOTE log-forwarding demo in a 4-pane tmux window.
# The sink runs on ANOTHER machine; this drives the server + forwarding here.
#
#   ./scripts/forwarding-demo/start-remote.sh <sink-host> [sink-port]
#   e.g. ./scripts/forwarding-demo/start-remote.sh 192.168.88.252
#        ./scripts/forwarding-demo/start-remote.sh 192.168.88.252 9000
#
# Panes:
#   monitor — loops the remote-sink reachability check (route + TCP) on the HOST
#   server  — Java server CONTAINER on :8082, forwarding -> <sink-host>:<port>
#   control — waits for server + green path, enables forwarding, polls status
#   inject  — ready-to-run log publishers + the remote sink-start command
#
# The server runs as a container (image assumed to exist:
# traceon-telemetry-server-java:latest; NO_PROXY=* so egress is direct). The
# route/TCP pre-flight runs on the HOST (it inspects host VPN routes).
#
# Plain tmux; captured pane IDs. Stop with:
#   tmux kill-session -t traceon-fwd-remote   (or prefix C-a then : kill-session)
# Stopping the session also removes the server container.
set -euo pipefail

SINK_HOST="${1:?usage: start-remote.sh <sink-host> [sink-port]}"
SINK_PORT="${2:-8080}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SESSION="traceon-fwd-remote"
DEMO="scripts/forwarding-demo"
IMAGE="traceon-telemetry-server-java:latest"
CONTAINER="traceon-fwd-remote-server"

command -v tmux   >/dev/null 2>&1 || { echo "tmux not found (brew install tmux)" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "docker not found" >&2; exit 1; }
docker image inspect "$IMAGE" >/dev/null 2>&1 || {
  echo "image '$IMAGE' not found — build it first:" >&2
  echo "  (cd telemetry-server-java && docker build -t $IMAGE .)" >&2
  exit 1
}

if tmux has-session -t "$SESSION" 2>/dev/null; then
  echo "[start] session '$SESSION' already running — attaching."
  exec tmux attach -t "$SESSION"
fi

cd "$ROOT"
echo "[start] remote sink target: $SINK_HOST:$SINK_PORT"
bash "$DEMO/remote-preflight.sh" "$SINK_HOST" "$SINK_PORT" || \
  echo "[start] (path not ready yet — the control pane will keep retrying)"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
echo

MONITOR_CMD="echo '== MONITOR: remote path $SINK_HOST:$SINK_PORT (host-side) =='; \
  while true; do bash $DEMO/remote-preflight.sh $SINK_HOST $SINK_PORT; echo '--- recheck in 5s ---'; sleep 5; done"
SERVER_CMD="echo '== SERVER :8082 (Java container, forwarding -> $SINK_HOST:$SINK_PORT) =='; \
  docker run --rm --name $CONTAINER -p 8082:8082 \
    -e TRACEON_MQTT_HOST=host.docker.internal \
    $IMAGE"
CONTROL_CMD="echo '== CONTROL: path-gated forwarding + status =='; \
  bash $DEMO/control-remote.sh $SINK_HOST $SINK_PORT"
INJECT_CMD="sed 's/<SINK_HOST>:<PORT>/$SINK_HOST:$SINK_PORT/' $DEMO/inject-hints-remote.txt; exec bash -i"

MON=$(tmux new-session -d -P -F '#{pane_id}' -s "$SESSION" -c "$ROOT" -n forwarding "$MONITOR_CMD")
SERVER=$(tmux split-window -h -P -F '#{pane_id}' -t "$MON" -c "$ROOT" "$SERVER_CMD")
CONTROL=$(tmux split-window -v -P -F '#{pane_id}' -t "$MON" -c "$ROOT" "$CONTROL_CMD")
INJECT=$(tmux split-window -v -P -F '#{pane_id}' -t "$SERVER" -c "$ROOT" "$INJECT_CMD")

tmux select-layout -t "$SESSION:forwarding" tiled
tmux select-pane -t "$INJECT"

tmux set-hook -t "$SESSION" session-closed "run-shell 'docker rm -f $CONTAINER >/dev/null 2>&1 || true'"

exec tmux attach -t "$SESSION"

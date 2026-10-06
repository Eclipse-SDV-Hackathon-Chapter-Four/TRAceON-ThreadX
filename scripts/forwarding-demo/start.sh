#!/usr/bin/env bash
# Launch the local log-forwarding demo in a 4-pane tmux window.
#
#   ./scripts/forwarding-demo/start.sh
#
# Panes:
#   sink    — HTTP sink on 0.0.0.0:8080, prints each forwarded LogEntry
#   server  — Java server CONTAINER on :8082, forwarding -> host.docker.internal:8080
#   control — waits for the server, enables forwarding, polls status
#   inject  — ready-to-run log publishers (send-log.sh)
#
# The server runs as a container (image assumed to exist:
# traceon-telemetry-server-java:latest). Container egress to the host sink uses
# host.docker.internal; the image sets NO_PROXY=* so forwarding is direct.
#
# Plain tmux (no tmuxinator); captured pane IDs. Stop with:
#   tmux kill-session -t traceon-fwd     (or, inside tmux: prefix C-a then : kill-session)
# Stopping the session also removes the server container (teardown pane).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SESSION="traceon-fwd"
DEMO="scripts/forwarding-demo"
IMAGE="traceon-telemetry-server-java:latest"
CONTAINER="traceon-fwd-server"

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
bash "$DEMO/preflight.sh" || true
# Remove any stale server container from a previous run.
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
echo

SINK_CMD="echo '== SINK :8080 =='; python3 $DEMO/sink.py 8080"
# Java server container: publish 8082, forward to the host sink via host.docker.internal.
SERVER_CMD="echo '== SERVER :8082 (Java container, forwarding -> host.docker.internal:8080) =='; \
  docker run --rm --name $CONTAINER -p 8082:8082 \
    -e TRACEON_MQTT_HOST=host.docker.internal \
    -e TRACEON_LOG_FORWARD_URL=http://host.docker.internal:8080/logs \
    $IMAGE"
CONTROL_CMD="echo '== CONTROL: start forwarding + live status =='; bash $DEMO/control.sh"
INJECT_CMD="cat $DEMO/inject-hints.txt; exec bash -i"

SINK=$(tmux new-session -d -P -F '#{pane_id}' -s "$SESSION" -c "$ROOT" -n forwarding "$SINK_CMD")
SERVER=$(tmux split-window -h -P -F '#{pane_id}' -t "$SINK" -c "$ROOT" "$SERVER_CMD")
CONTROL=$(tmux split-window -v -P -F '#{pane_id}' -t "$SINK" -c "$ROOT" "$CONTROL_CMD")
INJECT=$(tmux split-window -v -P -F '#{pane_id}' -t "$SERVER" -c "$ROOT" "$INJECT_CMD")

tmux select-layout -t "$SESSION:forwarding" tiled
tmux select-pane -t "$INJECT"

# Teardown: when the tmux session ends, stop the server container.
tmux set-hook -t "$SESSION" session-closed "run-shell 'docker rm -f $CONTAINER >/dev/null 2>&1 || true'"

exec tmux attach -t "$SESSION"

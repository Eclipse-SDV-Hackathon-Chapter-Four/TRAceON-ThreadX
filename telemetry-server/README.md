# TRAceON Telemetry HTTP Server

Subscribes to the AZ3166's MQTT telemetry (`TRAceON/sensor-data`), parses the
plain-text payload into JSON, holds the latest reading in memory, and serves it
over HTTP. Includes a **SOVD-flavored** read surface (ISO 17978 resource shape)
so a future **Eclipse OpenSOVD** gateway can integrate with minimal glue.

## Prerequisites

- Python 3.11+ (tested on 3.14).
- The MQTT broker running (see `../BUILD_AND_RUN.md` — the Docker `traceon-broker`).

## Setup & run

```bash
cd ~/repos/IEH/TRAceON-ThreadX/telemetry-server
./setup.sh          # creates .venv and installs deps
./run.sh            # starts the server on 0.0.0.0:8083
```

Open the auto-generated API docs at: http://localhost:8083/docs

## Run in Docker (so LAN peers can reach it)

The native (`./run.sh`) process is blocked for inbound LAN connections by the
managed Mac's application firewall. Running in Docker publishes the port through
Docker's network layer, which bypasses that firewall — the same reason the broker
runs in Docker. Teammates on the same WiFi can then reach it at
`http://<your-LAN-ip>:8083` (e.g. `http://192.168.88.254:8083`).

```bash
cd ~/repos/IEH/TRAceON-ThreadX/telemetry-server

# Build (empty proxy build-args: the injected Docker proxy can't be reached
# from build containers on this managed Mac).
docker build \
  --build-arg http_proxy= --build-arg https_proxy= \
  --build-arg HTTP_PROXY= --build-arg HTTPS_PROXY= \
  -t traceon-telemetry-server:latest .

# Stop any native server first (frees port 8083), then run the container.
docker run -d --name traceon-server -p 8083:8083 traceon-telemetry-server:latest

# Manage:
docker logs -f traceon-server
docker stop traceon-server
docker start traceon-server
docker rm -f traceon-server
```

The container reaches the broker (itself a container publishing 1883 on the host)
via `host.docker.internal:1883` — set as the default `TRACEON_MQTT_HOST` in the
image. Override any setting with `-e`, e.g. `-e TRACEON_HTTP_PORT=9090`.

> Only one thing can own host port 8083 at a time — don't run `./run.sh` and the
> container simultaneously.

## Configuration (env vars)

| Variable                | Default              | Meaning                        |
|-------------------------|----------------------|--------------------------------|
| `TRACEON_MQTT_HOST`     | `localhost`          | Broker host                    |
| `TRACEON_MQTT_PORT`     | `1883`               | Broker port                    |
| `TRACEON_SENSOR_TOPIC`  | `TRAceON/sensor-data`| Topic to subscribe (telemetry) |
| `TRACEON_LOG_TOPIC`     | `TRAceON/logs`       | Topic to subscribe (board logs) |
| `TRACEON_LOG_BUFFER_SIZE`| `100`               | Recent log entries kept in memory |
| `TRACEON_COMMAND_TOPIC` | `TRAceON/incoming`   | Topic to publish (commands)    |
| `TRACEON_COMPONENT`     | `TRAceON`            | SOVD component/entity name     |
| `TRACEON_HTTP_HOST`     | `0.0.0.0`            | HTTP bind host                 |
| `TRACEON_HTTP_PORT`     | `8083`              | HTTP bind port                 |

## Routes

### Streaming (Server-Sent Events) — the primary telemetry/logs interface
These are **live SSE streams by default** (`text/event-stream`); keep the connection open.
- `GET /telemetry/entries` — live telemetry readings as they arrive.
- `GET /logs/entries` — live board log entries (SSE). Each frame is an ISO
  **EventEnvelope** `{timestamp (server emit), payload: LogEntry, error}`. The board
  publishes ISO 17978-3 `LogEntry` `{timestamp, context (AUTOSAR_DLT object),
  severity (DLT_*), msg}` on `TRAceON/logs`. Malformed payloads still produce an
  entry (`severity: "DLT_INFO"`, raw text as `msg`).

### Log forwarding (optional sink, separate from SSE)
Forwards each received `LogEntry` to `TRACEON_LOG_FORWARD_URL` via HTTP POST
(fire-and-forget). **Off by default**; controlled at runtime:
- `GET  /logs/forwarding` — status.
- `POST /logs/forwarding/start` — start (optional body `{"url": "..."}` override).
- `POST /logs/forwarding/stop` — stop.

> Full API: see [`../API.md`](../API.md).

### Simple / native
- `GET  /health` — liveness + MQTT status + data freshness.
- `GET  /telemetry/latest/{field}` — one field snapshot, e.g. `temperature_degC`.
- `POST /command` — body `{"message": "..."}` → published to `TRAceON/incoming`
  (shows on the board's OLED).

### History (ring buffers, last 100) — snapshot queries with filters
Time filters (`since`/`until`) are **ISO-8601** and apply to the server-side receive time.
- `GET /telemetry/history` — last ≤100 readings. Query params:
  - `limit=N` (default/cap 100), `field=<name>` (project to that field →
    `{received_at, value}`), `since=<ISO-8601>`, `until=<ISO-8601>`.
- `GET /logs/history` — last ≤100 log entries. Query params:
  - `limit=N`, `severity=WARN,ERROR` (comma-separated, case-insensitive),
    `context=<name>` (exact), `since`, `until`.
  - Invalid `since`/`until` → HTTP 400.

### SOVD-flavored (for future OpenSOVD)
- `GET /components/TRAceON/data` — list available data resources.
- `GET /components/TRAceON/data/{resource_id}` — one data resource,
  e.g. `/components/TRAceON/data/temperature_degC`.

> Note: the standalone snapshots `/telemetry/latest` and `/logs` were removed —
> `/telemetry/entries` and `/logs/entries` are streams. For one-shot reads use the
> per-field route or the SOVD `data` resources.

## Example

```bash
curl -s localhost:8083/health | python3 -m json.tool
curl -s localhost:8083/telemetry/latest/temperature_degC | python3 -m json.tool
curl -s localhost:8083/components/TRAceON/data | python3 -m json.tool
curl -s -X POST localhost:8083/command \
     -H 'content-type: application/json' \
     -d '{"message":"Hello OLED"}'

# Live streams (Server-Sent Events) — keep the connection open:
curl -N localhost:8083/telemetry/entries
curl -N localhost:8083/logs/entries

# History (last 100) with filters:
curl -s "localhost:8083/telemetry/history?field=temperature_degC&limit=20"
curl -s "localhost:8083/logs/history?severity=WARN,ERROR&context=SensorTask"
curl -s "localhost:8083/telemetry/history?since=2026-10-06T14:00:00Z"
```

## Field names

Parsed from the firmware's plain-text payload:
`pressure_hPa`, `temperature_degC`, `humidity_perc`,
`acceleration_mg` (3-vector), `magnetic_mG` (3-vector).

## Architecture

```
MQTT (TRAceON/sensor-data) → MqttClient → parser → TelemetryStore (latest)
                                                       │
                                 FastAPI routes ───────┤ simple surface
                                 (JSON)         └───────┘ SOVD surface → (future) OpenSOVD
```

Modules kept decoupled on purpose so the SOVD adapter can grow independently.

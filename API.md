# TRAceON Telemetry Server — API Reference

Shareable reference for the TRAceON telemetry/log HTTP API. **Both server
implementations expose the identical API:**

- **Python (FastAPI):** default port **8083** (interactive docs at `/docs`)
- **Java (Jakarta: Jetty/Jersey/Yasson):** default port **8082**

Base URL in examples: `http://localhost:8083` (use `:8082` for Java, or the
host's LAN IP, e.g. `http://192.168.88.254:8083`).

Both subscribe to an MQTT broker and expose the vehicle's **telemetry** and
**logs** over HTTP. The log model follows **ISO 17978-3 (SOVD)**:
`EventEnvelope` (Table 5) and `LogEntry` (Table 316).

---

## Data models

### LogEntry (ISO 17978-3 Table 316)
```json
{
  "timestamp": "2026-10-06T17:08:15Z",
  "context": {
    "type": "AUTOSAR_DLT",
    "application_id": "TRAC",
    "context_id": "SensorTask",
    "session": "", "session_id": "", "message_id": ""
  },
  "severity": "DLT_WARN",
  "msg": "Humidity sensor returned no data"
}
```
- `timestamp` — string:date-time (ISO-8601), the board's time.
- `context` — a typed `Context` object. For this device: `type = "AUTOSAR_DLT"`
  with the subsystem name in `context_id`. (ISO also allows `type = "IETF RFC 5424"`
  with `host`/`process`/`pid`.)
- `severity` — one of `DLT_FATAL`, `DLT_ERROR`, `DLT_WARN`, `DLT_INFO`,
  `DLT_DEBUG`, `DLT_VERBOSE`.
- `msg` — string.

### EventEnvelope (ISO 17978-3 Table 5)
Used to wrap each event on the SSE **stream**:
```json
{
  "timestamp": "2026-10-06T17:08:16Z",   // server emit time
  "payload": { ...LogEntry... },          // the event body (AnyValue)
  "error": null                            // GenericError (string) or null on success
}
```

### Telemetry reading
```json
{
  "fields": {
    "pressure_hPa": 971.5, "temperature_degC": 28.2, "humidity_perc": 48.5,
    "acceleration_mg": [-9.0, -39.3, 1046.4], "magnetic_mG": [69.0, 235.5, -57.0]
  },
  "received_at": 1791300191.09, "age_seconds": 0.0, "message_count": 5
}
```

---

## Endpoints

### Health
| Method | Path | Description |
|---|---|---|
| GET | `/health` | Liveness, MQTT connection status, data freshness. |

### Telemetry
| Method | Path | Description |
|---|---|---|
| GET | `/telemetry/entries` | **SSE stream** of telemetry readings (live). |
| GET | `/telemetry/latest/{field}` | One field snapshot, e.g. `temperature_degC`. |
| GET | `/telemetry/history` | Last ≤100 readings (see query params). |

### Logs
| Method | Path | Description |
|---|---|---|
| GET | `/logs/entries` | **SSE stream** of `EventEnvelope`-wrapped `LogEntry` (live). |
| GET | `/logs/history` | Last ≤100 `LogEntry` (ISO-pure list; see query params). |

### Log forwarding (optional sink — separate from SSE)
Forwards each received `LogEntry` to a fixed URL (env `TRACEON_LOG_FORWARD_URL`)
via HTTP POST. Fire-and-forget; **off by default**; controlled at runtime.
| Method | Path | Description |
|---|---|---|
| GET  | `/logs/forwarding` | Status: `{enabled, url, forwarded, failed, dropped, queued}`. |
| POST | `/logs/forwarding/start` | Start forwarding. Optional body `{"url": "..."}` overrides the env URL. 400 if no URL configured. |
| POST | `/logs/forwarding/stop` | Stop forwarding. |

### Command (to the board)
| Method | Path | Description |
|---|---|---|
| POST | `/command` | Body `{"message": "..."}` → published to the board's MQTT incoming topic (shows on the OLED). |

### SOVD-flavored data resources (ISO-shaped reads)
| Method | Path | Description |
|---|---|---|
| GET | `/components/{component}/data` | List available data resources (telemetry fields). |
| GET | `/components/{component}/data/{resource_id}` | One data resource, e.g. `.../data/temperature_degC`. |

> Component id for this device: `TRAceON`.

---

## Query parameters

### `GET /telemetry/history`
| Param | Meaning |
|---|---|
| `limit` | Most recent N (default & cap 100). |
| `field` | Project to a single field → `{received_at, value}` per reading. |
| `since` / `until` | ISO-8601 bounds on **server receive time**. |

### `GET /logs/history`
| Param | Meaning |
|---|---|
| `limit` | Most recent N (default & cap 100). |
| `severity` | Comma-separated, e.g. `DLT_WARN,DLT_ERROR`. Legacy bare values (`WARN`) are mapped to `DLT_*`. |
| `context` | Match the AUTOSAR_DLT `context_id` (e.g. `SensorTask`). |
| `since` / `until` | ISO-8601 bounds on **server receive time**. |

Invalid `since`/`until` → HTTP 400.

---

## Examples

```bash
# Health
curl -s localhost:8083/health

# Live streams (SSE) — keep the connection open:
curl -N localhost:8083/telemetry/entries
curl -N localhost:8083/logs/entries      # frames are EventEnvelope { timestamp, payload, error }

# History with filters
curl -s "localhost:8083/telemetry/history?field=temperature_degC&limit=20"
curl -s "localhost:8083/logs/history?severity=DLT_WARN,DLT_ERROR&context=SensorTask"
curl -s "localhost:8083/logs/history?since=2026-10-06T17:00:00Z"

# Log forwarding (set TRACEON_LOG_FORWARD_URL on the server first)
curl -s localhost:8083/logs/forwarding
curl -s -X POST localhost:8083/logs/forwarding/start
curl -s -X POST localhost:8083/logs/forwarding/stop
# override URL at start (point at your sink; sink port is 8080 by convention):
curl -s -X POST localhost:8083/logs/forwarding/start \
     -H 'content-type: application/json' -d '{"url":"http://SINK_HOST:8080/logs"}'

# Command to the board
curl -s -X POST localhost:8083/command \
     -H 'content-type: application/json' -d '{"message":"Hello OLED"}'

# SOVD data resources
curl -s localhost:8083/components/TRAceON/data
curl -s localhost:8083/components/TRAceON/data/temperature_degC
```

---

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `TRACEON_MQTT_HOST` | `localhost` (Docker: `host.docker.internal`) | Broker host |
| `TRACEON_MQTT_PORT` | `1883` | Broker port |
| `TRACEON_SENSOR_TOPIC` | `TRAceON/sensor-data` | Telemetry topic (subscribe) |
| `TRACEON_LOG_TOPIC` | `TRAceON/logs` | Log topic (subscribe) |
| `TRACEON_COMMAND_TOPIC` | `TRAceON/incoming` | Command topic (publish) |
| `TRACEON_LOG_FORWARD_URL` | *(unset)* | Downstream sink for log forwarding |
| `TRACEON_LOG_BUFFER_SIZE` | `100` | Log history ring-buffer size |
| `TRACEON_TELEMETRY_HISTORY_SIZE` | `100` | Telemetry history ring-buffer size |
| `TRACEON_COMPONENT` | `TRAceON` | SOVD component/entity id |
| `TRACEON_HTTP_HOST` | `0.0.0.0` | HTTP bind host (Python) |
| `TRACEON_HTTP_PORT` | `8083` (Java: `8082`) | HTTP bind port |

---

## Notes

- **SSE vs snapshot:** `/{telemetry,logs}/entries` are live SSE streams (default
  interface). `/{telemetry,logs}/history` are one-shot snapshots with filters.
  Only the SSE log stream is `EventEnvelope`-wrapped; `/logs/history` returns a
  plain `LogEntry` list (ISO-pure).
- **Forwarding is independent of SSE** — enabling/disabling it does not affect
  the streams or history.
- **Security:** the forwarding target URL is operator-controlled; do not expose
  the control endpoints to untrusted clients (open-relay / SSRF risk).

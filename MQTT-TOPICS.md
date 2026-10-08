# TRAceON MQTT Topics — Reference

Shareable reference for the MQTT layer that connects the AZ3166 board to the
telemetry servers. For the HTTP side see [API.md](API.md); for the overall
picture see [ARCHITECTURE.md](ARCHITECTURE.md).

**Broker:** Eclipse Mosquitto 2, default `localhost:1883` (anonymous, no TLS).
**Protocol:** MQTT 3.1.1 (board uses NetX Duo `nxd_mqtt_client`; servers use
Eclipse Paho).

Topic names derive from the board's `MQTT_CLIENT_NAME` (default `TRAceON`), so
the prefix follows that name if it is overridden at build time.

## Topics at a glance

| Topic | Direction | Publisher → Subscriber | Payload | QoS (pub) |
|---|---|---|---|---|
| `TRAceON/sensor-data` | board → server | board → both servers | plain text (key/value rows) | 1 |
| `TRAceON/logs` | board → server | board → both servers | ISO 17978-3 `LogEntry` JSON | 1 |
| `TRAceON/incoming` | server → board | either server → board | plain text (free-form) | 1 pub / 0 sub |

- **Publishers/subscribers.** The board publishes `sensor-data` + `logs` and
  subscribes to `incoming`. Each server subscribes to `sensor-data` + `logs`
  (QoS 1) and publishes to `incoming` (QoS 1).
- **QoS.** Board publishes at **QoS 1**; the board *subscribes* to `incoming` at
  **QoS 0** (`mqtt_client.c`). Servers subscribe at QoS 1 and publish commands at
  QoS 1.
- **Client IDs.** board `TRAceON`; Java server `traceon-java`; Python server
  `traceon-http-server`. (MQTT requires unique client IDs per broker connection —
  see Notes.)
- **Retained / will.** None. All messages are non-retained; no last-will is set.

---

## `TRAceON/sensor-data` — telemetry (board → server)

The board publishes one message per sensor cycle. **Payload is a plain-text
block**, CRLF-separated `Label: value` rows (not JSON) — exactly what the board
also renders locally. Vectors are comma-separated `X, Y, Z`.

```
Pressure: 962.63
Temperature: 22.08
Humidity: 61.50
Acceleration: 80.09, 86.13, 1036.45
Magnetic: 240.00, -241.50, 30.00
```

| Label | Server field name | Unit |
|---|---|---|
| `Pressure` | `pressure_hPa` | hPa |
| `Temperature` | `temperature_degC` | °C |
| `Humidity` | `humidity_perc` | % |
| `Acceleration` | `acceleration_mg` (`[x,y,z]`) | mg |
| `Magnetic` | `magnetic_mG` (`[x,y,z]`) | mG |

The server parses each `Label: value` line into the typed field on the right
(`MqttService.parse` / the Python `parser`). Unknown labels are ignored;
unparseable numbers are skipped. These field names are what the HTTP API returns
(e.g. `GET /telemetry/latest/temperature_degC`).

---

## `TRAceON/logs` — diagnostic logs (board → server)

The board publishes one **ISO 17978-3 `LogEntry` JSON** per log event (~1/sec in
the default DEMO_LOGS mode, cycling severities/contexts). Built on-device by
`logger.c`.

```json
{
  "timestamp": "2026-10-08T06:13:16Z",
  "context": {
    "type": "AUTOSAR_DLT",
    "application_id": "TRAC",
    "context_id": "Thermal",
    "session": "",
    "session_id": "",
    "message_id": ""
  },
  "severity": "DLT_WARN",
  "msg": "temperature trending high"
}
```

- `timestamp` — ISO-8601 UTC, from SNTP.
- `context.type` — always `AUTOSAR_DLT`.
- `application_id` — the device's 4-char DLT app id (`TRAC`).
- `context_id` — the subsystem (e.g. `Main`, `SensorTask`, `MQTT`, `Thermal`,
  `Watchdog`, `Net`, `I2C`, `Power`, `Telemetry`).
- `severity` — a DLT level: `DLT_FATAL`, `DLT_ERROR`, `DLT_WARN`, `DLT_INFO`,
  `DLT_DEBUG`, `DLT_VERBOSE`.
- `msg` — JSON-escaped free text.

On the server this is stored as a pure `LogEntry` and, on the live SSE stream, is
wrapped in an ISO `EventEnvelope` (see API.md). The optional log-forwarder POSTs
the raw `LogEntry` to a downstream sink.

---

## `TRAceON/incoming` — commands (server → board)

Either server publishes here when it receives `POST /command`. The board
subscribes and shows the payload on its OLED (header `TRAceON:` + the message).

- **Payload:** free-form plain text (UTF-8). No schema; the board renders it
  wrapped on the OLED.
- **Example:** publishing `TRAceON` → OLED shows `TRAceON:` / `TRAceON`.

```bash
# via a server (preferred):
curl -s -X POST http://127.0.0.1:8082/command \
     -H 'content-type: application/json' -d '{"message":"hello"}'

# or straight to the broker:
mosquitto_pub -h localhost -t 'TRAceON/incoming' -m 'hello'
```

---

## Quick CLI

```bash
# watch everything (host client, or run inside the broker container)
mosquitto_sub -h localhost -t 'TRAceON/#' -v
docker exec traceon-broker mosquitto_sub -t 'TRAceON/#' -v   # if no host client

# just logs / just telemetry
mosquitto_sub -h localhost -t 'TRAceON/logs' -v
mosquitto_sub -h localhost -t 'TRAceON/sensor-data' -v

# inject a one-off log (demo helper) — publishes a LogEntry to TRAceON/logs
# (NOTE: a simplified LogEntry — context is a bare string, not the full
#  AUTOSAR_DLT object the board emits; the servers' lenient parser accepts both)
./scripts/send-log.sh DLT_ERROR SensorTask "Humidity sensor returned no data"
```

---

## Notes & gotchas

- **Broker IP is baked into the firmware** at build time (`BROKER_IP=a.b.c.d` →
  `MQTT_BROKER_O1..O4`). A new network needs a reflash (see DEMO.md /
  `reflash-for-network.sh`). The servers find the broker via `TRACEON_MQTT_HOST`
  (default `localhost`).
- **Client-ID uniqueness.** The three client IDs differ, so they coexist. Two
  instances sharing an ID (e.g. two boards, or two of the same server) would get
  kicked off the broker ("session taken over") — see LIMITATIONS.md #3.
- **No TLS / no auth.** Plaintext MQTT on the LAN with `allow_anonymous`. Fine for
  a trusted demo network; see LIMITATIONS.md #4/#5 for the production path
  (MQTT-over-TLS, broker auth).
- **Topic overrides (servers).** `TRACEON_SENSOR_TOPIC`, `TRACEON_LOG_TOPIC`,
  `TRACEON_COMMAND_TOPIC` override the defaults; they must match the board's
  topics (which follow `MQTT_CLIENT_NAME`).

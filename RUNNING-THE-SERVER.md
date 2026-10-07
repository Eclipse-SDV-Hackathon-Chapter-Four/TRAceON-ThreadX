# Running the Telemetry Server + Log Forwarding

How to start a TRAceON telemetry server and turn on log forwarding to an
**already-running** log sink. Covers both the **Python** and **Java** servers,
each in two ways: **native** and **Docker**.

Pick ONE server (Python *or* Java) — they are interchangeable and both subscribe
to the same broker. Running two at once is fine only if the broker allows it;
avoid running two of the *same* implementation (MQTT client-id collision).

## Conventions

| Thing | Value |
|---|---|
| MQTT broker | `BROKER_HOST:1883` (the machine running Mosquitto; e.g. `192.168.88.254`) |
| Python server HTTP port | **8083** |
| Java server HTTP port | **8082** |
| Log sink (assumed running) | `SINK_URL`, e.g. `http://127.0.0.1:8080/internal/logs` |

> **Forwarding is OFF by default** and started at runtime. Setting the sink URL
> does NOT auto-start it — you must call `/logs/forwarding/start`.
>
> **Payload:** the server POSTs the ISO 17978-3 `LogEntry` (severities are
> `DLT_INFO`, `DLT_WARN`, …). The sink must accept that shape.

---

## Prerequisites

- The **broker** is up and reachable (`nc -vz BROKER_HOST 1883` → succeeded).
- The **board/producer** is publishing to `TRAceON/logs` (optional for a smoke
  test — you can inject with `scripts/send-log.sh`).
- The **sink** is running and reachable from wherever the server runs
  (`nc -vz` the sink host/port first; see TESTING-LOG-FORWARDING.md pre-flight).

---

## Python server

### Native
```bash
cd telemetry-server
./setup.sh                       # one-time: create .venv + install deps

# Start, pointed at the broker. (Optionally bake the sink URL in here.)
TRACEON_MQTT_HOST=BROKER_HOST \
TRACEON_HTTP_PORT=8083 \
TRACEON_LOG_FORWARD_URL=SINK_URL \
./run.sh
```

### Docker
```bash
cd telemetry-server
# image assumed built: traceon-telemetry-server:latest
docker run -d --name traceon-server -p 8083:8083 \
  -e TRACEON_MQTT_HOST=BROKER_HOST \
  -e TRACEON_LOG_FORWARD_URL=SINK_URL \
  traceon-telemetry-server:latest
docker logs -f traceon-server    # watch startup / MQTT connect
```
> In Docker, if the broker/sink are on the **host**, use
> `host.docker.internal` instead of `127.0.0.1`/`localhost`. The image sets
> `NO_PROXY=*` so outbound forwarding bypasses the injected Docker proxy.

### Verify + start forwarding (Python)
```bash
# 1. server healthy + MQTT connected?
curl -s localhost:8083/health            # expect "mqtt_connected": true

# 2. start forwarding (uses TRACEON_LOG_FORWARD_URL set above)
curl -s -X POST localhost:8083/logs/forwarding/start ; echo
#    …or override the URL at start (no restart needed):
curl -s -X POST localhost:8083/logs/forwarding/start \
     -H 'content-type: application/json' -d '{"url":"SINK_URL"}' ; echo

# 3. watch the counters: forwarded should rise, failed stay 0
curl -s localhost:8083/logs/forwarding ; echo

# 4. (optional) inject a test log if no board is publishing
./scripts/send-log.sh DLT_INFO Main 'forwarding test'

# stop forwarding
curl -s -X POST localhost:8083/logs/forwarding/stop ; echo
```

---

## Java server

### Native
```bash
cd telemetry-server-java
export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || echo /opt/homebrew/opt/openjdk)"
mvn -B clean package             # builds target/telemetry-server-java-jar-with-dependencies.jar

# Start, pointed at the broker. (Optionally bake the sink URL in here.)
TRACEON_MQTT_HOST=BROKER_HOST \
TRACEON_HTTP_PORT=8082 \
TRACEON_LOG_FORWARD_URL=SINK_URL \
java -jar target/telemetry-server-java-jar-with-dependencies.jar
```

### Docker
```bash
cd telemetry-server-java
# image assumed built: traceon-telemetry-server-java:latest
docker run -d --name traceon-server-java -p 8082:8082 \
  -e TRACEON_MQTT_HOST=BROKER_HOST \
  -e TRACEON_LOG_FORWARD_URL=SINK_URL \
  traceon-telemetry-server-java:latest
docker logs -f traceon-server-java    # watch startup / MQTT connect
```
> Same `host.docker.internal` note as Python when broker/sink are on the host.
> The Java image also sets `NO_PROXY=*`.

### Verify + start forwarding (Java)
```bash
# 1. server healthy + MQTT connected?
curl -s localhost:8082/health            # expect "mqtt_connected": true

# 2. start forwarding
curl -s -X POST localhost:8082/logs/forwarding/start ; echo
#    …or override the URL at start:
curl -s -X POST localhost:8082/logs/forwarding/start \
     -H 'content-type: application/json' -d '{"url":"SINK_URL"}' ; echo

# 3. watch the counters
curl -s localhost:8082/logs/forwarding ; echo

# 4. (optional) inject a test log
./scripts/send-log.sh DLT_INFO Main 'forwarding test'

# stop forwarding
curl -s -X POST localhost:8082/logs/forwarding/stop ; echo
```

---

## Forwarding control endpoints (both servers)

| Method | Path | Purpose |
|---|---|---|
| GET  | `/logs/forwarding` | Status: `{enabled, url, forwarded, failed, dropped, queued}` |
| POST | `/logs/forwarding/start` | Start. Optional body `{"url":"..."}` overrides the env URL. 400 if no URL set. |
| POST | `/logs/forwarding/stop` | Stop. |

## Reading the status counters

- `forwarded` rising, `failed` 0 → working.
- `failed` rising → the sink is reachable but **rejecting** the request
  (e.g. HTTP 400 if it dislikes the payload — check it accepts the ISO
  `LogEntry` with `DLT_*` severities), OR the sink is unreachable (timeout).
- both 0 → no logs are arriving (board disconnected / nothing injected).

See `TESTING-LOG-FORWARDING.md` for the network pre-flight (route + TCP checks)
and `API.md` for the full API and the exact forwarded `LogEntry` schema.

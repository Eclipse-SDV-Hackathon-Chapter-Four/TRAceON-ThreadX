# Testing

Unit / module tests for the three TRAceON codebases. All run on the host — no
board required.

**Run all three at once:**
```bash
./scripts/run-tests.sh            # firmware + python + java; quiet (one line/suite) + summary
./scripts/run-tests.sh -v         # verbose: stream each suite's full output
./scripts/run-tests.sh python java   # or a subset
```
Quiet mode shows a suite's full output only if it fails; exits non-zero if any
suite fails. `-h`/`--help` prints usage.

Or run a single suite directly:

| Suite | Count | Framework | Command |
|---|---|---|---|
| Python server | 39 | pytest | `cd telemetry-server && ./setup.sh && . .venv/bin/activate && pip install -r requirements-dev.txt && pytest` |
| Java server | 22 | JUnit 5 | `cd telemetry-server-java && mvn -B test` |
| Firmware (pure logic) | 13 | custom host harness | `./app/common/tests/run.sh` |

---

## Python server (`telemetry-server/`)

pytest covering the pure-logic modules + a module test of the forwarding API.

```bash
cd telemetry-server
./setup.sh                              # create .venv + runtime deps (once)
source .venv/bin/activate
pip install -r requirements-dev.txt     # pytest + httpx (once)
pytest                                  # -> 39 passed
```

Covered:
- `logs.py` — severity normalization (DLT passthrough, legacy `WARN`→`DLT_WARN`,
  empty→`DLT_INFO`, unknown passthrough), lenient `parse_log` (full ISO entry,
  string-context wrapping, malformed/non-dict fallback), `context_id_of`,
  `LogStore` ring buffer (order, eviction, cumulative count, slot shape).
- `envelope.py` — `EventEnvelope` shape + error field + ISO-8601 `Z` timestamp.
- `query.py` — `clamp_limit` bounds, `parse_iso8601` (`Z`, naive→UTC, malformed
  raises), `in_time_range` inclusive bounds.
- `app.py` — forwarding control endpoints via FastAPI `TestClient`
  (status/start/stop, 400 when no URL). MQTT is not started (lifespan not
  entered) and the forwarder worker is monkeypatched, so no broker or real POST
  is needed.

## Java server (`telemetry-server-java/`)

JUnit 5 (via `maven-surefire-plugin`). Pure-logic only — no live MQTT/HTTP, so it
runs in CI without a broker.

```bash
cd telemetry-server-java
export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || echo /opt/homebrew/opt/openjdk)"
mvn -B test                             # -> 22 passed
```

Covered (`src/test/java/org/traceon/`):
- `LogStoreTest` — `mapSeverity`, `contextIdOf`, `normalize`, `fallback`, and the
  `add`/`entries`/`slots`/`count` ring buffer (isolated instances built via
  reflection on the private ctor).
- `EnvelopeJsonTest` — `EventEnvelope.wrap` shape + Yasson (`Json`) round-trip.
- `QueryTest` — `clampLimit`, `parseIso8601Millis` (`Z` + offset + malformed),
  `inRange`.

## Firmware pure logic (`app/common/tests/`)

The firmware is bare-metal (ThreadX/NetXDuo, cross-compiled for ARM), so it can't
run on the host as a whole. These tests cover **only the hardware-free logic** in
`logger.c`, host-compiled with stubs for the hardware dependencies.

```bash
./app/common/tests/run.sh               # -> ALL PASSED (13 checks)
```

How it works: `test_logger_host.c` `#include`s `logger.c` directly (to reach its
`static` helpers), pre-empts the real `sntp_client.h` / `mqtt_client.h`
(which pull in ThreadX/NetXDuo) by defining their include guards, and stubs
`sntp_time_get()` (fake clock) and `mqtt_publish_log()` (captures the JSON).

Covered:
- `epoch_to_iso8601` — civil-date conversion (known value, epoch 0, leap day).
- `json_escape` — quotes, backslashes, newline/tab, control-char dropping, NULL.
- `traceon_log` — full ISO 17978-3 LogEntry JSON assembly, NULL-severity →
  `DLT_INFO`, message escaping.

**Scope caveat:** this is best-effort host coverage of pure logic. It does *not*
exercise the RTOS, WiFi/NetXDuo, SNTP, or real MQTT publish paths — those need
on-target or HIL testing (see [OPENDUT-INTEGRATION.md](OPENDUT-INTEGRATION.md)).
The real ARM build (`./scripts/build.sh mqtt`) is unaffected: `app/CMakeLists.txt`
does not reference `tests/`.

---

## Running everything

```bash
# Python
(cd telemetry-server && . .venv/bin/activate && pytest)
# Java
(cd telemetry-server-java && JAVA_HOME="$(/usr/libexec/java_home)" mvn -B test)
# Firmware pure logic
./app/common/tests/run.sh
```

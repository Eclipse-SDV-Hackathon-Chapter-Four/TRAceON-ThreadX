# Demo runbook

Exact, ordered steps to run the TRAceON demo. Copy-paste friendly. The board
publishes sensor telemetry + ISO 17978-3 logs over MQTT; a server ingests them
and shows a live dashboard; you send a command that appears on the board's OLED.

> All paths are relative to the repo root `~/repos/IEH/TRAceON-ThreadX`.
> Everything runs on the Mac (broker in Docker, Java server native). The board
> talks to the Mac's broker over Wi-Fi.

---

## 0. Before the audience arrives — one-time per network

Find the Mac's IP on the demo Wi-Fi and reflash the board for it. The board bakes
in the broker IP + Wi-Fi creds, so a new network needs a reflash.

```bash
cd ~/repos/IEH/TRAceON-ThreadX

# (board plugged in via USB, mounted at /Volumes/AZ3166)
# Auto-detects the Mac's en0 IP as the broker IP:
./scripts/reflash-for-network.sh '<WIFI_SSID>' '<WIFI_PASSWORD>'
```

- Needs a **2.4 GHz** network with **no captive portal** (hotel/guest login
  pages block the board — it can't complete them). If the venue Wi-Fi has a
  portal, use a phone hotspot that allows it, or a different network.
- The script builds `clean`, flashes, and prints the server-start commands.
- The board reboots and shows the OLED splash: `TRAceON:` / `logger`.

## 1. Start the broker (Docker)

```bash
# Already running from a previous session? Check first:
docker ps --filter name=traceon-broker        # expect 0.0.0.0:1883->1883

# If not running, start it:
docker run -d --name traceon-broker -p 1883:1883 \
  -v "$PWD/scripts/mosquitto-docker.conf:/mosquitto/config/mosquitto.conf" \
  eclipse-mosquitto:2
# (if the container exists but is stopped:  docker start traceon-broker)
```

## 2. Confirm the board is connected and publishing

```bash
timeout 10 mosquitto_sub -h localhost -t 'TRAceON/logs' -C 1
```
You should see one ISO `LogEntry` JSON within a few seconds. If nothing:
- give it ~20 s after a reset (Wi-Fi → DHCP → SNTP → MQTT);
- confirm the Mac's IP still matches what you flashed (`ipconfig getifaddr en0`);
- the firmware auto-reconnects, so once the broker/network is right it recovers
  on its own.

## 3. Start the server + open the dashboard

```bash
cd telemetry-server-java
export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || echo /opt/homebrew/opt/openjdk)"
mvn -B clean package      # first time only; after that just run the jar
TRACEON_MQTT_HOST=localhost java -jar target/telemetry-server-java-jar-with-dependencies.jar
```

Then open the live dashboard in a browser:

> **http://localhost:8082/dashboard**

You'll see telemetry metrics updating and a color-coded ISO log stream scrolling
(the board emits a varied demo stream ~1/sec).

## 4. The "money" moments

**a) Send a command to the board's OLED** (new terminal):
```bash
mosquitto_pub -h localhost -t 'TRAceON/incoming' -m 'TRAceON'
```
→ the OLED shows `TRAceON:` / `TRAceON`.

**b) Inject a specific severity to show up on the dashboard:**
```bash
./scripts/send-log.sh DLT_ERROR SensorTask "Humidity sensor returned no data"
./scripts/send-log.sh DLT_WARN  Thermal    "temperature trending high"
```
→ these appear immediately in the dashboard's log column, color-coded.

**c) (Optional) Log forwarding to a sink** — see TESTING-LOG-FORWARDING.md for the
local demo (`scripts/forwarding-demo/start.sh`).

---

## Fallback — no board / board can't join the Wi-Fi

The whole server + dashboard story still demos without the board, using a mock
publisher on the Mac:

```bash
# 1. broker (step 1) + server (step 3) as above
# 2. publish a cycling demo log stream to the broker:
./scripts/mock-log-publisher.sh        # ~1 log/sec across severities/contexts
```
The dashboard fills with logs exactly as if the board were publishing. (You lose
only the real sensor telemetry + the OLED command demo.)

---

## Reset / recovery cheatsheet

| Symptom | Fix |
|---|---|
| Dashboard empty, `has_data:false` | board not publishing — check step 2; press board RESET |
| Board silent on serial after flash | press RESET (board doesn't always auto-start post-flash) |
| `mqtt_connected:false` in `/health` | server can't reach broker — is `traceon-broker` up on 1883? |
| Board won't join Wi-Fi | 2.4 GHz only; no captive portal; re-run step 0 with correct creds |
| Changed network/IP | re-run step 0 (reflash); broker needs no change (binds 0.0.0.0) |

## Quick reference

| Thing | Value |
|---|---|
| Broker | Docker `traceon-broker`, `localhost:1883` |
| Topics | `TRAceON/sensor-data`, `TRAceON/logs`, `TRAceON/incoming` |
| Java server + dashboard | `:8082`, dashboard at `/dashboard` |
| Python server (alt) | `:8083`, API docs at `/docs` |
| Serial | `/dev/cu.usbmodem*` @ 115200 |
| Run all tests | `./scripts/run-tests.sh` |

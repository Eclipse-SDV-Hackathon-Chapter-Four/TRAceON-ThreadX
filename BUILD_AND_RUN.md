# AZ3166 + MQTT — Build, Run & Debug Notes

> Team **TRAceON** — MXChip AZ3166 (Azure RTOS / ThreadX) telemetry over MQTT to a
> Mac-hosted broker. Verified working end-to-end on 2026-10-06.
>
> Covers: (1) building & flashing the firmware, (2) running the MQTT broker in Docker,
> (3) watching/sending MQTT, (4) serial-console debugging, (5) the gotchas we hit.
>
> **Mac-side HTTP server** that consumes this telemetry (FastAPI, with a SOVD-flavored
> surface for future OpenSOVD): see [`telemetry-server/README.md`](telemetry-server/README.md).

---

## 0. Environment (one-time)

- **Board:** MXChip AZ3166 (STM32F412, Cortex-M4). Connects over USB (ST-Link/DAPLink):
  exposes a serial port, a mass-storage drive for flashing, and SWD debug.
- **Arm toolchain:** installed at
  `/Applications/ArmGNUToolchain/14.2.rel1/arm-none-eabi/bin/` (NOT on PATH by default).
- **Build tools:** CMake + Ninja (`brew install cmake ninja`).
- **Broker:** Docker Desktop (running) + `eclipse-mosquitto:2` image.
- **Mac LAN IP (en0):** `192.168.88.254` — the board targets this as the broker.
  Re-check with `ipconfig getifaddr en0` (DHCP can change it).

> ⚠️ **Managed Mac:** this machine is under MDM. Two consequences we hit:
> 1. Docker injects an (often-unreachable) HTTP proxy — pass empty proxy env for container builds.
> 2. The macOS application firewall **cannot be changed from the CLI** and blocks the
>    *native* mosquitto binary's incoming connections. → We run mosquitto in **Docker**,
>    whose published ports bypass the per-app firewall. (This is why Option "Docker broker" works.)

---

## 1. Build & flash the AZ3166 firmware

### 1.1 Build

```bash
export ARM_GCC_PATH="/Applications/ArmGNUToolchain/14.2.rel1/arm-none-eabi/bin"
cd ~/repos/IEH/TRAceON-ThreadX
./scripts/build.sh <app> [clean|rebuild]
```

- `<app>` is `mqtt` (the only app config; the default). The upstream sample's
  `starter`/`telemetry`/`arcade` configs were removed — see the README.
- The toolchain is found via `ARM_GCC_PATH` (checked first) or `arm-none-eabi-gcc` on PATH.
  If neither is set, CMake aborts with "Unable to find ARM GCC".
- Output: `build/app/mxchip_threadx.{elf,bin,hex}`. Release build, `-Os -flto`.
- **`clean` is important after moving the project** — a stale `CMakeCache.txt` keeps the
  old absolute paths. Symptom: objcopy prints a path from the old location. Fix:
  `./scripts/build.sh mqtt clean`.

Typical sizes for the `mqtt` app: RAM ~68% (128 KB), FLASH ~33% (1 MB).
(A suspiciously tiny build — e.g. FLASH 2% — means a stale cache skipped the network stack.)

### 1.2 Flash

The AZ3166 flashes by **copying the `.bin` onto its USB mass-storage drive**.

```bash
# Board must be plugged in and mounted at /Volumes/AZ3166
ls /Volumes/AZ3166 && echo mounted
./scripts/deploy.sh            # copies build/app/*.bin to /Volumes/AZ3166
```

After the copy, DAPLink programs the flash and the board resets automatically
(the drive unmounts/remounts — normal).

---

## 2. Run the MQTT broker in Docker (the working setup)

Native mosquitto is blocked by the managed-Mac firewall, so we run it in Docker.

### 2.1 Start

```bash
cd ~/repos/IEH/TRAceON-ThreadX
# Make sure nothing native holds 1883:
pkill -x mosquitto 2>/dev/null

docker rm -f traceon-broker 2>/dev/null
docker run -d --name traceon-broker -p 1883:1883 \
  -v "$PWD/scripts/mosquitto-docker.conf:/mosquitto/config/mosquitto.conf" \
  eclipse-mosquitto:2
```

`scripts/mosquitto-docker.conf` sets `listener 1883 0.0.0.0`, `allow_anonymous true`,
and logs to stdout. **Dev-only** config (unauthenticated LAN access).

### 2.2 Verify / manage

```bash
docker ps --filter name=traceon-broker          # should show 0.0.0.0:1883->1883
docker logs -f traceon-broker                    # live connection + publish log
docker stop traceon-broker                       # stop
docker start traceon-broker                      # restart (config persists)
docker rm -f traceon-broker                      # remove
```

A successful board connection looks like this in the logs:
```
New client connected from 192.168.65.1:xxxxx as TRAceON (p4, c0, k300)
Sending CONNACK to TRAceON (0, 0)
Received SUBSCRIBE from TRAceON  ->  TRAceON/incoming
Received PUBLISH from TRAceON ... 'TRAceON/sensor-data' (128 bytes)
Sending PUBACK to TRAceON (m2, rc0)
```
(The board shows as `192.168.65.1` — Docker's NAT gateway. That's expected.)

> ⚠️ Only ONE broker on 1883 at a time. Don't run native mosquitto and the container together.

---

## 3. MQTT topics & how to watch / send

Topics are derived from `MQTT_CLIENT_NAME` in `app/mqtt/cloud_config.h`:

| Direction            | Topic                 | Meaning                                        |
|----------------------|-----------------------|------------------------------------------------|
| Board → Mac (publish)| `TRAceON/sensor-data` | Sensor telemetry (plain-text, ~128 B, QoS1)    |
| Mac → Board (subscribe)| `TRAceON/incoming`  | Commands to the board; shown on the OLED       |

### Watch telemetry
```bash
mosquitto_sub -h localhost -t 'TRAceON/sensor-data' -v
# everything the team uses:
mosquitto_sub -h localhost -t 'TRAceON/#' -v
```
Payload (plain text, published only when readings change beyond thresholds):
```
Pressure: 1013.25
Temperature: 23.40
Humidity: 41.20
Acceleration: 1.20, -0.30, 980.10
Magnetic: 120.00, -45.00, 310.00
```
Move/breathe on the board to force a change if it looks idle.

### Send a message to the board's OLED
```bash
mosquitto_pub -h localhost -t 'TRAceON/incoming' -m 'Hello Team 11'
```
Shows `TRAceON:` + the text wrapped across the OLED (see `screen_print_wrapped()`
in `app/common/screen.c`, called from `receive_message()` in `app/mqtt/mqtt_client.c`).
Display fits ~11 chars/line × 3 body lines ≈ 33 chars; longer text is truncated on
screen (full text still goes to the serial console).

---

## 4. Serial-console debugging (how to actually read it on this Mac)

The firmware logs over USB serial. **Settings: 115200 8N1** (set in
`app/common/board_init.c` → `UART_Console_Init`, `USART6`).

- **Device:** `/dev/cu.usbmodem*` (use `cu.`, not `tty.`). Find it with:
  ```bash
  ls /dev/cu.usbmodem*
  ```
- **`screen` / `cat` were unreliable here** (empty captures). The method that worked
  is a short Python `termios` reader at 115200. Minimal interactive option:
  ```bash
  # interactive terminal (exit: Ctrl-A then K, then y)
  screen /dev/cu.usbmodem21103 115200
  # or, if screen misbehaves, a one-shot capture:
  python3 - /dev/cu.usbmodem21103 <<'PY'
  import sys,time,termios,os
  fd=os.open(sys.argv[1],os.O_RDWR|os.O_NOCTTY|os.O_NONBLOCK)
  a=termios.tcgetattr(fd); a[0]=a[1]=a[3]=0
  a[2]=termios.CLOCAL|termios.CREAD|termios.CS8; a[4]=a[5]=115200
  termios.tcsetattr(fd,termios.TCSANOW,a)
  end=time.time()+20
  while time.time()<end:
      try:
          c=os.read(fd,256)
          if c: sys.stdout.write(c.decode('ascii','replace')); sys.stdout.flush()
      except BlockingIOError: time.sleep(0.03)
  PY
  ```
- **Garbage characters** = wrong baud / clock. Clean ASCII only appears at 115200.
- Press the board **RESET** button ~2 s into a capture to catch the one-time boot banner
  (WiFi/DHCP/DNS/SNTP/MQTT connect lines).

A healthy boot sequence prints:
```
Scanning I2C bus ...
Starting telemetry thread
Starting Eclipse ThreadX MQTT thread
Initializing WiFi ... SUCCESS
Connecting WiFi 'Hackathon-Team-11' ... SUCCESS
Initializing DHCP ... IP address: 192.168.88.xxx ... SUCCESS
Initializing DNS ... SUCCESS
Initializing SNTP ... SUCCESS
Creating MQTT client /  MQTT client created
MQTT Client connected.
Subscribed to topic TRAceON/incoming.
Waiting for messages
Published message.
```

---

## 5. Config reference — `app/mqtt/cloud_config.h`

```c
#define WIFI_SSID            "Hackathon-Team-11"
#define WIFI_PASSWORD        "SDVTeam-123456"      // ⚠ plaintext — do NOT commit real creds
#define WIFI_MODE            WPA2_PSK_AES
#define MQTT_CLIENT_NAME     "TRAceON"             // drives both topic names
#define MQTT_LOCAL_BROKER_IP (IP_ADDRESS(192,168,88,254))  // Mac en0 IP
#define MQTT_SUBSCRIBE_TOPIC MQTT_CLIENT_NAME "/incoming"
#define MQTT_PUBLISH_TOPIC   MQTT_CLIENT_NAME "/sensor-data"
```

Change the client/team name in ONE place (`MQTT_CLIENT_NAME`) — topics follow.
If the Mac's IP changes, update `MQTT_LOCAL_BROKER_IP` and rebuild+flash.

### Build-time overrides (no file edit needed)

The WiFi SSID/password, broker IP, and MQTT client name can be set **at build
time** via environment variables, so you don't edit `cloud_config.h` per network.
Each overrides the matching `#ifndef` default in `cloud_config.h`; any variable
you omit falls back to that default.

**Every build-time variable:**

| Env var | Overrides (`cloud_config.h`) | Default | Format | Notes |
|---|---|---|---|---|
| `WIFI_SSID` | `WIFI_SSID` | `Hackathon-Team-11` | string | 2.4 GHz networks only (board limitation) |
| `WIFI_PASSWORD` | `WIFI_PASSWORD` | `SDVTeam-123456` | string | kept out of git when passed this way |
| `BROKER_IP` | `MQTT_LOCAL_BROKER_IP` | `192.168.88.254` | dotted IPv4 `a.b.c.d` | split into octets for `IP_ADDRESS(...)`; must be 4 octets |
| `MQTT_CLIENT_NAME` | `MQTT_CLIENT_NAME` | `TRAceON` | string | also drives topic names (`<name>/sensor-data`, `/logs`, `/incoming`) |

**Positional argument** (not an env var): the first argument to `build.sh` is the
app config — only `mqtt` exists (and is the default). Second arg: `clean` or
`rebuild`.

**NOT overridable at build time** (edit `cloud_config.h` directly if you need to
change them): `HOSTNAME` and `WIFI_MODE` (defaults `eclipse-threadx` /
`WPA2_PSK_AES`).

**Fully-explicit example — all four variables, from the repo root:**

```bash
export ARM_GCC_PATH=/Applications/ArmGNUToolchain/14.2.rel1/arm-none-eabi/bin

WIFI_SSID='MyNetwork' \
WIFI_PASSWORD='mypassword' \
BROKER_IP='192.168.1.50' \
MQTT_CLIENT_NAME='TRAceON' \
  ./scripts/build.sh mqtt clean

./scripts/deploy.sh            # copies build/app/mxchip_threadx.bin to /Volumes/AZ3166
```

Minimal example — just the two things that usually change per venue (WiFi +
broker), everything else default:

```bash
WIFI_SSID='MyNetwork' WIFI_PASSWORD='mypassword' BROKER_IP='192.168.1.50' \
  ./scripts/build.sh mqtt clean
```

**Important:**
- **`clean` (or `rebuild`) is REQUIRED** after changing any of these — they are
  applied at CMake **configure** time, so a plain incremental build does not
  re-read them. Omitting `clean` silently keeps the previous values.
- Mechanism: `build.sh` forwards each set variable as `-D` to CMake →
  `target_compile_definitions` on the firmware target → the `#ifndef` guards in
  `cloud_config.h`. You can confirm what got baked in with:
  `strings build/app/mxchip_threadx.elf | grep -E 'MyNetwork|<your-ssid>'`.
- Credentials passed this way live only on your build command line, **not** in
  committed source.

---

## 6. MQTT error codes we decoded (NetX Duo, `nxd_mqtt_client.h`)

| Code (dec / hex) | Meaning                         | What it told us                      |
|------------------|---------------------------------|--------------------------------------|
| 65538 / 0x10002  | `NXD_MQTT_NOT_CONNECTED`        | publish/subscribe before a session   |
| 65543 / 0x10007  | `NXD_MQTT_COMMUNICATION_FAILURE`| TCP to broker failed → **firewall**  |

The 65543 on connect was the root cause: the native-mosquitto firewall block +
stealth mode dropped the board's TCP SYN. Running the broker in Docker fixed it.

---

## 7. Quick end-to-end checklist

1. `docker start traceon-broker` (or the `docker run …` in §2.1).
2. `export ARM_GCC_PATH=/Applications/ArmGNUToolchain/14.2.rel1/arm-none-eabi/bin`
3. `./scripts/build.sh mqtt` → `./scripts/deploy.sh`
4. `mosquitto_sub -h localhost -t 'TRAceON/#' -v`
5. `docker logs -f traceon-broker` to confirm `TRAceON` connects & publishes.
6. `mosquitto_pub -h localhost -t 'TRAceON/incoming' -m 'hi'` → check the OLED.

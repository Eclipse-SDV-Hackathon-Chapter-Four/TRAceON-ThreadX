# Plan: Testing TRAceON components with Eclipse openDuT

**Status:** proposal / next-steps (not yet implemented).
**Goal:** use Eclipse **openDuT** to give TRAceON a reliable, repeatable,
network-agnostic test setup — specifically to make the **log-forward hop between
two machines** work regardless of the local network, solving the cross-machine
reachability problems we hit at the venue (AP client isolation, host firewalls,
NAT) without touching any TRAceON code.

**Assumed topology (decided):** **two native x86_64 Linux machines.** This is the
simplest, fully-supported setup and avoids every obstacle we ran into on macOS
and would have hit with WSL2 (see "Why Linux" and the historical note below).

All CLEO commands below were taken from the openDuT user manual
(<https://opendut.eclipse.dev/book/>) and the Ethernet usage example. Flags are
accurate to that reference; still run `opendut-cleo <subcommand> --help` on your
installed version to confirm.

---

## What openDuT is (and the honest fit for us)

openDuT builds an **end-to-end-encrypted private network** between devices under
test using **EDGAR** edge agents. Components:

- **CARL** — central backend (manages peers/clusters), serves the **LEA** web UI.
- **EDGAR** — a **Linux** edge agent installed next to each device/host; it is the
  *peer* in the network. Joins a NetBird/**WireGuard** mesh.
- **CLEO** — CLI against CARL (create/list/describe/delete peers, devices,
  clusters, container-executors; generate setup-strings).
- **LEA** — web UI equivalent of CLEO.

**How the mesh works (from the docs):** EDGAR tunnels **Layer-2 Ethernet** traffic
between peers using **GRE encapsulated in WireGuard** (star topology, relayed via
CARL if no direct path), bridging a peer's interface into `br-opendut` so peers
reach each other over the overlay regardless of physical location.

### Honest caveats for TRAceON

1. **EDGAR is Linux-only and host-side.** The **AZ3166 cannot run EDGAR** (bare-
   metal ThreadX), so the board is **not** a mesh peer. It keeps publishing to the
   broker over plain Wi-Fi, exactly as today — openDuT only meshes the two Linux
   hosts, i.e. the **computer↔computer forward hop**.
2. **The board → broker hop still rides plain Wi-Fi.** openDuT does not help that
   leg; it rescues the server → sink forward hop between the two machines.
3. **Deployment is non-trivial:** CARL brings up Keycloak + NetBird (+ optional
   telemetry) via Docker Compose. A real deployment, best treated as
   post-hackathon work.

### Why two native Linux machines

| Concern | macOS (tried) | WSL2 | **Two native Linux** |
|---|---|---|---|
| CARL/Keycloak images (amd64) | **emulated → blocked** | native on Linux | **native ✓** |
| EDGAR runnable | **no (macOS)** | needs custom kernel | **native ✓** |
| WireGuard/GRE/netfilter | n/a | WSL2 gaps likely | **stock/available ✓** |
| Docker mount/network quirks | several | some | **none ✓** |
| TRAceON code changes | none | none | **none** (forward URL → overlay) |

The macOS path is a proven dead end (amd64-under-emulation; EDGAR can't run on
macOS at all — see the historical note at the bottom). WSL2 is viable but needs a
custom kernel for WireGuard/GRE/netfilter. Two native Linux hosts sidestep all of
it, and it's openDuT's primary supported setup (their hardware guide uses Linux
hosts / Raspberry Pis for EDGAR).

---

## Target topology

```
   AZ3166 (ThreadX) ── plain Wi-Fi / MQTT (NOT meshed) ──┐
                                                         ▼
┌──────────────────────────────┐  GRE/WireGuard  ┌──────────────────────────────┐
│ linux-a  (x86_64)             │◀═══ overlay ═══▶│ linux-b  (x86_64)             │
│  • CARL + Keycloak + NetBird  │                 │  • EDGAR peer "linux-b"       │
│  • Mosquitto broker (1883)    │                 │  • Rust log sink :8080        │
│  • telemetry server (:8082)   │                 │                               │
│  • EDGAR peer "linux-a"       │                 └──────────────────────────────┘
└──────────────────────────────┘
```

- **linux-a** hosts the backend (CARL/Keycloak/NetBird), the broker, the
  telemetry server, and one EDGAR peer.
- **linux-b** hosts the second EDGAR peer and the Rust sink.
- The **AZ3166** publishes to the broker over plain Wi-Fi (unchanged, not meshed).

> CARL may instead live on a third host (or cloud VM) both peers can reach;
> co-locating it on linux-a is just the fewest-moving-parts option. A reachable
> CARL also lets it relay WireGuard when peers have no direct path.

---

## Plan

### Phase 0 — Prerequisites (both machines)
```bash
# Docker + Docker Compose (for CARL, on linux-a).
# EDGAR needs these kernel modules on each machine:
sudo modprobe wireguard && echo "wireguard ok"
sudo modprobe ip_gre    && echo "ip_gre ok"
# can-utils only if you use CAN (we don't): EDGAR setup takes --skip-can
```

### Phase 1 — linux-a: deploy CARL (backend)
```bash
git clone https://github.com/eclipse-opendut/opendut.git && cd opendut
export OPENDUT_REPO_ROOT=$(git rev-parse --show-toplevel)
# provision secrets
docker compose --file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/docker-compose.yml \
  --env-file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/.env.development \
  up --build provision-secrets
docker cp opendut-provision-secrets:/provision/ \
  $OPENDUT_REPO_ROOT/.ci/deploy/localenv/data/secrets/
# bring up the stack (CARL + Keycloak + NetBird + LEA [+ telemetry])
docker compose --file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/docker-compose.yml \
  --env-file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/.env.development \
  --env-file $OPENDUT_REPO_ROOT/.ci/deploy/localenv/data/secrets/.env \
  up --detach --build
```
- On native x86_64 the images run natively — **no emulation**, so the Keycloak /
  CARL-init slowness we hit on the Mac does not occur.
- Resolve the `*.opendut.local` domains (real DNS, or `/etc/hosts` → the host IP).
- Secrets (incl. the CLEO OIDC client secret + the CA at `secrets/pki/`) land in
  `.ci/deploy/localenv/data/secrets/`.
- **Only if** you're behind a corporate proxy that injects into Docker, pass empty
  proxy build-args: `--build-arg http_proxy= --build-arg https_proxy=
  --build-arg HTTP_PROXY= --build-arg HTTPS_PROXY=` (see historical note).

### Phase 2 — linux-a: configure CLEO
```bash
export OPENDUT_CLEO_NETWORK_CARL_HOST=opendut.local
export OPENDUT_CLEO_NETWORK_CARL_PORT=443
export OPENDUT_CLEO_NETWORK_OIDC_CLIENT_ID=opendut-cleo-client
export OPENDUT_CLEO_NETWORK_OIDC_CLIENT_SECRET=<from secrets/.env>
export SSL_CERT_FILE=.../secrets/pki/opendut-ca.pem    # the openDuT CA
opendut-cleo list peers      # smoke test
```

### Phase 3 — linux-a: create the two peers
```bash
opendut-cleo create peer --name linux-a --location lab   # backend + server + sink-source
opendut-cleo create peer --name linux-b --location lab   # sink host
opendut-cleo list peers      # note the two PeerIDs: <A_ID>, <B_ID>
```

### Phase 4 — add a network interface to each peer
openDuT requires an interface to bridge; use the Ethernet form.
```bash
opendut-cleo create network-interface --peer-id <A_ID> --type eth --name eth0
opendut-cleo create network-interface --peer-id <B_ID> --type eth --name eth0
```
> Our forwarding only needs host↔host IP reachability over the overlay, not a
> bridged DuT. The interface is required by the model; the overlay IPs are what
> we actually use.

### Phase 5 — generate setup-strings + install EDGAR on each host
```bash
opendut-cleo generate-setup-string --id <A_ID>   # -> SETUP_A
opendut-cleo generate-setup-string --id <B_ID>   # -> SETUP_B
```
Download EDGAR from LEA → Downloads, then on each machine run the managed setup
with that host's string:
```bash
sudo ./opendut-edgar setup managed --skip-can     # paste SETUP_A on linux-a, SETUP_B on linux-b
ip link        # verify wt0 (WireGuard) + br-opendut appear
sudo wg        # verify the WireGuard peer link
```

### Phase 6 — create + deploy the cluster (on linux-a)
```bash
opendut-cleo create cluster-configuration --name traceon \
    --leader-id <A_ID> --peer-ids <A_ID>,<B_ID>      # confirm exact flags via --help
opendut-cleo list cluster-configurations             # note <CLUSTER_ID>
opendut-cleo create cluster-deployment --id <CLUSTER_ID>
```
Deploying establishes the GRE-over-WireGuard links between the two EDGARs.

> Alternatively declare peers + cluster in one YAML and `opendut-cleo apply <file>`
> (kubectl-style); the manual documents the `PeerDescriptor` / `ClusterDescriptor`
> schema.

### Phase 7 — read overlay IPs + pre-flight
```bash
# on each machine:
ip address show br-opendut      # or: ip address show wt0   -> note linux-b's overlay IP
# from linux-a, before enabling forwarding (same discipline as TESTING-LOG-FORWARDING.md):
nc -vz -w 3 <overlay-linux-b> 8080                 # must say "succeeded!"
route -n get <overlay-linux-b> | grep interface    # must be the openDuT iface, not a VPN
```

### Phase 8 — point forwarding at the overlay IP
**Only runtime config changes — no firmware/server code change.**
```bash
# linux-b: start the Rust sink on 0.0.0.0:8080 (reachable over the overlay)
# linux-a: start the telemetry server, then:
curl -s -X POST localhost:8082/logs/forwarding/start \
     -H 'content-type: application/json' \
     -d '{"url":"http://<overlay-linux-b>:8080/internal/logs"}'
```
or bake it in at launch:
`TRACEON_LOG_FORWARD_URL=http://<overlay-linux-b>:8080/internal/logs`.

Data path: **AZ3166 → linux-a broker (Wi-Fi) → telemetry server → POST over the
openDuT overlay → Rust sink on linux-b.** The local LAN between the two Linux
boxes is irrelevant — openDuT tunnels it, so AP isolation / host firewalls / NAT
don't matter.

---

## Optional: automated test via openDuT (VIPER / container-executor)

openDuT runs **containerized test applications** on a peer and uploads results to
a WebDAV dir (`/results/` + a `.results_ready` marker). We could codify a
**forward-and-verify** test:
```bash
opendut-cleo create container-executor \
    --peer-id <B_ID> --engine docker \
    --name traceon-forward-check --image <our-test-image> \
    --results-url http://nginx-webdav.opendut.local/
```
The test container would: run the sink, trigger log emission (board or
`send-log.sh`), assert the sink received the expected ISO LogEntries with the
right DLT severities, then write results. Deploying the cluster triggers it.

---

## What changes vs. stays the same

| Piece | Change for openDuT? |
|---|---|
| AZ3166 firmware | **None** — plain Wi-Fi/MQTT to the broker |
| Mosquitto broker | None |
| Telemetry server (Py/Java) code | **None** |
| Rust sink code | None |
| **Forward URL** | `192.168.x` → **overlay IP of linux-b** (only functional change) |
| New infra | CARL+Keycloak+NetBird on linux-a; EDGAR on both Linux hosts |

## Open items to validate early
- **Kernel modules** on both hosts: `wireguard`, `ip_gre` load (Phase 0).
- **Exact CLEO flag names** (`--peer-ids` / `--leader-id` / cluster subcommand
  spelling) — confirm with `--help` on the installed version.
- **CARL reachability**: both EDGARs must reach CARL; if the two Linux hosts are
  on different networks, host CARL somewhere both can reach (it can also relay the
  WireGuard tunnel).

---

## Historical note — the macOS attempt (2026-10-07, why we chose Linux)

We first tried a **Mac-only** bring-up of the CARL backend (Apple Silicon, arm64).
It is recorded here because the fixes are reusable and the blocker is the reason
we standardized on Linux.

**What worked on the Mac:** cloned the repo; `provision-secrets` + the image build
succeeded **once the injected Docker proxy was neutralized** (empty proxy
build-args — same issue as our own images; without it `apt-get` fails "Unable to
locate package"). Core services came up healthy (traefik, keycloak,
keycloak-postgres, netbird-signal/relay); `keycloak-init` completed.

**Docker-Desktop-on-Mac-specific fixes we needed (NOT needed on native Linux):**
1. **Proxy** — empty proxy build-args.
2. **Lean subset** — `OPENDUT_LOCALENV_TELEMETRY_ENABLED=0` zeroes the Grafana/
   Prometheus/Loki/Tempo/Alloy/otel replicas, but `grafana depends_on loki` then
   fails `up`; workaround was starting core services by name with `--no-deps`.
3. **Cert bind mount** — compose binds host path `/provision`
   (`SHARED_CERTS_HOST_DIR=/provision`), which isn't shared on Docker Desktop for
   Mac → "mounts denied"; workaround was overriding `SHARED_CERTS_HOST_DIR` to the
   real host PKI dir (`.ci/deploy/localenv/data/secrets/pki`).

**The blocker that ended the Mac path:** the localenv images are **amd64-only**;
on arm64 they run under **QEMU emulation**. Emulated **Keycloak was far too slow**
(traefik logged ~3 s/request, `DownstreamStatus 499`), so CARL's init loop
(**"Waiting for https://auth.opendut.local/…"**) timed out (~6 s) and CARL never
became `healthy`. This is an architecture/emulation wall, not a config bug — and
separately, **EDGAR has no macOS build at all**, so the Mac could never host a
mesh peer regardless. Hence: **two native x86_64 Linux machines.**

**Teardown used:** `docker compose … down --remove-orphans` (our `traceon-broker`
is a separate stack and was unaffected).

# TRAceON Python telemetry server — container image.
#
# Running in Docker lets LAN peers reach the server: Docker's published ports
# bypass the macOS application firewall that blocks native processes on this
# managed Mac (same reason the broker runs in Docker).
#
# The injected Docker HTTP proxy (see ~/.docker/config.json) is unreachable from
# build containers, so we accept empty proxy build-args to let pip reach PyPI.
FROM python:3.12-slim

ARG http_proxy=
ARG https_proxy=
ARG HTTP_PROXY=
ARG HTTPS_PROXY=

WORKDIR /app

# Install dependencies first (layer caching).
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# App code.
COPY traceon_server ./traceon_server

# Defaults; override at `docker run` time as needed.
#  - Reach the broker (a container publishing 1883 on the host) via the host.
#  - Neutralize the runtime HTTP proxy that Docker Desktop injects from
#    ~/.docker/config.json (proxies.default -> 192.168.122.1:3128). That proxy is
#    unreachable from containers on this managed Mac, so without this, outbound
#    POSTs (log forwarding) hang until timeout. We POST directly instead.
ENV TRACEON_MQTT_HOST=host.docker.internal \
    TRACEON_MQTT_PORT=1883 \
    TRACEON_HTTP_HOST=0.0.0.0 \
    TRACEON_HTTP_PORT=8083 \
    HTTP_PROXY= \
    HTTPS_PROXY= \
    http_proxy= \
    https_proxy= \
    NO_PROXY=*

EXPOSE 8083

CMD ["python", "-m", "traceon_server"]

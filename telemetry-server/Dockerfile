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
ENV TRACEON_MQTT_HOST=host.docker.internal \
    TRACEON_MQTT_PORT=1883 \
    TRACEON_HTTP_HOST=0.0.0.0 \
    TRACEON_HTTP_PORT=8080

EXPOSE 8080

CMD ["python", "-m", "traceon_server"]

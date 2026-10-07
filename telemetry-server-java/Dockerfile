# TRAceON Java (Jakarta) telemetry server — container image.
#
# Running in Docker lets LAN peers reach the server (Docker's published ports
# bypass the macOS application firewall on this managed Mac).
#
# Multi-stage: build the fat jar with Maven, then run on a slim JRE.
# Empty proxy build-args let Maven/apt reach the network from build containers.

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
ARG http_proxy=
ARG https_proxy=
ARG HTTP_PROXY=
ARG HTTPS_PROXY=

WORKDIR /src
# Resolve dependencies first (layer caching), then build.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -q clean package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/telemetry-server-java-jar-with-dependencies.jar ./server.jar

# Reach the broker (a container publishing 1883 on the host) via the host.
# Neutralize the runtime HTTP proxy that Docker Desktop injects from
# ~/.docker/config.json (proxies.default -> 192.168.122.1:3128). That proxy is
# unreachable from containers on this managed Mac, so without this, outbound
# POSTs (log forwarding) hang until timeout. java.net.http honors these too.
ENV TRACEON_MQTT_HOST=host.docker.internal \
    TRACEON_MQTT_PORT=1883 \
    TRACEON_HTTP_PORT=8082 \
    HTTP_PROXY= \
    HTTPS_PROXY= \
    http_proxy= \
    https_proxy= \
    NO_PROXY=*

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "server.jar"]

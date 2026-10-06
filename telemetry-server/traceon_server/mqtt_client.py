"""MQTT client wrapper: subscribes to the board's sensor-data topic, updates the
store, and can publish commands back to the board.

Uses paho-mqtt 2.x (CallbackAPIVersion.VERSION2). Runs its own network loop in a
background thread (loop_start), so it coexists with the async HTTP server.
"""
from __future__ import annotations

import logging

import paho.mqtt.client as mqtt

from .config import settings
from .parser import parse_payload
from .store import store
from .logs import log_store, parse_log
from .broadcaster import broadcaster
from .envelope import event_envelope
from .forwarder import forwarder

logger = logging.getLogger("traceon.mqtt")


class MqttClient:
    def __init__(self) -> None:
        self._client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id="traceon-http-server",
        )
        self._client.on_connect = self._on_connect
        self._client.on_disconnect = self._on_disconnect
        self._client.on_message = self._on_message
        self._connected = False

    # ---- lifecycle -------------------------------------------------------
    def start(self) -> None:
        logger.info("Connecting to broker %s:%s", settings.mqtt_host, settings.mqtt_port)
        # connect_async + loop_start so startup never blocks if broker is down.
        self._client.connect_async(settings.mqtt_host, settings.mqtt_port, keepalive=60)
        self._client.loop_start()

    def stop(self) -> None:
        self._client.loop_stop()
        try:
            self._client.disconnect()
        except Exception:  # noqa: BLE001 - best-effort on shutdown
            pass

    @property
    def connected(self) -> bool:
        return self._connected

    # ---- publishing (commands to the board) ------------------------------
    def publish_command(self, message: str) -> bool:
        info = self._client.publish(settings.command_topic, payload=message, qos=1)
        return info.rc == mqtt.MQTT_ERR_SUCCESS

    # ---- callbacks -------------------------------------------------------
    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        if reason_code == 0:
            self._connected = True
            logger.info("Connected; subscribing to %s and %s",
                        settings.sensor_topic, settings.log_topic)
            client.subscribe(settings.sensor_topic, qos=1)
            client.subscribe(settings.log_topic, qos=1)
        else:
            self._connected = False
            logger.warning("Connect failed: %s", reason_code)

    def _on_disconnect(self, client, userdata, *args):
        self._connected = False
        logger.warning("Disconnected from broker")

    def _on_message(self, client, userdata, msg):
        try:
            text = msg.payload.decode("utf-8", errors="replace")
        except Exception:  # noqa: BLE001
            logger.exception("Failed to decode payload")
            return

        if msg.topic == settings.log_topic:
            entry = log_store.add(parse_log(text))
            # SSE stream delivers each log wrapped in an ISO EventEnvelope.
            broadcaster.publish("logs", event_envelope(entry))
            # Separate, optional sink: forward the raw LogEntry via POST (if started).
            forwarder.submit(entry)
            logger.debug("Log entry: %s", entry)
        else:  # sensor/telemetry topic
            fields = parse_payload(text)
            store.update(fields, raw=text)
            broadcaster.publish("telemetry", store.latest())
            logger.debug("Updated telemetry: %s", fields)


# Module-level singleton.
mqtt_client = MqttClient()

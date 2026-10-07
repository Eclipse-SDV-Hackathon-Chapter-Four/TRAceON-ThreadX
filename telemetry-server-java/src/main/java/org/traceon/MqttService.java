/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Contributors to the Eclipse Foundation
 * SPDX-License-Identifier: MIT
 * Portions of this file were generated with AI assistance.
 */
/*
 * Eclipse Paho MQTT service: subscribes to the sensor topic, parses the board's
 * plain-text payload into TYPED fields, and can publish commands to the board.
 */
package org.traceon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public final class MqttService {

    public static final MqttService INSTANCE = new MqttService();

    private final String brokerHost = Config.env("TRACEON_MQTT_HOST", "localhost");
    private final int brokerPort = Integer.parseInt(Config.env("TRACEON_MQTT_PORT", "1883"));
    private final String sensorTopic = Config.env("TRACEON_SENSOR_TOPIC", "TRAceON/sensor-data");
    private final String logTopic = Config.env("TRACEON_LOG_TOPIC", "TRAceON/logs");
    private final String commandTopic = Config.env("TRACEON_COMMAND_TOPIC", "TRAceON/incoming");

    private MqttClient client;

    private MqttService() {}

    public String sensorTopic() { return sensorTopic; }
    public String logTopic() { return logTopic; }
    public String commandTopic() { return commandTopic; }

    public void start() throws MqttException {
        String uri = "tcp://" + brokerHost + ":" + brokerPort;
        client = new MqttClient(uri, "traceon-java", new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);
        client.connect(opts);
        TelemetryStore.INSTANCE.setMqttConnected(true);

        // Telemetry topic -> store + SSE "telemetry" channel.
        client.subscribe(sensorTopic, 1, (IMqttMessageListener) (topic, msg) -> {
            TelemetryStore.INSTANCE.update(parse(new String(msg.getPayload())));
            SseRegistry.INSTANCE.publish("telemetry", Json.toJson(TelemetryStore.INSTANCE.fields()));
        });

        // Log topic -> ring buffer + SSE "logs" channel (SEPARATE from telemetry).
        client.subscribe(logTopic, 1, (IMqttMessageListener) (topic, msg) -> {
            java.util.Map<String, Object> entry = LogStore.INSTANCE.add(new String(msg.getPayload()));
            // SSE delivers each log wrapped in an ISO EventEnvelope.
            SseRegistry.INSTANCE.publish("logs", Json.toJson(EventEnvelope.wrap(entry)));
            // Separate optional sink: forward the raw LogEntry via POST (if started).
            LogForwarder.INSTANCE.submit(entry);
        });

        System.out.println("Subscribed to " + sensorTopic + " and " + logTopic + " at " + uri);
    }

    public boolean publishCommand(String message) {
        try {
            MqttMessage m = new MqttMessage(message.getBytes());
            m.setQos(1);
            client.publish(commandTopic, m);
            return true;
        } catch (MqttException e) {
            System.err.println("Publish failed: " + e.getMessage());
            return false;
        }
    }

    /** Parse the plain-text payload into typed fields (Double / double[]). */
    static Map<String, Object> parse(String payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String line : payload.split("\\R")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String label = line.substring(0, c).trim();
            String value = line.substring(c + 1).trim();
            switch (label) {
                case "Pressure"     -> putDouble(out, "pressure_hPa", value);
                case "Temperature"  -> putDouble(out, "temperature_degC", value);
                case "Humidity"     -> putDouble(out, "humidity_perc", value);
                case "Acceleration" -> putVector(out, "acceleration_mg", value);
                case "Magnetic"     -> putVector(out, "magnetic_mG", value);
                default -> { /* ignore */ }
            }
        }
        return out;
    }

    private static void putDouble(Map<String, Object> out, String key, String value) {
        try { out.put(key, Double.parseDouble(value)); } catch (NumberFormatException ignored) {}
    }

    private static void putVector(Map<String, Object> out, String key, String value) {
        List<Double> vals = new ArrayList<>();
        for (String part : value.split(",")) {
            try { vals.add(Double.parseDouble(part.trim())); } catch (NumberFormatException ignored) {}
        }
        if (!vals.isEmpty()) {
            double[] arr = new double[vals.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = vals.get(i);
            out.put(key, arr);
        }
    }
}
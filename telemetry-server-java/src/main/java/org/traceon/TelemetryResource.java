/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 the TRAceON team
 * Portions of this file were generated with AI assistance.
 */
/*
 * JAX-RS (Jakarta RESTful Web Services) resources — "simple" surface.
 * JSON serialization is handled by JSON-B (Eclipse Yasson) via Jersey.
 */
package org.traceon;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class TelemetryResource {

    private final TelemetryStore store = TelemetryStore.INSTANCE;

    @GET
    @Path("health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("mqtt_connected", store.mqttConnected());
        m.put("has_data", store.hasData());
        m.put("message_count", store.messageCount());
        m.put("last_message_age_seconds", store.lastMessageAgeSeconds());
        return m;
    }

    @GET
    @Path("telemetry/latest/{field}")
    public Map<String, Object> field(@PathParam("field") String field) {
        Object value = store.field(field);
        if (value == null) {
            throw new WebApplicationException(
                "Field '" + field + "' not available. Known: " + store.fieldNames(),
                Response.Status.NOT_FOUND);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("value", value);
        return m;
    }

    @POST
    @Path("command")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> command(CommandRequest cmd) {
        if (cmd == null || cmd.message == null || cmd.message.isEmpty()) {
            throw new WebApplicationException("message is required", Response.Status.BAD_REQUEST);
        }
        boolean ok = MqttService.INSTANCE.publishCommand(cmd.message);
        if (!ok) {
            throw new WebApplicationException("Failed to publish command",
                Response.Status.BAD_GATEWAY);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("published", true);
        m.put("topic", MqttService.INSTANCE.commandTopic());
        m.put("message", cmd.message);
        return m;
    }

    /** Request body for POST /command (deserialized by JSON-B). */
    public static class CommandRequest {
        public String message;
    }
}

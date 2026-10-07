/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Contributors to the Eclipse Foundation
 * SPDX-License-Identifier: MIT
 * Portions of this file were generated with AI assistance.
 */
/*
 * JAX-RS control endpoints for the optional log forwarding sink (separate from SSE):
 *   GET  /logs/forwarding        -> status
 *   POST /logs/forwarding/start  -> start (optional {"url": "..."} override)
 *   POST /logs/forwarding/stop   -> stop
 */
package org.traceon;

import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/logs/forwarding")
@Produces(MediaType.APPLICATION_JSON)
public class LogControlResource {

    @GET
    public Map<String, Object> status() {
        return LogForwarder.INSTANCE.status();
    }

    @POST
    @Path("start")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> start(StartRequest req) {
        if (req != null && req.url != null && !req.url.isBlank()) {
            LogForwarder.INSTANCE.setUrl(req.url);
        }
        try {
            LogForwarder.INSTANCE.start();
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
        return LogForwarder.INSTANCE.status();
    }

    @POST
    @Path("stop")
    public Map<String, Object> stop() {
        LogForwarder.INSTANCE.stop();
        return LogForwarder.INSTANCE.status();
    }

    /** Optional body for start (URL override). */
    public static class StartRequest {
        public String url;
    }
}
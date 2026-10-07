/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 the TRAceON team
 * Portions of this file were generated with AI assistance.
 */
/*
 * JAX-RS resource — SOVD-flavored surface (ISO 17978 resource shape) for future
 * Eclipse OpenSOVD integration. Models telemetry fields as SOVD 'data' resources
 * under a component entity.
 */
package org.traceon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/components")
@Produces(MediaType.APPLICATION_JSON)
public class SovdResource {

    private final TelemetryStore store = TelemetryStore.INSTANCE;

    @GET
    @Path("{component}/data")
    public Map<String, Object> listData(@PathParam("component") String component) {
        checkComponent(component);
        List<Map<String, String>> items = new ArrayList<>();
        for (String name : store.fieldNames()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", name);
            item.put("href", "/components/" + component + "/data/" + name);
            items.add(item);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("component", component);
        m.put("items", items);
        return m;
    }

    @GET
    @Path("{component}/data/{resourceId}")
    public Map<String, Object> getData(@PathParam("component") String component,
                                       @PathParam("resourceId") String resourceId) {
        checkComponent(component);
        Object value = store.field(resourceId);
        if (value == null) {
            throw new WebApplicationException(
                "Data resource '" + resourceId + "' not available. Known: " + store.fieldNames(),
                Response.Status.NOT_FOUND);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", resourceId);
        m.put("data", value);
        return m;
    }

    private void checkComponent(String component) {
        if (!Config.COMPONENT.equals(component)) {
            throw new WebApplicationException("Unknown component '" + component + "'",
                Response.Status.NOT_FOUND);
        }
    }
}

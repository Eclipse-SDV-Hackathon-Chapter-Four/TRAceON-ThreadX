/*
 * JAX-RS history endpoints — snapshot queries over the ring buffers with filters.
 *   GET /telemetry/history  (limit, field, since, until)
 *   GET /logs/history       (limit, severity[csv], context, since, until)
 * Time filters (since/until, ISO-8601) apply to the server-side receive time.
 */
package org.traceon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class HistoryResource {

    @GET
    @Path("telemetry/history")
    public Map<String, Object> telemetryHistory(
            @QueryParam("limit") Integer limit,
            @QueryParam("field") String field,
            @QueryParam("since") String since,
            @QueryParam("until") String until) {

        Long sinceMs = parseOr400(since);
        Long untilMs = parseOr400(until);

        List<TelemetryStore.Reading> readings = TelemetryStore.INSTANCE.history();
        List<Object> items = new ArrayList<>();
        for (TelemetryStore.Reading r : readings) {
            if (!Query.inRange(r.received_at_ms, sinceMs, untilMs)) continue;
            if (field != null) {
                if (r.fields.containsKey(field)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("received_at_ms", r.received_at_ms);
                    m.put("value", r.fields.get(field));
                    items.add(m);
                }
            } else {
                items.add(r);
            }
        }

        items = tail(items, Query.clampLimit(limit));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size());
        out.put("field", field);
        out.put("entries", items);
        return out;
    }

    @GET
    @Path("logs/history")
    public Map<String, Object> logsHistory(
            @QueryParam("limit") Integer limit,
            @QueryParam("severity") String severity,
            @QueryParam("context") String context,
            @QueryParam("since") String since,
            @QueryParam("until") String until) {

        Long sinceMs = parseOr400(since);
        Long untilMs = parseOr400(until);

        Set<String> sevSet = null;
        if (severity != null && !severity.isBlank()) {
            sevSet = java.util.Arrays.stream(severity.split(","))
                    .map(s -> s.trim().toUpperCase())
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }

        List<LogStore.LogEntry> all = LogStore.INSTANCE.snapshot(null);
        List<LogStore.LogEntry> out = new ArrayList<>();
        for (LogStore.LogEntry e : all) {
            if (!Query.inRange(e.received_at_ms, sinceMs, untilMs)) continue;
            if (sevSet != null && (e.severity == null
                    || !sevSet.contains(e.severity.toUpperCase()))) continue;
            if (context != null && !context.equals(e.context)) continue;
            out.add(e);
        }

        out = tail(out, Query.clampLimit(limit));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", out.size());
        m.put("entries", out);
        return m;
    }

    private static <T> List<T> tail(List<T> list, int n) {
        if (list.size() <= n) return list;
        return new ArrayList<>(list.subList(list.size() - n, list.size()));
    }

    private static Long parseOr400(String iso) {
        try {
            return Query.parseIso8601Millis(iso);
        } catch (Exception e) {
            throw new WebApplicationException("Invalid since/until timestamp: " + iso,
                    Response.Status.BAD_REQUEST);
        }
    }
}

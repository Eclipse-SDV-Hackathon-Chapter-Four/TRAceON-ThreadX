/*
 * TRAceON telemetry server — full Jakarta / Eclipse stack.
 *
 *   Eclipse Jetty   (Jakarta Servlet)              — embedded HTTP
 *   Eclipse Jersey  (Jakarta RESTful Web Services) — JAX-RS resources
 *   Eclipse Yasson  (Jakarta JSON Binding, JSON-B) — JSON serialization
 *   Eclipse Paho    (MQTT client)                  — sensor ingest + commands
 *
 * Jersey is mounted as a servlet in Jetty via a ResourceConfig that registers
 * the JAX-RS resources and the JSON-B provider.
 */
package org.traceon;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

public class TelemetrySpike {

    public static void main(String[] args) throws Exception {
        // 1. MQTT (Eclipse Paho)
        MqttService.INSTANCE.start();

        // 2. HTTP (Eclipse Jetty + Jersey JAX-RS + JSON-B)
        ResourceConfig config = new ResourceConfig();
        config.register(TelemetryResource.class);
        config.register(SovdResource.class);
        config.register(HistoryResource.class);
        config.register(LogControlResource.class);
        // Explicitly register the JSON-B (Yasson) provider. Auto-discovery is
        // unreliable from a shaded fat-jar (merged META-INF/services), so we
        // register the feature directly to guarantee JSON read/write support.
        config.register(org.glassfish.jersey.jsonb.JsonBindingFeature.class);

        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.setContextPath("/");

        // SSE streams are plain async servlets (not JAX-RS SSE). Mapped at exact
        // paths so they take precedence over the Jersey catch-all. The "entries"
        // endpoints ARE the live SSE streams (streaming by default).
        ServletHolder telStream = new ServletHolder(new SseServlet("telemetry"));
        telStream.setAsyncSupported(true);
        ctx.addServlet(telStream, "/telemetry/entries");

        ServletHolder logStream = new ServletHolder(new SseServlet("logs"));
        logStream.setAsyncSupported(true);
        ctx.addServlet(logStream, "/logs/entries");

        // Jersey handles everything else (JSON routes).
        ServletHolder jersey = new ServletHolder(new ServletContainer(config));
        jersey.setInitOrder(1);
        ctx.addServlet(jersey, "/*");

        Server server = new Server(Config.HTTP_PORT);
        server.setHandler(ctx);
        server.start();

        System.out.println("TRAceON Java (Jakarta) server up: http://localhost:" + Config.HTTP_PORT
                + "  topic=" + MqttService.INSTANCE.sensorTopic());
        server.join();
    }
}

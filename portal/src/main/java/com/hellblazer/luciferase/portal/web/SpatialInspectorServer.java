package com.hellblazer.luciferase.portal.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web server for spatial inspector providing REST API for spatial operations.
 * Follows the ART DemoServer pattern with Javalin framework.
 *
 * <p>Serves web-based versions of:
 * <ul>
 *   <li>Spatial Index operations (Octree, Tetree, SFCArrayIndex)</li>
 *   <li>ESVO/ESVT rendering</li>
 *   <li>GPU OpenCL acceleration</li>
 *   <li>Ray casting and queries</li>
 * </ul>
 */
public class SpatialInspectorServer {

    private static final Logger log = LoggerFactory.getLogger(SpatialInspectorServer.class);
    private static final int DEFAULT_PORT = 7071;
    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes

    private final Map<String, SpatialSession> sessions = new ConcurrentHashMap<>();
    private final Javalin app;
    private final int port;

    /**
     * Create server with default port.
     */
    public SpatialInspectorServer() {
        this(DEFAULT_PORT);
    }

    /**
     * Create server with specified port.
     * Use port 0 for dynamic port assignment (useful for testing).
     */
    public SpatialInspectorServer(int port) {
        this.port = port;
        this.app = createApp();
    }

    private Javalin createApp() {
        var javalin = Javalin.create(config -> {
            config.staticFiles.add("/web");
            config.http.defaultContentType = "application/json";
        });

        // Global exception handler
        javalin.exception(Exception.class, (e, ctx) -> {
            log.error("Request failed: {}", ctx.path(), e);
            ctx.status(500).json(Map.of(
                "error", e.getMessage(),
                "type", e.getClass().getSimpleName(),
                "timestamp", Instant.now().toString()
            ));
        });

        // Register endpoints
        registerHealthEndpoints(javalin);
        registerSessionEndpoints(javalin);

        return javalin;
    }

    // ========== Health Endpoints ==========

    private void registerHealthEndpoints(Javalin app) {
        app.get("/api/health", this::healthCheck);
        app.get("/api/info", this::serverInfo);
    }

    private void healthCheck(Context ctx) {
        ctx.json(Map.of(
            "status", "ok",
            "timestamp", Instant.now().toString()
        ));
    }

    private void serverInfo(Context ctx) {
        ctx.json(Map.of(
            "name", "Luciferase Spatial Inspector",
            "version", "0.0.3-SNAPSHOT",
            "capabilities", Map.of(
                "spatialIndices", new String[]{"octree", "tetree", "sfc"},
                "rendering", new String[]{"esvo", "esvt"},
                "gpu", "pending"
            ),
            "activeSessions", sessions.size(),
            "timestamp", Instant.now().toString()
        ));
    }

    // ========== Session Endpoints ==========

    private void registerSessionEndpoints(Javalin app) {
        app.post("/api/session/create", this::createSession);
        app.get("/api/session/{id}", this::getSession);
        app.delete("/api/session/{id}", this::deleteSession);
    }

    private void createSession(Context ctx) {
        var session = SpatialSession.create();
        sessions.put(session.id(), session);
        log.info("Created session: {}", session.id());
        ctx.status(201).json(Map.of(
            "sessionId", session.id(),
            "created", session.created().toString(),
            "message", "Session created successfully"
        ));
    }

    private void getSession(Context ctx) {
        var sessionId = ctx.pathParam("id");
        var session = sessions.get(sessionId);

        if (session == null) {
            ctx.status(404).json(Map.of(
                "error", "Session not found",
                "sessionId", sessionId
            ));
            return;
        }

        // Touch session to update lastAccessed
        sessions.put(sessionId, session.touch());

        ctx.json(Map.of(
            "sessionId", session.id(),
            "created", session.created().toString(),
            "lastAccessed", session.lastAccessed().toString(),
            "expired", session.isExpired(SESSION_TIMEOUT_MS)
        ));
    }

    private void deleteSession(Context ctx) {
        var sessionId = ctx.pathParam("id");
        var session = sessions.remove(sessionId);

        if (session == null) {
            ctx.status(404).json(Map.of(
                "error", "Session not found",
                "sessionId", sessionId
            ));
            return;
        }

        try {
            session.close();
        } catch (Exception e) {
            log.warn("Error closing session {}: {}", sessionId, e.getMessage());
        }

        log.info("Deleted session: {}", sessionId);
        ctx.json(Map.of(
            "message", "Session deleted",
            "sessionId", sessionId
        ));
    }

    // ========== Server Lifecycle ==========

    /**
     * Start the server.
     */
    public void start() {
        app.start(port);
        var actualPort = app.port();
        log.info("=".repeat(70));
        log.info("Luciferase Spatial Inspector Server started on http://localhost:{}", actualPort);
        log.info("Endpoints:");
        log.info("  - Health:    GET  /api/health");
        log.info("  - Info:      GET  /api/info");
        log.info("  - Sessions:  POST /api/session/create");
        log.info("               GET  /api/session/{{id}}");
        log.info("               DELETE /api/session/{{id}}");
        log.info("  - Web UI:    http://localhost:{}/index.html", actualPort);
        log.info("=".repeat(70));
    }

    /**
     * Stop the server and cleanup resources.
     */
    public void stop() {
        log.info("Stopping Spatial Inspector Server...");

        // Close all sessions
        sessions.values().forEach(session -> {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("Error closing session {}: {}", session.id(), e.getMessage());
            }
        });
        sessions.clear();

        app.stop();
        log.info("Server stopped");
    }

    /**
     * Get the actual port the server is running on.
     * Useful when started with port 0 for dynamic assignment.
     */
    public int port() {
        return app.port();
    }

    /**
     * Get the Javalin app instance (for testing).
     */
    public Javalin app() {
        return app;
    }

    /**
     * Main entry point for standalone server.
     */
    public static void main(String[] args) {
        var port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        var server = new SpatialInspectorServer(port);
        server.start();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}

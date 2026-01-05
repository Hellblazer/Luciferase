package com.hellblazer.luciferase.simulation.viz;

import com.hellblazer.luciferase.simulation.bubble.BubbleBounds;
import com.hellblazer.luciferase.simulation.viz.dto.BubbleBoundsDTO;
import com.hellblazer.luciferase.simulation.viz.dto.BubbleBoundsDTO.CartesianPoint;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Web server for bubble bounds visualization.
 * <p>
 * Provides REST API for visualizing tetrahedral bubble bounds using HTML/JS frontend.
 * Follows the SpatialInspectorServer pattern with Javalin + static file serving.
 * <p>
 * Endpoints:
 * - GET /api/bubbles - List all bubble bounds in Cartesian coordinates
 * <p>
 * Static files served from /web:
 * - bounds.html - HTML page with Three.js visualization
 * - bounds.js - JavaScript rendering logic
 */
public class BubbleBoundsServer {

    private static final Logger log = LoggerFactory.getLogger(BubbleBoundsServer.class);
    private static final int DEFAULT_PORT = 7072;

    private final Map<UUID, BubbleBounds> bubbles = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> neighbors = new ConcurrentHashMap<>();
    private final Javalin app;
    private final int port;

    /**
     * Create server with default port.
     */
    public BubbleBoundsServer() {
        this(DEFAULT_PORT);
    }

    /**
     * Create server with specified port.
     * Use port 0 for dynamic port assignment (useful for testing).
     */
    public BubbleBoundsServer(int port) {
        this.port = port;
        this.app = createApp();
    }

    private Javalin createApp() {
        var javalin = Javalin.create(config -> {
            config.staticFiles.add("/web");
            config.http.defaultContentType = "application/json";
        });

        // Register endpoints
        javalin.get("/api/bubbles", ctx -> {
            var dtos = bubbles.entrySet().stream()
                .map(entry -> createDTO(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
            ctx.json(dtos);
        });

        return javalin;
    }

    /**
     * Add a bubble to the visualization.
     *
     * @param bubbleId Unique bubble identifier
     * @param bounds   Tetrahedral bounds
     * @param neighborIds Set of neighbor bubble IDs
     */
    public void addBubble(UUID bubbleId, BubbleBounds bounds, Set<UUID> neighborIds) {
        bubbles.put(bubbleId, bounds);
        neighbors.put(bubbleId, new HashSet<>(neighborIds));
    }

    /**
     * Remove a bubble from the visualization.
     *
     * @param bubbleId Bubble to remove
     */
    public void removeBubble(UUID bubbleId) {
        bubbles.remove(bubbleId);
        neighbors.remove(bubbleId);
    }

    /**
     * Update bubble bounds.
     *
     * @param bubbleId Bubble to update
     * @param bounds   New bounds
     */
    public void updateBubble(UUID bubbleId, BubbleBounds bounds) {
        if (bubbles.containsKey(bubbleId)) {
            bubbles.put(bubbleId, bounds);
        }
    }

    /**
     * Clear all bubbles.
     */
    public void clear() {
        bubbles.clear();
        neighbors.clear();
    }

    /**
     * Start the server.
     */
    public void start() {
        app.start(port);
        var actualPort = app.port();
        log.info("=".repeat(70));
        log.info("Bubble Bounds Visualization Server started on http://localhost:{}", actualPort);
        log.info("Endpoints:");
        log.info("  - Bubbles:   GET  /api/bubbles");
        log.info("  - Web UI:    http://localhost:{}/bounds.html", actualPort);
        log.info("=".repeat(70));
    }

    /**
     * Stop the server and cleanup resources.
     */
    public void stop() {
        log.info("Stopping Bubble Bounds Server...");
        app.stop();
        bubbles.clear();
        neighbors.clear();
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

    // ========== Helper Methods ==========

    private BubbleBoundsDTO createDTO(UUID bubbleId, BubbleBounds bounds) {
        // Get tetrahedron vertices in Cartesian coordinates
        var tet = bounds.rootKey().toTet();
        var coords = tet.coordinates();

        var vertices = new ArrayList<CartesianPoint>(4);
        for (int i = 0; i < 4; i++) {
            vertices.add(new CartesianPoint(coords[i].x, coords[i].y, coords[i].z));
        }

        // Get centroid
        var centroid = bounds.centroid();
        var centroidPoint = new CartesianPoint(centroid.getX(), centroid.getY(), centroid.getZ());

        // Get neighbor list
        var neighborList = neighbors.getOrDefault(bubbleId, Set.of())
            .stream()
            .collect(Collectors.toList());

        return new BubbleBoundsDTO(bubbleId, vertices, neighborList, centroidPoint);
    }

    /**
     * Main entry point for standalone server.
     */
    public static void main(String[] args) {
        var port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        var server = new BubbleBoundsServer(port);
        server.start();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}

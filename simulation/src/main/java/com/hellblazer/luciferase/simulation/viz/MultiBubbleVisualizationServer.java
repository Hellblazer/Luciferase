/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz;

import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.entity.EntityType;
import com.hellblazer.luciferase.simulation.topology.events.TopologyEventStream;
import com.hellblazer.luciferase.simulation.topology.metrics.DensityMonitor;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket server for visualizing multiple bubbles with entity streaming.
 * <p>
 * Designed for the "grand vision" demo: 2x2x2 tetree grid with 1000 entities,
 * pack hunting predators, and flocking prey.
 * <p>
 * Endpoints:
 * - WebSocket /ws/entities - Real-time entity position stream from all bubbles
 * - WebSocket /ws/bubbles - Bubble boundary updates
 * - WebSocket /ws/topology - Real-time topology change events (split/merge/move)
 * - GET /api/health - Health check
 * - GET /api/bubbles - Bubble metadata (boundaries, entity counts)
 * - GET /api/density - Density metrics (entity counts, states, ratios)
 * <p>
 * Static files served from /web:
 * - predator-prey-grid.html - Three.js visualization
 * - predator-prey-grid.js - WebSocket client
 */
public class MultiBubbleVisualizationServer {

    private static final Logger log = LoggerFactory.getLogger(MultiBubbleVisualizationServer.class);
    private static final int DEFAULT_PORT = 7081;
    private static final long STREAM_INTERVAL_MS = 33; // ~30fps (lower than single bubble for performance)

    private final Javalin app;
    private final int port;
    private final Set<WsContext> entityClients = ConcurrentHashMap.newKeySet();
    private final Set<WsContext> bubbleClients = ConcurrentHashMap.newKeySet();
    private final TopologyEventStream topologyEventStream = new TopologyEventStream();
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object streamingLock = new Object();

    private List<EnhancedBubble> bubbles = new ArrayList<>();
    private Map<UUID, Point3f[]> bubbleVertices = new ConcurrentHashMap<>();
    private Map<UUID, Byte> bubbleTypes = new ConcurrentHashMap<>();
    private Map<UUID, Map<String, Object>> bubbleSpheres = new ConcurrentHashMap<>();
    private ScheduledFuture<?> streamTask;
    private DensityMonitor densityMonitor;

    // Pluggable clock for deterministic testing - defaults to system time
    private volatile Clock clock = Clock.system();

    /**
     * Create server with default port.
     */
    public MultiBubbleVisualizationServer() {
        this(DEFAULT_PORT);
    }

    /**
     * Create server with specified port.
     */
    public MultiBubbleVisualizationServer(int port) {
        this.port = port;
        this.app = createApp();
    }

    /**
     * Sets the clock to use for timestamps in API responses.
     * <p>
     * For deterministic testing, inject a {@link com.hellblazer.luciferase.simulation.distributed.integration.TestClock}
     * to control time progression.
     *
     * @param clock the clock to use (must not be null)
     * @throws NullPointerException if clock is null
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    private Javalin createApp() {
        var javalin = Javalin.create(config -> {
            config.staticFiles.add("/web");
            config.http.defaultContentType = "application/json";
        });

        // Health check
        javalin.get("/api/health", ctx -> {
            ctx.json(Map.of(
                "status", "ok",
                "bubbles", bubbles.size(),
                "entityClients", entityClients.size(),
                "bubbleClients", bubbleClients.size(),
                "streaming", streaming.get()
            ));
        });

        // Bubble metadata
        javalin.get("/api/bubbles", ctx -> {
            if (bubbles.isEmpty()) {
                ctx.status(404).json(Map.of("error", "No bubbles configured"));
                return;
            }
            var bubbleData = bubbles.stream().map(b -> {
                var data = new HashMap<String, Object>();
                data.put("id", b.id().toString());
                data.put("entityCount", b.entityCount());
                data.put("bounds", getBubbleBounds(b));

                // Include tetrahedral vertices (4) or box vertices (8) if available
                var verts = bubbleVertices.get(b.id());
                if (verts != null && (verts.length == 4 || verts.length == 8)) {
                    var vertexList = new ArrayList<Map<String, Float>>();
                    for (var v : verts) {
                        vertexList.add(Map.of("x", v.x, "y", v.y, "z", v.z));
                    }
                    data.put("vertices", vertexList);
                }

                // Include tetrahedral type if available
                var type = bubbleTypes.get(b.id());
                if (type != null) {
                    data.put("tetType", type);
                }

                // Include inscribed sphere if available
                var sphere = bubbleSpheres.get(b.id());
                if (sphere != null) {
                    var center = (Point3f) sphere.get("center");
                    var radius = (Float) sphere.get("radius");
                    data.put("sphere", Map.of(
                        "center", Map.of("x", center.x, "y", center.y, "z", center.z),
                        "radius", radius
                    ));
                }

                return data;
            }).toList();
            ctx.json(Map.of("bubbles", bubbleData));
        });

        // Density metrics
        javalin.get("/api/density", ctx -> {
            if (densityMonitor == null) {
                ctx.status(503).json(Map.of("error", "Density monitor not configured"));
                return;
            }
            ctx.json(formatDensityMetrics());
        });

        // WebSocket for entity streaming
        javalin.ws("/ws/entities", ws -> {
            ws.onConnect(ctx -> {
                entityClients.add(ctx);
                log.debug("Entity client connected: {} (total: {})", ctx.sessionId(), entityClients.size());

                // Send initial state
                if (!bubbles.isEmpty()) {
                    sendEntities(ctx);
                }

                startStreamingIfNeeded();
            });

            ws.onClose(ctx -> {
                entityClients.remove(ctx);
                log.debug("Entity client disconnected: {} (total: {})", ctx.sessionId(), entityClients.size());
                stopStreamingIfNoClients();
            });

            ws.onError(ctx -> {
                log.warn("Entity WebSocket error for {}: {}", ctx.sessionId(), ctx.error());
                entityClients.remove(ctx);
            });
        });

        // WebSocket for bubble boundaries
        javalin.ws("/ws/bubbles", ws -> {
            ws.onConnect(ctx -> {
                bubbleClients.add(ctx);
                log.debug("Bubble client connected: {} (total: {})", ctx.sessionId(), bubbleClients.size());

                // Send bubble boundaries
                if (!bubbles.isEmpty()) {
                    sendBubbleBoundaries(ctx);
                }
            });

            ws.onClose(ctx -> {
                bubbleClients.remove(ctx);
                log.debug("Bubble client disconnected: {} (total: {})", ctx.sessionId(), bubbleClients.size());
            });

            ws.onError(ctx -> {
                log.warn("Bubble WebSocket error for {}: {}", ctx.sessionId(), ctx.error());
                bubbleClients.remove(ctx);
            });
        });

        // WebSocket for topology events
        javalin.ws("/ws/topology", ws -> {
            ws.onConnect(ctx -> {
                topologyEventStream.addClient(ctx);
                log.debug("Topology event client connected: {} (total clients: {})",
                         ctx.sessionId(), topologyEventStream.getClientCount());
            });

            ws.onClose(ctx -> {
                topologyEventStream.removeClient(ctx);
                log.debug("Topology event client disconnected: {} (total clients: {})",
                         ctx.sessionId(), topologyEventStream.getClientCount());
            });

            ws.onError(ctx -> {
                log.warn("Topology WebSocket error for {}: {}", ctx.sessionId(), ctx.error());
                topologyEventStream.removeClient(ctx);
            });
        });

        return javalin;
    }

    /**
     * Set the bubbles to visualize (typically from TetreeBubbleGrid).
     */
    public void setBubbles(List<EnhancedBubble> bubbles) {
        this.bubbles = new ArrayList<>(bubbles);
        log.debug("Bubbles set: {} bubbles", bubbles.size());

        // Broadcast bubble boundaries to connected clients
        broadcastBubbleBoundaries();

        startStreamingIfNeeded();
    }

    /**
     * Set the tetrahedral vertices for each bubble (for proper visualization).
     * @param vertices Map from bubble UUID to array of 4 Point3f vertices
     */
    public void setBubbleVertices(Map<UUID, Point3f[]> vertices) {
        this.bubbleVertices = new ConcurrentHashMap<>(vertices);
        log.debug("Bubble vertices set: {} bubbles with tetrahedral geometry", vertices.size());

        // Re-broadcast bubble boundaries with vertices
        broadcastBubbleBoundaries();
    }

    /**
     * Get the current bubble vertices.
     * @return Map from bubble UUID to vertex arrays (4 for tetrahedra, 8 for boxes)
     */
    public Map<UUID, Point3f[]> getBubbleVertices() {
        return new HashMap<>(bubbleVertices);
    }

    /**
     * Set the tetrahedral types for each bubble (0-5 for S0-S5 subdivision).
     * @param types Map from bubble UUID to tetrahedral type (0-5)
     */
    public void setBubbleTypes(Map<UUID, Byte> types) {
        this.bubbleTypes = new ConcurrentHashMap<>(types);
        log.debug("Bubble types set: {} bubbles with type information", types.size());

        // Re-broadcast bubble boundaries with types
        broadcastBubbleBoundaries();
    }

    /**
     * Set the inscribed sphere data for each bubble (center and radius).
     * @param spheres Map from bubble UUID to sphere data (center: Point3f, radius: float)
     */
    public void setBubbleSpheres(Map<UUID, Map<String, Object>> spheres) {
        this.bubbleSpheres = new ConcurrentHashMap<>(spheres);
        log.debug("Bubble spheres set: {} bubbles with inscribed sphere data", spheres.size());

        // Re-broadcast bubble boundaries with spheres
        broadcastBubbleBoundaries();
    }

    /**
     * Get the configured bubbles.
     */
    public List<EnhancedBubble> getBubbles() {
        return new ArrayList<>(bubbles);
    }

    /**
     * Get the topology event stream for wiring up topology event producers.
     * <p>
     * Use this to register TopologyExecutor and DensityMonitor as listeners:
     * <pre>{@code
     * topologyExecutor.addListener(server.getTopologyEventStream());
     * densityMonitor.addListener(server.getTopologyEventStream());
     * }</pre>
     *
     * @return the topology event stream
     */
    public TopologyEventStream getTopologyEventStream() {
        return topologyEventStream;
    }

    /**
     * Set the density monitor for metrics API.
     * <p>
     * Enables the /api/density endpoint to return real-time density metrics.
     *
     * @param monitor the density monitor
     */
    public void setDensityMonitor(DensityMonitor monitor) {
        this.densityMonitor = monitor;
    }

    /**
     * Start the server.
     */
    public void start() {
        app.start(port);
        log.info("MultiBubbleVisualizationServer started on http://localhost:{}", port);
        log.info("Open http://localhost:{}/predator-prey-grid.html to view", port);
    }

    /**
     * Stop the server.
     */
    public void stop() {
        stopStreamingInternal();
        app.stop();
        scheduler.shutdown();
        log.info("MultiBubbleVisualizationServer stopped");
    }

    /**
     * Get the actual port (useful when using port 0).
     */
    public int port() {
        return app.port();
    }

    // ========== Streaming Methods ==========

    private void startStreamingIfNeeded() {
        synchronized (streamingLock) {
            if (!entityClients.isEmpty() && !bubbles.isEmpty() && !streaming.get()) {
                startStreamingInternal();
            }
        }
    }

    private void stopStreamingIfNoClients() {
        synchronized (streamingLock) {
            if (entityClients.isEmpty() && streaming.get()) {
                stopStreamingInternal();
            }
        }
    }

    private void startStreamingInternal() {
        if (streaming.compareAndSet(false, true)) {
            streamTask = scheduler.scheduleAtFixedRate(
                this::broadcastEntities,
                0,
                STREAM_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );
            log.debug("Multi-bubble entity streaming started ({}ms interval)", STREAM_INTERVAL_MS);
        }
    }

    private void stopStreamingInternal() {
        if (streaming.compareAndSet(true, false)) {
            if (streamTask != null) {
                streamTask.cancel(false);
                streamTask = null;
            }
            log.debug("Multi-bubble entity streaming stopped");
        }
    }

    private void broadcastEntities() {
        if (entityClients.isEmpty() || bubbles.isEmpty()) {
            return;
        }

        var entities = getAllEntityDTOs();
        var json = entityListToJson(entities);

        var disconnected = new ArrayList<WsContext>();
        for (var client : entityClients) {
            try {
                client.send(json);
            } catch (Exception e) {
                log.warn("Failed to send to client {}: {}", client.sessionId(), e.getMessage());
                disconnected.add(client);
            }
        }

        disconnected.forEach(entityClients::remove);
    }

    private void sendEntities(WsContext ctx) {
        var entities = getAllEntityDTOs();
        var json = entityListToJson(entities);
        try {
            ctx.send(json);
        } catch (Exception e) {
            log.warn("Failed to send initial entities to {}: {}", ctx.sessionId(), e.getMessage());
        }
    }

    private void broadcastBubbleBoundaries() {
        if (bubbleClients.isEmpty() || bubbles.isEmpty()) {
            return;
        }

        var json = bubbleBoundariesToJson();

        for (var client : bubbleClients) {
            try {
                client.send(json);
            } catch (Exception e) {
                log.warn("Failed to send bubble boundaries to {}: {}", client.sessionId(), e.getMessage());
            }
        }
    }

    private void sendBubbleBoundaries(WsContext ctx) {
        var json = bubbleBoundariesToJson();
        try {
            ctx.send(json);
        } catch (Exception e) {
            log.warn("Failed to send bubble boundaries to {}: {}", ctx.sessionId(), e.getMessage());
        }
    }

    // ========== JSON Serialization ==========

    private List<Map<String, Object>> getAllEntityDTOs() {
        var entities = new ArrayList<Map<String, Object>>();

        for (var bubble : bubbles) {
            var records = bubble.getAllEntityRecords();
            for (var record : records) {
                var dto = new HashMap<String, Object>();
                dto.put("id", record.id());
                dto.put("x", record.position().x);
                dto.put("y", record.position().y);
                dto.put("z", record.position().z);
                dto.put("bubbleId", bubble.id().toString());

                // Entity type
                if (record.content() instanceof EntityType entityType) {
                    dto.put("type", entityType.name());
                } else {
                    dto.put("type", "DEFAULT");
                }

                entities.add(dto);
            }
        }

        return entities;
    }

    private String entityListToJson(List<Map<String, Object>> entities) {
        var sb = new StringBuilder();
        sb.append("{\"entities\":[");

        for (int i = 0; i < entities.size(); i++) {
            if (i > 0) sb.append(",");
            var e = entities.get(i);
            sb.append("{");
            sb.append("\"id\":\"").append(e.get("id")).append("\",");
            sb.append("\"x\":").append(e.get("x")).append(",");
            sb.append("\"y\":").append(e.get("y")).append(",");
            sb.append("\"z\":").append(e.get("z")).append(",");
            sb.append("\"type\":\"").append(e.get("type")).append("\",");
            sb.append("\"bubbleId\":\"").append(e.get("bubbleId")).append("\"");
            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    private String bubbleBoundariesToJson() {
        var sb = new StringBuilder();
        sb.append("{\"bubbles\":[");

        for (int i = 0; i < bubbles.size(); i++) {
            if (i > 0) sb.append(",");
            var bubble = bubbles.get(i);
            var bounds = getBubbleBounds(bubble);

            sb.append("{");
            sb.append("\"id\":\"").append(bubble.id()).append("\",");
            sb.append("\"min\":{");
            sb.append("\"x\":").append(bounds.get("minX")).append(",");
            sb.append("\"y\":").append(bounds.get("minY")).append(",");
            sb.append("\"z\":").append(bounds.get("minZ"));
            sb.append("},");
            sb.append("\"max\":{");
            sb.append("\"x\":").append(bounds.get("maxX")).append(",");
            sb.append("\"y\":").append(bounds.get("maxY")).append(",");
            sb.append("\"z\":").append(bounds.get("maxZ"));
            sb.append("},");
            sb.append("\"entityCount\":").append(bubble.entityCount());

            // Add tetrahedral vertices (4) or box vertices (8) if available
            var vertices = bubbleVertices.get(bubble.id());
            if (vertices != null && (vertices.length == 4 || vertices.length == 8)) {
                sb.append(",\"vertices\":[");
                for (int v = 0; v < vertices.length; v++) {
                    if (v > 0) sb.append(",");
                    sb.append("{\"x\":").append(vertices[v].x);
                    sb.append(",\"y\":").append(vertices[v].y);
                    sb.append(",\"z\":").append(vertices[v].z).append("}");
                }
                sb.append("]");
            }

            // Add tetrahedral type if available (0-5 for S0-S5 subdivision)
            var type = bubbleTypes.get(bubble.id());
            if (type != null) {
                sb.append(",\"tetType\":").append(type);
            }

            // Add inscribed sphere if available (center and radius)
            var sphere = bubbleSpheres.get(bubble.id());
            if (sphere != null) {
                var center = (Point3f) sphere.get("center");
                var radius = (Float) sphere.get("radius");
                sb.append(",\"sphere\":{");
                sb.append("\"center\":{\"x\":").append(center.x);
                sb.append(",\"y\":").append(center.y);
                sb.append(",\"z\":").append(center.z).append("}");
                sb.append(",\"radius\":").append(radius);
                sb.append("}");
            }

            sb.append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    private Map<String, Float> getBubbleBounds(EnhancedBubble bubble) {
        // Approximate bubble bounds (in a real implementation, would query tetree bounds)
        // For now, use a simple calculation based on world size
        // TODO: Get actual tetrahedral bounds from TetreeBubbleGrid
        return Map.of(
            "minX", -100f,
            "minY", -100f,
            "minZ", -100f,
            "maxX", 100f,
            "maxY", 100f,
            "maxZ", 100f
        );
    }

    /**
     * Format density metrics for JSON response.
     * <p>
     * Returns metrics for all tracked bubbles with current density state,
     * entity count, and density ratio.
     *
     * @return formatted density metrics
     */
    private Map<String, Object> formatDensityMetrics() {
        var metrics = new ArrayList<Map<String, Object>>();

        for (var bubble : bubbles) {
            var bubbleId = bubble.id();
            var state = densityMonitor.getState(bubbleId);
            var entityCount = densityMonitor.getEntityCount(bubbleId);
            var densityRatio = densityMonitor.getSplitRatio(bubbleId);

            metrics.add(Map.of(
                "bubbleId", bubbleId.toString(),
                "entityCount", entityCount,
                "state", state.name(),
                "densityRatio", densityRatio
            ));
        }

        return Map.of(
            "density", metrics,
            "timestamp", clock.currentTimeMillis()
        );
    }
}

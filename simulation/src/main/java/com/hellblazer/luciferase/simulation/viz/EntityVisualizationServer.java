/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz;

import com.hellblazer.luciferase.simulation.behavior.EntityBehavior;
import com.hellblazer.luciferase.simulation.behavior.FlockingBehavior;
import com.hellblazer.luciferase.simulation.behavior.RandomWalkBehavior;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.loop.SimulationLoop;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket server for real-time entity visualization.
 * <p>
 * Streams entity positions from a VonBubble to connected browser clients.
 * Follows the BubbleBoundsServer pattern with Javalin + WebSocket streaming.
 * <p>
 * Endpoints:
 * - WebSocket /ws/entities - Real-time entity position stream
 * - GET /api/entities - One-time entity snapshot
 * - GET /api/health - Health check
 * <p>
 * Static files served from /web:
 * - entity-viz.html - Three.js visualization
 * - entity-viz.js - WebSocket client
 */
public class EntityVisualizationServer {

    private static final Logger log = LoggerFactory.getLogger(EntityVisualizationServer.class);
    private static final int DEFAULT_PORT = 7080;
    private static final long STREAM_INTERVAL_MS = 16; // ~60fps

    private final Javalin app;
    private final int port;
    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private EnhancedBubble bubble;
    private SimulationLoop simulation;
    private ScheduledFuture<?> streamTask;

    /**
     * Create server with default port.
     */
    public EntityVisualizationServer() {
        this(DEFAULT_PORT);
    }

    /**
     * Create server with specified port.
     * Use port 0 for dynamic port assignment (useful for testing).
     */
    public EntityVisualizationServer(int port) {
        this.port = port;
        this.app = createApp();
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
                "bubble", bubble != null,
                "clients", clients.size(),
                "streaming", streaming.get()
            ));
        });

        // One-time entity snapshot
        javalin.get("/api/entities", ctx -> {
            if (bubble == null) {
                ctx.status(404).json(Map.of("error", "No bubble configured"));
                return;
            }
            ctx.json(Map.of("entities", getEntityDTOs()));
        });

        // WebSocket for real-time streaming
        javalin.ws("/ws/entities", ws -> {
            ws.onConnect(ctx -> {
                clients.add(ctx);
                log.info("Client connected: {} (total: {})", ctx.sessionId(), clients.size());

                // Send initial state
                if (bubble != null) {
                    sendEntities(ctx);
                }

                // Start streaming if not already
                if (!streaming.get() && bubble != null) {
                    startStreaming();
                }
            });

            ws.onClose(ctx -> {
                clients.remove(ctx);
                log.info("Client disconnected: {} (total: {})", ctx.sessionId(), clients.size());

                // Stop streaming if no clients
                if (clients.isEmpty()) {
                    stopStreaming();
                }
            });

            ws.onError(ctx -> {
                log.warn("WebSocket error for {}: {}", ctx.sessionId(), ctx.error());
                clients.remove(ctx);
            });
        });

        return javalin;
    }

    /**
     * Set the bubble to visualize.
     *
     * @param bubble EnhancedBubble (or VonBubble which extends it)
     */
    public void setBubble(EnhancedBubble bubble) {
        this.bubble = bubble;
        log.info("Bubble set: {}", bubble.id());

        // If clients are connected, start streaming
        if (!clients.isEmpty() && !streaming.get()) {
            startStreaming();
        }
    }

    /**
     * Get the configured bubble.
     */
    public EnhancedBubble getBubble() {
        return bubble;
    }

    /**
     * Set the simulation loop.
     * Also sets the bubble from the simulation.
     *
     * @param simulation SimulationLoop to run
     */
    public void setSimulation(SimulationLoop simulation) {
        this.simulation = simulation;
        this.bubble = simulation.getBubble();
        log.info("Simulation set: {} entities", bubble.entityCount());
    }

    /**
     * Get the simulation loop.
     */
    public SimulationLoop getSimulation() {
        return simulation;
    }

    /**
     * Start the simulation if one is configured.
     */
    public void startSimulation() {
        if (simulation != null && !simulation.isRunning()) {
            simulation.start();
        }
    }

    /**
     * Stop the simulation if one is running.
     */
    public void stopSimulation() {
        if (simulation != null && simulation.isRunning()) {
            simulation.stop();
        }
    }

    /**
     * Start the server.
     */
    public void start() {
        app.start(port);
        var actualPort = app.port();
        log.info("=".repeat(70));
        log.info("Entity Visualization Server started on http://localhost:{}", actualPort);
        log.info("Endpoints:");
        log.info("  - WebSocket: ws://localhost:{}/ws/entities", actualPort);
        log.info("  - Entities:  GET /api/entities");
        log.info("  - Health:    GET /api/health");
        log.info("  - Web UI:    http://localhost:{}/entity-viz.html", actualPort);
        log.info("=".repeat(70));
    }

    /**
     * Stop the server and cleanup resources.
     */
    public void stop() {
        log.info("Stopping Entity Visualization Server...");
        stopStreaming();
        if (simulation != null) {
            simulation.shutdown();
        }
        scheduler.shutdownNow();
        app.stop();
        clients.clear();
        log.info("Server stopped");
    }

    /**
     * Get the actual port the server is running on.
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
     * Get connected client count.
     */
    public int clientCount() {
        return clients.size();
    }

    /**
     * Check if streaming is active.
     */
    public boolean isStreaming() {
        return streaming.get();
    }

    // ========== Streaming Methods ==========

    private void startStreaming() {
        if (streaming.compareAndSet(false, true)) {
            streamTask = scheduler.scheduleAtFixedRate(
                this::broadcastEntities,
                0,
                STREAM_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );
            log.info("Entity streaming started ({}ms interval)", STREAM_INTERVAL_MS);
        }
    }

    private void stopStreaming() {
        if (streaming.compareAndSet(true, false)) {
            if (streamTask != null) {
                streamTask.cancel(false);
                streamTask = null;
            }
            log.info("Entity streaming stopped");
        }
    }

    private void broadcastEntities() {
        if (bubble == null || clients.isEmpty()) {
            return;
        }

        var json = buildEntityJson();

        // Send to all connected clients
        var deadClients = new ArrayList<WsContext>();
        for (var client : clients) {
            try {
                if (client.session.isOpen()) {
                    client.send(json);
                } else {
                    deadClients.add(client);
                }
            } catch (Exception e) {
                log.trace("Failed to send to client {}: {}", client.sessionId(), e.getMessage());
                deadClients.add(client);
            }
        }

        // Cleanup dead clients
        clients.removeAll(deadClients);
    }

    private void sendEntities(WsContext ctx) {
        if (bubble == null) {
            return;
        }

        try {
            ctx.send(buildEntityJson());
        } catch (Exception e) {
            log.warn("Failed to send initial entities to {}: {}", ctx.sessionId(), e.getMessage());
        }
    }

    private String buildEntityJson() {
        var entities = getEntityDTOs();
        var sb = new StringBuilder();
        sb.append("{\"entities\":[");

        boolean first = true;
        for (var entity : entities) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"id\":\"").append(entity.id()).append("\",");
            sb.append("\"x\":").append(entity.x()).append(",");
            sb.append("\"y\":").append(entity.y()).append(",");
            sb.append("\"z\":").append(entity.z()).append("}");
        }

        sb.append("],\"timestamp\":").append(System.currentTimeMillis()).append("}");
        return sb.toString();
    }

    private List<EntityDTO> getEntityDTOs() {
        if (bubble == null) {
            return List.of();
        }

        var records = bubble.getAllEntityRecords();
        var dtos = new ArrayList<EntityDTO>(records.size());

        for (var record : records) {
            var pos = record.position();
            dtos.add(new EntityDTO(record.id(), pos.x, pos.y, pos.z));
        }

        return dtos;
    }

    // ========== DTO ==========

    public record EntityDTO(String id, float x, float y, float z) {}

    // ========== Main ==========

    /**
     * Main entry point for standalone server with demo data.
     */
    /**
     * Main entry point for standalone server with demo data.
     * <p>
     * Usage: java EntityVisualizationServer [port] [entityCount] [behavior]
     * <p>
     * behavior: "flock" (default) or "random"
     */
    public static void main(String[] args) {
        var port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        var entityCount = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        var behaviorType = args.length > 2 ? args[2] : "flock";
        var server = new EntityVisualizationServer(port);

        // Create demo bubble with random entities
        var bubble = new EnhancedBubble(UUID.randomUUID(), (byte) 10, 16);
        var random = new Random(42);

        for (int i = 0; i < entityCount; i++) {
            var position = new Point3f(
                20 + random.nextFloat() * 160,
                20 + random.nextFloat() * 160,
                20 + random.nextFloat() * 160
            );
            bubble.addEntity("entity-" + i, position, null);
        }

        // Create behavior based on type
        EntityBehavior behavior;
        if ("random".equalsIgnoreCase(behaviorType)) {
            behavior = new RandomWalkBehavior(42);
            log.info("Using RandomWalkBehavior");
        } else {
            behavior = new FlockingBehavior();
            log.info("Using FlockingBehavior (separation/alignment/cohesion)");
        }

        var simulation = new SimulationLoop(bubble, behavior);

        server.setSimulation(simulation);
        server.start();
        server.startSimulation();

        log.info("Demo running with {} entities using {} behavior", entityCount, behaviorType);
        log.info("Open http://localhost:{}/entity-viz.html to view", port);

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}

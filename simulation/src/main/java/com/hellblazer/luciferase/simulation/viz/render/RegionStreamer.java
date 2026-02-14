/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket handler for streaming ESVO/ESVT region data to clients.
 * <p>
 * Manages client connections, processes viewport updates via JSON messages,
 * and pushes binary WebSocket frames containing compressed voxel data.
 * <p>
 * Day 5 implementation: Core WebSocket lifecycle and JSON message handling.
 * Day 6 implementation: Streaming loop and binary frame delivery (deferred).
 *
 * @author hal.hildebrand
 */
public class RegionStreamer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RegionStreamer.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // --- Dependencies ---
    private final ViewportTracker viewportTracker;
    private final RegionCache regionCache;
    private final AdaptiveRegionManager regionManager;
    private final StreamingConfig config;

    // --- Client Sessions ---
    private final ConcurrentHashMap<String, ClientSession> sessions;

    // --- Streaming State (Day 6) ---
    private final AtomicBoolean streaming;

    // --- Clock ---
    private volatile Clock clock = Clock.system();

    /**
     * Create a region streamer.
     *
     * @param viewportTracker Viewport tracking for frustum culling
     * @param regionCache     Region cache for built region retrieval (nullable for Day 5 tests)
     * @param regionManager   Region manager for region metadata
     * @param config          Streaming configuration
     */
    public RegionStreamer(
        ViewportTracker viewportTracker,
        RegionCache regionCache,
        AdaptiveRegionManager regionManager,
        StreamingConfig config
    ) {
        this.viewportTracker = Objects.requireNonNull(viewportTracker, "viewportTracker");
        this.regionCache = regionCache;  // Nullable for Day 5 tests
        this.regionManager = Objects.requireNonNull(regionManager, "regionManager");
        this.config = Objects.requireNonNull(config, "config");

        this.sessions = new ConcurrentHashMap<>();
        this.streaming = new AtomicBoolean(false);
    }

    // --- Public WebSocket Lifecycle Methods ---

    /**
     * Handle new WebSocket connection (public Javalin handler).
     */
    public void onConnect(WsContext ctx) {
        onConnectInternal(WsContextWrapper.wrap(ctx));
    }

    /**
     * Handle incoming JSON message (public Javalin handler).
     */
    public void onMessage(WsContext ctx, String message) {
        onMessageInternal(WsContextWrapper.wrap(ctx), message);
    }

    /**
     * Handle client disconnect (public Javalin handler).
     */
    public void onClose(WsContext ctx, int statusCode, String reason) {
        onCloseInternal(WsContextWrapper.wrap(ctx), statusCode, reason);
    }

    /**
     * Handle WebSocket error.
     */
    public void onError(WsContext ctx) {
        var sessionId = ctx.sessionId();
        log.error("WebSocket error for session {}", sessionId);
        // Cleanup handled by onClose which Javalin calls after error
    }

    // --- Internal Testable Handlers ---

    /**
     * Internal connect handler (testable with fake WsContextWrapper).
     */
    void onConnectInternal(WsContextWrapper ctx) {
        var sessionId = ctx.sessionId();

        // Enforce client limit
        if (sessions.size() >= config.maxClientsPerServer()) {
            log.warn("Client limit reached ({}), rejecting connection {}",
                config.maxClientsPerServer(), sessionId);
            ctx.closeSession(4001, "Server full");
            return;
        }

        var session = new ClientSession(sessionId, ctx, clock.currentTimeMillis());
        sessions.put(sessionId, session);
        viewportTracker.registerClient(sessionId);

        log.info("Client connected: {} (total: {})", sessionId, sessions.size());
    }

    /**
     * Internal message handler (testable with fake WsContextWrapper).
     */
    void onMessageInternal(WsContextWrapper ctx, String message) {
        var sessionId = ctx.sessionId();
        var session = sessions.get(sessionId);
        if (session == null) {
            log.warn("Received message from unknown session {}", sessionId);
            return;
        }

        session.lastActivityMs.set(clock.currentTimeMillis());

        try {
            var json = JSON_MAPPER.readTree(message);
            var type = json.get("type");
            if (type == null) {
                sendError(ctx, "Missing 'type' field");
                return;
            }

            switch (type.asText()) {
                case "REGISTER_CLIENT" -> handleRegisterClient(ctx, session, json);
                case "UPDATE_VIEWPORT" -> handleUpdateViewport(ctx, session, json);
                default -> sendError(ctx, "Unknown message type: " + type.asText());
            }

        } catch (Exception e) {
            log.error("Error processing message from {}: {}", sessionId, e.getMessage(), e);
            sendError(ctx, "Invalid JSON: " + e.getMessage());
        }
    }

    /**
     * Internal close handler (testable with fake WsContextWrapper).
     */
    void onCloseInternal(WsContextWrapper ctx, int statusCode, String reason) {
        var sessionId = ctx.sessionId();
        var session = sessions.remove(sessionId);
        if (session != null) {
            session.state = ClientSessionState.DISCONNECTING;
            viewportTracker.removeClient(sessionId);
            log.info("Client disconnected: {} (code={}, reason={})", sessionId, statusCode, reason);
        }
    }

    // --- Message Handlers ---

    /**
     * Handle REGISTER_CLIENT message.
     */
    private void handleRegisterClient(WsContextWrapper ctx, ClientSession session, JsonNode json) {
        var clientId = json.get("clientId");
        if (clientId == null) {
            sendError(ctx, "Missing 'clientId' field");
            return;
        }

        var viewportNode = json.get("viewport");
        if (viewportNode == null) {
            sendError(ctx, "Missing 'viewport' field");
            return;
        }

        try {
            var viewport = parseViewport(viewportNode);
            viewportTracker.updateViewport(session.sessionId, viewport);
            session.state = ClientSessionState.STREAMING;

            log.debug("Registered client {} with viewport", session.sessionId);

        } catch (Exception e) {
            log.error("Error parsing viewport for {}: {}", session.sessionId, e.getMessage());
            sendError(ctx, "Invalid viewport: " + e.getMessage());
        }
    }

    /**
     * Handle UPDATE_VIEWPORT message.
     */
    private void handleUpdateViewport(WsContextWrapper ctx, ClientSession session, JsonNode json) {
        var clientId = json.get("clientId");
        if (clientId == null) {
            sendError(ctx, "Missing 'clientId' field");
            return;
        }

        var viewportNode = json.get("viewport");
        if (viewportNode == null) {
            sendError(ctx, "Missing 'viewport' field");
            return;
        }

        try {
            var viewport = parseViewport(viewportNode);
            viewportTracker.updateViewport(session.sessionId, viewport);

            log.debug("Updated viewport for client {}", session.sessionId);

        } catch (Exception e) {
            log.error("Error parsing viewport for {}: {}", session.sessionId, e.getMessage());
            sendError(ctx, "Invalid viewport: " + e.getMessage());
        }
    }

    /**
     * Parse viewport from JSON node.
     */
    private ClientViewport parseViewport(JsonNode viewportNode) {
        var eyeNode = viewportNode.get("eye");
        var lookAtNode = viewportNode.get("lookAt");
        var upNode = viewportNode.get("up");

        if (eyeNode == null || lookAtNode == null || upNode == null) {
            throw new IllegalArgumentException("Missing eye, lookAt, or up field");
        }

        var eye = new Point3f(
            (float) eyeNode.get("x").asDouble(),
            (float) eyeNode.get("y").asDouble(),
            (float) eyeNode.get("z").asDouble()
        );

        var lookAt = new Point3f(
            (float) lookAtNode.get("x").asDouble(),
            (float) lookAtNode.get("y").asDouble(),
            (float) lookAtNode.get("z").asDouble()
        );

        var up = new Vector3f(
            (float) upNode.get("x").asDouble(),
            (float) upNode.get("y").asDouble(),
            (float) upNode.get("z").asDouble()
        );

        var fovY = (float) viewportNode.get("fovY").asDouble();
        var aspectRatio = (float) viewportNode.get("aspectRatio").asDouble();
        var nearPlane = (float) viewportNode.get("nearPlane").asDouble();
        var farPlane = (float) viewportNode.get("farPlane").asDouble();

        return new ClientViewport(eye, lookAt, up, fovY, aspectRatio, nearPlane, farPlane);
    }

    /**
     * Send error response to client (thread-safe).
     */
    private void sendError(WsContextWrapper ctx, String message) {
        try {
            var errorJson = String.format("{\"type\":\"ERROR\",\"message\":\"%s\"}", message);
            sendSafe(ctx, errorJson);
        } catch (Exception e) {
            log.error("Failed to send error response: {}", e.getMessage());
        }
    }

    /**
     * Thread-safe send wrapper (Fix 2 from architecture).
     */
    private void sendSafe(WsContextWrapper ctx, String json) {
        synchronized (ctx) {
            ctx.send(json);
        }
    }

    // --- Lifecycle Methods ---

    /**
     * Get the number of connected clients.
     */
    public int connectedClientCount() {
        return sessions.size();
    }

    /**
     * Set clock for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void close() {
        streaming.set(false);
        sessions.clear();
    }

    // --- Inner Classes ---

    /**
     * Per-client WebSocket session state.
     */
    static class ClientSession {
        final String sessionId;
        final WsContextWrapper wsContext;
        volatile ClientSessionState state;
        final AtomicLong lastViewportUpdateMs;
        final AtomicLong lastActivityMs;
        final AtomicInteger pendingSends;

        ClientSession(String sessionId, WsContextWrapper wsContext, long currentTimeMs) {
            this.sessionId = sessionId;
            this.wsContext = wsContext;
            this.state = ClientSessionState.CONNECTED;
            this.lastViewportUpdateMs = new AtomicLong(0);
            this.lastActivityMs = new AtomicLong(currentTimeMs);
            this.pendingSends = new AtomicInteger(0);
        }
    }

    /**
     * Client session state machine.
     */
    enum ClientSessionState {
        /** Connected but not yet registered. */
        CONNECTED,
        /** Registered with viewport, actively streaming. */
        STREAMING,
        /** Disconnecting (cleanup in progress). */
        DISCONNECTING
    }

    /**
     * Minimal abstraction over WsContext for testing.
     * Allows easy faking without Mockito limitations on final methods.
     */
    interface WsContextWrapper {
        String sessionId();
        void send(String message);
        void closeSession(int statusCode, String reason);

        /**
         * Wrap a real WsContext.
         */
        static WsContextWrapper wrap(WsContext ctx) {
            return new WsContextWrapper() {
                @Override
                public String sessionId() {
                    return ctx.sessionId();
                }

                @Override
                public void send(String message) {
                    ctx.send(message);
                }

                @Override
                public void closeSession(int statusCode, String reason) {
                    ctx.closeSession(statusCode, reason);
                }
            };
        }
    }
}

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
import java.util.Map;
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
    final ConcurrentHashMap<String, ClientSession> sessions;  // Package-private for benchmark access

    // --- Rate Limiting (Luciferase-heam) ---
    private final ConcurrentHashMap<String, ClientRateLimiter> rateLimiters;

    // --- Streaming State (Day 6) ---
    private final AtomicBoolean streaming;
    private volatile Thread streamingThread;
    private final AtomicBoolean closed;  // Luciferase-gzte: Idempotent close() support

    // --- Clock ---
    private volatile Clock clock = Clock.system();

    // --- ByteBuffer Pool (Luciferase-8db0) ---
    final ByteBufferPool bufferPool;  // Package-private for benchmark access

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
        this.rateLimiters = new ConcurrentHashMap<>();
        this.streaming = new AtomicBoolean(false);
        this.closed = new AtomicBoolean(false);
        this.bufferPool = new ByteBufferPool();  // Luciferase-8db0: ByteBuffer pooling
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
        ClientSession session;

        // CRITICAL FIX (Luciferase-1026): Atomic client limit enforcement using synchronized block.
        // Previous computeIfAbsent pattern was racy: computeIfAbsent only provides
        // atomicity for a single key, not across all keys. Multiple threads with
        // different sessionIds could both see size=N-1, both pass the check, and
        // both add sessions, exceeding maxClientsPerServer=N.
        //
        // The synchronized block ensures the size check and session insertion
        // are atomic across all concurrent connection attempts.
        synchronized (sessions) {
            if (sessions.size() >= config.maxClientsPerServer()) {
                log.warn("Client limit reached ({}), rejecting connection {}",
                    config.maxClientsPerServer(), sessionId);
                ctx.closeSession(4001, "Server full");
                return;
            }
            session = new ClientSession(sessionId, ctx, clock.currentTimeMillis());
            sessions.put(sessionId, session);
        }

        viewportTracker.registerClient(sessionId);

        log.info("Client connected: {} (total: {})", sessionId, sessions.size());
    }

    /**
     * Internal message handler (testable with fake WsContextWrapper).
     * <p>
     * Luciferase-heam: Added rate limiting and message size limits to prevent DoS attacks.
     */
    void onMessageInternal(WsContextWrapper ctx, String message) {
        var sessionId = ctx.sessionId();
        var session = sessions.get(sessionId);
        if (session == null) {
            log.warn("Received message from unknown session {}", sessionId);
            return;
        }

        // Luciferase-heam: Check message size limit before processing
        // Luciferase-us4t: Count actual bytes, not characters (multi-byte UTF-8 fix)
        var messageSizeBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (messageSizeBytes > config.maxMessageSizeBytes()) {
            log.warn("Message size limit exceeded for {}: {} bytes (max: {})",
                sessionId, messageSizeBytes, config.maxMessageSizeBytes());
            ctx.closeSession(4002, "Message size limit exceeded");
            return;
        }

        // Luciferase-heam: Check rate limit before processing
        if (config.rateLimitEnabled()) {
            var rateLimiter = rateLimiters.computeIfAbsent(sessionId,
                id -> new ClientRateLimiter(config.maxMessagesPerSecond(), clock));

            if (!rateLimiter.allowMessage()) {
                log.warn("Rate limit exceeded for {}: {} messages/sec", sessionId, config.maxMessagesPerSecond());
                sendError(session, "Rate limit exceeded");
                return;
            }
        }

        session.lastActivityMs.set(clock.currentTimeMillis());

        try {
            var json = JSON_MAPPER.readTree(message);
            var type = json.get("type");
            if (type == null) {
                sendError(session, "Missing 'type' field");
                return;
            }

            switch (type.asText()) {
                case "REGISTER_CLIENT" -> handleRegisterClient(ctx, session, json);
                case "UPDATE_VIEWPORT" -> handleUpdateViewport(ctx, session, json);
                default -> sendError(session, "Unknown message type: " + type.asText());
            }

        } catch (Exception e) {
            log.error("Error processing message from {}: {}", sessionId, e.getMessage(), e);
            sendError(session, "Invalid JSON: " + e.getMessage());
        }
    }

    /**
     * Internal close handler (testable with fake WsContextWrapper).
     * <p>
     * Luciferase-r2ky: Flush any pending buffered messages before disconnect.
     */
    void onCloseInternal(WsContextWrapper ctx, int statusCode, String reason) {
        var sessionId = ctx.sessionId();
        var session = sessions.remove(sessionId);
        if (session != null) {
            // Luciferase-r2ky: Flush buffer before disconnect
            synchronized (session) {
                flushBuffer(session);
            }

            // Cleanup rate limiter to prevent memory leak on abnormal disconnect
            rateLimiters.remove(sessionId);

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
            sendError(session, "Missing 'clientId' field");
            return;
        }

        var viewportNode = json.get("viewport");
        if (viewportNode == null) {
            sendError(session, "Missing 'viewport' field");
            return;
        }

        try {
            var viewport = parseViewport(viewportNode);
            viewportTracker.updateViewport(session.sessionId, viewport);
            session.state = ClientSessionState.STREAMING;
            session.lastViewport = viewport;  // Day 6: Store for diffing

            log.debug("Registered client {} with viewport", session.sessionId);

        } catch (Exception e) {
            log.error("Error parsing viewport for {}: {}", session.sessionId, e.getMessage());
            sendError(session, "Invalid viewport: " + e.getMessage());
        }
    }

    /**
     * Handle UPDATE_VIEWPORT message.
     */
    private void handleUpdateViewport(WsContextWrapper ctx, ClientSession session, JsonNode json) {
        var clientId = json.get("clientId");
        if (clientId == null) {
            sendError(session, "Missing 'clientId' field");
            return;
        }

        var viewportNode = json.get("viewport");
        if (viewportNode == null) {
            sendError(session, "Missing 'viewport' field");
            return;
        }

        try {
            var viewport = parseViewport(viewportNode);
            viewportTracker.updateViewport(session.sessionId, viewport);
            session.lastViewport = viewport;  // Day 6: Store for diffing

            log.debug("Updated viewport for client {}", session.sessionId);

        } catch (Exception e) {
            log.error("Error parsing viewport for {}: {}", session.sessionId, e.getMessage());
            sendError(session, "Invalid viewport: " + e.getMessage());
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

        // mppj: Null checks for nested coordinate fields
        var eyeX = eyeNode.get("x");
        var eyeY = eyeNode.get("y");
        var eyeZ = eyeNode.get("z");
        if (eyeX == null || eyeY == null || eyeZ == null) {
            throw new IllegalArgumentException("Missing eye.x, eye.y, or eye.z field");
        }

        var eye = new Point3f(
            (float) eyeX.asDouble(),
            (float) eyeY.asDouble(),
            (float) eyeZ.asDouble()
        );

        var lookAtX = lookAtNode.get("x");
        var lookAtY = lookAtNode.get("y");
        var lookAtZ = lookAtNode.get("z");
        if (lookAtX == null || lookAtY == null || lookAtZ == null) {
            throw new IllegalArgumentException("Missing lookAt.x, lookAt.y, or lookAt.z field");
        }

        var lookAt = new Point3f(
            (float) lookAtX.asDouble(),
            (float) lookAtY.asDouble(),
            (float) lookAtZ.asDouble()
        );

        var upX = upNode.get("x");
        var upY = upNode.get("y");
        var upZ = upNode.get("z");
        if (upX == null || upY == null || upZ == null) {
            throw new IllegalArgumentException("Missing up.x, up.y, or up.z field");
        }

        var up = new Vector3f(
            (float) upX.asDouble(),
            (float) upY.asDouble(),
            (float) upZ.asDouble()
        );

        // mppj: Null checks for top-level viewport fields
        var fovYNode = viewportNode.get("fovY");
        var aspectRatioNode = viewportNode.get("aspectRatio");
        var nearPlaneNode = viewportNode.get("nearPlane");
        var farPlaneNode = viewportNode.get("farPlane");

        if (fovYNode == null || aspectRatioNode == null || nearPlaneNode == null || farPlaneNode == null) {
            throw new IllegalArgumentException("Missing fovY, aspectRatio, nearPlane, or farPlane field");
        }

        var fovY = (float) fovYNode.asDouble();
        var aspectRatio = (float) aspectRatioNode.asDouble();
        var nearPlane = (float) nearPlaneNode.asDouble();
        var farPlane = (float) farPlaneNode.asDouble();

        return new ClientViewport(eye, lookAt, up, fovY, aspectRatio, nearPlane, farPlane);
    }

    /**
     * Send error response to client (thread-safe).
     */
    private void sendError(ClientSession session, String message) {
        try {
            // fr0y: Safe JSON serialization (prevents injection attacks)
            var errorResponse = Map.of(
                "type", "ERROR",
                "message", message
            );
            var errorJson = JSON_MAPPER.writeValueAsString(errorResponse);
            sendSafe(session, errorJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Serialization failure is a critical internal error
            log.error("Failed to serialize error message, closing connection: {}", e.getMessage());
            session.wsContext.closeSession(1011, "Internal error");
        } catch (Exception e) {
            log.error("Failed to send error response: {}", e.getMessage());
        }
    }

    /**
     * Thread-safe send wrapper (Fix 2 from architecture).
     * <p>
     * CRITICAL FIX: Synchronize on ClientSession, not WsContextWrapper.
     * Each wrap() call creates a new instance, so synchronizing on ctx
     * provides no actual thread-safety.
     */
    private void sendSafe(ClientSession session, String json) {
        synchronized (session) {
            session.wsContext.send(json);
        }
    }

    // --- Streaming Loop (Day 6) ---

    /**
     * Start the background streaming thread.
     * <p>
     * Idempotent: does nothing if already streaming.
     */
    public void start() {
        if (streaming.compareAndSet(false, true)) {
            streamingThread = new Thread(this::streamingLoop, "RegionStreamer-" + hashCode());
            streamingThread.setDaemon(true);
            streamingThread.start();
            log.info("RegionStreamer started");
        }
    }

    /**
     * Stop the background streaming thread.
     * <p>
     * Blocks until thread terminates (up to 5 seconds).
     */
    public void stop() {
        if (streaming.compareAndSet(true, false)) {
            var thread = streamingThread;
            if (thread != null) {
                try {
                    thread.join(5000);
                    if (thread.isAlive()) {
                        log.warn("Streaming thread did not terminate within 5 seconds");
                        thread.interrupt();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while stopping streaming thread", e);
                }
                streamingThread = null;
            }
            log.info("RegionStreamer stopped");
        }
    }

    /**
     * Check if streaming is active.
     *
     * @return true if streaming thread is running
     */
    public boolean isStreaming() {
        return streaming.get();
    }

    /**
     * Main streaming loop - runs periodically to send binary frames to clients.
     */
    private void streamingLoop() {
        log.debug("Streaming loop started");
        while (streaming.get()) {
            try {
                streamingCycle();
                Thread.sleep(config.streamingIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Streaming loop interrupted");
                break;
            } catch (Exception e) {
                log.error("Error in streaming cycle: {}", e.getMessage(), e);
                // Continue loop - don't crash on single cycle error
            }
        }
        log.debug("Streaming loop terminated");
    }

    /**
     * Execute one streaming cycle: compute viewport diffs and send binary frames.
     * <p>
     * Luciferase-r2ky: Added timeout-based buffer flush (50ms).
     * <p>
     * Package-private for benchmark access.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>For each STREAMING client:</li>
     *   <li>  Compute viewport diff (added/removed/LOD-changed regions)</li>
     *   <li>  For each added region:</li>
     *   <li>    Check backpressure (pendingSends < maxPendingSendsPerClient)</li>
     *   <li>    Build region on-demand via RegionBuilder</li>
     *   <li>    Encode to binary frame via BinaryFrameCodec</li>
     *   <li>    Send asynchronously</li>
     *   <li>  Unpin removed regions (only if no other clients viewing)</li>
     *   <li>  Flush buffer if 50ms elapsed since last flush</li>
     * </ol>
     */
    void streamingCycle() {
        var now = clock.currentTimeMillis();

        for (var session : sessions.values()) {
            if (session.state != ClientSessionState.STREAMING) {
                continue;
            }

            try {
                streamToClient(session);

                // Luciferase-r2ky: Flush buffer if 50ms elapsed since last flush
                synchronized (session) {
                    var elapsed = now - session.lastFlushMs.get();
                    if (elapsed >= 50 && !session.messageBuffer.isEmpty()) {
                        flushBuffer(session);
                    }
                }
            } catch (Exception e) {
                log.error("Error streaming to client {}: {}", session.sessionId, e.getMessage(), e);
                // Continue to next client - don't crash entire cycle
            }
        }
    }

    /**
     * Stream binary frames to a single client.
     *
     * @param session Client session
     */
    private void streamToClient(ClientSession session) {
        // Compute viewport diff
        var diff = viewportTracker.diffViewport(session.sessionId);

        if (diff.isEmpty()) {
            return; // No changes since last cycle
        }

        // Handle added regions (new regions or closer regions with higher LOD)
        for (var visibleRegion : diff.added()) {
            // Backpressure check
            if (session.pendingSends.get() >= config.maxPendingSendsPerClient()) {
                log.debug("Skipping region {} for client {} - backpressure (pending: {})",
                    visibleRegion.regionId(), session.sessionId, session.pendingSends.get());
                continue;
            }

            if (regionCache != null) {
                // A.2 (Luciferase-ct58): Normalize to LOD 0. The build pipeline always
                // produces LOD 0 entries. Streaming delivers the same data regardless
                // of client LOD — the LOD level in VisibleRegion only informs which
                // regions are worth building, not which cache bucket to look up.
                var cacheKey = new RegionCache.CacheKey(visibleRegion.regionId(), 0);
                var cached = regionCache.get(cacheKey);

                if (cached.isPresent()) {
                    sendBinaryFrameAsync(session, cached.get());
                } else {
                    // A.1 (Luciferase-06qb): Cache miss — trigger an on-demand build.
                    // Previously this only logged a trace message, so regions were never
                    // built unless the 10s backfill cycle happened to run first.
                    regionManager.scheduleBuild(visibleRegion.regionId(), true);
                }
            }
        }

        // Handle LOD-changed regions (same region, different LOD)
        for (var visibleRegion : diff.lodChanged()) {
            if (session.pendingSends.get() >= config.maxPendingSendsPerClient()) {
                continue;
            }

            if (regionCache != null) {
                // A.2: same LOD-0 normalization as the added-regions path above
                var cacheKey = new RegionCache.CacheKey(visibleRegion.regionId(), 0);
                var cached = regionCache.get(cacheKey);

                if (cached.isPresent()) {
                    sendBinaryFrameAsync(session, cached.get());
                }
            }
        }

        // Handle removed regions - unpin if no other clients viewing (Fix 1)
        unpinRegionsNotVisibleToAnyClient(diff.removed());
    }

    /**
     * Send binary frame asynchronously to client.
     * <p>
     * Luciferase-r2ky: Accumulates messages in buffer, flushes when threshold reached.
     * Increments pendingSends counter, encodes to binary frame, adds to buffer.
     * Decrements counter on completion.
     * <p>
     * Package-private for benchmark access.
     *
     * @param session Client session
     * @param cachedRegion Cached region to send
     */
    void sendBinaryFrameAsync(ClientSession session, RegionCache.CachedRegion cachedRegion) {
        session.pendingSends.incrementAndGet();

        try {
            var builtRegion = cachedRegion.builtRegion();

            // Luciferase-8db0: Borrow buffer from pool
            var data = builtRegion.serializedData();
            int requiredSize = com.hellblazer.luciferase.simulation.viz.render.protocol.ProtocolConstants.FRAME_HEADER_SIZE + data.length;
            var frame = bufferPool.borrow(requiredSize);

            // Encode to binary WebSocket frame using pooled buffer
            com.hellblazer.luciferase.simulation.viz.render.protocol.BinaryFrameCodec.encode(
                builtRegion, frame
            );

            // Luciferase-r2ky: Add to buffer instead of sending immediately
            synchronized (session) {
                session.messageBuffer.add(frame);

                // Flush if buffer reaches 10 messages
                if (session.messageBuffer.size() >= 10) {
                    flushBuffer(session);
                }
            }

            log.trace("Buffered binary frame for region {} LOD {} to client {} ({} bytes, buffer size: {})",
                builtRegion.regionId(), builtRegion.lodLevel(), session.sessionId, frame.remaining(),
                session.messageBuffer.size());

        } catch (Exception e) {
            log.error("Failed to buffer binary frame for client {}: {}", session.sessionId, e.getMessage());
        } finally {
            session.pendingSends.decrementAndGet();
        }
    }

    /**
     * Flush buffered messages to client (Luciferase-r2ky).
     * <p>
     * Sends all buffered binary frames and clears buffer.
     * Luciferase-8db0: Returns ByteBuffers to pool after send.
     * Must be called with session lock held.
     * <p>
     * Package-private for benchmark access.
     *
     * @param session Client session
     */
    void flushBuffer(ClientSession session) {
        if (session.messageBuffer.isEmpty()) {
            return;
        }

        var count = session.messageBuffer.size();
        var now = clock.currentTimeMillis();

        try {
            for (var frame : session.messageBuffer) {
                session.wsContext.sendBinary(frame);
                // Luciferase-8db0: Return buffer to pool after send
                bufferPool.returnBuffer(frame);
            }

            log.trace("Flushed {} buffered messages to client {} (elapsed: {}ms)",
                count, session.sessionId, now - session.lastFlushMs.get());

        } catch (Exception e) {
            log.error("Failed to flush buffer for client {}: {}", session.sessionId, e.getMessage());
            // Return buffers on error path too
            for (var frame : session.messageBuffer) {
                bufferPool.returnBuffer(frame);
            }
        } finally {
            session.messageBuffer.clear();
            session.lastFlushMs.set(now);
        }
    }

    /**
     * Unpin regions that are no longer visible to ANY client (Fix 1).
     * <p>
     * Uses viewportTracker.allVisibleRegions() to check if other clients still need the region.
     * Only unpins if no clients are viewing.
     *
     * @param removed Regions removed from this client's viewport
     */
    private void unpinRegionsNotVisibleToAnyClient(java.util.Set<RegionId> removed) {
        if (regionCache == null || removed.isEmpty()) {
            return;
        }

        var allVisible = viewportTracker.allVisibleRegions();

        for (var regionId : removed) {
            if (!allVisible.contains(regionId)) {
                // No clients need this region - safe to unpin
                // Try all LOD levels (we don't know which LOD was pinned)
                for (int lod = 0; lod <= config.maxLodLevel(); lod++) {
                    var cacheKey = new RegionCache.CacheKey(regionId, lod);
                    regionCache.unpin(cacheKey);
                }
                log.debug("Unpinned region {} - no longer visible to any client", regionId);
            }
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
     * Callback from AdaptiveRegionManager when a region build completes (Phase 3 Day 7).
     * <p>
     * This allows RegionStreamer to immediately send the built region to waiting clients
     * without polling the cache.
     *
     * @param regionId     Region that was built
     * @param builtRegion  The built region data
     */
    public void onRegionBuilt(RegionId regionId, RegionBuilder.BuiltRegion builtRegion) {
        log.debug("Region {} built, notifying streaming clients", regionId);

        // Find all clients that need this region and send it immediately
        for (var session : sessions.values()) {
            if (session.state != ClientSessionState.STREAMING) {
                continue;
            }

            // Check if this client is viewing this region.
            // A.2 (Luciferase-ct58): Do NOT filter by lodLevel. The build pipeline only
            // produces LOD 0 entries. A client viewing this region at LOD 3 still needs
            // the LOD 0 frame — matching only on regionId is correct.
            var visible = viewportTracker.visibleRegions(session.sessionId);
            var needsRegion = visible.stream()
                .anyMatch(vr -> vr.regionId().equals(regionId));

            if (needsRegion && session.pendingSends.get() < config.maxPendingSendsPerClient()) {
                // Send immediately without waiting for next streaming cycle
                if (regionCache != null) {
                    var cacheKey = new RegionCache.CacheKey(regionId, builtRegion.lodLevel());
                    var cached = regionCache.get(cacheKey);
                    cached.ifPresent(cachedRegion -> sendBinaryFrameAsync(session, cachedRegion));
                }
            }
        }
    }

    /**
     * Set clock for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    /**
     * Close the streamer and clean up all resources (Luciferase-gzte).
     * <p>
     * - Closes all WebSocket sessions with status 1001 (Going Away)
     * - Clears sessions map
     * - Idempotent: safe to call multiple times
     */
    public void close() {
        // Idempotent check: only run cleanup once
        if (!closed.compareAndSet(false, true)) {
            log.debug("close() already called, skipping cleanup");
            return;
        }

        log.info("Closing RegionStreamer, cleaning up {} sessions", sessions.size());

        // Stop streaming first
        stop();

        // Close all WebSocket sessions with status 1001 (Going Away)
        for (var session : sessions.values()) {
            try {
                session.wsContext.closeSession(1001, "Server shutdown");
            } catch (Exception e) {
                // Handle exceptions gracefully - one session failure shouldn't prevent others from closing
                log.warn("Failed to close session {}: {}", session.sessionId, e.getMessage());
            }
        }

        // Clear sessions map
        sessions.clear();

        log.info("RegionStreamer closed, all resources released");
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
        volatile ClientViewport lastViewport;  // Day 6: Track last viewport for diffing

        // Luciferase-r2ky: Message batching optimization
        final java.util.List<java.nio.ByteBuffer> messageBuffer;
        final AtomicLong lastFlushMs;

        ClientSession(String sessionId, WsContextWrapper wsContext, long currentTimeMs) {
            this.sessionId = sessionId;
            this.wsContext = wsContext;
            this.state = ClientSessionState.CONNECTED;
            this.lastViewportUpdateMs = new AtomicLong(0);
            this.lastActivityMs = new AtomicLong(currentTimeMs);
            this.pendingSends = new AtomicInteger(0);
            this.lastViewport = null;

            // Luciferase-r2ky: Initialize message buffer
            this.messageBuffer = new java.util.ArrayList<>(10);
            this.lastFlushMs = new AtomicLong(currentTimeMs);
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
        void sendBinary(java.nio.ByteBuffer data);  // Day 6: Binary frame delivery
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
                public void sendBinary(java.nio.ByteBuffer data) {
                    ctx.send(data);
                }

                @Override
                public void closeSession(int statusCode, String reason) {
                    ctx.closeSession(statusCode, reason);
                }
            };
        }
    }
}

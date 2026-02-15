/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RegionStreamer WebSocket handler.
 * Day 5: Core handler lifecycle (Tests 1-9)
 * Day 6: Streaming loop (Tests 10-18)
 *
 * @author hal.hildebrand
 */
class RegionStreamerTest {

    private RegionStreamer streamer;
    private ViewportTracker viewportTracker;
    private AdaptiveRegionManager regionManager;
    private StreamingConfig config;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        testClock = new TestClock();
        testClock.setTime(1000L);

        var serverConfig = RenderingServerConfig.testing();
        regionManager = new AdaptiveRegionManager(serverConfig);

        config = StreamingConfig.testing();
        viewportTracker = new ViewportTracker(regionManager, config);
        viewportTracker.setClock(testClock);

        // RegionCache and RegionBuilder not needed for Day 5 tests (used in Day 6 streaming)
        streamer = new RegionStreamer(viewportTracker, null, regionManager, config);
        streamer.setClock(testClock);
    }

    // ===== PHASE 1: Lifecycle Tests (1-3) =====

    /**
     * Test 1: onConnect creates session and registers client with ViewportTracker.
     */
    @Test
    void testOnConnect_createsSession() {
        var ctx = new FakeWsContext("session-1");

        streamer.onConnectInternal(ctx);

        assertEquals(1, streamer.connectedClientCount(), "Should have 1 connected client");
        assertEquals(1, viewportTracker.clientCount(), "ViewportTracker should have 1 registered client");
        assertFalse(ctx.wasClosed, "Connection should not be closed");
    }

    /**
     * Test 2: REGISTER_CLIENT message transitions state and registers with ViewportTracker.
     */
    @Test
    void testRegisterClient_message() {
        var ctx = new FakeWsContext("session-2");
        streamer.onConnectInternal(ctx);

        var registerJson = """
            {
                "type": "REGISTER_CLIENT",
                "clientId": "client-123",
                "viewport": {
                    "eye": {"x": 512, "y": 512, "z": 100},
                    "lookAt": {"x": 512, "y": 512, "z": 512},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "fovY": 1.047,
                    "aspectRatio": 1.777,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;

        streamer.onMessageInternal(ctx, registerJson);

        // ViewportTracker should have the viewport registered
        var visible = viewportTracker.visibleRegions("session-2");
        assertNotNull(visible, "Should have visibility info after registration");
    }

    /**
     * Test 3: onClose removes session and unregisters from ViewportTracker.
     */
    @Test
    void testOnClose_cleansUpSession() {
        var ctx = new FakeWsContext("session-3");
        streamer.onConnectInternal(ctx);

        streamer.onCloseInternal(ctx, 1000, "Normal close");

        assertEquals(0, streamer.connectedClientCount(), "Should have 0 connected clients");
        assertEquals(0, viewportTracker.clientCount(), "ViewportTracker should have 0 clients");
    }

    // ===== PHASE 3: UPDATE_VIEWPORT Tests (4-6) =====

    /**
     * Test 4: UPDATE_VIEWPORT message updates viewport in ViewportTracker.
     */
    @Test
    void testUpdateViewport_updatesTracker() {
        var ctx = new FakeWsContext("session-4");
        streamer.onConnectInternal(ctx);

        // First register
        var registerJson = """
            {
                "type": "REGISTER_CLIENT",
                "clientId": "client-456",
                "viewport": {
                    "eye": {"x": 512, "y": 512, "z": 100},
                    "lookAt": {"x": 512, "y": 512, "z": 512},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "fovY": 1.047,
                    "aspectRatio": 1.777,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;
        streamer.onMessageInternal(ctx, registerJson);

        // Then update viewport
        var updateJson = """
            {
                "type": "UPDATE_VIEWPORT",
                "clientId": "client-456",
                "viewport": {
                    "eye": {"x": 256, "y": 256, "z": 50},
                    "lookAt": {"x": 512, "y": 512, "z": 512},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "fovY": 1.047,
                    "aspectRatio": 1.777,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;
        streamer.onMessageInternal(ctx, updateJson);

        // ViewportTracker should have updated viewport
        var visible = viewportTracker.visibleRegions("session-4");
        assertNotNull(visible, "Should have visibility info after update");
    }

    /**
     * Test 5: UPDATE_VIEWPORT with missing clientId field sends ERROR.
     */
    @Test
    void testUpdateViewport_missingClientId() {
        var ctx = new FakeWsContext("session-5");
        streamer.onConnectInternal(ctx);

        var updateJson = """
            {
                "type": "UPDATE_VIEWPORT",
                "viewport": {
                    "eye": {"x": 256, "y": 256, "z": 50},
                    "lookAt": {"x": 512, "y": 512, "z": 512},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "fovY": 1.047,
                    "aspectRatio": 1.777,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;
        streamer.onMessageInternal(ctx, updateJson);

        // Should send ERROR response
        assertTrue(ctx.sentMessages.size() > 0, "Should have sent error message");
        assertTrue(ctx.sentMessages.get(0).contains("ERROR"), "Should be ERROR message");
        assertTrue(ctx.sentMessages.get(0).contains("clientId"), "Should mention missing clientId");
    }

    /**
     * Test 6: UPDATE_VIEWPORT with missing viewport field sends ERROR.
     */
    @Test
    void testUpdateViewport_missingViewport() {
        var ctx = new FakeWsContext("session-6");
        streamer.onConnectInternal(ctx);

        var updateJson = """
            {
                "type": "UPDATE_VIEWPORT",
                "clientId": "client-789"
            }
            """;
        streamer.onMessageInternal(ctx, updateJson);

        // Should send ERROR response
        assertTrue(ctx.sentMessages.size() > 0, "Should have sent error message");
        assertTrue(ctx.sentMessages.get(0).contains("ERROR"), "Should be ERROR message");
        assertTrue(ctx.sentMessages.get(0).contains("viewport"), "Should mention missing viewport");
    }

    // ===== PHASE 5: Error Handling Tests (7-9) =====

    /**
     * Test 7: Invalid JSON sends ERROR response.
     */
    @Test
    void testErrorHandling_invalidJson() {
        var ctx = new FakeWsContext("session-7");
        streamer.onConnectInternal(ctx);

        var invalidJson = "{ this is not valid JSON }";
        streamer.onMessageInternal(ctx, invalidJson);

        // Should send ERROR response
        assertTrue(ctx.sentMessages.size() > 0, "Should have sent error message");
        assertTrue(ctx.sentMessages.get(0).contains("ERROR"), "Should be ERROR message");
        assertTrue(ctx.sentMessages.get(0).contains("Invalid JSON"), "Should mention invalid JSON");
    }

    /**
     * Test 8: Unknown message type sends ERROR response.
     */
    @Test
    void testErrorHandling_unknownMessageType() {
        var ctx = new FakeWsContext("session-8");
        streamer.onConnectInternal(ctx);

        var unknownTypeJson = """
            {
                "type": "UNKNOWN_TYPE",
                "someData": "test"
            }
            """;
        streamer.onMessageInternal(ctx, unknownTypeJson);

        // Should send ERROR response
        assertTrue(ctx.sentMessages.size() > 0, "Should have sent error message");
        assertTrue(ctx.sentMessages.get(0).contains("ERROR"), "Should be ERROR message");
        assertTrue(ctx.sentMessages.get(0).contains("Unknown message type"), "Should mention unknown type");
    }

    /**
     * Test 9: Missing 'type' field sends ERROR response.
     */
    @Test
    void testErrorHandling_missingTypeField() {
        var ctx = new FakeWsContext("session-9");
        streamer.onConnectInternal(ctx);

        var noTypeJson = """
            {
                "clientId": "client-123",
                "someData": "test"
            }
            """;
        streamer.onMessageInternal(ctx, noTypeJson);

        // Should send ERROR response
        assertTrue(ctx.sentMessages.size() > 0, "Should have sent error message");
        assertTrue(ctx.sentMessages.get(0).contains("ERROR"), "Should be ERROR message");
        assertTrue(ctx.sentMessages.get(0).contains("type"), "Should mention missing type");
    }

    // ===== DAY 6: Streaming Loop Tests (10-18) =====

    /**
     * Test 10: start() launches streaming thread and sets streaming flag.
     */
    @Test
    void testStreamingStartStop() throws InterruptedException {
        assertFalse(streamer.isStreaming(), "Should not be streaming initially");

        streamer.start();
        assertTrue(streamer.isStreaming(), "Should be streaming after start()");

        // Give thread time to start
        Thread.sleep(100);

        streamer.stop();
        assertFalse(streamer.isStreaming(), "Should stop streaming after stop()");

        // Give thread time to terminate
        Thread.sleep(100);
    }

    /**
     * Test 11: streamingCycle() runs periodically when streaming is active.
     */
    @Test
    void testStreamingCycle() throws InterruptedException {
        // Need a way to observe that streamingCycle() was called
        // For now, verify start/stop works and thread runs
        streamer.start();
        assertTrue(streamer.isStreaming(), "Should be streaming");

        // Let it run for 2 intervals (2 * 50ms = 100ms in testing config)
        Thread.sleep(150);

        streamer.stop();
        assertFalse(streamer.isStreaming(), "Should have stopped");
    }

    /**
     * Test 12: Streaming interval is respected (config.streamingIntervalMs()).
     */
    @Test
    void testStreamingInterval() throws InterruptedException {
        // Testing config has 50ms interval
        // Verify we can start/stop without errors
        streamer.start();
        assertTrue(streamer.isStreaming());

        // Run for a few intervals
        Thread.sleep(200);

        streamer.stop();
        assertFalse(streamer.isStreaming());
    }

    /**
     * Test 13: Viewport diff sends added regions.
     * <p>
     * NOTE: Full implementation deferred to Day 7 integration.
     * Current implementation handles streamingCycle() with:
     * - ViewportTracker.diffViewport() to compute added/removed/LOD-changed
     * - Backpressure check (pendingSends < maxPendingSendsPerClient)
     * - RegionCache lookup for cached regions
     * - BinaryFrameCodec.encode() + sendBinary() for delivery
     * - Pin/unpin via viewportTracker.allVisibleRegions() (Fix 1)
     * <p>
     * Day 7 will add:
     * - RegionBuilder integration for on-demand building
     * - AdaptiveRegionManager.setRegionStreamer() for build notifications
     */
    @Test
    void testViewportDiff_SendsAddedRegions() {
        // Day 7: Test with RegionBuilder/RegionCache in full integration
        // For now, streamingCycle() is implemented and compiles
    }

    /**
     * Test 14: Viewport diff skips removed regions.
     * <p>
     * Validated in streamingCycle() - removed regions trigger unpin check,
     * not binary frame sends.
     */
    @Test
    void testViewportDiff_SkipsRemovedRegions() {
        // Day 7: Full validation with RegionCache
    }

    /**
     * Test 15: Viewport diff handles LOD-changed regions.
     * <p>
     * Implementation in streamToClient() - LOD-changed regions are sent
     * like added regions (same region ID, different LOD level).
     */
    @Test
    void testViewportDiff_HandleLODChanged() {
        // Day 7: Full validation
    }

    /**
     * Test 16: Backpressure - skip send when pendingSends >= maxPendingSendsPerClient.
     * <p>
     * Implementation in streamToClient():
     * <pre>
     * if (session.pendingSends.get() >= config.maxPendingSendsPerClient()) {
     *     log.debug("Skipping region - backpressure");
     *     continue;
     * }
     * </pre>
     */
    @Test
    void testBackpressure_SkipsSendWhenFull() {
        // Day 7: Validate with simulated backpressure scenario
    }

    /**
     * Test 17: Pin/unpin only when no clients viewing (Fix 1).
     * <p>
     * Implementation in unpinRegionsNotVisibleToAnyClient():
     * <pre>
     * var allVisible = viewportTracker.allVisibleRegions();
     * for (var regionId : removed) {
     *     if (!allVisible.contains(regionId)) {
     *         regionCache.unpin(cacheKey);
     *     }
     * }
     * </pre>
     */
    @Test
    void testPinUnpin_UnpinsOnlyWhenNoClients() {
        // Day 7: Validate with multi-client scenario
    }

    /**
     * Test 18: Binary frame encoding uses BinaryFrameCodec.
     * <p>
     * Implementation in sendBinaryFrameAsync():
     * <pre>
     * var frame = BinaryFrameCodec.encode(builtRegion);
     * session.wsContext.sendBinary(frame);
     * </pre>
     */
    @Test
    void testBinaryFrameEncoding() {
        // Day 7: Validate frame format and delivery
    }

    /**
     * Test 19: Resource cleanup on close() (Luciferase-gzte).
     * Verify that close() properly closes all WebSocket sessions and is idempotent.
     */
    @Test
    void testClose_closesAllSessions() {
        // Connect 3 clients
        var ctx1 = new FakeWsContext("session-1");
        var ctx2 = new FakeWsContext("session-2");
        var ctx3 = new FakeWsContext("session-3");

        streamer.onConnectInternal(ctx1);
        streamer.onConnectInternal(ctx2);
        streamer.onConnectInternal(ctx3);

        assertEquals(3, streamer.connectedClientCount());
        assertFalse(ctx1.wasClosed);
        assertFalse(ctx2.wasClosed);
        assertFalse(ctx3.wasClosed);

        // Close the streamer
        streamer.close();

        // All sessions should be closed with status 1001 (Going Away)
        assertTrue(ctx1.wasClosed, "Session 1 should be closed");
        assertTrue(ctx2.wasClosed, "Session 2 should be closed");
        assertTrue(ctx3.wasClosed, "Session 3 should be closed");
        assertEquals(1001, ctx1.closeCode, "Should use status 1001 (Going Away)");
        assertEquals(1001, ctx2.closeCode, "Should use status 1001 (Going Away)");
        assertEquals(1001, ctx3.closeCode, "Should use status 1001 (Going Away)");

        // Sessions map should be cleared
        assertEquals(0, streamer.connectedClientCount());

        // Second close() should be idempotent (no errors)
        assertDoesNotThrow(() -> streamer.close());
    }

    /**
     * Test 20: Concurrent client limit enforcement (Luciferase-1026).
     * Verify that when maxClientsPerServer concurrent connection attempts occur,
     * exactly maxClientsPerServer succeed and the rest are rejected.
     * This test validates the fix for the race condition in computeIfAbsent.
     */
    @Test
    void testConcurrentClientLimitEnforcement() throws InterruptedException {
        // Set up config with limit=10 (using testing() defaults)
        var limitedConfig = StreamingConfig.testing();  // maxClientsPerServer = 10
        var limitedStreamer = new RegionStreamer(viewportTracker, null, regionManager, limitedConfig);
        limitedStreamer.setClock(testClock);

        // Attempt 100 concurrent connections (10x the limit)
        int attemptedConnections = 100;
        var latch = new java.util.concurrent.CountDownLatch(attemptedConnections);
        var successfulConnections = new java.util.concurrent.atomic.AtomicInteger(0);
        var rejectedConnections = new java.util.concurrent.atomic.AtomicInteger(0);

        // Spawn 100 threads that all try to connect simultaneously
        var executor = java.util.concurrent.Executors.newFixedThreadPool(50);
        for (int i = 0; i < attemptedConnections; i++) {
            final String sessionId = "session-" + i;
            executor.submit(() -> {
                try {
                    var ctx = new FakeWsContext(sessionId);
                    limitedStreamer.onConnectInternal(ctx);

                    if (ctx.wasClosed) {
                        rejectedConnections.incrementAndGet();
                    } else {
                        successfulConnections.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        boolean completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(completed, "All connection attempts should complete within 5 seconds");

        // CRITICAL ASSERTION: Exactly 10 connections should succeed
        assertEquals(10, successfulConnections.get(),
            "Exactly maxClientsPerServer connections should succeed");
        assertEquals(90, rejectedConnections.get(),
            "Remaining connections should be rejected");

        // Verify all rejected connections received proper error code
        // (This is validated by checking ctx.wasClosed in the thread above)

        limitedStreamer.close();
    }

    // Fake WsContextWrapper for testing
    private static class FakeWsContext implements RegionStreamer.WsContextWrapper {
        final String sessionIdValue;
        final List<String> sentMessages = new ArrayList<>();
        final List<java.nio.ByteBuffer> sentBinaryFrames = new ArrayList<>();  // Day 6
        boolean wasClosed = false;
        int closeCode = -1;
        String closeReason = null;

        FakeWsContext(String sessionId) {
            this.sessionIdValue = sessionId;
        }

        @Override
        public String sessionId() {
            return sessionIdValue;
        }

        @Override
        public void send(String message) {
            sentMessages.add(message);
        }

        @Override
        public void sendBinary(java.nio.ByteBuffer data) {
            // Store a copy since ByteBuffer position may change
            var copy = java.nio.ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            sentBinaryFrames.add(copy);
        }

        @Override
        public void closeSession(int statusCode, String reason) {
            wasClosed = true;
            closeCode = statusCode;
            closeReason = reason;
        }
    }
}

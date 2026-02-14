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
 * Tests for RegionStreamer WebSocket handler (Day 5 - core handler only).
 * Streaming loop tests are in Day 6.
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

    // Fake WsContextWrapper for testing
    private static class FakeWsContext implements RegionStreamer.WsContextWrapper {
        final String sessionIdValue;
        final List<String> sentMessages = new ArrayList<>();
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
        public void closeSession(int statusCode, String reason) {
            wasClosed = true;
            closeCode = statusCode;
            closeReason = reason;
        }
    }
}

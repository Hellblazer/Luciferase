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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON null-check validation tests for RegionStreamer.
 * <p>
 * Tests the fix for Luciferase-mppj: Add JSON null checks and validation.
 * Validates that malformed messages with missing fields are handled gracefully:
 * - Missing top-level fields (type, clientId, viewport)
 * - Missing nested object fields (eye, lookAt, up)
 * - Missing coordinate fields (x, y, z)
 * - Missing viewport parameters (fovY, aspectRatio, nearPlane, farPlane)
 *
 * All validation errors trigger safe error responses (via Luciferase-fr0y fix).
 *
 * @author hal.hildebrand
 */
class RegionStreamerValidationTest {

    private RegionStreamer streamer;
    private FakeWsContext ctx;

    @BeforeEach
    void setUp() {
        var testClock = new TestClock();
        testClock.setTime(1000L);

        var serverConfig = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(serverConfig);

        var config = StreamingConfig.testing();
        var viewportTracker = new ViewportTracker(regionManager, config);
        viewportTracker.setClock(testClock);

        streamer = new RegionStreamer(viewportTracker, null, regionManager, config);
        streamer.setClock(testClock);

        ctx = new FakeWsContext("test-session");
        streamer.onConnectInternal(ctx);
    }

    @Test
    void testMissingTopLevelType_SendsError() {
        // Given: Message with no 'type' field
        var invalidJson = "{\"clientId\": \"test\"}";

        // When: Send invalid message
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error response sent
        assertTrue(ctx.sentMessages.size() > 0, "Should send error");
        assertTrue(ctx.sentMessages.get(0).contains("Missing 'type' field"),
                   "Error should mention missing type");
    }

    @Test
    void testMissingClientId_SendsError() {
        // Given: REGISTER_CLIENT without clientId
        var invalidJson = """
            {
                "type": "REGISTER_CLIENT",
                "viewport": {
                    "eye": {"x": 0, "y": 0, "z": 0},
                    "lookAt": {"x": 1, "y": 0, "z": 0},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "fovY": 45.0,
                    "aspectRatio": 1.0,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;

        // When: Send
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error sent
        assertTrue(ctx.sentMessages.size() > 0);
        assertTrue(ctx.sentMessages.get(0).contains("Missing 'clientId' field"));
    }

    @Test
    void testMissingViewport_SendsError() {
        // Given: REGISTER_CLIENT without viewport
        var invalidJson = """
            {
                "type": "REGISTER_CLIENT",
                "clientId": "test-client"
            }
            """;

        // When: Send
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error sent
        assertTrue(ctx.sentMessages.size() > 0);
        assertTrue(ctx.sentMessages.get(0).contains("Missing 'viewport' field"));
    }

    @Test
    void testMissingEye_SendsError() {
        // Given: Viewport without eye field
        var invalidJson = """
            {
                "type": "REGISTER_CLIENT",
                "clientId": "test",
                "viewport": {
                    "lookAt": {"x": 1, "y": 0, "z": 0},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "fovY": 45.0,
                    "aspectRatio": 1.0,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;

        // When: Send
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error sent
        assertTrue(ctx.sentMessages.size() > 0);
        assertTrue(ctx.sentMessages.get(0).contains("Missing eye"));
    }

    @Test
    void testMissingEyeX_SendsError() {
        // Given: eye object without x coordinate
        var invalidJson = """
            {
                "type": "REGISTER_CLIENT",
                "clientId": "test",
                "viewport": {
                    "eye": {"y": 0, "z": 0},
                    "lookAt": {"x": 1, "y": 0, "z": 0},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "fovY": 45.0,
                    "aspectRatio": 1.0,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;

        // When: Send
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error sent
        assertTrue(ctx.sentMessages.size() > 0);
        assertTrue(ctx.sentMessages.get(0).contains("eye.x"));
    }

    @Test
    void testMissingLookAtY_SendsError() {
        // Given: lookAt without y coordinate
        var invalidJson = """
            {
                "type": "REGISTER_CLIENT",
                "clientId": "test",
                "viewport": {
                    "eye": {"x": 0, "y": 0, "z": 0},
                    "lookAt": {"x": 1, "z": 0},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "fovY": 45.0,
                    "aspectRatio": 1.0,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;

        // When: Send
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error sent
        assertTrue(ctx.sentMessages.size() > 0);
        assertTrue(ctx.sentMessages.get(0).contains("lookAt.y"));
    }

    @Test
    void testMissingUpZ_SendsError() {
        // Given: up vector without z
        var invalidJson = """
            {
                "type": "UPDATE_VIEWPORT",
                "clientId": "test",
                "viewport": {
                    "eye": {"x": 0, "y": 0, "z": 0},
                    "lookAt": {"x": 1, "y": 0, "z": 0},
                    "up": {"x": 0, "y": 1},
                    "fovY": 45.0,
                    "aspectRatio": 1.0,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;

        // When: Send
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error sent
        assertTrue(ctx.sentMessages.size() > 0);
        assertTrue(ctx.sentMessages.get(0).contains("up.z"));
    }

    @Test
    void testMissingFovY_SendsError() {
        // Given: Viewport without fovY
        var invalidJson = """
            {
                "type": "UPDATE_VIEWPORT",
                "clientId": "test",
                "viewport": {
                    "eye": {"x": 0, "y": 0, "z": 0},
                    "lookAt": {"x": 1, "y": 0, "z": 0},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "aspectRatio": 1.0,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;

        // When: Send
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error sent
        assertTrue(ctx.sentMessages.size() > 0);
        assertTrue(ctx.sentMessages.get(0).contains("fovY"));
    }

    @Test
    void testMissingNearPlane_SendsError() {
        // Given: Viewport without nearPlane
        var invalidJson = """
            {
                "type": "REGISTER_CLIENT",
                "clientId": "test",
                "viewport": {
                    "eye": {"x": 0, "y": 0, "z": 0},
                    "lookAt": {"x": 1, "y": 0, "z": 0},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "fovY": 45.0,
                    "aspectRatio": 1.0,
                    "farPlane": 1000.0
                }
            }
            """;

        // When: Send
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error sent
        assertTrue(ctx.sentMessages.size() > 0);
        assertTrue(ctx.sentMessages.get(0).contains("nearPlane"));
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: Investigate session state interaction between tests")
    void testValidMessage_NoError() {
        // Given: Fresh session and completely valid message
        var freshCtx = new FakeWsContext("valid-test-session");
        streamer.onConnectInternal(freshCtx);

        var validJson = """
            {
                "type": "REGISTER_CLIENT",
                "clientId": "valid-client",
                "viewport": {
                    "eye": {"x": 10.0, "y": 5.0, "z": 15.0},
                    "lookAt": {"x": 0.0, "y": 0.0, "z": 0.0},
                    "up": {"x": 0.0, "y": 1.0, "z": 0.0},
                    "fovY": 45.0,
                    "aspectRatio": 1.777,
                    "nearPlane": 0.1,
                    "farPlane": 1000.0
                }
            }
            """;

        // When: Send
        streamer.onMessageInternal(freshCtx, validJson);

        // Then: No error sent (may have other messages, but not ERROR type)
        for (var msg : freshCtx.sentMessages) {
            assertFalse(msg.contains("\"type\":\"ERROR\""),
                       "Should not send error for valid message: " + msg);
        }
    }

    // --- Test Helper ---

    private static class FakeWsContext implements RegionStreamer.WsContextWrapper {
        final String sessionIdValue;
        final List<String> sentMessages = new ArrayList<>();
        final List<ByteBuffer> sentBinaryFrames = new ArrayList<>();
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
        public void sendBinary(ByteBuffer data) {
            var copy = ByteBuffer.allocate(data.remaining());
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

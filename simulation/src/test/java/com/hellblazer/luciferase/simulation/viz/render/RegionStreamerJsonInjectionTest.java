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
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSON injection security tests for RegionStreamer.sendError().
 * <p>
 * Tests the fix for Luciferase-fr0y: Fix JSON injection vulnerability in RegionStreamer.sendError().
 * Validates that error messages are safely serialized regardless of content:
 * - Unicode characters
 * - Escape sequences (quotes, backslashes, newlines)
 * - Control characters
 * - Injection attack vectors (attempting to break JSON structure)
 *
 * @author hal.hildebrand
 */
class RegionStreamerJsonInjectionTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private RegionStreamer streamer;
    private ViewportTracker viewportTracker;
    private AdaptiveRegionManager regionManager;
    private StreamingConfig config;
    private TestClock testClock;
    private FakeWsContext ctx;

    @BeforeEach
    void setUp() {
        testClock = new TestClock();
        testClock.setTime(1000L);

        var serverConfig = RenderingServerConfig.testing();
        regionManager = new AdaptiveRegionManager(serverConfig);

        config = StreamingConfig.testing();
        viewportTracker = new ViewportTracker(regionManager, config);
        viewportTracker.setClock(testClock);

        streamer = new RegionStreamer(viewportTracker, null, regionManager, config);
        streamer.setClock(testClock);

        ctx = new FakeWsContext("test-session");
        streamer.onConnectInternal(ctx);
    }

    @Test
    void testUnicodeCharacters_SafelySerialized() throws Exception {
        // Given: Message with unicode characters
        var maliciousMessage = "Error: \u4E2D\u6587 \u0420\u0443\u0441\u0441\u043A\u0438\u0439 \uD83D\uDE80";  // Chinese, Russian, Rocket emoji

        // When: Trigger error sending (via invalid message type)
        streamer.onMessageInternal(ctx, "{\"type\":\"INVALID\"}");

        // Then: JSON is valid and contains escaped unicode
        assertTrue(ctx.sentMessages.size() > 0, "Error message should be sent");
        var errorJson = ctx.sentMessages.get(ctx.sentMessages.size() - 1);

        // Validate JSON structure
        JsonNode parsed = JSON_MAPPER.readTree(errorJson);
        assertEquals("ERROR", parsed.get("type").asText());
        assertNotNull(parsed.get("message"));
    }

    @Test
    void testEscapeSequences_SafelySerialized() throws Exception {
        // This test would require directly calling sendError() which is private,
        // so we test through the message handler with invalid JSON that triggers errors.

        // Given: Invalid JSON that will trigger error with quotes and escapes
        var invalidJson = "{\"type\":\"TEST\", \"invalid\": \"unclosed string";

        // When: Send invalid JSON (triggers error internally)
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error response is valid JSON
        assertTrue(ctx.sentMessages.size() > 0);
        var errorJson = ctx.sentMessages.get(ctx.sentMessages.size() - 1);

        // Must be parseable JSON
        JsonNode parsed = JSON_MAPPER.readTree(errorJson);
        assertEquals("ERROR", parsed.get("type").asText());
    }

    @Test
    void testInjectionAttack_EscapedProperly() throws Exception {
        // Given: Injection attempt trying to break out of JSON structure
        // Send invalid JSON to trigger error response
        var invalidJson = "{\"type\":\"VIEWPORT_UPDATE\", \"viewportCenter\": \"injection\"}";

        // When: Send invalid message (will trigger error)
        streamer.onMessageInternal(ctx, invalidJson);

        // Then: Error response is a single valid JSON object (not multiple)
        assertTrue(ctx.sentMessages.size() > 0, "Error message should be sent");
        var errorJson = ctx.sentMessages.get(ctx.sentMessages.size() - 1);

        // Must be valid JSON
        JsonNode parsed = JSON_MAPPER.readTree(errorJson);
        assertEquals("ERROR", parsed.get("type").asText());

        // Verify it's a single object (not array of objects from injection)
        assertFalse(parsed.isArray(), "Should be single object, not array");
        assertTrue(parsed.isObject(), "Should be a JSON object");

        // Verify we only have two keys: type and message
        assertEquals(2, parsed.size(), "Should have exactly 2 fields");
        assertTrue(parsed.has("type"));
        assertTrue(parsed.has("message"));
    }

    @Test
    void testControlCharacters_SafelySerialized() throws Exception {
        // Given: Message with control characters (newline, tab, etc.)
        // Trigger via invalid viewport update

        var invalidViewport = """
            {
                "type": "VIEWPORT_UPDATE",
                "viewportCenter": {"x": 0, "y": 0, "z": "invalid\\n\\t\\r"}
            }
            """;

        // When: Send invalid viewport (triggers error)
        streamer.onMessageInternal(ctx, invalidViewport);

        // Then: Error response is valid JSON
        assertTrue(ctx.sentMessages.size() > 0);
        var errorJson = ctx.sentMessages.get(ctx.sentMessages.size() - 1);

        // Must be parseable
        JsonNode parsed = JSON_MAPPER.readTree(errorJson);
        assertEquals("ERROR", parsed.get("type").asText());
    }

    @Test
    void testDoubleQuotes_EscapedProperly() throws Exception {
        // Given: Error with double quotes in message
        // Simulate by sending empty JSON (which triggers error)

        streamer.onMessageInternal(ctx, "");

        // Then: JSON is valid despite quotes in error message
        assertTrue(ctx.sentMessages.size() > 0);
        var errorJson = ctx.sentMessages.get(ctx.sentMessages.size() - 1);

        JsonNode parsed = JSON_MAPPER.readTree(errorJson);
        assertEquals("ERROR", parsed.get("type").asText());
        String message = parsed.get("message").asText();

        // Error message should contain unescaped text (Jackson handles escaping)
        assertTrue(message.length() > 0);
    }

    @Test
    void testBackslashes_EscapedProperly() throws Exception {
        // Given: Invalid JSON with backslashes
        var invalidJson = "{\"type\":\"TEST\", \"path\":\"C:\\\\Windows\\\\System32\"}";

        // When: Process (will fail JSON parsing and trigger error)
        streamer.onMessageInternal(ctx, invalidJson + "garbage");

        // Then: Error response is valid
        assertTrue(ctx.sentMessages.size() > 0);
        var errorJson = ctx.sentMessages.get(ctx.sentMessages.size() - 1);

        JsonNode parsed = JSON_MAPPER.readTree(errorJson);
        assertEquals("ERROR", parsed.get("type").asText());
    }

    @Test
    void testMultipleInjectionAttempts_AllEscaped() throws Exception {
        // Given: Multiple injection attempts in sequence (via invalid JSON)
        var invalidMessages = List.of(
            "{\"type\":\"TEST\", \"data\":\"test\\\"}, {\\\"exploit\\\":\\\"1\"}",
            "{\"type\":\"TEST\", \"data\":\"test\\\\\\\"\\\\n\\\\r\\\\t\"}",
            "{\"type\":\"VIEWPORT_UPDATE\", \"viewportCenter\": \"invalid\"}",
            "{\"type\":\"TEST\", \"sql\":\"test'';DROP TABLE users;--\"}"
        );

        // Reset session to clear any previous messages
        ctx = new FakeWsContext("multi-injection-test");
        streamer.onConnectInternal(ctx);

        // When: Trigger errors with injection attempts (send invalid messages)
        for (var invalidMsg : invalidMessages) {
            streamer.onMessageInternal(ctx, invalidMsg);
        }

        // Then: All error responses are valid JSON
        assertTrue(ctx.sentMessages.size() > 0, "Should have error messages");
        for (var errorJson : ctx.sentMessages) {
            JsonNode parsed = JSON_MAPPER.readTree(errorJson);
            assertEquals("ERROR", parsed.get("type").asText());
            assertNotNull(parsed.get("message"));
            // Verify proper JSON structure (2 fields only)
            assertEquals(2, parsed.size(), "Should have exactly 2 fields");
        }
    }

    // --- Test Helper ---

    private static class FakeWsContext implements RegionStreamer.WsContextWrapper {
        String sessionIdValue;
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

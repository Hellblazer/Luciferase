/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.simulation.viz.render.protocol.BinaryFrameCodec;
import com.hellblazer.luciferase.simulation.viz.render.protocol.ProtocolConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end pipeline test proving the complete flow:
 * entity injection → region build → binary frame → WebSocket client.
 * <p>
 * Acceptance criteria (Luciferase-8kx3):
 * <ol>
 *   <li>Start RenderingServer (dynamic port, testing config)</li>
 *   <li>Inject entities into AdaptiveRegionManager</li>
 *   <li>Connect test WebSocket client</li>
 *   <li>Send REGISTER_CLIENT with viewport covering entities</li>
 *   <li>Wait deterministically for region build to complete</li>
 *   <li>Verify binary frame received with correct header via BinaryFrameCodec.decodeHeader()</li>
 *   <li>Verify payload decodable via BinaryFrameCodec.extractPayload()</li>
 * </ol>
 * <p>
 * Key assertions that distinguish version counter from nanosecond duration
 * (verifies the A.4 buildVersion fix is end-to-end correct):
 * <ul>
 *   <li>{@code header.buildVersion() == 1} after exactly one build — a nanosecond
 *       duration would be ≥ 100,000</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class RenderingServerE2ETest {

    // World is 0-1024, regionLevel=2 → 4 per axis → regionSize=256.
    // Entities here map to region [0,0,0] (x,y,z all < 256).
    private static final float ENTITY_X = 64f;
    private static final float ENTITY_Y = 64f;
    private static final float ENTITY_Z = 10f;

    // Camera 30 units above entity z-position. Frustum covers region [0,0,0].
    // Build pipeline always produces LOD 0; distance-based LOD is for prioritization only.
    private static final float EYE_Z = 30f;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TIMEOUT_SECONDS = 10;

    private RenderingServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        server = new RenderingServer(RenderingServerConfig.testing());
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * Full pipeline: entity injection → region build → binary frame → client.
     * <p>
     * This test has no timing-escape hatches: every assertion is hard. If the build
     * does not complete within 5 seconds, or no frame arrives within 10 seconds,
     * the test fails.
     */
    @Test
    void testFullPipeline_entitiesFlowToClient() throws Exception {
        var binaryFrameReceived = new CompletableFuture<ByteBuffer>();

        // Step 1: Connect WebSocket client
        var ws = httpClient.newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws/render"),
                new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        // Clone buffer: Javalin may reuse the underlying buffer
                        var copy = ByteBuffer.allocate(data.remaining());
                        copy.put(data);
                        copy.flip();
                        binaryFrameReceived.complete(copy);
                        return WebSocket.Listener.super.onBinary(webSocket, data, last);
                    }
                })
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Step 2: Send REGISTER_CLIENT with viewport directly above entity region.
        // Eye at (64, 64, 30) looks down at (64, 64, 0); farPlane=500 covers entities at z=10.
        // fovY must be in radians: π/3 ≈ 1.0472 rad = 60 degrees.
        // ClientViewport and Frustum3D both require radians and throw if value >= π.
        var registerMsg = Map.of(
            "type", "REGISTER_CLIENT",
            "clientId", "e2e-pipeline-client",
            "viewport", Map.of(
                "eye",         Map.of("x", ENTITY_X, "y", ENTITY_Y, "z", EYE_Z),
                "lookAt",      Map.of("x", ENTITY_X, "y", ENTITY_Y, "z", 0.0f),
                "up",          Map.of("x", 0.0f, "y", 1.0f, "z", 0.0f),
                "fovY",        (float) (Math.PI / 3.0),   // 60 degrees in radians
                "aspectRatio", 1.333f,
                "nearPlane",   1.0f,
                "farPlane",    500.0f
            )
        );
        ws.sendText(JSON.writeValueAsString(registerMsg), true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Thread.sleep(50); // Allow registration to be processed

        // Step 3: Inject entities into AdaptiveRegionManager
        var regionManager = server.getRegionManager();
        for (int i = 0; i < 5; i++) {
            regionManager.updateEntity("e2e-entity-" + i, ENTITY_X + i, ENTITY_Y + i, ENTITY_Z, "PREY");
        }

        // Step 4: Poll for region build completion (up to 5s).
        // The region becomes dirty on entity injection and is picked up by the
        // backfill retry or the RegionStreamer cache-miss path.
        var buildDeadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < buildDeadline) {
            var cache = server.getRegionCache();
            if (cache != null && cache.getStats().totalCount() > 0) {
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(server.getRegionCache(), "RegionCache must exist after start()");
        assertTrue(server.getRegionCache().getStats().totalCount() > 0,
            "Region build must complete within 5 seconds");

        // Step 5: Wait for the streaming cycle to deliver the binary frame.
        // StreamingConfig.testing() uses 50ms cycles; 10s timeout is generous.
        var frame = binaryFrameReceived.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Step 6: Verify binary frame header fields
        assertNotNull(frame, "Binary frame must be received");
        assertTrue(frame.remaining() >= ProtocolConstants.FRAME_HEADER_SIZE,
            "Frame must be at least " + ProtocolConstants.FRAME_HEADER_SIZE + " bytes, got " + frame.remaining());

        var header = BinaryFrameCodec.decodeHeader(frame);
        assertNotNull(header, "BinaryFrameCodec.decodeHeader() must succeed");

        assertEquals(ProtocolConstants.FRAME_MAGIC, header.magic(),
            "Frame magic must be 0x45535652 (\"ESVR\")");
        assertEquals(ProtocolConstants.FORMAT_ESVO, header.format(),
            "Format must be FORMAT_ESVO (0x01)");
        // keyType=KEY_TYPE_MORTON (0x01) for all octree-based builds.
        assertEquals(ProtocolConstants.KEY_TYPE_MORTON, header.keyType(),
            "keyType must be KEY_TYPE_MORTON (0x01): build pipeline uses MortonKey spatial indexing");
        assertTrue(header.level() >= 0 && header.level() <= 21,
            "Region level must be in [0, 21], got " + header.level());
        assertTrue(header.dataSize() > 0,
            "Payload data size must be > 0");

        // Key A.4 assertion: buildVersion is a monotonic counter, not a nanosecond duration.
        // After exactly one build the counter == 1; a nanosecond duration would be ≥ 100_000.
        assertEquals(1, header.buildVersion(),
            "buildVersion must be 1 (monotonic counter after first build). " +
            "A value >> 1 means encode() is still writing buildTimeNs instead of buildVersion.");

        // Step 7: Verify payload is extractable
        var payload = BinaryFrameCodec.extractPayload(frame);
        assertNotNull(payload, "BinaryFrameCodec.extractPayload() must return non-null");
        assertEquals(header.dataSize(), payload.length,
            "Extracted payload length must match header.dataSize");
        assertTrue(payload.length > 0, "Payload must not be empty");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Test complete");
    }
}

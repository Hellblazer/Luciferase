/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the complete Phase 1 stack:
 * Mock upstream server → EntityStreamConsumer → AdaptiveRegionManager.
 * <p>
 * Validates end-to-end entity streaming, region mapping, and critical fixes C2 and C3.
 *
 * @author hal.hildebrand
 */
class RenderingServerIntegrationTest {

    private RenderingServer renderingServer;
    private Javalin mockUpstream;

    @AfterEach
    void tearDown() {
        if (renderingServer != null) {
            renderingServer.close();
        }
        if (mockUpstream != null) {
            mockUpstream.stop();
        }
    }

    @Test
    void testEndToEndEntityStreamToRegionMapping() throws Exception {
        // Create mock upstream server that sends entity JSON
        mockUpstream = Javalin.create().start(0);
        int upstreamPort = mockUpstream.port();

        mockUpstream.ws("/ws/entities", ws -> {
            ws.onConnect(ctx -> {
                // Send entity update on connection
                var json = """
                    {
                        "entities": [
                            {"id": "e1", "x": 64.0, "y": 64.0, "z": 64.0, "type": "PREY"},
                            {"id": "e2", "x": 128.0, "y": 128.0, "z": 128.0, "type": "PREDATOR"}
                        ],
                        "timestamp": 1707840000000
                    }
                    """;
                ctx.send(json);
            });
        });

        // Create RenderingServer configured to consume from mock upstream
        var upstreams = List.of(
            new UpstreamConfig(
                URI.create("ws://localhost:" + upstreamPort + "/ws/entities"),
                "test-upstream"
            )
        );
        var config = new RenderingServerConfig(
            0, upstreams, 2, 16, 4, 16*1024*1024L, 5_000L, false, 1, SparseStructureType.ESVO
        );
        renderingServer = new RenderingServer(config);
        renderingServer.start();

        // Wait for entity updates to propagate through the pipeline
        Thread.sleep(500);

        // Verify entities reached region manager with upstream prefix
        var regionManager = renderingServer.getRegionManager();
        var allRegions = regionManager.getAllRegions();

        assertFalse(allRegions.isEmpty(), "Should have regions with entities");

        // Find entities in regions
        boolean foundE1 = false;
        boolean foundE2 = false;

        for (var regionId : allRegions) {
            var state = regionManager.getRegionState(regionId);
            if (state != null) {
                for (var entity : state.entities()) {
                    if (entity.id().equals("test-upstream:e1")) {
                        foundE1 = true;
                        assertEquals(64.0f, entity.x(), 0.01f);
                        assertEquals(64.0f, entity.y(), 0.01f);
                        assertEquals(64.0f, entity.z(), 0.01f);
                        assertEquals("PREY", entity.type());
                    }
                    if (entity.id().equals("test-upstream:e2")) {
                        foundE2 = true;
                        assertEquals(128.0f, entity.x(), 0.01f);
                        assertEquals("PREDATOR", entity.type());
                    }
                }
            }
        }

        assertTrue(foundE1, "Entity e1 should be in region manager");
        assertTrue(foundE2, "Entity e2 should be in region manager");
    }

    @Test
    void testCircuitBreakerIntegration_C2() throws Exception {
        // C2 CRITICAL FIX: Circuit breaker end-to-end validation
        // Verify circuit breaker infrastructure is wired correctly
        // (Detailed circuit breaker logic is tested in EntityStreamConsumerTest)

        // Create upstream on invalid port (will always fail)
        var upstreams = List.of(
            new UpstreamConfig(
                URI.create("ws://localhost:99999/ws/entities"),
                "invalid-upstream"
            )
        );
        var config = RenderingServerConfigExtensions.withUpstreams(RenderingServerConfig.testing(), upstreams);
        renderingServer = new RenderingServer(config);
        renderingServer.start();

        // Wait for connection attempt
        Thread.sleep(1000);

        // Verify EntityStreamConsumer is tracking upstream health
        var health = renderingServer.getEntityConsumer()
            .getUpstreamHealth(URI.create("ws://localhost:99999/ws/entities"));

        assertNotNull(health, "Should have health status for upstream");
        assertFalse(health.connected(), "Should not be connected to invalid port");
        assertTrue(health.reconnectAttempts() >= 1,
                   "Should have at least 1 reconnection attempt");

        // Circuit breaker should be functional (detailed testing in EntityStreamConsumerTest)
        // This integration test confirms the wiring is correct
    }

    @Test
    void testRegionBoundaryPrecisionIntegration_C3() throws Exception {
        // C3 CRITICAL FIX: Region boundary epsilon handling end-to-end
        mockUpstream = Javalin.create().start(0);
        int upstreamPort = mockUpstream.port();

        // Send entities near region boundaries
        mockUpstream.ws("/ws/entities", ws -> {
            ws.onConnect(ctx -> {
                // regionSize = 1024 / 4 = 256 (for regionLevel=2)
                // Entity near boundary at x=255.9999 should map to region 1 with epsilon
                var json = """
                    {
                        "entities": [
                            {"id": "boundary1", "x": 255.9999, "y": 10.0, "z": 10.0, "type": "TEST"},
                            {"id": "boundary2", "x": 256.0001, "y": 10.0, "z": 10.0, "type": "TEST"},
                            {"id": "safe", "x": 128.0, "y": 10.0, "z": 10.0, "type": "TEST"}
                        ],
                        "timestamp": 1707840000000
                    }
                    """;
                ctx.send(json);
            });
        });

        var upstreams = List.of(
            new UpstreamConfig(
                URI.create("ws://localhost:" + upstreamPort + "/ws/entities"),
                "boundary-test"
            )
        );
        var config = new RenderingServerConfig(
            0, upstreams, 2, 16, 4, 16*1024*1024L, 5_000L, false, 1, SparseStructureType.ESVO
        );
        renderingServer = new RenderingServer(config);
        renderingServer.start();

        Thread.sleep(500);

        var regionManager = renderingServer.getRegionManager();

        // Verify entities near boundary map correctly with epsilon
        // Both 255.9999 and 256.0001 should map to same region (1) due to epsilon
        var region1 = regionManager.regionForPosition(255.9999f, 10.0f, 10.0f);
        var region2 = regionManager.regionForPosition(256.0001f, 10.0f, 10.0f);

        // Due to epsilon, 255.9999 + epsilon → 256.x → region 1
        // And 256.0001 + epsilon → 256.x → region 1
        // They should map to the same region
        assertEquals(region1, region2,
                    "Entities near boundary should map to same region with epsilon");

        // Safe entity well inside region 0
        var region0 = regionManager.regionForPosition(128.0f, 10.0f, 10.0f);
        assertNotEquals(region0, region1,
                       "Entity well inside region 0 should map differently");
    }

    @Test
    void testMultipleUpstreamsIntegration() throws Exception {
        // Test multiple upstream servers with different entity ID prefixes (M4)
        var upstream1 = Javalin.create().start(0);
        var upstream2 = Javalin.create().start(0);

        upstream1.ws("/ws/entities", ws -> {
            ws.onConnect(ctx -> {
                ctx.send("""
                    {
                        "entities": [
                            {"id": "entity1", "x": 10.0, "y": 10.0, "z": 10.0, "type": "TYPE1"}
                        ],
                        "timestamp": 1707840000000
                    }
                    """);
            });
        });

        upstream2.ws("/ws/entities", ws -> {
            ws.onConnect(ctx -> {
                ctx.send("""
                    {
                        "entities": [
                            {"id": "entity1", "x": 20.0, "y": 20.0, "z": 20.0, "type": "TYPE2"}
                        ],
                        "timestamp": 1707840000000
                    }
                    """);
            });
        });

        var upstreams = List.of(
            new UpstreamConfig(
                URI.create("ws://localhost:" + upstream1.port() + "/ws/entities"),
                "upstream1"
            ),
            new UpstreamConfig(
                URI.create("ws://localhost:" + upstream2.port() + "/ws/entities"),
                "upstream2"
            )
        );

        var config = RenderingServerConfigExtensions.withUpstreams(RenderingServerConfig.testing(), upstreams);
        renderingServer = new RenderingServer(config);
        renderingServer.start();

        Thread.sleep(500);

        var regionManager = renderingServer.getRegionManager();

        // Find both entities with different prefixes
        boolean foundUpstream1Entity = false;
        boolean foundUpstream2Entity = false;

        for (var regionId : regionManager.getAllRegions()) {
            var state = regionManager.getRegionState(regionId);
            if (state != null) {
                for (var entity : state.entities()) {
                    if (entity.id().equals("upstream1:entity1")) {
                        foundUpstream1Entity = true;
                    }
                    if (entity.id().equals("upstream2:entity1")) {
                        foundUpstream2Entity = true;
                    }
                }
            }
        }

        assertTrue(foundUpstream1Entity, "Should have entity from upstream1");
        assertTrue(foundUpstream2Entity, "Should have entity from upstream2");

        upstream1.stop();
        upstream2.stop();
    }
}

/**
 * Extension of RenderingServerConfig to support builder pattern for tests.
 */
class RenderingServerConfigExtensions {
    static RenderingServerConfig withUpstreams(RenderingServerConfig config, List<UpstreamConfig> upstreams) {
        return new RenderingServerConfig(
            config.port(),
            upstreams,
            config.regionLevel(),
            config.gridResolution(),
            config.maxBuildDepth(),
            config.maxCacheMemoryBytes(),
            config.regionTtlMs(),
            config.gpuEnabled(),
            config.gpuPoolSize(),
            config.defaultStructureType()
        );
    }
}

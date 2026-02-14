/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for ViewportTracker basic functionality.
 * <p>
 * Verifies that ViewportTracker compiles, runs, and returns results without exceptions.
 * Comprehensive testing will be added in Day 4.
 *
 * @author hal.hildebrand
 */
class ViewportTrackerSmokeTest {

    @Test
    void testBasicLifecycle() {
        // Create infrastructure
        var config = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(config);
        var streamingConfig = StreamingConfig.testing();
        var tracker = new ViewportTracker(regionManager, streamingConfig);

        // Client lifecycle
        assertEquals(0, tracker.clientCount());

        tracker.registerClient("client1");
        assertEquals(1, tracker.clientCount());

        tracker.removeClient("client1");
        assertEquals(0, tracker.clientCount());
    }

    @Test
    void testViewportUpdate() {
        var config = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(config);
        var streamingConfig = StreamingConfig.testing();
        var tracker = new ViewportTracker(regionManager, streamingConfig);

        tracker.registerClient("client1");

        // Create viewport
        var viewport = new ClientViewport(
            new Point3f(0f, 0f, 0f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float)(Math.PI / 4),
            16f / 9f,
            1f,
            2000f
        );

        // Update viewport - should not throw
        assertDoesNotThrow(() -> tracker.updateViewport("client1", viewport));
    }

    @Test
    void testVisibleRegionsWithEmptyWorld() {
        var config = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(config);
        var streamingConfig = StreamingConfig.testing();
        var tracker = new ViewportTracker(regionManager, streamingConfig);

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(0f, 0f, 0f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float)(Math.PI / 4),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport);

        // Query visible regions - should return empty list (no entities yet)
        var visible = tracker.visibleRegions("client1");
        assertNotNull(visible);
        assertTrue(visible.isEmpty());
    }

    @Test
    void testVisibleRegionsWithEntities() {
        var config = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(config);
        var streamingConfig = StreamingConfig.testing();
        var tracker = new ViewportTracker(regionManager, streamingConfig);

        // Add some entities to populate regions
        regionManager.updateEntity("e1", 128f, 128f, 128f, "PREY");
        regionManager.updateEntity("e2", 256f, 256f, 256f, "PREY");
        regionManager.updateEntity("e3", 384f, 384f, 384f, "PREY");

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(0f, 0f, 0f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float)(Math.PI / 4),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport);

        // Query visible regions
        var visible = tracker.visibleRegions("client1");

        // Should have at least one region visible
        assertNotNull(visible);
        assertTrue(visible.size() > 0, "Expected at least one visible region with entities");

        // Verify each visible region has valid LOD
        for (var vr : visible) {
            assertTrue(vr.lodLevel() >= 0 && vr.lodLevel() <= streamingConfig.maxLodLevel(),
                "LOD level should be in valid range");
            assertTrue(vr.distance() >= 0, "Distance should be non-negative");
        }
    }

    @Test
    void testDiffViewport() {
        var config = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(config);
        var streamingConfig = StreamingConfig.testing();
        var tracker = new ViewportTracker(regionManager, streamingConfig);

        // Add entities
        regionManager.updateEntity("e1", 128f, 128f, 128f, "PREY");

        tracker.registerClient("client1");

        var viewport1 = new ClientViewport(
            new Point3f(0f, 0f, 0f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float)(Math.PI / 4),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport1);

        // First diff should have added regions
        var diff1 = tracker.diffViewport("client1");
        assertNotNull(diff1);

        // Move camera to different location
        var viewport2 = new ClientViewport(
            new Point3f(1000f, 1000f, 1000f),
            new Point3f(0f, 0f, 0f),
            new Vector3f(0f, 1f, 0f),
            (float)(Math.PI / 4),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport2);

        // Second diff should show changes
        var diff2 = tracker.diffViewport("client1");
        assertNotNull(diff2);
    }

    @Test
    void testAllVisibleRegions() {
        var config = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(config);
        var streamingConfig = StreamingConfig.testing();
        var tracker = new ViewportTracker(regionManager, streamingConfig);

        // Add entities
        regionManager.updateEntity("e1", 128f, 128f, 128f, "PREY");

        // Two clients with different viewports
        tracker.registerClient("client1");
        tracker.registerClient("client2");

        var viewport1 = new ClientViewport(
            new Point3f(0f, 0f, 0f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float)(Math.PI / 4),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport1);

        var viewport2 = new ClientViewport(
            new Point3f(512f, 512f, 512f),
            new Point3f(0f, 0f, 0f),
            new Vector3f(0f, 1f, 0f),
            (float)(Math.PI / 4),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client2", viewport2);

        // Populate visible sets
        tracker.visibleRegions("client1");
        tracker.visibleRegions("client2");

        // Get union
        var allVisible = tracker.allVisibleRegions();
        assertNotNull(allVisible);
    }

    @Test
    void testClockInjection() {
        var config = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(config);
        var streamingConfig = StreamingConfig.testing();
        var tracker = new ViewportTracker(regionManager, streamingConfig);

        // Should not throw
        assertDoesNotThrow(() -> tracker.setClock(Clock.fixed(12345L)));
    }

    @Test
    void testVisibleRegionsBeforeViewportUpdate() {
        var config = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(config);
        var streamingConfig = StreamingConfig.testing();
        var tracker = new ViewportTracker(regionManager, streamingConfig);

        tracker.registerClient("client1");

        // Query before viewport update should return empty list
        var visible = tracker.visibleRegions("client1");
        assertNotNull(visible);
        assertTrue(visible.isEmpty());
    }

    @Test
    void testDiffBeforeViewportUpdate() {
        var config = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(config);
        var streamingConfig = StreamingConfig.testing();
        var tracker = new ViewportTracker(regionManager, streamingConfig);

        tracker.registerClient("client1");

        // Diff before viewport update should return empty diff
        var diff = tracker.diffViewport("client1");
        assertNotNull(diff);
        assertTrue(diff.isEmpty());
    }
}

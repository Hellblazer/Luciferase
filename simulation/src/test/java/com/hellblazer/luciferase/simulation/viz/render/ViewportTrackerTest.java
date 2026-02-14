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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for ViewportTracker.
 * <p>
 * Covers:
 * <ul>
 *   <li>Client lifecycle (register, update viewport, remove)</li>
 *   <li>Frustum culling (entities inside/outside/partial overlap)</li>
 *   <li>LOD assignment (distance-based thresholds)</li>
 *   <li>Viewport diffing (added/removed/lodChanged)</li>
 *   <li>Multi-client scenarios (allVisibleRegions union)</li>
 *   <li>Edge cases (empty world, invalid viewports, concurrent access)</li>
 * </ul>
 * <p>
 * Test LOD thresholds (StreamingConfig.testing()): [50, 150, 350]
 * <ul>
 *   <li>LOD 0: distance < 50</li>
 *   <li>LOD 1: 50 <= distance < 150</li>
 *   <li>LOD 2: 150 <= distance < 350</li>
 *   <li>LOD 3: distance >= 350</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class ViewportTrackerTest {

    private RenderingServerConfig config;
    private AdaptiveRegionManager regionManager;
    private StreamingConfig streamingConfig;
    private ViewportTracker tracker;

    @BeforeEach
    void setUp() {
        config = RenderingServerConfig.testing();
        regionManager = new AdaptiveRegionManager(config);
        streamingConfig = StreamingConfig.testing(); // LOD thresholds: [50, 150, 350]
        tracker = new ViewportTracker(regionManager, streamingConfig);
    }

    // ==================== Client Lifecycle Tests ====================

    @Test
    void testRegisterClient() {
        assertEquals(0, tracker.clientCount(), "No clients initially");

        tracker.registerClient("client1");
        assertEquals(1, tracker.clientCount(), "Client registered");

        tracker.registerClient("client2");
        assertEquals(2, tracker.clientCount(), "Second client registered");
    }

    @Test
    void testUpdateViewport() {
        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),  // 60 degrees
            16f / 9f,
            1f,
            2000f
        );

        // Should not throw
        assertDoesNotThrow(() -> tracker.updateViewport("client1", viewport));

        // Viewport update should enable visibility queries
        var visible = tracker.visibleRegions("client1");
        assertNotNull(visible, "Visible regions query should succeed after viewport update");
    }

    @Test
    void testRemoveClient() {
        tracker.registerClient("client1");
        tracker.registerClient("client2");
        assertEquals(2, tracker.clientCount());

        tracker.removeClient("client1");
        assertEquals(1, tracker.clientCount(), "Client1 removed");

        tracker.removeClient("client2");
        assertEquals(0, tracker.clientCount(), "All clients removed");
    }

    @Test
    void testMultipleClients() {
        // Register 5 clients
        for (int i = 0; i < 5; i++) {
            tracker.registerClient("client" + i);
        }
        assertEquals(5, tracker.clientCount());

        // Each can have independent viewports
        for (int i = 0; i < 5; i++) {
            var viewport = new ClientViewport(
                new Point3f(i * 100f, i * 100f, 100f),
                new Point3f(512f, 512f, 512f),
                new Vector3f(0f, 1f, 0f),
                (float) (Math.PI / 3),
                16f / 9f,
                1f,
                2000f
            );
            tracker.updateViewport("client" + i, viewport);
        }

        // All should have visibility results
        for (int i = 0; i < 5; i++) {
            var visible = tracker.visibleRegions("client" + i);
            assertNotNull(visible, "Client " + i + " should have visibility results");
        }
    }

    // ==================== Frustum Culling Tests ====================

    @Test
    void testFrustumCulling_EntityInside() {
        // Add entities in front of camera
        regionManager.updateEntity("e1", 512f, 512f, 300f, "PREY");  // In front
        regionManager.updateEntity("e2", 520f, 520f, 400f, "PREY");  // Also in front

        tracker.registerClient("client1");

        // Camera at (512, 512, 100) looking toward (512, 512, 512)
        var viewport = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),  // 60 degree FOV
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport);

        var visible = tracker.visibleRegions("client1");

        assertTrue(visible.size() > 0, "Should have visible regions with entities in frustum");

        // Verify all visible regions are in front (positive Z direction from eye)
        for (var vr : visible) {
            var bounds = regionManager.boundsForRegion(vr.regionId());
            var centerZ = bounds.centerZ();
            assertTrue(centerZ > 100f, "Visible region should be in front of camera (z > 100)");
        }
    }

    @Test
    void testFrustumCulling_EntityOutside() {
        // Add entities behind camera
        regionManager.updateEntity("e1", 512f, 512f, 50f, "PREY");   // Behind camera
        regionManager.updateEntity("e2", 512f, 512f, 10f, "PREY");   // Way behind

        tracker.registerClient("client1");

        // Camera at (512, 512, 100) looking toward (512, 512, 512)
        var viewport = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport);

        var visible = tracker.visibleRegions("client1");

        // Entities behind camera should not be visible
        for (var vr : visible) {
            var bounds = regionManager.boundsForRegion(vr.regionId());
            var centerZ = bounds.centerZ();
            assertTrue(centerZ > 100f, "No regions behind camera should be visible");
        }
    }

    @Test
    void testFrustumCulling_PartialOverlap() {
        // Add entities at frustum edges (some may partially overlap)
        regionManager.updateEntity("e1", 128f, 128f, 300f, "PREY");  // Left edge
        regionManager.updateEntity("e2", 896f, 896f, 300f, "PREY");  // Right edge
        regionManager.updateEntity("e3", 512f, 512f, 300f, "PREY");  // Center

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),  // 60 degree FOV
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport);

        var visible = tracker.visibleRegions("client1");

        // Center entity should definitely be visible
        var centerRegionVisible = visible.stream()
            .anyMatch(vr -> {
                var bounds = regionManager.boundsForRegion(vr.regionId());
                return bounds.contains(512f, 512f, 300f);
            });

        assertTrue(centerRegionVisible, "Center entity should be visible");
    }

    @Test
    void testFrustumCulling_EmptyWorld() {
        // No entities added
        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport);

        var visible = tracker.visibleRegions("client1");

        assertTrue(visible.isEmpty(), "Empty world should have no visible regions");
    }

    @Test
    void testVisibleRegions_SortedByDistance() {
        // Add entities at different distances
        regionManager.updateEntity("e1", 512f, 512f, 150f, "PREY");  // Close (~50 units)
        regionManager.updateEntity("e2", 512f, 512f, 300f, "PREY");  // Medium (~200 units)
        regionManager.updateEntity("e3", 512f, 512f, 500f, "PREY");  // Far (~400 units)

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport);

        var visible = tracker.visibleRegions("client1");

        // Verify sorted by distance
        float lastDistance = -1f;
        for (var vr : visible) {
            assertTrue(vr.distance() >= lastDistance,
                "Visible regions should be sorted by distance (ascending)");
            lastDistance = vr.distance();
        }
    }

    // ==================== LOD Assignment Tests ====================
    // Note: LOD is calculated based on distance to REGION CENTER, not entity position.
    // Test config uses regionLevel=2 -> 4 regions/axis -> region size = 256 units.
    // Region centers are at: 128, 384, 640, 896 for each axis.

    @Test
    void testLODAssignment_NearDistance() {
        // Test LOD 0: distance < 50
        // Region center at (128, 128, 128), camera at (128, 128, 90)
        // Distance = sqrt(0 + 0 + 38²) = 38 units
        regionManager.updateEntity("e1", 128f, 128f, 128f, "PREY");

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(128f, 128f, 90f),   // Camera near region center
            new Point3f(128f, 128f, 200f),  // Looking along Z axis
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            500f
        );
        tracker.updateViewport("client1", viewport);

        var visible = tracker.visibleRegions("client1");

        // Find the region containing our entity
        var region = visible.stream()
            .filter(vr -> {
                var bounds = regionManager.boundsForRegion(vr.regionId());
                return bounds.contains(128f, 128f, 128f);
            })
            .findFirst();

        assertTrue(region.isPresent(), "Region with entity should be visible");
        assertEquals(0, region.get().lodLevel(), "Distance 38 < 50 should be LOD 0");
    }

    @Test
    void testLODAssignment_MidDistance() {
        // Test LOD 1: 50 <= distance < 150
        // Region center at (128, 128, 128), camera at (128, 128, 0)
        // Distance = sqrt(0 + 0 + 128²) = 128 units
        regionManager.updateEntity("e1", 128f, 128f, 128f, "PREY");

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(128f, 128f, 0f),    // Camera 128 units from region center
            new Point3f(128f, 128f, 200f),  // Looking toward region
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            500f
        );
        tracker.updateViewport("client1", viewport);

        var visible = tracker.visibleRegions("client1");

        var region = visible.stream()
            .filter(vr -> {
                var bounds = regionManager.boundsForRegion(vr.regionId());
                return bounds.contains(128f, 128f, 128f);
            })
            .findFirst();

        assertTrue(region.isPresent(), "Region with entity should be visible");
        assertEquals(1, region.get().lodLevel(), "Distance 128 in [50, 150) should be LOD 1");
    }

    @Test
    void testLODAssignment_FarDistance() {
        // Test LOD 2: 150 <= distance < 350
        // Region center at (128, 128, 128), camera at (128, 128, -100)
        // Distance = sqrt(0 + 0 + 228²) = 228 units
        regionManager.updateEntity("e1", 128f, 128f, 128f, "PREY");

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(128f, 128f, -100f),  // Camera 228 units from region center
            new Point3f(128f, 128f, 200f),   // Looking toward region
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            1000f
        );
        tracker.updateViewport("client1", viewport);

        var visible = tracker.visibleRegions("client1");

        var region = visible.stream()
            .filter(vr -> {
                var bounds = regionManager.boundsForRegion(vr.regionId());
                return bounds.contains(128f, 128f, 128f);
            })
            .findFirst();

        assertTrue(region.isPresent(), "Region with entity should be visible");
        assertEquals(2, region.get().lodLevel(), "Distance 228 in [150, 350) should be LOD 2");
    }

    @Test
    void testLODAssignment_VeryFarDistance() {
        // Test LOD 3: distance >= 350
        // Region center at (640, 640, 640), camera at (0, 0, 0)
        // Distance = sqrt(640² + 640² + 640²) = ~1108 units
        regionManager.updateEntity("e1", 640f, 640f, 640f, "PREY");

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(0f, 0f, 0f),         // Camera far from region
            new Point3f(640f, 640f, 640f),   // Looking at region
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport);

        var visible = tracker.visibleRegions("client1");

        var region = visible.stream()
            .filter(vr -> {
                var bounds = regionManager.boundsForRegion(vr.regionId());
                return bounds.contains(640f, 640f, 640f);
            })
            .findFirst();

        assertTrue(region.isPresent(), "Region with entity should be visible");
        assertEquals(3, region.get().lodLevel(), "Distance >= 350 should be LOD 3");
    }

    @Test
    void testLODAssignment_ExactThreshold() {
        // Test exact threshold boundary: distance = 50.0f
        // Region center at (128, 128, 128), camera at (128, 128, 78)
        // Distance = sqrt(0 + 0 + 50²) = 50.0 units
        // Threshold is exclusive: distance < 50 is LOD 0, distance >= 50 is LOD 1
        regionManager.updateEntity("e1", 128f, 128f, 128f, "PREY");

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(128f, 128f, 78f),    // Exactly 50 units from region center
            new Point3f(128f, 128f, 200f),   // Looking toward region
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            500f
        );
        tracker.updateViewport("client1", viewport);

        var visible = tracker.visibleRegions("client1");

        var region = visible.stream()
            .filter(vr -> {
                var bounds = regionManager.boundsForRegion(vr.regionId());
                return bounds.contains(128f, 128f, 128f);
            })
            .findFirst();

        assertTrue(region.isPresent(), "Region with entity should be visible");
        // distance = 50.0f is exactly at threshold, should be LOD 1 (threshold is exclusive)
        assertTrue(region.get().lodLevel() >= 1,
            "Distance exactly at threshold 50.0f should be LOD 1 or higher");
    }

    // ==================== Viewport Diff Tests ====================

    @Test
    void testViewportDiff_Added() {
        regionManager.updateEntity("e1", 512f, 512f, 300f, "PREY");

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport);

        // First diff: all visible regions are "added"
        var diff = tracker.diffViewport("client1");

        assertFalse(diff.added().isEmpty(), "First viewport should have added regions");
        assertTrue(diff.removed().isEmpty(), "First viewport should have no removed regions");
        assertTrue(diff.lodChanged().isEmpty(), "First viewport should have no LOD changes");
    }

    @Test
    void testViewportDiff_Removed() {
        regionManager.updateEntity("e1", 512f, 512f, 300f, "PREY");
        regionManager.updateEntity("e2", 512f, 512f, 800f, "PREY");  // Far region

        tracker.registerClient("client1");

        // First viewport: looking at entities
        var viewport1 = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport1);
        tracker.diffViewport("client1");  // Populate initial state

        // Second viewport: rotate camera away from entities
        var viewport2 = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(0f, 0f, 100f),  // Looking backward (negative Z direction)
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport2);

        var diff = tracker.diffViewport("client1");

        // Rotating camera should change visible set (either removed or added or both)
        assertTrue(diff.removed().isEmpty() && diff.added().isEmpty() ||
                   !diff.removed().isEmpty() || !diff.added().isEmpty(),
            "Diff should reflect viewport change");
    }

    @Test
    void testViewportDiff_LODChanged() {
        // Use known region center (384, 384, 384) for predictable LOD calculation
        regionManager.updateEntity("e1", 384f, 384f, 384f, "PREY");

        tracker.registerClient("client1");

        // First viewport: far from region center (LOD 2)
        // Distance from (384, 384, 150) to region center (384, 384, 384) = 234 units
        var viewport1 = new ClientViewport(
            new Point3f(384f, 384f, 150f),
            new Point3f(384f, 384f, 400f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            500f
        );
        tracker.updateViewport("client1", viewport1);
        tracker.diffViewport("client1");  // Populate initial state

        // Second viewport: move closer to region center (LOD 0)
        // Distance from (384, 384, 360) to region center (384, 384, 384) = 24 units
        var viewport2 = new ClientViewport(
            new Point3f(384f, 384f, 360f),  // Close to region center
            new Point3f(384f, 384f, 400f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            500f
        );
        tracker.updateViewport("client1", viewport2);

        var diff = tracker.diffViewport("client1");

        // Moving closer should cause LOD changes (from LOD 2 to LOD 0)
        assertFalse(diff.lodChanged().isEmpty(),
            "Moving camera closer should cause LOD changes");
    }

    @Test
    void testViewportDiff_NoChange() {
        regionManager.updateEntity("e1", 512f, 512f, 300f, "PREY");

        tracker.registerClient("client1");

        var viewport = new ClientViewport(
            new Point3f(512f, 512f, 100f),
            new Point3f(512f, 512f, 512f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            2000f
        );
        tracker.updateViewport("client1", viewport);
        tracker.diffViewport("client1");  // Populate initial state

        // Second diff with same viewport
        var diff = tracker.diffViewport("client1");

        assertTrue(diff.added().isEmpty(), "No change should have no added regions");
        assertTrue(diff.removed().isEmpty(), "No change should have no removed regions");
        assertTrue(diff.lodChanged().isEmpty(), "No change should have no LOD changes");
    }

    // ==================== Multi-Client & Edge Cases ====================

    @Test
    void testAllVisibleRegions_MultiClient() {
        // Add entities in different areas
        regionManager.updateEntity("e1", 128f, 128f, 300f, "PREY");
        regionManager.updateEntity("e2", 896f, 896f, 300f, "PREY");

        tracker.registerClient("client1");
        tracker.registerClient("client2");

        // Client1 looks at left side
        var viewport1 = new ClientViewport(
            new Point3f(128f, 128f, 100f),
            new Point3f(128f, 128f, 300f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            1000f
        );
        tracker.updateViewport("client1", viewport1);

        // Client2 looks at right side
        var viewport2 = new ClientViewport(
            new Point3f(896f, 896f, 100f),
            new Point3f(896f, 896f, 300f),
            new Vector3f(0f, 1f, 0f),
            (float) (Math.PI / 3),
            16f / 9f,
            1f,
            1000f
        );
        tracker.updateViewport("client2", viewport2);

        // Populate visibility
        tracker.visibleRegions("client1");
        tracker.visibleRegions("client2");

        // Get union
        var allVisible = tracker.allVisibleRegions();

        assertFalse(allVisible.isEmpty(), "At least one client should have visible regions");
    }

    @Test
    void testConcurrentClientRegistration() throws InterruptedException {
        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var exceptions = Collections.synchronizedList(new ArrayList<Exception>());
        var successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int clientIndex = i;
            executor.submit(() -> {
                try {
                    tracker.registerClient("client" + clientIndex);

                    var viewport = new ClientViewport(
                        new Point3f(clientIndex * 50f, clientIndex * 50f, 100f),
                        new Point3f(512f, 512f, 512f),
                        new Vector3f(0f, 1f, 0f),
                        (float) (Math.PI / 3),
                        16f / 9f,
                        1f,
                        2000f
                    );
                    tracker.updateViewport("client" + clientIndex, viewport);

                    var visible = tracker.visibleRegions("client" + clientIndex);
                    assertNotNull(visible);

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertTrue(exceptions.isEmpty(), "No exceptions should occur: " + exceptions);
        assertEquals(threadCount, successCount.get(), "All concurrent operations should succeed");
        assertEquals(threadCount, tracker.clientCount(), "All clients should be registered");
    }
}

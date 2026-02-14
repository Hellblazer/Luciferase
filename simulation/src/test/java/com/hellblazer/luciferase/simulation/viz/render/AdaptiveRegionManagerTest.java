/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AdaptiveRegionManager with focus on Critical Fix C3 (epsilon boundary handling).
 *
 * @author hal.hildebrand
 */
class AdaptiveRegionManagerTest {

    private AdaptiveRegionManager manager;
    private RenderingServerConfig config;

    @BeforeEach
    void setUp() {
        config = RenderingServerConfig.testing();
        manager = new AdaptiveRegionManager(config);
    }

    @Test
    void testEntityToRegionMapping() {
        // Add entity at origin
        manager.updateEntity("entity1", 0.0f, 0.0f, 0.0f, "TEST");

        var region = manager.regionForPosition(0.0f, 0.0f, 0.0f);
        assertNotNull(region);

        // Verify entity is in the region
        var state = manager.getRegionState(region);
        assertNotNull(state);
        assertEquals(1, state.entities().size());
        assertEquals("entity1", state.entities().get(0).id());
    }

    @Test
    void testRegionBoundaryPrecisionWithEpsilon_C3() {
        // C3 CRITICAL FIX: Epsilon tolerance for region boundaries
        // Configuration: regionLevel=2, worldMin=0.0, worldMax=1024.0
        // regionSize = 1024.0 / 4 = 256.0

        float regionSize = manager.regionSize();
        assertEquals(256.0f, regionSize, 0.001f); // 1024 / 4

        // Test entity well inside region 0 (should stay in region 0)
        float wellInside = regionSize / 2.0f;  // 128.0
        var regionInside = manager.regionForPosition(wellInside, 0.0f, 0.0f);
        int[] coordsInside = MortonCurve.decode(regionInside.mortonCode());
        assertEquals(0, coordsInside[0], "Entity at 128.0 should be in region 0");

        // Test entity near boundary - with epsilon this shifts into region 1
        float nearBoundary = regionSize - 0.0001f;  // 255.9999 + epsilon → region 1
        var region0 = manager.regionForPosition(nearBoundary, 0.0f, 0.0f);
        int[] coords0 = MortonCurve.decode(region0.mortonCode());
        assertEquals(1, coords0[0], "Entity at 255.9999 + epsilon should be in region 1");

        // Test entity just over boundary (should map to region 1)
        float justOver = regionSize + 0.0001f;  // 256.0001
        var region1 = manager.regionForPosition(justOver, 0.0f, 0.0f);
        int[] coords1 = MortonCurve.decode(region1.mortonCode());
        assertEquals(1, coords1[0], "Entity at 256.0001 should be in region 1");

        // Test exact boundary with epsilon (should map to next region)
        float exactBoundary = regionSize;  // 256.0
        var regionBoundary = manager.regionForPosition(exactBoundary, 0.0f, 0.0f);
        int[] coordsBoundary = MortonCurve.decode(regionBoundary.mortonCode());
        assertEquals(1, coordsBoundary[0], "Entity at exact boundary 256.0 + epsilon should be in region 1");

        // Test entity well before boundary (should be in region 0)
        float safeBefore = regionSize - 10.0f;  // 246.0
        var regionSafe = manager.regionForPosition(safeBefore, 0.0f, 0.0f);
        int[] coordsSafe = MortonCurve.decode(regionSafe.mortonCode());
        assertEquals(0, coordsSafe[0], "Entity at 246.0 should be in region 0");
    }

    @Test
    void testEntityCrossingRegionBoundary() {
        float regionSize = manager.regionSize();

        // Place entity in region 0
        manager.updateEntity("moving-entity", regionSize - 10.0f, 0.0f, 0.0f, "TEST");
        var region0 = manager.regionForPosition(regionSize - 10.0f, 0.0f, 0.0f);

        // Verify in region 0
        var state0 = manager.getRegionState(region0);
        assertEquals(1, state0.entities().size());
        assertTrue(state0.dirty().get(), "Region should be dirty after entity add");

        // Clear dirty flag
        state0.dirty().set(false);

        // Move entity across boundary to region 1
        manager.updateEntity("moving-entity", regionSize + 10.0f, 0.0f, 0.0f, "TEST");
        var region1 = manager.regionForPosition(regionSize + 10.0f, 0.0f, 0.0f);

        // Regions should be different
        assertNotEquals(region0, region1);

        // Old region should be dirty and empty
        var stateAfter0 = manager.getRegionState(region0);
        assertTrue(stateAfter0.dirty().get(), "Old region should be marked dirty after entity left");
        assertEquals(0, stateAfter0.entities().size(), "Old region should be empty");

        // New region should be dirty and contain entity
        var stateAfter1 = manager.getRegionState(region1);
        assertTrue(stateAfter1.dirty().get(), "New region should be marked dirty after entity arrived");
        assertEquals(1, stateAfter1.entities().size(), "New region should contain entity");
        assertEquals("moving-entity", stateAfter1.entities().get(0).id());
    }

    @Test
    void testOutOfBoundsClamping() {
        // Entity outside world bounds should be clamped to edge regions
        float worldMax = manager.worldMax();

        manager.updateEntity("out-of-bounds", worldMax + 100.0f, 0.0f, 0.0f, "TEST");

        var region = manager.regionForPosition(worldMax + 100.0f, 0.0f, 0.0f);
        int[] coords = MortonCurve.decode(region.mortonCode());

        // Should clamp to max region index
        int maxRegionIndex = manager.regionsPerAxis() - 1;
        assertEquals(maxRegionIndex, coords[0], "Out-of-bounds entity should clamp to max region");
    }

    @Test
    void testMultipleEntitiesInSameRegion() {
        manager.updateEntity("entity1", 10.0f, 10.0f, 10.0f, "PREY");
        manager.updateEntity("entity2", 20.0f, 10.0f, 10.0f, "PREDATOR");
        manager.updateEntity("entity3", 30.0f, 10.0f, 10.0f, "PREY");

        var region = manager.regionForPosition(10.0f, 10.0f, 10.0f);
        var state = manager.getRegionState(region);

        assertEquals(3, state.entities().size());
        assertTrue(state.dirty().get());
    }

    @Test
    void testRemoveEntity() {
        manager.updateEntity("temp-entity", 50.0f, 50.0f, 50.0f, "TEST");

        var region = manager.regionForPosition(50.0f, 50.0f, 50.0f);
        var stateBefore = manager.getRegionState(region);
        assertEquals(1, stateBefore.entities().size());

        // Clear dirty flag
        stateBefore.dirty().set(false);

        manager.removeEntity("temp-entity");

        var stateAfter = manager.getRegionState(region);
        assertEquals(0, stateAfter.entities().size());
        assertTrue(stateAfter.dirty().get(), "Region should be dirty after entity removal");
    }

    @Test
    void testDirtyRegionTracking() {
        manager.updateEntity("entity1", 10.0f, 10.0f, 10.0f, "TEST");
        manager.updateEntity("entity2", 300.0f, 300.0f, 300.0f, "TEST");

        var dirtyRegions = manager.dirtyRegions();
        assertEquals(2, dirtyRegions.size(), "Two regions should be dirty");
    }

    @Test
    void testClockInjection() {
        var testClock = Clock.fixed(1000L);
        manager.setClock(testClock);

        manager.updateEntity("entity1", 10.0f, 10.0f, 10.0f, "TEST");

        var region = manager.regionForPosition(10.0f, 10.0f, 10.0f);
        var state = manager.getRegionState(region);

        assertEquals(1000L, state.lastModifiedMs(), "Should use injected clock");
    }

    @Test
    void testEntityIdPrefixing_M4() {
        // M4: Multi-upstream entity ID prefixing
        // When entities come from multiple upstreams, IDs are prefixed with upstream label

        manager.updateEntity("upstream1:entity1", 10.0f, 10.0f, 10.0f, "TEST");
        manager.updateEntity("upstream2:entity1", 20.0f, 10.0f, 10.0f, "TEST");

        var region = manager.regionForPosition(10.0f, 10.0f, 10.0f);
        var state = manager.getRegionState(region);

        // Both entities should coexist (different prefixes = different entities)
        assertEquals(2, state.entities().size());

        var ids = state.entities().stream().map(EntityPosition::id).toList();
        assertTrue(ids.contains("upstream1:entity1"));
        assertTrue(ids.contains("upstream2:entity1"));
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        // Test thread-safe concurrent entity updates
        var threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    manager.updateEntity("entity-" + id + "-" + j,
                                         (id * 10.0f) + j,
                                         10.0f,
                                         10.0f,
                                         "TEST");
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // Verify no data corruption - all entities should be tracked
        int totalEntities = 0;
        for (var region : manager.getAllRegions()) {
            var state = manager.getRegionState(region);
            totalEntities += state.entities().size();
        }

        assertEquals(1000, totalEntities, "All 1000 entities should be tracked");
    }

    @Test
    void testUpdateEntity_rejectsNaNCoordinates() {
        var testConfig = RenderingServerConfig.testing();
        var testManager = new AdaptiveRegionManager(testConfig);

        assertThrows(IllegalArgumentException.class,
                () -> testManager.updateEntity("entity1", Float.NaN, 0f, 0f, "test"),
                "Should reject NaN x coordinate");
        assertThrows(IllegalArgumentException.class,
                () -> testManager.updateEntity("entity1", 0f, Float.NaN, 0f, "test"),
                "Should reject NaN y coordinate");
        assertThrows(IllegalArgumentException.class,
                () -> testManager.updateEntity("entity1", 0f, 0f, Float.NaN, "test"),
                "Should reject NaN z coordinate");
    }

    @Test
    void testUpdateEntity_rejectsInfCoordinates() {
        var testConfig = RenderingServerConfig.testing();
        var testManager = new AdaptiveRegionManager(testConfig);

        assertThrows(IllegalArgumentException.class,
                () -> testManager.updateEntity("entity1", Float.POSITIVE_INFINITY, 0f, 0f, "test"),
                "Should reject +Inf x coordinate");
        assertThrows(IllegalArgumentException.class,
                () -> testManager.updateEntity("entity1", Float.NEGATIVE_INFINITY, 0f, 0f, "test"),
                "Should reject -Inf x coordinate");
        assertThrows(IllegalArgumentException.class,
                () -> testManager.updateEntity("entity1", 0f, Float.POSITIVE_INFINITY, 0f, "test"),
                "Should reject +Inf y coordinate");
        assertThrows(IllegalArgumentException.class,
                () -> testManager.updateEntity("entity1", 0f, 0f, Float.NEGATIVE_INFINITY, "test"),
                "Should reject -Inf z coordinate");
    }

    @Test
    void testUpdateEntity_clampsOutOfBoundsCoordinates() {
        var testConfig = RenderingServerConfig.testing();
        var testManager = new AdaptiveRegionManager(testConfig);

        float worldMin = 0f;
        float worldMax = 1024f;
        int maxRegionIndex = 3; // level 2 = 4x4x4 grid

        // Out-of-bounds coordinates should be clamped to world bounds
        testManager.updateEntity("entity1", worldMax + 100f, worldMax + 100f, worldMax + 100f, "test");

        var region = testManager.regionForPosition(worldMax + 100f, worldMax + 100f, worldMax + 100f);
        var coords = MortonCurve.decode(region.mortonCode());

        // Should clamp to max region index
        assertEquals(maxRegionIndex, coords[0], "Out-of-bounds x should clamp to max region");
        assertEquals(maxRegionIndex, coords[1], "Out-of-bounds y should clamp to max region");
        assertEquals(maxRegionIndex, coords[2], "Out-of-bounds z should clamp to max region");

        // Negative coordinates should clamp to min
        testManager.updateEntity("entity2", worldMin - 100f, worldMin - 100f, worldMin - 100f, "test");

        var regionMin = testManager.regionForPosition(worldMin - 100f, worldMin - 100f, worldMin - 100f);
        var coordsMin = MortonCurve.decode(regionMin.mortonCode());

        // Should clamp to min region index (0)
        assertEquals(0, coordsMin[0], "Out-of-bounds negative x should clamp to min region");
        assertEquals(0, coordsMin[1], "Out-of-bounds negative y should clamp to min region");
        assertEquals(0, coordsMin[2], "Out-of-bounds negative z should clamp to min region");
    }

    @Test
    void testUpdateEntity_rejectsNullEntityId() {
        var testConfig = RenderingServerConfig.testing();
        var testManager = new AdaptiveRegionManager(testConfig);

        assertThrows(IllegalArgumentException.class, () -> testManager.updateEntity(null, 0f, 0f, 0f, "test"),
                "Should reject null entity ID");
    }

    @Test
    void testUpdateEntity_rejectsLongEntityId() {
        var testConfig = RenderingServerConfig.testing();
        var testManager = new AdaptiveRegionManager(testConfig);

        // Create 257-character string (exceeds 256 limit)
        var longId = "a".repeat(257);

        assertThrows(IllegalArgumentException.class, () -> testManager.updateEntity(longId, 0f, 0f, 0f, "test"),
                "Should reject entity ID > 256 characters");
    }

    @Test
    void testUpdateEntity_acceptsMaxLengthEntityId() {
        var testConfig = RenderingServerConfig.testing();
        var testManager = new AdaptiveRegionManager(testConfig);

        // Create 256-character string (exactly at limit)
        var maxLengthId = "a".repeat(256);

        // Should NOT throw - exactly at limit is valid
        assertDoesNotThrow(() -> testManager.updateEntity(maxLengthId, 0f, 0f, 0f, "test"),
                "Should accept entity ID exactly 256 characters");

        // Verify entity was actually added
        var entities = testManager.getRegionState(testManager.regionForPosition(0f, 0f, 0f)).entities();
        assertTrue(entities.stream().anyMatch(e -> e.id().equals(maxLengthId)),
                "Entity with max-length ID should be tracked");
    }

    @Test
    void testBackfillDirtyRegions_withBuilder() throws InterruptedException {
        // Test that backfill works correctly with builder wired (xox5)
        var testConfig = RenderingServerConfig.testing();
        var testManager = new AdaptiveRegionManager(testConfig);

        // Create a builder with normal queue
        var builder = new RegionBuilder(1, 50, 4, 16);
        var cache = new RegionCache(16 * 1024 * 1024, java.time.Duration.ofSeconds(30));

        testManager.setBuilder(builder);
        testManager.setCache(cache);

        // Create dirty regions by adding entities
        for (int i = 0; i < 5; i++) {
            testManager.updateEntity("entity" + i, i * 10f, i * 10f, i * 10f, "test");
        }

        // Backfill should process regions without skipping (queue has capacity)
        int skipped = testManager.backfillDirtyRegions();

        // With normal queue capacity, nothing should be skipped
        assertEquals(0, skipped, "Should not skip regions when queue has capacity");

        // Verify queue depth is reasonable
        int queueDepth = builder.getQueueDepth();
        assertTrue(queueDepth >= 0, "Queue depth should be non-negative");
        assertTrue(queueDepth <= 50, "Queue depth should not exceed max");

        // Cleanup
        builder.close();
        cache.close();
    }

    @Test
    void testBackfillDirtyRegions_withoutBuilder() {
        // Test that backfill handles missing builder gracefully
        var testConfig = RenderingServerConfig.testing();
        var testManager = new AdaptiveRegionManager(testConfig);

        // Create dirty regions
        testManager.updateEntity("entity1", 10f, 10f, 10f, "test");

        // Backfill without builder should return 0 (no operations performed)
        int skipped = testManager.backfillDirtyRegions();
        assertEquals(0, skipped, "Should return 0 when builder not wired");
    }

    @Test
    void testUpdateEntity_enforcesEntityLimit() {
        // Test that entity count limit is enforced per region (vtet)
        var testConfig = RenderingServerConfig.testing(); // maxEntitiesPerRegion = 1000
        var testManager = new AdaptiveRegionManager(testConfig);

        // Fill a region to its limit
        for (int i = 0; i < 1000; i++) {
            testManager.updateEntity("entity" + i, 10f, 10f, 10f, "test");
        }

        // Verify region has 1000 entities
        var region = testManager.regionForPosition(10f, 10f, 10f);
        var state = testManager.getRegionState(region);
        assertEquals(1000, state.entities().size(), "Region should have exactly 1000 entities");

        // Attempt to add one more entity to same region should fail
        assertThrows(IllegalStateException.class,
                () -> testManager.updateEntity("entity1000", 10f, 10f, 10f, "test"),
                "Should reject entity when region is at capacity");

        // Updating an existing entity should still work
        assertDoesNotThrow(() -> testManager.updateEntity("entity0", 11f, 10f, 10f, "test"),
                "Should allow updating existing entity even at capacity");

        // Adding to a different region should work
        assertDoesNotThrow(() -> testManager.updateEntity("entity1000", 500f, 500f, 500f, "test"),
                "Should allow adding entity to different region");
    }
}

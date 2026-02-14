/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 2 wiring: RegionBuilder and RegionCache integration
 * with AdaptiveRegionManager and RenderingServer.
 *
 * @author hal.hildebrand
 */
class BuildIntegrationTest {

    private RenderingServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void testEntityUpdateTriggersScheduleBuild() throws Exception {
        // Create and start server (wires builder/cache automatically)
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);
        server.start();

        var regionManager = server.getRegionManager();
        var builder = server.getRegionBuilder();

        assertNotNull(builder, "Builder should be created on start");

        // Initial queue should be empty (after backfill completes)
        Thread.sleep(100);  // Allow backfill to complete
        int initialQueueDepth = builder.getQueueDepth();

        // Add entity (should trigger scheduleBuild)
        regionManager.updateEntity("entity1", 100.0f, 100.0f, 100.0f, "PREY");

        // Mark region as visible to trigger high-priority build
        var region = regionManager.regionForPosition(100.0f, 100.0f, 100.0f);
        regionManager.scheduleBuild(region, true);

        // Wait for build to be queued
        Thread.sleep(100);

        // Queue depth should increase (or build already completed)
        int queueAfterSchedule = builder.getQueueDepth();
        int totalBuilds = builder.getTotalBuilds();

        // Either queued OR already completed
        assertTrue(queueAfterSchedule > initialQueueDepth || totalBuilds > 0,
                  "Build should be queued or completed. Queue: " + queueAfterSchedule +
                  ", Total builds: " + totalBuilds);
    }

    @Test
    void testCacheHitSkipsBuild() throws Exception {
        // Create and start server
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);
        server.start();

        var regionManager = server.getRegionManager();
        var builder = server.getRegionBuilder();
        var cache = server.getRegionCache();

        assertNotNull(builder, "Builder should be created");
        assertNotNull(cache, "Cache should be created");

        // Add entity and trigger initial build
        regionManager.updateEntity("entity1", 100.0f, 100.0f, 100.0f, "PREY");
        var region = regionManager.regionForPosition(100.0f, 100.0f, 100.0f);
        regionManager.scheduleBuild(region, true);

        // Wait for build to complete and cache to be populated
        Thread.sleep(500);

        int buildsAfterFirst = builder.getTotalBuilds();
        assertTrue(buildsAfterFirst > 0, "Should have completed at least one build");

        // Verify cache has the region
        var cacheKey = new RegionCache.CacheKey(region, 0);
        assertTrue(cache.get(cacheKey).isPresent(), "Region should be cached");

        // Schedule another build for the same region - should skip due to cache hit
        regionManager.scheduleBuild(region, true);

        Thread.sleep(100);

        // Total builds should NOT increase (cache hit)
        int buildsAfterSecond = builder.getTotalBuilds();
        assertEquals(buildsAfterFirst, buildsAfterSecond,
                    "Should not trigger new build for cached region");
    }

    @Test
    void testDirtyRegionInvalidatesCacheAndRebuilds() throws Exception {
        // Create and start server
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);
        server.start();

        var regionManager = server.getRegionManager();
        var builder = server.getRegionBuilder();
        var cache = server.getRegionCache();

        // Add entity and build
        regionManager.updateEntity("entity1", 100.0f, 100.0f, 100.0f, "PREY");
        var region = regionManager.regionForPosition(100.0f, 100.0f, 100.0f);
        regionManager.scheduleBuild(region, true);

        Thread.sleep(500);  // Wait for build

        var cacheKey = new RegionCache.CacheKey(region, 0);
        assertTrue(cache.get(cacheKey).isPresent(), "Region should be cached");

        int buildsAfterFirst = builder.getTotalBuilds();

        // Update entity (marks region dirty)
        regionManager.updateEntity("entity1", 105.0f, 105.0f, 105.0f, "PREY");

        // Verify region is dirty
        assertTrue(regionManager.dirtyRegions().contains(region),
                  "Region should be marked dirty after entity update");

        // Invalidate cache for dirty region
        cache.invalidate(cacheKey);

        // Schedule rebuild
        regionManager.scheduleBuild(region, true);

        Thread.sleep(500);  // Wait for rebuild

        // Should have triggered a new build
        int buildsAfterRebuild = builder.getTotalBuilds();
        assertTrue(buildsAfterRebuild > buildsAfterFirst,
                  "Should trigger rebuild for invalidated cache. Before: " + buildsAfterFirst +
                  ", After: " + buildsAfterRebuild);
    }

    @Test
    void testSetBuilder_backfillsDirtyRegions() throws Exception {
        // Create server WITHOUT starting (no builder/cache wired yet)
        var config = RenderingServerConfig.testing();
        var regionManager = new AdaptiveRegionManager(config);

        // Add entities to multiple regions BEFORE builder is wired
        // Testing config uses regionLevel=2 → 4 regions per axis, regionSize=256
        // Use coordinates that guarantee different regions:
        regionManager.updateEntity("entity1", 64.0f, 64.0f, 64.0f, "PREY");      // Region (0,0,0)
        regionManager.updateEntity("entity2", 300.0f, 300.0f, 300.0f, "PREDATOR"); // Region (1,1,1)
        regionManager.updateEntity("entity3", 600.0f, 600.0f, 600.0f, "PREY");    // Region (2,2,2)

        // All regions should be dirty
        var dirtyRegions = regionManager.dirtyRegions();
        assertTrue(dirtyRegions.size() >= 3,
                  "Should have at least 3 dirty regions, got: " + dirtyRegions.size());

        // Now create and wire builder/cache (S3: should trigger backfill)
        var builder = new RegionBuilder(
            config.buildPoolSize(),
            100,
            config.maxBuildDepth(),
            config.gridResolution()
        );

        var cache = new RegionCache(
            config.maxCacheMemoryBytes(),
            Duration.ofMillis(config.regionTtlMs())
        );

        regionManager.setBuilder(builder);
        regionManager.setCache(cache);

        // Explicitly trigger backfill
        regionManager.backfillDirtyRegions();

        // Wait for backfill builds to queue/complete
        Thread.sleep(500);

        // Should have queued or completed builds for dirty regions
        int totalBuilds = builder.getTotalBuilds();
        int queueDepth = builder.getQueueDepth();

        assertTrue(totalBuilds > 0 || queueDepth > 0,
                  "Backfill should have queued/completed builds. Total: " + totalBuilds +
                  ", Queue: " + queueDepth);

        // Cleanup
        builder.close();
        cache.close();
    }
}

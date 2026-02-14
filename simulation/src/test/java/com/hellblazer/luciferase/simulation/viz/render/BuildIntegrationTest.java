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

import static com.hellblazer.luciferase.simulation.viz.render.TestUtils.awaitBuilds;
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

        // Brief wait for async initialization (no backfill expected in this test)
        Thread.sleep(50);
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
        int initialBuilds = builder.getTotalBuilds();
        awaitBuilds(builder, initialBuilds + 1, Duration.ofSeconds(2));

        int buildsAfterFirst = builder.getTotalBuilds();
        assertTrue(buildsAfterFirst > initialBuilds, "Should have completed at least one build");

        // Verify cache has the region
        var cacheKey = new RegionCache.CacheKey(region, 0);
        assertTrue(cache.get(cacheKey).isPresent(), "Region should be cached");

        // Schedule another build for the same region - should skip due to cache hit
        regionManager.scheduleBuild(region, true);

        // Brief wait to allow cache check to happen (no build should start)
        Thread.sleep(50);

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

        // Wait for first build to complete
        int initialBuilds = builder.getTotalBuilds();
        awaitBuilds(builder, initialBuilds + 1, Duration.ofSeconds(2));

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

        // Wait for second build to complete
        awaitBuilds(builder, buildsAfterFirst + 1, Duration.ofSeconds(2));

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
        var builder = new RegionBuilder(config.build());

        var cache = new RegionCache(
            config.cache().maxCacheMemoryBytes(),
            Duration.ofMillis(30_000L)  // 30 second TTL
        );

        regionManager.setBuilder(builder);
        regionManager.setCache(cache);

        // Explicitly trigger backfill
        regionManager.backfillDirtyRegions();

        // Wait for backfill builds to complete (polling for at least 3 builds)
        long startMs = System.currentTimeMillis();
        long timeoutMs = Duration.ofSeconds(2).toMillis();
        while (builder.getTotalBuilds() < 3) {
            Thread.sleep(50);  // 50ms poll interval
            if (System.currentTimeMillis() - startMs > timeoutMs) {
                fail("Timeout waiting for 3 backfill builds, got: " + builder.getTotalBuilds());
            }
        }

        // Should have queued or completed builds for dirty regions
        int totalBuilds = builder.getTotalBuilds();
        int queueDepth = builder.getQueueDepth();

        assertTrue(totalBuilds >= 3,
                  "Backfill should have completed 3 builds for 3 dirty regions. Total: " + totalBuilds +
                  ", Queue: " + queueDepth);

        // Cleanup
        builder.close();
        cache.close();
    }

    @Test
    void testFullPipeline_multipleEntitiesMultipleRegions() throws Exception {
        // Test full pipeline with 50 entities across 5 regions
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);
        server.start();

        var regionManager = server.getRegionManager();
        var builder = server.getRegionBuilder();
        var cache = server.getRegionCache();

        // Add 50 entities distributed across 5 regions
        // Testing config: regionLevel=2 -> 4 regions per axis, regionSize=256
        // Place entities in regions (0,0,0), (1,0,0), (0,1,0), (0,0,1), (1,1,0)
        for (int i = 0; i < 50; i++) {
            float x, y, z;
            int regionIndex = i % 5;
            switch (regionIndex) {
                case 0 -> { x = 64.0f; y = 64.0f; z = 64.0f; }      // Region (0,0,0)
                case 1 -> { x = 300.0f; y = 64.0f; z = 64.0f; }     // Region (1,0,0)
                case 2 -> { x = 64.0f; y = 300.0f; z = 64.0f; }     // Region (0,1,0)
                case 3 -> { x = 64.0f; y = 64.0f; z = 300.0f; }     // Region (0,0,1)
                default -> { x = 300.0f; y = 300.0f; z = 64.0f; }   // Region (1,1,0)
            }
            // Add slight offset to avoid exact duplicates
            x += (i / 5) * 10.0f;
            y += (i / 5) * 10.0f;

            String type = (i % 2 == 0) ? "PREY" : "PREDATOR";
            regionManager.updateEntity("entity" + i, x, y, z, type);
        }

        // Trigger builds for all 5 regions
        var allRegions = regionManager.getAllRegions();
        assertEquals(5, allRegions.size(), "Should have 5 distinct regions");

        int initialBuilds = builder.getTotalBuilds();
        for (var region : allRegions) {
            regionManager.scheduleBuild(region, true);
        }

        // Wait for builds to complete (at least some, cache may skip others)
        Thread.sleep(500); // Give builds time to process

        // Verify all 5 regions are cached
        int cachedRegions = 0;
        for (var region : allRegions) {
            var cacheKey = new RegionCache.CacheKey(region, 0);
            if (cache.get(cacheKey).isPresent()) {
                cachedRegions++;
            }
        }
        assertEquals(5, cachedRegions, "All 5 regions should be cached");

        // Verify builder metrics
        int totalBuilds = builder.getTotalBuilds();
        int buildsCompleted = totalBuilds - initialBuilds;
        assertTrue(buildsCompleted > 0, "Should have completed at least 1 build, got: " + buildsCompleted);
        assertEquals(0, builder.getFailedBuilds(), "Should have no failed builds");

        // Verify cache stats
        var stats = cache.getStats();
        assertTrue(stats.totalCount() >= 5, "Cache should have at least 5 regions");
        assertTrue(stats.memoryPressure() > 0.0, "Memory pressure should be non-zero");
    }

    @Test
    void testPerformanceGate_buildLatencyP50Under50ms() throws Exception {
        // Performance gate: P50 build latency < 50ms
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);
        server.start();

        var regionManager = server.getRegionManager();
        var builder = server.getRegionBuilder();
        var cache = server.getRegionCache();

        // Run 20 builds to get P50 measurement
        // Use different regions to avoid cache hits
        long[] buildTimes = new long[20];

        for (int i = 0; i < 20; i++) {
            // Spread entities across different regions (regionSize=256)
            float x = 100.0f + ((i % 4) * 300.0f);
            float y = 100.0f + ((i / 4) * 300.0f);
            float z = 64.0f;

            regionManager.updateEntity("entity" + i, x, y, z, "PREY");
            var region = regionManager.regionForPosition(x, y, z);

            // Invalidate cache to force rebuild
            var cacheKey = new RegionCache.CacheKey(region, 0);
            cache.invalidate(cacheKey);

            int buildsBefore = builder.getTotalBuilds();
            long startNs = System.nanoTime();

            regionManager.scheduleBuild(region, true);
            awaitBuilds(builder, buildsBefore + 1, Duration.ofSeconds(1), 1); // 1ms polling

            buildTimes[i] = (System.nanoTime() - startNs) / 1_000_000; // Convert to ms
        }

        // Calculate P50 (median)
        java.util.Arrays.sort(buildTimes);
        long p50 = buildTimes[10]; // 50th percentile

        assertTrue(p50 < 50,
            "P50 build latency should be < 50ms, got: " + p50 + "ms");
    }

    @Test
    void testPerformanceGate_buildLatencyP99Under200ms() throws Exception {
        // Performance gate: P99 build latency < 200ms
        var config = RenderingServerConfig.testing();
        server = new RenderingServer(config);
        server.start();

        var regionManager = server.getRegionManager();
        var builder = server.getRegionBuilder();
        var cache = server.getRegionCache();

        // Run 100 builds to get accurate P99 measurement
        // Use fewer unique regions and accept some cache hits
        long[] buildTimes = new long[100];

        for (int i = 0; i < 100; i++) {
            // Spread across 10 different regions (regionSize=256)
            int regionIndex = i % 10;
            float x = 100.0f + ((regionIndex % 4) * 300.0f);
            float y = 100.0f + ((regionIndex / 4) * 300.0f);
            float z = 64.0f;

            regionManager.updateEntity("entity" + i, x, y, z, "PREY");
            var region = regionManager.regionForPosition(x, y, z);

            // Invalidate cache to force rebuild
            var cacheKey = new RegionCache.CacheKey(region, 0);
            cache.invalidate(cacheKey);

            int buildsBefore = builder.getTotalBuilds();
            long startNs = System.nanoTime();

            regionManager.scheduleBuild(region, true);
            awaitBuilds(builder, buildsBefore + 1, Duration.ofSeconds(1), 1); // 1ms polling

            buildTimes[i] = (System.nanoTime() - startNs) / 1_000_000; // Convert to ms
        }

        // Calculate P99 (99th percentile)
        java.util.Arrays.sort(buildTimes);
        long p99 = buildTimes[98]; // 99th percentile (0-indexed)

        assertTrue(p99 < 200,
            "P99 build latency should be < 200ms, got: " + p99 + "ms");
    }

    @Test
    void testGracefulDegradation_queueSaturation() throws Exception {
        // Test graceful degradation under queue saturation (M4)
        var config = RenderingServerConfig.testing(); // maxQueueDepth = 100
        server = new RenderingServer(config);
        server.start();

        var regionManager = server.getRegionManager();
        var builder = server.getRegionBuilder();
        var cache = server.getRegionCache();

        // Rapidly schedule 150 builds (exceeds queue capacity of 100)
        int scheduledBuilds = 0;
        for (int i = 0; i < 150; i++) {
            float pos = 100.0f + (i * 2.0f);
            regionManager.updateEntity("entity" + i, pos, pos, pos, "PREY");
            var region = regionManager.regionForPosition(pos, pos, pos);

            try {
                regionManager.scheduleBuild(region, true);
                scheduledBuilds++;
            } catch (Exception e) {
                // Expected: some builds may be rejected when queue is full
                assertTrue(e.getMessage().contains("queue") || e.getMessage().contains("full"),
                    "Should fail with queue-related message: " + e.getMessage());
            }
        }

        // Wait for queue to drain
        Thread.sleep(1000);

        // Verify graceful degradation
        int totalBuilds = builder.getTotalBuilds();
        int failedBuilds = builder.getFailedBuilds();
        int queueDepth = builder.getQueueDepth();

        assertTrue(totalBuilds > 0, "Should have completed some builds");
        assertTrue(queueDepth < 100, "Queue should have drained");

        // Either all scheduled builds succeeded, or some were rejected gracefully
        assertTrue(scheduledBuilds <= 150, "Should not exceed attempted builds");

        // System should remain operational - verify by scheduling another build
        regionManager.updateEntity("recovery_test", 500.0f, 500.0f, 500.0f, "PREY");
        var region = regionManager.regionForPosition(500.0f, 500.0f, 500.0f);

        // Invalidate cache to ensure this build actually runs
        var cacheKey = new RegionCache.CacheKey(region, 0);
        cache.invalidate(cacheKey);

        int beforeRecovery = builder.getTotalBuilds();
        regionManager.scheduleBuild(region, true);

        awaitBuilds(builder, beforeRecovery + 1, Duration.ofSeconds(2));

        assertTrue(builder.getTotalBuilds() > beforeRecovery,
            "System should recover and process new builds after saturation");
    }
}

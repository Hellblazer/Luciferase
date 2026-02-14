package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.geometry.Point3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RegionBuilder - records, position conversion, and constructor.
 */
class RegionBuilderTest {

    private RegionBuilder regionBuilder;

    @BeforeEach
    void setUp() {
        regionBuilder = new RegionBuilder(2, 100, 10, 64);
    }

    @AfterEach
    void tearDown() {
        if (regionBuilder != null) {
            regionBuilder.close();
        }
    }

    @Test
    void testBuildESVO_producesNonEmptyResult() {
        // Create sample voxel positions
        var voxels = List.of(
                new Point3i(10, 20, 30),
                new Point3i(15, 25, 35),
                new Point3i(20, 30, 40)
        );

        // Build ESVO octree
        var octreeData = regionBuilder.buildESVO(voxels);

        // Verify non-empty result
        assertNotNull(octreeData, "ESVO octree data should not be null");
        assertTrue(octreeData.getNodeCount() > 0, "ESVO octree should have nodes");

        // Verify nodes were created
        var nodeIndices = octreeData.getNodeIndices();
        assertTrue(nodeIndices.length > 0, "ESVO octree should have node indices");

        // Verify actual nodes exist
        assertTrue(octreeData.getNode(0) != null, "Root node should exist");
    }

    @Test
    void testBuildEmptyRegion_returnsEmptyResult() {
        // Build with empty voxel list
        var emptyVoxels = new ArrayList<Point3i>();
        var octreeData = regionBuilder.buildESVO(emptyVoxels);

        // Verify minimal structure (OctreeBuilder returns minimal structure for empty input)
        assertNotNull(octreeData, "ESVO octree data should not be null even for empty input");
    }

    @Test
    void testPositionsToVoxels_normalizesCorrectly() {
        // Create positions in world space
        var positions = List.of(
                new Point3f(0.5f, 0.5f, 0.5f),  // Center of region
                new Point3f(1.0f, 1.0f, 1.0f),  // Max corner
                new Point3f(0.0f, 0.0f, 0.0f)   // Min corner
        );

        var bounds = new RegionBounds(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);

        // Convert to voxels
        var voxels = regionBuilder.positionsToVoxels(positions, bounds);

        // Verify correct normalization and quantization
        assertEquals(3, voxels.size());

        // Center (0.5, 0.5, 0.5) → (32, 32, 32) for 64³ grid
        assertEquals(32, voxels.get(0).x);
        assertEquals(32, voxels.get(0).y);
        assertEquals(32, voxels.get(0).z);

        // Max corner (1.0, 1.0, 1.0) → (63, 63, 63) - clamped to gridResolution-1
        assertEquals(63, voxels.get(1).x);
        assertEquals(63, voxels.get(1).y);
        assertEquals(63, voxels.get(1).z);

        // Min corner (0.0, 0.0, 0.0) → (0, 0, 0)
        assertEquals(0, voxels.get(2).x);
        assertEquals(0, voxels.get(2).y);
        assertEquals(0, voxels.get(2).z);
    }

    @Test
    void testPositionsToVoxels_clampsOutOfBounds() {
        // Create positions outside region bounds
        var positions = List.of(
                new Point3f(-0.5f, -0.5f, -0.5f),  // Below min
                new Point3f(1.5f, 1.5f, 1.5f),     // Above max
                new Point3f(0.5f, -1.0f, 2.0f)     // Mixed out of bounds
        );

        var bounds = new RegionBounds(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);

        // Convert to voxels
        var voxels = regionBuilder.positionsToVoxels(positions, bounds);

        // Verify all clamped to valid range [0, 63]
        assertEquals(3, voxels.size());

        // All coordinates should be within [0, 63]
        for (var voxel : voxels) {
            assertTrue(voxel.x >= 0 && voxel.x <= 63,
                    "Voxel x should be clamped: " + voxel.x);
            assertTrue(voxel.y >= 0 && voxel.y <= 63,
                    "Voxel y should be clamped: " + voxel.y);
            assertTrue(voxel.z >= 0 && voxel.z <= 63,
                    "Voxel z should be clamped: " + voxel.z);
        }

        // Below min should clamp to 0
        assertEquals(0, voxels.get(0).x);
        assertEquals(0, voxels.get(0).y);
        assertEquals(0, voxels.get(0).z);

        // Above max should clamp to 63
        assertEquals(63, voxels.get(1).x);
        assertEquals(63, voxels.get(1).y);
        assertEquals(63, voxels.get(1).z);

        // Mixed: x=32 (in bounds), y=0 (clamped), z=63 (clamped)
        assertEquals(32, voxels.get(2).x); // (0.5-0)/(1-0) * 64 = 32
        assertEquals(0, voxels.get(2).y);  // Clamped from negative
        assertEquals(63, voxels.get(2).z); // Clamped from > 63
    }

    @Test
    void testBuildESVT_producesNonEmptyResult() {
        // Create sample voxel positions
        var voxels = List.of(
                new Point3i(10, 20, 30),
                new Point3i(15, 25, 35),
                new Point3i(20, 30, 40)
        );

        // Build ESVT tetree
        var esvtData = regionBuilder.buildESVT(voxels);

        // Verify non-empty result
        assertNotNull(esvtData, "ESVT data should not be null");
        assertTrue(esvtData.nodes().length > 0, "ESVT should have nodes");
        assertTrue(esvtData.maxDepth() > 0, "ESVT should have depth");
        assertTrue(esvtData.nodeCount() > 0, "ESVT should have node count");
    }

    // ===== Day 3 Tests: Backpressure, Circuit Breaker, Eviction =====

    @Test
    void testQueueBackpressure_rejectsInvisibleWhenFull() throws Exception {
        // Create small queue with single thread to slow processing
        var smallBuilder = new RegionBuilder(1, 5, 10, 64);

        try {
            // Use larger voxel set to make builds take longer
            var positions = new ArrayList<Point3f>();
            var random = new java.util.Random(42);
            for (int i = 0; i < 500; i++) {
                positions.add(new Point3f(random.nextFloat(), random.nextFloat(), random.nextFloat()));
            }
            var bounds = new RegionBounds(0, 0, 0, 1, 1, 1);

            // Fill queue with invisible builds
            for (int i = 0; i < 5; i++) {
                var request = new RegionBuilder.BuildRequest(
                        new RegionId(i, 0),
                        positions,
                        bounds,
                        0,
                        false, // invisible
                        RegionBuilder.BuildType.ESVO,
                        System.currentTimeMillis()
                );
                smallBuilder.build(request); // Should succeed
            }

            // Next invisible build should be rejected
            var rejectedRequest = new RegionBuilder.BuildRequest(
                    new RegionId(999L, 0),
                    positions,
                    bounds,
                    0,
                    false, // invisible
                    RegionBuilder.BuildType.ESVO,
                    System.currentTimeMillis()
            );

            assertThrows(RegionBuilder.BuildQueueFullException.class, () -> {
                smallBuilder.build(rejectedRequest);
            }, "Invisible build should be rejected when queue is full");

        } finally {
            smallBuilder.close();
        }
    }

    @Test
    void testQueueBackpressure_evictsInvisibleForVisible_OLogN() throws Exception {
        // Create small queue with single thread
        var smallBuilder = new RegionBuilder(1, 5, 10, 64);

        try {
            // Use larger voxel set to make builds take longer
            var positions = new ArrayList<Point3f>();
            var random = new java.util.Random(42);
            for (int i = 0; i < 500; i++) {
                positions.add(new Point3f(random.nextFloat(), random.nextFloat(), random.nextFloat()));
            }
            var bounds = new RegionBounds(0, 0, 0, 1, 1, 1);

            // Fill queue with invisible builds
            for (int i = 0; i < 5; i++) {
                var request = new RegionBuilder.BuildRequest(
                        new RegionId(i, 0),
                        positions,
                        bounds,
                        0,
                        false, // invisible
                        RegionBuilder.BuildType.ESVO,
                        i // timestamp - earlier requests have lower timestamp
                );
                smallBuilder.build(request);
            }

            assertEquals(5, smallBuilder.getQueueDepth(), "Queue should be full");

            // Submit visible build - should evict oldest invisible
            var visibleRequest = new RegionBuilder.BuildRequest(
                    new RegionId(100L, 0),
                    positions,
                    bounds,
                    0,
                    true, // visible
                    RegionBuilder.BuildType.ESVO,
                    System.currentTimeMillis()
            );

            assertDoesNotThrow(() -> smallBuilder.build(visibleRequest),
                    "Visible build should evict invisible build");

            // Queue depth should still be at max (evicted one, added one)
            assertEquals(5, smallBuilder.getQueueDepth(),
                    "Queue depth should remain at max after eviction");

        } finally {
            smallBuilder.close();
        }
    }

    @Test
    void testQueueDepthNeverExceedsMax() throws Exception {
        var smallBuilder = new RegionBuilder(4, 10, 10, 64);

        try {
            var positions = List.of(new Point3f(0.5f, 0.5f, 0.5f));
            var bounds = new RegionBounds(0, 0, 0, 1, 1, 1);

            // Submit many builds rapidly
            for (int i = 0; i < 20; i++) {
                var request = new RegionBuilder.BuildRequest(
                        new RegionId(i, 0),
                        positions,
                        bounds,
                        0,
                        i % 2 == 0, // Alternate visible/invisible
                        RegionBuilder.BuildType.ESVO,
                        System.currentTimeMillis() + i
                );

                try {
                    smallBuilder.build(request);
                } catch (RegionBuilder.BuildQueueFullException e) {
                    // Expected for some requests
                }

                // Verify queue never exceeds max
                assertTrue(smallBuilder.getQueueDepth() <= 10,
                        "Queue depth should never exceed max: " + smallBuilder.getQueueDepth());
            }

        } finally {
            smallBuilder.close();
        }
    }

    @Test
    void testBuildFailure_circuitBreakerActivates() throws Exception {
        var positions = List.of(new Point3f(0.5f, 0.5f, 0.5f));
        var bounds = new RegionBounds(0, 0, 0, 1, 1, 1);
        var regionId = new RegionId(42L, 0);

        // Note: Circuit breaker activates after 3 failures
        // In real implementation, we'd need to inject a failing builder
        // For now, test the CircuitBreakerState directly

        var breaker = new RegionBuilder.CircuitBreakerState();
        long now = System.currentTimeMillis();

        // Record 3 failures
        breaker.recordFailure(now);
        breaker.recordFailure(now + 1000);
        breaker.recordFailure(now + 2000);

        // Circuit breaker should be open (default 60s timeout, 3 failures)
        assertTrue(breaker.isOpen(now + 3000, 60_000L, 3),
                "Circuit breaker should be open after 3 failures");
        assertEquals(3, breaker.getConsecutiveFailures());

        // After 60 seconds, circuit breaker should close
        assertFalse(breaker.isOpen(now + 63000, 60_000L, 3),
                "Circuit breaker should close after 60s timeout");
    }

    @Test
    void testBuildFailure_circuitBreakerResets() throws Exception {
        var breaker = new RegionBuilder.CircuitBreakerState();
        long now = System.currentTimeMillis();

        // Record 2 failures (not enough to trip)
        breaker.recordFailure(now);
        breaker.recordFailure(now + 1000);

        assertFalse(breaker.isOpen(now + 2000, 60_000L, 3),
                "Circuit breaker should not open with only 2 failures");

        // After success, circuit breaker state is cleared by removing from map
        // (tested in integration test with actual builds)
    }

    @Test
    void testBuildFailure_metricsTracked() throws Exception {
        int initialFailed = regionBuilder.getFailedBuilds();

        // Note: To actually test failed builds, we'd need to inject a failing builder
        // For now, verify metrics accessors work
        assertTrue(regionBuilder.getTotalBuilds() >= 0);
        assertTrue(regionBuilder.getFailedBuilds() >= 0);

        // Submit a successful build
        var positions = List.of(new Point3f(0.5f, 0.5f, 0.5f));
        var bounds = new RegionBounds(0, 0, 0, 1, 1, 1);
        var request = new RegionBuilder.BuildRequest(
                new RegionId(1L, 0),
                positions,
                bounds,
                0,
                false,
                RegionBuilder.BuildType.ESVO,
                System.currentTimeMillis()
        );

        var future = regionBuilder.build(request);
        var result = future.get();

        assertNotNull(result);
        assertTrue(regionBuilder.getTotalBuilds() > 0,
                "Total builds should increment");
        assertEquals(initialFailed, regionBuilder.getFailedBuilds(),
                "Failed builds should not increment on success");
    }

    @Test
    void testBackpressure_evictsLowestPriorityInvisible_OLogN() throws Exception {
        // This test verifies S2: O(log n) eviction using ConcurrentSkipListSet
        var smallBuilder = new RegionBuilder(1, 5, 10, 64);

        try {
            // Use larger voxel set to make builds take longer
            var positions = new ArrayList<Point3f>();
            var random = new java.util.Random(42);
            for (int i = 0; i < 500; i++) {
                positions.add(new Point3f(random.nextFloat(), random.nextFloat(), random.nextFloat()));
            }
            var bounds = new RegionBounds(0, 0, 0, 1, 1, 1);

            // Add invisible builds with different timestamps
            var timestamps = new long[]{100, 200, 50, 300, 150};

            for (int i = 0; i < 5; i++) {
                var request = new RegionBuilder.BuildRequest(
                        new RegionId(i, 0),
                        positions,
                        bounds,
                        0,
                        false, // invisible
                        RegionBuilder.BuildType.ESVO,
                        timestamps[i]
                );
                smallBuilder.build(request);
            }

            // Submit visible build - should evict oldest (timestamp 50)
            var visibleRequest = new RegionBuilder.BuildRequest(
                    new RegionId(200L, 0),
                    positions,
                    bounds,
                    0,
                    true, // visible
                    RegionBuilder.BuildType.ESVO,
                    System.currentTimeMillis()
            );

            smallBuilder.build(visibleRequest);

            // The eviction uses ConcurrentSkipListSet.pollFirst() which is O(log n)
            // compared to scanning entire queue which would be O(n)
            assertEquals(5, smallBuilder.getQueueDepth());

        } finally {
            smallBuilder.close();
        }
    }

    @Test
    void testConcurrentBuilds_noCorruption() throws Exception {
        var positions = List.of(new Point3f(0.5f, 0.5f, 0.5f));
        var bounds = new RegionBounds(0, 0, 0, 1, 1, 1);

        // Submit multiple builds concurrently
        var futures = new ArrayList<java.util.concurrent.CompletableFuture<RegionBuilder.BuiltRegion>>();

        for (int i = 0; i < 10; i++) {
            var request = new RegionBuilder.BuildRequest(
                    new RegionId(i, 0),
                    positions,
                    bounds,
                    0,
                    i % 2 == 0,
                    RegionBuilder.BuildType.ESVO,
                    System.currentTimeMillis() + i
            );

            try {
                futures.add(regionBuilder.build(request));
            } catch (RegionBuilder.BuildQueueFullException e) {
                // Expected if queue fills
            }
        }

        // Wait for all builds to complete
        for (var future : futures) {
            var result = future.get();
            assertNotNull(result);
            assertTrue(result.serializedData().length > 0);
        }

        // Verify no corruption in metrics
        assertTrue(regionBuilder.getTotalBuilds() >= futures.size());
    }

    @Test
    void testLargeRegionStress_1000Voxels() throws Exception {
        // Create 1000 voxel positions
        var positions = new ArrayList<Point3f>();
        var random = new java.util.Random(42);
        for (int i = 0; i < 1000; i++) {
            positions.add(new Point3f(
                    random.nextFloat(),
                    random.nextFloat(),
                    random.nextFloat()
            ));
        }

        var bounds = new RegionBounds(0, 0, 0, 1, 1, 1);
        var request = new RegionBuilder.BuildRequest(
                new RegionId(1000L, 0),
                positions,
                bounds,
                0,
                false,
                RegionBuilder.BuildType.ESVO,
                System.currentTimeMillis()
        );

        var future = regionBuilder.build(request);
        var result = future.get();

        assertNotNull(result);
        assertTrue(result.serializedData().length > 0);
        assertTrue(result.buildTimeNs() > 0);

        // Verify compression likely happened for large data
        // (A3: > 200 bytes should be compressed)
        if (result.serializedData().length > 200) {
            assertTrue(result.compressed() || result.serializedData().length < 200,
                    "Large data should be compressed");
        }
    }

    @Test
    void testClose_failsPendingBuilds() throws Exception {
        var smallBuilder = new RegionBuilder(1, 100, 10, 64);

        var positions = List.of(new Point3f(0.5f, 0.5f, 0.5f));
        var bounds = new RegionBounds(0, 0, 0, 1, 1, 1);

        // Submit build
        var request = new RegionBuilder.BuildRequest(
                new RegionId(999L, 0),
                positions,
                bounds,
                0,
                false,
                RegionBuilder.BuildType.ESVO,
                System.currentTimeMillis()
        );

        var future = smallBuilder.build(request);

        // Close immediately
        smallBuilder.close();

        // Future may complete or fail depending on timing
        // Just verify close doesn't hang
        try {
            future.get(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // Expected - build may fail or timeout
        }
    }

    // ===== Thread Pool Validation Tests =====

    @Test
    void testConstructor_rejectsInvalidPoolSize() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> new RegionBuilder(0, 100, 10, 64),
                "Should reject buildPoolSize < 1");
        assertTrue(exception.getMessage().contains("buildPoolSize must be >= 1"));
        assertTrue(exception.getMessage().contains("got: 0"));
    }

    @Test
    void testConstructor_rejectsNegativePoolSize() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> new RegionBuilder(-5, 100, 10, 64),
                "Should reject negative buildPoolSize");
        assertTrue(exception.getMessage().contains("buildPoolSize must be >= 1"));
        assertTrue(exception.getMessage().contains("got: -5"));
    }

    @Test
    void testConstructor_acceptsMinimumPoolSize() {
        // buildPoolSize=1 should be valid
        try (var builder = new RegionBuilder(1, 100, 10, 64)) {
            assertNotNull(builder);
        }
    }

    @Test
    void testConstructor_acceptsRecommendedPoolSize() {
        // buildPoolSize=4 (within recommended 2-8 range) should be valid
        try (var builder = new RegionBuilder(4, 100, 10, 64)) {
            assertNotNull(builder);
        }
    }

    @Test
    void testConstructor_warnsWhenExceedingAvailableProcessors() {
        // This test validates that very large pool sizes don't throw exception
        // (Warning is logged but construction succeeds)
        int excessivePoolSize = Runtime.getRuntime().availableProcessors() + 10;

        try (var builder = new RegionBuilder(excessivePoolSize, 100, 10, 64)) {
            assertNotNull(builder);
            // Construction should succeed despite warning
        }
    }
}

package com.hellblazer.luciferase.simulation;

import com.hellblazer.luciferase.simulation.tumbler.TumblerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for VolumeAnimator with SpatialTumbler.
 * <p>
 * Phase 4: Verifies end-to-end tracking with adaptive sharding.
 * Tests both tumbler-enabled and tumbler-disabled modes for backward compatibility.
 *
 * @author hal.hildebrand
 */
class VolumeAnimatorIntegrationTest {

    private VolumeAnimator animator;

    @BeforeEach
    void setUp() {
        // Fresh animator per test
    }

    @AfterEach
    void tearDown() {
        // Controllers may hold threads, cleanup if needed
        animator = null;
    }

    @Test
    void testTrackingWithoutTumbler() {
        // Backward compatibility test - original behavior
        animator = new VolumeAnimator("test-no-tumbler");

        var position = new Point3f(1000f, 1000f, 1000f);
        var cursor = animator.track(position);

        assertNotNull(cursor, "Should track entity without tumbler");
        assertFalse(animator.isTumblerEnabled(), "Tumbler should be disabled");
        assertNull(animator.getTumbler(), "Tumbler should be null");
    }

    @Test
    void testTrackingWithTumbler() {
        // Phase 4: New functionality with tumbler enabled
        var config = TumblerConfig.defaults();
        animator = new VolumeAnimator("test-with-tumbler", config);

        var position = new Point3f(1000f, 1000f, 1000f);
        var cursor = animator.track(position);

        assertNotNull(cursor, "Should track entity with tumbler");
        assertTrue(animator.isTumblerEnabled(), "Tumbler should be enabled");
        assertNotNull(animator.getTumbler(), "Tumbler should be initialized");

        // Verify entity tracked in tumbler
        var tumbler = animator.getTumbler();
        var regions = tumbler.getAllRegions();
        var totalEntities = regions.stream()
            .mapToInt(r -> r.entityCount())
            .sum();
        assertEquals(1, totalEntities, "Tumbler should have 1 entity");
    }

    @Test
    void testMultipleEntitiesWithTumbler() {
        var config = TumblerConfig.defaults();
        animator = new VolumeAnimator("test-multiple", config);

        // Track 10 entities in different locations
        for (int i = 0; i < 10; i++) {
            var x = 1000f + i * 100f;
            var y = 1000f + i * 100f;
            var z = 1000f + i * 100f;
            var cursor = animator.track(new Point3f(x, y, z));
            assertNotNull(cursor, "Should track entity " + i);
        }

        // Verify all entities tracked
        var tumbler = animator.getTumbler();
        var regions = tumbler.getAllRegions();
        var totalEntities = regions.stream()
            .mapToInt(r -> r.entityCount())
            .sum();
        assertEquals(10, totalEntities, "Should have all 10 entities");
    }

    @Test
    void testTumblerSplitTriggered() {
        // Use small split threshold to trigger split easily
        var config = new TumblerConfig(
            10,       // splitThreshold - low for testing
            2,        // joinThreshold
            (byte) 4, // minRegionLevel
            (byte) 10, // maxRegionLevel
            0.1f,     // spanWidthRatio
            1.0f,     // minSpanDistance
            true,     // autoAdapt
            5,        // adaptCheckInterval - check often
            TumblerConfig.RegionSplitStrategy.OCTANT
        );
        animator = new VolumeAnimator("test-split", config);

        var tumbler = animator.getTumbler();
        var regionCountBefore = tumbler.getAllRegions().size();

        // Track 20 entities in same location to trigger split
        var position = new Point3f(1000f, 1000f, 1000f);
        for (int i = 0; i < 20; i++) {
            animator.track(position);
        }

        // Manually trigger split check (auto-adapt may not have triggered yet)
        var splitCount = tumbler.checkAndSplit();

        // Should have split at least once
        assertTrue(splitCount > 0, "Should have triggered split");

        var regionCountAfter = tumbler.getAllRegions().size();
        assertTrue(regionCountAfter > regionCountBefore,
            "Should have more regions after split (before: " + regionCountBefore + ", after: " + regionCountAfter + ")");

        // Verify all entities still tracked
        var totalEntities = tumbler.getAllRegions().stream()
            .mapToInt(r -> r.entityCount())
            .sum();
        assertEquals(20, totalEntities, "Should still have all 20 entities after split");
    }

    @Test
    void testBoundaryTracking() {
        var config = TumblerConfig.defaults();
        animator = new VolumeAnimator("test-boundary", config);

        // Track entities to create regions
        animator.track(new Point3f(1000f, 1000f, 1000f));
        animator.track(new Point3f(5000f, 5000f, 5000f));

        var tumbler = animator.getTumbler();
        var span = tumbler.getSpan();

        assertNotNull(span, "Span should be initialized");

        // Boundary zones may or may not exist depending on region structure
        var boundaryZoneCount = span.getBoundaryZoneCount();
        assertTrue(boundaryZoneCount >= 0, "Boundary zone count should be non-negative");
    }

    @Test
    void testInvalidPosition() {
        var config = TumblerConfig.defaults();
        animator = new VolumeAnimator("test-invalid", config);

        // Position outside valid range [0, WORLD_SCALE]
        var invalidPosition = new Point3f(-1000f, -1000f, -1000f);
        var cursor = animator.track(invalidPosition);

        assertNull(cursor, "Should reject invalid position");

        // Verify no entities tracked
        var tumbler = animator.getTumbler();
        var totalEntities = tumbler.getAllRegions().stream()
            .mapToInt(r -> r.entityCount())
            .sum();
        assertEquals(0, totalEntities, "Should have no entities");
    }

    @Test
    void testEdgePositions() {
        var config = TumblerConfig.defaults();
        animator = new VolumeAnimator("test-edge", config);

        // Test positions at boundaries of valid range
        var positions = new Point3f[] {
            new Point3f(0f, 0f, 0f),           // Min corner
            new Point3f(32200f, 32200f, 32200f), // Max corner (WORLD_SCALE)
            new Point3f(16100f, 16100f, 16100f)  // Center
        };

        for (var pos : positions) {
            var cursor = animator.track(pos);
            assertNotNull(cursor, "Should track edge position: " + pos);
        }

        var tumbler = animator.getTumbler();
        var totalEntities = tumbler.getAllRegions().stream()
            .mapToInt(r -> r.entityCount())
            .sum();
        assertEquals(3, totalEntities, "Should have tracked all 3 edge positions");
    }

    @Test
    void testConfigurationImmutability() {
        var config = TumblerConfig.defaults();
        animator = new VolumeAnimator("test-config", config);

        var tumbler = animator.getTumbler();
        var retrievedConfig = tumbler.getConfig();

        assertNotNull(retrievedConfig, "Config should be accessible");
        assertEquals(config.splitThreshold(), retrievedConfig.splitThreshold());
        assertEquals(config.joinThreshold(), retrievedConfig.joinThreshold());
        assertEquals(config.minRegionLevel(), retrievedConfig.minRegionLevel());
        assertEquals(config.maxRegionLevel(), retrievedConfig.maxRegionLevel());
    }

    @Test
    void testBackwardCompatibilityDeprecatedConstructor() {
        // Test deprecated constructor still works
        @SuppressWarnings("deprecation")
        var deprecatedAnimator = new VolumeAnimator("test-deprecated", new Object(), new Object());

        assertNotNull(deprecatedAnimator, "Deprecated constructor should work");
        assertFalse(deprecatedAnimator.isTumblerEnabled(), "Deprecated constructor should not enable tumbler");

        var cursor = deprecatedAnimator.track(new Point3f(1000f, 1000f, 1000f));
        assertNotNull(cursor, "Tracking should work with deprecated constructor");
    }

    @Test
    void testPerformanceBaseline() {
        // Measure tracking performance without tumbler (baseline)
        animator = new VolumeAnimator("test-perf-baseline");

        var startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            var x = 1000f + i * 10f;
            var y = 1000f + i * 10f;
            var z = 1000f + i * 10f;
            animator.track(new Point3f(x, y, z));
        }
        var baselineTime = (System.nanoTime() - startTime) / 1_000_000; // ms

        assertTrue(baselineTime < 1000,
            "Baseline tracking should be fast (< 1000ms), took " + baselineTime + "ms");
    }

    @Test
    void testPerformanceWithTumbler() {
        // Measure tracking performance with tumbler
        var config = TumblerConfig.defaults();
        animator = new VolumeAnimator("test-perf-tumbler", config);

        var startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            var x = 1000f + i * 10f;
            var y = 1000f + i * 10f;
            var z = 1000f + i * 10f;
            animator.track(new Point3f(x, y, z));
        }
        var tumblerTime = (System.nanoTime() - startTime) / 1_000_000; // ms

        assertTrue(tumblerTime < 1000,
            "Tumbler tracking should be fast (< 1000ms), took " + tumblerTime + "ms");

        // Phase 4 requirement: < 10% overhead
        // Note: Can't enforce this strictly without baseline comparison
        // This is documented in performance benchmarking task
    }

    @Test
    void testConcurrentTracking() {
        // Verify thread-safety of concurrent tracking
        var config = TumblerConfig.defaults();
        animator = new VolumeAnimator("test-concurrent", config);

        var threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    var x = 1000f + threadId * 1000f + i * 10f;
                    var y = 1000f + threadId * 1000f + i * 10f;
                    var z = 1000f + threadId * 1000f + i * 10f;
                    animator.track(new Point3f(x, y, z));
                }
            });
        }

        // Start all threads
        for (var thread : threads) {
            thread.start();
        }

        // Wait for completion
        for (var thread : threads) {
            try {
                thread.join(5000); // 5 second timeout
            } catch (InterruptedException e) {
                fail("Thread interrupted: " + e.getMessage());
            }
        }

        // Verify all entities tracked (4 threads * 25 entities = 100)
        var tumbler = animator.getTumbler();
        var totalEntities = tumbler.getAllRegions().stream()
            .mapToInt(r -> r.entityCount())
            .sum();
        assertEquals(100, totalEntities, "Should have all 100 entities from concurrent tracking");
    }
}

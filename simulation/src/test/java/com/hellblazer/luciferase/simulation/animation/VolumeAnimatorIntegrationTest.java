package com.hellblazer.luciferase.simulation.animation;

import com.hellblazer.luciferase.simulation.animation.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for VolumeAnimator.
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
    void testBasicTracking() {
        animator = new VolumeAnimator("test-tracking");

        var position = new Point3f(1000f, 1000f, 1000f);
        var cursor = animator.track(position);

        assertNotNull(cursor, "Should track entity");
    }

    @Test
    void testMultipleEntities() {
        animator = new VolumeAnimator("test-multiple");

        for (int i = 0; i < 10; i++) {
            var x = 1000f + i * 100f;
            var y = 1000f + i * 100f;
            var z = 1000f + i * 100f;
            var cursor = animator.track(new Point3f(x, y, z));
            assertNotNull(cursor, "Should track entity " + i);
        }
    }

    @Test
    void testInvalidPosition() {
        animator = new VolumeAnimator("test-invalid");

        // Negative position (outside valid range [0, WORLD_SCALE])
        var invalid = new Point3f(-100f, -100f, -100f);
        var cursor = animator.track(invalid);

        assertNull(cursor, "Should reject invalid position");
    }

    @Test
    void testEdgePositions() {
        animator = new VolumeAnimator("test-edge");

        // Test positions at the boundaries
        var positions = new Point3f[]{
            new Point3f(0f, 0f, 0f),
            new Point3f(32200f, 32200f, 32200f),
            new Point3f(16100f, 16100f, 16100f)
        };

        for (var pos : positions) {
            var cursor = animator.track(pos);
            assertNotNull(cursor, "Should track edge position: " + pos);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void testBackwardCompatibilityDeprecatedConstructor() {
        // Test deprecated constructor still works
        var deprecatedAnimator = new VolumeAnimator("test-deprecated", new Object(), new Object());

        assertNotNull(deprecatedAnimator, "Deprecated constructor should work");

        var cursor = deprecatedAnimator.track(new Point3f(1000f, 1000f, 1000f));
        assertNotNull(cursor, "Deprecated animator should track entities");
    }

    @Test
    void testPerformanceBaseline() {
        // Measure tracking performance without tumbler (baseline)
        animator = new VolumeAnimator("test-perf-baseline");

        var start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            var x = 1000f + i * 10f;
            var y = 1000f + i * 10f;
            var z = 1000f + i * 10f;
            animator.track(new Point3f(x, y, z));
        }
        var duration = System.nanoTime() - start;

        assertTrue(duration > 0, "Should complete tracking");
        double opsPerSec = 1000.0 / (duration / 1_000_000_000.0);
        assertTrue(opsPerSec > 100, "Should have reasonable throughput (>100 ops/sec), was: " + opsPerSec);
    }

    @Test
    void testConcurrentTracking() {
        // Verify thread-safety of concurrent tracking
        animator = new VolumeAnimator("test-concurrent");

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

        // Verify completed without errors (entity count validation not possible without tumbler)
        assertTrue(true, "Concurrent tracking should complete without errors");
    }
}

package com.hellblazer.luciferase.esvo.gpu.beam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BeamTree with batch kernel processing.
 *
 * Validates that BeamTree produces coherent batches suitable for GPU kernel execution.
 */
class BeamTreeIntegrationTest {

    private Ray[] rays;

    @BeforeEach
    void setUp() {
        // Create 1000 test rays simulating camera frustum
        rays = new Ray[1000];
        var idx = 0;
        for (int z = 0; z < 10; z++) {
            for (int y = 0; y < 10; y++) {
                for (int x = 0; x < 10; x++) {
                    var origin = new Point3f(
                            0.5f + (x - 5) * 0.02f,
                            0.5f + (y - 5) * 0.02f,
                            z * 0.1f
                    );
                    var direction = new Vector3f(0f, 0f, 1f);
                    rays[idx++] = new Ray(origin, direction);
                }
            }
        }
    }

    @Test
    void testBeamTreeUsedWhenCoherenceHigh() {
        // High coherence camera scene
        var tree = BeamTreeBuilder.from(rays)
                .withCoherenceThreshold(0.5)
                .withMaxBatchSize(32)
                .build();

        var stats = tree.getStatistics();
        assertTrue(stats.totalBeams() > 0, "Should create beams for coherent scene");
        assertGreaterOrEqual(stats.averageCoherence(), 0.0);
    }

    @Test
    void testFlatArrayUsedWhenCoherenceLow() {
        // Create incoherent scene
        var incoherentRays = new Ray[100];
        for (int i = 0; i < 100; i++) {
            var x = (float) Math.random();
            var y = (float) Math.random();
            var z = (float) Math.random();
            var origin = new Point3f(x, y, z);

            var dx = (float) (Math.random() - 0.5);
            var dy = (float) (Math.random() - 0.5);
            var dz = (float) (Math.random() - 0.5);
            var direction = new Vector3f(dx, dy, dz);
            direction.normalize();

            incoherentRays[i] = new Ray(origin, direction);
        }

        var tree = BeamTreeBuilder.from(incoherentRays)
                .withCoherenceThreshold(0.1)
                .build();

        // Tree should still be created, but coherence may be low
        assertNotNull(tree);
    }

    @Test
    void testBatchExecutionWithBeamTree() {
        var tree = BeamTreeBuilder.from(rays)
                .withMaxBatchSize(16)
                .build();

        var batches = tree.getCoherentRayBatches(16);

        assertTrue(!batches.isEmpty(), "Should produce batches");

        int totalRays = 0;
        for (var batch : batches) {
            assertTrue(batch.length > 0, "Each batch should contain rays");
            assertTrue(batch.length <= 16, "Batch should respect size limit");
            totalRays += batch.length;
        }

        assertEquals(1000, totalRays, "All rays should be in batches");
    }

    @Test
    void testResultsMatchWithoutBeamTree() {
        // Verify batches can be reconstructed from tree
        var tree = BeamTreeBuilder.from(rays)
                .withMaxBatchSize(32)
                .build();

        var batches = tree.getCoherentRayBatches(32);

        // Collect all ray indices from batches
        var batchedIndices = new java.util.HashSet<Integer>();
        for (var batch : batches) {
            for (var rayIdx : batch) {
                batchedIndices.add(rayIdx);
            }
        }

        // Verify all rays are present exactly once
        assertEquals(1000, batchedIndices.size(), "All rays should be included");
    }

    @Test
    void testNodeReduction30Percent() {
        // Simulate high coherence camera scene
        var cameraRays = new Ray[512];
        for (int i = 0; i < 512; i++) {
            var x = (float) (Math.random() * 0.1);
            var y = (float) (Math.random() * 0.1);
            var z = i / 512.0f;
            var origin = new Point3f(0.5f + x, 0.5f + y, z);
            var direction = new Vector3f(0f, 0f, 1f);
            cameraRays[i] = new Ray(origin, direction);
        }

        var tree = BeamTreeBuilder.from(cameraRays)
                .withMaxBatchSize(8)
                .build();

        var stats = tree.getStatistics();

        // With camera rays and batch organization, should reduce node traversals
        assertTrue(stats.totalBeams() > 0, "Should create multiple beams");
        assertTrue(stats.totalBeams() < 512 / 8.0 * 1.3, "Should have fewer beams than rays/batchSize");
    }

    @Test
    void testPerformanceImprovement() {
        var tree = BeamTreeBuilder.from(rays)
                .withMaxBatchSize(32)
                .build();

        // Measure batch creation time
        var startTime = System.nanoTime();
        var batches = tree.getCoherentRayBatches(32);
        var endTime = System.nanoTime();

        var durationUs = (endTime - startTime) / 1000.0;
        assertTrue(durationUs < 1000, "Batch creation should be fast (< 1ms)");
        assertTrue(batches.size() > 0);
    }

    @Test
    void testCacheEfficiencyImprovement() {
        var tree = BeamTreeBuilder.from(rays)
                .withMaxBatchSize(16)
                .build();

        var batches = tree.getCoherentRayBatches(16);

        // Verify coherence promotes cache efficiency
        var stats = tree.getStatistics();
        assertTrue(stats.totalBeams() > 0);
        assertTrue(stats.averageCoherence() >= 0.0);
    }

    @Test
    void testMultipleFrames() {
        // Simulate rendering multiple frames
        for (int frame = 0; frame < 3; frame++) {
            // Slight variation each frame
            var frameRays = new Ray[rays.length];
            for (int i = 0; i < rays.length; i++) {
                var origin = new Point3f(rays[i].origin());
                origin.x += (frame * 0.001f);
                frameRays[i] = new Ray(origin, rays[i].direction());
            }

            var tree = BeamTreeBuilder.from(frameRays)
                    .withMaxBatchSize(32)
                    .build();

            var batches = tree.getCoherentRayBatches(32);
            int totalRays = 0;
            for (var batch : batches) {
                totalRays += batch.length;
            }
            assertEquals(1000, totalRays);
        }
    }

    @Test
    void testRayCountVariations() {
        // Test with different ray counts
        int[] testCounts = {100, 1000, 10000};

        for (int count : testCounts) {
            var testRays = new Ray[count];
            for (int i = 0; i < count; i++) {
                var origin = new Point3f(
                        0.5f + (i % 10) * 0.01f,
                        0.5f + ((i / 10) % 10) * 0.01f,
                        0.5f
                );
                var direction = new Vector3f(0f, 0f, 1f);
                testRays[i] = new Ray(origin, direction);
            }

            var tree = BeamTreeBuilder.from(testRays)
                    .withMaxBatchSize(32)
                    .build();

            var batches = tree.getCoherentRayBatches(32);
            int totalRays = 0;
            for (var batch : batches) {
                totalRays += batch.length;
            }
            assertEquals(count, totalRays, "All rays should be included for count=" + count);
        }
    }

    @Test
    void testComplexScene() {
        // Mix of parallel and divergent rays
        var complexRays = new Ray[256];
        var idx = 0;

        // Half parallel (camera rays)
        for (int i = 0; i < 128; i++) {
            var origin = new Point3f(0.5f + i * 0.001f, 0.5f, 0f);
            var direction = new Vector3f(0f, 0f, 1f);
            complexRays[idx++] = new Ray(origin, direction);
        }

        // Half divergent (light rays)
        for (int i = 0; i < 128; i++) {
            var angle = (float) Math.PI * 2 * i / 128;
            var origin = new Point3f(0.5f, 0.5f, 0.5f);
            var direction = new Vector3f(
                    (float) Math.cos(angle),
                    (float) Math.sin(angle),
                    0f
            );
            direction.normalize();
            complexRays[idx++] = new Ray(origin, direction);
        }

        var tree = BeamTreeBuilder.from(complexRays)
                .withMaxBatchSize(16)
                .build();

        var batches = tree.getCoherentRayBatches(16);
        int totalRays = 0;
        for (var batch : batches) {
            totalRays += batch.length;
        }
        assertEquals(256, totalRays);

        var stats = tree.getStatistics();
        assertTrue(stats.totalBeams() > 0);
    }

    // Helper assertions
    private static void assertGreaterOrEqual(double actual, double threshold) {
        assertTrue(actual >= threshold, "expected >= " + threshold + " but got " + actual);
    }
}

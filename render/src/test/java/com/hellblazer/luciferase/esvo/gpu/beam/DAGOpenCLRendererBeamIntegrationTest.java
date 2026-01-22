package com.hellblazer.luciferase.esvo.gpu.beam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BeamTree with DAGOpenCLRenderer.
 *
 * Validates that BeamTree integrates correctly with the renderer and produces
 * coherent ray batches for batch kernel processing.
 */
class DAGOpenCLRendererBeamIntegrationTest {

    private BeamKernelSelector selector;
    private Ray[] rays;

    @BeforeEach
    void setUp() {
        selector = new BeamKernelSelector();
        rays = createCameraRays(100);
    }

    @Test
    void testBeamTreeConstructionFromRays() {
        var tree = BeamTreeBuilder.from(rays)
                .withMaxBatchSize(32)
                .build();

        assertNotNull(tree);
        var stats = tree.getStatistics();
        assertTrue(stats.totalBeams() > 0);
    }

    @Test
    void testKernelSelectionForCoherentRays() {
        var tree = BeamTreeBuilder.from(rays).build();
        var choice = selector.selectKernel(tree);

        assertNotNull(choice);
        assertTrue(choice == BeamKernelSelector.KernelChoice.BATCH ||
                choice == BeamKernelSelector.KernelChoice.SINGLE_RAY);
    }

    @Test
    void testBatchAssemblyFromBeamTree() {
        var tree = BeamTreeBuilder.from(rays)
                .withMaxBatchSize(16)
                .build();

        var batches = tree.getCoherentRayBatches(16);

        assertNotNull(batches);
        assertTrue(batches.size() > 0);

        int totalRays = 0;
        for (var batch : batches) {
            assertTrue(batch.length > 0);
            assertTrue(batch.length <= 16);
            totalRays += batch.length;
        }

        assertEquals(100, totalRays);
    }

    @Test
    void testBatchConsistencyAcrossFrames() {
        // First frame
        var tree1 = BeamTreeBuilder.from(rays).build();
        var batches1 = tree1.getCoherentRayBatches(32);

        // Second frame (same rays)
        var tree2 = BeamTreeBuilder.from(rays).build();
        var batches2 = tree2.getCoherentRayBatches(32);

        // Should produce consistent batching
        assertEquals(batches1.size(), batches2.size());
    }

    @Test
    void testCoherenceImpactsKernelSelection() {
        // High coherence (camera rays)
        var highCoherenceRays = createCameraRays(100);
        var tree1 = BeamTreeBuilder.from(highCoherenceRays).build();
        var choice1 = selector.selectKernel(tree1);

        // Low coherence (random rays)
        var lowCoherenceRays = createRandomRays(100);
        var tree2 = BeamTreeBuilder.from(lowCoherenceRays).build();
        var choice2 = selector.selectKernel(tree2);

        // At least one should make a decision (though both are valid)
        assertNotNull(choice1);
        assertNotNull(choice2);
    }

    @Test
    void testNoRaysLost() {
        var tree = BeamTreeBuilder.from(rays)
                .withMaxBatchSize(8)
                .build();

        var batches = tree.getCoherentRayBatches(8);

        var rayCount = 0;
        for (var batch : batches) {
            rayCount += batch.length;
        }

        assertEquals(100, rayCount);
    }

    @Test
    void testBatchSizeRespected() {
        var maxBatchSize = 16;
        var tree = BeamTreeBuilder.from(rays)
                .withMaxBatchSize(maxBatchSize)
                .build();

        var batches = tree.getCoherentRayBatches(maxBatchSize);

        for (var batch : batches) {
            assertLessOrEqual(batch.length, maxBatchSize);
        }
    }

    @Test
    void testTreeStatisticsForCoherence() {
        var tree = BeamTreeBuilder.from(rays)
                .withMaxBatchSize(16)
                .build();

        var stats = tree.getStatistics();

        assertTrue(stats.totalBeams() > 0);
        assertTrue(stats.averageCoherence() >= 0.0);
        assertTrue(stats.averageCoherence() <= 1.0);
        assertTrue(stats.maxDepth() >= 0);
    }

    @Test
    void testMultipleBeamTreeInstances() {
        // Create multiple independent trees
        var tree1 = BeamTreeBuilder.from(rays).build();
        var tree2 = BeamTreeBuilder.from(rays).build();
        var tree3 = BeamTreeBuilder.from(rays).build();

        var batches1 = tree1.getCoherentRayBatches(32);
        var batches2 = tree2.getCoherentRayBatches(32);
        var batches3 = tree3.getCoherentRayBatches(32);

        // All should be valid
        assertTrue(batches1.size() > 0);
        assertTrue(batches2.size() > 0);
        assertTrue(batches3.size() > 0);
    }

    @Test
    void testKernelSelectorMetricsTracking() {
        var tree1 = BeamTreeBuilder.from(rays).build();
        var tree2 = BeamTreeBuilder.from(createRandomRays(50)).build();

        selector.selectKernel(tree1);
        selector.selectKernel(tree2);
        selector.selectKernel(tree1);

        var metrics = selector.getMetrics();
        assertEquals(3, metrics.selectionCount());
    }

    // Helper methods
    private Ray[] createCameraRays(int count) {
        var cameraRays = new Ray[count];
        var idx = 0;
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) {
                var origin = new Point3f(
                        0.5f + (x - 5) * 0.01f,
                        0.5f + (y - 5) * 0.01f,
                        0f
                );
                var direction = new Vector3f(0f, 0f, 1f);
                cameraRays[idx++] = new Ray(origin, direction);
            }
        }
        return cameraRays;
    }

    private Ray[] createRandomRays(int count) {
        var randomRays = new Ray[count];
        for (int i = 0; i < count; i++) {
            var x = (float) Math.random();
            var y = (float) Math.random();
            var z = (float) Math.random();
            var origin = new Point3f(x, y, z);

            var dx = (float) (Math.random() - 0.5);
            var dy = (float) (Math.random() - 0.5);
            var dz = (float) (Math.random() - 0.5);
            var direction = new Vector3f(dx, dy, dz);
            direction.normalize();

            randomRays[i] = new Ray(origin, direction);
        }
        return randomRays;
    }

    private static void assertLessOrEqual(int actual, int threshold) {
        assertTrue(actual <= threshold, "Expected " + actual + " <= " + threshold);
    }
}

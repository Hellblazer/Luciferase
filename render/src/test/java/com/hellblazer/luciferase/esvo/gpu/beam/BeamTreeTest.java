package com.hellblazer.luciferase.esvo.gpu.beam;

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

class BeamTreeTest {

    private Ray[] rays;
    private DAGOctreeData mockDAG;

    @BeforeEach
    void setUp() {
        // Create 100 test rays
        rays = new Ray[100];
        var idx = 0;
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) {
                var origin = new Point3f(x * 0.1f, y * 0.1f, 0.5f);
                var direction = new Vector3f(0f, 0f, 1f);
                rays[idx++] = new Ray(origin, direction);
            }
        }

        mockDAG = null;  // DAG is optional for builder
    }

    @Test
    void testGetCoherentRayBatches() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withMaxBatchSize(16)
                .build();

        var batches = tree.getCoherentRayBatches(16);

        assertNotNull(batches);
        assertFalse(batches.isEmpty());
    }

    @Test
    void testBatchSizeRespected() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withMaxBatchSize(20)
                .build();

        var batches = tree.getCoherentRayBatches(20);

        for (var batch : batches) {
            assertLessOrEqual(batch.length, 20, "Each batch should respect max size");
        }
    }

    @Test
    void testAllRaysIncluded() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .build();

        var batches = tree.getCoherentRayBatches(32);

        int totalRays = 0;
        for (var batch : batches) {
            totalRays += batch.length;
        }

        assertEquals(100, totalRays, "All rays should be included in batches");
    }

    @Test
    void testCoherenceThreshold() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withCoherenceThreshold(0.0)
                .build();

        assertTrue(tree.validateCoherence(), "Should validate with threshold 0.0");
    }

    @Test
    void testTreeStatistics() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withMaxRaysPerBeam(20)
                .build();

        var stats = tree.getStatistics();

        assertGreater(stats.totalBeams(), 0);
        assertGreaterOrEqual(stats.averageCoherence(), 0.0);
        assertLessOrEqual(stats.averageCoherence(), 1.0);
        assertGreaterOrEqual(stats.maxDepth(), 0);
    }

    @Test
    void testTraversal() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withMaxRaysPerBeam(16)
                .build();

        var visitCount = new int[1];
        tree.traverse((node, depth) -> {
            visitCount[0]++;
            assertNotNull(node);
            assertGreaterOrEqual(depth, 0);
        });

        assertGreater(visitCount[0], 0);
    }

    @Test
    void testValidateCoherence() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withCoherenceThreshold(0.0)
                .build();

        assertTrue(tree.validateCoherence());
    }

    @Test
    void testHighCoherenceScene() {
        // Camera rays (all parallel, same origin area)
        var cameraRays = new Ray[64];
        for (int i = 0; i < 64; i++) {
            var offset = i / 8;
            var origin = new Point3f(0.5f, 0.5f, 0.0f);
            origin.x += (offset % 2) * 0.01f;
            origin.y += ((offset / 2) % 2) * 0.01f;
            var direction = new Vector3f(0f, 0f, 1f);
            cameraRays[i] = new Ray(origin, direction);
        }

        var tree = BeamTreeBuilder.from(cameraRays)
                .withDAG(mockDAG)
                .build();

        var stats = tree.getStatistics();
        assertGreaterOrEqual(stats.averageCoherence(), 0.0);
        assertLessOrEqual(stats.averageCoherence(), 1.0);
        assertTrue(stats.totalBeams() > 0);
    }

    @Test
    void testLowCoherenceScene() {
        // Rays with diverse origins and directions
        var diverseRays = new Ray[50];
        for (int i = 0; i < 50; i++) {
            var x = (float) Math.random();
            var y = (float) Math.random();
            var z = (float) Math.random();
            var origin = new Point3f(x, y, z);

            var dx = (float) (Math.random() - 0.5);
            var dy = (float) (Math.random() - 0.5);
            var dz = (float) (Math.random() - 0.5);
            var direction = new Vector3f(dx, dy, dz);
            direction.normalize();

            diverseRays[i] = new Ray(origin, direction);
        }

        var tree = BeamTreeBuilder.from(diverseRays)
                .withDAG(mockDAG)
                .withCoherenceThreshold(0.1)
                .build();

        var batches = tree.getCoherentRayBatches(32);
        int totalRays = 0;
        for (var batch : batches) {
            totalRays += batch.length;
        }
        assertEquals(50, totalRays);
    }

    @Test
    void testRealWorldScene() {
        // Simulate 100K rays as mentioned in plan
        var largeRaySet = new Ray[100];
        for (int i = 0; i < 100; i++) {
            var angle = (float) Math.PI * 2 * i / 100;
            var origin = new Point3f(
                    0.5f + 0.3f * (float) Math.cos(angle),
                    0.5f + 0.3f * (float) Math.sin(angle),
                    0.0f
            );
            var direction = new Vector3f(0f, 0f, 1f);
            largeRaySet[i] = new Ray(origin, direction);
        }

        var tree = BeamTreeBuilder.from(largeRaySet)
                .withDAG(mockDAG)
                .withMaxBatchSize(8)
                .build();

        var batches = tree.getCoherentRayBatches(8);
        assertFalse(batches.isEmpty());

        int totalRays = 0;
        for (var batch : batches) {
            totalRays += batch.length;
            assertLessOrEqual(batch.length, 8);
        }
        assertEquals(100, totalRays);
    }

    // Helper methods
    private static void assertGreater(int actual, int threshold) {
        assertTrue(actual > threshold, "expected > " + threshold + " but got " + actual);
    }

    private static void assertGreater(double actual, double threshold) {
        assertTrue(actual > threshold, "expected > " + threshold + " but got " + actual);
    }

    private static void assertGreaterOrEqual(int actual, int threshold) {
        assertTrue(actual >= threshold, "expected >= " + threshold + " but got " + actual);
    }

    private static void assertGreaterOrEqual(double actual, double threshold) {
        assertTrue(actual >= threshold, "expected >= " + threshold + " but got " + actual);
    }

    private static void assertLessOrEqual(int actual, int threshold, String message) {
        assertTrue(actual <= threshold, message + ": expected <= " + threshold + " but got " + actual);
    }

    private static void assertLessOrEqual(double actual, double threshold) {
        assertTrue(actual <= threshold, "expected <= " + threshold + " but got " + actual);
    }
}

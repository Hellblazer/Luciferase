package com.hellblazer.luciferase.esvo.gpu.beam;

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

class BeamTreeBuilderTest {

    private Ray[] rays;
    private DAGOctreeData mockDAG;

    @BeforeEach
    void setUp() {
        // Create 100 test rays in a grid pattern
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
    void testBuilderPattern() {
        var builder = BeamTreeBuilder.from(rays)
                .withCoherenceThreshold(0.5)
                .withMaxBatchSize(32)
                .withSubdivisionStrategy(SubdivisionStrategy.OCTREE);

        assertNotNull(builder);
    }

    @Test
    void testSpatialPartition() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withSubdivisionStrategy(SubdivisionStrategy.OCTREE)
                .withMaxRaysPerBeam(10)
                .build();

        assertNotNull(tree);
        var stats = tree.getStatistics();
        assertGreater(stats.totalBeams(), 1, "Should have multiple beams");
    }

    @Test
    void testCoherenceAnalysis() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withCoherenceThreshold(0.5)
                .build();

        var stats = tree.getStatistics();
        assertGreaterThanOrEqual(stats.averageCoherence(), 0.0);
        assertLessThanOrEqual(stats.averageCoherence(), 1.0);
    }

    @Test
    void testBatchAssembly() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withMaxBatchSize(16)
                .build();

        var batches = tree.getCoherentRayBatches(16);

        assertNotNull(batches);
        assertFalse(batches.isEmpty(), "Should have at least one batch");

        // Verify all batches respect size limit
        for (var batch : batches) {
            assertLessOrEqual(batch.length, 16, "Batch should respect max size");
        }
    }

    @Test
    void testValidation() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withCoherenceThreshold(0.0)  // Accept any coherence
                .build();

        assertTrue(tree.validateCoherence(), "Tree should validate with threshold 0.0");
    }

    @Test
    void testWithPreAnalysis() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withPreAnalysis(true)
                .build();

        assertNotNull(tree);
        var stats = tree.getStatistics();
        assertGreater(stats.totalBeams(), 0);
    }

    @Test
    void testAdaptiveBatchSize() {
        var tree = BeamTreeBuilder.from(rays)
                .withDAG(mockDAG)
                .withMaxBatchSize(8)
                .withMaxRaysPerBeam(16)
                .build();

        var batches = tree.getCoherentRayBatches(8);

        int totalRays = 0;
        for (var batch : batches) {
            totalRays += batch.length;
            assertLessOrEqual(batch.length, 8);
        }
        assertEquals(100, totalRays, "All rays should be included in batches");
    }

    @Test
    void testEmptyRayArray() {
        var emptyRays = new Ray[0];
        assertThrows(IllegalArgumentException.class, () ->
                BeamTreeBuilder.from(emptyRays).build()
        );
    }

    @Test
    void testSingleRay() {
        var singleRay = new Ray[]{rays[0]};
        var tree = BeamTreeBuilder.from(singleRay)
                .withDAG(mockDAG)
                .build();

        var batches = tree.getCoherentRayBatches(64);
        assertEquals(1, batches.size());
        assertEquals(1, batches.get(0).length);
    }

    @Test
    void testLowCoherenceScene() {
        // Create rays with diverse directions
        var diverseRays = new Ray[20];
        for (int i = 0; i < 20; i++) {
            var angle = (float) Math.PI * 2 * i / 20;
            var origin = new Point3f(0.5f, 0.5f, 0.5f);
            var direction = new Vector3f(
                    (float) Math.cos(angle),
                    (float) Math.sin(angle),
                    0f
            );
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
        assertEquals(20, totalRays);
    }

    // Helper methods for assertions
    private static void assertGreater(int actual, int expected) {
        assertTrue(actual > expected, "expected > " + expected + " but got " + actual);
    }

    private static void assertGreater(int actual, int expected, String message) {
        assertTrue(actual > expected, message + ": expected > " + expected + " but got " + actual);
    }

    private static void assertGreaterThanOrEqual(double actual, double expected) {
        assertTrue(actual >= expected, "expected >= " + expected + " but got " + actual);
    }

    private static void assertLessThanOrEqual(double actual, double expected) {
        assertTrue(actual <= expected, "expected <= " + expected + " but got " + actual);
    }

    private static void assertLessOrEqual(int actual, int expected) {
        assertTrue(actual <= expected, "expected <= " + expected + " but got " + actual);
    }

    private static void assertLessOrEqual(int actual, int expected, String message) {
        assertTrue(actual <= expected, message + ": expected <= " + expected + " but got " + actual);
    }
}

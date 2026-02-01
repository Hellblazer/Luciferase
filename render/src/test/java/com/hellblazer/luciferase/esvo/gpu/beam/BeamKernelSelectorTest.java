package com.hellblazer.luciferase.esvo.gpu.beam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BeamKernelSelector - kernel selection logic based on ray coherence.
 *
 * Validates that the selector correctly chooses between single-ray and batch kernels
 * based on coherence metrics from BeamTree.
 */
class BeamKernelSelectorTest {

    private BeamKernelSelector selector;
    private Ray[] rays;

    @BeforeEach
    void setUp() {
        selector = new BeamKernelSelector();
        rays = createTestRays(100);
    }

    @Test
    void testSelectHighCoherence() {
        // High coherence scene (parallel camera rays)
        var coherentRays = new Ray[100];
        for (int i = 0; i < 100; i++) {
            var origin = new Point3f(0.5f + (i % 10) * 0.01f, 0.5f + (i / 10) * 0.01f, 0f);
            var direction = new Vector3f(0f, 0f, 1f);
            coherentRays[i] = new Ray(origin, direction);
        }

        var tree = BeamTreeBuilder.from(coherentRays).build();
        var choice = selector.selectKernel(tree);

        assertEquals(BeamKernelSelector.KernelChoice.BATCH, choice);
    }

    @Test
    void testSelectLowCoherence() {
        // Low coherence scene (uniformly distributed incoherent rays)
        var incoherentRays = createIncoherentRays(100);

        var tree = BeamTreeBuilder.from(incoherentRays).build();
        var choice = selector.selectKernel(tree);

        assertEquals(BeamKernelSelector.KernelChoice.SINGLE_RAY, choice);
    }

    @Test
    void testSelectBoundaryCoherence() {
        // Build tree with rays at coherence boundary
        var tree = BeamTreeBuilder.from(rays).build();
        var choice = selector.selectKernel(tree);

        // Should make a valid choice (either is acceptable)
        assertNotNull(choice);
        assertTrue(choice == BeamKernelSelector.KernelChoice.BATCH ||
                choice == BeamKernelSelector.KernelChoice.SINGLE_RAY);
    }

    @Test
    void testNullTree() {
        var choice = selector.selectKernel(null);
        assertEquals(BeamKernelSelector.KernelChoice.SINGLE_RAY, choice);
    }

    @Test
    void testEmptyTree() {
        var emptyRays = new Ray[1];
        emptyRays[0] = new Ray(new Point3f(0, 0, 0), new Vector3f(0, 0, 1));

        var tree = BeamTreeBuilder.from(emptyRays).build();
        var choice = selector.selectKernel(tree);

        assertNotNull(choice);
    }

    @Test
    void testMetricsTracking() {
        var coherentRays = createCoherentRays(100);
        var tree = BeamTreeBuilder.from(coherentRays).build();

        selector.selectKernel(tree);
        selector.selectKernel(tree);
        selector.selectKernel(tree);

        var metrics = selector.getMetrics();
        assertEquals(3, metrics.selectionCount());
        assertEquals(3, metrics.batchSelections());
        assertEquals(0, metrics.singleRaySelections());
    }

    @Test
    void testMetricsMixedSelections() {
        var coherentRays = createCoherentRays(100);
        var incoherentRays = createIncoherentRays(100);

        var tree1 = BeamTreeBuilder.from(coherentRays).build();
        var tree2 = BeamTreeBuilder.from(incoherentRays).build();

        selector.selectKernel(tree1);
        selector.selectKernel(tree2);
        selector.selectKernel(tree1);

        var metrics = selector.getMetrics();
        assertEquals(3, metrics.selectionCount());
        assertTrue(metrics.batchSelections() >= 1);
        assertTrue(metrics.singleRaySelections() >= 1);
    }

    @Test
    void testMetricsReset() {
        var tree = BeamTreeBuilder.from(rays).build();
        selector.selectKernel(tree);
        selector.selectKernel(tree);

        var metricsBefore = selector.getMetrics();
        assertTrue(metricsBefore.selectionCount() > 0);

        selector.resetMetrics();

        var metricsAfter = selector.getMetrics();
        assertEquals(0, metricsAfter.selectionCount());
    }

    @Test
    void testCoherenceThresholdConfiguration() {
        var selector2 = new BeamKernelSelector(0.5);  // Custom threshold

        var rays50 = new Ray[100];
        for (int i = 0; i < 100; i++) {
            var origin = new Point3f(0.5f, 0.5f, 0.5f);
            var dx = (float) Math.cos(i * 0.063f);
            var dy = (float) Math.sin(i * 0.063f);
            var direction = new Vector3f(dx, dy, 0);
            direction.normalize();
            rays50[i] = new Ray(origin, direction);
        }

        var tree = BeamTreeBuilder.from(rays50).build();
        var choice = selector2.selectKernel(tree);

        assertNotNull(choice);
    }

    @Test
    void testMultipleSelectionsConsistent() {
        var tree = BeamTreeBuilder.from(rays).build();

        var choice1 = selector.selectKernel(tree);
        var choice2 = selector.selectKernel(tree);
        var choice3 = selector.selectKernel(tree);

        assertEquals(choice1, choice2);
        assertEquals(choice2, choice3);
    }

    // Helper methods
    private Ray[] createTestRays(int count) {
        var testRays = new Ray[count];
        for (int i = 0; i < count; i++) {
            var origin = new Point3f(i % 10 * 0.1f, i / 10 * 0.1f, 0f);
            var direction = new Vector3f(0f, 0f, 1f);
            testRays[i] = new Ray(origin, direction);
        }
        return testRays;
    }

    private Ray[] createCoherentRays(int count) {
        var coherentRays = new Ray[count];
        for (int i = 0; i < count; i++) {
            var origin = new Point3f(0.5f + (i % 10) * 0.01f, 0.5f + (i / 10) * 0.01f, 0f);
            var direction = new Vector3f(0f, 0f, 1f);
            coherentRays[i] = new Ray(origin, direction);
        }
        return coherentRays;
    }

    private Ray[] createIncoherentRays(int count) {
        var incoherentRays = new Ray[count];

        // Use golden ratio for uniform sphere distribution (Fibonacci lattice)
        // This ensures deterministic, uniformly distributed directions that
        // produce average |cos(θ)| ≈ 0.5 between any two directions
        var goldenRatio = (1 + Math.sqrt(5)) / 2;
        var goldenAngle = 2 * Math.PI / (goldenRatio * goldenRatio);

        for (int i = 0; i < count; i++) {
            // Spread origins across unit cube (deterministic pattern)
            var x = ((i * 7 + 3) % 100) * 0.01f;
            var y = ((i * 13 + 5) % 100) * 0.01f;
            var z = ((i * 19 + 7) % 100) * 0.01f;
            var origin = new Point3f(x, y, z);

            // Fibonacci lattice for uniform sphere distribution
            var theta = goldenAngle * i;
            var phi = Math.acos(1 - 2.0 * (i + 0.5) / count);
            var dx = (float) (Math.sin(phi) * Math.cos(theta));
            var dy = (float) (Math.sin(phi) * Math.sin(theta));
            var dz = (float) Math.cos(phi);
            var direction = new Vector3f(dx, dy, dz);
            direction.normalize();

            incoherentRays[i] = new Ray(origin, direction);
        }
        return incoherentRays;
    }
}

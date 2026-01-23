package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DispatchMetrics record.
 * Validates factory methods, percentage calculation, and boundary conditions.
 */
class DispatchMetricsTest {

    @Test
    void testEmptyFactory() {
        var empty = DispatchMetrics.empty();

        assertEquals(0, empty.totalDispatches());
        assertEquals(0, empty.batchDispatches());
        assertEquals(0, empty.singleRayDispatches());
        assertEquals(0.0, empty.batchPercentage(), 0.001);
    }

    @Test
    void testFromFactory() {
        var metrics = DispatchMetrics.from(100, 75, 25);

        assertEquals(100, metrics.totalDispatches());
        assertEquals(75, metrics.batchDispatches());
        assertEquals(25, metrics.singleRayDispatches());
        assertEquals(75.0, metrics.batchPercentage(), 0.001);
    }

    @Test
    void testFromFactoryZeroTotal() {
        var metrics = DispatchMetrics.from(0, 0, 0);

        assertEquals(0, metrics.totalDispatches());
        assertEquals(0.0, metrics.batchPercentage(), 0.001);
    }

    @Test
    void testPercentageCalculation() {
        // 50/50 split
        var half = DispatchMetrics.from(100, 50, 50);
        assertEquals(50.0, half.batchPercentage(), 0.001);

        // All batch
        var allBatch = DispatchMetrics.from(100, 100, 0);
        assertEquals(100.0, allBatch.batchPercentage(), 0.001);

        // No batch
        var noBatch = DispatchMetrics.from(100, 0, 100);
        assertEquals(0.0, noBatch.batchPercentage(), 0.001);

        // Precision test
        var precise = DispatchMetrics.from(333, 111, 222);
        assertEquals(33.333, precise.batchPercentage(), 0.01);
    }

    @Test
    void testNegativeValuesRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new DispatchMetrics(-1, 0, 0, 0.0)
        );

        assertThrows(IllegalArgumentException.class, () ->
            new DispatchMetrics(100, -1, 0, 0.0)
        );

        assertThrows(IllegalArgumentException.class, () ->
            new DispatchMetrics(100, 0, -1, 0.0)
        );
    }

    @Test
    void testSumConsistency() {
        // batch + singleRay should equal total
        assertThrows(IllegalArgumentException.class, () ->
            new DispatchMetrics(100, 60, 50, 60.0)  // 60 + 50 = 110 > 100
        );

        assertThrows(IllegalArgumentException.class, () ->
            new DispatchMetrics(100, 30, 30, 30.0)  // 30 + 30 = 60 < 100
        );

        // Valid: sum equals total
        var valid = new DispatchMetrics(100, 60, 40, 60.0);
        assertEquals(100, valid.totalDispatches());
    }

    @Test
    void testPercentageRange() {
        // Percentage must be in [0.0, 100.0]
        assertThrows(IllegalArgumentException.class, () ->
            new DispatchMetrics(100, 50, 50, -1.0)
        );

        assertThrows(IllegalArgumentException.class, () ->
            new DispatchMetrics(100, 50, 50, 101.0)
        );

        // Valid boundaries
        var min = new DispatchMetrics(100, 0, 100, 0.0);
        assertEquals(0.0, min.batchPercentage());

        var max = new DispatchMetrics(100, 100, 0, 100.0);
        assertEquals(100.0, max.batchPercentage());
    }
}

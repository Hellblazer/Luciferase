package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricsSnapshot record.
 * Validates composite metrics, factory methods, and calculations.
 */
class MetricsSnapshotTest {

    @Test
    void testEmptyFactory() {
        var empty = MetricsSnapshot.empty();

        assertEquals(0.0, empty.currentFps(), 0.001);
        assertEquals(0.0, empty.avgFrameTimeMs(), 0.001);
        assertEquals(0.0, empty.minFrameTimeMs(), 0.001);
        assertEquals(0.0, empty.maxFrameTimeMs(), 0.001);

        assertNotNull(empty.coherence());
        assertEquals(CoherenceSnapshot.empty(), empty.coherence());

        assertNotNull(empty.dispatch());
        assertEquals(DispatchMetrics.empty(), empty.dispatch());

        assertEquals(0L, empty.gpuMemoryUsedBytes());
        assertEquals(0L, empty.gpuMemoryTotalBytes());

        assertTrue(empty.timestampNanos() > 0); // Should have valid timestamp
    }

    @Test
    void testValidSnapshot() {
        var coherence = new CoherenceSnapshot(0.65, 0.2, 0.9, 100, 5);
        var dispatch = DispatchMetrics.from(100, 75, 25);
        var now = System.nanoTime();

        var snapshot = new MetricsSnapshot(
            60.0,           // currentFps
            16.67,          // avgFrameTimeMs
            15.0,           // minFrameTimeMs
            18.0,           // maxFrameTimeMs
            coherence,
            dispatch,
            512_000_000L,   // 512 MB used
            1_024_000_000L, // 1 GB total
            now
        );

        assertEquals(60.0, snapshot.currentFps(), 0.001);
        assertEquals(16.67, snapshot.avgFrameTimeMs(), 0.001);
        assertEquals(15.0, snapshot.minFrameTimeMs(), 0.001);
        assertEquals(18.0, snapshot.maxFrameTimeMs(), 0.001);
        assertEquals(coherence, snapshot.coherence());
        assertEquals(dispatch, snapshot.dispatch());
        assertEquals(512_000_000L, snapshot.gpuMemoryUsedBytes());
        assertEquals(1_024_000_000L, snapshot.gpuMemoryTotalBytes());
        assertEquals(now, snapshot.timestampNanos());
    }

    @Test
    void testMemoryUsagePercentCalculation() {
        var coherence = CoherenceSnapshot.empty();
        var dispatch = DispatchMetrics.empty();

        // 50% usage
        var halfUsed = new MetricsSnapshot(
            60.0, 16.67, 15.0, 18.0,
            coherence, dispatch,
            512_000_000L, 1_024_000_000L,
            0L
        );
        assertEquals(50.0, halfUsed.memoryUsagePercent(), 0.01);

        // 100% usage
        var fullUsed = new MetricsSnapshot(
            60.0, 16.67, 15.0, 18.0,
            coherence, dispatch,
            1_024_000_000L, 1_024_000_000L,
            0L
        );
        assertEquals(100.0, fullUsed.memoryUsagePercent(), 0.01);

        // 0% usage
        var noUsage = new MetricsSnapshot(
            60.0, 16.67, 15.0, 18.0,
            coherence, dispatch,
            0L, 1_024_000_000L,
            0L
        );
        assertEquals(0.0, noUsage.memoryUsagePercent(), 0.01);

        // Zero total (avoid divide by zero)
        var zeroTotal = new MetricsSnapshot(
            60.0, 16.67, 15.0, 18.0,
            coherence, dispatch,
            0L, 0L,
            0L
        );
        assertEquals(0.0, zeroTotal.memoryUsagePercent(), 0.01);
    }

    @Test
    void testNegativeValuesRejected() {
        var coherence = CoherenceSnapshot.empty();
        var dispatch = DispatchMetrics.empty();

        // Negative FPS
        assertThrows(IllegalArgumentException.class, () ->
            new MetricsSnapshot(
                -1.0, 16.67, 15.0, 18.0,
                coherence, dispatch,
                0L, 0L, 0L
            )
        );

        // Negative frame time
        assertThrows(IllegalArgumentException.class, () ->
            new MetricsSnapshot(
                60.0, -1.0, 15.0, 18.0,
                coherence, dispatch,
                0L, 0L, 0L
            )
        );

        // Negative memory
        assertThrows(IllegalArgumentException.class, () ->
            new MetricsSnapshot(
                60.0, 16.67, 15.0, 18.0,
                coherence, dispatch,
                -1L, 1_024_000_000L, 0L
            )
        );

        assertThrows(IllegalArgumentException.class, () ->
            new MetricsSnapshot(
                60.0, 16.67, 15.0, 18.0,
                coherence, dispatch,
                512_000_000L, -1L, 0L
            )
        );
    }

    @Test
    void testNullNestedRecordsRejected() {
        assertThrows(NullPointerException.class, () ->
            new MetricsSnapshot(
                60.0, 16.67, 15.0, 18.0,
                null,  // null coherence
                DispatchMetrics.empty(),
                0L, 0L, 0L
            )
        );

        assertThrows(NullPointerException.class, () ->
            new MetricsSnapshot(
                60.0, 16.67, 15.0, 18.0,
                CoherenceSnapshot.empty(),
                null,  // null dispatch
                0L, 0L, 0L
            )
        );
    }

    @Test
    void testFrameTimeConsistency() {
        var coherence = CoherenceSnapshot.empty();
        var dispatch = DispatchMetrics.empty();

        // min > max should throw
        assertThrows(IllegalArgumentException.class, () ->
            new MetricsSnapshot(
                60.0, 16.67,
                20.0,  // min
                15.0,  // max (less than min)
                coherence, dispatch,
                0L, 0L, 0L
            )
        );

        // avg outside [min, max] should throw
        assertThrows(IllegalArgumentException.class, () ->
            new MetricsSnapshot(
                60.0,
                10.0,  // avg less than min
                15.0,  // min
                18.0,  // max
                coherence, dispatch,
                0L, 0L, 0L
            )
        );

        assertThrows(IllegalArgumentException.class, () ->
            new MetricsSnapshot(
                60.0,
                20.0,  // avg greater than max
                15.0,  // min
                18.0,  // max
                coherence, dispatch,
                0L, 0L, 0L
            )
        );
    }

    @Test
    void testMemoryUsedExceedsTotalRejected() {
        var coherence = CoherenceSnapshot.empty();
        var dispatch = DispatchMetrics.empty();

        assertThrows(IllegalArgumentException.class, () ->
            new MetricsSnapshot(
                60.0, 16.67, 15.0, 18.0,
                coherence, dispatch,
                2_000_000_000L,  // used > total
                1_024_000_000L,
                0L
            )
        );
    }

    @Test
    void testImmutability() {
        var coherence1 = new CoherenceSnapshot(0.5, 0.2, 0.8, 50, 4);
        var dispatch1 = DispatchMetrics.from(100, 75, 25);

        var snapshot = new MetricsSnapshot(
            60.0, 16.67, 15.0, 18.0,
            coherence1, dispatch1,
            512_000_000L, 1_024_000_000L,
            12345L
        );

        // Verify immutability
        assertEquals(60.0, snapshot.currentFps());
        assertEquals(coherence1, snapshot.coherence());
        assertEquals(dispatch1, snapshot.dispatch());

        // Creating new snapshot doesn't affect original
        var coherence2 = new CoherenceSnapshot(0.7, 0.3, 0.9, 100, 6);
        var dispatch2 = DispatchMetrics.from(200, 150, 50);
        var snapshot2 = new MetricsSnapshot(
            30.0, 33.33, 30.0, 35.0,
            coherence2, dispatch2,
            256_000_000L, 512_000_000L,
            67890L
        );

        assertEquals(60.0, snapshot.currentFps());
        assertEquals(30.0, snapshot2.currentFps());
    }
}

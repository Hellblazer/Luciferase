package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for KernelTimingMetrics record.
 * Validates timing calculations and conversions.
 */
class KernelTimingMetricsTest {

    @Test
    void testBasicConstruction() {
        var metrics = new KernelTimingMetrics("batch_kernel", 1_500_000L, 12345L);

        assertEquals("batch_kernel", metrics.kernelName());
        assertEquals(1_500_000L, metrics.executionTimeNanos());
        assertEquals(12345L, metrics.timestampNanos());
    }

    @Test
    void testExecutionTimeConversion() {
        // 1 millisecond = 1,000,000 nanoseconds
        var oneMs = new KernelTimingMetrics("test", 1_000_000L, 0L);
        assertEquals(1.0, oneMs.executionTimeMs(), 0.001);

        // 1.5 milliseconds
        var onePointFiveMs = new KernelTimingMetrics("test", 1_500_000L, 0L);
        assertEquals(1.5, onePointFiveMs.executionTimeMs(), 0.001);

        // Zero execution time
        var zero = new KernelTimingMetrics("test", 0L, 0L);
        assertEquals(0.0, zero.executionTimeMs(), 0.001);

        // 1 second = 1,000 milliseconds
        var oneSec = new KernelTimingMetrics("test", 1_000_000_000L, 0L);
        assertEquals(1000.0, oneSec.executionTimeMs(), 0.001);
    }

    @Test
    void testNegativeExecutionTimeRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new KernelTimingMetrics("test", -1L, 0L)
        );
    }

    @Test
    void testEmptyKernelNameRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new KernelTimingMetrics("", 1_000_000L, 0L)
        );

        assertThrows(NullPointerException.class, () ->
            new KernelTimingMetrics(null, 1_000_000L, 0L)
        );
    }

    @Test
    void testKernelNamePreservation() {
        var batchKernel = new KernelTimingMetrics("batch_traversal", 1_000_000L, 0L);
        assertEquals("batch_traversal", batchKernel.kernelName());

        var singleKernel = new KernelTimingMetrics("single_ray", 500_000L, 0L);
        assertEquals("single_ray", singleKernel.kernelName());
    }

    @Test
    void testTimestampPreservation() {
        var now = System.nanoTime();
        var metrics = new KernelTimingMetrics("test", 1_000_000L, now);

        assertEquals(now, metrics.timestampNanos());
    }

    @Test
    void testImmutability() {
        var metrics = new KernelTimingMetrics("test", 1_500_000L, 12345L);

        // Verify record is immutable
        assertEquals("test", metrics.kernelName());
        assertEquals(1_500_000L, metrics.executionTimeNanos());

        // Creating new instance doesn't affect original
        var metrics2 = new KernelTimingMetrics("other", 2_000_000L, 67890L);
        assertEquals("test", metrics.kernelName());
        assertEquals("other", metrics2.kernelName());
    }

    @Test
    void testPrecisionConversion() {
        // Test nanosecond precision in conversion
        var precise = new KernelTimingMetrics("test", 1_234_567L, 0L);
        assertEquals(1.234567, precise.executionTimeMs(), 0.000001);
    }
}

package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import java.util.Objects;

/**
 * Timing metrics for a single kernel execution.
 * Immutable and thread-safe.
 *
 * @param kernelName Name of the kernel executed
 * @param executionTimeNanos Execution time in nanoseconds
 * @param timestampNanos System timestamp when execution completed
 */
public record KernelTimingMetrics(
    String kernelName,
    long executionTimeNanos,
    long timestampNanos
) {
    /**
     * Compact constructor with validation.
     */
    public KernelTimingMetrics {
        Objects.requireNonNull(kernelName, "kernelName cannot be null");
        if (kernelName.isEmpty()) {
            throw new IllegalArgumentException("kernelName cannot be empty");
        }
        if (executionTimeNanos < 0) {
            throw new IllegalArgumentException("executionTimeNanos cannot be negative: " + executionTimeNanos);
        }
    }

    /**
     * Converts execution time from nanoseconds to milliseconds.
     *
     * @return Execution time in milliseconds with nanosecond precision
     */
    public double executionTimeMs() {
        return executionTimeNanos / 1_000_000.0;
    }
}

package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import java.util.Objects;

/**
 * Combined metrics snapshot for overlay consumption.
 * Immutable and thread-safe - safe to pass between threads.
 *
 * @param currentFps Current frames per second
 * @param avgFrameTimeMs Average frame time in milliseconds
 * @param minFrameTimeMs Minimum frame time in the window
 * @param maxFrameTimeMs Maximum frame time in the window
 * @param coherence Coherence metrics snapshot
 * @param dispatch Dispatch statistics snapshot
 * @param gpuMemoryUsedBytes GPU memory currently used (bytes)
 * @param gpuMemoryTotalBytes Total GPU memory available (bytes)
 * @param timestampNanos System timestamp when snapshot was created
 */
public record MetricsSnapshot(
    double currentFps,
    double avgFrameTimeMs,
    double minFrameTimeMs,
    double maxFrameTimeMs,
    CoherenceSnapshot coherence,
    DispatchMetrics dispatch,
    long gpuMemoryUsedBytes,
    long gpuMemoryTotalBytes,
    long timestampNanos
) {
    // Epsilon for floating point comparisons (handles precision errors)
    private static final double EPSILON = 1e-6;

    /**
     * Compact constructor with validation.
     */
    public MetricsSnapshot {
        if (currentFps < 0.0) {
            throw new IllegalArgumentException("currentFps cannot be negative: " + currentFps);
        }
        if (avgFrameTimeMs < 0.0) {
            throw new IllegalArgumentException("avgFrameTimeMs cannot be negative: " + avgFrameTimeMs);
        }
        if (minFrameTimeMs < 0.0) {
            throw new IllegalArgumentException("minFrameTimeMs cannot be negative: " + minFrameTimeMs);
        }
        if (maxFrameTimeMs < 0.0) {
            throw new IllegalArgumentException("maxFrameTimeMs cannot be negative: " + maxFrameTimeMs);
        }
        if (minFrameTimeMs > maxFrameTimeMs + EPSILON) {
            throw new IllegalArgumentException(
                "minFrameTimeMs (" + minFrameTimeMs + ") cannot be greater than maxFrameTimeMs (" + maxFrameTimeMs + ")"
            );
        }
        if (avgFrameTimeMs < minFrameTimeMs - EPSILON || avgFrameTimeMs > maxFrameTimeMs + EPSILON) {
            throw new IllegalArgumentException(
                "avgFrameTimeMs (" + avgFrameTimeMs + ") must be in range [" + minFrameTimeMs + ", " + maxFrameTimeMs + "]"
            );
        }
        Objects.requireNonNull(coherence, "coherence cannot be null");
        Objects.requireNonNull(dispatch, "dispatch cannot be null");
        if (gpuMemoryUsedBytes < 0) {
            throw new IllegalArgumentException("gpuMemoryUsedBytes cannot be negative: " + gpuMemoryUsedBytes);
        }
        if (gpuMemoryTotalBytes < 0) {
            throw new IllegalArgumentException("gpuMemoryTotalBytes cannot be negative: " + gpuMemoryTotalBytes);
        }
        if (gpuMemoryUsedBytes > gpuMemoryTotalBytes) {
            throw new IllegalArgumentException(
                "gpuMemoryUsedBytes (" + gpuMemoryUsedBytes + ") cannot exceed gpuMemoryTotalBytes (" + gpuMemoryTotalBytes + ")"
            );
        }
    }

    /**
     * Creates an empty metrics snapshot with all values set to zero.
     * Timestamp is set to current system time.
     */
    public static MetricsSnapshot empty() {
        return new MetricsSnapshot(
            0.0, 0.0, 0.0, 0.0,
            CoherenceSnapshot.empty(),
            DispatchMetrics.empty(),
            0, 0,
            System.nanoTime()
        );
    }

    /**
     * Calculates GPU memory usage as a percentage.
     *
     * @return Memory usage percentage (0.0 to 100.0), or 0.0 if total is zero
     */
    public double memoryUsagePercent() {
        return gpuMemoryTotalBytes > 0
            ? 100.0 * gpuMemoryUsedBytes / gpuMemoryTotalBytes
            : 0.0;
    }
}

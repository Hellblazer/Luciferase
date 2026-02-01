package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collects GPU rendering metrics using CPU-side System.nanoTime() timing.
 * No OpenCL dependency - pure Java implementation.
 * Thread-safe for concurrent metric recording and snapshot retrieval.
 */
public class GPUMetricsCollector {

    private final MetricsAggregator aggregator;

    // Frame-level tracking (per-frame state) - using atomics for thread safety
    private final AtomicLong frameStartNanos = new AtomicLong(0);
    private final AtomicReference<CoherenceSnapshot> currentCoherence = new AtomicReference<>(CoherenceSnapshot.empty());
    private final AtomicInteger frameBatchDispatches = new AtomicInteger(0);
    private final AtomicInteger frameSingleRayDispatches = new AtomicInteger(0);

    /**
     * Creates collector with default window size (60 frames).
     */
    public GPUMetricsCollector() {
        this(MetricsAggregator.DEFAULT_WINDOW_SIZE);
    }

    /**
     * Creates collector with specified window size.
     *
     * @param windowSize Number of frames to track in rolling window
     */
    public GPUMetricsCollector(int windowSize) {
        this.aggregator = new MetricsAggregator(windowSize);
    }

    /**
     * Marks the beginning of a new frame.
     * Resets per-frame metrics.
     */
    public void beginFrame() {
        frameStartNanos.set(System.nanoTime());
        currentCoherence.set(CoherenceSnapshot.empty());
        frameBatchDispatches.set(0);
        frameSingleRayDispatches.set(0);
    }

    /**
     * Marks the end of the current frame.
     * Aggregates collected metrics and adds to rolling window.
     */
    public void endFrame() {
        var startNanos = frameStartNanos.get();
        if (startNanos == 0) {
            // No matching beginFrame - ignore
            return;
        }

        var frameEndNanos = System.nanoTime();
        var frameTimeNanos = frameEndNanos - startNanos;

        // Build dispatch metrics from accumulated frame data
        var batchCount = frameBatchDispatches.get();
        var singleRayCount = frameSingleRayDispatches.get();
        var totalDispatches = batchCount + singleRayCount;
        var dispatch = DispatchMetrics.from(
            totalDispatches,
            batchCount,
            singleRayCount
        );

        // Add frame to aggregator
        aggregator.addFrame(frameTimeNanos, currentCoherence.get(), dispatch);

        // Reset frame start for next frame
        frameStartNanos.set(0);
    }

    /**
     * Records kernel execution timing.
     * Currently not used in aggregation but available for future profiling.
     *
     * @param kernelName Name of the kernel executed
     * @param startNanos Start time in nanoseconds
     * @param endNanos End time in nanoseconds
     */
    public void recordKernelTiming(String kernelName, long startNanos, long endNanos) {
        // Currently stored for potential future use
        // Could be extended to track per-kernel statistics
    }

    /**
     * Records BeamTree statistics for the current frame.
     * Overwrites previous coherence data if called multiple times in same frame.
     *
     * @param coherence Coherence snapshot from BeamTree
     */
    public void recordBeamTreeStats(CoherenceSnapshot coherence) {
        this.currentCoherence.set(coherence);
    }

    /**
     * Records kernel selection metrics for the current frame.
     * Accumulates dispatch counts if called multiple times in same frame.
     *
     * @param dispatch Dispatch metrics from kernel selector
     */
    public void recordKernelSelection(DispatchMetrics dispatch) {
        frameBatchDispatches.addAndGet(dispatch.batchDispatches());
        frameSingleRayDispatches.addAndGet(dispatch.singleRayDispatches());
    }

    /**
     * Gets current aggregated metrics snapshot.
     * Thread-safe - can be called concurrently with metric recording.
     *
     * @return Immutable metrics snapshot
     */
    public MetricsSnapshot getSnapshot() {
        return aggregator.aggregate();
    }

    /**
     * Resets all collected metrics.
     * Clears rolling window and frame-level state.
     */
    public void reset() {
        aggregator.clear();
        frameStartNanos.set(0);
        currentCoherence.set(CoherenceSnapshot.empty());
        frameBatchDispatches.set(0);
        frameSingleRayDispatches.set(0);
    }
}

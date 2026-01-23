package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Rolling window aggregator for metrics smoothing.
 * Thread-safe for concurrent read/write access.
 * Uses ReentrantReadWriteLock to allow multiple concurrent readers
 * while ensuring exclusive write access.
 */
public class MetricsAggregator {

    public static final int DEFAULT_WINDOW_SIZE = 60;

    private final int windowSize;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Deque<FrameData> frameWindow = new ArrayDeque<>();

    /**
     * Creates aggregator with default window size (60 frames).
     */
    public MetricsAggregator() {
        this(DEFAULT_WINDOW_SIZE);
    }

    /**
     * Creates aggregator with specified window size.
     *
     * @param windowSize Number of frames to track in rolling window
     */
    public MetricsAggregator(int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive: " + windowSize);
        }
        this.windowSize = windowSize;
    }

    /**
     * Adds a frame's data to the rolling window.
     * If window is full, oldest frame is evicted.
     *
     * @param frameTimeNanos Frame time in nanoseconds
     * @param coherence Coherence snapshot for this frame
     * @param dispatch Dispatch metrics for this frame
     */
    public void addFrame(long frameTimeNanos, CoherenceSnapshot coherence, DispatchMetrics dispatch) {
        lock.writeLock().lock();
        try {
            // Add new frame
            frameWindow.addLast(new FrameData(frameTimeNanos, coherence, dispatch));

            // Evict oldest if over capacity
            while (frameWindow.size() > windowSize) {
                frameWindow.removeFirst();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Aggregates all frames in the window into a single snapshot.
     * Returns empty snapshot if window is empty.
     *
     * @return Aggregated metrics snapshot
     */
    public MetricsSnapshot aggregate() {
        lock.readLock().lock();
        try {
            if (frameWindow.isEmpty()) {
                return MetricsSnapshot.empty();
            }

            // Calculate frame time statistics
            var frameTimeMs = 0.0;
            var minFrameTimeMs = Double.MAX_VALUE;
            var maxFrameTimeMs = 0.0;

            for (var frame : frameWindow) {
                var ms = frame.frameTimeNanos / 1_000_000.0;
                frameTimeMs += ms;
                minFrameTimeMs = Math.min(minFrameTimeMs, ms);
                maxFrameTimeMs = Math.max(maxFrameTimeMs, ms);
            }

            var avgFrameTimeMs = frameTimeMs / frameWindow.size();

            // FPS from most recent frame
            var lastFrame = frameWindow.getLast();
            var currentFps = lastFrame.frameTimeNanos > 0
                ? 1_000_000_000.0 / lastFrame.frameTimeNanos
                : 0.0;

            // Aggregate coherence
            var avgCoherence = 0.0;
            var minCoherence = Double.MAX_VALUE;
            var maxCoherence = 0.0;
            var totalBeams = 0;
            var maxDepth = 0;

            for (var frame : frameWindow) {
                avgCoherence += frame.coherence.averageCoherence();
                minCoherence = Math.min(minCoherence, frame.coherence.minCoherence());
                maxCoherence = Math.max(maxCoherence, frame.coherence.maxCoherence());
                totalBeams += frame.coherence.totalBeams();
                maxDepth = Math.max(maxDepth, frame.coherence.maxDepth());
            }
            avgCoherence /= frameWindow.size();

            // Handle empty coherence case
            if (minCoherence == Double.MAX_VALUE) {
                minCoherence = 0.0;
            }

            var coherenceSnapshot = new CoherenceSnapshot(
                avgCoherence,
                minCoherence,
                maxCoherence,
                totalBeams / frameWindow.size(),  // Average beam count
                maxDepth
            );

            // Aggregate dispatch (sum across all frames)
            var totalDispatches = 0;
            var batchDispatches = 0;
            var singleRayDispatches = 0;

            for (var frame : frameWindow) {
                totalDispatches += frame.dispatch.totalDispatches();
                batchDispatches += frame.dispatch.batchDispatches();
                singleRayDispatches += frame.dispatch.singleRayDispatches();
            }

            var dispatchSnapshot = DispatchMetrics.from(
                totalDispatches,
                batchDispatches,
                singleRayDispatches
            );

            return new MetricsSnapshot(
                currentFps,
                avgFrameTimeMs,
                minFrameTimeMs,
                maxFrameTimeMs,
                coherenceSnapshot,
                dispatchSnapshot,
                0,  // GPU memory placeholder
                0,
                System.nanoTime()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clears all frames from the window.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            frameWindow.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns current number of frames in the window.
     *
     * @return Frame count (0 to windowSize)
     */
    public int getFrameCount() {
        lock.readLock().lock();
        try {
            return frameWindow.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Internal frame data holder.
     */
    private record FrameData(
        long frameTimeNanos,
        CoherenceSnapshot coherence,
        DispatchMetrics dispatch
    ) {}
}

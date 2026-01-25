/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.render.tile;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Tracks frame-to-frame performance for adaptive scheduling decisions.
 *
 * <p>Maintains a rolling window of performance metrics and provides trend analysis
 * for dynamic threshold adjustment. Thread-safe implementation using read-write locks.
 *
 * @see AdaptiveScheduler
 */
public class PerformanceTracker {

    /**
     * Performance snapshot for a single frame.
     */
    public record FrameMetrics(
        long frameTimeNs,
        long gpuTimeNs,
        long cpuTimeNs,
        int gpuTiles,
        int cpuTiles,
        double avgCoherence,
        double gpuSaturation
    ) {
        /**
         * Returns GPU utilization as ratio of GPU time to total time.
         */
        public double gpuUtilization() {
            return frameTimeNs > 0 ? (double) gpuTimeNs / frameTimeNs : 0.0;
        }

        /**
         * Returns CPU utilization as ratio of CPU time to total time.
         */
        public double cpuUtilization() {
            return frameTimeNs > 0 ? (double) cpuTimeNs / frameTimeNs : 0.0;
        }

        /**
         * Returns GPU tile ratio.
         */
        public double gpuTileRatio() {
            int total = gpuTiles + cpuTiles;
            return total > 0 ? (double) gpuTiles / total : 0.0;
        }

        /**
         * Creates FrameMetrics from HybridDispatchMetrics.
         */
        public static FrameMetrics from(HybridDispatchMetrics metrics) {
            return new FrameMetrics(
                metrics.dispatchTimeNs(),
                metrics.gpuTimeNs(),
                metrics.cpuTimeNs(),
                metrics.totalGpuTiles(),
                metrics.cpuTiles(),
                metrics.avgCoherence(),
                metrics.gpuSaturation()
            );
        }
    }

    /**
     * Performance trend indicator.
     */
    public enum Trend {
        IMPROVING,    // Performance getting better
        STABLE,       // Performance within tolerance
        DEGRADING     // Performance getting worse
    }

    /**
     * Summary of recent performance.
     */
    public record PerformanceSummary(
        double avgFrameTimeMs,
        double avgGpuUtilization,
        double avgCpuUtilization,
        double avgGpuTileRatio,
        double avgCoherence,
        double avgGpuSaturation,
        Trend trend,
        int sampleCount
    ) {}

    private static final int DEFAULT_WINDOW_SIZE = 30;  // ~0.5 second at 60 FPS
    private static final double TREND_THRESHOLD = 0.05;  // 5% change triggers trend

    private final int windowSize;
    private final Deque<FrameMetrics> history;
    private final ReentrantReadWriteLock lock;

    // Cached statistics (updated on each record)
    private double rollingFrameTimeNs;
    private double rollingGpuTime;
    private double rollingCpuTime;
    private double rollingGpuRatio;
    private double rollingCoherence;
    private double rollingGpuSaturation;

    /**
     * Creates a tracker with default window size (30 frames).
     */
    public PerformanceTracker() {
        this(DEFAULT_WINDOW_SIZE);
    }

    /**
     * Creates a tracker with specified window size.
     *
     * @param windowSize number of frames to track
     */
    public PerformanceTracker(int windowSize) {
        if (windowSize < 3) {
            throw new IllegalArgumentException("Window size must be at least 3");
        }
        this.windowSize = windowSize;
        this.history = new ArrayDeque<>(windowSize);
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Records a new frame's metrics.
     *
     * @param metrics frame metrics to record
     */
    public void record(FrameMetrics metrics) {
        lock.writeLock().lock();
        try {
            if (history.size() >= windowSize) {
                var removed = history.removeFirst();
                // Subtract removed values from rolling sums
                rollingFrameTimeNs -= removed.frameTimeNs();
                rollingGpuTime -= removed.gpuTimeNs();
                rollingCpuTime -= removed.cpuTimeNs();
                rollingGpuRatio -= removed.gpuTileRatio();
                rollingCoherence -= removed.avgCoherence();
                rollingGpuSaturation -= removed.gpuSaturation();
            }

            history.addLast(metrics);

            // Add new values to rolling sums
            rollingFrameTimeNs += metrics.frameTimeNs();
            rollingGpuTime += metrics.gpuTimeNs();
            rollingCpuTime += metrics.cpuTimeNs();
            rollingGpuRatio += metrics.gpuTileRatio();
            rollingCoherence += metrics.avgCoherence();
            rollingGpuSaturation += metrics.gpuSaturation();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Records metrics from HybridDispatchMetrics.
     *
     * @param metrics dispatch metrics
     */
    public void record(HybridDispatchMetrics metrics) {
        record(FrameMetrics.from(metrics));
    }

    /**
     * Gets a summary of recent performance.
     *
     * @return performance summary
     */
    public PerformanceSummary getSummary() {
        lock.readLock().lock();
        try {
            int count = history.size();
            if (count == 0) {
                return new PerformanceSummary(0, 0, 0, 0, 0, 0, Trend.STABLE, 0);
            }

            double avgFrameTimeMs = (rollingFrameTimeNs / count) / 1_000_000.0;
            double avgFrameTime = rollingFrameTimeNs / count;
            double avgGpuUtil = avgFrameTime > 0 ? (rollingGpuTime / count) / avgFrameTime : 0;
            double avgCpuUtil = avgFrameTime > 0 ? (rollingCpuTime / count) / avgFrameTime : 0;
            double avgGpuRatio = rollingGpuRatio / count;
            double avgCoherence = rollingCoherence / count;
            double avgSaturation = rollingGpuSaturation / count;

            return new PerformanceSummary(
                avgFrameTimeMs,
                avgGpuUtil,
                avgCpuUtil,
                avgGpuRatio,
                avgCoherence,
                avgSaturation,
                calculateTrend(),
                count
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the most recent frame metrics.
     *
     * @return latest metrics or null if none recorded
     */
    public FrameMetrics getLatest() {
        lock.readLock().lock();
        try {
            return history.isEmpty() ? null : history.getLast();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks if enough samples exist for reliable trend analysis.
     *
     * @return true if at least half the window is filled
     */
    public boolean hasReliableData() {
        lock.readLock().lock();
        try {
            return history.size() >= windowSize / 2;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Resets the tracker, clearing all history.
     */
    public void reset() {
        lock.writeLock().lock();
        try {
            history.clear();
            rollingFrameTimeNs = 0;
            rollingGpuTime = 0;
            rollingCpuTime = 0;
            rollingGpuRatio = 0;
            rollingCoherence = 0;
            rollingGpuSaturation = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the current sample count.
     */
    public int getSampleCount() {
        lock.readLock().lock();
        try {
            return history.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Calculates performance trend by comparing first and second halves of window.
     */
    private Trend calculateTrend() {
        if (history.size() < 6) {
            return Trend.STABLE;  // Not enough data
        }

        int halfSize = history.size() / 2;
        double firstHalfAvg = 0;
        double secondHalfAvg = 0;
        int idx = 0;

        for (var metrics : history) {
            if (idx < halfSize) {
                firstHalfAvg += metrics.frameTimeNs();
            } else {
                secondHalfAvg += metrics.frameTimeNs();
            }
            idx++;
        }

        firstHalfAvg /= halfSize;
        secondHalfAvg /= (history.size() - halfSize);

        double change = (secondHalfAvg - firstHalfAvg) / firstHalfAvg;

        if (change < -TREND_THRESHOLD) {
            return Trend.IMPROVING;  // Frame time decreasing = faster
        } else if (change > TREND_THRESHOLD) {
            return Trend.DEGRADING;  // Frame time increasing = slower
        }
        return Trend.STABLE;
    }
}

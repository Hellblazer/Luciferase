/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.sparse.core.SparseVoxelData;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe compression metrics collector with timing measurement.
 * <p>
 * Records compression operations with automatic timing, tracks min/max/average build times,
 * and maintains a history of all compression metrics.
 *
 * @author hal.hildebrand
 */
public class CompressionMetricsCollector {

    private final List<CompressionMetrics> metrics = Collections.synchronizedList(new ArrayList<>());
    private final Lock                     lock    = new ReentrantLock();

    /**
     * Record a compression operation with automatic timing measurement
     *
     * @param source source SVO data
     * @param result compressed DAG data
     * @throws NullPointerException if source or result is null
     */
    public void recordCompression(SparseVoxelData source, DAGOctreeData result) {
        Objects.requireNonNull(source, "source octree cannot be null");
        Objects.requireNonNull(result, "compression result cannot be null");

        var sourceNodeCount = source.nodeCount();
        var compressedNodeCount = result.nodeCount();
        var uniqueInternalNodes = result.internalCount();
        var uniqueLeafNodes = result.leafCount();
        var buildTime = result.getMetadata().buildTime();

        var metric = new CompressionMetrics(compressedNodeCount, sourceNodeCount, uniqueInternalNodes,
                                             uniqueLeafNodes, buildTime);

        lock.lock();
        try {
            metrics.add(metric);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get summary statistics for all recorded compressions
     *
     * @return summary with aggregated timing statistics
     */
    public CompressionSummary getSummary() {
        lock.lock();
        try {
            if (metrics.isEmpty()) {
                return new CompressionSummary(0, 0L, 0.0, 0L, 0L);
            }

            long totalTimeMs = 0;
            long minTimeMs = Long.MAX_VALUE;
            long maxTimeMs = 0;

            for (var metric : metrics) {
                var timeMs = metric.buildTime().toMillis();
                totalTimeMs += timeMs;
                minTimeMs = Math.min(minTimeMs, timeMs);
                maxTimeMs = Math.max(maxTimeMs, timeMs);
            }

            var averageTimeMs = (double) totalTimeMs / metrics.size();

            return new CompressionSummary(metrics.size(), totalTimeMs, averageTimeMs, minTimeMs, maxTimeMs);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get all recorded compression metrics
     *
     * @return immutable list of all metrics
     */
    public List<CompressionMetrics> getAllMetrics() {
        lock.lock();
        try {
            return new ArrayList<>(metrics);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reset all collected metrics
     */
    public void reset() {
        lock.lock();
        try {
            metrics.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Summary statistics for compression operations
     *
     * @param totalCompressions Total number of compressions recorded
     * @param totalTimeMs       Total time across all compressions
     * @param averageTimeMs     Average compression time
     * @param minTimeMs         Minimum compression time
     * @param maxTimeMs         Maximum compression time
     */
    public record CompressionSummary(int totalCompressions, long totalTimeMs, double averageTimeMs, long minTimeMs,
                                     long maxTimeMs) {
    }
}

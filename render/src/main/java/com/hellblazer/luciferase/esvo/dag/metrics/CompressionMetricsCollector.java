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
 * Thread-safe compression metrics collector with timing measurement and statistical analysis.
 * <p>
 * Records compression operations with automatic timing, tracks min/max/average build times,
 * and maintains a history of all compression metrics for performance analysis and benchmarking.
 *
 * <h3>Thread Safety</h3>
 * This collector uses a {@link ReentrantLock} to provide thread-safe access to the metrics collection.
 * Multiple threads can safely record compression operations concurrently without data corruption.
 * <p>
 * The internal storage uses {@link Collections#synchronizedList} for double-safety, though the
 * ReentrantLock provides the primary concurrency guarantee.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * // Create collector
 * var collector = new CompressionMetricsCollector();
 *
 * // Record multiple compression operations
 * for (var svo : octrees) {
 *     var dag = DAGBuilder.from(svo).build();
 *     collector.recordCompression(svo, dag);
 * }
 *
 * // Analyze performance
 * var summary = collector.getSummary();
 * System.out.printf("Compressions: %d%n", summary.totalCompressions());
 * System.out.printf("Average time: %.2fms%n", summary.averageTimeMs());
 * System.out.printf("Range: %dms - %dms%n", summary.minTimeMs(), summary.maxTimeMs());
 * }</pre>
 *
 * <h3>Concurrent Usage Example</h3>
 * <pre>{@code
 * var collector = new CompressionMetricsCollector();
 *
 * // Multiple threads recording concurrently
 * octrees.parallelStream().forEach(svo -> {
 *     var dag = DAGBuilder.from(svo).build();
 *     collector.recordCompression(svo, dag);  // Thread-safe
 * });
 *
 * // Get aggregated results (also thread-safe)
 * var summary = collector.getSummary();
 * }</pre>
 *
 * @author hal.hildebrand
 * @see CompressionMetrics
 * @see FileMetricsExporter
 */
public class CompressionMetricsCollector {

    private final List<CompressionMetrics> metrics = Collections.synchronizedList(new ArrayList<>());
    private final Lock                     lock    = new ReentrantLock();

    /**
     * Record a compression operation with automatic metric extraction.
     * <p>
     * Extracts compression statistics from the source and result data structures:
     * <ul>
     *   <li>Source node count (before compression)</li>
     *   <li>Compressed node count (after deduplication)</li>
     *   <li>Unique internal node count (non-leaf nodes)</li>
     *   <li>Unique leaf node count</li>
     *   <li>Build time (from DAG metadata)</li>
     * </ul>
     * The created {@link CompressionMetrics} record is added to the internal collection
     * in a thread-safe manner.
     *
     * <h3>Example</h3>
     * <pre>{@code
     * var collector = new CompressionMetricsCollector();
     * var svo = createOctree();  // Create source octree
     * var dag = DAGBuilder.from(svo).build();  // Compress to DAG
     *
     * collector.recordCompression(svo, dag);  // Record metrics
     *
     * // Metrics are now available in collector
     * var allMetrics = collector.getAllMetrics();
     * System.out.printf("Recorded %d compressions%n", allMetrics.size());
     * }</pre>
     *
     * @param source source SVO data (before compression)
     * @param result compressed DAG data (after deduplication)
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
     * Get summary statistics for all recorded compressions.
     * <p>
     * Computes aggregated timing statistics across all recorded compression operations:
     * <ul>
     *   <li><b>Total compressions</b>: Count of recorded operations</li>
     *   <li><b>Total time</b>: Sum of all build times</li>
     *   <li><b>Average time</b>: Mean build time (totalTimeMs / count)</li>
     *   <li><b>Min time</b>: Fastest compression operation</li>
     *   <li><b>Max time</b>: Slowest compression operation</li>
     * </ul>
     *
     * <h3>Calculation Details</h3>
     * The summary is computed on-demand by iterating through all collected metrics:
     * <pre>
     * averageTimeMs = totalTimeMs / totalCompressions
     * minTimeMs = min(metric.buildTime() for all metrics)
     * maxTimeMs = max(metric.buildTime() for all metrics)
     * </pre>
     *
     * <h3>Empty Collection</h3>
     * If no metrics have been recorded, returns a zero-initialized summary:
     * <pre>{@code
     * new CompressionSummary(0, 0L, 0.0, 0L, 0L)
     * }</pre>
     *
     * <h3>Example</h3>
     * <pre>{@code
     * var summary = collector.getSummary();
     * System.out.printf("Processed %d compressions%n", summary.totalCompressions());
     * System.out.printf("Total time: %dms, Average: %.2fms%n",
     *     summary.totalTimeMs(), summary.averageTimeMs());
     * System.out.printf("Time range: [%dms, %dms]%n",
     *     summary.minTimeMs(), summary.maxTimeMs());
     * }</pre>
     *
     * @return summary with aggregated timing statistics (never null)
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
     * Get all recorded compression metrics as an immutable snapshot.
     * <p>
     * Returns a defensive copy of the internal metrics collection to prevent external
     * modification. The returned list is independent of the collector's internal state
     * and can be freely modified without affecting subsequent calls.
     *
     * <h3>Thread Safety</h3>
     * The copy is created while holding the lock, ensuring a consistent snapshot even
     * when other threads are concurrently recording metrics.
     *
     * <h3>Performance Considerations</h3>
     * This method creates a new {@link ArrayList} copy on each call. For large metric
     * collections (10,000+ entries), consider using {@link #getSummary()} instead if
     * only statistical data is needed.
     *
     * <h3>Example</h3>
     * <pre>{@code
     * var allMetrics = collector.getAllMetrics();
     *
     * // Safe to modify without affecting collector
     * allMetrics.sort(Comparator.comparing(m -> m.buildTime()));
     *
     * // Export individual metrics
     * for (var metric : allMetrics) {
     *     System.out.printf("%d nodes -> %d nodes (%.2fx) in %dms%n",
     *         metric.sourceNodeCount(),
     *         metric.compressedNodeCount(),
     *         metric.compressionRatio(),
     *         metric.buildTime().toMillis());
     * }
     * }</pre>
     *
     * @return immutable snapshot of all metrics (never null, may be empty)
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
     * Reset all collected metrics, clearing the internal collection.
     * <p>
     * After calling this method, {@link #getAllMetrics()} will return an empty list
     * and {@link #getSummary()} will return a zero-initialized summary.
     *
     * <h3>Thread Safety</h3>
     * The reset operation acquires the lock before clearing, ensuring that concurrent
     * {@link #recordCompression} calls either complete before or after the reset.
     *
     * <h3>Use Cases</h3>
     * <ul>
     *   <li>Periodic metric flushing in long-running processes</li>
     *   <li>Benchmark warmup phase cleanup</li>
     *   <li>Test isolation between test cases</li>
     * </ul>
     *
     * <h3>Example</h3>
     * <pre>{@code
     * // Warmup phase
     * for (int i = 0; i < 10; i++) {
     *     var dag = DAGBuilder.from(testOctree).build();
     *     collector.recordCompression(testOctree, dag);
     * }
     * collector.reset();  // Discard warmup metrics
     *
     * // Actual benchmark
     * for (var svo : benchmarkOctrees) {
     *     var dag = DAGBuilder.from(svo).build();
     *     collector.recordCompression(svo, dag);
     * }
     * var summary = collector.getSummary();  // Only benchmark data
     * }</pre>
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
     * Summary statistics for compression operations.
     * <p>
     * Immutable record containing aggregated timing and count statistics across
     * multiple compression operations. Created by {@link #getSummary()}.
     *
     * <h3>Statistical Properties</h3>
     * <ul>
     *   <li><b>Total compressions</b>: Sample size (N)</li>
     *   <li><b>Total time</b>: Sum of all build times (Σ t_i)</li>
     *   <li><b>Average time</b>: Arithmetic mean (Σ t_i / N)</li>
     *   <li><b>Min time</b>: Fastest operation (min(t_i))</li>
     *   <li><b>Max time</b>: Slowest operation (max(t_i))</li>
     * </ul>
     *
     * <h3>Example Analysis</h3>
     * <pre>{@code
     * var summary = collector.getSummary();
     *
     * // Performance variance
     * double variance = summary.maxTimeMs() - summary.minTimeMs();
     * double cvPercent = (variance / summary.averageTimeMs()) * 100;
     *
     * System.out.printf("Performance variability: %.1f%%%n", cvPercent);
     *
     * // Throughput calculation
     * double compressionsThroughput = (double) summary.totalCompressions()
     *     / (summary.totalTimeMs() / 1000.0); // per second
     * System.out.printf("Throughput: %.2f compressions/sec%n", compressionsThroughput);
     * }</pre>
     *
     * @param totalCompressions Total number of compressions recorded (N)
     * @param totalTimeMs       Total time across all compressions in milliseconds (Σ t_i)
     * @param averageTimeMs     Average compression time in milliseconds (Σ t_i / N)
     * @param minTimeMs         Minimum compression time in milliseconds (min(t_i))
     * @param maxTimeMs         Maximum compression time in milliseconds (max(t_i))
     */
    public record CompressionSummary(int totalCompressions, long totalTimeMs, double averageTimeMs, long minTimeMs,
                                     long maxTimeMs) {
    }
}

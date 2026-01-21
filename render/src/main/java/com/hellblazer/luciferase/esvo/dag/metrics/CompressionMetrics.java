/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

import java.time.Duration;

/**
 * Immutable compression performance metrics record.
 * <p>
 * Captures comprehensive statistics about DAG compression operations, including node counts,
 * compression ratios, build timing, and memory savings. This record is designed to be
 * collected during DAG construction and used for performance analysis, benchmarking,
 * and optimization decisions.
 *
 * <h3>Metrics Tracked</h3>
 * <ul>
 *   <li><b>Node Counts</b>: Source vs compressed node counts, unique internal/leaf nodes</li>
 *   <li><b>Compression Ratios</b>: Both ratio (e.g., 4.5x) and percentage (e.g., 78%)</li>
 *   <li><b>Memory Savings</b>: Bytes saved and percentage reduction</li>
 *   <li><b>Build Performance</b>: Time taken for compression operation</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * // During DAG construction
 * var metrics = new CompressionMetrics(
 *     2048,                    // compressed nodes
 *     10240,                   // source nodes
 *     1536,                    // unique internal nodes
 *     512,                     // unique leaf nodes
 *     Duration.ofMillis(250)   // build time
 * );
 *
 * // Analysis
 * System.out.printf("Compression: %.2fx (%.1f%%)%n",
 *     metrics.compressionRatio(),
 *     metrics.compressionPercent());
 * System.out.printf("Memory saved: %d bytes (%.1f%%)%n",
 *     metrics.memorySavedBytes(),
 *     metrics.memorySavedPercent());
 * System.out.printf("Build time: %dms%n",
 *     metrics.buildTime().toMillis());
 * }</pre>
 *
 * <h3>Compression Ratio Calculation</h3>
 * The compression ratio is computed as:
 * <pre>
 * compressionRatio = sourceNodeCount / compressedNodeCount
 * </pre>
 * Higher ratios indicate better compression:
 * <ul>
 *   <li>1.0x = No compression (worst case)</li>
 *   <li>2.0x = 50% reduction (moderate)</li>
 *   <li>4.0x = 75% reduction (good)</li>
 *   <li>10.0x = 90% reduction (excellent)</li>
 * </ul>
 *
 * <h3>Memory Savings Formula</h3>
 * Memory savings assumes 8 bytes per node reference (64-bit pointer):
 * <pre>
 * memorySavedBytes = (sourceNodeCount - compressedNodeCount) * 8
 * memorySavedPercent = ((sourceNodeCount - compressedNodeCount) / sourceNodeCount) * 100
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * This record is immutable and thread-safe. Instances can be safely shared across threads
 * and stored in concurrent collections without synchronization.
 *
 * @param compressedNodeCount Number of nodes in compressed DAG (after deduplication)
 * @param sourceNodeCount     Number of nodes in source octree (before compression)
 * @param uniqueInternalNodes Number of unique internal (non-leaf) nodes in DAG
 * @param uniqueLeafNodes     Number of unique leaf nodes in DAG
 * @param buildTime           Time taken to build the compressed DAG (all phases)
 * @author hal.hildebrand
 * @see CompressionMetricsCollector
 * @see com.hellblazer.luciferase.esvo.dag.DAGBuilder
 */
public record CompressionMetrics(int compressedNodeCount, int sourceNodeCount, int uniqueInternalNodes,
                                 int uniqueLeafNodes, Duration buildTime) {

    /** Assumed bytes per node reference (64-bit pointer) for memory calculations */
    private static final int BYTES_PER_NODE_REFERENCE = 8;

    /**
     * Internal timestamp when metrics were created.
     *
     * @return current system time in milliseconds
     */
    private long timestamp() {
        return System.currentTimeMillis();
    }

    /**
     * Calculate compression ratio as source/compressed node count.
     * <p>
     * This provides an intuitive measure of compression effectiveness:
     * <ul>
     *   <li>1.0x = no compression</li>
     *   <li>2.0x = half the nodes</li>
     *   <li>5.0x = one-fifth the nodes</li>
     * </ul>
     *
     * <h3>Example</h3>
     * <pre>{@code
     * var metrics = new CompressionMetrics(2000, 10000, 1500, 500, Duration.ofMillis(100));
     * System.out.printf("Compression: %.2fx%n", metrics.compressionRatio()); // "Compression: 5.00x"
     * }</pre>
     *
     * @return ratio of source nodes to compressed nodes (higher = better compression),
     *         or 0.0 if either count is zero
     */
    public float compressionRatio() {
        if (sourceNodeCount == 0 || compressedNodeCount == 0) {
            return 0.0f; // No meaningful compression ratio
        }
        return (float) sourceNodeCount / compressedNodeCount;
    }

    /**
     * Calculate compression as percentage reduction in node count.
     * <p>
     * This provides an alternative representation of compression effectiveness:
     * <ul>
     *   <li>0% = no compression</li>
     *   <li>50% = half the nodes removed</li>
     *   <li>90% = nine-tenths removed</li>
     * </ul>
     *
     * <h3>Relationship to Ratio</h3>
     * <pre>
     * compressionPercent = (1 - 1/compressionRatio) * 100
     *
     * Examples:
     *   2.0x ratio = 50.0% reduction
     *   5.0x ratio = 80.0% reduction
     *  10.0x ratio = 90.0% reduction
     * </pre>
     *
     * <h3>Example</h3>
     * <pre>{@code
     * var metrics = new CompressionMetrics(2000, 10000, 1500, 500, Duration.ofMillis(100));
     * System.out.printf("Reduction: %.1f%%%n", metrics.compressionPercent()); // "Reduction: 80.0%"
     * }</pre>
     *
     * @return percentage reduction in node count (0-100, higher = better),
     *         or 0.0 if source count is zero
     */
    public float compressionPercent() {
        if (sourceNodeCount == 0) {
            return 0.0f;
        }
        return (1.0f - (float) compressedNodeCount / sourceNodeCount) * 100.0f;
    }

    /**
     * Calculate estimated memory saved in bytes.
     * <p>
     * Assumes 8 bytes per node reference (64-bit pointer architecture).
     * This is a conservative estimate that doesn't include:
     * <ul>
     *   <li>Node payload data (descriptors, attributes)</li>
     *   <li>Child pointer arrays</li>
     *   <li>Metadata overhead</li>
     * </ul>
     * Actual memory savings may be higher due to shared subtree data.
     *
     * <h3>Calculation</h3>
     * <pre>
     * memorySaved = (sourceNodeCount - compressedNodeCount) * 8 bytes
     * </pre>
     *
     * <h3>Example</h3>
     * <pre>{@code
     * var metrics = new CompressionMetrics(2000, 10000, 1500, 500, Duration.ofMillis(100));
     * long savedBytes = metrics.memorySavedBytes(); // 64,000 bytes (62.5 KB)
     * System.out.printf("Memory saved: %.1f KB%n", savedBytes / 1024.0);
     * }</pre>
     *
     * @return estimated bytes saved (positive = compression, negative = expansion)
     */
    public long memorySavedBytes() {
        return (long) (sourceNodeCount - compressedNodeCount) * BYTES_PER_NODE_REFERENCE;
    }

    /**
     * Calculate memory saved as percentage of original memory usage.
     * <p>
     * This metric is equivalent to {@link #compressionPercent()} when only
     * considering node references. It's provided separately for clarity when
     * discussing memory vs node count compression.
     *
     * <h3>Example</h3>
     * <pre>{@code
     * var metrics = new CompressionMetrics(2000, 10000, 1500, 500, Duration.ofMillis(100));
     * System.out.printf("Memory reduction: %.1f%%%n",
     *     metrics.memorySavedPercent()); // "Memory reduction: 80.0%"
     * }</pre>
     *
     * @return percentage of original memory saved (0-100, higher = better),
     *         or 0.0 if source count is zero
     */
    public float memorySavedPercent() {
        if (sourceNodeCount == 0) {
            return 0.0f;
        }
        return (float) (sourceNodeCount - compressedNodeCount) / sourceNodeCount * 100.0f;
    }

    /**
     * Get the compression strategy identifier.
     * <p>
     * Currently returns "HASH_BASED" for all compression operations.
     * Future versions may support multiple strategies (e.g., "GEOMETRIC", "ATTRIBUTE_BASED").
     *
     * @return strategy identifier string (currently always "HASH_BASED")
     */
    public String strategy() {
        return "HASH_BASED"; // Default strategy for now
    }

    /**
     * Get the timestamp when these metrics were created.
     * <p>
     * This is primarily used for serialization and test verification.
     * The timestamp is generated at call time, not captured during construction.
     *
     * @return timestamp in milliseconds since epoch
     */
    public long timestamp_value() {
        return timestamp();
    }
}

/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.luciferase.esvo.dag.metrics;

import java.time.Duration;

/**
 * Immutable compression performance metrics record.
 * <p>
 * Tracks compression statistics including node counts, ratios, timing, and memory savings.
 *
 * @param compressedNodeCount Number of nodes in compressed DAG
 * @param sourceNodeCount     Number of nodes in source octree
 * @param uniqueInternalNodes Number of unique internal nodes
 * @param uniqueLeafNodes     Number of unique leaf nodes
 * @param buildTime           Time taken to build the compressed DAG
 * @author hal.hildebrand
 */
public record CompressionMetrics(int compressedNodeCount, int sourceNodeCount, int uniqueInternalNodes,
                                 int uniqueLeafNodes, Duration buildTime) {

    private static final int BYTES_PER_NODE_REFERENCE = 8; // Pointer size assumption

    /**
     * Timestamp when metrics were created
     */
    private long timestamp() {
        return System.currentTimeMillis();
    }

    /**
     * Compression ratio: sourceNodeCount / compressedNodeCount
     *
     * @return ratio of source nodes to compressed nodes (higher = better compression)
     */
    public float compressionRatio() {
        if (sourceNodeCount == 0 || compressedNodeCount == 0) {
            return 0.0f; // No meaningful compression ratio
        }
        return (float) sourceNodeCount / compressedNodeCount;
    }

    /**
     * Compression percentage: (1 - compressedNodeCount/sourceNodeCount) * 100
     *
     * @return percentage reduction in node count (0-100, higher = better)
     */
    public float compressionPercent() {
        if (sourceNodeCount == 0) {
            return 0.0f;
        }
        return (1.0f - (float) compressedNodeCount / sourceNodeCount) * 100.0f;
    }

    /**
     * Memory saved in bytes: (sourceNodeCount - compressedNodeCount) * BYTES_PER_NODE
     *
     * @return estimated bytes saved (negative = expansion)
     */
    public long memorySavedBytes() {
        return (long) (sourceNodeCount - compressedNodeCount) * BYTES_PER_NODE_REFERENCE;
    }

    /**
     * Memory saved as percentage of original: (sourceNodeCount - compressedNodeCount) / sourceNodeCount * 100
     *
     * @return percentage of original memory saved (0-100, higher = better)
     */
    public float memorySavedPercent() {
        if (sourceNodeCount == 0) {
            return 0.0f;
        }
        return (float) (sourceNodeCount - compressedNodeCount) / sourceNodeCount * 100.0f;
    }

    /**
     * Compression strategy used (derived from build context)
     *
     * @return strategy identifier string
     */
    public String strategy() {
        return "HASH_BASED"; // Default strategy for now
    }

    /**
     * Get actual timestamp (exposed for testing/serialization)
     *
     * @return timestamp in milliseconds
     */
    public long timestamp_value() {
        return timestamp();
    }
}

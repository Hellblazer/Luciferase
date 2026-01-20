/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.esvo.dag;

import java.time.Duration;
import java.util.Map;

/**
 * Comprehensive metadata about a constructed DAG.
 *
 * <p>Captures compression statistics, build configuration, and performance metrics
 * from DAG construction. This metadata is immutable and provides derived metrics
 * through computed methods.
 *
 * <h3>Compression Metrics</h3>
 * <ul>
 * <li>{@link #compressionRatio()}: Ratio of original to unique nodes (higher is better)</li>
 * <li>{@link #compressionPercent()}: Percentage reduction in node count</li>
 * <li>{@link #memorySavedBytes()}: Absolute memory savings in bytes</li>
 * </ul>
 *
 * <h3>Node Statistics</h3>
 * <ul>
 * <li>{@link #uniqueNodeCount}: Final deduplicated node count</li>
 * <li>{@link #originalNodeCount}: Original SVO node count before compression</li>
 * <li>{@link #sharedSubtreeCount}: Number of shared subtrees identified</li>
 * <li>{@link #sharingByDepth}: Sharing distribution across tree levels</li>
 * </ul>
 *
 * <h3>Build Configuration</h3>
 * <ul>
 * <li>{@link #strategy}: Compression strategy used during build</li>
 * <li>{@link #hashAlgorithm}: Hash algorithm for deduplication</li>
 * <li>{@link #buildTime}: Time spent constructing the DAG</li>
 * <li>{@link #sourceHash}: Hash of the source SVO structure</li>
 * </ul>
 *
 * @param uniqueNodeCount final number of unique nodes after deduplication
 * @param originalNodeCount original SVO node count before compression
 * @param maxDepth maximum tree depth
 * @param sharedSubtreeCount number of shared subtrees identified
 * @param sharingByDepth map of depth level to sharing count
 * @param buildTime duration of DAG construction
 * @param hashAlgorithm hash algorithm used for deduplication
 * @param strategy compression strategy used during build
 * @param sourceHash hash of the source SVO structure
 * @author hal.hildebrand
 * @see CompressionStrategy
 * @see HashAlgorithm
 */
public record DAGMetadata(
    int uniqueNodeCount,
    int originalNodeCount,
    int maxDepth,
    int sharedSubtreeCount,
    Map<Integer, Integer> sharingByDepth,
    Duration buildTime,
    HashAlgorithm hashAlgorithm,
    CompressionStrategy strategy,
    long sourceHash
) {
    /**
     * Calculate the compression ratio.
     *
     * <p>Formula: {@code originalNodeCount / uniqueNodeCount}
     *
     * <p>Examples:
     * <ul>
     * <li>5.0 = 5:1 compression (80% reduction)</li>
     * <li>2.0 = 2:1 compression (50% reduction)</li>
     * <li>1.0 = No compression</li>
     * </ul>
     *
     * @return compression ratio (≥ 1.0), or 1.0 if uniqueNodeCount is 0
     */
    public float compressionRatio() {
        return uniqueNodeCount > 0 ? (float) originalNodeCount / uniqueNodeCount : 1.0f;
    }

    /**
     * Calculate memory savings in bytes.
     *
     * <p>Formula: {@code (originalNodeCount - uniqueNodeCount) * 8 bytes}
     *
     * <p>Assumes 8 bytes per node (64-bit pointer size). Negative values indicate
     * expansion (shouldn't happen in normal operation).
     *
     * @return memory saved in bytes (can be negative in edge cases)
     */
    public long memorySavedBytes() {
        return (long) (originalNodeCount - uniqueNodeCount) * 8;
    }

    /**
     * Calculate compression percentage.
     *
     * <p>Formula: {@code (1 - uniqueNodeCount / originalNodeCount) * 100}
     *
     * <p>Examples:
     * <ul>
     * <li>80% = 5:1 compression ratio</li>
     * <li>50% = 2:1 compression ratio</li>
     * <li>0% = No compression</li>
     * </ul>
     *
     * @return compression percentage [0, 100], or 0.0 if originalNodeCount is 0
     */
    public float compressionPercent() {
        return originalNodeCount > 0 ? 100.0f * (1.0f - (float) uniqueNodeCount / originalNodeCount) : 0.0f;
    }
}

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
package com.hellblazer.luciferase.esvo.dag.pipeline;

import com.hellblazer.luciferase.esvo.dag.CompressionStrategy;
import com.hellblazer.luciferase.sparse.core.SparseVoxelData;

/**
 * Immutable record containing the results of a compression operation.
 *
 * <p>Provides access to both the original and compressed data, along with
 * metrics about the compression operation (ratio, memory saved, build time).
 *
 * @param originalData original uncompressed octree data
 * @param compressedData compressed octree data (DAG)
 * @param compressionRatio ratio of original to compressed node count (e.g., 5.0x)
 * @param buildTimeMs time taken to build the DAG in milliseconds
 * @param memorySavedBytes estimated memory saved by compression
 * @param strategy compression strategy used
 *
 * @author hal.hildebrand
 */
public record CompressionResult(
    SparseVoxelData originalData,
    CompressibleOctreeData compressedData,
    float compressionRatio,
    long buildTimeMs,
    long memorySavedBytes,
    CompressionStrategy strategy
) {

    /**
     * @return true if compression was applied (always true for this record)
     */
    public boolean isCompressed() {
        return true;
    }

    /**
     * Get metadata summary string.
     *
     * @return formatted metadata string with key compression metrics
     */
    public String getMetadata() {
        return String.format("Compression: %.1fx (original=%d, compressed=%d, time=%dms, strategy=%s)",
                             compressionRatio,
                             originalData.nodeCount(),
                             compressedData.nodeCount(),
                             buildTimeMs,
                             strategy);
    }

    /**
     * Get detailed compression information.
     *
     * @return multi-line string with detailed breakdown
     */
    public String getCompressionDetails() {
        var sb = new StringBuilder();
        sb.append("Compression Details:\n");
        sb.append("  Original nodes: ").append(originalData.nodeCount()).append("\n");
        sb.append("  Compressed nodes: ").append(compressedData.nodeCount()).append("\n");
        sb.append("  Ratio: ").append(String.format("%.1fx", compressionRatio)).append("\n");
        sb.append("  Memory saved: ").append(memorySavedBytes).append(" bytes\n");
        sb.append("  Build time: ").append(buildTimeMs).append("ms\n");
        sb.append("  Strategy: ").append(strategy).append("\n");
        return sb.toString();
    }
}

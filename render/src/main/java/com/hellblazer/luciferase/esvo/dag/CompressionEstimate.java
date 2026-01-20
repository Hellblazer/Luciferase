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

/**
 * Estimated compression metrics before DAG construction.
 *
 * <p>Provides quick estimates of DAG compression potential based on sampling
 * or heuristics, without performing full DAG construction.
 *
 * <p>Useful for deciding whether to build a DAG or use the original SVO structure.
 *
 * @param estimatedCompressionRatio estimated ratio of original to unique nodes
 * @param estimatedUniqueNodeCount estimated number of unique nodes after deduplication
 * @param estimatedMemorySaved estimated memory savings in bytes
 * @author hal.hildebrand
 * @see DAGMetadata
 */
public record CompressionEstimate(
    float estimatedCompressionRatio,
    long estimatedUniqueNodeCount,
    long estimatedMemorySaved
) {
}

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

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.sparse.core.SparseVoxelData;

/**
 * Marker interface for octree data that can be compressed or is already compressed.
 *
 * <p>This interface extends {@link SparseVoxelData} to allow both {@link com.hellblazer.luciferase.esvo.core.ESVOOctreeData}
 * (uncompressed SVO) and {@link com.hellblazer.luciferase.esvo.dag.DAGOctreeData} (compressed DAG)
 * to be treated uniformly in the compression pipeline.
 *
 * <h3>Design Rationale</h3>
 * <p>This marker interface enables polymorphic treatment of compressed and uncompressed octrees
 * without requiring modification of the base {@link SparseVoxelData} interface. The pipeline
 * can accept {@code CompressibleOctreeData} and handle both types transparently.
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * // Pipeline accepts either SVO or DAG
 * public void processOctree(CompressibleOctreeData data) {
 *     if (data instanceof DAGOctreeData) {
 *         // Already compressed
 *     } else {
 *         // Compress it
 *         var dag = DAGBuilder.from((ESVOOctreeData) data).build();
 *     }
 * }
 * }</pre>
 *
 * <h3>Implementations</h3>
 * <ul>
 * <li>{@link com.hellblazer.luciferase.esvo.core.ESVOOctreeData} - Uncompressed sparse voxel octree</li>
 * <li>{@link com.hellblazer.luciferase.esvo.dag.DAGOctreeData} - Compressed directed acyclic graph octree</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see SparseVoxelData
 * @see com.hellblazer.luciferase.esvo.core.ESVOOctreeData
 * @see com.hellblazer.luciferase.esvo.dag.DAGOctreeData
 */
public interface CompressibleOctreeData extends SparseVoxelData<ESVONodeUnified> {
    // Marker interface - no additional methods required
    // Implementations inherit all methods from SparseVoxelData
}

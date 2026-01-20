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

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.dag.pipeline.CompressibleOctreeData;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import com.hellblazer.luciferase.sparse.core.SparseVoxelData;

/**
 * DAG (Directed Acyclic Graph) Octree Data interface.
 *
 * <p>Extends {@link CompressibleOctreeData} with DAG-specific semantics:
 *
 * <h3>Absolute Pointer Addressing</h3>
 * <p>Unlike SVOs which use relative addressing, DAGs use absolute addressing:
 * <pre>{@code
 * // SVO (relative): childIndex = parentIdx + childPtr + sparseIdx
 * // DAG (absolute): childIndex = childPtr + sparseIdx
 * }</pre>
 *
 * <p>This enables node sharing (multiple parents pointing to the same subtree),
 * which is the foundation of DAG compression.
 *
 * <h3>Child Index Resolution</h3>
 * <p>The {@link #resolveChildIndex(int, ESVONodeUnified, int)} method computes
 * the absolute index of a child node:
 *
 * <ol>
 * <li>Extract {@code childPtr} from the parent node (absolute base address)</li>
 * <li>Compute {@code sparseIdx} = popcount(childMask & ((1 << octant) - 1))</li>
 * <li>Return {@code childPtr + sparseIdx}</li>
 * </ol>
 *
 * <p>Note that {@code parentIdx} is <b>not used</b> in the calculation for DAGs,
 * unlike SVOs where it's the base address.
 *
 * <h3>Compression Metadata</h3>
 * <p>DAGs provide compression statistics through {@link #getMetadata()}:
 * <ul>
 * <li>Compression ratio (originalNodeCount / uniqueNodeCount)</li>
 * <li>Memory saved (in bytes)</li>
 * <li>Sharing distribution by tree depth</li>
 * <li>Build time and configuration</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see SparseVoxelData
 * @see PointerAddressingMode
 * @see DAGMetadata
 */
public interface DAGOctreeData extends CompressibleOctreeData {

    /**
     * Get the addressing mode for this data structure.
     *
     * <p>DAGs always use {@link PointerAddressingMode#ABSOLUTE} addressing,
     * which enables node sharing (multiple parents pointing to the same child).
     *
     * @return {@link PointerAddressingMode#ABSOLUTE}
     */
    @Override
    default PointerAddressingMode getAddressingMode() {
        return PointerAddressingMode.ABSOLUTE;
    }

    /**
     * Resolve the child index for a given parent node and octant.
     *
     * <p>Uses absolute addressing: {@code childIndex = childPtr + sparseIdx}
     *
     * <p>The {@code parentIdx} parameter is <b>ignored</b> for DAGs (included
     * only for interface compatibility with SVO implementations).
     *
     * <p>This method inherits the default implementation from {@link SparseVoxelData}
     * which already handles absolute addressing based on {@link #getAddressingMode()}.
     *
     * @param parentIdx parent node index (ignored for DAGs)
     * @param node parent node
     * @param octant child octant [0-7]
     * @return absolute child index in the node pool
     * @throws IndexOutOfBoundsException if octant is not in [0, 7]
     */
    // Inherited default implementation from SparseVoxelData handles this correctly

    /**
     * Get comprehensive metadata about this DAG.
     *
     * <p>Includes compression statistics, build configuration, and performance metrics.
     *
     * @return DAG metadata (never null)
     */
    DAGMetadata getMetadata();

    /**
     * Get the compression ratio for this DAG.
     *
     * <p>Convenience method equivalent to {@code getMetadata().compressionRatio()}.
     *
     * @return compression ratio (originalNodeCount / uniqueNodeCount)
     */
    float getCompressionRatio();
}

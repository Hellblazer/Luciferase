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
package com.hellblazer.luciferase.esvt.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Container for ESVT octree data ready for GPU transfer.
 *
 * Contains the packed node array plus metadata about the structure.
 *
 * @author hal.hildebrand
 */
public record ESVTData(
    ESVTNodeUnified[] nodes,
    int rootType,
    int maxDepth,
    int leafCount,
    int internalCount
) {

    /**
     * Get the total number of nodes
     */
    public int nodeCount() {
        return nodes.length;
    }

    /**
     * Get the size in bytes of the node array
     */
    public int sizeInBytes() {
        return nodes.length * ESVTNodeUnified.SIZE_BYTES;
    }

    /**
     * Get the root node
     */
    public ESVTNodeUnified root() {
        return nodes.length > 0 ? nodes[0] : null;
    }

    /**
     * Pack all nodes into a ByteBuffer for GPU transfer.
     * Uses native byte order for performance.
     *
     * @return ByteBuffer containing packed node data
     */
    public ByteBuffer toByteBuffer() {
        var buffer = ByteBuffer.allocateDirect(sizeInBytes())
                               .order(ByteOrder.nativeOrder());
        for (var node : nodes) {
            node.writeTo(buffer);
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Create ESVTData from a ByteBuffer.
     *
     * @param buffer Buffer containing packed node data
     * @param nodeCount Number of nodes to read
     * @param rootType Root tetrahedron type
     * @param maxDepth Maximum tree depth
     * @param leafCount Number of leaf nodes
     * @param internalCount Number of internal nodes
     * @return ESVTData instance
     */
    public static ESVTData fromByteBuffer(ByteBuffer buffer, int nodeCount,
                                          int rootType, int maxDepth,
                                          int leafCount, int internalCount) {
        var nodes = new ESVTNodeUnified[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            nodes[i] = ESVTNodeUnified.readFrom(buffer);
        }
        return new ESVTData(nodes, rootType, maxDepth, leafCount, internalCount);
    }

    @Override
    public String toString() {
        return String.format("ESVTData[nodes=%d, rootType=%d, depth=%d, leaves=%d, internal=%d, size=%dKB]",
            nodeCount(), rootType, maxDepth, leafCount, internalCount, sizeInBytes() / 1024);
    }
}

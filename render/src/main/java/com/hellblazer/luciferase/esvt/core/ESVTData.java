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

import com.hellblazer.luciferase.render.inspector.SpatialData;
import com.hellblazer.luciferase.sparse.core.CoordinateSpace;
import com.hellblazer.luciferase.sparse.core.SparseVoxelData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Container for ESVT octree data ready for GPU transfer.
 *
 * Contains the packed node array, contour data, and metadata about the structure.
 *
 * <p>Contour data is stored separately from nodes for cache efficiency:
 * <ul>
 *   <li>Nodes store contourMask (4 bits) and contourPtr (20 bits)</li>
 *   <li>contourPtr indexes into the contour array</li>
 *   <li>Each set bit in contourMask corresponds to one contour value</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public record ESVTData(
    ESVTNodeUnified[] nodes,
    int[] contours,
    int[] farPointers,
    int rootType,
    int maxDepth,
    int leafCount,
    int internalCount,
    int gridResolution,           // Original voxel grid size (0 if not from voxels)
    int[] leafVoxelCoords         // Packed voxel coords: leafIdx*3+{0,1,2} = {x,y,z}
) implements SpatialData, SparseVoxelData<ESVTNodeUnified> {

    /**
     * Compact constructor that creates ESVTData without contours or far pointers.
     */
    public ESVTData(ESVTNodeUnified[] nodes, int rootType, int maxDepth,
                    int leafCount, int internalCount) {
        this(nodes, new int[0], new int[0], rootType, maxDepth, leafCount, internalCount, 0, new int[0]);
    }

    /**
     * Constructor with contours but no far pointers.
     */
    public ESVTData(ESVTNodeUnified[] nodes, int[] contours, int rootType, int maxDepth,
                    int leafCount, int internalCount) {
        this(nodes, contours, new int[0], rootType, maxDepth, leafCount, internalCount, 0, new int[0]);
    }

    /**
     * Constructor with far pointers but no voxel coordinate info (backward compatible).
     */
    public ESVTData(ESVTNodeUnified[] nodes, int[] contours, int[] farPointers,
                    int rootType, int maxDepth, int leafCount, int internalCount) {
        this(nodes, contours, farPointers, rootType, maxDepth, leafCount, internalCount, 0, new int[0]);
    }

    /**
     * Get the total number of nodes
     */
    public int nodeCount() {
        return nodes.length;
    }

    /**
     * Get the number of contour values
     */
    public int contourCount() {
        return contours != null ? contours.length : 0;
    }

    /**
     * Check if this data has contour information
     */
    public boolean hasContours() {
        return contours != null && contours.length > 0;
    }

    /**
     * Get the number of far pointers
     */
    public int farPointerCount() {
        return farPointers != null ? farPointers.length : 0;
    }

    /**
     * Check if this data has far pointers
     */
    public boolean hasFarPointers() {
        return farPointers != null && farPointers.length > 0;
    }

    /**
     * Get the coordinate space used by ESVT.
     *
     * @return {@link CoordinateSpace#UNIT_CUBE} ([0, 1] normalized space)
     */
    @Override
    public CoordinateSpace getCoordinateSpace() {
        return CoordinateSpace.UNIT_CUBE;
    }

    /**
     * Get far pointers array (implements {@link SparseVoxelData#getFarPointers()}).
     */
    @Override
    public int[] getFarPointers() {
        return farPointers != null ? farPointers : new int[0];
    }

    /**
     * Get contours array (implements {@link SparseVoxelData#getContours()}).
     */
    @Override
    public int[] getContours() {
        return contours != null ? contours : new int[0];
    }

    /**
     * Check if this data has voxel coordinate information
     */
    public boolean hasVoxelCoords() {
        return gridResolution > 0 && leafVoxelCoords != null && leafVoxelCoords.length > 0;
    }

    /**
     * Get the voxel X coordinate for a leaf.
     * @param leafIndex Index of the leaf (0 to leafCount-1)
     * @return Voxel X coordinate
     */
    public int getLeafVoxelX(int leafIndex) {
        return leafVoxelCoords[leafIndex * 3];
    }

    /**
     * Get the voxel Y coordinate for a leaf.
     * @param leafIndex Index of the leaf (0 to leafCount-1)
     * @return Voxel Y coordinate
     */
    public int getLeafVoxelY(int leafIndex) {
        return leafVoxelCoords[leafIndex * 3 + 1];
    }

    /**
     * Get the voxel Z coordinate for a leaf.
     * @param leafIndex Index of the leaf (0 to leafCount-1)
     * @return Voxel Z coordinate
     */
    public int getLeafVoxelZ(int leafIndex) {
        return leafVoxelCoords[leafIndex * 3 + 2];
    }

    /**
     * Resolve child pointer, handling far pointers if needed.
     *
     * @param node The node to get child pointer from
     * @return The actual child index in the nodes array
     */
    public int resolveChildPtr(ESVTNodeUnified node) {
        int ptr = node.getChildPtr();
        if (node.isFar() && farPointers != null && ptr < farPointers.length) {
            return farPointers[ptr];
        }
        return ptr;
    }

    /**
     * Get the size in bytes of the node array
     */
    public int nodeSizeInBytes() {
        return nodes.length * ESVTNodeUnified.SIZE_BYTES;
    }

    /**
     * Get the size in bytes of the contour array
     */
    public int contourSizeInBytes() {
        return contourCount() * 4; // 4 bytes per int
    }

    /**
     * Get the size in bytes of the far pointer array
     */
    public int farPointerSizeInBytes() {
        return farPointerCount() * 4; // 4 bytes per int
    }

    /**
     * Get the total size in bytes (nodes + contours + far pointers)
     */
    public int sizeInBytes() {
        return nodeSizeInBytes() + contourSizeInBytes() + farPointerSizeInBytes();
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
    public ByteBuffer nodesToByteBuffer() {
        var buffer = ByteBuffer.allocateDirect(nodeSizeInBytes())
                               .order(ByteOrder.nativeOrder());
        for (var node : nodes) {
            node.writeTo(buffer);
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Pack contours into a ByteBuffer for GPU transfer.
     * Uses native byte order for performance.
     *
     * @return ByteBuffer containing packed contour data (may be empty)
     */
    public ByteBuffer contoursToByteBuffer() {
        var buffer = ByteBuffer.allocateDirect(contourSizeInBytes())
                               .order(ByteOrder.nativeOrder());
        if (contours != null) {
            for (int contour : contours) {
                buffer.putInt(contour);
            }
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Legacy method for backward compatibility.
     * Returns node data only.
     */
    public ByteBuffer toByteBuffer() {
        return nodesToByteBuffer();
    }

    /**
     * Create ESVTData from ByteBuffers.
     *
     * @param nodeBuffer Buffer containing packed node data
     * @param contourBuffer Buffer containing packed contour data (may be null)
     * @param nodeCount Number of nodes to read
     * @param contourCount Number of contours to read
     * @param rootType Root tetrahedron type
     * @param maxDepth Maximum tree depth
     * @param leafCount Number of leaf nodes
     * @param internalCount Number of internal nodes
     * @return ESVTData instance
     */
    public static ESVTData fromByteBuffers(ByteBuffer nodeBuffer, ByteBuffer contourBuffer,
                                           int nodeCount, int contourCount,
                                           int rootType, int maxDepth,
                                           int leafCount, int internalCount) {
        var nodes = new ESVTNodeUnified[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            nodes[i] = ESVTNodeUnified.fromByteBuffer(nodeBuffer);
        }

        int[] contours = new int[contourCount];
        if (contourBuffer != null && contourCount > 0) {
            for (int i = 0; i < contourCount; i++) {
                contours[i] = contourBuffer.getInt();
            }
        }

        return new ESVTData(nodes, contours, rootType, maxDepth, leafCount, internalCount);
    }

    /**
     * Legacy method for backward compatibility.
     */
    public static ESVTData fromByteBuffer(ByteBuffer buffer, int nodeCount,
                                          int rootType, int maxDepth,
                                          int leafCount, int internalCount) {
        return fromByteBuffers(buffer, null, nodeCount, 0, rootType, maxDepth, leafCount, internalCount);
    }

    @Override
    public String toString() {
        return String.format("ESVTData[nodes=%d, contours=%d, farPtrs=%d, rootType=%d, depth=%d, leaves=%d, internal=%d, grid=%d, hasVoxelCoords=%b, size=%dKB]",
            nodeCount(), contourCount(), farPointerCount(), rootType, maxDepth, leafCount, internalCount, gridResolution, hasVoxelCoords(), sizeInBytes() / 1024);
    }

    /**
     * Create a new builder for ESVTData.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a builder initialized with values from an existing ESVTData.
     *
     * @param source the ESVTData to copy values from
     * @return new builder pre-populated with source values
     */
    public static Builder builder(ESVTData source) {
        return new Builder()
            .nodes(source.nodes())
            .contours(source.contours())
            .farPointers(source.farPointers())
            .rootType(source.rootType())
            .maxDepth(source.maxDepth())
            .leafCount(source.leafCount())
            .internalCount(source.internalCount())
            .gridResolution(source.gridResolution())
            .leafVoxelCoords(source.leafVoxelCoords());
    }

    /**
     * Builder for ESVTData.
     *
     * <p>Provides a fluent API for constructing ESVTData instances,
     * avoiding the 9-parameter constructor which is error-prone.
     *
     * <p>Example usage:
     * <pre>{@code
     * ESVTData data = ESVTData.builder()
     *     .nodes(nodeArray)
     *     .contours(contourArray)
     *     .rootType(0)
     *     .maxDepth(12)
     *     .leafCount(1000)
     *     .internalCount(500)
     *     .build();
     * }</pre>
     */
    public static final class Builder {
        private ESVTNodeUnified[] nodes = new ESVTNodeUnified[0];
        private int[] contours = new int[0];
        private int[] farPointers = new int[0];
        private int rootType = 0;
        private int maxDepth = 0;
        private int leafCount = 0;
        private int internalCount = 0;
        private int gridResolution = 0;
        private int[] leafVoxelCoords = new int[0];

        private Builder() {}

        public Builder nodes(ESVTNodeUnified[] nodes) {
            this.nodes = nodes != null ? nodes : new ESVTNodeUnified[0];
            return this;
        }

        public Builder contours(int[] contours) {
            this.contours = contours != null ? contours : new int[0];
            return this;
        }

        public Builder farPointers(int[] farPointers) {
            this.farPointers = farPointers != null ? farPointers : new int[0];
            return this;
        }

        public Builder rootType(int rootType) {
            this.rootType = rootType;
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        public Builder leafCount(int leafCount) {
            this.leafCount = leafCount;
            return this;
        }

        public Builder internalCount(int internalCount) {
            this.internalCount = internalCount;
            return this;
        }

        public Builder gridResolution(int gridResolution) {
            this.gridResolution = gridResolution;
            return this;
        }

        public Builder leafVoxelCoords(int[] leafVoxelCoords) {
            this.leafVoxelCoords = leafVoxelCoords != null ? leafVoxelCoords : new int[0];
            return this;
        }

        /**
         * Build the ESVTData instance.
         *
         * @return new ESVTData with the configured values
         */
        public ESVTData build() {
            return new ESVTData(nodes, contours, farPointers, rootType, maxDepth,
                               leafCount, internalCount, gridResolution, leafVoxelCoords);
        }
    }
}

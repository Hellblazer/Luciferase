package com.hellblazer.luciferase.esvo.core;

import com.hellblazer.luciferase.render.inspector.SpatialData;
import com.hellblazer.luciferase.sparse.core.CoordinateSpace;
import com.hellblazer.luciferase.sparse.core.SparseVoxelData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * ESVO Octree Data container for file I/O.
 *
 * <p>Manages octree nodes for serialization/deserialization. Implements both
 * {@link SpatialData} for inspector compatibility and {@link SparseVoxelData}
 * for the generic optimization pipeline.
 *
 * <p>Note: This implementation uses a Map-based storage internally but provides
 * array-based access through the {@link SparseVoxelData} interface.
 *
 * @author hal.hildebrand
 */
public class ESVOOctreeData implements SpatialData, SparseVoxelData<ESVONodeUnified> {
    private final Map<Integer, ESVONodeUnified> nodes;
    private final int maxSizeBytes;
    private int maxDepth = 0;
    private int leafCount = 0;
    private int internalCount = 0;

    /**
     * Far pointers array for nodes that need offsets larger than 14 bits.
     * When a node has isFar()=true, its childPtr is an INDEX into this array,
     * and farPointers[childPtr] contains the actual relative offset.
     */
    private int[] farPointers = new int[0];

    /**
     * Create octree data with specified max size in bytes
     */
    public ESVOOctreeData(int maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
        this.nodes = new HashMap<>();
    }
    
    /**
     * Set a node at the specified index
     */
    public void setNode(int index, ESVONodeUnified node) {
        if (node != null) {
            nodes.put(index, node);
        }
    }
    
    /**
     * Get a node at the specified index
     */
    public ESVONodeUnified getNode(int index) {
        return nodes.get(index);
    }
    
    /**
     * Check if a node exists at the specified index
     */
    public boolean hasNode(int index) {
        return nodes.containsKey(index);
    }
    
    /**
     * Get the number of nodes
     */
    public int getNodeCount() {
        return nodes.size();
    }
    
    /**
     * Get all node indices
     */
    public int[] getNodeIndices() {
        return nodes.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
    }
    
    /**
     * Clear all nodes
     */
    public void clear() {
        nodes.clear();
    }
    
    /**
     * Get the maximum size in bytes
     */
    public int getMaxSizeBytes() {
        return maxSizeBytes;
    }
    
    /**
     * Get the capacity (same as max size in bytes for compatibility)
     */
    public int getCapacity() {
        return maxSizeBytes;
    }

    // SpatialData interface implementation

    @Override
    public int nodeCount() {
        return nodes.size();
    }

    @Override
    public int maxDepth() {
        return maxDepth;
    }

    @Override
    public int leafCount() {
        return leafCount;
    }

    @Override
    public int internalCount() {
        return internalCount;
    }

    @Override
    public int sizeInBytes() {
        return nodes.size() * ESVONodeUnified.SIZE_BYTES;
    }

    /**
     * Set the maximum depth of this octree.
     * @param depth maximum depth
     */
    public void setMaxDepth(int depth) {
        this.maxDepth = depth;
    }

    /**
     * Set the leaf count for this octree.
     * @param count number of leaf nodes
     */
    public void setLeafCount(int count) {
        this.leafCount = count;
    }

    /**
     * Set the internal node count for this octree.
     * @param count number of internal nodes
     */
    public void setInternalCount(int count) {
        this.internalCount = count;
    }

    /**
     * Update statistics by analyzing the octree structure.
     * Call this after building the octree.
     */
    public void updateStatistics() {
        leafCount = 0;
        internalCount = 0;
        maxDepth = 0;

        for (var node : nodes.values()) {
            if (node.getChildMask() == 0) {
                leafCount++;
            } else {
                internalCount++;
            }
        }

        // Estimate depth from node count (log8 of node count)
        if (!nodes.isEmpty()) {
            maxDepth = Math.max(1, (int) Math.ceil(Math.log(nodes.size()) / Math.log(8)));
        }
    }

    /**
     * Get the far pointers array.
     * When a node has isFar()=true, its childPtr is an index into this array,
     * and farPointers[childPtr] contains the actual relative offset.
     *
     * @return the far pointers array, or empty array if no far pointers
     */
    public int[] getFarPointers() {
        return farPointers;
    }

    /**
     * Set the far pointers array.
     *
     * @param farPointers array of relative offsets for far pointer resolution
     */
    public void setFarPointers(int[] farPointers) {
        this.farPointers = farPointers != null ? farPointers : new int[0];
    }

    // === SparseVoxelData interface implementation ===

    /**
     * Get the coordinate space used by ESVO.
     *
     * @return {@link CoordinateSpace#OCTREE_SPACE} ([1, 2] normalized space)
     */
    @Override
    public CoordinateSpace getCoordinateSpace() {
        return CoordinateSpace.OCTREE_SPACE;
    }

    /**
     * Get all nodes as an array.
     *
     * <p>Nodes are returned in index order. This method creates a new array
     * on each call; for frequent access, consider caching the result.
     *
     * @return array of nodes in index order
     */
    @Override
    public ESVONodeUnified[] nodes() {
        var indices = getNodeIndices();
        var result = new ESVONodeUnified[indices.length];
        for (int i = 0; i < indices.length; i++) {
            result[i] = nodes.get(indices[i]);
        }
        return result;
    }

    /**
     * Pack all nodes into a ByteBuffer for GPU transfer.
     *
     * <p>Uses native byte order for performance.
     *
     * @return ByteBuffer containing packed node data
     */
    @Override
    public ByteBuffer nodesToByteBuffer() {
        var nodeArray = nodes();
        var buffer = ByteBuffer.allocateDirect(nodeArray.length * ESVONodeUnified.SIZE_BYTES)
                               .order(ByteOrder.nativeOrder());
        for (var node : nodeArray) {
            node.writeTo(buffer);
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Get the contours array (empty for basic ESVO data).
     *
     * @return empty array (contours stored separately in ESVO)
     */
    @Override
    public int[] getContours() {
        return new int[0]; // ESVO contours are stored separately
    }
}
package com.hellblazer.luciferase.esvo.core;

import com.hellblazer.luciferase.render.inspector.SpatialData;

import java.util.HashMap;
import java.util.Map;

/**
 * ESVO Octree Data container for file I/O
 * Manages octree nodes for serialization/deserialization
 */
public class ESVOOctreeData implements SpatialData {
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
}
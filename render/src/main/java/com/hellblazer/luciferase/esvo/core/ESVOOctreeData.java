package com.hellblazer.luciferase.esvo.core;

import java.util.HashMap;
import java.util.Map;

/**
 * ESVO Octree Data container for file I/O
 * Manages octree nodes for serialization/deserialization
 */
public class ESVOOctreeData {
    private final Map<Integer, ESVOOctreeNode> nodes;
    private final int maxSizeBytes;
    
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
    public void setNode(int index, ESVOOctreeNode node) {
        if (node != null) {
            nodes.put(index, node);
        }
    }
    
    /**
     * Get a node at the specified index
     */
    public ESVOOctreeNode getNode(int index) {
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
}
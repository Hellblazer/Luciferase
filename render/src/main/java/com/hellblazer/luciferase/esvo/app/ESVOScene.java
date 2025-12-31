package com.hellblazer.luciferase.esvo.app;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.io.ESVODeserializer;

import javax.vecmath.Vector3f;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Scene management system for ESVO octrees
 * Handles multiple octrees, spatial bounds, and real-time updates
 */
public class ESVOScene {
    
    private final Map<String, ESVOOctreeData> octrees = new ConcurrentHashMap<>();
    private final Set<String> dirtyOctrees = new CopyOnWriteArraySet<>();
    private final ESVODeserializer deserializer = new ESVODeserializer();
    
    // Scene bounds
    private Vector3f minBounds = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
    private Vector3f maxBounds = new Vector3f(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE);
    private boolean boundsValid = false;
    
    // Update callback
    private Runnable updateCallback;
    
    public ESVOScene() {
        // Initialize with default ESVO coordinate space bounds [0,1]
        minBounds.set(0.0f, 0.0f, 0.0f);
        maxBounds.set(1.0f, 1.0f, 1.0f);
    }
    
    /**
     * Load an octree directly into the scene
     */
    public void loadOctree(String name, ESVOOctreeData octree) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Octree name cannot be null or empty");
        }
        
        octrees.put(name, octree);
        invalidateBounds();
        notifyUpdate();
    }
    
    /**
     * Load an octree from file
     */
    public void loadOctreeFromFile(String name, Path filePath) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Octree name cannot be null or empty");
        }
        
        ESVOOctreeData octree = deserializer.deserialize(filePath);
        loadOctree(name, octree);
    }
    
    /**
     * Remove an octree from the scene
     */
    public void removeOctree(String name) {
        if (octrees.remove(name) != null) {
            dirtyOctrees.remove(name);
            invalidateBounds();
            notifyUpdate();
        }
    }
    
    /**
     * Get an octree by name
     */
    public ESVOOctreeData getOctree(String name) {
        return octrees.get(name);
    }
    
    /**
     * Check if an octree exists
     */
    public boolean hasOctree(String name) {
        return octrees.containsKey(name);
    }
    
    /**
     * Get all octree names
     */
    public Set<String> getOctreeNames() {
        return new HashSet<>(octrees.keySet());
    }
    
    /**
     * Get the number of octrees in the scene
     */
    public int getOctreeCount() {
        return octrees.size();
    }
    
    /**
     * Clear all octrees from the scene
     */
    public void clear() {
        octrees.clear();
        dirtyOctrees.clear();
        invalidateBounds();
        notifyUpdate();
    }
    
    /**
     * Mark an octree as dirty (needs update)
     */
    public void markDirty(String name) {
        if (octrees.containsKey(name)) {
            dirtyOctrees.add(name);
            invalidateBounds();
            notifyUpdate();
        }
    }
    
    /**
     * Check if an octree is marked as dirty
     */
    public boolean isDirty(String name) {
        return dirtyOctrees.contains(name);
    }
    
    /**
     * Clear dirty flag for an octree
     */
    public void clearDirty(String name) {
        dirtyOctrees.remove(name);
    }
    
    /**
     * Get all dirty octree names
     */
    public Set<String> getDirtyOctrees() {
        return new HashSet<>(dirtyOctrees);
    }
    
    /**
     * Set update callback for real-time notifications
     */
    public void setUpdateCallback(Runnable callback) {
        this.updateCallback = callback;
    }
    
    /**
     * Get scene minimum bounds
     */
    public Vector3f getMinBounds() {
        if (!boundsValid) {
            calculateBounds();
        }
        return new Vector3f(minBounds);
    }
    
    /**
     * Get scene maximum bounds
     */
    public Vector3f getMaxBounds() {
        if (!boundsValid) {
            calculateBounds();
        }
        return new Vector3f(maxBounds);
    }
    
    /**
     * Get scene center point
     */
    public Vector3f getCenter() {
        Vector3f min = getMinBounds();
        Vector3f max = getMaxBounds();
        
        return new Vector3f(
            (min.x + max.x) * 0.5f,
            (min.y + max.y) * 0.5f,
            (min.z + max.z) * 0.5f
        );
    }
    
    /**
     * Get scene diagonal size
     */
    public float getSize() {
        Vector3f min = getMinBounds();
        Vector3f max = getMaxBounds();
        
        Vector3f diagonal = new Vector3f();
        diagonal.sub(max, min);
        return diagonal.length();
    }
    
    /**
     * Check if a point is within scene bounds
     */
    public boolean contains(Vector3f point) {
        Vector3f min = getMinBounds();
        Vector3f max = getMaxBounds();
        
        return point.x >= min.x && point.x <= max.x &&
               point.y >= min.y && point.y <= max.y &&
               point.z >= min.z && point.z <= max.z;
    }
    
    /**
     * Get statistics about the scene
     */
    public SceneStatistics getStatistics() {
        int totalNodes = 0;
        int totalNonEmptyNodes = 0;
        int totalMemoryUsage = 0;
        
        for (ESVOOctreeData octree : octrees.values()) {
            int[] nodeIndices = octree.getNodeIndices();
            totalNodes += nodeIndices.length;
            
            for (int index : nodeIndices) {
                ESVONodeUnified node = octree.getNode(index);
                if (node != null && node.getChildMask() != 0) {
                    totalNonEmptyNodes++;
                }
            }
            
            // Estimate memory usage (simplified)
            totalMemoryUsage += nodeIndices.length * 12; // 12 bytes per node
        }
        
        return new SceneStatistics(
            octrees.size(),
            totalNodes,
            totalNonEmptyNodes,
            totalMemoryUsage,
            dirtyOctrees.size()
        );
    }
    
    /**
     * Perform scene validation
     */
    public List<String> validate() {
        List<String> issues = new ArrayList<>();
        
        for (Map.Entry<String, ESVOOctreeData> entry : octrees.entrySet()) {
            String name = entry.getKey();
            ESVOOctreeData octree = entry.getValue();
            
            if (octree == null) {
                issues.add("Octree '" + name + "' is null");
                continue;
            }
            
            // Check for valid root node
            ESVONodeUnified root = octree.getNode(0);
            if (root == null) {
                issues.add("Octree '" + name + "' has no root node");
            }
            
            // Check node consistency
            int[] nodeIndices = octree.getNodeIndices();
            for (int index : nodeIndices) {
                ESVONodeUnified node = octree.getNode(index);
                if (node == null) {
                    issues.add("Octree '" + name + "' has null node at index " + index);
                }
            }
        }
        
        return issues;
    }
    
    // Private helper methods
    
    private void invalidateBounds() {
        boundsValid = false;
    }
    
    private void calculateBounds() {
        if (octrees.isEmpty()) {
            // Default to ESVO coordinate space [0,1]
            minBounds.set(0.0f, 0.0f, 0.0f);
            maxBounds.set(1.0f, 1.0f, 1.0f);
        } else {
            // Calculate bounds from all octrees
            minBounds.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
            maxBounds.set(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE);

            // For ESVO, octrees use [0,1] coordinate space
            // We extend bounds to encompass all octree data
            for (ESVOOctreeData octree : octrees.values()) {
                // Each octree covers the [0,1] space
                // In a more sophisticated implementation, we would calculate
                // actual spatial extent based on non-empty nodes

                updateBounds(0.0f, 0.0f, 0.0f);
                updateBounds(1.0f, 1.0f, 1.0f);
            }
        }
        
        boundsValid = true;
    }
    
    private void updateBounds(float x, float y, float z) {
        minBounds.x = Math.min(minBounds.x, x);
        minBounds.y = Math.min(minBounds.y, y);
        minBounds.z = Math.min(minBounds.z, z);
        
        maxBounds.x = Math.max(maxBounds.x, x);
        maxBounds.y = Math.max(maxBounds.y, y);
        maxBounds.z = Math.max(maxBounds.z, z);
    }
    
    private void notifyUpdate() {
        if (updateCallback != null) {
            updateCallback.run();
        }
    }
    
    /**
     * Scene statistics data class
     */
    public static class SceneStatistics {
        public final int octreeCount;
        public final int totalNodes;
        public final int nonEmptyNodes;
        public final int memoryUsageBytes;
        public final int dirtyOctrees;
        
        public SceneStatistics(int octreeCount, int totalNodes, int nonEmptyNodes, 
                             int memoryUsageBytes, int dirtyOctrees) {
            this.octreeCount = octreeCount;
            this.totalNodes = totalNodes;
            this.nonEmptyNodes = nonEmptyNodes;
            this.memoryUsageBytes = memoryUsageBytes;
            this.dirtyOctrees = dirtyOctrees;
        }
        
        @Override
        public String toString() {
            return String.format("SceneStats{octrees=%d, nodes=%d/%d, memory=%dB, dirty=%d}",
                               octreeCount, nonEmptyNodes, totalNodes, memoryUsageBytes, dirtyOctrees);
        }
    }
}
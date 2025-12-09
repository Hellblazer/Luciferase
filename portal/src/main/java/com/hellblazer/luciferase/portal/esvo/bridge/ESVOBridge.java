/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.portal.esvo.bridge;

import com.hellblazer.luciferase.esvo.app.ESVOPerformanceMonitor;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.esvo.traversal.RayTraversalUtils;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal.DeepTraversalResult;
import com.hellblazer.luciferase.geometry.Point3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import java.util.List;

/**
 * Bridge between portal visualization layer and render module ESVO components.
 * Provides simplified interface for octree construction and ray casting operations.
 * Handles coordinate transformations and data structure conversions.
 * 
 * @author hal.hildebrand
 */
public class ESVOBridge {
    private static final Logger log = LoggerFactory.getLogger(ESVOBridge.class);
    
    private final ESVOPerformanceMonitor performanceMonitor;
    private ESVOOctreeData currentOctree;
    private int currentMaxDepth;
    
    /**
     * Create a new ESVO bridge with performance monitoring.
     */
    public ESVOBridge() {
        this.performanceMonitor = new ESVOPerformanceMonitor();
        log.debug("ESVOBridge initialized with performance monitoring");
    }
    
    /**
     * Build ESVO octree from voxel data.
     * Uses OctreeBuilder.buildFromVoxels() from render module.
     * 
     * @param voxels List of voxel coordinates (Point3i from common module)
     * @param maxDepth Maximum octree depth (1-15)
     * @return ESVO octree data structure
     * @throws IllegalArgumentException if maxDepth is out of range or voxels is null
     */
    public ESVOOctreeData buildOctree(List<Point3i> voxels, int maxDepth) {
        if (voxels == null) {
            throw new IllegalArgumentException("Voxels list cannot be null");
        }
        
        if (maxDepth < 1 || maxDepth > 15) {
            throw new IllegalArgumentException("Max depth must be between 1 and 15, got: " + maxDepth);
        }
        
        log.debug("Building octree from {} voxels at depth {}", voxels.size(), maxDepth);
        
        var startTime = System.nanoTime();
        
        // Use OctreeBuilder.buildFromVoxels() from render module
        // Point3i is from com.hellblazer.luciferase.geometry package in common module
        try (var builder = new OctreeBuilder(maxDepth)) {
            currentOctree = builder.buildFromVoxels(voxels, maxDepth);
            currentMaxDepth = maxDepth;
            
            var buildTime = (System.nanoTime() - startTime) / 1_000_000.0; // Convert to ms
            log.debug("Octree built in {:.2f}ms with {} nodes", buildTime, currentOctree.getNodeCount());
            
            return currentOctree;
        } catch (Exception e) {
            log.error("Failed to build octree", e);
            throw new RuntimeException("Octree construction failed", e);
        }
    }
    
    /**
     * Cast ray through octree and return traversal data.
     * Uses RayTraversalUtils for simplified ray creation.
     * 
     * @param octree ESVO octree data
     * @param cameraOrigin Camera position in world space [0,1]
     * @param cameraDirection Camera look direction
     * @return Traversal result with hit information
     * @throws IllegalArgumentException if octree is null or directions are invalid
     */
    public DeepTraversalResult castRay(ESVOOctreeData octree, 
                                       Vector3f cameraOrigin,
                                       Vector3f cameraDirection) {
        if (octree == null) {
            throw new IllegalArgumentException("Octree cannot be null");
        }
        
        if (cameraOrigin == null || cameraDirection == null) {
            throw new IllegalArgumentException("Camera origin and direction cannot be null");
        }
        
        // Validate direction vector (should not be zero)
        if (cameraDirection.lengthSquared() < 1e-6f) {
            throw new IllegalArgumentException("Camera direction vector is too small (near-zero)");
        }
        
        // Create ray using RayTraversalUtils (handles [0,1] → [1,2] transformation)
        var ray = RayTraversalUtils.createRayFromCamera(cameraOrigin, cameraDirection);
        
        // Create octree for traversal
        var multiLevelOctree = RayTraversalUtils.createOctreeFromData(octree, currentMaxDepth);
        
        // Traverse and return result (static method)
        var result = StackBasedRayTraversal.traverse(ray, multiLevelOctree);
        
        // Record traversal statistics
        if (result != null) {
            performanceMonitor.recordTraversal(1, result.traversalDepth, result.hit ? 1 : 0);
        }
        
        return result;
    }
    
    /**
     * Cast ray through the current octree.
     * Convenience method using the most recently built octree.
     * 
     * @param cameraOrigin Camera position in world space [0,1]
     * @param cameraDirection Camera look direction
     * @return Traversal result with hit information
     * @throws IllegalStateException if no octree has been built yet
     */
    public DeepTraversalResult castRay(Vector3f cameraOrigin, Vector3f cameraDirection) {
        if (currentOctree == null) {
            throw new IllegalStateException("No octree available. Call buildOctree() first.");
        }
        
        return castRay(currentOctree, cameraOrigin, cameraDirection);
    }
    
    /**
     * Get the currently active octree.
     * 
     * @return Current octree data, or null if none has been built
     */
    public ESVOOctreeData getCurrentOctree() {
        return currentOctree;
    }
    
    /**
     * Get the maximum depth of the current octree.
     * 
     * @return Current max depth, or 0 if no octree has been built
     */
    public int getCurrentMaxDepth() {
        return currentMaxDepth;
    }
    
    /**
     * Get performance monitor for metrics collection.
     * 
     * @return Performance monitor instance
     */
    public ESVOPerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }
    
    /**
     * Clear the current octree and reset state.
     */
    public void clear() {
        currentOctree = null;
        currentMaxDepth = 0;
        performanceMonitor.reset();
        log.debug("ESVOBridge cleared");
    }
    
    /**
     * Check if an octree is currently loaded.
     * 
     * @return True if octree is available
     */
    public boolean hasOctree() {
        return currentOctree != null;
    }
    
    /**
     * Get statistics about the current octree.
     * 
     * @return Octree statistics as formatted string
     */
    public String getOctreeStats() {
        if (currentOctree == null) {
            return "No octree loaded";
        }
        
        return String.format("Octree: depth=%d, nodes=%d", 
                           currentMaxDepth, 
                           currentOctree.getNodeCount());
    }
}

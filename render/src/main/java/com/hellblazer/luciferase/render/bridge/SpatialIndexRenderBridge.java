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
package com.hellblazer.luciferase.render.bridge;

import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.core.VoxelData;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Bridge between lucien spatial indices and the render module.
 * Converts spatial index data into voxel representations for rendering.
 * 
 * @param <ID> The type of EntityID used
 * @param <Content> The type of content stored
 */
public class SpatialIndexRenderBridge<ID extends EntityID, Content> {
    
    private static final Logger log = Logger.getLogger(SpatialIndexRenderBridge.class.getName());
    
    private final Octree<ID, Content> spatialIndex;
    private final Map<MortonKey, EnhancedVoxelOctreeNode> nodeCache;
    private final float worldSize;
    private final byte maxDepth = MortonCurve.MAX_REFINEMENT_LEVEL;
    
    // Rendering configuration
    private int defaultMaterialId = 1;
    private int defaultColor = 0xFFFFFFFF; // White by default
    
    public SpatialIndexRenderBridge(Octree<ID, Content> spatialIndex, float worldSize) {
        this.spatialIndex = spatialIndex;
        this.worldSize = worldSize;
        this.nodeCache = new ConcurrentHashMap<>();
    }
    
    /**
     * Convert the spatial index into a voxel octree for rendering.
     * 
     * @return Root node of the voxel octree
     */
    public EnhancedVoxelOctreeNode buildVoxelOctree() {
        log.info("Building voxel octree from spatial index with " + spatialIndex.size() + " entities");
        
        // Clear cache
        nodeCache.clear();
        
        // Create root node
        var rootBounds = new float[]{0, 0, 0, worldSize, worldSize, worldSize};
        var rootNode = new EnhancedVoxelOctreeNode(
            rootBounds[0], rootBounds[1], rootBounds[2],
            rootBounds[3], rootBounds[4], rootBounds[5],
            0, 0
        );
        
        // Process all spatial nodes by traversing the index
        // Since we can't directly access all entities, we'll use region query
        // Cube takes origin and extent (size), not min/max
        var allRegion = new Spatial.Cube(0, 0, 0, worldSize);
        var allEntities = spatialIndex.entitiesInRegion(allRegion);
        var processedKeys = new HashSet<MortonKey>();
        
        for (var entityId : allEntities) {
            var position = spatialIndex.getEntityPosition(entityId);
            if (position != null) {
                var mortonKey = spatialIndex.calculateSpatialIndex(position, maxDepth);
                if (!processedKeys.contains(mortonKey)) {
                    processedKeys.add(mortonKey);
                    var voxelNode = createVoxelNodeForPosition(mortonKey, position);
                    nodeCache.put(mortonKey, voxelNode);
                }
            }
        }
        
        // Build hierarchy
        buildHierarchy(rootNode);
        
        log.info("Voxel octree built with " + nodeCache.size() + " nodes");
        return rootNode;
    }
    
    /**
     * Update voxel representation for a specific region.
     * 
     * @param region The region to update
     * @return Updated voxel nodes
     */
    public List<EnhancedVoxelOctreeNode> updateRegion(BoundingBox region) {
        var updatedNodes = new ArrayList<EnhancedVoxelOctreeNode>();
        
        // Create a Spatial.Cube for the region
        // Cube takes origin and extent (size), not min/max
        float extent = Math.max(
            region.maxX - region.minX,
            Math.max(region.maxY - region.minY, region.maxZ - region.minZ)
        );
        var cube = new Spatial.Cube(region.minX, region.minY, region.minZ, extent);
        
        // Find all spatial nodes in the region
        var entities = spatialIndex.entitiesInRegion(cube);
        
        // Update corresponding voxel nodes
        for (var entity : entities) {
            var position = spatialIndex.getEntityPosition(entity);
            if (position != null) {
                var mortonKey = spatialIndex.calculateSpatialIndex(position, maxDepth);
                var voxelNode = createVoxelNodeForPosition(mortonKey, position);
                nodeCache.put(mortonKey, voxelNode);
                updatedNodes.add(voxelNode);
            }
        }
        
        return updatedNodes;
    }
    
    /**
     * Get voxel data for rendering a frustum view.
     * 
     * @param frustum The view frustum
     * @param maxNodes Maximum number of nodes to return
     * @return List of voxel nodes within the frustum
     */
    public List<EnhancedVoxelOctreeNode> getVisibleNodes(Frustum frustum, int maxNodes) {
        var visibleNodes = new ArrayList<EnhancedVoxelOctreeNode>();
        
        // Use spatial index frustum culling
        // For now, use a region query as a workaround since Frustum3D needs 6 Plane3D objects
        // TODO: Properly construct Frustum3D with Plane3D objects
        var worldRegion = new Spatial.Cube(0, 0, 0, worldSize);
        var entitiesInFrustum = spatialIndex.entitiesInRegion(worldRegion);
        
        // Convert to voxel nodes
        var processedKeys = new HashSet<MortonKey>();
        for (var entity : entitiesInFrustum) {
            if (visibleNodes.size() >= maxNodes) {
                break;
            }
            
            var position = spatialIndex.getEntityPosition(entity);
            if (position != null) {
                var mortonKey = spatialIndex.calculateSpatialIndex(position, maxDepth);
                
                if (!processedKeys.contains(mortonKey)) {
                    processedKeys.add(mortonKey);
                    
                    var voxelNode = nodeCache.get(mortonKey);
                    if (voxelNode == null) {
                        voxelNode = createVoxelNodeForPosition(mortonKey, position);
                        nodeCache.put(mortonKey, voxelNode);
                    }
                    
                    if (voxelNode != null) {
                        visibleNodes.add(voxelNode);
                    }
                }
            }
        }
        
        return visibleNodes;
    }
    
    /**
     * Set material properties for entities based on their content.
     * 
     * @param materialMapper Function to map content to material ID
     */
    public void setMaterialMapper(java.util.function.Function<Content, Integer> materialMapper) {
        // Update material IDs based on entity content
        // Use region query to get all entities
        var allRegion = new Spatial.Cube(0, 0, 0, worldSize);
        var allEntities = spatialIndex.entitiesInRegion(allRegion);
        
        for (var entityId : allEntities) {
            var position = spatialIndex.getEntityPosition(entityId);
            if (position != null) {
                var mortonKey = spatialIndex.calculateSpatialIndex(position, (byte)maxDepth);
                var voxelNode = nodeCache.get(mortonKey);
                if (voxelNode != null) {
                    var entity = spatialIndex.getEntity(entityId);
                    if (entity != null) {
                    var materialId = materialMapper.apply(entity);
                    voxelNode.setMaterialId(materialId);
                    }
                }
            }
        }
    }
    
    // ===== Private Helper Methods =====
    
    private EnhancedVoxelOctreeNode createVoxelNodeForPosition(MortonKey mortonKey, Point3f position) {
        var coords = MortonCurve.decode(mortonKey.getMortonCode());
        var level = mortonKey.getLevel();
        var cellSize = Constants.lengthAtLevel(level);
        
        var node = new EnhancedVoxelOctreeNode(
            coords[0], coords[1], coords[2],
            coords[0] + cellSize, coords[1] + cellSize, coords[2] + cellSize,
            level, (int)(mortonKey.getMortonCode() & 0xFFFFFFFF)
        );
        
        // Set voxel data - extract RGB from packed color
        int red = (defaultColor >> 16) & 0xFF;
        int green = (defaultColor >> 8) & 0xFF;
        int blue = defaultColor & 0xFF;
        int opacity = (defaultColor >> 24) & 0xFF;
        
        var voxelData = new VoxelData(red, green, blue, opacity, defaultMaterialId);
        node.setVoxelData(voxelData);
        node.setVoxelCount(1);
        
        return node;
    }
    
    private void buildHierarchy(EnhancedVoxelOctreeNode root) {
        // Build parent-child relationships
        for (var entry : nodeCache.entrySet()) {
            var mortonKey = entry.getKey();
            var node = entry.getValue();
            
            if (mortonKey.getLevel() > 0) {
                // Find parent
                var parentKey = findParentKey(mortonKey);
                var parent = nodeCache.get(parentKey);
                
                if (parent == null) {
                    // Create parent if it doesn't exist
                    parent = createParentNode(parentKey);
                    nodeCache.put(parentKey, parent);
                }
                
                // Add as child
                var childIndex = getChildIndex(mortonKey);
                parent.setChild(childIndex, node);
            }
        }
    }
    
    private MortonKey findParentKey(MortonKey childKey) {
        var parentLevel = (byte)(childKey.getLevel() - 1);
        var parentCode = childKey.getMortonCode() >>> 3; // Remove last 3 bits (unsigned shift)
        return new MortonKey(parentCode, parentLevel);
    }
    
    private EnhancedVoxelOctreeNode createParentNode(MortonKey parentKey) {
        var coords = MortonCurve.decode(parentKey.getMortonCode());
        var level = parentKey.getLevel();
        var cellSize = Constants.lengthAtLevel(level);
        
        return new EnhancedVoxelOctreeNode(
            coords[0], coords[1], coords[2],
            coords[0] + cellSize, coords[1] + cellSize, coords[2] + cellSize,
            level, (int)(parentKey.getMortonCode() & 0xFFFFFFFF)
        );
    }
    
    private int getChildIndex(MortonKey childKey) {
        // Extract last 3 bits to get child index (0-7)
        return (int)(childKey.getMortonCode() & 0x7);
    }
    
    // ===== Helper Classes =====
    
    public static class BoundingBox {
        public final float minX, minY, minZ;
        public final float maxX, maxY, maxZ;
        
        public BoundingBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }
    
    public static class Frustum {
        private final float[][] planes;
        private final Point3f origin;
        
        public Frustum(float[][] planes, Point3f origin) {
            this.planes = planes;
            this.origin = origin;
        }
        
        public float[][] getPlanes() { return planes; }
        public Point3f getOrigin() { return origin; }
    }
}
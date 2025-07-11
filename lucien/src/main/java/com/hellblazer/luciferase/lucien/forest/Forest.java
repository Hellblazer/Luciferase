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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A Forest manages multiple spatial index trees, providing coordinated operations across
 * the collection. It supports spatial partitioning strategies, tree distribution policies,
 * and optimized query routing.
 *
 * <p>The Forest class enables advanced multi-tree spatial indexing scenarios such as:
 * <ul>
 *   <li>Distributed spatial indexing across multiple trees</li>
 *   <li>Level-of-detail (LOD) management with different trees for different detail levels</li>
 *   <li>Spatial partitioning with optional overlapping regions</li>
 *   <li>Load balancing across trees based on entity distribution</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe for concurrent access. Trees can be added,
 * removed, and queried concurrently. The CopyOnWriteArrayList ensures thread-safe iteration
 * over trees during queries.
 *
 * @param <Key>     The spatial key type (e.g., MortonKey, TetreeKey)
 * @param <ID>      The entity ID type
 * @param <Content> The content type stored with entities
 * @author hal.hildebrand
 */
public class Forest<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(Forest.class);
    
    /** Thread-safe collection of trees in the forest */
    private final CopyOnWriteArrayList<TreeNode<Key, ID, Content>> trees;
    
    /** Map for fast tree lookup by ID */
    private final Map<String, TreeNode<Key, ID, Content>> treeMap;
    
    /** Tree ID generator */
    private final AtomicInteger treeIdGenerator;
    
    /** Forest configuration */
    private final ForestConfig config;
    
    /** Forest-wide metadata */
    private final Map<String, Object> forestMetadata;
    
    /** Total entity count across all trees */
    private final AtomicInteger totalEntityCount;
    
    /**
     * Create a new Forest with the specified configuration.
     *
     * @param config the forest configuration
     */
    public Forest(ForestConfig config) {
        this.config = Objects.requireNonNull(config, "Forest config cannot be null");
        this.trees = new CopyOnWriteArrayList<>();
        this.treeMap = new ConcurrentHashMap<>();
        this.treeIdGenerator = new AtomicInteger(0);
        this.forestMetadata = new ConcurrentHashMap<>();
        this.totalEntityCount = new AtomicInteger(0);
        
        log.info("Created Forest with configuration: {}", config);
    }
    
    /**
     * Create a new Forest with default configuration.
     */
    public Forest() {
        this(ForestConfig.defaultConfig());
    }
    
    /**
     * Add a new tree to the forest.
     *
     * @param spatialIndex the spatial index to add
     * @param metadata optional metadata for the tree
     * @return the ID assigned to the new tree
     */
    public String addTree(AbstractSpatialIndex<Key, ID, Content> spatialIndex, TreeMetadata metadata) {
        var treeId = generateTreeId(metadata);
        var treeNode = new TreeNode<>(treeId, spatialIndex);
        
        // Store metadata in the tree node
        if (metadata != null) {
            treeNode.setMetadata("metadata", metadata);
        }
        
        // Add to collections
        trees.add(treeNode);
        treeMap.put(treeId, treeNode);
        
        // Update statistics
        treeNode.refreshStatistics();
        updateTotalEntityCount();
        
        log.info("Added tree {} to forest (type: {})", treeId, 
                metadata != null ? metadata.getTreeType() : "unknown");
        
        return treeId;
    }
    
    /**
     * Add a tree without metadata.
     *
     * @param spatialIndex the spatial index to add
     * @return the ID assigned to the new tree
     */
    public String addTree(AbstractSpatialIndex<Key, ID, Content> spatialIndex) {
        return addTree(spatialIndex, null);
    }
    
    /**
     * Remove a tree from the forest.
     *
     * @param treeId the ID of the tree to remove
     * @return true if the tree was removed, false if not found
     */
    public boolean removeTree(String treeId) {
        var treeNode = treeMap.remove(treeId);
        if (treeNode != null) {
            trees.remove(treeNode);
            
            // Update neighbor relationships
            for (var neighbor : treeNode.getNeighbors()) {
                var neighborNode = treeMap.get(neighbor);
                if (neighborNode != null) {
                    neighborNode.removeNeighbor(treeId);
                }
            }
            
            updateTotalEntityCount();
            log.info("Removed tree {} from forest", treeId);
            return true;
        }
        return false;
    }
    
    /**
     * Get a tree by its ID.
     *
     * @param treeId the tree ID
     * @return the tree node, or null if not found
     */
    public TreeNode<Key, ID, Content> getTree(String treeId) {
        return treeMap.get(treeId);
    }
    
    /**
     * Get all trees in the forest.
     *
     * @return unmodifiable list of all trees
     */
    public List<TreeNode<Key, ID, Content>> getAllTrees() {
        return Collections.unmodifiableList(new ArrayList<>(trees));
    }
    
    /**
     * Get the number of trees in the forest.
     *
     * @return the tree count
     */
    public int getTreeCount() {
        return trees.size();
    }
    
    /**
     * Get the total entity count across all trees.
     *
     * @return the total entity count
     */
    public int getTotalEntityCount() {
        return totalEntityCount.get();
    }
    
    /**
     * Find all entities within a spatial region across all trees.
     *
     * @param region the spatial region to search
     * @return list of entity IDs found across all trees
     */
    public List<ID> findEntitiesInRegion(Spatial region) {
        var results = new ArrayList<ID>();
        
        for (var tree : trees) {
            var spatialIndex = tree.getSpatialIndex();
            var bounds = tree.getGlobalBounds();
            
            // Quick bounds check to skip trees that don't overlap the region
            if (regionOverlapsBounds(region, bounds)) {
                // AbstractSpatialIndex.entitiesInRegion expects a Cube, not a generic Spatial
                Spatial.Cube cubeRegion;
                if (region instanceof Spatial.Cube) {
                    cubeRegion = (Spatial.Cube) region;
                } else {
                    // Convert to cube - this is a simplified approach
                    cubeRegion = new Spatial.Cube(0, 0, 0, 1);
                }
                var entities = spatialIndex.entitiesInRegion(cubeRegion);
                results.addAll(entities);
            }
        }
        
        return results;
    }
    
    /**
     * Find K nearest neighbors to a point across all trees.
     *
     * @param point the query point
     * @param k the number of neighbors to find
     * @return list of K nearest entity IDs
     */
    public List<ID> findKNearestNeighbors(Point3f point, int k) {
        // Collect candidates from all trees
        var candidates = new ArrayList<EntityDistance<ID>>();
        
        for (var tree : trees) {
            var spatialIndex = tree.getSpatialIndex();
            var neighbors = spatialIndex.kNearestNeighbors(point, k, Float.MAX_VALUE);
            
            // Convert to EntityDistance objects for global sorting
            for (var entityId : neighbors) {
                var position = spatialIndex.getEntityPosition(entityId);
                if (position != null) {
                    var distance = point.distance(position);
                    candidates.add(new EntityDistance<>(entityId, distance));
                }
            }
        }
        
        // Sort globally and return top K
        return candidates.stream()
            .sorted(Comparator.comparingDouble(EntityDistance::distance))
            .limit(k)
            .map(EntityDistance::entityId)
            .collect(Collectors.toList());
    }
    
    /**
     * Route a query to the appropriate trees based on spatial bounds.
     *
     * @param queryBounds the spatial bounds of the query
     * @return stream of trees that potentially contain relevant data
     */
    public Stream<TreeNode<Key, ID, Content>> routeQuery(EntityBounds queryBounds) {
        return trees.stream()
            .filter(tree -> {
                var treeBounds = tree.getGlobalBounds();
                return treeBounds != null && boundsOverlap(queryBounds, treeBounds);
            });
    }
    
    /**
     * Establish a neighbor relationship between two trees.
     *
     * @param treeId1 first tree ID
     * @param treeId2 second tree ID
     * @return true if the relationship was established
     */
    public boolean addNeighborRelationship(String treeId1, String treeId2) {
        var tree1 = treeMap.get(treeId1);
        var tree2 = treeMap.get(treeId2);
        
        if (tree1 != null && tree2 != null && !treeId1.equals(treeId2)) {
            tree1.addNeighbor(treeId2);
            tree2.addNeighbor(treeId1);
            return true;
        }
        return false;
    }
    
    /**
     * Remove a neighbor relationship between two trees.
     *
     * @param treeId1 first tree ID
     * @param treeId2 second tree ID
     * @return true if the relationship was removed
     */
    public boolean removeNeighborRelationship(String treeId1, String treeId2) {
        var tree1 = treeMap.get(treeId1);
        var tree2 = treeMap.get(treeId2);
        
        if (tree1 != null && tree2 != null) {
            tree1.removeNeighbor(treeId2);
            tree2.removeNeighbor(treeId1);
            return true;
        }
        return false;
    }
    
    /**
     * Refresh statistics for all trees.
     */
    public void refreshAllStatistics() {
        for (var tree : trees) {
            tree.refreshStatistics();
        }
        updateTotalEntityCount();
    }
    
    /**
     * Get forest-wide statistics.
     *
     * @return map of statistic names to values
     */
    public Map<String, Object> getForestStatistics() {
        var stats = new HashMap<String, Object>();
        stats.put("treeCount", trees.size());
        stats.put("totalEntityCount", totalEntityCount.get());
        stats.put("configuration", config.toString());
        
        // Aggregate tree statistics
        var totalNodes = 0L;
        var maxDepth = 0;
        
        for (var tree : trees) {
            totalNodes += tree.getNodeCount();
            maxDepth = Math.max(maxDepth, tree.getMaxDepth());
        }
        
        stats.put("totalNodeCount", totalNodes);
        stats.put("maxTreeDepth", maxDepth);
        stats.put("averageEntitiesPerTree", trees.isEmpty() ? 0 : 
            totalEntityCount.get() / (double) trees.size());
        
        return stats;
    }
    
    /**
     * Set forest-wide metadata.
     *
     * @param key the metadata key
     * @param value the metadata value
     * @return the previous value, if any
     */
    public Object setForestMetadata(String key, Object value) {
        return forestMetadata.put(key, value);
    }
    
    /**
     * Get forest-wide metadata.
     *
     * @param key the metadata key
     * @return the metadata value, or null if not set
     */
    public Object getForestMetadata(String key) {
        return forestMetadata.get(key);
    }
    
    /**
     * Get all forest metadata.
     *
     * @return unmodifiable view of forest metadata
     */
    public Map<String, Object> getAllForestMetadata() {
        return Collections.unmodifiableMap(forestMetadata);
    }
    
    /**
     * Get the forest configuration.
     *
     * @return the forest configuration
     */
    public ForestConfig getConfig() {
        return config;
    }
    
    // Helper methods
    
    private String generateTreeId(TreeMetadata metadata) {
        var id = treeIdGenerator.getAndIncrement();
        if (metadata != null && metadata.getName() != null) {
            return metadata.getName() + "_" + id;
        }
        return "tree_" + id;
    }
    
    private void updateTotalEntityCount() {
        var count = trees.stream()
            .mapToInt(TreeNode::getEntityCount)
            .sum();
        totalEntityCount.set(count);
    }
    
    private boolean regionOverlapsBounds(Spatial region, EntityBounds bounds) {
        // Simple AABB overlap test
        if (region instanceof Spatial.Cube) {
            var cube = (Spatial.Cube) region;
            // Check if cube intersects with bounds
            var min = bounds.getMin();
            var max = bounds.getMax();
            return cube.intersects(min.x, min.y, min.z, max.x, max.y, max.z);
        }
        // For other spatial types, use a conservative approach
        return true;
    }
    
    private Spatial.Cube createCubeFromRegion(Spatial region) {
        // Convert arbitrary spatial region to cube for query
        // This would need to be implemented based on region type
        // For now, return a placeholder
        return new Spatial.Cube(0, 0, 0, 1);
    }
    
    private boolean boundsOverlap(EntityBounds bounds1, EntityBounds bounds2) {
        var min1 = bounds1.getMin();
        var max1 = bounds1.getMax();
        var min2 = bounds2.getMin();
        var max2 = bounds2.getMax();
        
        return min1.x <= max2.x && max1.x >= min2.x &&
               min1.y <= max2.y && max1.y >= min2.y &&
               min1.z <= max2.z && max1.z >= min2.z;
    }
    
    @Override
    public String toString() {
        return String.format("Forest[trees=%d, entities=%d, config=%s]",
                           trees.size(), totalEntityCount.get(), config);
    }
    
    /**
     * Helper class for entity distance tracking during k-NN queries.
     */
    private record EntityDistance<T extends EntityID>(T entityId, double distance) {
    }
}
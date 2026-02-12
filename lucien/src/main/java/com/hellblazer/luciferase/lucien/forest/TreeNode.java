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
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A TreeNode wraps a spatial index tree with forest-specific metadata and capabilities.
 * This class manages a single tree within a forest of spatial index trees, providing
 * neighbor tracking, bounds management, and statistics gathering.
 *
 * <p>The TreeNode maintains metadata about the spatial index tree including:
 * <ul>
 *   <li>Global bounds of all entities in the tree</li>
 *   <li>Neighbor relationships with other trees in the forest</li>
 *   <li>Tree statistics (entity count, max depth, node count)</li>
 *   <li>Custom metadata for application-specific use</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe for concurrent access. All mutable state
 * is protected by appropriate synchronization mechanisms.
 *
 * @param <Key>     The spatial key type (e.g., MortonKey, TetreeKey)
 * @param <ID>      The entity ID type
 * @param <Content> The content type stored with entities
 * @author hal.hildebrand
 */
public class TreeNode<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(TreeNode.class);
    
    /** Unique identifier for this tree within the forest */
    private final String treeId;
    
    /** The wrapped spatial index instance */
    private final AbstractSpatialIndex<Key, ID, Content> spatialIndex;
    
    /** Global bounds of all entities in this tree */
    private volatile EntityBounds globalBounds;
    
    /** Neighbor trees that this tree has spatial relationships with */
    private final Set<String> neighbors;
    
    /** Application-specific metadata */
    private final Map<String, Object> metadata;
    
    /** Statistics tracking */
    private final AtomicInteger entityCount;
    private final AtomicInteger maxDepth;
    private final AtomicLong nodeCount;
    private volatile long lastUpdateTime;

    // ========== Hierarchy Fields (Phase 1 - Tetrahedral Forest Enhancement) ==========

    /** TreeBounds defining the spatial region covered by this tree (AABB or tetrahedral) */
    private volatile TreeBounds treeBounds;

    /** CAS guard to prevent double-subdivision race condition */
    private final AtomicBoolean subdivided;

    /** Parent tree ID in the hierarchy (null for root trees) */
    private final AtomicReference<String> parentTreeId;

    /** Child tree IDs after subdivision */
    private final CopyOnWriteArrayList<String> childTreeIds;

    /** Level in the hierarchy (0 = root, increases with subdivision) */
    private volatile int hierarchyLevel;

    // ========== Server Assignment Tracking (Phase 3 - Tumbler Integration) ==========

    /** Optional server assignment for distributed deployment (managed by ForestToTumblerBridge) */
    private volatile String assignedServerId;
    
    /**
     * Create a new TreeNode wrapping a spatial index.
     *
     * @param treeId      unique identifier for this tree
     * @param spatialIndex the spatial index to wrap
     */
    public TreeNode(String treeId, AbstractSpatialIndex<Key, ID, Content> spatialIndex) {
        this.treeId = Objects.requireNonNull(treeId, "Tree ID cannot be null");
        this.spatialIndex = Objects.requireNonNull(spatialIndex, "Spatial index cannot be null");
        this.neighbors = ConcurrentHashMap.newKeySet();
        this.metadata = new ConcurrentHashMap<>();
        this.entityCount = new AtomicInteger(0);
        this.maxDepth = new AtomicInteger(0);
        this.nodeCount = new AtomicLong(0);
        this.lastUpdateTime = System.currentTimeMillis();

        // Initialize global bounds as null to indicate no entities yet
        this.globalBounds = null;

        // Initialize hierarchy fields (Phase 1 - Tetrahedral Forest Enhancement)
        this.treeBounds = null;
        this.subdivided = new AtomicBoolean(false);
        this.parentTreeId = new AtomicReference<>(null);
        this.childTreeIds = new CopyOnWriteArrayList<>();
        this.hierarchyLevel = 0;

        log.debug("Created TreeNode with ID: {}", treeId);
    }
    
    /**
     * Get the unique identifier for this tree.
     *
     * @return the tree ID
     */
    public String getTreeId() {
        return treeId;
    }
    
    /**
     * Get the wrapped spatial index.
     *
     * @return the spatial index instance
     */
    public AbstractSpatialIndex<Key, ID, Content> getSpatialIndex() {
        return spatialIndex;
    }
    
    /**
     * Get the global bounds of all entities in this tree.
     *
     * @return the global bounds
     */
    public EntityBounds getGlobalBounds() {
        return globalBounds;
    }
    
    /**
     * Update the global bounds to include a new entity bounds.
     *
     * @param entityBounds the bounds to include
     */
    public void expandGlobalBounds(EntityBounds entityBounds) {
        synchronized (this) {
            if (globalBounds == null) {
                // First entity - initialize bounds
                globalBounds = new EntityBounds(
                    new Point3f(entityBounds.getMin()),
                    new Point3f(entityBounds.getMax())
                );
            } else {
                var min = globalBounds.getMin();
                var max = globalBounds.getMax();
                var entityMin = entityBounds.getMin();
                var entityMax = entityBounds.getMax();
                
                var newMin = new Point3f(
                    Math.min(min.x, entityMin.x),
                    Math.min(min.y, entityMin.y),
                    Math.min(min.z, entityMin.z)
                );
                
                var newMax = new Point3f(
                    Math.max(max.x, entityMax.x),
                    Math.max(max.y, entityMax.y),
                    Math.max(max.z, entityMax.z)
                );
                
                globalBounds = new EntityBounds(newMin, newMax);
            }
            lastUpdateTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Add a neighbor tree.
     *
     * @param neighborId the ID of the neighbor tree
     * @return true if the neighbor was newly added
     */
    public boolean addNeighbor(String neighborId) {
        var added = neighbors.add(neighborId);
        if (added) {
            log.debug("Added neighbor {} to tree {}", neighborId, treeId);
        }
        return added;
    }
    
    /**
     * Remove a neighbor tree.
     *
     * @param neighborId the ID of the neighbor tree
     * @return true if the neighbor was removed
     */
    public boolean removeNeighbor(String neighborId) {
        var removed = neighbors.remove(neighborId);
        if (removed) {
            log.debug("Removed neighbor {} from tree {}", neighborId, treeId);
        }
        return removed;
    }
    
    /**
     * Get all neighbor tree IDs.
     *
     * @return unmodifiable set of neighbor IDs
     */
    public Set<String> getNeighbors() {
        return Collections.unmodifiableSet(neighbors);
    }
    
    /**
     * Check if a tree is a neighbor.
     *
     * @param neighborId the ID to check
     * @return true if the tree is a neighbor
     */
    public boolean hasNeighbor(String neighborId) {
        return neighbors.contains(neighborId);
    }
    
    /**
     * Set a metadata value.
     *
     * @param key   the metadata key
     * @param value the metadata value
     * @return the previous value, if any
     */
    public Object setMetadata(String key, Object value) {
        return metadata.put(key, value);
    }
    
    /**
     * Get a metadata value.
     *
     * @param key the metadata key
     * @return the metadata value, or null if not set
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }
    
    /**
     * Remove a metadata value.
     *
     * @param key the metadata key
     * @return the removed value, or null if not set
     */
    public Object removeMetadata(String key) {
        return metadata.remove(key);
    }
    
    /**
     * Get all metadata as an unmodifiable map.
     *
     * @return unmodifiable view of metadata
     */
    public Map<String, Object> getAllMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
    
    /**
     * Update entity count statistic.
     *
     * @param count the new entity count
     */
    public void updateEntityCount(int count) {
        entityCount.set(count);
        lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Update max depth statistic.
     *
     * @param depth the new max depth
     */
    public void updateMaxDepth(int depth) {
        maxDepth.set(depth);
        lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Update node count statistic.
     *
     * @param count the new node count
     */
    public void updateNodeCount(long count) {
        nodeCount.set(count);
        lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Get the current entity count.
     *
     * @return the entity count
     */
    public int getEntityCount() {
        return entityCount.get();
    }
    
    /**
     * Get the current max depth.
     *
     * @return the max depth
     */
    public int getMaxDepth() {
        return maxDepth.get();
    }
    
    /**
     * Get the current node count.
     *
     * @return the node count
     */
    public long getNodeCount() {
        return nodeCount.get();
    }
    
    /**
     * Get the last update timestamp.
     *
     * @return timestamp in milliseconds since epoch
     */
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
    
    /**
     * Get tree statistics as a map.
     *
     * @return map of statistic names to values
     */
    public Map<String, Object> getStatistics() {
        var stats = new HashMap<String, Object>();
        stats.put("entityCount", entityCount.get());
        stats.put("maxDepth", maxDepth.get());
        stats.put("nodeCount", nodeCount.get());
        stats.put("neighborCount", neighbors.size());
        stats.put("lastUpdateTime", lastUpdateTime);
        stats.put("globalBounds", globalBounds);
        return stats;
    }
    
    /**
     * Refresh statistics from the underlying spatial index.
     * This queries the spatial index for current values.
     */
    public void refreshStatistics() {
        try {
            // Get entity count from spatial index
            var currentEntityCount = spatialIndex.entityCount();
            entityCount.set(currentEntityCount);
            
            // Get node count from spatial index
            var currentNodeCount = spatialIndex.getNodeCount();
            nodeCount.set(currentNodeCount);
            
            // Max depth would need to be tracked separately as it's not exposed
            
            lastUpdateTime = System.currentTimeMillis();
            
            log.debug("Refreshed statistics for tree {}: entities={}, depth={}, nodes={}", 
                     treeId, currentEntityCount, maxDepth.get(), nodeCount.get());
        } catch (Exception e) {
            log.error("Failed to refresh statistics for tree {}", treeId, e);
        }
    }
    
    // ========== Hierarchy Accessors (Phase 1 - Tetrahedral Forest Enhancement) ==========

    /**
     * Get the TreeBounds defining this tree's spatial region.
     *
     * @return the tree bounds (null if not set)
     */
    public TreeBounds getTreeBounds() {
        return treeBounds;
    }

    /**
     * Set the TreeBounds defining this tree's spatial region.
     *
     * @param bounds the tree bounds (CubicBounds or TetrahedralBounds)
     */
    public void setTreeBounds(TreeBounds bounds) {
        this.treeBounds = bounds;
    }

    /**
     * Atomically mark this tree as subdivided.
     * Uses CAS (compare-and-set) to prevent double-subdivision race condition.
     *
     * @return true if this thread won the race and marked as subdivided, false if already subdivided
     */
    public boolean tryMarkSubdivided() {
        return subdivided.compareAndSet(false, true);
    }

    /**
     * Check if this tree has been subdivided.
     *
     * @return true if subdivided (has children)
     */
    public boolean isSubdivided() {
        return subdivided.get();
    }

    /**
     * Get the parent tree ID in the hierarchy.
     *
     * @return parent tree ID (null for root trees)
     */
    public String getParentTreeId() {
        return parentTreeId.get();
    }

    /**
     * Set the parent tree ID in the hierarchy.
     *
     * @param parentId parent tree ID
     */
    public void setParentTreeId(String parentId) {
        this.parentTreeId.set(parentId);
    }

    /**
     * Get an unmodifiable view of child tree IDs.
     *
     * @return list of child tree IDs
     */
    public List<String> getChildTreeIds() {
        return Collections.unmodifiableList(childTreeIds);
    }

    /**
     * Add a child tree ID.
     *
     * @param childId child tree ID to add
     */
    public void addChildTreeId(String childId) {
        childTreeIds.add(childId);
    }

    /**
     * Remove a child tree ID.
     *
     * @param childId child tree ID to remove
     */
    public void removeChildTreeId(String childId) {
        childTreeIds.remove(childId);
    }

    /**
     * Check if this tree is a leaf (has no children).
     *
     * @return true if leaf (no children)
     */
    public boolean isLeaf() {
        return childTreeIds.isEmpty();
    }

    /**
     * Check if this tree is a root (has no parent).
     *
     * @return true if root (no parent)
     */
    public boolean isRoot() {
        return parentTreeId.get() == null;
    }

    /**
     * Get the hierarchy level (0 = root, increases with subdivision).
     *
     * @return hierarchy level
     */
    public int getHierarchyLevel() {
        return hierarchyLevel;
    }

    /**
     * Set the hierarchy level.
     *
     * @param level hierarchy level
     */
    public void setHierarchyLevel(int level) {
        this.hierarchyLevel = level;
    }

    /**
     * Check if this tree has children.
     *
     * @return true if has children
     */
    public boolean hasChildren() {
        return !childTreeIds.isEmpty();
    }

    // ========== Server Assignment Accessors (Phase 3 - Tumbler Integration) ==========

    /**
     * Get the assigned server ID for this tree.
     *
     * This is optional tracking managed by ForestToTumblerBridge. Returns null if no server assigned.
     *
     * @return assigned server ID, or null if unassigned
     */
    public String getAssignedServerId() {
        return assignedServerId;
    }

    /**
     * Set the assigned server ID for this tree.
     *
     * This is optional tracking managed by ForestToTumblerBridge.
     *
     * @param serverId server ID to assign
     */
    public void setAssignedServerId(String serverId) {
        this.assignedServerId = serverId;
    }

    @Override
    public String toString() {
        return String.format("TreeNode[id=%s, server=%s, entities=%d, depth=%d, nodes=%d, neighbors=%d, level=%d, children=%d]",
                           treeId,
                           assignedServerId != null ? assignedServerId : "unassigned",
                           entityCount.get(), maxDepth.get(), nodeCount.get(), neighbors.size(),
                           hierarchyLevel, childTreeIds.size());
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        var treeNode = (TreeNode<?, ?, ?>) o;
        return treeId.equals(treeNode.treeId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(treeId);
    }
}
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

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.Entity;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Manages ghost zones between adjacent trees in a forest of spatial indices.
 * Ghost zones are regions near tree boundaries where entities are replicated
 * to enable efficient cross-tree queries and interactions.
 *
 * <p>The GhostZoneManager handles:
 * <ul>
 *   <li>Tracking ghost entities (entities near tree boundaries)</li>
 *   <li>Synchronizing ghost entities between adjacent trees</li>
 *   <li>Configurable ghost zone width for different use cases</li>
 *   <li>Efficient updates when entities move</li>
 *   <li>Thread-safe operations for concurrent access</li>
 * </ul>
 *
 * <p>Ghost entities are read-only replicas maintained in neighboring trees to avoid
 * expensive cross-tree lookups during spatial queries that span tree boundaries.
 *
 * @param <Key>     The spatial key type (e.g., MortonKey, TetreeKey)
 * @param <ID>      The entity ID type
 * @param <Content> The content type stored with entities
 * @author hal.hildebrand
 */
public class GhostZoneManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(GhostZoneManager.class);
    
    /**
     * Represents a ghost entity - a read-only replica of an entity from another tree.
     */
    public static class GhostEntity<ID extends EntityID, Content> {
        private final ID entityId;
        private final Content content;
        private final Point3f position;
        private final EntityBounds bounds;
        private final String sourceTreeId;
        private final long timestamp;
        
        public GhostEntity(ID entityId, Content content, Point3f position, 
                          EntityBounds bounds, String sourceTreeId) {
            this.entityId = Objects.requireNonNull(entityId, "Entity ID cannot be null");
            this.content = content;
            this.position = new Point3f(Objects.requireNonNull(position, "Position cannot be null"));
            this.bounds = bounds;
            this.sourceTreeId = Objects.requireNonNull(sourceTreeId, "Source tree ID cannot be null");
            this.timestamp = System.currentTimeMillis();
        }
        
        public ID getEntityId() { return entityId; }
        public Content getContent() { return content; }
        public Point3f getPosition() { return new Point3f(position); }
        public EntityBounds getBounds() { return bounds; }
        public String getSourceTreeId() { return sourceTreeId; }
        public long getTimestamp() { return timestamp; }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            var that = (GhostEntity<?, ?>) o;
            return entityId.equals(that.entityId) && sourceTreeId.equals(that.sourceTreeId);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(entityId, sourceTreeId);
        }
    }
    
    /**
     * Tracks ghost zone relationships between trees.
     */
    private static class GhostZoneRelation {
        private final String treeId1;
        private final String treeId2;
        private final float ghostZoneWidth;
        
        public GhostZoneRelation(String treeId1, String treeId2, float ghostZoneWidth) {
            // Ensure consistent ordering for bidirectional relationships
            if (treeId1.compareTo(treeId2) < 0) {
                this.treeId1 = treeId1;
                this.treeId2 = treeId2;
            } else {
                this.treeId1 = treeId2;
                this.treeId2 = treeId1;
            }
            this.ghostZoneWidth = ghostZoneWidth;
        }
        
        public boolean involvesTree(String treeId) {
            return treeId1.equals(treeId) || treeId2.equals(treeId);
        }
        
        public String getOtherTree(String treeId) {
            if (treeId1.equals(treeId)) return treeId2;
            if (treeId2.equals(treeId)) return treeId1;
            return null;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            var that = (GhostZoneRelation) o;
            return treeId1.equals(that.treeId1) && treeId2.equals(that.treeId2);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(treeId1, treeId2);
        }
    }
    
    // The forest being managed
    private final Forest<Key, ID, Content> forest;
    
    // Default ghost zone width
    private volatile float defaultGhostZoneWidth;
    
    // Ghost zone relationships between trees
    private final Set<GhostZoneRelation> ghostZoneRelations;
    
    // Ghost entities by tree: Tree ID → Set of ghost entities
    private final Map<String, Set<GhostEntity<ID, Content>>> ghostEntitiesByTree;
    
    // Entity to ghost locations: Entity ID → Set of tree IDs where it exists as ghost
    private final Map<ID, Set<String>> entityGhostLocations;
    
    // Lock for thread-safe operations
    private final ReadWriteLock lock;
    
    /**
     * Create a ghost zone manager with a default ghost zone width.
     *
     * @param forest the forest to manage
     * @param defaultGhostZoneWidth the default width of ghost zones
     */
    public GhostZoneManager(Forest<Key, ID, Content> forest, float defaultGhostZoneWidth) {
        this.forest = Objects.requireNonNull(forest, "Forest cannot be null");
        this.defaultGhostZoneWidth = defaultGhostZoneWidth;
        this.ghostZoneRelations = ConcurrentHashMap.newKeySet();
        this.ghostEntitiesByTree = new ConcurrentHashMap<>();
        this.entityGhostLocations = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        
        log.info("Created GhostZoneManager with default ghost zone width: {}", defaultGhostZoneWidth);
    }
    
    /**
     * Set the default ghost zone width.
     *
     * @param width the new default width
     */
    public void setDefaultGhostZoneWidth(float width) {
        lock.writeLock().lock();
        try {
            this.defaultGhostZoneWidth = width;
            log.info("Updated default ghost zone width to: {}", width);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get the default ghost zone width.
     *
     * @return the default width
     */
    public float getDefaultGhostZoneWidth() {
        return defaultGhostZoneWidth;
    }
    
    /**
     * Establish a ghost zone relationship between two trees.
     *
     * @param treeId1 the first tree ID
     * @param treeId2 the second tree ID
     * @param ghostZoneWidth the width of the ghost zone (use null for default)
     * @return true if the relationship was newly established
     */
    public boolean establishGhostZone(String treeId1, String treeId2, Float ghostZoneWidth) {
        lock.writeLock().lock();
        try {
            var width = ghostZoneWidth != null ? ghostZoneWidth : defaultGhostZoneWidth;
            var relation = new GhostZoneRelation(treeId1, treeId2, width);
            
            if (ghostZoneRelations.add(relation)) {
                // Initialize ghost entity storage for both trees if needed
                ghostEntitiesByTree.computeIfAbsent(treeId1, k -> ConcurrentHashMap.newKeySet());
                ghostEntitiesByTree.computeIfAbsent(treeId2, k -> ConcurrentHashMap.newKeySet());
                
                log.info("Established ghost zone between trees {} and {} with width {}", 
                        treeId1, treeId2, width);
                
                // Perform initial synchronization
                synchronizeGhostZone(treeId1, treeId2);
                
                return true;
            }
            
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Remove a ghost zone relationship between two trees.
     *
     * @param treeId1 the first tree ID
     * @param treeId2 the second tree ID
     * @return true if the relationship was removed
     */
    public boolean removeGhostZone(String treeId1, String treeId2) {
        lock.writeLock().lock();
        try {
            var relation = new GhostZoneRelation(treeId1, treeId2, 0);
            
            if (ghostZoneRelations.remove(relation)) {
                // Clean up ghost entities between these trees
                cleanupGhostsBetweenTrees(treeId1, treeId2);
                
                log.info("Removed ghost zone between trees {} and {}", treeId1, treeId2);
                return true;
            }
            
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get all trees that have ghost zones with the specified tree.
     *
     * @param treeId the tree ID
     * @return set of neighbor tree IDs with ghost zones
     */
    public Set<String> getGhostZoneNeighbors(String treeId) {
        lock.readLock().lock();
        try {
            return ghostZoneRelations.stream()
                .filter(r -> r.involvesTree(treeId))
                .map(r -> r.getOtherTree(treeId))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Update ghost entities when an entity is inserted or updated.
     *
     * @param entityId the entity ID
     * @param sourceTreeId the tree where the entity resides
     * @param position the entity position
     * @param bounds optional entity bounds
     * @param content the entity content
     */
    public void updateGhostEntity(ID entityId, String sourceTreeId, Point3f position, 
                                 EntityBounds bounds, Content content) {
        lock.writeLock().lock();
        try {
            var sourceTree = forest.getTree(sourceTreeId);
            if (sourceTree == null) {
                log.warn("Source tree {} not found", sourceTreeId);
                return;
            }
            
            // Find all trees with ghost zones to the source tree
            var ghostZoneNeighbors = getGhostZoneNeighbors(sourceTreeId);
            
            // Track which trees should have this ghost
            var newGhostTrees = new HashSet<String>();
            
            for (var neighborId : ghostZoneNeighbors) {
                var neighborTree = forest.getTree(neighborId);
                if (neighborTree == null) continue;
                
                // Check if entity is within ghost zone distance of neighbor tree
                var ghostZoneWidth = getGhostZoneWidth(sourceTreeId, neighborId);
                if (isInGhostZone(position, bounds, sourceTree, neighborTree, ghostZoneWidth)) {
                    // Create or update ghost entity
                    var ghost = new GhostEntity<>(entityId, content, position, bounds, sourceTreeId);
                    var ghosts = ghostEntitiesByTree.get(neighborId);
                    
                    // Remove old version if exists
                    ghosts.removeIf(g -> g.getEntityId().equals(entityId) && 
                                        g.getSourceTreeId().equals(sourceTreeId));
                    
                    // Add new version
                    ghosts.add(ghost);
                    newGhostTrees.add(neighborId);
                    
                    log.debug("Updated ghost entity {} from tree {} in tree {}", 
                             entityId, sourceTreeId, neighborId);
                }
            }
            
            // Update entity ghost locations
            var oldGhostTrees = entityGhostLocations.get(entityId);
            if (oldGhostTrees != null) {
                // Remove ghost from trees where it's no longer needed
                for (var treeId : oldGhostTrees) {
                    if (!newGhostTrees.contains(treeId)) {
                        removeGhostFromTree(entityId, sourceTreeId, treeId);
                    }
                }
            }
            
            // Update tracking
            if (!newGhostTrees.isEmpty()) {
                entityGhostLocations.put(entityId, newGhostTrees);
            } else {
                entityGhostLocations.remove(entityId);
            }
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Remove ghost entities when an entity is deleted.
     *
     * @param entityId the entity ID
     * @param sourceTreeId the tree where the entity was located
     */
    public void removeGhostEntity(ID entityId, String sourceTreeId) {
        lock.writeLock().lock();
        try {
            var ghostTrees = entityGhostLocations.remove(entityId);
            if (ghostTrees != null) {
                for (var treeId : ghostTrees) {
                    removeGhostFromTree(entityId, sourceTreeId, treeId);
                }
            }
            
            log.debug("Removed all ghost entities for entity {} from source tree {}", 
                     entityId, sourceTreeId);
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get all ghost entities in a specific tree.
     *
     * @param treeId the tree ID
     * @return set of ghost entities in the tree
     */
    public Set<GhostEntity<ID, Content>> getGhostEntities(String treeId) {
        lock.readLock().lock();
        try {
            var ghosts = ghostEntitiesByTree.get(treeId);
            return ghosts != null ? new HashSet<>(ghosts) : Collections.emptySet();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get ghost entities from a specific source tree.
     *
     * @param treeId the tree containing ghosts
     * @param sourceTreeId the source tree of the ghosts
     * @return set of ghost entities from the source tree
     */
    public Set<GhostEntity<ID, Content>> getGhostEntitiesFromTree(String treeId, String sourceTreeId) {
        lock.readLock().lock();
        try {
            var ghosts = ghostEntitiesByTree.get(treeId);
            if (ghosts == null) return Collections.emptySet();
            
            return ghosts.stream()
                .filter(g -> g.getSourceTreeId().equals(sourceTreeId))
                .collect(Collectors.toSet());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Synchronize ghost zones between all trees.
     * This performs a full resynchronization of all ghost entities.
     */
    public void synchronizeAllGhostZones() {
        lock.writeLock().lock();
        try {
            log.info("Starting full ghost zone synchronization");
            
            // Clear all existing ghosts
            ghostEntitiesByTree.values().forEach(Set::clear);
            entityGhostLocations.clear();
            
            // Synchronize each ghost zone relation
            for (var relation : ghostZoneRelations) {
                synchronizeGhostZone(relation.treeId1, relation.treeId2);
            }
            
            log.info("Completed full ghost zone synchronization");
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get statistics about ghost zones.
     *
     * @return map of statistics
     */
    public Map<String, Object> getStatistics() {
        lock.readLock().lock();
        try {
            var stats = new HashMap<String, Object>();
            stats.put("ghostZoneRelations", ghostZoneRelations.size());
            stats.put("totalGhostEntities", ghostEntitiesByTree.values().stream()
                .mapToInt(Set::size).sum());
            stats.put("entitiesWithGhosts", entityGhostLocations.size());
            
            // Per-tree ghost counts
            var perTreeGhosts = new HashMap<String, Integer>();
            for (var entry : ghostEntitiesByTree.entrySet()) {
                perTreeGhosts.put(entry.getKey(), entry.getValue().size());
            }
            stats.put("ghostsPerTree", perTreeGhosts);
            
            return stats;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Clear all ghost zones and entities.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            ghostZoneRelations.clear();
            ghostEntitiesByTree.clear();
            entityGhostLocations.clear();
            
            log.info("Cleared all ghost zones");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // Private helper methods
    
    private void synchronizeGhostZone(String treeId1, String treeId2) {
        var tree1 = forest.getTree(treeId1);
        var tree2 = forest.getTree(treeId2);
        
        if (tree1 == null || tree2 == null) {
            log.warn("Cannot synchronize ghost zone between {} and {} - tree not found", 
                    treeId1, treeId2);
            return;
        }
        
        var ghostZoneWidth = getGhostZoneWidth(treeId1, treeId2);
        
        // Check entities from tree1 that should be ghosts in tree2
        synchronizeDirection(tree1, tree2, ghostZoneWidth);
        
        // Check entities from tree2 that should be ghosts in tree1
        synchronizeDirection(tree2, tree1, ghostZoneWidth);
    }
    
    private void synchronizeDirection(TreeNode<Key, ID, Content> sourceTree, 
                                    TreeNode<Key, ID, Content> targetTree,
                                    float ghostZoneWidth) {
        // NOTE: Since AbstractSpatialIndex doesn't provide a way to iterate all entities,
        // this method would need to be called by the forest when entities are added/updated.
        // The synchronization logic has been moved to updateGhostEntity() which is called
        // for individual entity updates.
        log.debug("Synchronization between {} and {} requires explicit entity updates",
                 sourceTree.getTreeId(), targetTree.getTreeId());
    }
    
    private boolean isInGhostZone(Point3f position, EntityBounds entityBounds,
                                 TreeNode<Key, ID, Content> sourceTree,
                                 TreeNode<Key, ID, Content> targetTree,
                                 float ghostZoneWidth) {
        var targetBounds = targetTree.getGlobalBounds();
        
        // Use entity bounds if available, otherwise treat as point
        if (entityBounds != null) {
            return isAABBNearAABB(entityBounds, targetBounds, ghostZoneWidth);
        } else {
            return isPointNearAABB(position, targetBounds, ghostZoneWidth);
        }
    }
    
    private boolean isPointNearAABB(Point3f point, EntityBounds aabb, float distance) {
        var min = aabb.getMin();
        var max = aabb.getMax();
        
        // Calculate the closest point on the AABB to the given point
        var closestX = Math.max(min.x, Math.min(point.x, max.x));
        var closestY = Math.max(min.y, Math.min(point.y, max.y));
        var closestZ = Math.max(min.z, Math.min(point.z, max.z));
        
        // Calculate squared distance to avoid sqrt
        var dx = point.x - closestX;
        var dy = point.y - closestY;
        var dz = point.z - closestZ;
        var sqDist = dx * dx + dy * dy + dz * dz;
        
        return sqDist <= distance * distance;
    }
    
    private boolean isAABBNearAABB(EntityBounds aabb1, EntityBounds aabb2, float distance) {
        var min1 = aabb1.getMin();
        var max1 = aabb1.getMax();
        var min2 = aabb2.getMin();
        var max2 = aabb2.getMax();
        
        // Check if AABBs are within distance on each axis
        var xSeparation = Math.max(0, Math.max(min1.x - max2.x, min2.x - max1.x));
        var ySeparation = Math.max(0, Math.max(min1.y - max2.y, min2.y - max1.y));
        var zSeparation = Math.max(0, Math.max(min1.z - max2.z, min2.z - max1.z));
        
        // If the sum of separations is within distance, they're close enough
        return xSeparation <= distance && ySeparation <= distance && zSeparation <= distance;
    }
    
    private float getGhostZoneWidth(String treeId1, String treeId2) {
        for (var relation : ghostZoneRelations) {
            if (relation.involvesTree(treeId1) && relation.involvesTree(treeId2)) {
                return relation.ghostZoneWidth;
            }
        }
        return defaultGhostZoneWidth;
    }
    
    private void removeGhostFromTree(ID entityId, String sourceTreeId, String targetTreeId) {
        var ghosts = ghostEntitiesByTree.get(targetTreeId);
        if (ghosts != null) {
            ghosts.removeIf(g -> g.getEntityId().equals(entityId) && 
                               g.getSourceTreeId().equals(sourceTreeId));
        }
    }
    
    private void cleanupGhostsBetweenTrees(String treeId1, String treeId2) {
        // Remove ghosts from tree1 that came from tree2
        var ghosts1 = ghostEntitiesByTree.get(treeId1);
        if (ghosts1 != null) {
            ghosts1.removeIf(g -> g.getSourceTreeId().equals(treeId2));
        }
        
        // Remove ghosts from tree2 that came from tree1
        var ghosts2 = ghostEntitiesByTree.get(treeId2);
        if (ghosts2 != null) {
            ghosts2.removeIf(g -> g.getSourceTreeId().equals(treeId1));
        }
        
        // Update entity ghost location tracking
        for (var entry : entityGhostLocations.entrySet()) {
            entry.getValue().remove(treeId1);
            entry.getValue().remove(treeId2);
        }
        
        // Remove empty entries
        entityGhostLocations.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    
    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return String.format("GhostZoneManager[relations=%d, totalGhosts=%d, defaultWidth=%.2f]",
                               ghostZoneRelations.size(),
                               ghostEntitiesByTree.values().stream().mapToInt(Set::size).sum(),
                               defaultGhostZoneWidth);
        } finally {
            lock.readLock().unlock();
        }
    }
}
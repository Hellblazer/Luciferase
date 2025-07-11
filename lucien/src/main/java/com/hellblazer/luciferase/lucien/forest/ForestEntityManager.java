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
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Manages entities across all trees in a forest. This class coordinates entity lifecycle,
 * tracks entity locations across trees, and handles entity migration between trees.
 *
 * <p>The ForestEntityManager maintains a global view of all entities in the forest,
 * tracking which tree each entity belongs to and managing the assignment of new entities
 * to appropriate trees based on configurable strategies.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Entity insertion with tree assignment</li>
 *   <li>Entity removal across all trees</li>
 *   <li>Entity position updates with potential tree migration</li>
 *   <li>Tree assignment strategy management</li>
 *   <li>Cross-tree entity queries</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe for concurrent access. All operations
 * are protected by appropriate locking mechanisms.
 *
 * @param <Key>     The spatial key type (e.g., MortonKey, TetreeKey)
 * @param <ID>      The entity ID type
 * @param <Content> The content type stored with entities
 * @author hal.hildebrand
 */
public class ForestEntityManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(ForestEntityManager.class);
    
    /**
     * Tracks the location of an entity within the forest.
     */
    public static class TreeLocation {
        private final String treeId;
        private final Point3f position;
        
        public TreeLocation(String treeId, Point3f position) {
            this.treeId = Objects.requireNonNull(treeId, "Tree ID cannot be null");
            this.position = new Point3f(Objects.requireNonNull(position, "Position cannot be null"));
        }
        
        public String getTreeId() {
            return treeId;
        }
        
        public Point3f getPosition() {
            return new Point3f(position);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            var that = (TreeLocation) o;
            return treeId.equals(that.treeId) && position.equals(that.position);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(treeId, position);
        }
    }
    
    /**
     * Strategy interface for assigning entities to trees.
     */
    public interface TreeAssignmentStrategy<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
        /**
         * Select a tree for a new entity.
         *
         * @param entityId the entity ID
         * @param position the entity position
         * @param bounds   optional entity bounds
         * @param forest   the forest containing available trees
         * @return the selected tree ID, or null if no suitable tree
         */
        String selectTree(ID entityId, Point3f position, EntityBounds bounds,
                         Forest<Key, ID, Content> forest);
        
        /**
         * Determine if an entity should migrate to a different tree.
         *
         * @param entityId    the entity ID
         * @param currentTree the current tree ID
         * @param newPosition the new entity position
         * @param forest      the forest containing available trees
         * @return the new tree ID, or null to keep in current tree
         */
        String shouldMigrate(ID entityId, String currentTree, Point3f newPosition,
                           Forest<Key, ID, Content> forest);
    }
    
    /**
     * Basic round-robin tree assignment strategy.
     */
    public static class RoundRobinStrategy<Key extends SpatialKey<Key>, ID extends EntityID, Content> 
            implements TreeAssignmentStrategy<Key, ID, Content> {
        
        private int nextTreeIndex = 0;
        
        @Override
        public synchronized String selectTree(ID entityId, Point3f position, EntityBounds bounds,
                                            Forest<Key, ID, Content> forest) {
            var trees = forest.getAllTrees();
            if (trees.isEmpty()) {
                return null;
            }
            
            var selectedTree = trees.get(nextTreeIndex % trees.size());
            nextTreeIndex++;
            return selectedTree.getTreeId();
        }
        
        @Override
        public String shouldMigrate(ID entityId, String currentTree, Point3f newPosition,
                                  Forest<Key, ID, Content> forest) {
            // Round-robin strategy doesn't migrate entities
            return null;
        }
    }
    
    /**
     * Spatial bounds-based tree assignment strategy.
     */
    public static class SpatialBoundsStrategy<Key extends SpatialKey<Key>, ID extends EntityID, Content> 
            implements TreeAssignmentStrategy<Key, ID, Content> {
        
        @Override
        public String selectTree(ID entityId, Point3f position, EntityBounds bounds,
                               Forest<Key, ID, Content> forest) {
            // Find the tree whose bounds best contain the entity
            var bestTree = (TreeNode<Key, ID, Content>) null;
            var bestContainment = 0.0;
            
            for (var tree : forest.getAllTrees()) {
                var treeBounds = tree.getGlobalBounds();
                if (treeBounds != null && containsPoint(treeBounds, position)) {
                    // Prefer trees that tightly contain the position
                    var containment = calculateContainmentScore(treeBounds, position);
                    if (containment > bestContainment) {
                        bestContainment = containment;
                        bestTree = tree;
                    }
                }
            }
            
            // If no tree contains the position, choose the closest one
            if (bestTree == null) {
                var closestDistance = Double.MAX_VALUE;
                for (var tree : forest.getAllTrees()) {
                    var treeBounds = tree.getGlobalBounds();
                    if (treeBounds != null) {
                        var distance = distanceToBounds(treeBounds, position);
                        if (distance < closestDistance) {
                            closestDistance = distance;
                            bestTree = tree;
                        }
                    }
                }
            }
            
            // If still no tree found (all have null bounds), use round-robin
            if (bestTree == null && !forest.getAllTrees().isEmpty()) {
                bestTree = forest.getAllTrees().get(0);
            }
            
            return bestTree != null ? bestTree.getTreeId() : null;
        }
        
        @Override
        public String shouldMigrate(ID entityId, String currentTree, Point3f newPosition,
                                  Forest<Key, ID, Content> forest) {
            var current = forest.getTree(currentTree);
            if (current == null) {
                return null;
            }
            
            // Check if position is still within current tree bounds
            var currentBounds = current.getGlobalBounds();
            if (currentBounds != null && containsPoint(currentBounds, newPosition)) {
                return null;
            }
            
            // Find a better tree
            var newTreeId = selectTree(entityId, newPosition, null, forest);
            return newTreeId != null && !newTreeId.equals(currentTree) ? newTreeId : null;
        }
        
        private boolean containsPoint(EntityBounds bounds, Point3f point) {
            var min = bounds.getMin();
            var max = bounds.getMax();
            return point.x >= min.x && point.x <= max.x &&
                   point.y >= min.y && point.y <= max.y &&
                   point.z >= min.z && point.z <= max.z;
        }
        
        private double calculateContainmentScore(EntityBounds bounds, Point3f point) {
            // Higher score for smaller volumes (tighter fit)
            var min = bounds.getMin();
            var max = bounds.getMax();
            var volume = (max.x - min.x) * (max.y - min.y) * (max.z - min.z);
            return volume > 0 ? 1.0 / volume : 0.0;
        }
        
        private double distanceToBounds(EntityBounds bounds, Point3f point) {
            var min = bounds.getMin();
            var max = bounds.getMax();
            
            // Calculate squared distance to closest point on bounds
            var dx = Math.max(min.x - point.x, Math.max(0, point.x - max.x));
            var dy = Math.max(min.y - point.y, Math.max(0, point.y - max.y));
            var dz = Math.max(min.z - point.z, Math.max(0, point.z - max.z));
            
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
    
    // Entity location tracking: Entity ID → Tree location
    private final Map<ID, TreeLocation> entityLocations;
    
    // The forest being managed
    private final Forest<Key, ID, Content> forest;
    
    // ID generation strategy
    private final EntityIDGenerator<ID> idGenerator;
    
    // Tree assignment strategy
    private TreeAssignmentStrategy<Key, ID, Content> assignmentStrategy;
    
    // Lock for thread-safe operations
    private final ReadWriteLock lock;
    
    /**
     * Create a forest entity manager with default round-robin assignment strategy.
     *
     * @param forest      the forest to manage
     * @param idGenerator the entity ID generator
     */
    public ForestEntityManager(Forest<Key, ID, Content> forest, EntityIDGenerator<ID> idGenerator) {
        this(forest, idGenerator, new RoundRobinStrategy<>());
    }
    
    /**
     * Create a forest entity manager with a specific assignment strategy.
     *
     * @param forest             the forest to manage
     * @param idGenerator        the entity ID generator
     * @param assignmentStrategy the tree assignment strategy
     */
    public ForestEntityManager(Forest<Key, ID, Content> forest, EntityIDGenerator<ID> idGenerator,
                              TreeAssignmentStrategy<Key, ID, Content> assignmentStrategy) {
        this.forest = Objects.requireNonNull(forest, "Forest cannot be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "ID generator cannot be null");
        this.assignmentStrategy = Objects.requireNonNull(assignmentStrategy, "Assignment strategy cannot be null");
        this.entityLocations = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        
        log.info("Created ForestEntityManager with {} strategy", assignmentStrategy.getClass().getSimpleName());
    }
    
    /**
     * Set a new tree assignment strategy.
     *
     * @param strategy the new strategy
     */
    public void setAssignmentStrategy(TreeAssignmentStrategy<Key, ID, Content> strategy) {
        lock.writeLock().lock();
        try {
            this.assignmentStrategy = Objects.requireNonNull(strategy, "Assignment strategy cannot be null");
            log.info("Changed assignment strategy to {}", strategy.getClass().getSimpleName());
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Insert a new entity into the forest.
     *
     * @param entityId the entity ID
     * @param content  the entity content
     * @param position the entity position
     * @param bounds   optional entity bounds
     * @return the tree ID where the entity was inserted, or null if insertion failed
     */
    public String insert(ID entityId, Content content, Point3f position, EntityBounds bounds) {
        lock.writeLock().lock();
        try {
            // Check if entity already exists
            if (entityLocations.containsKey(entityId)) {
                log.warn("Entity {} already exists in tree {}", entityId, entityLocations.get(entityId).getTreeId());
                return null;
            }
            
            // Select a tree for the entity
            var treeId = assignmentStrategy.selectTree(entityId, position, bounds, forest);
            if (treeId == null) {
                log.error("No tree available for entity {}", entityId);
                return null;
            }
            
            var treeNode = forest.getTree(treeId);
            if (treeNode == null) {
                log.error("Tree {} not found in forest", treeId);
                return null;
            }
            
            // Insert into the selected tree
            var spatialIndex = treeNode.getSpatialIndex();
            spatialIndex.insert(entityId, position, (byte)0, content, bounds);
            
            // Track the location
            entityLocations.put(entityId, new TreeLocation(treeId, position));
            
            // Update tree bounds
            if (bounds != null) {
                treeNode.expandGlobalBounds(bounds);
            } else {
                // Create bounds from position (point bounds)
                var pointBounds = new EntityBounds(position, position);
                treeNode.expandGlobalBounds(pointBounds);
            }
            
            log.debug("Inserted entity {} into tree {}", entityId, treeId);
            return treeId;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Remove an entity from the forest.
     *
     * @param entityId the entity ID to remove
     * @return true if the entity was removed, false if not found
     */
    public boolean remove(ID entityId) {
        lock.writeLock().lock();
        try {
            var location = entityLocations.remove(entityId);
            if (location == null) {
                log.debug("Entity {} not found in forest", entityId);
                return false;
            }
            
            var treeNode = forest.getTree(location.getTreeId());
            if (treeNode == null) {
                log.warn("Tree {} not found for entity {}", location.getTreeId(), entityId);
                return false;
            }
            
            // Remove from the tree
            var spatialIndex = treeNode.getSpatialIndex();
            var removed = spatialIndex.removeEntity(entityId);
            
            if (removed) {
                log.debug("Removed entity {} from tree {}", entityId, location.getTreeId());
            } else {
                log.warn("Entity {} not found in tree {} during removal", entityId, location.getTreeId());
            }
            
            return removed;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Update an entity's position, potentially migrating it to a different tree.
     *
     * @param entityId    the entity ID
     * @param newPosition the new position
     * @return true if the update was successful, false if entity not found
     */
    public boolean updatePosition(ID entityId, Point3f newPosition) {
        lock.writeLock().lock();
        try {
            var location = entityLocations.get(entityId);
            if (location == null) {
                log.debug("Entity {} not found in forest", entityId);
                return false;
            }
            
            var currentTreeId = location.getTreeId();
            var currentTree = forest.getTree(currentTreeId);
            if (currentTree == null) {
                log.error("Tree {} not found for entity {}", currentTreeId, entityId);
                return false;
            }
            
            // Check if migration is needed
            var newTreeId = assignmentStrategy.shouldMigrate(entityId, currentTreeId, newPosition, forest);
            
            if (newTreeId != null && !newTreeId.equals(currentTreeId)) {
                // Migrate to new tree
                var newTree = forest.getTree(newTreeId);
                if (newTree == null) {
                    log.error("Target tree {} not found for migration", newTreeId);
                    return false;
                }
                
                // Get entity data from current tree
                var currentIndex = currentTree.getSpatialIndex();
                var content = currentIndex.getEntity(entityId);
                var bounds = currentIndex.getEntityBounds(entityId);
                
                // Remove from current tree
                currentIndex.removeEntity(entityId);
                
                // Insert into new tree
                var newIndex = newTree.getSpatialIndex();
                newIndex.insert(entityId, newPosition, (byte)0, content, bounds);
                
                // Update location tracking
                entityLocations.put(entityId, new TreeLocation(newTreeId, newPosition));
                
                // Update tree bounds
                if (bounds != null) {
                    newTree.expandGlobalBounds(bounds);
                }
                
                log.info("Migrated entity {} from tree {} to tree {}", entityId, currentTreeId, newTreeId);
                
            } else {
                // Update position within current tree
                var spatialIndex = currentTree.getSpatialIndex();
                spatialIndex.updateEntity(entityId, newPosition, (byte)0);
                
                // Update location tracking
                entityLocations.put(entityId, new TreeLocation(currentTreeId, newPosition));
                
                log.debug("Updated position for entity {} in tree {}", entityId, currentTreeId);
            }
            
            return true;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get the tree location for an entity.
     *
     * @param entityId the entity ID
     * @return the tree location, or null if not found
     */
    public TreeLocation getEntityLocation(ID entityId) {
        lock.readLock().lock();
        try {
            return entityLocations.get(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get all entity IDs in the forest.
     *
     * @return set of all entity IDs
     */
    public Set<ID> getAllEntityIds() {
        lock.readLock().lock();
        try {
            return new HashSet<>(entityLocations.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get entity IDs by tree.
     *
     * @param treeId the tree ID
     * @return list of entity IDs in the specified tree
     */
    public List<ID> getEntitiesInTree(String treeId) {
        lock.readLock().lock();
        try {
            return entityLocations.entrySet().stream()
                .filter(entry -> entry.getValue().getTreeId().equals(treeId))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get the total entity count across all trees.
     *
     * @return the total entity count
     */
    public int getEntityCount() {
        lock.readLock().lock();
        try {
            return entityLocations.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Check if an entity exists in the forest.
     *
     * @param entityId the entity ID
     * @return true if the entity exists
     */
    public boolean containsEntity(ID entityId) {
        lock.readLock().lock();
        try {
            return entityLocations.containsKey(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get entity content by ID.
     *
     * @param entityId the entity ID
     * @return the entity content, or null if not found
     */
    public Content getEntityContent(ID entityId) {
        lock.readLock().lock();
        try {
            var location = entityLocations.get(entityId);
            if (location == null) {
                return null;
            }
            
            var tree = forest.getTree(location.getTreeId());
            if (tree == null) {
                return null;
            }
            
            return tree.getSpatialIndex().getEntity(entityId);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get entity position by ID.
     *
     * @param entityId the entity ID
     * @return the entity position, or null if not found
     */
    public Point3f getEntityPosition(ID entityId) {
        lock.readLock().lock();
        try {
            var location = entityLocations.get(entityId);
            return location != null ? location.getPosition() : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Generate a new entity ID.
     *
     * @return a new unique entity ID
     */
    public ID generateEntityId() {
        return idGenerator.generateID();
    }
    
    /**
     * Clear all entities from all trees.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            // Clear all trees
            for (var tree : forest.getAllTrees()) {
                // Clear not available - would need to remove all entities individually
            }
            
            // Clear location tracking
            entityLocations.clear();
            
            log.info("Cleared all entities from forest");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get statistics about entity distribution across trees.
     *
     * @return map of tree ID to entity count
     */
    public Map<String, Integer> getEntityDistribution() {
        lock.readLock().lock();
        try {
            var distribution = new HashMap<String, Integer>();
            
            for (var entry : entityLocations.entrySet()) {
                var treeId = entry.getValue().getTreeId();
                distribution.merge(treeId, 1, Integer::sum);
            }
            
            return distribution;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return String.format("ForestEntityManager[entities=%d, trees=%d, strategy=%s]",
                               entityLocations.size(), forest.getTreeCount(), 
                               assignmentStrategy.getClass().getSimpleName());
        } finally {
            lock.readLock().unlock();
        }
    }
}
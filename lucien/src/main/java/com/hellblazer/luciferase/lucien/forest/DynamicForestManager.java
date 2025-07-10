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
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Manages dynamic forest operations including tree addition/removal, splitting of overloaded trees,
 * and merging of underutilized trees. This class provides thread-safe operations for maintaining
 * an efficient forest structure as entity distributions change over time.
 *
 * <p>The DynamicForestManager monitors tree utilization and automatically splits trees that
 * exceed their capacity or merges trees that are underutilized. It handles entity migration
 * during these operations to maintain data consistency.
 *
 * <p>Key features:
 * <ul>
 *   <li>Dynamic tree addition and removal at runtime</li>
 *   <li>Automatic tree splitting based on configurable strategies</li>
 *   <li>Tree merging for underutilized trees</li>
 *   <li>Entity migration during split/merge operations</li>
 *   <li>Thread-safe concurrent operations</li>
 *   <li>Pluggable split/merge strategies</li>
 *   <li>Performance monitoring and statistics</li>
 * </ul>
 *
 * @param <Key>     The spatial key type (e.g., MortonKey, TetreeKey)
 * @param <ID>      The entity ID type
 * @param <Content> The content type stored with entities
 * @author hal.hildebrand
 */
public class DynamicForestManager<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(DynamicForestManager.class);
    
    /**
     * Interface for tree splitting strategies.
     */
    public interface SplitStrategy<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
        /**
         * Determine if a tree should be split.
         *
         * @param tree the tree to evaluate
         * @return true if the tree should be split
         */
        boolean shouldSplit(TreeNode<Key, ID, Content> tree);
        
        /**
         * Create split specifications for a tree.
         *
         * @param tree the tree to split
         * @return list of split specifications
         */
        List<SplitSpecification> createSplits(TreeNode<Key, ID, Content> tree);
    }
    
    /**
     * Interface for tree merging strategies.
     */
    public interface MergeStrategy<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
        /**
         * Find trees that should be merged.
         *
         * @param trees all trees in the forest
         * @return groups of trees to merge
         */
        List<MergeGroup> findMergeCandidates(List<TreeNode<Key, ID, Content>> trees);
    }
    
    /**
     * Specification for how to split a tree.
     */
    public static class SplitSpecification {
        private final String name;
        private final EntityBounds bounds;
        private final Map<String, Object> metadata;
        
        public SplitSpecification(String name, EntityBounds bounds) {
            this(name, bounds, new HashMap<>());
        }
        
        public SplitSpecification(String name, EntityBounds bounds, Map<String, Object> metadata) {
            this.name = Objects.requireNonNull(name);
            this.bounds = Objects.requireNonNull(bounds);
            this.metadata = new HashMap<>(metadata);
        }
        
        public String getName() {
            return name;
        }
        
        public EntityBounds getBounds() {
            return bounds;
        }
        
        public Map<String, Object> getMetadata() {
            return Collections.unmodifiableMap(metadata);
        }
    }
    
    /**
     * Group of trees to be merged.
     */
    public static class MergeGroup {
        private final List<String> treeIds;
        private final String targetTreeId;
        
        public MergeGroup(List<String> treeIds, String targetTreeId) {
            this.treeIds = new ArrayList<>(treeIds);
            this.targetTreeId = targetTreeId;
        }
        
        public List<String> getTreeIds() {
            return Collections.unmodifiableList(treeIds);
        }
        
        public String getTargetTreeId() {
            return targetTreeId;
        }
    }
    
    /**
     * Basic entity count-based split strategy.
     */
    public static class EntityCountSplitStrategy<Key extends SpatialKey<Key>, ID extends EntityID, Content> 
            implements SplitStrategy<Key, ID, Content> {
        
        private final int maxEntitiesPerTree;
        
        public EntityCountSplitStrategy(int maxEntitiesPerTree) {
            if (maxEntitiesPerTree <= 0) {
                throw new IllegalArgumentException("Max entities must be positive");
            }
            this.maxEntitiesPerTree = maxEntitiesPerTree;
        }
        
        @Override
        public boolean shouldSplit(TreeNode<Key, ID, Content> tree) {
            return tree.getEntityCount() > maxEntitiesPerTree;
        }
        
        @Override
        public List<SplitSpecification> createSplits(TreeNode<Key, ID, Content> tree) {
            var bounds = tree.getGlobalBounds();
            var min = bounds.getMin();
            var max = bounds.getMax();
            
            // Split into 8 octants
            var midX = (min.x + max.x) / 2.0f;
            var midY = (min.y + max.y) / 2.0f;
            var midZ = (min.z + max.z) / 2.0f;
            
            var splits = new ArrayList<SplitSpecification>();
            
            // Create 8 octant splits
            for (int i = 0; i < 8; i++) {
                var octantMin = new Point3f(
                    (i & 1) == 0 ? min.x : midX,
                    (i & 2) == 0 ? min.y : midY,
                    (i & 4) == 0 ? min.z : midZ
                );
                var octantMax = new Point3f(
                    (i & 1) == 0 ? midX : max.x,
                    (i & 2) == 0 ? midY : max.y,
                    (i & 4) == 0 ? midZ : max.z
                );
                
                var octantBounds = new EntityBounds(octantMin, octantMax);
                var name = tree.getTreeId() + "_octant_" + i;
                splits.add(new SplitSpecification(name, octantBounds));
            }
            
            return splits;
        }
    }
    
    /**
     * Basic underutilization merge strategy.
     */
    public static class UnderutilizedMergeStrategy<Key extends SpatialKey<Key>, ID extends EntityID, Content> 
            implements MergeStrategy<Key, ID, Content> {
        
        private final int minEntitiesPerTree;
        private final double maxMergedTreeEntities;
        
        public UnderutilizedMergeStrategy(int minEntitiesPerTree, double maxMergedTreeEntities) {
            this.minEntitiesPerTree = minEntitiesPerTree;
            this.maxMergedTreeEntities = maxMergedTreeEntities;
        }
        
        @Override
        public List<MergeGroup> findMergeCandidates(List<TreeNode<Key, ID, Content>> trees) {
            var mergeGroups = new ArrayList<MergeGroup>();
            var underutilized = trees.stream()
                .filter(tree -> tree.getEntityCount() < minEntitiesPerTree)
                .collect(Collectors.toList());
            
            if (underutilized.size() < 2) {
                return mergeGroups;
            }
            
            // Simple greedy merging based on spatial proximity
            var processed = new HashSet<String>();
            
            for (var tree1 : underutilized) {
                if (processed.contains(tree1.getTreeId())) {
                    continue;
                }
                
                var group = new ArrayList<String>();
                group.add(tree1.getTreeId());
                var totalEntities = tree1.getEntityCount();
                
                for (var tree2 : underutilized) {
                    if (tree1 == tree2 || processed.contains(tree2.getTreeId())) {
                        continue;
                    }
                    
                    // Check if trees are neighbors or spatially close
                    if (tree1.hasNeighbor(tree2.getTreeId()) || 
                        areSpatiallyClose(tree1, tree2)) {
                        
                        var newTotal = totalEntities + tree2.getEntityCount();
                        if (newTotal <= maxMergedTreeEntities) {
                            group.add(tree2.getTreeId());
                            totalEntities = newTotal;
                        }
                    }
                }
                
                if (group.size() > 1) {
                    processed.addAll(group);
                    // Choose the tree with most entities as target
                    var targetId = group.stream()
                        .max(Comparator.comparingInt(id -> 
                            trees.stream()
                                .filter(t -> t.getTreeId().equals(id))
                                .findFirst()
                                .map(TreeNode::getEntityCount)
                                .orElse(0)))
                        .orElse(group.get(0));
                    
                    mergeGroups.add(new MergeGroup(group, targetId));
                }
            }
            
            return mergeGroups;
        }
        
        private boolean areSpatiallyClose(TreeNode<Key, ID, Content> tree1, 
                                        TreeNode<Key, ID, Content> tree2) {
            var bounds1 = tree1.getGlobalBounds();
            var bounds2 = tree2.getGlobalBounds();
            
            // Check if bounds overlap or are adjacent
            var min1 = bounds1.getMin();
            var max1 = bounds1.getMax();
            var min2 = bounds2.getMin();
            var max2 = bounds2.getMax();
            
            // Allow small gap for "close" determination
            var gap = 0.1f;
            
            return min1.x <= max2.x + gap && max1.x + gap >= min2.x &&
                   min1.y <= max2.y + gap && max1.y + gap >= min2.y &&
                   min1.z <= max2.z + gap && max1.z + gap >= min2.z;
        }
    }
    
    // Core components
    private final Forest<Key, ID, Content> forest;
    private final ForestEntityManager<Key, ID, Content> entityManager;
    private final Supplier<AbstractSpatialIndex<Key, ID, Content>> treeFactory;
    
    // Strategies
    private volatile SplitStrategy<Key, ID, Content> splitStrategy;
    private volatile MergeStrategy<Key, ID, Content> mergeStrategy;
    
    // Monitoring and control
    private final ScheduledExecutorService scheduler;
    private volatile boolean autoManagementEnabled;
    private volatile long checkIntervalMs;
    
    // Statistics
    private final Map<String, TreeOperationStats> operationStats;
    private final ReadWriteLock statsLock;
    
    /**
     * Statistics for tree operations.
     */
    public static class TreeOperationStats {
        private int splitCount;
        private int mergeCount;
        private long lastSplitTime;
        private long lastMergeTime;
        private int entitiesMigrated;
        
        public synchronized void recordSplit(int entitiesMigrated) {
            this.splitCount++;
            this.lastSplitTime = System.currentTimeMillis();
            this.entitiesMigrated += entitiesMigrated;
        }
        
        public synchronized void recordMerge(int entitiesMigrated) {
            this.mergeCount++;
            this.lastMergeTime = System.currentTimeMillis();
            this.entitiesMigrated += entitiesMigrated;
        }
        
        public synchronized Map<String, Object> toMap() {
            var map = new HashMap<String, Object>();
            map.put("splitCount", splitCount);
            map.put("mergeCount", mergeCount);
            map.put("lastSplitTime", lastSplitTime);
            map.put("lastMergeTime", lastMergeTime);
            map.put("entitiesMigrated", entitiesMigrated);
            return map;
        }
    }
    
    /**
     * Create a dynamic forest manager.
     *
     * @param forest        the forest to manage
     * @param entityManager the entity manager
     * @param treeFactory   factory for creating new trees
     */
    public DynamicForestManager(Forest<Key, ID, Content> forest,
                               ForestEntityManager<Key, ID, Content> entityManager,
                               Supplier<AbstractSpatialIndex<Key, ID, Content>> treeFactory) {
        this.forest = Objects.requireNonNull(forest);
        this.entityManager = Objects.requireNonNull(entityManager);
        this.treeFactory = Objects.requireNonNull(treeFactory);
        
        // Default strategies
        this.splitStrategy = new EntityCountSplitStrategy<>(10000);
        this.mergeStrategy = new UnderutilizedMergeStrategy<>(100, 15000);
        
        // Monitoring setup
        this.scheduler = new ScheduledThreadPoolExecutor(1);
        this.autoManagementEnabled = false;
        this.checkIntervalMs = 60000; // 1 minute default
        
        // Statistics
        this.operationStats = new ConcurrentHashMap<>();
        this.statsLock = new ReentrantReadWriteLock();
        
        log.info("Created DynamicForestManager for forest with {} trees", forest.getTreeCount());
    }
    
    /**
     * Add a new tree to the forest at runtime.
     *
     * @param treeType the type of tree to add
     * @param name     optional name for the tree
     * @param bounds   optional initial bounds
     * @return the ID of the newly added tree
     */
    public String addTree(TreeMetadata.TreeType treeType, String name, EntityBounds bounds) {
        var tree = createTree(treeType);
        
        var metadata = TreeMetadata.builder()
            .name(name != null ? name : "dynamic_tree")
            .treeType(treeType)
            .creationTimestamp(Instant.now())
            .property("dynamic", true)
            .property("initialBounds", bounds)
            .build();
        
        var treeId = forest.addTree(tree, metadata);
        
        if (bounds != null) {
            var treeNode = forest.getTree(treeId);
            treeNode.expandGlobalBounds(bounds);
        }
        
        log.info("Added new {} tree '{}' to forest", treeType, treeId);
        return treeId;
    }
    
    /**
     * Remove a tree from the forest, migrating its entities.
     *
     * @param treeId            the ID of the tree to remove
     * @param targetTreeId      optional target tree for entity migration
     * @return true if the tree was removed
     */
    public boolean removeTree(String treeId, String targetTreeId) {
        var tree = forest.getTree(treeId);
        if (tree == null) {
            log.warn("Tree {} not found for removal", treeId);
            return false;
        }
        
        // Migrate entities if any exist
        var entities = entityManager.getEntitiesInTree(treeId);
        if (!entities.isEmpty()) {
            if (targetTreeId == null) {
                // Select a target tree automatically
                var otherTrees = forest.getAllTrees().stream()
                    .filter(t -> !t.getTreeId().equals(treeId))
                    .collect(Collectors.toList());
                
                if (otherTrees.isEmpty()) {
                    log.error("Cannot remove last tree with entities");
                    return false;
                }
                
                // Choose tree with fewest entities
                targetTreeId = otherTrees.stream()
                    .min(Comparator.comparingInt(TreeNode::getEntityCount))
                    .map(TreeNode::getTreeId)
                    .orElse(null);
            }
            
            log.info("Migrating {} entities from tree {} to {}", 
                    entities.size(), treeId, targetTreeId);
            
            migrateEntities(entities, treeId, targetTreeId);
        }
        
        // Remove the tree
        var removed = forest.removeTree(treeId);
        if (removed) {
            log.info("Removed tree {} from forest", treeId);
        }
        
        return removed;
    }
    
    /**
     * Check for and execute tree splits based on the current strategy.
     *
     * @return number of trees split
     */
    public int checkAndSplitTrees() {
        var splitCount = 0;
        var treesToSplit = forest.getAllTrees().stream()
            .filter(splitStrategy::shouldSplit)
            .collect(Collectors.toList());
        
        for (var tree : treesToSplit) {
            if (splitTree(tree)) {
                splitCount++;
            }
        }
        
        return splitCount;
    }
    
    /**
     * Check for and execute tree merges based on the current strategy.
     *
     * @return number of merge operations performed
     */
    public int checkAndMergeTrees() {
        var mergeGroups = mergeStrategy.findMergeCandidates(forest.getAllTrees());
        var mergeCount = 0;
        
        for (var group : mergeGroups) {
            if (mergeTrees(group)) {
                mergeCount++;
            }
        }
        
        return mergeCount;
    }
    
    /**
     * Split a tree into multiple trees.
     *
     * @param tree the tree to split
     * @return true if the split was successful
     */
    public boolean splitTree(TreeNode<Key, ID, Content> tree) {
        var treeId = tree.getTreeId();
        log.info("Splitting tree {} with {} entities", treeId, tree.getEntityCount());
        
        try {
            // Create split specifications
            var splits = splitStrategy.createSplits(tree);
            if (splits.isEmpty()) {
                log.warn("No splits created for tree {}", treeId);
                return false;
            }
            
            // Create new trees
            var newTreeIds = new ArrayList<String>();
            for (var split : splits) {
                var metadata = tree.getMetadata("metadata");
                var treeType = TreeMetadata.TreeType.OCTREE;
                if (metadata instanceof TreeMetadata) {
                    treeType = ((TreeMetadata) metadata).getTreeType();
                }
                
                var newTreeId = addTree(treeType, split.getName(), split.getBounds());
                newTreeIds.add(newTreeId);
                
                // Set up neighbor relationships
                forest.addNeighborRelationship(treeId, newTreeId);
                for (var otherId : newTreeIds) {
                    if (!otherId.equals(newTreeId)) {
                        forest.addNeighborRelationship(newTreeId, otherId);
                    }
                }
            }
            
            // Migrate entities to appropriate new trees
            var entities = entityManager.getEntitiesInTree(treeId);
            var migratedCount = 0;
            
            for (var entityId : entities) {
                var position = entityManager.getEntityPosition(entityId);
                if (position != null) {
                    // Find best matching split
                    var bestTree = findBestTreeForPosition(position, newTreeIds);
                    if (bestTree != null) {
                        // Update entity manager to move entity
                        entityManager.remove(entityId);
                        var content = tree.getSpatialIndex().getEntity(entityId);
                        var bounds = tree.getSpatialIndex().getEntityBounds(entityId);
                        entityManager.insert(entityId, content, position, bounds);
                        migratedCount++;
                    }
                }
            }
            
            // Remove original tree
            removeTree(treeId, null);
            
            // Record statistics
            getOrCreateStats(treeId).recordSplit(migratedCount);
            
            log.info("Successfully split tree {} into {} new trees, migrated {} entities",
                    treeId, newTreeIds.size(), migratedCount);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to split tree {}", treeId, e);
            return false;
        }
    }
    
    /**
     * Merge multiple trees into one.
     *
     * @param mergeGroup the group of trees to merge
     * @return true if the merge was successful
     */
    public boolean mergeTrees(MergeGroup mergeGroup) {
        var targetId = mergeGroup.getTargetTreeId();
        var sourceIds = mergeGroup.getTreeIds().stream()
            .filter(id -> !id.equals(targetId))
            .collect(Collectors.toList());
        
        log.info("Merging {} trees into tree {}", sourceIds.size(), targetId);
        
        try {
            var migratedCount = 0;
            
            for (var sourceId : sourceIds) {
                var entities = entityManager.getEntitiesInTree(sourceId);
                migratedCount += migrateEntities(entities, sourceId, targetId);
                
                // Remove source tree
                forest.removeTree(sourceId);
                
                // Record statistics
                getOrCreateStats(sourceId).recordMerge(entities.size());
            }
            
            // Update target tree statistics
            getOrCreateStats(targetId).recordMerge(migratedCount);
            
            log.info("Successfully merged {} trees into {}, migrated {} entities",
                    sourceIds.size(), targetId, migratedCount);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to merge trees into {}", targetId, e);
            return false;
        }
    }
    
    /**
     * Set the split strategy.
     *
     * @param strategy the new split strategy
     */
    public void setSplitStrategy(SplitStrategy<Key, ID, Content> strategy) {
        this.splitStrategy = Objects.requireNonNull(strategy);
        log.info("Updated split strategy to {}", strategy.getClass().getSimpleName());
    }
    
    /**
     * Set the merge strategy.
     *
     * @param strategy the new merge strategy
     */
    public void setMergeStrategy(MergeStrategy<Key, ID, Content> strategy) {
        this.mergeStrategy = Objects.requireNonNull(strategy);
        log.info("Updated merge strategy to {}", strategy.getClass().getSimpleName());
    }
    
    /**
     * Enable automatic forest management.
     *
     * @param intervalMs check interval in milliseconds
     */
    public void enableAutoManagement(long intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Interval must be positive");
        }
        
        this.checkIntervalMs = intervalMs;
        this.autoManagementEnabled = true;
        
        scheduler.scheduleAtFixedRate(this::performAutoManagement, 
            intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        
        log.info("Enabled auto-management with {}ms interval", intervalMs);
    }
    
    /**
     * Disable automatic forest management.
     */
    public void disableAutoManagement() {
        this.autoManagementEnabled = false;
        scheduler.shutdown();
        log.info("Disabled auto-management");
    }
    
    /**
     * Get operation statistics for all trees.
     *
     * @return map of tree ID to statistics
     */
    public Map<String, Map<String, Object>> getOperationStatistics() {
        statsLock.readLock().lock();
        try {
            var result = new HashMap<String, Map<String, Object>>();
            for (var entry : operationStats.entrySet()) {
                result.put(entry.getKey(), entry.getValue().toMap());
            }
            return result;
        } finally {
            statsLock.readLock().unlock();
        }
    }
    
    /**
     * Shutdown the manager and release resources.
     */
    public void shutdown() {
        disableAutoManagement();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Dynamic forest manager shutdown complete");
    }
    
    // Helper methods
    
    private AbstractSpatialIndex<Key, ID, Content> createTree(TreeMetadata.TreeType type) {
        var tree = treeFactory.get();
        if (type == TreeMetadata.TreeType.OCTREE && !(tree instanceof Octree)) {
            log.warn("Tree factory produced non-Octree for OCTREE type");
        } else if (type == TreeMetadata.TreeType.TETREE && !(tree instanceof Tetree)) {
            log.warn("Tree factory produced non-Tetree for TETREE type");
        }
        return tree;
    }
    
    private int migrateEntities(List<ID> entities, String sourceTreeId, String targetTreeId) {
        var sourceTree = forest.getTree(sourceTreeId);
        var targetTree = forest.getTree(targetTreeId);
        
        if (sourceTree == null || targetTree == null) {
            log.error("Cannot migrate: source or target tree not found");
            return 0;
        }
        
        var migratedCount = 0;
        var sourceIndex = sourceTree.getSpatialIndex();
        
        for (var entityId : entities) {
            try {
                var position = entityManager.getEntityPosition(entityId);
                var content = sourceIndex.getEntity(entityId);
                var bounds = sourceIndex.getEntityBounds(entityId);
                
                if (position != null) {
                    entityManager.remove(entityId);
                    entityManager.insert(entityId, content, position, bounds);
                    migratedCount++;
                }
            } catch (Exception e) {
                log.error("Failed to migrate entity {}", entityId, e);
            }
        }
        
        return migratedCount;
    }
    
    private String findBestTreeForPosition(Point3f position, List<String> treeIds) {
        var bestTree = (String) null;
        var bestScore = Double.MAX_VALUE;
        
        for (var treeId : treeIds) {
            var tree = forest.getTree(treeId);
            if (tree != null) {
                var bounds = tree.getGlobalBounds();
                if (containsPoint(bounds, position)) {
                    // Prefer trees that tightly contain the position
                    var score = calculateContainmentScore(bounds, position);
                    if (score < bestScore) {
                        bestScore = score;
                        bestTree = treeId;
                    }
                }
            }
        }
        
        // If no tree contains the position, choose closest
        if (bestTree == null && !treeIds.isEmpty()) {
            bestTree = treeIds.get(0);
        }
        
        return bestTree;
    }
    
    private boolean containsPoint(EntityBounds bounds, Point3f point) {
        var min = bounds.getMin();
        var max = bounds.getMax();
        return point.x >= min.x && point.x <= max.x &&
               point.y >= min.y && point.y <= max.y &&
               point.z >= min.z && point.z <= max.z;
    }
    
    private double calculateContainmentScore(EntityBounds bounds, Point3f point) {
        var min = bounds.getMin();
        var max = bounds.getMax();
        var volume = (max.x - min.x) * (max.y - min.y) * (max.z - min.z);
        return volume;
    }
    
    private TreeOperationStats getOrCreateStats(String treeId) {
        statsLock.writeLock().lock();
        try {
            return operationStats.computeIfAbsent(treeId, k -> new TreeOperationStats());
        } finally {
            statsLock.writeLock().unlock();
        }
    }
    
    private void performAutoManagement() {
        if (!autoManagementEnabled) {
            return;
        }
        
        try {
            log.debug("Performing automatic forest management check");
            
            // Refresh statistics
            forest.refreshAllStatistics();
            
            // Check for splits
            var splitCount = checkAndSplitTrees();
            if (splitCount > 0) {
                log.info("Auto-management split {} trees", splitCount);
            }
            
            // Check for merges
            var mergeCount = checkAndMergeTrees();
            if (mergeCount > 0) {
                log.info("Auto-management merged {} tree groups", mergeCount);
            }
            
        } catch (Exception e) {
            log.error("Error during auto-management", e);
        }
    }
}
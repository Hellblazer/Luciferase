/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A hierarchical forest that manages trees at multiple levels of detail.
 * This implementation provides automatic level-of-detail management,
 * hierarchical query optimization, and multi-level query routing.
 * 
 * Key features:
 * - Multiple levels of spatial detail (coarse to fine)
 * - Automatic entity promotion/demotion between levels
 * - Level-of-detail queries based on camera distance
 * - Hierarchical query optimization
 * - Cross-level entity consistency
 * 
 * @param <Key> The spatial key type
 * @param <ID> The entity ID type
 * @param <Content> The entity content type
 */
public class HierarchicalForest<Key extends SpatialKey<Key>, ID extends EntityID, Content> 
    extends Forest<Key, ID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(HierarchicalForest.class);
    
    /**
     * Configuration for hierarchical forest
     */
    public static class HierarchyConfig {
        private final int maxLevels;
        private final float[] levelDistances;
        private final int[] maxEntitiesPerLevel;
        private final boolean autoLevelManagement;
        private final float levelTransitionHysteresis;
        private final LevelSelectionStrategy levelStrategy;
        
        public enum LevelSelectionStrategy {
            DISTANCE_BASED,     // Select level based on viewer distance
            DENSITY_BASED,      // Select level based on entity density
            IMPORTANCE_BASED,   // Select level based on entity importance
            HYBRID             // Combine multiple strategies
        }
        
        private HierarchyConfig(Builder builder) {
            this.maxLevels = builder.maxLevels;
            this.levelDistances = builder.levelDistances;
            this.maxEntitiesPerLevel = builder.maxEntitiesPerLevel;
            this.autoLevelManagement = builder.autoLevelManagement;
            this.levelTransitionHysteresis = builder.levelTransitionHysteresis;
            this.levelStrategy = builder.levelStrategy;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private int maxLevels = 3;
            private float[] levelDistances = {100.0f, 500.0f, 2000.0f};
            private int[] maxEntitiesPerLevel = {1000, 5000, 20000};
            private boolean autoLevelManagement = true;
            private float levelTransitionHysteresis = 0.1f;
            private LevelSelectionStrategy levelStrategy = LevelSelectionStrategy.DISTANCE_BASED;
            
            public Builder maxLevels(int levels) {
                this.maxLevels = levels;
                return this;
            }
            
            public Builder levelDistances(float... distances) {
                this.levelDistances = distances;
                return this;
            }
            
            public Builder maxEntitiesPerLevel(int... maxEntities) {
                this.maxEntitiesPerLevel = maxEntities;
                return this;
            }
            
            public Builder autoLevelManagement(boolean enable) {
                this.autoLevelManagement = enable;
                return this;
            }
            
            public Builder levelTransitionHysteresis(float hysteresis) {
                this.levelTransitionHysteresis = hysteresis;
                return this;
            }
            
            public Builder levelStrategy(LevelSelectionStrategy strategy) {
                this.levelStrategy = strategy;
                return this;
            }
            
            public HierarchyConfig build() {
                // Validate configuration
                if (levelDistances.length != maxLevels) {
                    throw new IllegalStateException("Level distances must match max levels");
                }
                if (maxEntitiesPerLevel.length != maxLevels) {
                    throw new IllegalStateException("Max entities per level must match max levels");
                }
                return new HierarchyConfig(this);
            }
        }
        
        // Getters
        public int getMaxLevels() { return maxLevels; }
        public float[] getLevelDistances() { return levelDistances.clone(); }
        public int[] getMaxEntitiesPerLevel() { return maxEntitiesPerLevel.clone(); }
        public boolean isAutoLevelManagementEnabled() { return autoLevelManagement; }
        public float getLevelTransitionHysteresis() { return levelTransitionHysteresis; }
        public LevelSelectionStrategy getLevelStrategy() { return levelStrategy; }
    }
    
    /**
     * Represents a level in the hierarchy
     */
    private static class HierarchyLevel<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
        final int level;
        final float minDistance;
        final float maxDistance;
        final int maxEntities;
        final List<String> treeIds = new CopyOnWriteArrayList<>();
        final Map<ID, String> entityToTree = new ConcurrentHashMap<>();
        final AtomicInteger entityCount = new AtomicInteger(0);
        
        HierarchyLevel(int level, float minDistance, float maxDistance, int maxEntities) {
            this.level = level;
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
            this.maxEntities = maxEntities;
        }
        
        boolean canAcceptEntity() {
            return entityCount.get() < maxEntities;
        }
        
        void addEntity(ID entityId, String treeId) {
            entityToTree.put(entityId, treeId);
            entityCount.incrementAndGet();
        }
        
        void removeEntity(ID entityId) {
            if (entityToTree.remove(entityId) != null) {
                entityCount.decrementAndGet();
            }
        }
    }
    
    // Instance fields
    private final HierarchyConfig hierarchyConfig;
    private final EntityIDGenerator<ID> idGenerator;
    private final List<HierarchyLevel<Key, ID, Content>> levels;
    private final Map<ID, Integer> entityLevels;
    private final Map<String, Integer> treeLevels;
    private volatile Point3f viewerPosition = new Point3f(0, 0, 0);
    
    /**
     * Create a hierarchical forest with specified configuration
     */
    public HierarchicalForest(ForestConfig forestConfig, HierarchyConfig hierarchyConfig,
                             EntityIDGenerator<ID> idGenerator) {
        super(forestConfig);
        this.hierarchyConfig = Objects.requireNonNull(hierarchyConfig);
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.levels = new ArrayList<>(hierarchyConfig.maxLevels);
        this.entityLevels = new ConcurrentHashMap<>();
        this.treeLevels = new ConcurrentHashMap<>();
        
        // Initialize hierarchy levels
        initializeLevels();
        
        log.info("Created HierarchicalForest with {} levels", hierarchyConfig.maxLevels);
    }
    
    /**
     * Create hierarchical forest with default configuration
     */
    public HierarchicalForest(EntityIDGenerator<ID> idGenerator) {
        this(ForestConfig.defaultConfig(),
             HierarchyConfig.builder().build(),
             idGenerator);
    }
    
    /**
     * Initialize hierarchy levels
     */
    private void initializeLevels() {
        float prevDistance = 0;
        for (int i = 0; i < hierarchyConfig.maxLevels; i++) {
            var level = new HierarchyLevel<Key, ID, Content>(
                i,
                prevDistance,
                hierarchyConfig.levelDistances[i],
                hierarchyConfig.maxEntitiesPerLevel[i]
            );
            levels.add(level);
            prevDistance = hierarchyConfig.levelDistances[i];
        }
    }
    
    @Override
    public String addTree(AbstractSpatialIndex<Key, ID, Content> spatialIndex, TreeMetadata metadata) {
        var treeId = super.addTree(spatialIndex, metadata);
        
        // Determine level for this tree
        int level = 0;
        if (metadata != null) {
            var levelProp = metadata.getProperty("hierarchyLevel", Integer.class);
            if (levelProp != null) {
                level = Math.min(levelProp, hierarchyConfig.maxLevels - 1);
            }
        }
        
        // Track tree level
        treeLevels.put(treeId, level);
        levels.get(level).treeIds.add(treeId);
        
        log.debug("Added tree {} at hierarchy level {}", treeId, level);
        
        return treeId;
    }
    
    @Override
    public boolean removeTree(String treeId) {
        // Remove from level tracking
        var level = treeLevels.remove(treeId);
        if (level != null) {
            levels.get(level).treeIds.remove(treeId);
        }
        
        return super.removeTree(treeId);
    }
    
    /**
     * Create a tree at a specific hierarchy level
     */
    public String createLevelTree(int level, EntityBounds bounds, String name) {
        if (level < 0 || level >= hierarchyConfig.maxLevels) {
            throw new IllegalArgumentException("Invalid hierarchy level: " + level);
        }
        
        // Create appropriate tree type based on level
        AbstractSpatialIndex<Key, ID, Content> tree;
        if (level == 0) {
            // Coarsest level - use Tetree for memory efficiency
            tree = createTetree();
        } else {
            // Finer levels - use Octree for accuracy
            tree = createOctree();
        }
        
        // Create metadata
        var metadata = TreeMetadata.builder()
            .name(name != null ? name : "Level" + level + "_Tree")
            .treeType(tree instanceof Octree ? TreeMetadata.TreeType.OCTREE : TreeMetadata.TreeType.TETREE)
            .property("hierarchyLevel", level)
            .property("levelBounds", bounds)
            .build();
        
        var treeId = addTree(tree, metadata);
        
        // Set bounds
        var treeNode = getTree(treeId);
        if (treeNode != null) {
            treeNode.expandGlobalBounds(bounds);
        }
        
        return treeId;
    }
    
    @SuppressWarnings("unchecked")
    private AbstractSpatialIndex<Key, ID, Content> createOctree() {
        return (AbstractSpatialIndex<Key, ID, Content>) new Octree<ID, Content>(idGenerator);
    }
    
    @SuppressWarnings("unchecked")
    private AbstractSpatialIndex<Key, ID, Content> createTetree() {
        return (AbstractSpatialIndex<Key, ID, Content>) new Tetree<ID, Content>(idGenerator);
    }
    
    /**
     * Insert entity at appropriate level based on importance or distance
     */
    public void insertAtLevel(ID entityId, Content content, Point3f position, 
                            int preferredLevel, Object metadata) {
        // Clamp to valid level
        int level = Math.max(0, Math.min(preferredLevel, hierarchyConfig.maxLevels - 1));
        
        // Find best tree at this level
        var levelData = levels.get(level);
        String targetTreeId = null;
        
        // First try to find tree that contains position
        for (var treeId : levelData.treeIds) {
            var tree = getTree(treeId);
            if (tree != null && tree.getGlobalBounds() != null && 
                containsPoint(tree.getGlobalBounds(), position)) {
                targetTreeId = treeId;
                break;
            }
        }
        
        // If no containing tree, find nearest
        if (targetTreeId == null && !levelData.treeIds.isEmpty()) {
            targetTreeId = findNearestTree(position, levelData.treeIds);
        }
        
        // If still no tree, create one
        if (targetTreeId == null) {
            var bounds = new EntityBounds(
                new Point3f(position.x - 50, position.y - 50, position.z - 50),
                new Point3f(position.x + 50, position.y + 50, position.z + 50)
            );
            targetTreeId = createLevelTree(level, bounds, null);
        }
        
        // Insert entity
        var tree = getTree(targetTreeId);
        if (tree != null) {
            tree.getSpatialIndex().insert(entityId, position, (byte)0, content);
            levelData.addEntity(entityId, targetTreeId);
            entityLevels.put(entityId, level);
            
            log.trace("Inserted entity {} at level {} in tree {}", entityId, level, targetTreeId);
        }
    }
    
    /**
     * Update viewer position for LOD calculations
     */
    public void updateViewerPosition(Point3f position) {
        this.viewerPosition = new Point3f(position);
        
        if (hierarchyConfig.autoLevelManagement && 
            hierarchyConfig.levelStrategy == HierarchyConfig.LevelSelectionStrategy.DISTANCE_BASED) {
            updateEntityLevels();
        }
    }
    
    /**
     * Update entity levels based on current viewer position
     */
    private void updateEntityLevels() {
        // Check each entity to see if it should change levels
        var entitiesToMove = new ArrayList<EntityLevelChange<ID>>();
        
        for (var entry : entityLevels.entrySet()) {
            var entityId = entry.getKey();
            var currentLevel = entry.getValue();
            
            // Get entity position
            Point3f position = null;
            for (var level : levels) {
                var treeId = level.entityToTree.get(entityId);
                if (treeId != null) {
                    var tree = getTree(treeId);
                    if (tree != null) {
                        position = tree.getSpatialIndex().getEntityPosition(entityId);
                        break;
                    }
                }
            }
            
            if (position != null) {
                var distance = viewerPosition.distance(position);
                var targetLevel = calculateTargetLevel(distance, currentLevel);
                
                if (targetLevel != currentLevel) {
                    entitiesToMove.add(new EntityLevelChange<>(entityId, currentLevel, targetLevel));
                }
            }
        }
        
        // Move entities to new levels
        for (var change : entitiesToMove) {
            moveEntityToLevel(change.entityId, change.fromLevel, change.toLevel);
        }
    }
    
    /**
     * Calculate target level based on distance and hysteresis
     */
    private int calculateTargetLevel(float distance, int currentLevel) {
        // Apply hysteresis to prevent oscillation
        var hysteresis = hierarchyConfig.levelTransitionHysteresis;
        
        // Check each level's distance range
        for (int i = 0; i < hierarchyConfig.maxLevels; i++) {
            var level = levels.get(i);
            
            // Apply hysteresis based on direction
            float minDist = level.minDistance;
            float maxDist = level.maxDistance;
            
            if (i < currentLevel) {
                // Moving to coarser level - add hysteresis
                maxDist *= (1 + hysteresis);
            } else if (i > currentLevel) {
                // Moving to finer level - subtract hysteresis
                minDist *= (1 - hysteresis);
            }
            
            if (distance >= minDist && distance < maxDist) {
                return i;
            }
        }
        
        // Default to finest level if beyond all ranges
        return hierarchyConfig.maxLevels - 1;
    }
    
    /**
     * Move entity from one level to another
     */
    private void moveEntityToLevel(ID entityId, int fromLevel, int toLevel) {
        log.debug("Moving entity {} from level {} to level {}", entityId, fromLevel, toLevel);
        
        var fromLevelData = levels.get(fromLevel);
        var toLevelData = levels.get(toLevel);
        
        // Find entity in source level
        var sourceTreeId = fromLevelData.entityToTree.get(entityId);
        if (sourceTreeId == null) {
            return;
        }
        
        var sourceTree = getTree(sourceTreeId);
        if (sourceTree == null) {
            return;
        }
        
        // Get entity data
        var content = sourceTree.getSpatialIndex().getEntity(entityId);
        var position = sourceTree.getSpatialIndex().getEntityPosition(entityId);
        Object metadata = null; // AbstractSpatialIndex doesn't track metadata separately
        
        if (content == null || position == null) {
            return;
        }
        
        // Remove from source
        sourceTree.getSpatialIndex().removeEntity(entityId);
        fromLevelData.removeEntity(entityId);
        
        // Insert at new level
        insertAtLevel(entityId, content, position, toLevel, metadata);
    }
    
    /**
     * Perform hierarchical query that searches appropriate levels
     */
    public List<ID> hierarchicalQuery(Point3f queryPoint, float queryRadius,
                                     QueryMode mode) {
        // Determine which levels to query based on mode
        List<Integer> levelsToQuery = selectQueryLevels(queryPoint, queryRadius, mode);
        
        var results = new ArrayList<ID>();
        var seen = new HashSet<ID>();
        
        // Query each selected level
        for (int level : levelsToQuery) {
            var levelResults = queryLevel(level, queryPoint, queryRadius);
            for (var id : levelResults) {
                if (seen.add(id)) {
                    results.add(id);
                }
            }
        }
        
        return results;
    }
    
    /**
     * Query mode for hierarchical queries
     */
    public enum QueryMode {
        CURRENT_LOD,    // Query only the appropriate LOD level
        ALL_LEVELS,     // Query all levels
        PROGRESSIVE,    // Query from coarse to fine until enough results
        ADAPTIVE        // Adapt query strategy based on density
    }
    
    /**
     * Select which levels to query based on mode
     */
    private List<Integer> selectQueryLevels(Point3f queryPoint, float queryRadius, QueryMode mode) {
        var distance = viewerPosition.distance(queryPoint);
        
        switch (mode) {
            case CURRENT_LOD:
                // Only query the appropriate level for this distance
                int level = calculateTargetLevel(distance, 0);
                return Collections.singletonList(level);
                
            case ALL_LEVELS:
                // Query all levels
                var allLevels = new ArrayList<Integer>();
                for (int i = 0; i < hierarchyConfig.maxLevels; i++) {
                    allLevels.add(i);
                }
                return allLevels;
                
            case PROGRESSIVE:
                // Start from coarse and add finer levels
                var progressive = new ArrayList<Integer>();
                int targetLevel = calculateTargetLevel(distance, 0);
                for (int i = 0; i <= targetLevel; i++) {
                    progressive.add(i);
                }
                return progressive;
                
            case ADAPTIVE:
                // Use adaptive strategy based on query size and entity density
                return selectAdaptiveLevels(queryPoint, queryRadius);
                
            default:
                return Collections.singletonList(0);
        }
    }
    
    /**
     * Select levels using adaptive strategy
     */
    private List<Integer> selectAdaptiveLevels(Point3f queryPoint, float queryRadius) {
        var levels = new ArrayList<Integer>();
        
        // Estimate query volume
        float queryVolume = (4.0f / 3.0f) * (float)Math.PI * queryRadius * queryRadius * queryRadius;
        
        // Check each level's average entity density
        for (int i = 0; i < hierarchyConfig.maxLevels; i++) {
            var level = this.levels.get(i);
            if (level.entityCount.get() > 0 && !level.treeIds.isEmpty()) {
                // Estimate if this level is appropriate for the query
                float avgEntitiesPerTree = (float)level.entityCount.get() / level.treeIds.size();
                
                // Include level if it has reasonable density for the query volume
                if (avgEntitiesPerTree * queryVolume > 10) { // Threshold for inclusion
                    levels.add(i);
                }
            }
        }
        
        // Always include at least one level
        if (levels.isEmpty()) {
            levels.add(0);
        }
        
        return levels;
    }
    
    /**
     * Query entities at a specific level
     */
    private List<ID> queryLevel(int level, Point3f center, float radius) {
        var levelData = levels.get(level);
        var results = new ArrayList<ID>();
        
        // Query each tree at this level
        for (var treeId : levelData.treeIds) {
            var tree = getTree(treeId);
            if (tree != null) {
                // Check if tree bounds overlap query sphere
                if (tree.getGlobalBounds() == null || 
                    sphereIntersectsBounds(center, radius, tree.getGlobalBounds())) {
                    
                    var treeResults = tree.getSpatialIndex().kNearestNeighbors(
                        center, Integer.MAX_VALUE, radius
                    );
                    results.addAll(treeResults);
                }
            }
        }
        
        return results;
    }
    
    /**
     * Check if sphere intersects bounds
     */
    private boolean sphereIntersectsBounds(Point3f center, float radius, EntityBounds bounds) {
        // Find closest point on bounds to sphere center
        float closestX = Math.max(bounds.getMinX(), Math.min(center.x, bounds.getMaxX()));
        float closestY = Math.max(bounds.getMinY(), Math.min(center.y, bounds.getMaxY()));
        float closestZ = Math.max(bounds.getMinZ(), Math.min(center.z, bounds.getMaxZ()));
        
        // Check if closest point is within sphere radius
        float dx = closestX - center.x;
        float dy = closestY - center.y;
        float dz = closestZ - center.z;
        
        return (dx * dx + dy * dy + dz * dz) <= (radius * radius);
    }
    
    /**
     * Find K nearest neighbors using hierarchical optimization
     */
    public List<ID> hierarchicalKNN(Point3f queryPoint, int k) {
        // Start with coarse level for initial candidates
        var candidates = new TreeMap<Float, ID>();
        var seen = new HashSet<ID>();
        
        // Query progressively finer levels
        for (int level = 0; level < hierarchyConfig.maxLevels; level++) {
            var levelData = levels.get(level);
            
            // Query this level
            for (var treeId : levelData.treeIds) {
                var tree = getTree(treeId);
                if (tree != null) {
                    var results = tree.getSpatialIndex().kNearestNeighbors(queryPoint, k * 2, Float.MAX_VALUE);
                    
                    // Add to candidates with distance
                    for (var id : results) {
                        if (seen.add(id)) {
                            var position = tree.getSpatialIndex().getEntityPosition(id);
                            if (position != null) {
                                float distance = queryPoint.distance(position);
                                candidates.put(distance, id);
                            }
                        }
                    }
                }
            }
            
            // Check if we have enough good candidates
            if (candidates.size() >= k * 2) {
                // Check if remaining levels could have closer entities
                var kthDistance = new ArrayList<>(candidates.keySet()).get(Math.min(k, candidates.size() - 1));
                
                // If next level's minimum distance is greater than kth distance, we can stop
                if (level + 1 < hierarchyConfig.maxLevels) {
                    float nextLevelMinDist = levels.get(level + 1).minDistance;
                    if (nextLevelMinDist > kthDistance) {
                        break;
                    }
                }
            }
        }
        
        // Return top K
        return candidates.values().stream()
            .limit(k)
            .collect(Collectors.toList());
    }
    
    /**
     * Get statistics about the hierarchy
     */
    public HierarchyStatistics getHierarchyStatistics() {
        var stats = new HierarchyStatistics();
        stats.totalLevels = hierarchyConfig.maxLevels;
        stats.viewerPosition = new Point3f(viewerPosition);
        
        for (int i = 0; i < hierarchyConfig.maxLevels; i++) {
            var level = levels.get(i);
            var levelStats = new LevelStatistics();
            levelStats.level = i;
            levelStats.treeCount = level.treeIds.size();
            levelStats.entityCount = level.entityCount.get();
            levelStats.minDistance = level.minDistance;
            levelStats.maxDistance = level.maxDistance;
            levelStats.maxEntities = level.maxEntities;
            
            stats.levelStats.add(levelStats);
        }
        
        return stats;
    }
    
    /**
     * Check if bounds contains a point
     */
    private boolean containsPoint(EntityBounds bounds, Point3f point) {
        return point.x >= bounds.getMinX() && point.x <= bounds.getMaxX() &&
               point.y >= bounds.getMinY() && point.y <= bounds.getMaxY() &&
               point.z >= bounds.getMinZ() && point.z <= bounds.getMaxZ();
    }
    
    /**
     * Calculate distance from bounds to point
     */
    private float distanceToPoint(EntityBounds bounds, Point3f point) {
        // Find closest point on bounds to the query point
        float closestX = Math.max(bounds.getMinX(), Math.min(point.x, bounds.getMaxX()));
        float closestY = Math.max(bounds.getMinY(), Math.min(point.y, bounds.getMaxY()));
        float closestZ = Math.max(bounds.getMinZ(), Math.min(point.z, bounds.getMaxZ()));
        
        // Calculate distance
        float dx = closestX - point.x;
        float dy = closestY - point.y;
        float dz = closestZ - point.z;
        
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    /**
     * Find nearest tree to a position from a list
     */
    private String findNearestTree(Point3f position, List<String> treeIds) {
        String nearest = null;
        float minDistance = Float.MAX_VALUE;
        
        for (var treeId : treeIds) {
            var tree = getTree(treeId);
            if (tree != null && tree.getGlobalBounds() != null) {
                float distance = distanceToPoint(tree.getGlobalBounds(), position);
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = treeId;
                }
            }
        }
        
        return nearest;
    }
    
    // Helper classes
    
    private static class EntityLevelChange<ID extends EntityID> {
        final ID entityId;
        final int fromLevel;
        final int toLevel;
        
        EntityLevelChange(ID entityId, int fromLevel, int toLevel) {
            this.entityId = entityId;
            this.fromLevel = fromLevel;
            this.toLevel = toLevel;
        }
    }
    
    public static class HierarchyStatistics {
        public int totalLevels;
        public Point3f viewerPosition;
        public List<LevelStatistics> levelStats = new ArrayList<>();
    }
    
    public static class LevelStatistics {
        public int level;
        public int treeCount;
        public int entityCount;
        public float minDistance;
        public float maxDistance;
        public int maxEntities;
        
        @Override
        public String toString() {
            return String.format("Level %d: %d trees, %d/%d entities, distance [%.1f-%.1f]",
                level, treeCount, entityCount, maxEntities, minDistance, maxDistance);
        }
    }
    
    // Getters
    public HierarchyConfig getHierarchyConfig() {
        return hierarchyConfig;
    }
    
    public Point3f getViewerPosition() {
        return new Point3f(viewerPosition);
    }
    
    public int getEntityLevel(ID entityId) {
        return entityLevels.getOrDefault(entityId, -1);
    }
}
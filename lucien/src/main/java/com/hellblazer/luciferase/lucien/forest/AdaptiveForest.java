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
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * An adaptive forest that automatically adjusts its tree structure based on entity density
 * and access patterns. This forest type dynamically creates, splits, and merges trees
 * to maintain optimal performance.
 * 
 * Key features:
 * - Advanced entity density tracking and analysis
 * - Automatic tree subdivision when density thresholds are exceeded
 * - Tree merging when density falls below minimum thresholds
 * - Adaptive partitioning based on spatial distribution patterns
 * - Background adaptation with minimal impact on queries
 * 
 * @param <Key> The spatial key type
 * @param <ID> The entity ID type
 * @param <Content> The entity content type
 */
public class AdaptiveForest<Key extends SpatialKey<Key>, ID extends EntityID, Content> extends Forest<Key, ID, Content> {
    private static final Logger log = LoggerFactory.getLogger(AdaptiveForest.class);
    
    // Adaptation configuration
    public static class AdaptationConfig {
        private final int maxEntitiesPerTree;
        private final int minEntitiesPerTree;
        private final float densityThreshold;
        private final float minTreeVolume;
        private final float maxTreeVolume;
        private final int densityCheckInterval;
        private final boolean enableAutoSubdivision;
        private final boolean enableAutoMerging;
        private final SubdivisionStrategy subdivisionStrategy;
        
        public enum SubdivisionStrategy {
            OCTANT,      // Split into 8 equal octants
            BINARY_X,    // Binary split along X axis
            BINARY_Y,    // Binary split along Y axis
            BINARY_Z,    // Binary split along Z axis
            ADAPTIVE,    // Choose split axis based on entity distribution
            K_MEANS,     // Use k-means clustering for subdivision
            TETRAHEDRAL  // Dual-path tetrahedral subdivision (Phase 1: cubic→6 S0-S5 tets, tet→8 Bey children)
        }
        
        private AdaptationConfig(Builder builder) {
            this.maxEntitiesPerTree = builder.maxEntitiesPerTree;
            this.minEntitiesPerTree = builder.minEntitiesPerTree;
            this.densityThreshold = builder.densityThreshold;
            this.minTreeVolume = builder.minTreeVolume;
            this.maxTreeVolume = builder.maxTreeVolume;
            this.densityCheckInterval = builder.densityCheckInterval;
            this.enableAutoSubdivision = builder.enableAutoSubdivision;
            this.enableAutoMerging = builder.enableAutoMerging;
            this.subdivisionStrategy = builder.subdivisionStrategy;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private int maxEntitiesPerTree = 10000;
            private int minEntitiesPerTree = 100;
            private float densityThreshold = 100.0f; // entities per unit volume
            private float minTreeVolume = 1.0f;
            private float maxTreeVolume = 1000000.0f;
            private int densityCheckInterval = 1000; // check every N operations
            private boolean enableAutoSubdivision = true;
            private boolean enableAutoMerging = true;
            private SubdivisionStrategy subdivisionStrategy = SubdivisionStrategy.ADAPTIVE;
            
            public Builder maxEntitiesPerTree(int max) {
                this.maxEntitiesPerTree = max;
                return this;
            }
            
            public Builder minEntitiesPerTree(int min) {
                this.minEntitiesPerTree = min;
                return this;
            }
            
            public Builder densityThreshold(float threshold) {
                this.densityThreshold = threshold;
                return this;
            }
            
            public Builder minTreeVolume(float volume) {
                this.minTreeVolume = volume;
                return this;
            }
            
            public Builder maxTreeVolume(float volume) {
                this.maxTreeVolume = volume;
                return this;
            }
            
            public Builder densityCheckInterval(int interval) {
                this.densityCheckInterval = interval;
                return this;
            }
            
            public Builder enableAutoSubdivision(boolean enable) {
                this.enableAutoSubdivision = enable;
                return this;
            }
            
            public Builder enableAutoMerging(boolean enable) {
                this.enableAutoMerging = enable;
                return this;
            }
            
            public Builder subdivisionStrategy(SubdivisionStrategy strategy) {
                this.subdivisionStrategy = strategy;
                return this;
            }
            
            public AdaptationConfig build() {
                return new AdaptationConfig(this);
            }
        }
        
        // Getters
        public int getMaxEntitiesPerTree() { return maxEntitiesPerTree; }
        public int getMinEntitiesPerTree() { return minEntitiesPerTree; }
        public float getDensityThreshold() { return densityThreshold; }
        public float getMinTreeVolume() { return minTreeVolume; }
        public float getMaxTreeVolume() { return maxTreeVolume; }
        public int getDensityCheckInterval() { return densityCheckInterval; }
        public boolean isAutoSubdivisionEnabled() { return enableAutoSubdivision; }
        public boolean isAutoMergingEnabled() { return enableAutoMerging; }
        public SubdivisionStrategy getSubdivisionStrategy() { return subdivisionStrategy; }
    }
    
    // Density tracking data structure
    private static class DensityRegion<ID extends EntityID> {
        final String treeId;
        final EntityBounds bounds;
        final AtomicInteger entityCount = new AtomicInteger(0);
        final AtomicReference<Float> density = new AtomicReference<>(0.0f);
        final ConcurrentHashMap<ID, Point3f> entityPositions = new ConcurrentHashMap<>();
        volatile long lastUpdateTime = System.currentTimeMillis();
        
        DensityRegion(String treeId, EntityBounds bounds) {
            this.treeId = treeId;
            this.bounds = bounds;
        }
        
        void updateDensity() {
            float volume = calculateVolume(bounds);
            if (volume > 0) {
                density.set(entityCount.get() / volume);
            }
        }
        
        float getDensity() {
            return density.get();
        }
        
        private float calculateVolume(EntityBounds bounds) {
            float width = bounds.getMaxX() - bounds.getMinX();
            float height = bounds.getMaxY() - bounds.getMinY();
            float depth = bounds.getMaxZ() - bounds.getMinZ();
            return width * height * depth;
        }
    }
    
    // Instance fields
    private final AdaptationConfig adaptationConfig;
    private final EntityIDGenerator<ID> idGenerator;
    private final ConcurrentHashMap<String, DensityRegion<ID>> densityRegions;
    private final ScheduledExecutorService adaptationExecutor;
    private final AtomicInteger operationCounter;
    private final AtomicInteger subdivisionCount;
    private final AtomicInteger mergeCount;
    private volatile boolean adaptationEnabled = true;
    
    /**
     * Create an adaptive forest with specified configuration
     */
    public AdaptiveForest(ForestConfig forestConfig, AdaptationConfig adaptationConfig, 
                         EntityIDGenerator<ID> idGenerator) {
        super(forestConfig);
        this.adaptationConfig = Objects.requireNonNull(adaptationConfig);
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.densityRegions = new ConcurrentHashMap<>();
        this.operationCounter = new AtomicInteger(0);
        this.subdivisionCount = new AtomicInteger(0);
        this.mergeCount = new AtomicInteger(0);
        
        // Create adaptation executor for background processing
        this.adaptationExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "AdaptiveForest-Adaptation");
            thread.setDaemon(true);
            return thread;
        });
        
        // Schedule periodic density analysis
        adaptationExecutor.scheduleWithFixedDelay(
            this::performDensityAnalysis,
            10, 10, TimeUnit.SECONDS
        );
        
        log.info("Created AdaptiveForest with adaptation config: max={}, min={}, density={}", 
                adaptationConfig.maxEntitiesPerTree, 
                adaptationConfig.minEntitiesPerTree,
                adaptationConfig.densityThreshold);
    }
    
    /**
     * Create adaptive forest with default adaptation configuration
     */
    public AdaptiveForest(EntityIDGenerator<ID> idGenerator) {
        this(ForestConfig.defaultConfig(), 
             AdaptationConfig.builder().build(), 
             idGenerator);
    }
    
    @Override
    public String addTree(AbstractSpatialIndex<Key, ID, Content> spatialIndex, TreeMetadata metadata) {
        var treeId = super.addTree(spatialIndex, metadata);
        
        // Initialize density tracking for the new tree
        var treeNode = getTree(treeId);
        if (treeNode != null) {
            var bounds = treeNode.getGlobalBounds();
            if (bounds == null) {
                // Initialize with a default bounds
                bounds = new EntityBounds(
                    new Point3f(0, 0, 0),
                    new Point3f(100, 100, 100)
                );
                // Initialize bounds by expanding from empty
                treeNode.expandGlobalBounds(bounds);
            }
            var region = new DensityRegion<ID>(treeId, bounds);
            densityRegions.put(treeId, region);
        }
        
        return treeId;
    }
    
    @Override
    public boolean removeTree(String treeId) {
        densityRegions.remove(treeId);
        return super.removeTree(treeId);
    }
    
    /**
     * Track entity insertion for density analysis
     */
    public void trackEntityInsertion(String treeId, ID entityId, Point3f position) {
        var region = densityRegions.get(treeId);
        if (region != null) {
            region.entityPositions.put(entityId, position);
            region.entityCount.incrementAndGet();
            region.updateDensity();
            
            // Check if adaptation is needed
            if (operationCounter.incrementAndGet() % adaptationConfig.densityCheckInterval == 0) {
                checkAdaptationTriggers();
            }
        }
    }
    
    /**
     * Track entity removal for density analysis
     */
    public void trackEntityRemoval(String treeId, ID entityId) {
        var region = densityRegions.get(treeId);
        if (region != null) {
            region.entityPositions.remove(entityId);
            region.entityCount.decrementAndGet();
            region.updateDensity();
        }
    }
    
    /**
     * Track entity movement for density analysis
     */
    public void trackEntityMovement(String oldTreeId, String newTreeId, ID entityId, Point3f newPosition) {
        if (!oldTreeId.equals(newTreeId)) {
            trackEntityRemoval(oldTreeId, entityId);
            trackEntityInsertion(newTreeId, entityId, newPosition);
        } else {
            var region = densityRegions.get(oldTreeId);
            if (region != null) {
                region.entityPositions.put(entityId, newPosition);
            }
        }
    }
    
    /**
     * Perform comprehensive density analysis across all trees
     */
    private void performDensityAnalysis() {
        if (!adaptationEnabled) {
            return;
        }
        
        try {
            log.debug("Performing density analysis on {} trees", getTreeCount());
            
            // Analyze each tree's density
            var highDensityTrees = new ArrayList<String>();
            var lowDensityTrees = new ArrayList<String>();
            
            for (var entry : densityRegions.entrySet()) {
                var treeId = entry.getKey();
                var region = entry.getValue();
                var density = region.getDensity();
                var entityCount = region.entityCount.get();
                
                // Check for high density requiring subdivision
                if (adaptationConfig.enableAutoSubdivision &&
                    (entityCount > adaptationConfig.maxEntitiesPerTree || 
                     density > adaptationConfig.densityThreshold)) {
                    highDensityTrees.add(treeId);
                }
                
                // Check for low density that might benefit from merging
                if (adaptationConfig.enableAutoMerging &&
                    entityCount < adaptationConfig.minEntitiesPerTree &&
                    density < adaptationConfig.densityThreshold * 0.1f) {
                    lowDensityTrees.add(treeId);
                }
            }
            
            // Process high density trees
            for (var treeId : highDensityTrees) {
                considerSubdivision(treeId);
            }
            
            // Process low density trees for potential merging
            if (!lowDensityTrees.isEmpty()) {
                considerMerging(lowDensityTrees);
            }
            
        } catch (Exception e) {
            log.error("Error during density analysis", e);
        }
    }
    
    /**
     * Calculate volume of bounds
     */
    private float calculateVolume(EntityBounds bounds) {
        float width = bounds.getMaxX() - bounds.getMinX();
        float height = bounds.getMaxY() - bounds.getMinY();
        float depth = bounds.getMaxZ() - bounds.getMinZ();
        return width * height * depth;
    }
    
    /**
     * Check if immediate adaptation is needed based on triggers
     */
    private void checkAdaptationTriggers() {
        // Quick check for obvious triggers
        for (var region : densityRegions.values()) {
            if (region.entityCount.get() > adaptationConfig.maxEntitiesPerTree * 1.5) {
                // Urgent subdivision needed
                adaptationExecutor.execute(() -> considerSubdivision(region.treeId));
            }
        }
    }
    
    /**
     * Consider subdividing a high-density tree
     */
    private void considerSubdivision(String treeId) {
        var treeNode = getTree(treeId);
        var region = densityRegions.get(treeId);
        
        if (treeNode == null || region == null) {
            return;
        }
        
        var bounds = region.bounds;
        var volume = calculateVolume(bounds);
        
        // Check if tree is too small to subdivide
        if (volume <= adaptationConfig.minTreeVolume * 8) {
            log.debug("Tree {} too small to subdivide (volume: {})", treeId, volume);
            return;
        }
        
        log.info("Subdividing tree {} with {} entities (density: {})", 
                treeId, region.entityCount.get(), region.getDensity());
        
        // Determine subdivision strategy
        var strategy = adaptationConfig.subdivisionStrategy;
        if (strategy == AdaptationConfig.SubdivisionStrategy.ADAPTIVE) {
            strategy = determineOptimalSubdivisionStrategy(region);
        }
        
        // Perform subdivision based on strategy
        switch (strategy) {
            case OCTANT:
                subdivideOctant(treeNode, region);
                break;
            case BINARY_X:
                subdivideBinary(treeNode, region, 0);
                break;
            case BINARY_Y:
                subdivideBinary(treeNode, region, 1);
                break;
            case BINARY_Z:
                subdivideBinary(treeNode, region, 2);
                break;
            case K_MEANS:
                subdivideKMeans(treeNode, region);
                break;
            case TETRAHEDRAL:
                subdivideTetrahedral(treeNode, region);
                break;
            default:
                subdivideOctant(treeNode, region);
        }
        
        subdivisionCount.incrementAndGet();
        
        // Trigger ghost updates for all affected trees after subdivision
        triggerGhostUpdatesAfterSubdivision(treeId);
    }
    
    /**
     * Determine optimal subdivision strategy based on entity distribution
     */
    private AdaptationConfig.SubdivisionStrategy determineOptimalSubdivisionStrategy(DensityRegion region) {
        // Analyze entity distribution along each axis
        var positions = new ArrayList<>(region.entityPositions.values());
        if (positions.isEmpty()) {
            return AdaptationConfig.SubdivisionStrategy.OCTANT;
        }
        
        // Calculate variance along each axis
        var varianceX = calculateVariance(positions, 0);
        var varianceY = calculateVariance(positions, 1);
        var varianceZ = calculateVariance(positions, 2);
        
        // Choose strategy based on variance
        var maxVariance = Math.max(varianceX, Math.max(varianceY, varianceZ));
        
        if (maxVariance == varianceX) {
            return AdaptationConfig.SubdivisionStrategy.BINARY_X;
        } else if (maxVariance == varianceY) {
            return AdaptationConfig.SubdivisionStrategy.BINARY_Y;
        } else if (maxVariance == varianceZ) {
            return AdaptationConfig.SubdivisionStrategy.BINARY_Z;
        } else {
            // Similar variance on all axes, use octant subdivision
            return AdaptationConfig.SubdivisionStrategy.OCTANT;
        }
    }
    
    /**
     * Calculate variance of positions along a specific axis
     */
    private float calculateVariance(List<Point3f> positions, int axis) {
        if (positions.isEmpty()) {
            return 0;
        }
        
        // Calculate mean
        float sum = 0;
        for (var pos : positions) {
            sum += getAxisValue(pos, axis);
        }
        float mean = sum / positions.size();
        
        // Calculate variance
        float variance = 0;
        for (var pos : positions) {
            float diff = getAxisValue(pos, axis) - mean;
            variance += diff * diff;
        }
        
        return variance / positions.size();
    }
    
    private float getAxisValue(Point3f point, int axis) {
        switch (axis) {
            case 0: return point.x;
            case 1: return point.y;
            case 2: return point.z;
            default: throw new IllegalArgumentException("Invalid axis: " + axis);
        }
    }
    
    /**
     * Subdivide tree into 8 octants
     */
    private void subdivideOctant(TreeNode<Key, ID, Content> parentTree, DensityRegion region) {
        var bounds = region.bounds;
        var center = bounds.getCenter();
        
        // Create 8 child trees
        var childTrees = new ArrayList<TreeNode<Key, ID, Content>>(8);
        
        for (int i = 0; i < 8; i++) {
            var childBounds = createOctantBounds(bounds, i);
            var childTree = createChildTree(parentTree, childBounds, i);
            childTrees.add(childTree);
        }
        
        // Redistribute entities to child trees
        redistributeEntities(parentTree, childTrees, region);
        
        // Remove parent tree
        removeTree(parentTree.getTreeId());
    }
    
    /**
     * Create bounds for an octant
     */
    private EntityBounds createOctantBounds(EntityBounds parentBounds, int octant) {
        var min = parentBounds.getMin();
        var max = parentBounds.getMax();
        var center = parentBounds.getCenter();
        
        // Determine min/max for each octant
        float minX = (octant & 1) == 0 ? min.x : center.x;
        float maxX = (octant & 1) == 0 ? center.x : max.x;
        float minY = (octant & 2) == 0 ? min.y : center.y;
        float maxY = (octant & 2) == 0 ? center.y : max.y;
        float minZ = (octant & 4) == 0 ? min.z : center.z;
        float maxZ = (octant & 4) == 0 ? center.z : max.z;
        
        return new EntityBounds(
            new Point3f(minX, minY, minZ),
            new Point3f(maxX, maxY, maxZ)
        );
    }
    
    /**
     * Binary subdivision along a specific axis
     */
    private void subdivideBinary(TreeNode<Key, ID, Content> parentTree, DensityRegion region, int axis) {
        var bounds = region.bounds;
        var center = bounds.getCenter();
        
        // Create two child trees split along the specified axis
        var bounds1 = createBinarySplitBounds(bounds, axis, false);
        var bounds2 = createBinarySplitBounds(bounds, axis, true);
        
        var child1 = createChildTree(parentTree, bounds1, 0);
        var child2 = createChildTree(parentTree, bounds2, 1);
        
        var childTrees = Arrays.asList(child1, child2);
        
        // Redistribute entities
        redistributeEntities(parentTree, childTrees, region);
        
        // Remove parent tree
        removeTree(parentTree.getTreeId());
    }
    
    /**
     * Create bounds for binary split
     */
    private EntityBounds createBinarySplitBounds(EntityBounds parentBounds, int axis, boolean upperHalf) {
        var min = new Point3f(parentBounds.getMin());
        var max = new Point3f(parentBounds.getMax());
        var center = parentBounds.getCenter();
        
        if (axis == 0) { // X axis
            if (upperHalf) {
                min.x = center.x;
            } else {
                max.x = center.x;
            }
        } else if (axis == 1) { // Y axis
            if (upperHalf) {
                min.y = center.y;
            } else {
                max.y = center.y;
            }
        } else { // Z axis
            if (upperHalf) {
                min.z = center.z;
            } else {
                max.z = center.z;
            }
        }
        
        return new EntityBounds(min, max);
    }
    
    /**
     * K-means based subdivision
     */
    private void subdivideKMeans(TreeNode<Key, ID, Content> parentTree, DensityRegion region) {
        var positions = new ArrayList<>(region.entityPositions.values());
        if (positions.size() < 8) {
            // Fall back to octant subdivision for small entity counts
            subdivideOctant(parentTree, region);
            return;
        }
        
        // Perform k-means clustering with k=8
        var clusters = performKMeansClustering(positions, 8);
        
        // Create child trees based on clusters
        var childTrees = new ArrayList<TreeNode<Key, ID, Content>>();
        
        for (int i = 0; i < clusters.size(); i++) {
            @SuppressWarnings("unchecked")
            var cluster = (List<Point3f>) clusters.get(i);
            if (!cluster.isEmpty()) {
                var clusterBounds = calculateClusterBounds(cluster);
                var childTree = createChildTree(parentTree, clusterBounds, i);
                childTrees.add(childTree);
            }
        }
        
        // Redistribute entities based on clustering
        redistributeEntitiesByClusters(parentTree, childTrees, region, clusters);

        // Remove parent tree
        removeTree(parentTree.getTreeId());
    }

    /**
     * Subdivide tree using dual-path tetrahedral strategy (Phase 1).
     *
     * Dispatches based on parent TreeBounds type:
     * - CubicBounds parent → 6 S0-S5 characteristic tetrahedra (Case A)
     * - TetrahedralBounds parent → 8 Bey subdivision children (Case B)
     *
     * CRITICAL: Uses pattern matching on TreeBounds sealed interface to ensure
     * type-safe dispatch to correct subdivision algorithm.
     *
     * @param parentTree the tree to subdivide
     * @param region the density region
     */
    private void subdivideTetrahedral(TreeNode<Key, ID, Content> parentTree, DensityRegion region) {
        // TODO: Implement dual-path tetrahedral subdivision (Steps 4-6)
        // This stub will be replaced with full implementation in Phase 1 Steps 4-6
        throw new UnsupportedOperationException(
            "Tetrahedral subdivision not yet implemented. " +
            "Requires: (1) dual-path dispatcher, (2) subdivideCubicToTets (6 S0-S5 children), " +
            "(3) subdivideTetToSubTets (8 Bey children), (4) redistributeEntitiesTetrahedral. " +
            "See Phase 1 architecture v2 for complete specification."
        );
    }

    /**
     * Simple k-means clustering implementation
     */
    private List<List<Point3f>> performKMeansClustering(List<Point3f> points, int k) {
        // Initialize cluster centers randomly
        var centers = new ArrayList<Point3f>(k);
        var random = new Random();
        
        for (int i = 0; i < k; i++) {
            centers.add(new Point3f(points.get(random.nextInt(points.size()))));
        }
        
        // Iterative k-means
        var clusters = new ArrayList<List<Point3f>>(k);
        var changed = true;
        var iterations = 0;
        
        while (changed && iterations < 20) { // Max 20 iterations
            // Clear clusters
            clusters.clear();
            for (int i = 0; i < k; i++) {
                clusters.add(new ArrayList<>());
            }
            
            // Assign points to nearest center
            for (var point : points) {
                int nearestCluster = 0;
                float minDist = Float.MAX_VALUE;
                
                for (int i = 0; i < k; i++) {
                    float dist = point.distance(centers.get(i));
                    if (dist < minDist) {
                        minDist = dist;
                        nearestCluster = i;
                    }
                }
                
                clusters.get(nearestCluster).add(point);
            }
            
            // Update centers
            changed = false;
            for (int i = 0; i < k; i++) {
                var cluster = clusters.get(i);
                if (!cluster.isEmpty()) {
                    var newCenter = calculateCentroid(cluster);
                    if (newCenter.distance(centers.get(i)) > 0.01f) {
                        changed = true;
                    }
                    centers.set(i, newCenter);
                }
            }
            
            iterations++;
        }
        
        return clusters;
    }
    
    /**
     * Calculate centroid of a set of points
     */
    private Point3f calculateCentroid(List<Point3f> points) {
        var centroid = new Point3f(0, 0, 0);
        for (var point : points) {
            centroid.add(point);
        }
        centroid.scale(1.0f / points.size());
        return centroid;
    }
    
    /**
     * Calculate bounds for a cluster of points
     */
    private EntityBounds calculateClusterBounds(List<Point3f> cluster) {
        var min = new Point3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        var max = new Point3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        
        for (var point : cluster) {
            min.x = Math.min(min.x, point.x);
            min.y = Math.min(min.y, point.y);
            min.z = Math.min(min.z, point.z);
            max.x = Math.max(max.x, point.x);
            max.y = Math.max(max.y, point.y);
            max.z = Math.max(max.z, point.z);
        }
        
        // Add small padding
        var padding = new Vector3f(1, 1, 1);
        min.sub(padding);
        max.add(padding);
        
        return new EntityBounds(min, max);
    }
    
    /**
     * Consider merging low-density trees
     */
    private void considerMerging(List<String> lowDensityTrees) {
        if (lowDensityTrees.size() < 2) {
            return;
        }
        
        log.debug("Considering merge of {} low-density trees", lowDensityTrees.size());
        
        // Find adjacent trees that can be merged
        var merged = new HashSet<String>();
        
        for (int i = 0; i < lowDensityTrees.size(); i++) {
            if (merged.contains(lowDensityTrees.get(i))) {
                continue;
            }
            
            var tree1Id = lowDensityTrees.get(i);
            var tree1 = getTree(tree1Id);
            if (tree1 == null) continue;
            
            // Find a suitable merge partner
            for (int j = i + 1; j < lowDensityTrees.size(); j++) {
                if (merged.contains(lowDensityTrees.get(j))) {
                    continue;
                }
                
                var tree2Id = lowDensityTrees.get(j);
                var tree2 = getTree(tree2Id);
                if (tree2 == null) continue;
                
                // Check if trees are adjacent or overlapping
                if (areTreesAdjacent(tree1, tree2)) {
                    mergeTrees(tree1, tree2);
                    merged.add(tree1Id);
                    merged.add(tree2Id);
                    mergeCount.incrementAndGet();
                    break;
                }
            }
        }
    }
    
    /**
     * Create a child tree
     */
    @SuppressWarnings("unchecked")
    private TreeNode<Key, ID, Content> createChildTree(TreeNode<Key, ID, Content> parentTree, 
                                                      EntityBounds bounds, int childIndex) {
        // Determine tree type from parent
        var parentIndex = parentTree.getSpatialIndex();
        AbstractSpatialIndex<Key, ID, Content> childSpatialIndex;
        
        if (parentIndex instanceof Octree) {
            childSpatialIndex = (AbstractSpatialIndex<Key, ID, Content>) new Octree<ID, Content>(idGenerator);
        } else if (parentIndex instanceof Tetree) {
            childSpatialIndex = (AbstractSpatialIndex<Key, ID, Content>) new Tetree<ID, Content>(idGenerator);
        } else {
            throw new IllegalStateException("Unknown spatial index type: " + parentIndex.getClass());
        }
        
        // Create metadata for child
        var parentMetadata = parentTree.getMetadata("metadata");
        // Use simple naming - the hash-based ID will ensure uniqueness
        var childMetadata = TreeMetadata.builder()
            .name("SubTree")
            .treeType(parentMetadata instanceof TreeMetadata ? 
                     ((TreeMetadata)parentMetadata).getTreeType() : TreeMetadata.TreeType.OCTREE)
            .property("parentId", parentTree.getTreeId())
            .property("childIndex", childIndex)
            .property("subdivisionTime", System.currentTimeMillis())
            .build();
        
        // Add to forest
        var childId = addTree(childSpatialIndex, childMetadata);
        var childTree = getTree(childId);
        childTree.expandGlobalBounds(bounds);
        
        return childTree;
    }
    
    /**
     * Redistribute entities from parent to child trees
     */
    private void redistributeEntities(TreeNode<Key, ID, Content> parentTree,
                                    List<TreeNode<Key, ID, Content>> childTrees,
                                    DensityRegion<ID> parentRegion) {
        log.debug("Redistributing {} entities from parent {} to {} children",
                 parentRegion.entityCount.get(), parentTree.getTreeId(), childTrees.size());
        
        // For each entity in parent, determine which child it belongs to
        var parentIndex = parentTree.getSpatialIndex();
        var redistributed = 0;
        
        for (Map.Entry<ID, Point3f> entry : parentRegion.entityPositions.entrySet()) {
            var entityId = entry.getKey();
            var position = entry.getValue();
            
            // Find appropriate child tree
            TreeNode<Key, ID, Content> targetChild = null;
            for (var child : childTrees) {
                if (containsPoint(child.getGlobalBounds(), position)) {
                    targetChild = child;
                    break;
                }
            }
            
            if (targetChild != null) {
                // Get entity content from parent
                var content = parentIndex.getEntity(entityId);
                if (content != null) {
                    // Remove from parent and add to child
                    parentIndex.removeEntity(entityId);
                    targetChild.getSpatialIndex().insert(entityId, position, (byte)0, content);
                    redistributed++;
                    
                    // Update density tracking
                    trackEntityInsertion(targetChild.getTreeId(), entityId, position);
                }
            }
        }
        
        log.info("Redistributed {} entities from parent {} to children", 
                redistributed, parentTree.getTreeId());
    }
    
    /**
     * Redistribute entities based on k-means clusters
     */
    private void redistributeEntitiesByClusters(TreeNode<Key, ID, Content> parentTree,
                                              List<TreeNode<Key, ID, Content>> childTrees,
                                              DensityRegion<ID> parentRegion,
                                              List<List<Point3f>> clusters) {
        // Map each position to its cluster
        var positionToCluster = new HashMap<Point3f, Integer>();
        for (int i = 0; i < clusters.size(); i++) {
            for (var pos : clusters.get(i)) {
                positionToCluster.put(pos, i);
            }
        }
        
        // Redistribute based on clustering
        var parentIndex = parentTree.getSpatialIndex();
        
        for (Map.Entry<ID, Point3f> entry : parentRegion.entityPositions.entrySet()) {
            var entityId = entry.getKey();
            var position = entry.getValue();
            
            var clusterIndex = positionToCluster.get(position);
            if (clusterIndex != null && clusterIndex < childTrees.size()) {
                var targetChild = childTrees.get(clusterIndex);
                var content = parentIndex.getEntity(entityId);
                
                if (content != null) {
                    parentIndex.removeEntity(entityId);
                    targetChild.getSpatialIndex().insert(entityId, position, (byte)0, content);
                    trackEntityInsertion(targetChild.getTreeId(), entityId, position);
                }
            }
        }
    }
    
    /**
     * Check if two trees are adjacent
     */
    private boolean areTreesAdjacent(TreeNode<Key, ID, Content> tree1, TreeNode<Key, ID, Content> tree2) {
        var bounds1 = tree1.getGlobalBounds();
        var bounds2 = tree2.getGlobalBounds();
        
        if (bounds1 == null || bounds2 == null) {
            return false;
        }
        
        // Check if bounds are touching or overlapping
        var gap = 1.0f; // Allow small gap
        
        return !(bounds1.getMaxX() + gap < bounds2.getMinX() || 
                bounds2.getMaxX() + gap < bounds1.getMinX() ||
                bounds1.getMaxY() + gap < bounds2.getMinY() || 
                bounds2.getMaxY() + gap < bounds1.getMinY() ||
                bounds1.getMaxZ() + gap < bounds2.getMinZ() || 
                bounds2.getMaxZ() + gap < bounds1.getMinZ());
    }
    
    /**
     * Merge two trees into one
     */
    @SuppressWarnings("unchecked")
    private void mergeTrees(TreeNode<Key, ID, Content> tree1, TreeNode<Key, ID, Content> tree2) {
        log.info("Merging trees {} and {}", tree1.getTreeId(), tree2.getTreeId());
        
        // Calculate merged bounds
        var bounds1 = tree1.getGlobalBounds();
        var bounds2 = tree2.getGlobalBounds();
        var mergedBounds = new EntityBounds(
            new Point3f(
                Math.min(bounds1.getMinX(), bounds2.getMinX()),
                Math.min(bounds1.getMinY(), bounds2.getMinY()),
                Math.min(bounds1.getMinZ(), bounds2.getMinZ())
            ),
            new Point3f(
                Math.max(bounds1.getMaxX(), bounds2.getMaxX()),
                Math.max(bounds1.getMaxY(), bounds2.getMaxY()),
                Math.max(bounds1.getMaxZ(), bounds2.getMaxZ())
            )
        );
        
        // Create new merged tree
        AbstractSpatialIndex<Key, ID, Content> mergedIndex;
        if (tree1.getSpatialIndex() instanceof Octree) {
            mergedIndex = (AbstractSpatialIndex<Key, ID, Content>) new Octree<ID, Content>(idGenerator);
        } else {
            mergedIndex = (AbstractSpatialIndex<Key, ID, Content>) new Tetree<ID, Content>(idGenerator);
        }
        
        // Use simple naming - the hash-based ID will ensure uniqueness
        var mergedMetadata = TreeMetadata.builder()
            .name("MergedTree")
            .treeType(tree1.getSpatialIndex() instanceof Octree ? 
                     TreeMetadata.TreeType.OCTREE : TreeMetadata.TreeType.TETREE)
            .property("mergedFrom", Arrays.asList(tree1.getTreeId(), tree2.getTreeId()))
            .property("mergeTime", System.currentTimeMillis())
            .build();
        
        var mergedId = addTree(mergedIndex, mergedMetadata);
        var mergedTree = getTree(mergedId);
        mergedTree.expandGlobalBounds(mergedBounds);
        
        // Transfer entities from both trees
        transferEntities(tree1, mergedTree);
        transferEntities(tree2, mergedTree);
        
        // Remove original trees
        removeTree(tree1.getTreeId());
        removeTree(tree2.getTreeId());
        
        // Trigger ghost updates after forest repartitioning
        triggerGhostUpdatesAfterRepartitioning(mergedTree.getSpatialIndex());
    }
    
    /**
     * Transfer all entities from source to target tree
     */
    private void transferEntities(TreeNode<Key, ID, Content> source, TreeNode<Key, ID, Content> target) {
        var sourceRegion = densityRegions.get(source.getTreeId());
        if (sourceRegion == null) {
            return;
        }
        
        var sourceIndex = source.getSpatialIndex();
        var targetIndex = target.getSpatialIndex();
        
        for (var entry : sourceRegion.entityPositions.entrySet()) {
            var entityId = entry.getKey();
            var position = entry.getValue();
            var content = sourceIndex.getEntity(entityId);
            
            if (content != null) {
                sourceIndex.removeEntity(entityId);
                targetIndex.insert(entityId, position, (byte)0, content);
                trackEntityInsertion(target.getTreeId(), entityId, position);
            }
        }
    }
    
    /**
     * Get adaptation statistics
     */
    public Map<String, Object> getAdaptationStatistics() {
        var stats = new HashMap<String, Object>();
        stats.put("subdivisionCount", subdivisionCount.get());
        stats.put("mergeCount", mergeCount.get());
        stats.put("densityRegions", densityRegions.size());
        stats.put("adaptationEnabled", adaptationEnabled);
        
        // Calculate average density
        double totalDensity = 0;
        int regionCount = 0;
        for (var region : densityRegions.values()) {
            totalDensity += region.getDensity();
            regionCount++;
        }
        stats.put("averageDensity", regionCount > 0 ? totalDensity / regionCount : 0);
        
        return stats;
    }
    
    /**
     * Enable or disable automatic adaptation
     */
    public void setAdaptationEnabled(boolean enabled) {
        this.adaptationEnabled = enabled;
        log.info("Adaptation {} for forest", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Shutdown the adaptive forest
     */
    public void shutdown() {
        adaptationExecutor.shutdown();
        try {
            if (!adaptationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                adaptationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            adaptationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Force immediate adaptation check
     */
    public void checkAndAdapt() {
        // Force immediate adaptation check
        checkAdaptationTriggers();
    }
    
    /**
     * Check if bounds contains a point
     */
    private boolean containsPoint(EntityBounds bounds, Point3f point) {
        return point.x >= bounds.getMinX() && point.x <= bounds.getMaxX() &&
               point.y >= bounds.getMinY() && point.y <= bounds.getMaxY() &&
               point.z >= bounds.getMinZ() && point.z <= bounds.getMaxZ();
    }
    
    // Getters
    public AdaptationConfig getAdaptationConfig() {
        return adaptationConfig;
    }
    
    public int getSubdivisionCount() {
        return subdivisionCount.get();
    }
    
    public int getMergeCount() {
        return mergeCount.get();
    }
    
    // ========================================
    // Ghost Layer Integration
    // ========================================
    
    /**
     * Triggers ghost updates for all spatial indices after forest repartitioning.
     * Called after tree merging operations.
     * 
     * @param affectedIndex the spatial index that was affected by repartitioning
     */
    private void triggerGhostUpdatesAfterRepartitioning(AbstractSpatialIndex<Key, ID, Content> affectedIndex) {
        if (affectedIndex.getGhostType() != GhostType.NONE) {
            log.debug("Triggering ghost updates after forest repartitioning");
            try {
                affectedIndex.updateGhostLayer();
            } catch (Exception e) {
                log.warn("Failed to update ghost layer after repartitioning", e);
            }
        }
        
        // Also update ghosts for other trees that might be affected by the repartitioning
        triggerGhostUpdatesForAllTrees();
    }
    
    /**
     * Triggers ghost updates for affected trees after subdivision.
     * Called after tree subdivision operations.
     * 
     * @param originalTreeId the ID of the tree that was subdivided (may no longer exist)
     */
    private void triggerGhostUpdatesAfterSubdivision(String originalTreeId) {
        log.debug("Triggering ghost updates after tree subdivision for tree: {}", originalTreeId);
        
        // Update ghosts for all trees since subdivision may have created new boundary relationships
        triggerGhostUpdatesForAllTrees();
    }
    
    /**
     * Triggers ghost updates for all trees in the forest.
     * This is a comprehensive approach used when the forest structure changes significantly.
     */
    private void triggerGhostUpdatesForAllTrees() {
        for (var tree : getAllTrees()) {
            var spatialIndex = tree.getSpatialIndex();
            if (spatialIndex.getGhostType() != GhostType.NONE) {
                try {
                    spatialIndex.updateGhostLayer();
                } catch (Exception e) {
                    log.warn("Failed to update ghost layer for tree {}", tree.getTreeId(), e);
                }
            }
        }
    }
    
    /**
     * Sets the ghost type for all trees in the forest.
     * 
     * @param ghostType the ghost type to set for all spatial indices
     */
    public void setForestGhostType(GhostType ghostType) {
        log.info("Setting ghost type {} for all trees in forest", ghostType);
        for (var tree : getAllTrees()) {
            tree.getSpatialIndex().setGhostType(ghostType);
        }
    }
    
    /**
     * Creates ghost layers for all trees in the forest.
     */
    public void createForestGhostLayers() {
        log.info("Creating ghost layers for all trees in forest");
        for (var tree : getAllTrees()) {
            var spatialIndex = tree.getSpatialIndex();
            if (spatialIndex.getGhostType() != GhostType.NONE) {
                try {
                    spatialIndex.createGhostLayer();
                } catch (Exception e) {
                    log.warn("Failed to create ghost layer for tree {}", tree.getTreeId(), e);
                }
            }
        }
    }
    
}
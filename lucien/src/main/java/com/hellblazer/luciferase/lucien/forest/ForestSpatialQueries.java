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

import com.hellblazer.luciferase.lucien.*;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Provides spatial query operations across multiple trees in a forest.
 * This class implements efficient multi-tree spatial queries with parallel processing
 * and query routing based on spatial bounds.
 *
 * <p>Key features:
 * <ul>
 *   <li>K-NN search across entire forest</li>
 *   <li>Range queries with distance filtering</li>
 *   <li>Ray intersection across trees</li>
 *   <li>Frustum culling for visibility determination</li>
 *   <li>Parallel processing for multi-tree queries</li>
 *   <li>Query routing based on spatial bounds</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe and designed for concurrent queries.
 * The parallel processing uses a configurable thread pool for multi-tree operations.
 *
 * @param <Key>     The spatial key type (e.g., MortonKey, TetreeKey)
 * @param <ID>      The entity ID type
 * @param <Content> The content type stored with entities
 * @author hal.hildebrand
 */
public class ForestSpatialQueries<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    private static final Logger log = LoggerFactory.getLogger(ForestSpatialQueries.class);
    
    /** The forest to query */
    private final Forest<Key, ID, Content> forest;
    
    /** Thread pool for parallel query execution */
    private final ExecutorService queryExecutor;
    
    /** Configuration for parallel processing */
    private final QueryConfig config;
    
    /**
     * Create ForestSpatialQueries with default configuration.
     *
     * @param forest the forest to query
     */
    public ForestSpatialQueries(Forest<Key, ID, Content> forest) {
        this(forest, QueryConfig.defaultConfig());
    }
    
    /**
     * Create ForestSpatialQueries with custom configuration.
     *
     * @param forest the forest to query
     * @param config query configuration
     */
    public ForestSpatialQueries(Forest<Key, ID, Content> forest, QueryConfig config) {
        this.forest = Objects.requireNonNull(forest, "Forest cannot be null");
        this.config = Objects.requireNonNull(config, "Query config cannot be null");
        
        // Create thread pool based on configuration
        this.queryExecutor = new ForkJoinPool(
            config.getParallelism(),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            null,
            true
        );
        
        log.info("Created ForestSpatialQueries with parallelism: {}", config.getParallelism());
    }
    
    /**
     * Find the K nearest neighbors to a point across the entire forest.
     *
     * @param queryPoint the query point
     * @param k the number of neighbors to find
     * @param maxDistance maximum distance to search (use Float.MAX_VALUE for unlimited)
     * @return list of K nearest entity IDs sorted by distance
     */
    public List<ID> findKNearestNeighbors(Point3f queryPoint, int k, float maxDistance) {
        if (k <= 0) {
            return Collections.emptyList();
        }
        
        var trees = forest.getAllTrees();
        if (trees.isEmpty()) {
            return Collections.emptyList();
        }
        
        // For small forests or few neighbors, use sequential processing
        if (trees.size() <= config.getMinTreesForParallel() || k <= config.getMinKForParallel()) {
            return findKNearestNeighborsSequential(queryPoint, k, maxDistance);
        }
        
        // Use parallel processing for large queries
        return findKNearestNeighborsParallel(queryPoint, k, maxDistance);
    }
    
    /**
     * Find all entities within a specified distance from a point.
     *
     * @param center the center point
     * @param radius the search radius
     * @return list of entities within the radius
     */
    public List<ID> findEntitiesWithinDistance(Point3f center, float radius) {
        var bounds = new EntityBounds(
            new Point3f(center.x - radius, center.y - radius, center.z - radius),
            new Point3f(center.x + radius, center.y + radius, center.z + radius)
        );
        
        // Route query to relevant trees
        var relevantTrees = forest.routeQuery(bounds).collect(Collectors.toList());
        
        if (relevantTrees.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Use parallel processing if enough trees
        if (relevantTrees.size() >= config.getMinTreesForParallel()) {
            return findEntitiesWithinDistanceParallel(center, radius, relevantTrees);
        } else {
            return findEntitiesWithinDistanceSequential(center, radius, relevantTrees);
        }
    }
    
    /**
     * Find all entities intersected by a ray across the forest.
     *
     * @param ray the ray to test
     * @return list of ray intersections sorted by distance along the ray
     */
    public List<SpatialIndex.RayIntersection<ID, Content>> rayIntersectAll(Ray3D ray) {
        var trees = forest.getAllTrees();
        if (trees.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Use parallel processing for multiple trees
        if (trees.size() >= config.getMinTreesForParallel()) {
            return rayIntersectAllParallel(ray, trees);
        } else {
            return rayIntersectAllSequential(ray, trees);
        }
    }
    
    /**
     * Find the first entity intersected by a ray across the forest.
     *
     * @param ray the ray to test
     * @return the first intersection, or empty if no intersection
     */
    public Optional<SpatialIndex.RayIntersection<ID, Content>> rayIntersectFirst(Ray3D ray) {
        var trees = forest.getAllTrees();
        if (trees.isEmpty()) {
            return Optional.empty();
        }
        
        // For first intersection, we can use early termination
        SpatialIndex.RayIntersection<ID, Content> closest = null;
        var closestDistance = Float.MAX_VALUE;
        
        for (var tree : trees) {
            var intersection = tree.getSpatialIndex().rayIntersectFirst(ray);
            if (intersection.isPresent()) {
                var hit = intersection.get();
                if (hit.distance() < closestDistance) {
                    closest = hit;
                    closestDistance = hit.distance();
                }
            }
        }
        
        return Optional.ofNullable(closest);
    }
    
    /**
     * Find all entities visible within a view frustum across the forest.
     *
     * @param frustum the view frustum
     * @return list of entity IDs that are potentially visible
     */
    public List<ID> frustumCullVisible(Frustum3D frustum) {
        var trees = forest.getAllTrees();
        if (trees.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Use parallel processing for multiple trees
        if (trees.size() >= config.getMinTreesForParallel()) {
            return frustumCullVisibleParallel(frustum, trees);
        } else {
            return frustumCullVisibleSequential(frustum, trees);
        }
    }
    
    /**
     * Find all entities visible within a frustum, sorted by distance from camera.
     *
     * @param frustum the view frustum
     * @param cameraPosition the camera position for distance sorting
     * @return list of visible entities sorted by distance
     */
    public List<ID> frustumCullVisibleSorted(Frustum3D frustum, Point3f cameraPosition) {
        var trees = forest.getAllTrees();
        if (trees.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Collect all visible entities with distances
        var visibleWithDistance = new ArrayList<EntityDistance<ID>>();
        
        if (trees.size() >= config.getMinTreesForParallel()) {
            // Parallel collection
            var futures = new ArrayList<CompletableFuture<List<EntityDistance<ID>>>>();
            
            for (var tree : trees) {
                var future = CompletableFuture.<List<EntityDistance<ID>>>supplyAsync(() -> {
                    var localVisible = new ArrayList<EntityDistance<ID>>();
                    var spatialIndex = tree.getSpatialIndex();
                    var visible = spatialIndex.frustumCullVisible(frustum);
                    
                    for (var entityId : visible) {
                        var position = spatialIndex.getEntityPosition(entityId);
                        if (position != null) {
                            var distance = cameraPosition.distance(position);
                            localVisible.add(new EntityDistance<>(entityId, distance));
                        }
                    }
                    
                    return localVisible;
                }, queryExecutor);
                
                futures.add(future);
            }
            
            // Collect results
            for (var future : futures) {
                try {
                    visibleWithDistance.addAll(future.get());
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error in parallel frustum culling", e);
                }
            }
        } else {
            // Sequential collection
            for (var tree : trees) {
                var spatialIndex = tree.getSpatialIndex();
                var visible = spatialIndex.frustumCullVisible(frustum);
                
                for (var entityId : visible) {
                    var position = spatialIndex.getEntityPosition(entityId);
                    if (position != null) {
                        var distance = cameraPosition.distance(position);
                        visibleWithDistance.add(new EntityDistance<>(entityId, distance));
                    }
                }
            }
        }
        
        // Sort by distance and extract IDs
        return visibleWithDistance.stream()
            .sorted(Comparator.comparingDouble(EntityDistance<ID>::distance))
            .map(EntityDistance<ID>::entityId)
            .collect(Collectors.toList());
    }
    
    /**
     * Find all entities in a spatial region across the forest.
     *
     * @param region the spatial region
     * @return list of entities in the region
     */
    public List<ID> findEntitiesInRegion(Spatial region) {
        return forest.findEntitiesInRegion(region);
    }
    
    /**
     * Shutdown the query executor.
     * Call this when done with queries to release resources.
     */
    public void shutdown() {
        queryExecutor.shutdown();
        try {
            if (!queryExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                queryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            queryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("ForestSpatialQueries shutdown complete");
    }
    
    // Private helper methods
    
    private List<ID> findKNearestNeighborsSequential(Point3f queryPoint, int k, float maxDistance) {
        var candidates = new PriorityQueue<EntityDistance<ID>>(maxHeapComparator());
        
        for (var tree : forest.getAllTrees()) {
            var spatialIndex = tree.getSpatialIndex();
            var neighbors = spatialIndex.kNearestNeighbors(queryPoint, k, maxDistance);
            
            for (var entityId : neighbors) {
                var position = spatialIndex.getEntityPosition(entityId);
                if (position != null) {
                    var distance = queryPoint.distance(position);
                    if (distance <= maxDistance) {
                        candidates.offer(new EntityDistance<>(entityId, distance));
                        
                        // Keep only k best candidates
                        if (candidates.size() > k) {
                            candidates.poll();
                        }
                    }
                }
            }
        }
        
        // Convert to sorted list
        var result = new ArrayList<>(candidates);
        result.sort(Comparator.comparingDouble(EntityDistance<ID>::distance));
        return result.stream()
            .map(EntityDistance<ID>::entityId)
            .collect(Collectors.toList());
    }
    
    private List<ID> findKNearestNeighborsParallel(Point3f queryPoint, int k, float maxDistance) {
        var trees = forest.getAllTrees();
        var futures = new ArrayList<CompletableFuture<List<EntityDistance<ID>>>>();
        
        // Submit parallel tasks
        for (var tree : trees) {
            var future = CompletableFuture.<List<EntityDistance<ID>>>supplyAsync(() -> {
                var localCandidates = new ArrayList<EntityDistance<ID>>();
                var spatialIndex = tree.getSpatialIndex();
                var neighbors = spatialIndex.kNearestNeighbors(queryPoint, k, maxDistance);
                
                for (var entityId : neighbors) {
                    var position = spatialIndex.getEntityPosition(entityId);
                    if (position != null) {
                        var distance = queryPoint.distance(position);
                        if (distance <= maxDistance) {
                            localCandidates.add(new EntityDistance<>(entityId, distance));
                        }
                    }
                }
                
                return localCandidates;
            }, queryExecutor);
            
            futures.add(future);
        }
        
        // Collect all candidates
        var allCandidates = new PriorityQueue<EntityDistance<ID>>(maxHeapComparator());
        
        for (var future : futures) {
            try {
                var treeCandidates = future.get();
                for (var candidate : treeCandidates) {
                    allCandidates.offer(candidate);
                    if (allCandidates.size() > k) {
                        allCandidates.poll();
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                log.error("Error in parallel k-NN search", e);
            }
        }
        
        // Convert to sorted list
        var result = new ArrayList<>(allCandidates);
        result.sort(Comparator.comparingDouble(EntityDistance<ID>::distance));
        return result.stream()
            .map(EntityDistance<ID>::entityId)
            .collect(Collectors.toList());
    }
    
    private List<ID> findEntitiesWithinDistanceSequential(Point3f center, float radius, 
                                                          List<TreeNode<Key, ID, Content>> trees) {
        var results = new HashSet<ID>();
        
        for (var tree : trees) {
            var spatialIndex = tree.getSpatialIndex();
            var candidates = spatialIndex.kNearestNeighbors(center, Integer.MAX_VALUE, radius);
            
            for (var entityId : candidates) {
                var position = spatialIndex.getEntityPosition(entityId);
                if (position != null && center.distance(position) <= radius) {
                    results.add(entityId);
                }
            }
        }
        
        return new ArrayList<>(results);
    }
    
    private List<ID> findEntitiesWithinDistanceParallel(Point3f center, float radius,
                                                        List<TreeNode<Key, ID, Content>> trees) {
        var futures = new ArrayList<CompletableFuture<Set<ID>>>();
        
        for (var tree : trees) {
            var future = CompletableFuture.<Set<ID>>supplyAsync(() -> {
                var localResults = new HashSet<ID>();
                var spatialIndex = tree.getSpatialIndex();
                var candidates = spatialIndex.kNearestNeighbors(center, Integer.MAX_VALUE, radius);
                
                for (var entityId : candidates) {
                    var position = spatialIndex.getEntityPosition(entityId);
                    if (position != null && center.distance(position) <= radius) {
                        localResults.add(entityId);
                    }
                }
                
                return localResults;
            }, queryExecutor);
            
            futures.add(future);
        }
        
        // Collect results
        var results = new HashSet<ID>();
        for (var future : futures) {
            try {
                results.addAll(future.get());
            } catch (InterruptedException | ExecutionException e) {
                log.error("Error in parallel range query", e);
            }
        }
        
        return new ArrayList<>(results);
    }
    
    private List<SpatialIndex.RayIntersection<ID, Content>> rayIntersectAllSequential(
            Ray3D ray, List<TreeNode<Key, ID, Content>> trees) {
        var intersections = new ArrayList<SpatialIndex.RayIntersection<ID, Content>>();
        
        for (var tree : trees) {
            var treeIntersections = tree.getSpatialIndex().rayIntersectAll(ray);
            intersections.addAll(treeIntersections);
        }
        
        Collections.sort(intersections);
        return intersections;
    }
    
    private List<SpatialIndex.RayIntersection<ID, Content>> rayIntersectAllParallel(
            Ray3D ray, List<TreeNode<Key, ID, Content>> trees) {
        var futures = new ArrayList<CompletableFuture<List<SpatialIndex.RayIntersection<ID, Content>>>>();
        
        for (var tree : trees) {
            var future = CompletableFuture.supplyAsync(() -> 
                tree.getSpatialIndex().rayIntersectAll(ray), queryExecutor);
            futures.add(future);
        }
        
        // Collect all intersections
        var allIntersections = new ArrayList<SpatialIndex.RayIntersection<ID, Content>>();
        
        for (var future : futures) {
            try {
                allIntersections.addAll(future.get());
            } catch (InterruptedException | ExecutionException e) {
                log.error("Error in parallel ray intersection", e);
            }
        }
        
        Collections.sort(allIntersections);
        return allIntersections;
    }
    
    private List<ID> frustumCullVisibleSequential(Frustum3D frustum, List<TreeNode<Key, ID, Content>> trees) {
        var visibleEntities = new HashSet<ID>();
        
        for (var tree : trees) {
            var treeVisible = tree.getSpatialIndex().frustumCullVisible(frustum);
            visibleEntities.addAll(treeVisible);
        }
        
        return new ArrayList<>(visibleEntities);
    }
    
    private List<ID> frustumCullVisibleParallel(Frustum3D frustum, List<TreeNode<Key, ID, Content>> trees) {
        var futures = new ArrayList<CompletableFuture<List<ID>>>();
        
        for (var tree : trees) {
            var future = CompletableFuture.supplyAsync(() -> 
                tree.getSpatialIndex().frustumCullVisible(frustum), queryExecutor);
            futures.add(future);
        }
        
        // Collect results
        var visibleEntities = new HashSet<ID>();
        
        for (var future : futures) {
            try {
                visibleEntities.addAll(future.get());
            } catch (InterruptedException | ExecutionException e) {
                log.error("Error in parallel frustum culling", e);
            }
        }
        
        return new ArrayList<>(visibleEntities);
    }
    
    /**
     * Helper class for entity distance tracking.
     */
    private record EntityDistance<ID>(ID entityId, double distance) {
    }
    
    private static <ID> Comparator<EntityDistance<ID>> maxHeapComparator() {
        return (a, b) -> Double.compare(b.distance, a.distance);
    }
    
    /**
     * Configuration for forest spatial queries.
     */
    public static class QueryConfig {
        private int parallelism = Runtime.getRuntime().availableProcessors();
        private int minTreesForParallel = 2;
        private int minKForParallel = 10;
        
        public static QueryConfig defaultConfig() {
            return new QueryConfig();
        }
        
        public QueryConfig withParallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }
        
        public QueryConfig withMinTreesForParallel(int minTrees) {
            this.minTreesForParallel = minTrees;
            return this;
        }
        
        public QueryConfig withMinKForParallel(int minK) {
            this.minKForParallel = minK;
            return this;
        }
        
        public int getParallelism() {
            return parallelism;
        }
        
        public int getMinTreesForParallel() {
            return minTreesForParallel;
        }
        
        public int getMinKForParallel() {
            return minKForParallel;
        }
    }
}
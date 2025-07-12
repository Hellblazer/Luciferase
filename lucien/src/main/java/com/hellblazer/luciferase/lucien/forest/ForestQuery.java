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
import com.hellblazer.luciferase.lucien.SpatialIndex.CollisionPair;
import com.hellblazer.luciferase.lucien.SpatialIndex.RayIntersection;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Query operations interface for Forest spatial indexing structures.
 * Provides comprehensive query capabilities across multiple spatial index trees,
 * supporting both single-tree and multi-tree query operations with various
 * filtering and optimization strategies.
 *
 * <p>This interface defines the contract for querying entities stored across
 * multiple spatial trees in a forest. It supports:
 * <ul>
 *   <li>Single-tree queries with tree selection strategies</li>
 *   <li>Multi-tree queries with result aggregation</li>
 *   <li>Predicate-based filtering at both entity and tree levels</li>
 *   <li>Region-based spatial queries with intersection/containment tests</li>
 *   <li>K-nearest neighbor searches across trees</li>
 *   <li>Ray casting and collision detection</li>
 *   <li>Frustum culling for visibility determination</li>
 * </ul>
 *
 * <p>Thread Safety: Implementations should ensure thread-safe query operations,
 * allowing concurrent queries while maintaining consistency.
 *
 * @param <Key>     The spatial key type (e.g., MortonKey, TetreeKey)
 * @param <ID>      The entity ID type
 * @param <Content> The content type stored with entities
 * @author hal.hildebrand
 */
public interface ForestQuery<Key extends SpatialKey<Key>, ID extends EntityID, Content> {
    
    // ===== Single-Tree Query Methods =====
    
    /**
     * Query entities in a specific tree by its ID.
     *
     * @param treeId the ID of the tree to query
     * @param queryBounds the spatial bounds to query
     * @return list of entity IDs found in the specified tree, empty if tree not found
     */
    List<ID> queryTree(String treeId, EntityBounds queryBounds);
    
    /**
     * Query entities in a specific tree with a content predicate.
     *
     * @param treeId the ID of the tree to query
     * @param queryBounds the spatial bounds to query
     * @param contentPredicate predicate to filter entities by content
     * @return list of entity IDs matching the predicate in the specified tree
     */
    List<ID> queryTree(String treeId, EntityBounds queryBounds, Predicate<Content> contentPredicate);
    
    /**
     * Find k-nearest neighbors in a specific tree.
     *
     * @param treeId the ID of the tree to query
     * @param point the query point
     * @param k the number of neighbors to find
     * @return list of k nearest entity IDs in the specified tree
     */
    List<ID> kNearestInTree(String treeId, Point3f point, int k);
    
    /**
     * Find k-nearest neighbors in a specific tree with maximum distance constraint.
     *
     * @param treeId the ID of the tree to query
     * @param point the query point
     * @param k the number of neighbors to find
     * @param maxDistance maximum distance to search
     * @return list of k nearest entity IDs within maxDistance in the specified tree
     */
    List<ID> kNearestInTree(String treeId, Point3f point, int k, float maxDistance);
    
    // ===== Multi-Tree Query Methods =====
    
    /**
     * Query entities across all trees in the forest.
     *
     * @param queryBounds the spatial bounds to query
     * @return list of entity IDs found across all trees
     */
    List<ID> queryAllTrees(EntityBounds queryBounds);
    
    /**
     * Query entities across all trees with a content predicate.
     *
     * @param queryBounds the spatial bounds to query
     * @param contentPredicate predicate to filter entities by content
     * @return list of entity IDs matching the predicate across all trees
     */
    List<ID> queryAllTrees(EntityBounds queryBounds, Predicate<Content> contentPredicate);
    
    /**
     * Query entities across selected trees based on a tree filter.
     *
     * @param queryBounds the spatial bounds to query
     * @param treePredicate predicate to select which trees to query
     * @return list of entity IDs found in the selected trees
     */
    List<ID> querySelectedTrees(EntityBounds queryBounds, Predicate<TreeNode<Key, ID, Content>> treePredicate);
    
    /**
     * Query entities across selected trees with both tree and content filtering.
     *
     * @param queryBounds the spatial bounds to query
     * @param treePredicate predicate to select which trees to query
     * @param contentPredicate predicate to filter entities by content
     * @return list of entity IDs matching both predicates
     */
    List<ID> querySelectedTrees(EntityBounds queryBounds, 
                               Predicate<TreeNode<Key, ID, Content>> treePredicate,
                               Predicate<Content> contentPredicate);
    
    // ===== Predicate-Based Filtering =====
    
    /**
     * Find all entities matching a content predicate across all trees.
     *
     * @param contentPredicate predicate to match entities
     * @return stream of entity IDs matching the predicate
     */
    Stream<ID> findEntitiesMatching(Predicate<Content> contentPredicate);
    
    /**
     * Find entities matching a content predicate within a spatial region.
     *
     * @param region the spatial region to search
     * @param contentPredicate predicate to match entities
     * @return stream of entity IDs in the region matching the predicate
     */
    Stream<ID> findEntitiesInRegion(Spatial region, Predicate<Content> contentPredicate);
    
    /**
     * Find entities matching complex spatial and content criteria.
     *
     * @param spatialPredicate predicate for spatial filtering
     * @param contentPredicate predicate for content filtering
     * @return stream of entity IDs matching both predicates
     */
    Stream<ID> findEntities(Predicate<Point3f> spatialPredicate, Predicate<Content> contentPredicate);
    
    // ===== Region-Based Queries =====
    
    /**
     * Find all entities completely contained within a region across all trees.
     *
     * @param region the spatial region
     * @return list of entity IDs completely contained in the region
     */
    List<ID> entitiesContainedIn(Spatial region);
    
    /**
     * Find all entities that intersect with a region across all trees.
     *
     * @param region the spatial region
     * @return list of entity IDs that intersect the region
     */
    List<ID> entitiesIntersecting(Spatial region);
    
    /**
     * Find all entities within a spherical region across all trees.
     *
     * @param center the center point
     * @param radius the search radius
     * @return list of entity IDs within the spherical region
     */
    List<ID> entitiesWithinRadius(Point3f center, float radius);
    
    /**
     * Find all entities within a spherical region with content filtering.
     *
     * @param center the center point
     * @param radius the search radius
     * @param contentPredicate predicate to filter entities by content
     * @return list of entity IDs within the radius matching the predicate
     */
    List<ID> entitiesWithinRadius(Point3f center, float radius, Predicate<Content> contentPredicate);
    
    // ===== K-Nearest Neighbor Queries =====
    
    /**
     * Find k-nearest neighbors across all trees.
     *
     * @param point the query point
     * @param k the number of neighbors to find
     * @return list of k nearest entity IDs across all trees
     */
    List<ID> kNearestNeighbors(Point3f point, int k);
    
    /**
     * Find k-nearest neighbors with maximum distance constraint.
     *
     * @param point the query point
     * @param k the number of neighbors to find
     * @param maxDistance maximum distance to search
     * @return list of k nearest entity IDs within maxDistance
     */
    List<ID> kNearestNeighbors(Point3f point, int k, float maxDistance);
    
    /**
     * Find k-nearest neighbors matching a content predicate.
     *
     * @param point the query point
     * @param k the number of neighbors to find
     * @param contentPredicate predicate to filter entities by content
     * @return list of k nearest entity IDs matching the predicate
     */
    List<ID> kNearestNeighborsMatching(Point3f point, int k, Predicate<Content> contentPredicate);
    
    // ===== Ray Casting Queries =====
    
    /**
     * Find the first entity intersected by a ray across all trees.
     *
     * @param ray the query ray
     * @return the first intersection, or empty if no intersection
     */
    Optional<RayIntersection<ID, Content>> raycastFirst(Ray3D ray);
    
    /**
     * Find all entities intersected by a ray across all trees.
     *
     * @param ray the query ray
     * @return list of intersections sorted by distance along the ray
     */
    List<RayIntersection<ID, Content>> raycastAll(Ray3D ray);
    
    /**
     * Find entities intersected by a ray within a maximum distance.
     *
     * @param ray the query ray
     * @param maxDistance maximum distance along the ray
     * @return list of intersections within the distance, sorted by distance
     */
    List<RayIntersection<ID, Content>> raycastWithin(Ray3D ray, float maxDistance);
    
    /**
     * Find entities intersected by a ray matching a content predicate.
     *
     * @param ray the query ray
     * @param contentPredicate predicate to filter entities by content
     * @return list of intersections matching the predicate, sorted by distance
     */
    List<RayIntersection<ID, Content>> raycastMatching(Ray3D ray, Predicate<Content> contentPredicate);
    
    // ===== Collision Detection Queries =====
    
    /**
     * Find all collision pairs across all trees.
     *
     * @return list of collision pairs sorted by penetration depth
     */
    List<CollisionPair<ID, Content>> findAllCollisions();
    
    /**
     * Find collisions involving a specific entity across all trees.
     *
     * @param entityId the entity to check
     * @return list of collision pairs involving this entity
     */
    List<CollisionPair<ID, Content>> findCollisionsFor(ID entityId);
    
    /**
     * Find collisions within a spatial region across all trees.
     *
     * @param region the region to check
     * @return list of collision pairs within the region
     */
    List<CollisionPair<ID, Content>> findCollisionsInRegion(Spatial region);
    
    /**
     * Check if two specific entities are colliding.
     *
     * @param entityId1 first entity
     * @param entityId2 second entity
     * @return collision pair if colliding, empty otherwise
     */
    Optional<CollisionPair<ID, Content>> checkCollision(ID entityId1, ID entityId2);
    
    // ===== Visibility Queries =====
    
    /**
     * Find all entities visible within a view frustum across all trees.
     *
     * @param frustum the view frustum
     * @return list of potentially visible entity IDs
     */
    List<ID> frustumCullVisible(Frustum3D frustum);
    
    /**
     * Find visible entities within a frustum matching a content predicate.
     *
     * @param frustum the view frustum
     * @param contentPredicate predicate to filter entities by content
     * @return list of visible entity IDs matching the predicate
     */
    List<ID> frustumCullVisibleMatching(Frustum3D frustum, Predicate<Content> contentPredicate);
    
    // ===== Aggregate Queries =====
    
    /**
     * Get entity distribution statistics across trees.
     *
     * @return map of tree IDs to entity counts
     */
    Map<String, Integer> getEntityDistribution();
    
    /**
     * Get spatial coverage information for each tree.
     *
     * @return map of tree IDs to their spatial bounds
     */
    Map<String, EntityBounds> getTreeCoverage();
    
    /**
     * Find trees that contain a specific point.
     *
     * @param point the query point
     * @return set of tree IDs containing the point
     */
    Set<String> findTreesContainingPoint(Point3f point);
    
    /**
     * Find trees that intersect with a spatial region.
     *
     * @param region the query region
     * @return set of tree IDs intersecting the region
     */
    Set<String> findTreesIntersectingRegion(Spatial region);
    
    // ===== Query Optimization =====
    
    /**
     * Create an optimized query plan for a spatial region.
     * Returns a query plan that identifies which trees to query
     * and in what order for optimal performance.
     *
     * @param queryBounds the spatial bounds to query
     * @return optimized query plan
     */
    QueryPlan createQueryPlan(EntityBounds queryBounds);
    
    /**
     * Execute a pre-computed query plan.
     *
     * @param plan the query plan to execute
     * @return list of entity IDs found
     */
    List<ID> executeQueryPlan(QueryPlan plan);
    
    /**
     * Execute a query plan with content filtering.
     *
     * @param plan the query plan to execute
     * @param contentPredicate predicate to filter entities by content
     * @return list of entity IDs matching the predicate
     */
    List<ID> executeQueryPlan(QueryPlan plan, Predicate<Content> contentPredicate);
    
    /**
     * Query plan for optimized multi-tree queries.
     */
    interface QueryPlan {
        /**
         * Get the ordered list of tree IDs to query.
         *
         * @return list of tree IDs in optimal query order
         */
        List<String> getTreeOrder();
        
        /**
         * Get the original query bounds.
         *
         * @return the query bounds
         */
        EntityBounds getQueryBounds();
        
        /**
         * Check if early termination is possible.
         *
         * @return true if query can terminate early when sufficient results are found
         */
        boolean supportsEarlyTermination();
        
        /**
         * Get estimated result count.
         *
         * @return estimated number of results, or -1 if unknown
         */
        int getEstimatedResultCount();
        
        /**
         * Get query optimization hints.
         *
         * @return map of optimization hints
         */
        Map<String, Object> getOptimizationHints();
    }
}
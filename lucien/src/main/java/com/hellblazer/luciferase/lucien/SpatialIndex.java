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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.collision.CollisionShape;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3i;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.stream.Stream;

/**
 * Multi-entity spatial indexing interface. This interface supports multiple entities per spatial node and entities
 * spanning multiple nodes.
 *
 * @param <ID>      The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * @author hal.hildebrand
 */
public interface SpatialIndex<ID extends EntityID, Content> {

    /**
     * Get all nodes completely contained within a bounding volume
     *
     * @param volume the bounding volume
     * @return stream of nodes contained within the volume
     */
    Stream<SpatialNode<ID>> boundedBy(Spatial volume);

    // ===== Insert Operations =====

    /**
     * Get all nodes that intersect with a bounding volume
     *
     * @param volume the bounding volume
     * @return stream of nodes that intersect the volume
     */
    Stream<SpatialNode<ID>> bounding(Spatial volume);

    /**
     * Check if an entity exists
     *
     * @param entityId the entity ID to check
     * @return true if the entity exists
     */
    boolean containsEntity(ID entityId);

    /**
     * Find the minimum enclosing node for a volume
     *
     * @param volume the volume to enclose
     * @return the minimum enclosing node, or null if not found
     */
    SpatialNode<ID> enclosing(Spatial volume);

    // ===== Lookup Operations =====

    /**
     * Find the enclosing node at a specific level
     *
     * @param point the point to enclose
     * @param level the refinement level
     * @return the enclosing node at that level
     */
    SpatialNode<ID> enclosing(Tuple3i point, byte level);

    /**
     * Find all entities within a bounding region
     *
     * @param region the bounding region
     * @return list of entity IDs in the region
     */
    List<ID> entitiesInRegion(Spatial.Cube region);

    // ===== Entity Management =====

    /**
     * Get the total number of entities stored
     *
     * @return the number of unique entities
     */
    int entityCount();

    /**
     * Get content for multiple entity IDs
     *
     * @param entityIds list of entity IDs
     * @return list of content in same order (null entries for not found)
     */
    List<Content> getEntities(List<ID> entityIds);

    /**
     * Get all entities with their positions
     *
     * @return unmodifiable map of entity IDs to positions
     */
    Map<ID, Point3f> getEntitiesWithPositions();

    /**
     * Get content for a specific entity ID
     *
     * @param entityId the entity ID
     * @return the content, or null if not found
     */
    Content getEntity(ID entityId);

    /**
     * Get the bounds of a specific entity
     *
     * @param entityId the entity ID
     * @return the entity's bounds, or null if not found or not set
     */
    EntityBounds getEntityBounds(ID entityId);

    // ===== Entity Position/Bounds Queries =====

    /**
     * Get the position of a specific entity
     *
     * @param entityId the entity ID
     * @return the entity's position, or null if not found
     */
    Point3f getEntityPosition(ID entityId);

    /**
     * Get the number of nodes an entity spans
     *
     * @param entityId the entity ID
     * @return the span count, or 0 if not found
     */
    int getEntitySpanCount(ID entityId);

    /**
     * Get a navigable map view of Morton indices to nodes This allows for range queries and ordered traversal
     *
     * @return navigable map with Morton indices as keys and entity ID sets as values
     */
    NavigableMap<Long, Set<ID>> getSpatialMap();

    /**
     * Get comprehensive statistics about the spatial index
     *
     * @return statistics object
     */
    EntityStats getStats();

    // ===== Spatial Queries =====

    /**
     * Check if a node exists at the given Morton index
     *
     * @param mortonIndex the Morton index to check
     * @return true if a node exists at that index
     */
    boolean hasNode(long mortonIndex);

    /**
     * Insert content with auto-generated entity ID
     *
     * @param position the 3D position
     * @param level    the refinement level
     * @param content  the content to store
     * @return the generated entity ID
     */
    ID insert(Point3f position, byte level, Content content);

    /**
     * Insert content with explicit entity ID
     *
     * @param entityId the entity ID to use
     * @param position the 3D position
     * @param level    the refinement level
     * @param content  the content to store
     */
    void insert(ID entityId, Point3f position, byte level, Content content);

    /**
     * Insert content with explicit entity ID and bounds (for spanning)
     *
     * @param entityId the entity ID to use
     * @param position the 3D position
     * @param level    the refinement level
     * @param content  the content to store
     * @param bounds   the entity bounds for spanning calculations
     */
    void insert(ID entityId, Point3f position, byte level, Content content, EntityBounds bounds);

    /**
     * Find the k nearest neighbors to a query point
     *
     * @param queryPoint  the query point
     * @param k           the number of neighbors to find
     * @param maxDistance maximum distance to search
     * @return list of entity IDs sorted by distance
     */
    List<ID> kNearestNeighbors(Point3f queryPoint, int k, float maxDistance);

    // ===== Map Operations =====

    /**
     * Look up all entities at a specific position
     *
     * @param position the 3D position
     * @param level    the refinement level
     * @return list of entity IDs at that position
     */
    List<ID> lookup(Point3f position, byte level);

    /**
     * Get the total number of nodes in the spatial index
     *
     * @return the number of nodes
     */
    int nodeCount();

    /**
     * Stream all nodes in the spatial index
     *
     * @return stream of spatial nodes with their entity IDs
     */
    Stream<SpatialNode<ID>> nodes();

    /**
     * Find all entities intersected by a ray, sorted by distance
     *
     * @param ray the ray to test
     * @return list of ray intersections sorted by distance along the ray
     */
    List<RayIntersection<ID, Content>> rayIntersectAll(Ray3D ray);

    // ===== k-Nearest Neighbor Operations =====

    /**
     * Find the first entity intersected by a ray
     *
     * @param ray the ray to test
     * @return the first intersection, or empty if no intersection
     */
    Optional<RayIntersection<ID, Content>> rayIntersectFirst(Ray3D ray);

    // ===== Ray Intersection Operations =====

    /**
     * Find all entities intersected by a ray within a maximum distance
     *
     * @param ray         the ray to test
     * @param maxDistance maximum distance along the ray
     * @return list of ray intersections within the distance, sorted by distance
     */
    List<RayIntersection<ID, Content>> rayIntersectWithin(Ray3D ray, float maxDistance);

    /**
     * Remove an entity from all nodes
     *
     * @param entityId the entity ID to remove
     * @return true if the entity was removed
     */
    boolean removeEntity(ID entityId);

    /**
     * Update an entity's position
     *
     * @param entityId    the entity ID
     * @param newPosition the new position
     * @param level       the refinement level
     */
    void updateEntity(ID entityId, Point3f newPosition, byte level);

    // ===== Collision Detection Operations =====

    /**
     * Find all collision pairs within the spatial index
     *
     * @return list of collision pairs
     */
    List<CollisionPair<ID, Content>> findAllCollisions();

    /**
     * Find collisions involving a specific entity
     *
     * @param entityId the entity to check
     * @return list of collision pairs involving this entity
     */
    List<CollisionPair<ID, Content>> findCollisions(ID entityId);

    /**
     * Find collisions within a spatial region
     *
     * @param region the region to check
     * @return list of collision pairs within the region
     */
    List<CollisionPair<ID, Content>> findCollisionsInRegion(Spatial region);

    /**
     * Check if two specific entities are colliding
     *
     * @param entityId1 first entity
     * @param entityId2 second entity
     * @return collision pair if colliding, empty otherwise
     */
    Optional<CollisionPair<ID, Content>> checkCollision(ID entityId1, ID entityId2);
    
    /**
     * Set collision shape for an entity. If not set, AABB will be used for collision detection.
     *
     * @param entityId the entity ID
     * @param shape    the collision shape (null to use AABB)
     */
    void setCollisionShape(ID entityId, CollisionShape shape);
    
    /**
     * Get collision shape for an entity
     *
     * @param entityId the entity ID
     * @return the collision shape, or null if using default AABB
     */
    CollisionShape getCollisionShape(ID entityId);

    // ===== Statistics =====

    /**
     * Node wrapper that provides uniform access to spatial data with multiple entities
     */
    record SpatialNode<ID extends EntityID>(long mortonIndex, Set<ID> entityIds) {
        /**
         * Convert the node's Morton index to a spatial cube
         */
        public Spatial.Cube toCube() {
            return new Spatial.Cube(mortonIndex);
        }
    }

    /**
     * Statistics about the spatial index with entity information
     */
    record EntityStats(int nodeCount, int entityCount, int totalEntityReferences, int maxDepth) {
        /**
         * Calculate the average entities per node
         */
        public double averageEntitiesPerNode() {
            return nodeCount > 0 ? (double) totalEntityReferences / nodeCount : 0.0;
        }

        /**
         * Calculate the entity spanning factor (how many nodes per entity on average)
         */
        public double entitySpanningFactor() {
            return entityCount > 0 ? (double) totalEntityReferences / entityCount : 0.0;
        }
    }

    /**
     * Result of a ray-entity intersection test
     *
     * @param <ID>      The type of EntityID used for entity identification
     * @param <Content> The type of content stored with each entity
     */
    record RayIntersection<ID extends EntityID, Content>(ID entityId, Content content, float distance,
                                                         Point3f intersectionPoint, Vector3f normal,
                                                         EntityBounds bounds)
    implements Comparable<RayIntersection<ID, Content>> {

        /**
         * Compare intersections by distance for sorting
         */
        @Override
        public int compareTo(RayIntersection<ID, Content> other) {
            return Float.compare(this.distance, other.distance);
        }

        /**
         * Get the entity position (may differ from intersection point for bounded entities)
         */
        public Point3f getEntityPosition() {
            if (bounds != null) {
                // Return center of bounds
                return new Point3f((bounds.getMinX() + bounds.getMaxX()) / 2, (bounds.getMinY() + bounds.getMaxY()) / 2,
                                   (bounds.getMinZ() + bounds.getMaxZ()) / 2);
            }
            return intersectionPoint;
        }

        /**
         * Check if this intersection has bounds information
         */
        public boolean hasBounds() {
            return bounds != null;
        }
    }

    /**
     * Result of a collision detection between two entities
     *
     * @param <ID>      The type of EntityID used for entity identification
     * @param <Content> The type of content stored with each entity
     */
    record CollisionPair<ID extends EntityID, Content>(ID entityId1, Content content1, EntityBounds bounds1,
                                                       ID entityId2, Content content2, EntityBounds bounds2,
                                                       Point3f contactPoint, Vector3f contactNormal,
                                                       float penetrationDepth)
    implements Comparable<CollisionPair<ID, Content>> {

        /**
         * Create a collision pair ensuring consistent ordering (smaller ID first)
         */
        public static <ID extends EntityID, Content> CollisionPair<ID, Content> create(ID id1, Content content1,
                                                                                      EntityBounds bounds1, ID id2,
                                                                                      Content content2,
                                                                                      EntityBounds bounds2,
                                                                                      Point3f contactPoint,
                                                                                      Vector3f contactNormal,
                                                                                      float penetrationDepth) {
            // Ensure consistent ordering for deduplication
            if (id1.compareTo(id2) > 0) {
                // Swap entities and invert normal
                Vector3f invertedNormal = new Vector3f(contactNormal);
                invertedNormal.negate();
                return new CollisionPair<>(id2, content2, bounds2, id1, content1, bounds1, contactPoint, invertedNormal,
                                         penetrationDepth);
            }
            return new CollisionPair<>(id1, content1, bounds1, id2, content2, bounds2, contactPoint, contactNormal,
                                     penetrationDepth);
        }

        /**
         * Compare collision pairs by penetration depth (deeper collisions first)
         */
        @Override
        public int compareTo(CollisionPair<ID, Content> other) {
            return Float.compare(other.penetrationDepth, this.penetrationDepth);
        }

        /**
         * Check if this collision involves a specific entity
         */
        public boolean involves(ID entityId) {
            return entityId1.equals(entityId) || entityId2.equals(entityId);
        }

        /**
         * Get the other entity in this collision
         */
        public ID getOtherEntity(ID entityId) {
            if (entityId1.equals(entityId)) {
                return entityId2;
            } else if (entityId2.equals(entityId)) {
                return entityId1;
            }
            throw new IllegalArgumentException("Entity not involved in this collision: " + entityId);
        }

        /**
         * Check if both entities have bounds (not point entities)
         */
        public boolean hasBounds() {
            return bounds1 != null && bounds2 != null;
        }
    }
}

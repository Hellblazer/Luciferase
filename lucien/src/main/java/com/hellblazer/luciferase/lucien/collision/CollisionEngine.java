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
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex.CollisionPair;
import com.hellblazer.luciferase.lucien.SpatialIndexCore;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.SpatialNodeImpl;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.internal.ObjectPools;
import com.hellblazer.luciferase.lucien.internal.UnorderedPair;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The collision-detection feature object for a spatial index (RDR-008 P5).
 *
 * <p>Owns the collision cluster — seven public entries
 * ({@link #checkCollision}, {@link #findAllCollisions}, {@link #findCollisions}, {@link #findCollisionsFineGrained},
 * {@link #findCollisionsInRegion}, {@link #getCollisionShape}, {@link #setCollisionShape}) and the cluster's ~22
 * private helpers (AABB/point/mixed/shape collision checks, contact normal/point/penetration calculation, the
 * four-phase {@code findAllCollisions} sweep, the bounded/point entity collision finders, region-membership and
 * point-in-volume helpers). Verbatim preservation of the pre-extraction behavior.
 *
 * <p>The two subclass-overridden hooks ({@link CollisionGeometry#findNodesIntersectingBounds} and
 * {@link CollisionGeometry#addNeighboringNodes}), the façade-resident range-query
 * ({@link CollisionGeometry#spatialRangeQuery}), and the two cached entity accessors
 * ({@link CollisionGeometry#getCachedEntityPosition}, {@link CollisionGeometry#getCachedEntityBounds}) arrive
 * through the callback; the sorted node map, the read-write lock, the entity manager, and the
 * fine-grained locking strategy all arrive through {@link SpatialIndexCore}. RDR-008 P6 relocated the strategy
 * field into the core (discharging the P3 substantive-critic Significant#1), so the prior
 * {@code Supplier<FineGrainedLockingStrategy>} constructor argument is gone; {@code findCollisionsFineGrained}
 * reads the volatile reference at each call via {@link SpatialIndexCore#lockingStrategy()}.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public final class CollisionEngine<Key extends SpatialKey<Key>, ID extends EntityID, Content> {

    private final SpatialIndexCore<Key, ID, Content>  core;
    private final CollisionGeometry<Key, ID, Content> callback;

    // Narrow-phase invocation counter for the last findAllCollisions() pass (Luciferase-ig4yi). AtomicInteger
    // because findAllCollisions runs under a (shared) read lock, so concurrent passes would otherwise race on it.
    // Lets tests assert the sweep-and-prune broad-phase keeps distant bounded pairs out of the narrow phase.
    private final java.util.concurrent.atomic.AtomicInteger lastNarrowPhaseChecks =
        new java.util.concurrent.atomic.AtomicInteger();

    public CollisionEngine(SpatialIndexCore<Key, ID, Content> core, CollisionGeometry<Key, ID, Content> callback) {
        this.core = core;
        this.callback = callback;
    }

    // ===== Public API =====

    /** Check whether two entities collide (verbatim preservation of the pre-extraction behavior). */
    public Optional<CollisionPair<ID, Content>> checkCollision(ID entityId1, ID entityId2) {
        if (entityId1.equals(entityId2)) {
            return Optional.empty();
        }

        core.lock().readLock().lock();
        try {
            var bounds1 = callback.getCachedEntityBounds(entityId1);
            var bounds2 = callback.getCachedEntityBounds(entityId2);

            // Quick early rejection check
            if (bounds1 != null && bounds2 != null) {
                // Both have bounds - quick AABB check
                if (!boundsIntersect(bounds1, bounds2)) {
                    return Optional.empty();
                }
            } else if (bounds1 == null && bounds2 == null) {
                // Both are points - check distance
                var pos1 = callback.getCachedEntityPosition(entityId1);
                var pos2 = callback.getCachedEntityPosition(entityId2);

                if (pos1 == null || pos2 == null) {
                    return Optional.empty();
                }

                var threshold = 0.1f; // Small threshold for point entities
                if (pos1.distance(pos2) > threshold) {
                    return Optional.empty();
                }
            }
            // For mixed types (one bounded, one point), skip early rejection and go to detailed check

            // Perform detailed collision check
            return performDetailedCollisionCheck(entityId1, entityId2);
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Find all collisions in the spatial index (four-phase sweep). */
    public List<CollisionPair<ID, Content>> findAllCollisions() {
        core.lock().readLock().lock();
        try {
            var collisions = ObjectPools.<CollisionPair<ID, Content>>borrowArrayList();
            var checkedPairs = ObjectPools.<UnorderedPair<ID>>borrowHashSet();
            lastNarrowPhaseChecks.set(0);
            try {
                // Perform four phases of collision detection
                findIntraNodeCollisions(collisions, checkedPairs);
                findBoundedEntityCollisions(collisions, checkedPairs);
                findAdjacentNodeCollisions(collisions, checkedPairs);
                findPointBoundedCollisions(collisions, checkedPairs);

                // Sort by penetration depth (deepest first)
                Collections.sort(collisions);

                return new ArrayList<>(collisions);
            } finally {
                ObjectPools.returnArrayList(collisions);
                ObjectPools.returnHashSet(checkedPairs);
            }
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Find collisions involving a specific entity. */
    public List<CollisionPair<ID, Content>> findCollisions(ID entityId) {
        // Luciferase-fwfpm: read getEntityLocations INSIDE the read lock (mirroring findCollisionsFineGrained).
        // updateEntity clears then re-inserts the location set under the write lock; reading it before locking
        // could observe the transiently-empty set mid-move and short-circuit to emptyList() — a false-negative
        // collision result for an entity that is in fact colliding.
        core.lock().readLock().lock();
        try {
            var locations = core.entityManager().getEntityLocations(entityId);
            if (locations.isEmpty()) {
                return Collections.emptyList();
            }

            var collisions = ObjectPools.<CollisionPair<ID, Content>>borrowArrayList();
            var checkedEntities = ObjectPools.<ID>borrowHashSet();
            try {
                checkedEntities.add(entityId);

                var entityBounds = callback.getCachedEntityBounds(entityId);
                if (entityBounds != null) {
                    findBoundedEntityCollisions(entityId, entityBounds, checkedEntities, collisions);
                } else {
                    findPointEntityCollisions(entityId, locations, checkedEntities, collisions);
                }

                Collections.sort(collisions);
                return new ArrayList<>(collisions);
            } finally {
                ObjectPools.returnArrayList(collisions);
                ObjectPools.returnHashSet(checkedEntities);
            }
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /**
     * Find collisions for a single entity using per-node fine-grained locking for the node traversal.
     * <p>Since Luciferase-kvdto this method takes the global read lock as an outer guard (like every other read
     * path), so it is serialized against writes exactly as {@link #findCollisions} is — it does NOT offer extra
     * concurrency with respect to global writes. The fine-grained per-node strategy only reduces contention among
     * the node accesses within the read-locked traversal; choose this over {@link #findCollisions} only for that
     * intra-traversal property, not for global write concurrency.
     */
    public List<CollisionPair<ID, Content>> findCollisionsFineGrained(ID entityId) {
        // Luciferase-kvdto: take the global read lock as an outer guard, like every other read path
        // (findAllCollisions, findCollisions, findCollisionsInRegion, kNN). The per-node fine-grained strategy is
        // independent of core.lock(); all write paths take core.lock().writeLock(), so without this guard a
        // fine-grained collision query could traverse mid-write and observe torn results. The lock is reentrant,
        // so the inner per-node reads are fine.
        core.lock().readLock().lock();
        try {
            var locations = core.entityManager().getEntityLocations(entityId);
            if (locations.isEmpty()) {
                return Collections.emptyList();
            }

            // Use fine-grained locking for each node access — re-read the volatile strategy reference at each call
            // so a concurrent configureFineGrainedLocking is observed (mirrors KnnSearcher's pattern).
            return core.lockingStrategy().executeRead(0L, () -> {
                var collisions = ObjectPools.<CollisionPair<ID, Content>>borrowArrayList();
                var checkedEntities = ObjectPools.<ID>borrowHashSet();
                try {
                    checkedEntities.add(entityId);

                    var entityBounds = callback.getCachedEntityBounds(entityId);
                    if (entityBounds != null) {
                        findBoundedEntityCollisions(entityId, entityBounds, checkedEntities, collisions);
                    } else {
                        findPointEntityCollisions(entityId, locations, checkedEntities, collisions);
                    }

                    Collections.sort(collisions);
                    return new ArrayList<>(collisions);
                } finally {
                    ObjectPools.returnArrayList(collisions);
                    ObjectPools.returnHashSet(checkedEntities);
                }
            });
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Find all collisions in the given region. */
    public List<CollisionPair<ID, Content>> findCollisionsInRegion(Spatial region) {
        core.lock().readLock().lock();
        try {
            var collisions = new ArrayList<CollisionPair<ID, Content>>();
            var checkedPairs = new HashSet<UnorderedPair<ID>>();

            // Find all nodes intersecting the region. Uses the static VolumeBounds.from rather than the
            // façade's protected getVolumeBounds wrapper; the wrapper today is a pure pass-through to the
            // same static, but a future subclass that overrides getVolumeBounds to normalize/clamp the
            // bounds would diverge here. Audited as low-risk for P5 (no subclass overrides exist); revisit
            // if such an override is introduced. P5 code-review-expert Important#3.
            var bounds = VolumeBounds.from(region);
            if (bounds == null) {
                return collisions;
            }

            var nodesInRegion = callback.spatialRangeQuery(bounds, true);
            var nodeList = nodesInRegion.collect(Collectors.toList());

            // Check entities within and between nodes
            for (int i = 0; i < nodeList.size(); i++) {
                List<ID> nodeEntities = new ArrayList<>(nodeList.get(i).getValue().getEntityIds());

                // Check within node
                for (int j = 0; j < nodeEntities.size(); j++) {
                    for (int k = j + 1; k < nodeEntities.size(); k++) {
                        var id1 = nodeEntities.get(j);
                        var id2 = nodeEntities.get(k);

                        // Check if both entities are actually in the region
                        if (isEntityInRegion(id1, region) && isEntityInRegion(id2, region)) {
                            var pair = new UnorderedPair<>(id1, id2);
                            if (checkedPairs.add(pair)) {
                                checkAndAddCollision(id1, id2, collisions);
                            }
                        }
                    }
                }

                // Check between nodes
                for (int j = i + 1; j < nodeList.size(); j++) {
                    for (ID id1 : nodeEntities) {
                        if (!isEntityInRegion(id1, region)) {
                            continue;
                        }

                        for (ID id2 : nodeList.get(j).getValue().getEntityIds()) {
                            if (!isEntityInRegion(id2, region)) {
                                continue;
                            }

                            var pair = new UnorderedPair<>(id1, id2);
                            if (checkedPairs.add(pair)) {
                                checkAndAddCollision(id1, id2, collisions);
                            }
                        }
                    }
                }
            }

            Collections.sort(collisions);
            return collisions;
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Get the entity's collision shape, or {@code null} if unset. */
    public CollisionShape getCollisionShape(ID entityId) {
        core.lock().readLock().lock();
        try {
            return core.entityManager().getEntityCollisionShape(entityId);
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Set the entity's collision shape, invalidating the cached entity bounds. */
    public void setCollisionShape(ID entityId, CollisionShape shape) {
        core.lock().writeLock().lock();
        try {
            core.entityManager().setEntityCollisionShape(entityId, shape);
            // Invalidate cache as bounds may have changed (verbatim preservation of the pre-extraction
            // façade behavior)
            core.entityCache().remove(entityId);
        } finally {
            core.lock().writeLock().unlock();
        }
    }

    // ===== Collision check helpers (verbatim preservation) =====

    private boolean boundsIntersect(EntityBounds b1, EntityBounds b2) {
        return b1.getMaxX() >= b2.getMinX() && b1.getMinX() <= b2.getMaxX() && b1.getMaxY() >= b2.getMinY()
        && b1.getMinY() <= b2.getMaxY() && b1.getMaxZ() >= b2.getMinZ() && b1.getMinZ() <= b2.getMaxZ();
    }

    private boolean boundsIntersectVolume(EntityBounds bounds, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube ->
            bounds.getMaxX() >= cube.originX() && bounds.getMinX() <= cube.originX() + cube.extent()
            && bounds.getMaxY() >= cube.originY() && bounds.getMinY() <= cube.originY() + cube.extent()
            && bounds.getMaxZ() >= cube.originZ() && bounds.getMinZ() <= cube.originZ() + cube.extent();

            case Spatial.Sphere sphere -> {
                // Find closest point on bounds to sphere center
                var closestX = Math.max(bounds.getMinX(), Math.min(sphere.centerX(), bounds.getMaxX()));
                var closestY = Math.max(bounds.getMinY(), Math.min(sphere.centerY(), bounds.getMaxY()));
                var closestZ = Math.max(bounds.getMinZ(), Math.min(sphere.centerZ(), bounds.getMaxZ()));

                var dx = closestX - sphere.centerX();
                var dy = closestY - sphere.centerY();
                var dz = closestZ - sphere.centerZ();
                yield (dx * dx + dy * dy + dz * dz) <= (sphere.radius() * sphere.radius());
            }

            default -> true;
        };
    }

    private Vector3f calculateContactNormal(EntityBounds b1, EntityBounds b2) {
        // Find the axis with minimum penetration
        var penetrations = new float[6];
        penetrations[0] = b1.getMaxX() - b2.getMinX(); // +X
        penetrations[1] = b2.getMaxX() - b1.getMinX(); // -X
        penetrations[2] = b1.getMaxY() - b2.getMinY(); // +Y
        penetrations[3] = b2.getMaxY() - b1.getMinY(); // -Y
        penetrations[4] = b1.getMaxZ() - b2.getMinZ(); // +Z
        penetrations[5] = b2.getMaxZ() - b1.getMinZ(); // -Z

        var minAxis = 0;
        var minPenetration = penetrations[0];
        for (int i = 1; i < 6; i++) {
            if (penetrations[i] < minPenetration) {
                minPenetration = penetrations[i];
                minAxis = i;
            }
        }

        // Return normal based on minimum penetration axis
        return switch (minAxis) {
            case 0 -> new Vector3f(1, 0, 0);
            case 1 -> new Vector3f(-1, 0, 0);
            case 2 -> new Vector3f(0, 1, 0);
            case 3 -> new Vector3f(0, -1, 0);
            case 4 -> new Vector3f(0, 0, 1);
            case 5 -> new Vector3f(0, 0, -1);
            default -> new Vector3f(1, 0, 0);
        };
    }

    private Point3f calculateContactPoint(EntityBounds b1, EntityBounds b2) {
        // Use the center of the intersection volume
        var minX = Math.max(b1.getMinX(), b2.getMinX());
        var minY = Math.max(b1.getMinY(), b2.getMinY());
        var minZ = Math.max(b1.getMinZ(), b2.getMinZ());
        var maxX = Math.min(b1.getMaxX(), b2.getMaxX());
        var maxY = Math.min(b1.getMaxY(), b2.getMaxY());
        var maxZ = Math.min(b1.getMaxZ(), b2.getMaxZ());

        return new Point3f((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }

    private float calculatePenetrationDepth(EntityBounds b1, EntityBounds b2) {
        var xPenetration = Math.min(b1.getMaxX() - b2.getMinX(), b2.getMaxX() - b1.getMinX());
        var yPenetration = Math.min(b1.getMaxY() - b2.getMinY(), b2.getMaxY() - b1.getMinY());
        var zPenetration = Math.min(b1.getMaxZ() - b2.getMinZ(), b2.getMaxZ() - b1.getMinZ());

        return Math.min(Math.min(xPenetration, yPenetration), zPenetration);
    }

    private Vector3f calculatePointBoundsNormal(Point3f point, EntityBounds bounds) {
        var boundsCenter = new Point3f((bounds.getMinX() + bounds.getMaxX()) / 2,
                                       (bounds.getMinY() + bounds.getMaxY()) / 2,
                                       (bounds.getMinZ() + bounds.getMaxZ()) / 2);

        Vector3f contactNormal = new Vector3f();
        contactNormal.sub(point, boundsCenter);
        if (contactNormal.length() > 0) {
            contactNormal.normalize();
        } else {
            contactNormal.set(1, 0, 0); // Default normal
        }
        return contactNormal;
    }

    private float calculatePointBoundsPenetration(Point3f point, EntityBounds bounds, float threshold) {
        if (bounds.getMinX() == bounds.getMaxX() && bounds.getMinY() == bounds.getMaxY()
        && bounds.getMinZ() == bounds.getMaxZ()) {
            // Zero-size bounds - use distance
            Point3f boundsPoint = new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
            float distance = point.distance(boundsPoint);
            return (distance == 0) ? 0 : Math.max(0, threshold - distance);
        } else {
            // Regular bounds - distance from point to nearest surface
            return Math.min(Math.min(point.x - bounds.getMinX(), bounds.getMaxX() - point.x),
                            Math.min(Math.min(point.y - bounds.getMinY(), bounds.getMaxY() - point.y),
                                     Math.min(point.z - bounds.getMinZ(), bounds.getMaxZ() - point.z)));
        }
    }

    private Optional<CollisionPair<ID, Content>> checkAABBCollision(ID id1, Content content1, EntityBounds bounds1,
                                                                    ID id2, Content content2, EntityBounds bounds2) {
        if (boundsIntersect(bounds1, bounds2)) {
            var contactPoint = calculateContactPoint(bounds1, bounds2);
            var contactNormal = calculateContactNormal(bounds1, bounds2);
            var penetrationDepth = calculatePenetrationDepth(bounds1, bounds2);

            return Optional.of(
            CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, contactPoint, contactNormal,
                                 penetrationDepth));
        }
        return Optional.empty();
    }

    /** Narrow-phase invocations during the last {@link #findAllCollisions()} (Luciferase-ig4yi test seam). */
    int lastNarrowPhaseChecks() {
        return lastNarrowPhaseChecks.get();
    }

    private void checkAndAddCollision(ID id1, ID id2, List<CollisionPair<ID, Content>> collisions) {
        if (id1.equals(id2)) {
            return;
        }

        lastNarrowPhaseChecks.incrementAndGet();
        var collision = performDetailedCollisionCheck(id1, id2);
        collision.ifPresent(collisions::add);
    }

    private Optional<CollisionPair<ID, Content>> checkMixedCollision(ID id1, Content content1, EntityBounds bounds1,
                                                                     ID id2, Content content2, EntityBounds bounds2) {
        var bounds = bounds1 != null ? bounds1 : bounds2;
        var pointEntityId = bounds1 != null ? id2 : id1;
        var pointPos = core.entityManager().getEntityPosition(pointEntityId);

        if (pointPos == null) {
            return Optional.empty();
        }

        float threshold = 0.1f;
        if (isPointInBoundsWithThreshold(pointPos, bounds, threshold)) {
            var contactPoint = new Point3f(pointPos);
            var contactNormal = calculatePointBoundsNormal(pointPos, bounds);
            var penetrationDepth = calculatePointBoundsPenetration(pointPos, bounds, threshold);

            return Optional.of(
            CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, contactPoint, contactNormal,
                                 penetrationDepth));
        }
        return Optional.empty();
    }

    private void checkNeighborNodesForCollisions(ID entityId, Key nodeIndex, Set<ID> checkedEntities,
                                                 List<CollisionPair<ID, Content>> collisions) {
        var neighbors = new LinkedList<Key>();
        var visitedNeighbors = new HashSet<Key>();
        callback.addNeighboringNodes(nodeIndex, neighbors, visitedNeighbors);

        while (!neighbors.isEmpty()) {
            var neighborIndex = neighbors.poll();
            checkNodeEntitiesForCollisions(entityId, neighborIndex, checkedEntities, collisions);
        }
    }

    private void checkNodeEntitiesForCollisions(ID entityId, Key nodeIndex, Set<ID> checkedEntities,
                                                List<CollisionPair<ID, Content>> collisions) {
        var node = core.spatialIndex().get(nodeIndex);
        if (node == null || node.isEmpty()) {
            return;
        }

        for (ID otherId : node.getEntityIds()) {
            if (!checkedEntities.add(otherId)) {
                continue;
            }
            checkAndAddCollision(entityId, otherId, collisions);
        }
    }

    private Optional<CollisionPair<ID, Content>> checkPointCollision(ID id1, Content content1, EntityBounds bounds1,
                                                                     ID id2, Content content2, EntityBounds bounds2) {
        var pos1 = core.entityManager().getEntityPosition(id1);
        var pos2 = core.entityManager().getEntityPosition(id2);

        if (pos1 == null || pos2 == null) {
            return Optional.empty();
        }

        var distance = pos1.distance(pos2);
        var threshold = 0.1f;

        if (distance <= threshold) {
            var contactPoint = new Point3f();
            contactPoint.interpolate(pos1, pos2, 0.5f);

            Vector3f contactNormal = new Vector3f();
            contactNormal.sub(pos2, pos1);
            if (contactNormal.length() > 0) {
                contactNormal.normalize();
            } else {
                contactNormal.set(1, 0, 0); // Default normal for identical positions
            }

            // For identical positions, penetration depth is 0
            // Otherwise it's the overlap amount
            var penetrationDepth = (distance == 0) ? 0 : Math.max(0, threshold - distance);

            return Optional.of(
            CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, contactPoint, contactNormal,
                                 penetrationDepth));
        }
        return Optional.empty();
    }

    private Optional<CollisionPair<ID, Content>> checkShapeCollision(ID id1, Content content1, EntityBounds bounds1,
                                                                     CollisionShape shape1, ID id2, Content content2,
                                                                     EntityBounds bounds2, CollisionShape shape2) {
        var result = shape1.collidesWith(shape2);
        if (result.collides) {
            // Luciferase-zz8xp: carry the narrow-phase contact manifold through to the CollisionPair instead of
            // dropping it, so an angular resolver can distribute impulse across the contact area.
            return Optional.of(CollisionPair.create(id1, content1, bounds1, id2, content2, bounds2, result.contactPoint,
                                                    result.contactNormal, result.penetrationDepth,
                                                    result.contactManifold));
        }
        return Optional.empty();
    }

    // ===== Four-phase findAllCollisions sweep =====

    private void findAdjacentNodeCollisions(List<CollisionPair<ID, Content>> collisions,
                                            Set<UnorderedPair<ID>> checkedPairs) {
        for (var nodeIndex : core.spatialIndex().keySet()) {
            var node = core.spatialIndex().get(nodeIndex);
            if (node == null || node.isEmpty()) {
                continue;
            }

            var nodeEntities = new ArrayList<>(node.getEntityIds());

            // Find neighboring nodes
            var neighbors = new LinkedList<Key>();
            var visitedNeighbors = new HashSet<Key>();
            callback.addNeighboringNodes(nodeIndex, neighbors, visitedNeighbors);

            // Check entities between this node and its neighbors
            while (!neighbors.isEmpty()) {
                var neighborIndex = neighbors.poll();

                // Skip already processed nodes (SFC ordering optimization)
                if (neighborIndex.compareTo(nodeIndex) < 0) {
                    continue;
                }

                var neighborNode = core.spatialIndex().get(neighborIndex);
                if (neighborNode == null || neighborNode.isEmpty()) {
                    continue;
                }

                // Check entities between adjacent nodes
                for (ID id1 : nodeEntities) {
                    // Skip bounded entities (already handled)
                    if (core.entityManager().getEntityBounds(id1) != null) {
                        continue;
                    }

                    for (ID id2 : neighborNode.getEntityIds()) {
                        var pair = new UnorderedPair<>(id1, id2);
                        if (checkedPairs.add(pair)) {
                            checkAndAddCollision(id1, id2, collisions);
                        }
                    }
                }
            }
        }
    }

    private void findBoundedEntityCollisions(ID entityId, EntityBounds entityBounds, Set<ID> checkedEntities,
                                             List<CollisionPair<ID, Content>> collisions) {
        var nodesToCheck = findNodesIntersectingBounds(entityBounds);

        for (var nodeIndex : nodesToCheck) {
            checkNodeEntitiesForCollisions(entityId, nodeIndex, checkedEntities, collisions);
        }
    }

    private void findBoundedEntityCollisions(List<CollisionPair<ID, Content>> collisions,
                                             Set<UnorderedPair<ID>> checkedPairs) {
        // Sweep-and-prune on the X axis (Luciferase-ig4yi). The previous code tested every pair of bounded entities
        // (O(n^2)). A node-membership broad-phase is UNSOUND here: bounded entities are stored only in their
        // position node (SINGLE_NODE_ONLY), so two large AABBs overlapping in the gap between their position nodes
        // would be silently missed. SAP is EXACT — it prunes only pairs that cannot overlap on X — and
        // sub-quadratic for spatially distributed scenes. Each X-overlapping survivor still gets the full
        // narrow-phase AABB test in checkAndAddCollision.
        var bounded = new ArrayList<BoundedEntry<ID>>();
        for (var entityId : core.entityManager().getAllEntityIds()) {
            var bounds = core.entityManager().getEntityBounds(entityId);
            if (bounds != null) {
                bounded.add(new BoundedEntry<>(entityId, bounds));
            }
        }
        bounded.sort(Comparator.comparingDouble(e -> e.minX));

        var active = new ArrayList<BoundedEntry<ID>>();
        for (var e : bounded) {
            // Drop actives whose X span ends before e begins — they cannot overlap e or anything after it on X.
            active.removeIf(f -> f.maxX < e.minX);
            for (var f : active) {
                var pair = new UnorderedPair<>(e.id, f.id);
                if (checkedPairs.add(pair)) {
                    checkAndAddCollision(e.id, f.id, collisions);
                }
            }
            active.add(e);
        }
    }

    /** X-axis sweep-and-prune entry for bounded-entity broad-phase (Luciferase-ig4yi). */
    private static final class BoundedEntry<I> {
        final I     id;
        final float minX;
        final float maxX;

        BoundedEntry(I id, EntityBounds bounds) {
            this.id = id;
            this.minX = bounds.getMinX();
            this.maxX = bounds.getMaxX();
        }
    }

    private void findIntraNodeCollisions(List<CollisionPair<ID, Content>> collisions,
                                         Set<UnorderedPair<ID>> checkedPairs) {
        for (var nodeIndex : core.spatialIndex().keySet()) {
            var node = core.spatialIndex().get(nodeIndex);
            if (node == null || node.isEmpty()) {
                continue;
            }

            var nodeEntities = new ArrayList<>(node.getEntityIds());
            if (nodeEntities.size() < 2) {
                continue;
            }

            // Check all pairs within this node
            for (int i = 0; i < nodeEntities.size() - 1; i++) {
                for (int j = i + 1; j < nodeEntities.size(); j++) {
                    var id1 = nodeEntities.get(i);
                    var id2 = nodeEntities.get(j);
                    var pair = new UnorderedPair<>(id1, id2);
                    if (checkedPairs.add(pair)) {
                        checkAndAddCollision(id1, id2, collisions);
                    }
                }
            }
        }
    }

    private void findPointBoundedCollisions(List<CollisionPair<ID, Content>> collisions,
                                            Set<UnorderedPair<ID>> checkedPairs) {
        // First, collect all bounded entities and their expanded search regions
        var boundedEntitySearchNodes = new HashMap<ID, Set<Key>>();

        for (var nodeIndex : core.spatialIndex().keySet()) {
            var node = core.spatialIndex().get(nodeIndex);
            if (node == null || node.isEmpty()) {
                continue;
            }

            for (ID entityId : node.getEntityIds()) {
                var bounds = core.entityManager().getEntityBounds(entityId);
                if (bounds != null) {
                    // Calculate nodes that could contain point entities that might collide
                    var threshold = 0.1f; // Collision threshold for point entities
                    var expandedBounds = new EntityBounds(
                    new Point3f(bounds.getMinX() - threshold, bounds.getMinY() - threshold,
                                bounds.getMinZ() - threshold),
                    new Point3f(bounds.getMaxX() + threshold, bounds.getMaxY() + threshold,
                                bounds.getMaxZ() + threshold));
                    var searchNodes = findNodesIntersectingBounds(expandedBounds);
                    boundedEntitySearchNodes.put(entityId, searchNodes);
                }
            }
        }

        // Now check point entities against bounded entities in their search regions
        for (var entry : boundedEntitySearchNodes.entrySet()) {
            var boundedId = entry.getKey();
            var searchNodes = entry.getValue();

            for (var searchNodeIndex : searchNodes) {
                var searchNode = core.spatialIndex().get(searchNodeIndex);
                if (searchNode == null || searchNode.isEmpty()) {
                    continue;
                }

                for (ID pointId : searchNode.getEntityIds()) {
                    if (core.entityManager().getEntityBounds(pointId) == null) { // Only check point entities
                        var pair = new UnorderedPair<>(boundedId, pointId);
                        if (checkedPairs.add(pair)) {
                            checkAndAddCollision(boundedId, pointId, collisions);
                        }
                    }
                }
            }
        }
    }

    private void findPointEntityCollisions(ID entityId, Set<Key> locations, Set<ID> checkedEntities,
                                           List<CollisionPair<ID, Content>> collisions) {
        for (var nodeIndex : locations) {
            // Check entities in the same node
            checkNodeEntitiesForCollisions(entityId, nodeIndex, checkedEntities, collisions);

            // Check entities in neighboring nodes
            checkNeighborNodesForCollisions(entityId, nodeIndex, checkedEntities, collisions);
        }
    }

    // ===== Volume/region helpers =====

    /**
     * Find all nodes intersecting the bounds of a bounded entity. Verbatim port of the pre-extraction protected
     * façade overload — composes over {@link CollisionGeometry#spatialRangeQuery}, NOT the raw subclass
     * {@code findNodesIntersectingBounds(VolumeBounds)} hook, so the precise {@code doesNodeIntersectVolume}
     * filter the range query applies is preserved. A direct call to the {@code VolumeBounds} hook skips that
     * filter and returns false-positive candidate nodes, which silently dropped multi-entity collisions
     * (caught by {@code OctreeCollisionAccuracyTest.testMultipleCollisionAccuracy} on the first P5 verification
     * pass).
     */
    private Set<Key> findNodesIntersectingBounds(EntityBounds bounds) {
        var volumeBounds = new VolumeBounds(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(), bounds.getMaxX(),
                                            bounds.getMaxY(), bounds.getMaxZ());
        var intersectingNodes = new HashSet<Key>();
        callback.spatialRangeQuery(volumeBounds, true).forEach(entry -> intersectingNodes.add(entry.getKey()));
        return intersectingNodes;
    }

    private boolean isEntityInRegion(ID entityId, Spatial region) {
        var pos = core.entityManager().getEntityPosition(entityId);
        if (pos == null) {
            return false;
        }

        EntityBounds bounds = core.entityManager().getEntityBounds(entityId);
        if (bounds != null) {
            // Check if bounds intersect region
            return boundsIntersectVolume(bounds, region);
        } else {
            // Check if point is in region
            return isPointInVolume(pos, region);
        }
    }

    private boolean isPointInBoundsWithThreshold(Point3f point, EntityBounds bounds, float threshold) {
        var inBounds = point.x >= bounds.getMinX() && point.x <= bounds.getMaxX() && point.y >= bounds.getMinY()
        && point.y <= bounds.getMaxY() && point.z >= bounds.getMinZ() && point.z <= bounds.getMaxZ();

        // For zero-size bounds, check distance to bounds center
        if (!inBounds && bounds.getMinX() == bounds.getMaxX() && bounds.getMinY() == bounds.getMaxY()
        && bounds.getMinZ() == bounds.getMaxZ()) {
            Point3f boundsPoint = new Point3f(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
            float distance = point.distance(boundsPoint);
            inBounds = distance <= threshold;
        }

        return inBounds;
    }

    private boolean isPointInVolume(Point3f point, Spatial volume) {
        return switch (volume) {
            case Spatial.Cube cube ->
            point.x >= cube.originX() && point.x <= cube.originX() + cube.extent() && point.y >= cube.originY()
            && point.y <= cube.originY() + cube.extent() && point.z >= cube.originZ()
            && point.z <= cube.originZ() + cube.extent();

            case Spatial.Sphere sphere -> {
                var dx = point.x - sphere.centerX();
                var dy = point.y - sphere.centerY();
                var dz = point.z - sphere.centerZ();
                yield (dx * dx + dy * dy + dz * dz) <= (sphere.radius() * sphere.radius());
            }

            default -> true; // Conservative for unknown types
        };
    }

    private Optional<CollisionPair<ID, Content>> performDetailedCollisionCheck(ID id1, ID id2) {
        var bounds1 = core.entityManager().getEntityBounds(id1);
        var bounds2 = core.entityManager().getEntityBounds(id2);
        var content1 = core.entityManager().getEntityContent(id1);
        var content2 = core.entityManager().getEntityContent(id2);

        // Check if we have collision shapes for narrow-phase detection
        var shape1 = core.entityManager().getEntityCollisionShape(id1);
        var shape2 = core.entityManager().getEntityCollisionShape(id2);

        if (shape1 != null && shape2 != null) {
            return checkShapeCollision(id1, content1, bounds1, shape1, id2, content2, bounds2, shape2);
        }

        // Fall back to AABB collision detection
        if (bounds1 != null && bounds2 != null) {
            return checkAABBCollision(id1, content1, bounds1, id2, content2, bounds2);
        } else if (bounds1 != null || bounds2 != null) {
            return checkMixedCollision(id1, content1, bounds1, id2, content2, bounds2);
        } else {
            return checkPointCollision(id1, content1, bounds1, id2, content2, bounds2);
        }
    }
}

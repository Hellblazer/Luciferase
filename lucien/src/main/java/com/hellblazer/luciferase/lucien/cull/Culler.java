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
package com.hellblazer.luciferase.lucien.cull;

import com.hellblazer.luciferase.lucien.Frustum3D;
import com.hellblazer.luciferase.lucien.FrustumIntersection;
import com.hellblazer.luciferase.lucien.Plane3D;
import com.hellblazer.luciferase.lucien.PlaneIntersection;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.SpatialDistanceCalculator;
import com.hellblazer.luciferase.lucien.SpatialIndex.RayIntersection;
import com.hellblazer.luciferase.lucien.SpatialIndexCore;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.internal.ObjectPools;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The frustum/plane/ray cull feature object for a spatial index (RDR-008 P4).
 *
 * <p>Owns the bundled cull cluster — five frustum entries (inside, intersecting, visible, visible-ID, within-distance),
 * five plane entries (all+tolerance, all, negative side, positive side, within-distance), three ray entries (all,
 * first, within), the standard non-DSOC cull fallback {@link #frustumCullVisibleStandard}, and the cluster's twelve
 * private geometry helpers (frustum/plane/ray AABB + point/sphere intersection, entity-intersection calculators, the
 * two AABB-closest-point helpers, the ray-entity distance default, and the {@code getComponent} accessor used by ray-
 * AABB slab). Bundled rather than split per RDR-008 §Decision item 2 (shared traversal hooks, high internal cohesion).
 *
 * <p>The cluster's seven subclass-overridden hooks (frustum / plane / ray traversal + intersect, plus ray-node
 * intersection distance) and the two cached entity accessors and the spanning-policy flag arrive through
 * {@link CullGeometry}; the shared sorted-node map, the read-write lock, and the entity manager arrive through
 * {@link SpatialIndexCore}.
 *
 * <p>{@link FrustumCullProvider} is implemented directly: {@code DsocController} takes a {@code Culler} as its
 * frustum-cull-provider argument and calls {@link #frustumCullVisibleStandard} as the fallback when DSOC's Z-buffer
 * is inactive — preserving the P1-installed DSOC ↔ standard-cull seam.
 *
 * @param <Key>     the spatial key type
 * @param <ID>      the entity identifier type
 * @param <Content> the entity content type
 * @author hal.hildebrand
 */
public final class Culler<Key extends SpatialKey<Key>, ID extends EntityID, Content>
implements FrustumCullProvider<Key, ID, Content> {

    private final SpatialIndexCore<Key, ID, Content> core;
    private final CullGeometry<Key, ID, Content>     callback;

    public Culler(SpatialIndexCore<Key, ID, Content> core, CullGeometry<Key, ID, Content> callback) {
        this.core = core;
        this.callback = callback;
    }

    // ===== Frustum culling =====
    //
    // Note: the public derivatives frustumCullInside / frustumCullIntersecting / frustumCullWithinDistance live on
    // the façade and compose on top of frustumCullVisible(frustum, cameraPosition) so they inherit the DSOC routing
    // decision. The Culler never re-implements them here — doing so would offer a path that silently bypasses DSOC.

    /**
     * Simple ID-only frustum-visibility query (no DSOC integration, no distance sorting, point-containment only).
     * Verbatim preservation of the pre-extraction single-arg variant. Distinct from the camera-position-aware
     * {@link #frustumCullVisibleStandard}: this variant has no DSOC equivalent on the façade because the pre-
     * extraction {@code frustumCullVisible(Frustum3D)} was always non-DSOC.
     */
    public List<ID> frustumCullVisibleIds(Frustum3D frustum) {
        if (frustum == null) {
            throw new NullPointerException("Frustum cannot be null");
        }
        core.lock().readLock().lock();
        try {
            var visibleEntities = new ArrayList<ID>();
            var visitedEntities = new HashSet<ID>();
            core.spatialIndex().forEach((nodeIndex, node) -> {
                if (node == null || node.isEmpty()) {
                    return;
                }
                if (!callback.doesFrustumIntersectNode(nodeIndex, frustum)) {
                    return;
                }
                for (ID entityId : node.getEntityIds()) {
                    if (visitedEntities.add(entityId)) {
                        var entityPos = callback.getCachedEntityPosition(entityId);
                        if (entityPos != null && frustum.containsPoint(entityPos)) {
                            visibleEntities.add(entityId);
                        }
                    }
                }
            });
            return visibleEntities;
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Standard (non-DSOC) frustum cull — the P1-introduced fallback. Verbatim preservation. */
    @Override
    public List<FrustumIntersection<ID, Content>> frustumCullVisibleStandard(Frustum3D frustum,
                                                                             Point3f cameraPosition) {
        if (frustum == null) {
            throw new NullPointerException("Frustum cannot be null");
        }
        core.lock().readLock().lock();
        try {
            var intersections = ObjectPools.<FrustumIntersection<ID, Content>>borrowArrayList();
            var visitedEntities = ObjectPools.<ID>borrowHashSet();
            try {
                callback.getFrustumTraversalOrder(frustum, cameraPosition).forEach(nodeIndex -> {
                    var node = core.spatialIndex().get(nodeIndex);
                    if (node == null || node.isEmpty()) {
                        return;
                    }
                    if (!callback.doesFrustumIntersectNode(nodeIndex, frustum)) {
                        return;
                    }
                    for (ID entityId : node.getEntityIds()) {
                        if (!visitedEntities.add(entityId)) {
                            continue;
                        }
                        var content = core.entityManager().getEntityContent(entityId);
                        if (content == null) {
                            continue;
                        }
                        var entityPos = callback.getCachedEntityPosition(entityId);
                        var bounds = callback.getCachedEntityBounds(entityId);
                        var intersection = calculateFrustumEntityIntersection(frustum, cameraPosition, entityId,
                                                                              content, entityPos, bounds);
                        if (intersection != null && intersection.isVisible()) {
                            intersections.add(intersection);
                        }
                    }
                });
                Collections.sort(intersections);
                return new ArrayList<>(intersections);
            } finally {
                ObjectPools.returnArrayList(intersections);
                ObjectPools.returnHashSet(visitedEntities);
            }
        } finally {
            core.lock().readLock().unlock();
        }
    }

    // ===== Plane intersection =====

    /** Find all entities intersecting the plane within {@code tolerance} of it. */
    public List<PlaneIntersection<ID, Content>> planeIntersectAll(Plane3D plane, float tolerance) {
        core.lock().readLock().lock();
        try {
            var intersections = new ArrayList<PlaneIntersection<ID, Content>>();
            callback.getPlaneTraversalOrder(plane).forEach(nodeIndex -> {
                var node = core.spatialIndex().get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    return;
                }
                if (!callback.doesPlaneIntersectNode(nodeIndex, plane)) {
                    return;
                }
                for (ID entityId : node.getEntityIds()) {
                    var content = core.entityManager().getEntityContent(entityId);
                    if (content == null) {
                        continue;
                    }
                    var entityPos = callback.getCachedEntityPosition(entityId);
                    var bounds = core.entityManager().getEntityBounds(entityId);
                    var intersection = calculatePlaneEntityIntersection(plane, entityId, content, entityPos, bounds,
                                                                        tolerance);
                    if (intersection != null) {
                        intersections.add(intersection);
                    }
                }
            });
            Collections.sort(intersections);
            return intersections;
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Find all entities exactly intersecting the plane (tolerance 0). */
    public List<PlaneIntersection<ID, Content>> planeIntersectAll(Plane3D plane) {
        return planeIntersectAll(plane, 0.0f);
    }

    /** Find all entities on the negative side of the plane (opposite to normal). */
    public List<PlaneIntersection<ID, Content>> planeIntersectNegativeSide(Plane3D plane) {
        return planeIntersectAll(plane, Float.MAX_VALUE).stream()
                                                        .filter(PlaneIntersection::isOnNegativeSide)
                                                        .collect(Collectors.toList());
    }

    /** Find all entities on the positive side of the plane (in direction of normal). */
    public List<PlaneIntersection<ID, Content>> planeIntersectPositiveSide(Plane3D plane) {
        return planeIntersectAll(plane, Float.MAX_VALUE).stream()
                                                        .filter(PlaneIntersection::isOnPositiveSide)
                                                        .collect(Collectors.toList());
    }

    /** Find all entities within {@code maxDistance} of the plane (on either side). */
    public List<PlaneIntersection<ID, Content>> planeIntersectWithinDistance(Plane3D plane, float maxDistance) {
        return planeIntersectAll(plane, maxDistance).stream()
                                                    .filter(i -> Math.abs(i.distanceFromPlane()) <= maxDistance)
                                                    .collect(Collectors.toList());
    }

    // ===== Ray intersection =====

    /** Find all entities the ray intersects, sorted by ray-distance. */
    public List<RayIntersection<ID, Content>> rayIntersectAll(Ray3D ray) {
        core.lock().readLock().lock();
        try {
            var intersections = new ArrayList<RayIntersection<ID, Content>>();
            var visitedEntities = new HashSet<ID>();
            callback.getRayTraversalOrder(ray).forEach(nodeIndex -> {
                var node = core.spatialIndex().get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    return;
                }
                if (!callback.doesRayIntersectNode(nodeIndex, ray)) {
                    return;
                }
                for (ID entityId : node.getEntityIds()) {
                    if (!visitedEntities.add(entityId)) {
                        continue;
                    }
                    var entityPos = callback.getCachedEntityPosition(entityId);
                    if (entityPos == null) {
                        continue;
                    }
                    var distance = getRayEntityDistance(ray, entityId, entityPos);
                    if (distance >= 0 && ray.isWithinDistance(distance)) {
                        var content = core.entityManager().getEntityContent(entityId);
                        var bounds = callback.getCachedEntityBounds(entityId);
                        var intersectionPoint = ray.pointAt(distance);
                        var normal = new Vector3f();
                        normal.sub(ray.origin(), intersectionPoint);
                        normal.normalize();
                        intersections.add(
                        new RayIntersection<>(entityId, content, distance, intersectionPoint, normal, bounds));
                    }
                }
            });
            // Spanning sweep — spanning entities can live in nodes outside the ray's path but still have bounds the
            // ray hits. Verbatim preservation from the pre-extraction façade.
            if (callback.isSpanningEnabled()) {
                for (var entry : core.spatialIndex().entrySet()) {
                    for (var entityId : entry.getValue().getEntityIds()) {
                        if (!visitedEntities.contains(entityId)) {
                            var entityBounds = core.entityManager().getEntityBounds(entityId);
                            if (entityBounds != null) {
                                var distance = SpatialDistanceCalculator.rayIntersectsAABB(ray, entityBounds.getMinX(),
                                                                                           entityBounds.getMinY(),
                                                                                           entityBounds.getMinZ(),
                                                                                           entityBounds.getMaxX(),
                                                                                           entityBounds.getMaxY(),
                                                                                           entityBounds.getMaxZ());
                                if (distance >= 0 && ray.isWithinDistance(distance)) {
                                    visitedEntities.add(entityId);
                                    var content = core.entityManager().getEntityContent(entityId);
                                    var intersectionPoint = ray.pointAt(distance);
                                    var normal = new Vector3f();
                                    normal.sub(ray.origin(), intersectionPoint);
                                    normal.normalize();
                                    intersections.add(new RayIntersection<>(entityId, content, distance,
                                                                            intersectionPoint, normal, entityBounds));
                                }
                            }
                        }
                    }
                }
            }
            Collections.sort(intersections);
            return intersections;
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Find the closest entity the ray hits (with early termination). */
    public Optional<RayIntersection<ID, Content>> rayIntersectFirst(Ray3D ray) {
        core.lock().readLock().lock();
        try {
            var visitedEntities = new HashSet<ID>();
            RayIntersection<ID, Content> closest = null;
            var closestDistance = Float.MAX_VALUE;
            var nodeIterator = callback.getRayTraversalOrder(ray).iterator();
            while (nodeIterator.hasNext()) {
                var nodeIndex = nodeIterator.next();
                var nodeDistance = callback.getRayNodeIntersectionDistance(nodeIndex, ray);
                if (nodeDistance > closestDistance) {
                    break;
                }
                var node = core.spatialIndex().get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    continue;
                }
                if (!callback.doesRayIntersectNode(nodeIndex, ray)) {
                    continue;
                }
                for (ID entityId : node.getEntityIds()) {
                    if (!visitedEntities.add(entityId)) {
                        continue;
                    }
                    var entityPos = callback.getCachedEntityPosition(entityId);
                    if (entityPos == null) {
                        continue;
                    }
                    var distance = getRayEntityDistance(ray, entityId, entityPos);
                    if (distance >= 0 && distance < closestDistance && ray.isWithinDistance(distance)) {
                        var content = core.entityManager().getEntityContent(entityId);
                        var bounds = callback.getCachedEntityBounds(entityId);
                        var intersectionPoint = ray.pointAt(distance);
                        var normal = new Vector3f();
                        normal.sub(ray.origin(), intersectionPoint);
                        normal.normalize();
                        closest = new RayIntersection<>(entityId, content, distance, intersectionPoint, normal, bounds);
                        closestDistance = distance;
                    }
                }
            }
            // Spanning sweep (mirrors rayIntersectAll). Verbatim preservation.
            if (callback.isSpanningEnabled()) {
                for (var entry : core.spatialIndex().entrySet()) {
                    for (var entityId : entry.getValue().getEntityIds()) {
                        if (!visitedEntities.contains(entityId)) {
                            var entityBounds = core.entityManager().getEntityBounds(entityId);
                            if (entityBounds != null) {
                                var distance = SpatialDistanceCalculator.rayIntersectsAABB(ray, entityBounds.getMinX(),
                                                                                           entityBounds.getMinY(),
                                                                                           entityBounds.getMinZ(),
                                                                                           entityBounds.getMaxX(),
                                                                                           entityBounds.getMaxY(),
                                                                                           entityBounds.getMaxZ());
                                if (distance >= 0 && distance < closestDistance && ray.isWithinDistance(distance)) {
                                    visitedEntities.add(entityId);
                                    var content = core.entityManager().getEntityContent(entityId);
                                    var intersectionPoint = ray.pointAt(distance);
                                    var normal = new Vector3f();
                                    normal.sub(ray.origin(), intersectionPoint);
                                    normal.normalize();
                                    closest = new RayIntersection<>(entityId, content, distance, intersectionPoint,
                                                                    normal, entityBounds);
                                    closestDistance = distance;
                                }
                            }
                        }
                    }
                }
            }
            return Optional.ofNullable(closest);
        } finally {
            core.lock().readLock().unlock();
        }
    }

    /** Find all entities the ray intersects within {@code maxDistance}, sorted by ray-distance. */
    public List<RayIntersection<ID, Content>> rayIntersectWithin(Ray3D ray, float maxDistance) {
        if (maxDistance <= 0) {
            return Collections.emptyList();
        }
        var boundedRay = ray.withMaxDistance(Math.min(ray.maxDistance(), maxDistance));
        core.lock().readLock().lock();
        try {
            var intersections = new ArrayList<RayIntersection<ID, Content>>();
            var visitedEntities = new HashSet<ID>();
            callback.getRayTraversalOrder(boundedRay).forEach(nodeIndex -> {
                float nodeDistance = callback.getRayNodeIntersectionDistance(nodeIndex, boundedRay);
                if (nodeDistance > maxDistance) {
                    return;
                }
                var node = core.spatialIndex().get(nodeIndex);
                if (node == null || node.isEmpty()) {
                    return;
                }
                if (!callback.doesRayIntersectNode(nodeIndex, boundedRay)) {
                    return;
                }
                for (ID entityId : node.getEntityIds()) {
                    if (!visitedEntities.add(entityId)) {
                        continue;
                    }
                    var entityPos = callback.getCachedEntityPosition(entityId);
                    if (entityPos == null) {
                        continue;
                    }
                    float distance = getRayEntityDistance(boundedRay, entityId, entityPos);
                    if (distance >= 0 && distance <= maxDistance) {
                        var content = core.entityManager().getEntityContent(entityId);
                        var bounds = callback.getCachedEntityBounds(entityId);
                        var intersectionPoint = boundedRay.pointAt(distance);
                        var normal = new Vector3f();
                        normal.sub(boundedRay.origin(), intersectionPoint);
                        normal.normalize();
                        intersections.add(
                        new RayIntersection<>(entityId, content, distance, intersectionPoint, normal, bounds));
                    }
                }
            });
            // Spanning sweep within the bounded distance (mirrors rayIntersectAll). Verbatim preservation.
            if (callback.isSpanningEnabled()) {
                for (var entry : core.spatialIndex().entrySet()) {
                    for (var entityId : entry.getValue().getEntityIds()) {
                        if (!visitedEntities.contains(entityId)) {
                            var entityBounds = core.entityManager().getEntityBounds(entityId);
                            if (entityBounds != null) {
                                var distance = SpatialDistanceCalculator.rayIntersectsAABB(boundedRay,
                                                                                           entityBounds.getMinX(),
                                                                                           entityBounds.getMinY(),
                                                                                           entityBounds.getMinZ(),
                                                                                           entityBounds.getMaxX(),
                                                                                           entityBounds.getMaxY(),
                                                                                           entityBounds.getMaxZ());
                                if (distance >= 0 && distance <= maxDistance) {
                                    visitedEntities.add(entityId);
                                    var content = core.entityManager().getEntityContent(entityId);
                                    var intersectionPoint = boundedRay.pointAt(distance);
                                    var normal = new Vector3f();
                                    normal.sub(boundedRay.origin(), intersectionPoint);
                                    normal.normalize();
                                    intersections.add(new RayIntersection<>(entityId, content, distance,
                                                                            intersectionPoint, normal, entityBounds));
                                }
                            }
                        }
                    }
                }
            }
            Collections.sort(intersections);
            return intersections;
        } finally {
            core.lock().readLock().unlock();
        }
    }

    // ===== Frustum geometry helpers =====

    private FrustumIntersection<ID, Content> calculateFrustumEntityIntersection(Frustum3D frustum,
                                                                                Point3f cameraPosition, ID entityId,
                                                                                Content content, Point3f entityPos,
                                                                                EntityBounds bounds) {
        if (bounds != null) {
            return frustumAABBIntersection(frustum, cameraPosition, entityId, content, bounds);
        }
        return frustumPointIntersection(frustum, cameraPosition, entityId, content, entityPos);
    }

    private FrustumIntersection<ID, Content> frustumAABBIntersection(Frustum3D frustum, Point3f cameraPosition,
                                                                     ID entityId, Content content,
                                                                     EntityBounds bounds) {
        var minX = bounds.getMinX();
        var minY = bounds.getMinY();
        var minZ = bounds.getMinZ();
        var maxX = bounds.getMaxX();
        var maxY = bounds.getMaxY();
        var maxZ = bounds.getMaxZ();
        var completelyInside = frustum.containsAABB(minX, minY, minZ, maxX, maxY, maxZ);
        var intersects = frustum.intersectsAABB(minX, minY, minZ, maxX, maxY, maxZ);
        if (!intersects) {
            return null;
        }
        var visibilityType = completelyInside ? FrustumIntersection.VisibilityType.INSIDE
                                              : FrustumIntersection.VisibilityType.INTERSECTING;
        var entityCenter = bounds.getCenter();
        var distanceFromCamera = cameraPosition.distance(entityCenter);
        var closestPoint = findClosestPointOnAABBToCamera(cameraPosition, bounds);
        return new FrustumIntersection<>(entityId, content, distanceFromCamera, closestPoint, visibilityType, bounds);
    }

    private FrustumIntersection<ID, Content> frustumPointIntersection(Frustum3D frustum, Point3f cameraPosition,
                                                                      ID entityId, Content content, Point3f point) {
        if (!frustum.containsPoint(point)) {
            return null;
        }
        var distanceFromCamera = cameraPosition.distance(point);
        return new FrustumIntersection<>(entityId, content, distanceFromCamera, new Point3f(point),
                                         FrustumIntersection.VisibilityType.INSIDE, null);
    }

    private Point3f findClosestPointOnAABBToCamera(Point3f cameraPosition, EntityBounds bounds) {
        var x = Math.max(bounds.getMinX(), Math.min(cameraPosition.x, bounds.getMaxX()));
        var y = Math.max(bounds.getMinY(), Math.min(cameraPosition.y, bounds.getMaxY()));
        var z = Math.max(bounds.getMinZ(), Math.min(cameraPosition.z, bounds.getMaxZ()));
        return new Point3f(x, y, z);
    }

    // ===== Plane geometry helpers =====

    private PlaneIntersection<ID, Content> calculatePlaneEntityIntersection(Plane3D plane, ID entityId, Content content,
                                                                            Point3f entityPos, EntityBounds bounds,
                                                                            float tolerance) {
        if (bounds != null) {
            return planeAABBIntersection(plane, entityId, content, bounds, tolerance);
        }
        return planePointIntersection(plane, entityId, content, entityPos, tolerance);
    }

    private PlaneIntersection<ID, Content> planeAABBIntersection(Plane3D plane, ID entityId, Content content,
                                                                 EntityBounds bounds, float tolerance) {
        var minX = bounds.getMinX();
        var minY = bounds.getMinY();
        var minZ = bounds.getMinZ();
        var maxX = bounds.getMaxX();
        var maxY = bounds.getMaxY();
        var maxZ = bounds.getMaxZ();
        var corners = new Point3f[] { new Point3f(minX, minY, minZ), new Point3f(maxX, minY, minZ),
                                      new Point3f(minX, maxY, minZ), new Point3f(maxX, maxY, minZ),
                                      new Point3f(minX, minY, maxZ), new Point3f(maxX, minY, maxZ),
                                      new Point3f(minX, maxY, maxZ), new Point3f(maxX, maxY, maxZ) };
        var minDistance = Float.POSITIVE_INFINITY;
        var maxDistance = Float.NEGATIVE_INFINITY;
        var closestPoint = new Point3f();
        for (var corner : corners) {
            var distance = plane.distanceToPoint(corner);
            if (distance < minDistance) {
                minDistance = distance;
                closestPoint.set(corner);
            }
            if (distance > maxDistance) {
                maxDistance = distance;
            }
        }
        PlaneIntersection.IntersectionType intersectionType;
        float resultDistance;
        if (Math.abs(minDistance) <= tolerance && Math.abs(maxDistance) <= tolerance) {
            intersectionType = PlaneIntersection.IntersectionType.ON_PLANE;
            resultDistance = (minDistance + maxDistance) / 2.0f;
        } else if (minDistance * maxDistance <= 0) {
            intersectionType = PlaneIntersection.IntersectionType.INTERSECTING;
            resultDistance = Math.abs(minDistance) < Math.abs(maxDistance) ? minDistance : maxDistance;
        } else if (minDistance > 0) {
            intersectionType = PlaneIntersection.IntersectionType.POSITIVE_SIDE;
            resultDistance = minDistance;
        } else {
            intersectionType = PlaneIntersection.IntersectionType.NEGATIVE_SIDE;
            resultDistance = maxDistance;
        }
        if (tolerance > 0 && Math.abs(resultDistance) > tolerance) {
            return null;
        }
        if (intersectionType == PlaneIntersection.IntersectionType.INTERSECTING) {
            closestPoint = findClosestPointOnAABBToPlane(plane, bounds);
        }
        return new PlaneIntersection<>(entityId, content, resultDistance, closestPoint, intersectionType, bounds);
    }

    private PlaneIntersection<ID, Content> planePointIntersection(Plane3D plane, ID entityId, Content content,
                                                                  Point3f point, float tolerance) {
        var distance = plane.distanceToPoint(point);
        if (tolerance > 0 && Math.abs(distance) > tolerance) {
            return null;
        }
        PlaneIntersection.IntersectionType intersectionType;
        if (Math.abs(distance) <= 1e-6f) {
            intersectionType = PlaneIntersection.IntersectionType.ON_PLANE;
        } else if (distance > 0) {
            intersectionType = PlaneIntersection.IntersectionType.POSITIVE_SIDE;
        } else {
            intersectionType = PlaneIntersection.IntersectionType.NEGATIVE_SIDE;
        }
        return new PlaneIntersection<>(entityId, content, distance, new Point3f(point), intersectionType, null);
    }

    private Point3f findClosestPointOnAABBToPlane(Plane3D plane, EntityBounds bounds) {
        var normal = plane.getNormal();
        var x = (normal.x >= 0) ? bounds.getMinX() : bounds.getMaxX();
        var y = (normal.y >= 0) ? bounds.getMinY() : bounds.getMaxY();
        var z = (normal.z >= 0) ? bounds.getMinZ() : bounds.getMaxZ();
        return new Point3f(x, y, z);
    }

    // ===== Ray geometry helpers =====

    private float getRayEntityDistance(Ray3D ray, ID entityId, Point3f entityPos) {
        EntityBounds bounds = core.entityManager().getEntityBounds(entityId);
        if (bounds != null) {
            return rayAABBIntersection(ray, bounds);
        }
        return raySphereIntersection(ray, entityPos, 0.1f);
    }

    private float rayAABBIntersection(Ray3D ray, EntityBounds bounds) {
        var tmin = 0.0f;
        var tmax = ray.maxDistance();
        for (int i = 0; i < 3; i++) {
            var origin = getComponent(ray.origin(), i);
            var direction = getComponent(ray.direction(), i);
            var min = getComponent(bounds, i, true);
            var max = getComponent(bounds, i, false);
            if (Math.abs(direction) < 1e-6f) {
                if (origin < min || origin > max) {
                    return -1;
                }
            } else {
                var t1 = (min - origin) / direction;
                var t2 = (max - origin) / direction;
                if (t1 > t2) {
                    var temp = t1;
                    t1 = t2;
                    t2 = temp;
                }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) {
                    return -1;
                }
            }
        }
        return tmin;
    }

    private float raySphereIntersection(Ray3D ray, Point3f center, float radius) {
        var distFromOrigin = ray.origin().distance(center);
        if (distFromOrigin <= radius) {
            return 0.0f;
        }
        var toCenter = new Vector3f();
        toCenter.sub(center, ray.origin());
        var t = toCenter.dot(ray.direction());
        if (t < 0) {
            return -1;
        }
        var closestPoint = new Point3f(ray.origin().x + t * ray.direction().x,
                                       ray.origin().y + t * ray.direction().y,
                                       ray.origin().z + t * ray.direction().z);
        var distToCenter = closestPoint.distance(center);
        if (distToCenter > radius) {
            return -1;
        }
        var halfChordLength = (float) Math.sqrt(radius * radius - distToCenter * distToCenter);
        var t1 = t - halfChordLength;
        var t2 = t + halfChordLength;
        if (t1 >= 0 && t1 <= ray.maxDistance()) {
            return t1;
        }
        if (t2 >= 0 && t2 <= ray.maxDistance()) {
            return t2;
        }
        return -1;
    }

    private float getComponent(Point3f point, int axis) {
        return switch (axis) {
            case 0 -> point.x;
            case 1 -> point.y;
            case 2 -> point.z;
            default -> throw new IllegalArgumentException("Invalid axis: " + axis);
        };
    }

    private float getComponent(Vector3f vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x;
            case 1 -> vector.y;
            case 2 -> vector.z;
            default -> throw new IllegalArgumentException("Invalid axis: " + axis);
        };
    }

    private float getComponent(EntityBounds bounds, int axis, boolean min) {
        return switch (axis) {
            case 0 -> min ? bounds.getMinX() : bounds.getMaxX();
            case 1 -> min ? bounds.getMinY() : bounds.getMaxY();
            case 2 -> min ? bounds.getMinZ() : bounds.getMaxZ();
            default -> throw new IllegalArgumentException("Invalid axis: " + axis);
        };
    }
}

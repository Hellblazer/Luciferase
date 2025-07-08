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

import com.hellblazer.luciferase.lucien.collision.CollisionShape.CollisionResult;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Collision detection utility using Java 23 pattern matching. Handles all shape-to-shape collision tests.
 *
 * @author hal.hildebrand
 */
public class CollisionDetector {

    private static CollisionResult boxVsBox(BoxShape box1, BoxShape box2) {
        if (!CollisionShape.boundsIntersect(box1.getAABB(), box2.getAABB())) {
            return CollisionResult.noCollision();
        }

        var xOverlap = Math.min(box1.getAABB().getMaxX() - box2.getAABB().getMinX(),
                                box2.getAABB().getMaxX() - box1.getAABB().getMinX());
        var yOverlap = Math.min(box1.getAABB().getMaxY() - box2.getAABB().getMinY(),
                                box2.getAABB().getMaxY() - box1.getAABB().getMinY());
        var zOverlap = Math.min(box1.getAABB().getMaxZ() - box2.getAABB().getMinZ(),
                                box2.getAABB().getMaxZ() - box1.getAABB().getMinZ());

        var minOverlap = xOverlap;
        var axis = 0;

        if (yOverlap < minOverlap) {
            minOverlap = yOverlap;
            axis = 1;
        }

        if (zOverlap < minOverlap) {
            minOverlap = zOverlap;
            axis = 2;
        }

        var normal = new Vector3f();
        var contactPoint = new Point3f();

        switch (axis) {
            case 0 -> {
                if (box1.getPosition().x < box2.getPosition().x) {
                    normal.set(1, 0, 0);
                    contactPoint.x = box1.getAABB().getMaxX();
                } else {
                    normal.set(-1, 0, 0);
                    contactPoint.x = box1.getAABB().getMinX();
                }
                contactPoint.y = Math.max(box1.getAABB().getMinY(), box2.getAABB().getMinY()) + Math.min(
                box1.getAABB().getMaxY() - box1.getAABB().getMinY(),
                box2.getAABB().getMaxY() - box2.getAABB().getMinY()) / 2;
                contactPoint.z = Math.max(box1.getAABB().getMinZ(), box2.getAABB().getMinZ()) + Math.min(
                box1.getAABB().getMaxZ() - box1.getAABB().getMinZ(),
                box2.getAABB().getMaxZ() - box2.getAABB().getMinZ()) / 2;
            }
            case 1 -> {
                if (box1.getPosition().y < box2.getPosition().y) {
                    normal.set(0, 1, 0);
                    contactPoint.y = box1.getAABB().getMaxY();
                } else {
                    normal.set(0, -1, 0);
                    contactPoint.y = box1.getAABB().getMinY();
                }
                contactPoint.x = Math.max(box1.getAABB().getMinX(), box2.getAABB().getMinX()) + Math.min(
                box1.getAABB().getMaxX() - box1.getAABB().getMinX(),
                box2.getAABB().getMaxX() - box2.getAABB().getMinX()) / 2;
                contactPoint.z = Math.max(box1.getAABB().getMinZ(), box2.getAABB().getMinZ()) + Math.min(
                box1.getAABB().getMaxZ() - box1.getAABB().getMinZ(),
                box2.getAABB().getMaxZ() - box2.getAABB().getMinZ()) / 2;
            }
            case 2 -> {
                if (box1.getPosition().z < box2.getPosition().z) {
                    normal.set(0, 0, 1);
                    contactPoint.z = box1.getAABB().getMaxZ();
                } else {
                    normal.set(0, 0, -1);
                    contactPoint.z = box1.getAABB().getMinZ();
                }
                contactPoint.x = Math.max(box1.getAABB().getMinX(), box2.getAABB().getMinX()) + Math.min(
                box1.getAABB().getMaxX() - box1.getAABB().getMinX(),
                box2.getAABB().getMaxX() - box2.getAABB().getMinX()) / 2;
                contactPoint.y = Math.max(box1.getAABB().getMinY(), box2.getAABB().getMinY()) + Math.min(
                box1.getAABB().getMaxY() - box1.getAABB().getMinY(),
                box2.getAABB().getMaxY() - box2.getAABB().getMinY()) / 2;
            }
        }

        return CollisionResult.collision(contactPoint, normal, minOverlap);
    }

    private static CollisionResult boxVsCapsule(BoxShape box, CapsuleShape capsule) {
        var p1 = capsule.getEndpoint1();
        var p2 = capsule.getEndpoint2();

        var closest1 = box.getClosestPoint(p1);
        var closest2 = box.getClosestPoint(p2);

        var closestOnSegment = capsule.getClosestPointOnSegment(box.getPosition());
        var closestOnBox = box.getClosestPoint(closestOnSegment);

        var delta = new Vector3f();
        delta.sub(closestOnSegment, closestOnBox);
        var distance = delta.length();

        if (distance > capsule.getRadius()) {
            return CollisionResult.noCollision();
        }

        var normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            normal = box.getClosestFaceNormal(closestOnSegment);
        }

        var penetrationDepth = capsule.getRadius() - distance;

        return CollisionResult.collision(closestOnBox, normal, penetrationDepth);
    }

    // Sphere collision methods

    private static CollisionResult boxVsMesh(BoxShape box, MeshShape mesh) {
        var triangles = mesh.getBVH().getTrianglesInAABB(box.getAABB());

        if (triangles.isEmpty()) {
            return CollisionResult.noCollision();
        }

        for (var triIndex : triangles) {
            var v0 = new Point3f();
            var v1 = new Point3f();
            var v2 = new Point3f();
            mesh.getMeshData().getTriangleVertices(triIndex, v0, v1, v2);

            if (triangleIntersectsBox(v0, v1, v2, box)) {
                var center = new Point3f(v0);
                center.add(v1);
                center.add(v2);
                center.scale(1.0f / 3.0f);

                var edge1 = new Vector3f();
                edge1.sub(v1, v0);
                var edge2 = new Vector3f();
                edge2.sub(v2, v0);
                var normal = new Vector3f();
                normal.cross(edge1, edge2);
                normal.normalize();

                return CollisionResult.collision(center, normal, 0.1f);
            }
        }

        return CollisionResult.noCollision();
    }

    private static CollisionResult boxVsOrientedBox(BoxShape box, OrientedBoxShape obb) {
        // Simplified SAT test - would need full implementation
        if (!CollisionShape.boundsIntersect(box.getAABB(), obb.getAABB())) {
            return CollisionResult.noCollision();
        }

        // For now, use center-to-center approach
        var delta = new Vector3f();
        delta.sub(obb.getPosition(), box.getPosition());

        var normal = new Vector3f(delta);
        if (normal.length() > 0) {
            normal.normalize();
        } else {
            normal.set(1, 0, 0);
        }

        var contactPoint = new Point3f();
        contactPoint.interpolate(box.getPosition(), obb.getPosition(), 0.5f);

        return CollisionResult.collision(contactPoint, normal, 0.1f);
    }

    private static CollisionResult capsuleVsCapsule(CapsuleShape capsule1, CapsuleShape capsule2) {
        var closestThis = findClosestPointsBetweenSegments(capsule1.getEndpoint1(), capsule1.getEndpoint2(),
                                                           capsule2.getEndpoint1(), capsule2.getEndpoint2());

        var closestOther = capsule2.getClosestPointOnSegment(closestThis);
        closestThis = capsule1.getClosestPointOnSegment(closestOther);

        var delta = new Vector3f();
        delta.sub(closestOther, closestThis);
        var distance = delta.length();
        var radiusSum = capsule1.getRadius() + capsule2.getRadius();

        if (distance > radiusSum) {
            return CollisionResult.noCollision();
        }

        var normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            normal = capsule1.getPerpendicularDirection();
        }

        var contactPoint = new Point3f();
        contactPoint.interpolate(closestThis, closestOther, capsule1.getRadius() / radiusSum);

        var penetrationDepth = radiusSum - distance;

        return CollisionResult.collision(contactPoint, normal, penetrationDepth);
    }

    private static CollisionResult capsuleVsMesh(CapsuleShape capsule, MeshShape mesh) {
        float searchRadius = capsule.getRadius() + capsule.getHalfHeight();
        var triangles = mesh.getBVH().getTrianglesIntersectingSphere(capsule.getPosition(), searchRadius);

        if (triangles.isEmpty()) {
            return CollisionResult.noCollision();
        }

        for (var triIndex : triangles) {
            var v0 = new Point3f();
            var v1 = new Point3f();
            var v2 = new Point3f();
            mesh.getMeshData().getTriangleVertices(triIndex, v0, v1, v2);

            if (triangleIntersectsCapsule(v0, v1, v2, capsule)) {
                var center = new Point3f(v0);
                center.add(v1);
                center.add(v2);
                center.scale(1.0f / 3.0f);

                var edge1 = new Vector3f();
                edge1.sub(v1, v0);
                var edge2 = new Vector3f();
                edge2.sub(v2, v0);
                var normal = new Vector3f();
                normal.cross(edge1, edge2);
                normal.normalize();

                return CollisionResult.collision(center, normal, 0.1f);
            }
        }

        return CollisionResult.noCollision();
    }

    private static Point3f closestPointOnTriangle(Point3f p, Point3f a, Point3f b, Point3f c) {
        var ab = new Vector3f();
        ab.sub(b, a);
        var ac = new Vector3f();
        ac.sub(c, a);
        var ap = new Vector3f();
        ap.sub(p, a);

        float d1 = ab.dot(ap);
        float d2 = ac.dot(ap);
        if (d1 <= 0 && d2 <= 0) {
            return new Point3f(a);
        }

        var bp = new Vector3f();
        bp.sub(p, b);
        float d3 = ab.dot(bp);
        float d4 = ac.dot(bp);
        if (d3 >= 0 && d4 <= d3) {
            return new Point3f(b);
        }

        float vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            float v = d1 / (d1 - d3);
            var result = new Point3f(ab);
            result.scale(v);
            result.add(a);
            return result;
        }

        var cp = new Vector3f();
        cp.sub(p, c);
        float d5 = ab.dot(cp);
        float d6 = ac.dot(cp);
        if (d6 >= 0 && d5 <= d6) {
            return new Point3f(c);
        }

        float vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            float w = d2 / (d2 - d6);
            var result = new Point3f(ac);
            result.scale(w);
            result.add(a);
            return result;
        }

        float va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            var result = new Point3f();
            result.sub(c, b);
            result.scale(w);
            result.add(b);
            return result;
        }

        float denom = 1 / (va + vb + vc);
        float v = vb * denom;
        float w = vc * denom;

        var result = new Point3f(a);
        var temp = new Point3f(ab);
        temp.scale(v);
        result.add(temp);
        temp.set(ac);
        temp.scale(w);
        result.add(temp);

        return result;
    }

    // Box collision methods

    /**
     * Detect collision between two shapes using pattern matching
     */
    public static CollisionResult detectCollision(CollisionShape shape1, CollisionShape shape2) {
        return switch (shape1) {
            case SphereShape sphere1 -> switch (shape2) {
                case SphereShape sphere2 -> sphereVsSphere(sphere1, sphere2);
                case BoxShape box -> sphereVsBox(sphere1, box);
                case OrientedBoxShape obb -> sphereVsOrientedBox(sphere1, obb);
                case CapsuleShape capsule -> sphereVsCapsule(sphere1, capsule);
                case MeshShape mesh -> sphereVsMesh(sphere1, mesh);
                case ConvexHullShape hull -> sphereVsConvexHull(sphere1, hull);
                case HeightmapShape heightmap -> sphereVsHeightmap(sphere1, heightmap);
            };

            case BoxShape box1 -> switch (shape2) {
                case SphereShape sphere -> flipNormal(sphereVsBox(sphere, box1));
                case BoxShape box2 -> boxVsBox(box1, box2);
                case OrientedBoxShape obb -> boxVsOrientedBox(box1, obb);
                case CapsuleShape capsule -> boxVsCapsule(box1, capsule);
                case MeshShape mesh -> boxVsMesh(box1, mesh);
                case ConvexHullShape hull -> boxVsConvexHull(box1, hull);
                case HeightmapShape heightmap -> boxVsHeightmap(box1, heightmap);
            };

            case OrientedBoxShape obb1 -> switch (shape2) {
                case SphereShape sphere -> flipNormal(sphereVsOrientedBox(sphere, obb1));
                case BoxShape box -> flipNormal(boxVsOrientedBox(box, obb1));
                case OrientedBoxShape obb2 -> orientedBoxVsOrientedBox(obb1, obb2);
                case CapsuleShape capsule -> orientedBoxVsCapsule(obb1, capsule);
                case MeshShape mesh -> orientedBoxVsMesh(obb1, mesh);
                case ConvexHullShape hull -> orientedBoxVsConvexHull(obb1, hull);
                case HeightmapShape heightmap -> orientedBoxVsHeightmap(obb1, heightmap);
            };

            case CapsuleShape capsule1 -> switch (shape2) {
                case SphereShape sphere -> flipNormal(sphereVsCapsule(sphere, capsule1));
                case BoxShape box -> flipNormal(boxVsCapsule(box, capsule1));
                case OrientedBoxShape obb -> flipNormal(orientedBoxVsCapsule(obb, capsule1));
                case CapsuleShape capsule2 -> capsuleVsCapsule(capsule1, capsule2);
                case MeshShape mesh -> capsuleVsMesh(capsule1, mesh);
                case ConvexHullShape hull -> capsuleVsConvexHull(capsule1, hull);
                case HeightmapShape heightmap -> capsuleVsHeightmap(capsule1, heightmap);
            };

            case MeshShape mesh1 -> switch (shape2) {
                case SphereShape sphere -> flipNormal(sphereVsMesh(sphere, mesh1));
                case BoxShape box -> flipNormal(boxVsMesh(box, mesh1));
                case OrientedBoxShape obb -> flipNormal(orientedBoxVsMesh(obb, mesh1));
                case CapsuleShape capsule -> flipNormal(capsuleVsMesh(capsule, mesh1));
                case MeshShape mesh2 -> meshVsMesh(mesh1, mesh2);
                case ConvexHullShape hull -> meshVsConvexHull(mesh1, hull);
                case HeightmapShape heightmap -> meshVsHeightmap(mesh1, heightmap);
            };
            case ConvexHullShape hull1 -> switch (shape2) {
                case SphereShape sphere -> flipNormal(sphereVsConvexHull(sphere, hull1));
                case BoxShape box -> flipNormal(boxVsConvexHull(box, hull1));
                case OrientedBoxShape obb -> flipNormal(orientedBoxVsConvexHull(obb, hull1));
                case CapsuleShape capsule -> flipNormal(capsuleVsConvexHull(capsule, hull1));
                case MeshShape mesh -> flipNormal(meshVsConvexHull(mesh, hull1));
                case ConvexHullShape hull2 -> convexHullVsConvexHull(hull1, hull2);
                case HeightmapShape heightmap -> convexHullVsHeightmap(hull1, heightmap);
            };
            case HeightmapShape heightmap1 -> switch (shape2) {
                case SphereShape sphere -> flipNormal(sphereVsHeightmap(sphere, heightmap1));
                case BoxShape box -> flipNormal(boxVsHeightmap(box, heightmap1));
                case OrientedBoxShape obb -> flipNormal(orientedBoxVsHeightmap(obb, heightmap1));
                case CapsuleShape capsule -> flipNormal(capsuleVsHeightmap(capsule, heightmap1));
                case MeshShape mesh -> flipNormal(meshVsHeightmap(mesh, heightmap1));
                case ConvexHullShape hull -> flipNormal(convexHullVsHeightmap(hull, heightmap1));
                case HeightmapShape heightmap2 -> heightmapVsHeightmap(heightmap1, heightmap2);
            };
        };
    }

    static Point3f findClosestPointsBetweenSegments(Point3f a1, Point3f a2, Point3f b1, Point3f b2) {
        var d1 = new Vector3f();
        d1.sub(a2, a1);
        var d2 = new Vector3f();
        d2.sub(b2, b1);
        var r = new Vector3f();
        r.sub(a1, b1);

        float a = d1.dot(d1);
        float b = d1.dot(d2);
        float c = d1.dot(r);
        float e = d2.dot(d2);
        float f = d2.dot(r);

        float s, t;
        float denom = a * e - b * b;

        if (denom != 0) {
            s = (b * f - c * e) / denom;
        } else {
            s = 0;
        }

        s = Math.max(0, Math.min(1, s));
        t = (b * s + f) / e;

        if (t < 0) {
            t = 0;
            s = Math.max(0, Math.min(-c / a, 1));
        } else if (t > 1) {
            t = 1;
            s = Math.max(0, Math.min((b - c) / a, 1));
        }

        var closestOnA = new Point3f();
        closestOnA.scaleAdd(s, d1, a1);

        return closestOnA;
    }

    private static CollisionResult flipNormal(CollisionResult result) {
        if (result.collides) {
            var flippedNormal = new Vector3f(result.contactNormal);
            flippedNormal.scale(-1);
            return CollisionResult.collision(result.contactPoint, flippedNormal, result.penetrationDepth);
        }
        return result;
    }

    private static Point3f getClosestPointOnSegment(Point3f p1, Point3f p2, Point3f point) {
        var v = new Vector3f();
        v.sub(p2, p1);

        if (v.lengthSquared() < 1e-6f) {
            return new Point3f(p1);
        }

        var w = new Vector3f();
        w.sub(point, p1);

        float t = w.dot(v) / v.dot(v);
        t = Math.max(0, Math.min(1, t));

        var result = new Point3f();
        result.scaleAdd(t, v, p1);

        return result;
    }

    // OrientedBox collision methods

    private static CollisionResult meshVsMesh(MeshShape mesh1, MeshShape mesh2) {
        if (!CollisionShape.boundsIntersect(mesh1.getAABB(), mesh2.getAABB())) {
            return CollisionResult.noCollision();
        }

        for (int i = 0; i < mesh1.getMeshData().getTriangleCount(); i++) {
            var v0 = new Point3f();
            var v1 = new Point3f();
            var v2 = new Point3f();
            mesh1.getMeshData().getTriangleVertices(i, v0, v1, v2);

            var triMin = new Point3f(Math.min(Math.min(v0.x, v1.x), v2.x), Math.min(Math.min(v0.y, v1.y), v2.y),
                                     Math.min(Math.min(v0.z, v1.z), v2.z));
            var triMax = new Point3f(Math.max(Math.max(v0.x, v1.x), v2.x), Math.max(Math.max(v0.y, v1.y), v2.y),
                                     Math.max(Math.max(v0.z, v1.z), v2.z));
            var triBounds = new EntityBounds(triMin, triMax);

            var otherTriangles = mesh2.getBVH().getTrianglesInAABB(triBounds);

            for (var otherTriIndex : otherTriangles) {
                var ov0 = new Point3f();
                var ov1 = new Point3f();
                var ov2 = new Point3f();
                mesh2.getMeshData().getTriangleVertices(otherTriIndex, ov0, ov1, ov2);

                if (trianglesIntersect(v0, v1, v2, ov0, ov1, ov2)) {
                    var center = new Point3f(v0);
                    center.add(v1);
                    center.add(v2);
                    center.scale(1.0f / 3.0f);

                    var edge1 = new Vector3f();
                    edge1.sub(v1, v0);
                    var edge2 = new Vector3f();
                    edge2.sub(v2, v0);
                    var normal = new Vector3f();
                    normal.cross(edge1, edge2);
                    normal.normalize();

                    return CollisionResult.collision(center, normal, 0.1f);
                }
            }
        }

        return CollisionResult.noCollision();
    }

    private static CollisionResult orientedBoxVsCapsule(OrientedBoxShape obb, CapsuleShape capsule) {
        var localP1 = obb.worldToLocal(capsule.getEndpoint1());
        var localP2 = obb.worldToLocal(capsule.getEndpoint2());

        var closestOnSegment = getClosestPointOnSegment(localP1, localP2, new Point3f(0, 0, 0));
        var closestOnBox = obb.getClosestPointLocal(closestOnSegment);

        var worldClosestOnSegment = obb.localToWorld(closestOnSegment);
        var worldClosestOnBox = obb.localToWorld(closestOnBox);

        var delta = new Vector3f();
        delta.sub(worldClosestOnSegment, worldClosestOnBox);
        var distance = delta.length();

        if (distance > capsule.getRadius()) {
            return CollisionResult.noCollision();
        }

        var normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            normal = obb.getClosestFaceNormalWorld(worldClosestOnSegment);
        }

        var penetrationDepth = capsule.getRadius() - distance;

        return CollisionResult.collision(worldClosestOnBox, normal, penetrationDepth);
    }

    private static CollisionResult orientedBoxVsMesh(OrientedBoxShape obb, MeshShape mesh) {
        var aabb = obb.getAABB();
        var triangles = mesh.getBVH().getTrianglesInAABB(aabb);

        if (triangles.isEmpty()) {
            return CollisionResult.noCollision();
        }

        for (var triIndex : triangles) {
            var v0 = new Point3f();
            var v1 = new Point3f();
            var v2 = new Point3f();
            mesh.getMeshData().getTriangleVertices(triIndex, v0, v1, v2);

            if (triangleIntersectsOBB(v0, v1, v2, obb)) {
                var center = new Point3f(v0);
                center.add(v1);
                center.add(v2);
                center.scale(1.0f / 3.0f);

                var edge1 = new Vector3f();
                edge1.sub(v1, v0);
                var edge2 = new Vector3f();
                edge2.sub(v2, v0);
                var normal = new Vector3f();
                normal.cross(edge1, edge2);
                normal.normalize();

                return CollisionResult.collision(center, normal, 0.1f);
            }
        }

        return CollisionResult.noCollision();
    }

    // Capsule collision methods

    private static CollisionResult orientedBoxVsOrientedBox(OrientedBoxShape obb1, OrientedBoxShape obb2) {
        if (!CollisionShape.boundsIntersect(obb1.getAABB(), obb2.getAABB())) {
            return CollisionResult.noCollision();
        }

        var delta = new Vector3f();
        delta.sub(obb2.getPosition(), obb1.getPosition());

        var normal = new Vector3f(delta);
        if (normal.length() > 0) {
            normal.normalize();
        } else {
            normal.set(1, 0, 0);
        }

        var contactPoint = new Point3f();
        contactPoint.interpolate(obb1.getPosition(), obb2.getPosition(), 0.5f);

        return CollisionResult.collision(contactPoint, normal, 0.1f);
    }

    private static CollisionResult sphereVsBox(SphereShape sphere, BoxShape box) {
        var closestPoint = box.getClosestPoint(sphere.getPosition());
        var delta = new Vector3f();
        delta.sub(sphere.getPosition(), closestPoint);
        var distance = delta.length();

        if (distance > sphere.getRadius()) {
            return CollisionResult.noCollision();
        }

        var normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            normal = box.getClosestFaceNormal(sphere.getPosition());
        }

        var penetrationDepth = sphere.getRadius() - distance;

        return CollisionResult.collision(closestPoint, normal, penetrationDepth);
    }

    // Mesh collision methods

    private static CollisionResult sphereVsCapsule(SphereShape sphere, CapsuleShape capsule) {
        var closestOnCapsule = capsule.getClosestPointOnSegment(sphere.getPosition());
        var delta = new Vector3f();
        delta.sub(sphere.getPosition(), closestOnCapsule);
        var distance = delta.length();
        var radiusSum = sphere.getRadius() + capsule.getRadius();

        if (distance > radiusSum) {
            return CollisionResult.noCollision();
        }

        var normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            normal = capsule.getPerpendicularDirection();
        }

        var contactPoint = new Point3f();
        contactPoint.interpolate(closestOnCapsule, sphere.getPosition(), capsule.getRadius() / radiusSum);

        var penetrationDepth = radiusSum - distance;

        return CollisionResult.collision(contactPoint, normal, penetrationDepth);
    }

    // Helper methods

    private static CollisionResult sphereVsMesh(SphereShape sphere, MeshShape mesh) {
        var triangles = mesh.getBVH().getTrianglesIntersectingSphere(sphere.getPosition(), sphere.getRadius());

        if (triangles.isEmpty()) {
            return CollisionResult.noCollision();
        }

        var closestPoint = new Point3f();
        var closestNormal = new Vector3f();
        float minDistance = Float.MAX_VALUE;
        boolean found = false;

        for (var triIndex : triangles) {
            var v0 = new Point3f();
            var v1 = new Point3f();
            var v2 = new Point3f();
            mesh.getMeshData().getTriangleVertices(triIndex, v0, v1, v2);

            var point = closestPointOnTriangle(sphere.getPosition(), v0, v1, v2);
            var dist = point.distance(sphere.getPosition());

            if (dist <= sphere.getRadius() && dist < minDistance) {
                minDistance = dist;
                closestPoint.set(point);

                var edge1 = new Vector3f();
                edge1.sub(v1, v0);
                var edge2 = new Vector3f();
                edge2.sub(v2, v0);
                closestNormal.cross(edge1, edge2);
                closestNormal.normalize();

                found = true;
            }
        }

        if (found) {
            float penetration = sphere.getRadius() - minDistance;
            return CollisionResult.collision(closestPoint, closestNormal, penetration);
        }

        return CollisionResult.noCollision();
    }

    private static CollisionResult sphereVsOrientedBox(SphereShape sphere, OrientedBoxShape obb) {
        var localSphereCenter = obb.worldToLocal(sphere.getPosition());
        var localClosest = obb.getClosestPointLocal(localSphereCenter);
        var worldClosest = obb.localToWorld(localClosest);

        var delta = new Vector3f();
        delta.sub(sphere.getPosition(), worldClosest);
        var distance = delta.length();

        if (distance > sphere.getRadius()) {
            return CollisionResult.noCollision();
        }

        var normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
        } else {
            normal = obb.getClosestFaceNormalWorld(sphere.getPosition());
        }

        var penetrationDepth = sphere.getRadius() - distance;

        return CollisionResult.collision(worldClosest, normal, penetrationDepth);
    }

    private static CollisionResult sphereVsSphere(SphereShape sphere1, SphereShape sphere2) {
        var delta = new Vector3f();
        delta.sub(sphere2.getPosition(), sphere1.getPosition());
        var distance = delta.length();
        var radiusSum = sphere1.getRadius() + sphere2.getRadius();

        if (distance > radiusSum) {
            return CollisionResult.noCollision();
        }

        var normal = new Vector3f(delta);
        if (distance > 0) {
            normal.scale(1.0f / distance);
            // Collision normal points from sphere1 towards sphere2, but we need it pointing away from sphere1
            normal.scale(-1.0f);
        } else {
            normal.set(-1, 0, 0);
        }

        var contactPoint = new Point3f();
        // Contact point is on sphere1's surface in the direction of sphere2
        var toSphere2 = new Vector3f(delta);
        if (distance > 0) {
            toSphere2.scale(1.0f / distance);
        } else {
            toSphere2.set(1, 0, 0);
        }
        contactPoint.scaleAdd(sphere1.getRadius(), toSphere2, sphere1.getPosition());

        var penetrationDepth = radiusSum - distance;

        return CollisionResult.collision(contactPoint, normal, penetrationDepth);
    }

    private static boolean triangleIntersectsBox(Point3f v0, Point3f v1, Point3f v2, BoxShape box) {
        var boxBounds = box.getAABB();

        var triMin = new Point3f(Math.min(Math.min(v0.x, v1.x), v2.x), Math.min(Math.min(v0.y, v1.y), v2.y),
                                 Math.min(Math.min(v0.z, v1.z), v2.z));
        var triMax = new Point3f(Math.max(Math.max(v0.x, v1.x), v2.x), Math.max(Math.max(v0.y, v1.y), v2.y),
                                 Math.max(Math.max(v0.z, v1.z), v2.z));

        return !(triMax.x < boxBounds.getMinX() || triMin.x > boxBounds.getMaxX() || triMax.y < boxBounds.getMinY()
                 || triMin.y > boxBounds.getMaxY() || triMax.z < boxBounds.getMinZ() || triMin.z > boxBounds.getMaxZ());
    }

    private static boolean triangleIntersectsCapsule(Point3f v0, Point3f v1, Point3f v2, CapsuleShape capsule) {
        var capsuleTop = new Point3f(capsule.getPosition());
        capsuleTop.y += capsule.getHalfHeight();
        var capsuleBottom = new Point3f(capsule.getPosition());
        capsuleBottom.y -= capsule.getHalfHeight();

        var closest = closestPointOnTriangle(capsule.getPosition(), v0, v1, v2);
        return closest.distance(capsule.getPosition()) <= capsule.getRadius();
    }

    private static boolean triangleIntersectsOBB(Point3f v0, Point3f v1, Point3f v2, OrientedBoxShape obb) {
        // Simplified test using AABB approximation
        var boxShape = new BoxShape(obb.getPosition(), obb.getHalfExtents());
        return triangleIntersectsBox(v0, v1, v2, boxShape);
    }

    private static boolean trianglesIntersect(Point3f a0, Point3f a1, Point3f a2, Point3f b0, Point3f b1, Point3f b2) {
        var aMin = new Point3f(Math.min(Math.min(a0.x, a1.x), a2.x), Math.min(Math.min(a0.y, a1.y), a2.y),
                               Math.min(Math.min(a0.z, a1.z), a2.z));
        var aMax = new Point3f(Math.max(Math.max(a0.x, a1.x), a2.x), Math.max(Math.max(a0.y, a1.y), a2.y),
                               Math.max(Math.max(a0.z, a1.z), a2.z));

        var bMin = new Point3f(Math.min(Math.min(b0.x, b1.x), b2.x), Math.min(Math.min(b0.y, b1.y), b2.y),
                               Math.min(Math.min(b0.z, b1.z), b2.z));
        var bMax = new Point3f(Math.max(Math.max(b0.x, b1.x), b2.x), Math.max(Math.max(b0.y, b1.y), b2.y),
                               Math.max(Math.max(b0.z, b1.z), b2.z));

        return !(aMax.x < bMin.x || aMin.x > bMax.x || aMax.y < bMin.y || aMin.y > bMax.y || aMax.z < bMin.z
                 || aMin.z > bMax.z);
    }
    
    // ConvexHull collision methods
    
    private static CollisionResult sphereVsConvexHull(SphereShape sphere, ConvexHullShape hull) {
        // First check if sphere center is inside hull
        var sphereCenter = sphere.getPosition();
        boolean isInside = isPointInsideConvexHull(sphereCenter, hull);
        
        if (isInside) {
            // Find closest face and push sphere out
            var closestFace = -1;
            var minDist = Float.MAX_VALUE;
            
            for (int i = 0; i < hull.getFaces().size(); i++) {
                var face = hull.getFaces().get(i);
                var v0 = hull.getVertices().get(face.v0);
                
                // Distance from point to plane
                var toPoint = new Vector3f();
                toPoint.sub(sphereCenter, v0);
                var dist = Math.abs(toPoint.dot(face.normal));
                
                if (dist < minDist) {
                    minDist = dist;
                    closestFace = i;
                }
            }
            
            if (closestFace >= 0) {
                var face = hull.getFaces().get(closestFace);
                var normal = new Vector3f(face.normal);
                normal.scale(-1); // Point outward from hull
                var penetration = sphere.getRadius() + minDist;
                var contactPoint = new Point3f();
                contactPoint.scaleAdd(-minDist, face.normal, sphereCenter);
                
                return CollisionResult.collision(contactPoint, normal, penetration);
            }
        }
        
        // Sphere center is outside - find closest point on hull surface
        var closestPoint = new Point3f();
        var closestDist = Float.MAX_VALUE;
        var closestNormal = new Vector3f();
        
        for (var face : hull.getFaces()) {
            var v0 = hull.getVertices().get(face.v0);
            var v1 = hull.getVertices().get(face.v1);
            var v2 = hull.getVertices().get(face.v2);
            
            var point = closestPointOnTriangle(sphereCenter, v0, v1, v2);
            var dist = point.distance(sphereCenter);
            
            if (dist < closestDist) {
                closestDist = dist;
                closestPoint.set(point);
                closestNormal.set(face.normal);
            }
        }
        
        if (closestDist > sphere.getRadius()) {
            return CollisionResult.noCollision();
        }
        
        var normal = new Vector3f();
        normal.sub(sphereCenter, closestPoint);
        if (normal.length() > 0) {
            normal.normalize();
        } else {
            normal.set(closestNormal);
        }
        
        var penetration = sphere.getRadius() - closestDist;
        return CollisionResult.collision(closestPoint, normal, penetration);
    }
    
    private static CollisionResult boxVsConvexHull(BoxShape box, ConvexHullShape hull) {
        // Simplified SAT test
        if (!CollisionShape.boundsIntersect(box.getAABB(), hull.getAABB())) {
            return CollisionResult.noCollision();
        }
        
        // Use center-to-center approach for simplicity
        var delta = new Vector3f();
        delta.sub(hull.getCentroid(), box.getPosition());
        
        var normal = new Vector3f(delta);
        if (normal.length() > 0) {
            normal.normalize();
        } else {
            normal.set(1, 0, 0);
        }
        
        var contactPoint = new Point3f();
        contactPoint.interpolate(box.getPosition(), hull.getCentroid(), 0.5f);
        
        return CollisionResult.collision(contactPoint, normal, 0.1f);
    }
    
    private static CollisionResult orientedBoxVsConvexHull(OrientedBoxShape obb, ConvexHullShape hull) {
        // Simplified SAT test
        if (!CollisionShape.boundsIntersect(obb.getAABB(), hull.getAABB())) {
            return CollisionResult.noCollision();
        }
        
        var delta = new Vector3f();
        delta.sub(hull.getCentroid(), obb.getPosition());
        
        var normal = new Vector3f(delta);
        if (normal.length() > 0) {
            normal.normalize();
        } else {
            normal.set(1, 0, 0);
        }
        
        var contactPoint = new Point3f();
        contactPoint.interpolate(obb.getPosition(), hull.getCentroid(), 0.5f);
        
        return CollisionResult.collision(contactPoint, normal, 0.1f);
    }
    
    private static CollisionResult capsuleVsConvexHull(CapsuleShape capsule, ConvexHullShape hull) {
        var p1 = capsule.getEndpoint1();
        var p2 = capsule.getEndpoint2();
        
        var closestOnCapsule = new Point3f();
        var closestOnHull = new Point3f();
        var minDist = Float.MAX_VALUE;
        
        // Check each face of the hull against the capsule line segment
        for (var face : hull.getFaces()) {
            var v0 = hull.getVertices().get(face.v0);
            var v1 = hull.getVertices().get(face.v1);
            var v2 = hull.getVertices().get(face.v2);
            
            // Find closest point on capsule line to this triangle
            for (float t = 0; t <= 1.0f; t += 0.1f) {
                var pointOnCapsule = new Point3f();
                pointOnCapsule.interpolate(p1, p2, t);
                
                var pointOnTriangle = closestPointOnTriangle(pointOnCapsule, v0, v1, v2);
                var dist = pointOnCapsule.distance(pointOnTriangle);
                
                if (dist < minDist) {
                    minDist = dist;
                    closestOnCapsule.set(pointOnCapsule);
                    closestOnHull.set(pointOnTriangle);
                }
            }
        }
        
        if (minDist > capsule.getRadius()) {
            return CollisionResult.noCollision();
        }
        
        var normal = new Vector3f();
        normal.sub(closestOnCapsule, closestOnHull);
        if (normal.length() > 0) {
            normal.normalize();
        } else {
            normal.set(0, 1, 0);
        }
        
        var penetration = capsule.getRadius() - minDist;
        return CollisionResult.collision(closestOnHull, normal, penetration);
    }
    
    private static CollisionResult meshVsConvexHull(MeshShape mesh, ConvexHullShape hull) {
        if (!CollisionShape.boundsIntersect(mesh.getAABB(), hull.getAABB())) {
            return CollisionResult.noCollision();
        }
        
        // Check mesh triangles against hull
        for (int i = 0; i < mesh.getMeshData().getTriangleCount(); i++) {
            var v0 = new Point3f();
            var v1 = new Point3f();
            var v2 = new Point3f();
            mesh.getMeshData().getTriangleVertices(i, v0, v1, v2);
            
            // Add mesh position offset
            v0.add(mesh.getPosition());
            v1.add(mesh.getPosition());
            v2.add(mesh.getPosition());
            
            // Check if any vertex is inside hull or if triangle intersects hull
            if (isPointInsideConvexHull(v0, hull) || 
                isPointInsideConvexHull(v1, hull) || 
                isPointInsideConvexHull(v2, hull)) {
                
                var edge1 = new Vector3f();
                edge1.sub(v1, v0);
                var edge2 = new Vector3f();
                edge2.sub(v2, v0);
                var normal = new Vector3f();
                normal.cross(edge1, edge2);
                normal.normalize();
                
                var center = new Point3f(v0);
                center.add(v1);
                center.add(v2);
                center.scale(1.0f / 3.0f);
                
                return CollisionResult.collision(center, normal, 0.1f);
            }
            
            // Also check if hull vertices are inside this triangle
            for (var hullVertex : hull.getVertices()) {
                // Simple check - project onto triangle plane
                var toVertex = new Vector3f();
                toVertex.sub(hullVertex, v0);
                
                var edge1 = new Vector3f();
                edge1.sub(v1, v0);
                var edge2 = new Vector3f();
                edge2.sub(v2, v0);
                
                // Check if point projects inside triangle (simplified)
                var u = toVertex.dot(edge1) / edge1.lengthSquared();
                var v = toVertex.dot(edge2) / edge2.lengthSquared();
                
                if (u >= 0 && v >= 0 && u + v <= 1) {
                    var normal = new Vector3f();
                    normal.cross(edge1, edge2);
                    normal.normalize();
                    
                    return CollisionResult.collision(hullVertex, normal, 0.1f);
                }
            }
        }
        
        return CollisionResult.noCollision();
    }
    
    private static CollisionResult convexHullVsConvexHull(ConvexHullShape hull1, ConvexHullShape hull2) {
        // Simplified GJK or SAT implementation
        if (!CollisionShape.boundsIntersect(hull1.getAABB(), hull2.getAABB())) {
            return CollisionResult.noCollision();
        }
        
        var delta = new Vector3f();
        delta.sub(hull2.getCentroid(), hull1.getCentroid());
        
        var normal = new Vector3f(delta);
        if (normal.length() > 0) {
            normal.normalize();
        } else {
            normal.set(1, 0, 0);
        }
        
        var contactPoint = new Point3f();
        contactPoint.interpolate(hull1.getCentroid(), hull2.getCentroid(), 0.5f);
        
        return CollisionResult.collision(contactPoint, normal, 0.1f);
    }
    
    private static boolean isPointInsideConvexHull(Point3f point, ConvexHullShape hull) {
        // Simple test - check if point is on correct side of all faces
        for (var face : hull.getFaces()) {
            var v0 = hull.getVertices().get(face.v0);
            var toPoint = new Vector3f();
            toPoint.sub(point, v0);
            
            if (toPoint.dot(face.normal) > 0) {
                return false; // Outside this face
            }
        }
        return true;
    }
    
    // Heightmap collision methods
    
    private static CollisionResult sphereVsHeightmap(SphereShape sphere, HeightmapShape heightmap) {
        var center = sphere.getPosition();
        var terrainHeight = heightmap.getHeightAtPosition(center.x, center.z);
        
        // Check if sphere bottom is below terrain
        var sphereBottom = center.y - sphere.getRadius();
        if (sphereBottom > terrainHeight) {
            return CollisionResult.noCollision();
        }
        
        // Contact point is on terrain surface directly below sphere center
        var contactPoint = new Point3f(center.x, terrainHeight, center.z);
        var normal = heightmap.getNormalAtPosition(center.x, center.z);
        
        // Penetration is how far sphere goes into terrain
        var penetration = terrainHeight - sphereBottom;
        
        return CollisionResult.collision(contactPoint, normal, penetration);
    }
    
    private static CollisionResult boxVsHeightmap(BoxShape box, HeightmapShape heightmap) {
        var boxBounds = box.getAABB();
        var heightmapBounds = heightmap.getAABB();
        
        if (!CollisionShape.boundsIntersect(boxBounds, heightmapBounds)) {
            return CollisionResult.noCollision();
        }
        
        // Check box corners against heightmap
        var minContact = new Point3f();
        var minPenetration = Float.MAX_VALUE;
        var contactNormal = new Vector3f();
        boolean hasContact = false;
        
        // Sample points on box bottom
        for (int i = 0; i <= 4; i++) {
            for (int j = 0; j <= 4; j++) {
                float tx = i / 4.0f;
                float tz = j / 4.0f;
                
                float x = boxBounds.getMinX() + tx * (boxBounds.getMaxX() - boxBounds.getMinX());
                float z = boxBounds.getMinZ() + tz * (boxBounds.getMaxZ() - boxBounds.getMinZ());
                float y = boxBounds.getMinY();
                
                float terrainHeight = heightmap.getHeightAtPosition(x, z);
                
                if (y <= terrainHeight) {
                    float penetration = terrainHeight - y;
                    if (penetration < minPenetration) {
                        minPenetration = penetration;
                        minContact.set(x, terrainHeight, z);
                        contactNormal = heightmap.getNormalAtPosition(x, z);
                        hasContact = true;
                    }
                }
            }
        }
        
        if (!hasContact) {
            return CollisionResult.noCollision();
        }
        
        return CollisionResult.collision(minContact, contactNormal, minPenetration);
    }
    
    private static CollisionResult orientedBoxVsHeightmap(OrientedBoxShape obb, HeightmapShape heightmap) {
        // Simplified - use AABB approximation
        var aabb = obb.getAABB();
        var heightmapBounds = heightmap.getAABB();
        
        if (!CollisionShape.boundsIntersect(aabb, heightmapBounds)) {
            return CollisionResult.noCollision();
        }
        
        var center = obb.getPosition();
        var terrainHeight = heightmap.getHeightAtPosition(center.x, center.z);
        
        if (center.y - obb.getHalfExtents().y > terrainHeight) {
            return CollisionResult.noCollision();
        }
        
        var contactPoint = new Point3f(center.x, terrainHeight, center.z);
        var normal = heightmap.getNormalAtPosition(center.x, center.z);
        var penetration = terrainHeight - (center.y - obb.getHalfExtents().y);
        
        return CollisionResult.collision(contactPoint, normal, penetration);
    }
    
    private static CollisionResult capsuleVsHeightmap(CapsuleShape capsule, HeightmapShape heightmap) {
        // Get capsule endpoints
        var p1 = capsule.getEndpoint1();
        var p2 = capsule.getEndpoint2();
        
        // Check multiple points along the capsule axis
        var closestPoint = new Point3f();
        var closestNormal = new Vector3f();
        var minPenetration = Float.MAX_VALUE;
        boolean collision = false;
        
        // Check endpoints and several points along the capsule
        int numSamples = 5;
        for (int i = 0; i <= numSamples; i++) {
            float t = i / (float)numSamples;
            var point = new Point3f();
            point.x = p1.x + t * (p2.x - p1.x);
            point.y = p1.y + t * (p2.y - p1.y);
            point.z = p1.z + t * (p2.z - p1.z);
            
            var terrainHeight = heightmap.getHeightAtPosition(point.x, point.z);
            var lowestPoint = point.y - capsule.getRadius();
            
            if (lowestPoint <= terrainHeight) {
                collision = true;
                var penetration = terrainHeight - lowestPoint;
                if (penetration < minPenetration) {
                    minPenetration = penetration;
                    closestPoint.set(point.x, terrainHeight, point.z);
                    closestNormal = heightmap.getNormalAtPosition(point.x, point.z);
                }
            }
        }
        
        if (collision) {
            return CollisionResult.collision(closestPoint, closestNormal, minPenetration);
        }
        
        return CollisionResult.noCollision();
    }
    
    private static CollisionResult meshVsHeightmap(MeshShape mesh, HeightmapShape heightmap) {
        if (!CollisionShape.boundsIntersect(mesh.getAABB(), heightmap.getAABB())) {
            return CollisionResult.noCollision();
        }
        
        // Check mesh vertices against heightmap
        for (int i = 0; i < mesh.getMeshData().getVertexCount(); i++) {
            var vertex = mesh.getMeshData().getVertex(i);
            var terrainHeight = heightmap.getHeightAtPosition(vertex.x, vertex.z);
            
            if (vertex.y <= terrainHeight) {
                var contactPoint = new Point3f(vertex.x, terrainHeight, vertex.z);
                var normal = heightmap.getNormalAtPosition(vertex.x, vertex.z);
                var penetration = terrainHeight - vertex.y;
                
                return CollisionResult.collision(contactPoint, normal, penetration);
            }
        }
        
        return CollisionResult.noCollision();
    }
    
    private static CollisionResult convexHullVsHeightmap(ConvexHullShape hull, HeightmapShape heightmap) {
        if (!CollisionShape.boundsIntersect(hull.getAABB(), heightmap.getAABB())) {
            return CollisionResult.noCollision();
        }
        
        // Check hull vertices against heightmap
        for (var vertex : hull.getVertices()) {
            var terrainHeight = heightmap.getHeightAtPosition(vertex.x, vertex.z);
            
            if (vertex.y <= terrainHeight) {
                var contactPoint = new Point3f(vertex.x, terrainHeight, vertex.z);
                var normal = heightmap.getNormalAtPosition(vertex.x, vertex.z);
                var penetration = terrainHeight - vertex.y;
                
                return CollisionResult.collision(contactPoint, normal, penetration);
            }
        }
        
        return CollisionResult.noCollision();
    }
    
    private static CollisionResult heightmapVsHeightmap(HeightmapShape heightmap1, HeightmapShape heightmap2) {
        // Heightmap vs heightmap is complex - simplified implementation
        var bounds1 = heightmap1.getAABB();
        var bounds2 = heightmap2.getAABB();
        
        // Check if bounds intersect
        boolean intersects = !(bounds1.getMaxX() < bounds2.getMinX() || bounds1.getMinX() > bounds2.getMaxX() ||
                              bounds1.getMaxY() < bounds2.getMinY() || bounds1.getMinY() > bounds2.getMaxY() ||
                              bounds1.getMaxZ() < bounds2.getMinZ() || bounds1.getMinZ() > bounds2.getMaxZ());
        
        if (!intersects) {
            return CollisionResult.noCollision();
        }
        
        // For now, if the bounding boxes overlap, consider it a collision
        // A more sophisticated implementation would check actual height values
        var contactPoint = new Point3f(
            (bounds1.getMaxX() + bounds2.getMinX()) / 2,
            Math.min(bounds1.getMaxY(), bounds2.getMaxY()),
            (bounds1.getMaxZ() + bounds2.getMinZ()) / 2
        );
        
        var normal = new Vector3f(0, 1, 0); // Default to up normal
        var penetration = 1.0f; // Simplified penetration depth
        
        return CollisionResult.collision(contactPoint, normal, penetration);
    }
}

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Matrix3f;
import javax.vecmath.Vector3f;

/**
 * Collision detection utility using Java 23 pattern matching. Handles all shape-to-shape collision tests.
 *
 * @author hal.hildebrand
 */
public class CollisionDetector {

    private static final Logger log = LoggerFactory.getLogger(CollisionDetector.class);

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

            var sat = triangleVsBox(v0, v1, v2, box);
            if (sat.collides) {
                // Real MTV penetration depth and SAT axis (Luciferase-bsibi) — no longer a fabricated 0.1f.
                var center = triangleCentroid(v0, v1, v2);
                return CollisionResult.collision(center, orientNormal(sat.axis, box.getPosition(), center),
                                                 sat.penetration);
            }
        }

        return CollisionResult.noCollision();
    }

    private static Point3f triangleCentroid(Point3f v0, Point3f v1, Point3f v2) {
        var c = new Point3f(v0);
        c.add(v1);
        c.add(v2);
        c.scale(1.0f / 3.0f);
        return c;
    }

    /** Orient a SAT axis to point from the box centre toward the contact (stable contact-normal sign). */
    private static Vector3f orientNormal(Vector3f axis, Point3f boxCenter, Point3f contact) {
        var n = new Vector3f(axis);
        var toContact = new Vector3f(contact.x - boxCenter.x, contact.y - boxCenter.y, contact.z - boxCenter.z);
        if (n.dot(toContact) < 0) {
            n.negate();
        }
        return n;
    }

    private static CollisionResult boxVsOrientedBox(BoxShape box, OrientedBoxShape obb) {
        if (!CollisionShape.boundsIntersect(box.getAABB(), obb.getAABB())) {
            return CollisionResult.noCollision();
        }
        // A box is an axis-aligned OBB: exact 15-axis SAT (Luciferase-i1mlg).
        return satBoxes(box, box.getPosition(), aabbAxes(), box.getHalfExtents(),
                        obb, obb.getPosition(), obbAxes(obb), obb.getHalfExtents());
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
                    // Contact at the midpoint of the two triangle centroids; real geometry-derived penetration and
                    // oriented normal from the other triangle's plane (Luciferase-p6e5g) — no longer a fabricated 0.1f.
                    var center = new Point3f(v0);
                    center.add(v1);
                    center.add(v2);
                    center.add(ov0);
                    center.add(ov1);
                    center.add(ov2);
                    center.scale(1.0f / 6.0f);

                    var normal = new Vector3f();
                    var penetration = triTriPenetration(v0, v1, v2, ov0, ov1, ov2, normal);
                    return CollisionResult.collision(center, normal, penetration);
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

            var sat = triangleVsOBB(v0, v1, v2, obb);
            if (sat.collides) {
                // Orientation-correct SAT with real MTV penetration (Luciferase-bsibi).
                var center = triangleCentroid(v0, v1, v2);
                return CollisionResult.collision(center, orientNormal(sat.axis, obb.getPosition(), center),
                                                 sat.penetration);
            }
        }

        return CollisionResult.noCollision();
    }

    // Capsule collision methods

    private static CollisionResult orientedBoxVsOrientedBox(OrientedBoxShape obb1, OrientedBoxShape obb2) {
        if (!CollisionShape.boundsIntersect(obb1.getAABB(), obb2.getAABB())) {
            return CollisionResult.noCollision();
        }
        // Exact 15-axis SAT respecting both orientations (Luciferase-i1mlg).
        return satBoxes(obb1, obb1.getPosition(), obbAxes(obb1), obb1.getHalfExtents(),
                        obb2, obb2.getPosition(), obbAxes(obb2), obb2.getHalfExtents());
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

    static boolean triangleIntersectsBox(Point3f v0, Point3f v1, Point3f v2, BoxShape box) {
        return triangleVsBox(v0, v1, v2, box).collides;
    }

    /** Triangle vs axis-aligned box via the real 13-axis SAT (Luciferase-bsibi), with MTV penetration in world. */
    private static TriBoxSat triangleVsBox(Point3f v0, Point3f v1, Point3f v2, BoxShape box) {
        var c = box.getPosition();
        var u0 = new Point3f(v0.x - c.x, v0.y - c.y, v0.z - c.z);
        var u1 = new Point3f(v1.x - c.x, v1.y - c.y, v1.z - c.z);
        var u2 = new Point3f(v2.x - c.x, v2.y - c.y, v2.z - c.z);
        return triangleBoxSAT(u0, u1, u2, box.getHalfExtents()); // box axes are world axes — MTV already in world
    }

    private static boolean triangleIntersectsCapsule(Point3f v0, Point3f v1, Point3f v2, CapsuleShape capsule) {
        var capsuleTop = new Point3f(capsule.getPosition());
        capsuleTop.y += capsule.getHalfHeight();
        var capsuleBottom = new Point3f(capsule.getPosition());
        capsuleBottom.y -= capsule.getHalfHeight();

        var closest = closestPointOnTriangle(capsule.getPosition(), v0, v1, v2);
        return closest.distance(capsule.getPosition()) <= capsule.getRadius();
    }

    // Package-private for direct unit testing of orientation-correct triangle-vs-OBB SAT (Luciferase-bsibi).
    static boolean triangleIntersectsOBB(Point3f v0, Point3f v1, Point3f v2, OrientedBoxShape obb) {
        return triangleVsOBB(v0, v1, v2, obb).collides;
    }

    /**
     * Triangle vs oriented box (Luciferase-bsibi): transform the triangle into the box's local frame (subtract the
     * centre, rotate by the inverse orientation) and run the 13-axis triangle-vs-AABB SAT against {@code [-he, he]}.
     * This replaces the previous AABB approximation that stripped the box orientation entirely.
     *
     * @return SAT result with collision flag and, when colliding, the minimal-translation penetration depth and the
     *         contact normal in WORLD space (box surface toward the triangle).
     */
    private static TriBoxSat triangleVsOBB(Point3f v0, Point3f v1, Point3f v2, OrientedBoxShape obb) {
        var center = obb.getPosition();
        var inv = new Matrix3f(obb.getOrientation());
        inv.transpose(); // rotation matrix: transpose == inverse

        var u0 = toLocal(v0, center, inv);
        var u1 = toLocal(v1, center, inv);
        var u2 = toLocal(v2, center, inv);

        var sat = triangleBoxSAT(u0, u1, u2, obb.getHalfExtents());
        if (sat.collides && sat.axis != null) {
            // Rotate the local-frame MTV axis back to world.
            var worldAxis = new Vector3f();
            obb.getOrientation().transform(sat.axis, worldAxis);
            return new TriBoxSat(true, sat.penetration, worldAxis);
        }
        return sat;
    }

    private static Point3f toLocal(Point3f p, Point3f center, Matrix3f inverseOrientation) {
        var rel = new Vector3f(p.x - center.x, p.y - center.y, p.z - center.z);
        var local = new Vector3f();
        inverseOrientation.transform(rel, local);
        return new Point3f(local.x, local.y, local.z);
    }

    /** Test seam (Luciferase-bsibi): MTV penetration depth of an axis-aligned triangle-vs-box hit, -1 if none. */
    static float triangleBoxPenetrationForTest(Point3f v0, Point3f v1, Point3f v2, BoxShape box) {
        var sat = triangleVsBox(v0, v1, v2, box);
        return sat.collides ? sat.penetration : -1.0f;
    }

    /** Test seam (Luciferase-bsibi): world-space MTV axis of an axis-aligned triangle-vs-box hit, null if none. */
    static Vector3f triangleBoxAxisForTest(Point3f v0, Point3f v1, Point3f v2, BoxShape box) {
        var sat = triangleVsBox(v0, v1, v2, box);
        return sat.collides ? sat.axis : null;
    }

    /** Result of a triangle-vs-box separating-axis test (Luciferase-bsibi). */
    private record TriBoxSat(boolean collides, float penetration, Vector3f axis) {
        static TriBoxSat noHit() {
            return new TriBoxSat(false, 0.0f, null);
        }
    }

    /**
     * 13-axis triangle-vs-AABB separating-axis test for a box centred at the origin with the given half-extents
     * (Akenine-Möller), extended to return the minimal-overlap translation vector (Luciferase-bsibi). Axes tested:
     * 3 box face normals, the triangle normal, and the 9 cross products of the 3 box axes with the 3 triangle edges.
     *
     * @param u0 u1 u2 triangle vertices expressed relative to the box centre, in the box's axis frame
     * @param h  box half-extents
     */
    private static TriBoxSat triangleBoxSAT(Point3f u0, Point3f u1, Point3f u2, Vector3f h) {
        Vector3f[] verts = { new Vector3f(u0.x, u0.y, u0.z), new Vector3f(u1.x, u1.y, u1.z),
                             new Vector3f(u2.x, u2.y, u2.z) };
        Vector3f[] edges = { sub(verts[1], verts[0]), sub(verts[2], verts[1]), sub(verts[0], verts[2]) };
        Vector3f[] boxAxes = { new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1) };

        float minOverlap = Float.MAX_VALUE;
        Vector3f mtvAxis = null;

        // 9 edge-cross axes + 3 box face normals + 1 triangle normal.
        var axes = new java.util.ArrayList<Vector3f>(13);
        for (var ba : boxAxes) {
            for (var e : edges) {
                var a = new Vector3f();
                a.cross(ba, e);
                axes.add(a);
            }
        }
        axes.add(new Vector3f(1, 0, 0));
        axes.add(new Vector3f(0, 1, 0));
        axes.add(new Vector3f(0, 0, 1));
        var triNormal = new Vector3f();
        triNormal.cross(edges[0], sub(verts[2], verts[0]));
        axes.add(triNormal);

        for (var axis : axes) {
            float len = axis.length();
            if (len < 1e-7f) {
                continue; // degenerate (parallel) axis — redundant
            }
            // Project triangle.
            float p0 = verts[0].dot(axis), p1 = verts[1].dot(axis), p2 = verts[2].dot(axis);
            float triMin = Math.min(p0, Math.min(p1, p2));
            float triMax = Math.max(p0, Math.max(p1, p2));
            // Project box (centred at origin): radius is the L1 combination of half-extents with |axis components|.
            float r = h.x * Math.abs(axis.x) + h.y * Math.abs(axis.y) + h.z * Math.abs(axis.z);
            // Penetration depth (minimum translation to separate) of the triangle interval [triMin,triMax] from the
            // box interval [-r,r]: min(triMax-(-r), r-triMin). Correct even when the triangle projects to a zero-width
            // interval on an axis (e.g. its own normal); the intersection-width formula gives 0 there and would
            // wrongly report separation.
            float overlap = Math.min(triMax + r, r - triMin);
            if (overlap < 0.0f) {
                return TriBoxSat.noHit(); // separating axis found
            }
            float normalizedOverlap = overlap / len;
            if (normalizedOverlap < minOverlap) {
                minOverlap = normalizedOverlap;
                var unit = new Vector3f(axis);
                unit.scale(1.0f / len);
                mtvAxis = unit;
            }
        }
        return new TriBoxSat(true, minOverlap, mtvAxis);
    }


    private static final float TRI_EPS = 1e-6f;

    /**
     * Real triangle-triangle intersection test (Möller, "A Fast Triangle-Triangle Intersection Test", 1997),
     * replacing the prior triangle-AABB-vs-triangle-AABB approximation that produced false positives whenever the
     * two triangles' bounding boxes overlapped (Luciferase-p6e5g). Handles the coplanar case via 2D projection.
     */
    static boolean trianglesIntersect(Point3f a0, Point3f a1, Point3f a2, Point3f b0, Point3f b1, Point3f b2) {
        // Plane of triangle B
        var nB = triNormalRaw(b0, b1, b2);
        float dB = -dot(nB, b0);
        float da0 = dot(nB, a0) + dB, da1 = dot(nB, a1) + dB, da2 = dot(nB, a2) + dB;
        if (Math.abs(da0) < TRI_EPS) da0 = 0;
        if (Math.abs(da1) < TRI_EPS) da1 = 0;
        if (Math.abs(da2) < TRI_EPS) da2 = 0;
        if (da0 * da1 > 0 && da0 * da2 > 0) {
            return false;   // triangle A entirely on one side of B's plane
        }

        // Plane of triangle A
        var nA = triNormalRaw(a0, a1, a2);
        float dA = -dot(nA, a0);
        float db0 = dot(nA, b0) + dA, db1 = dot(nA, b1) + dA, db2 = dot(nA, b2) + dA;
        if (Math.abs(db0) < TRI_EPS) db0 = 0;
        if (Math.abs(db1) < TRI_EPS) db1 = 0;
        if (Math.abs(db2) < TRI_EPS) db2 = 0;
        if (db0 * db1 > 0 && db0 * db2 > 0) {
            return false;   // triangle B entirely on one side of A's plane
        }

        // Direction of the line of intersection of the two planes; project onto its dominant axis.
        var dir = new Vector3f();
        dir.cross(nA, nB);
        if (dir.lengthSquared() < TRI_EPS * TRI_EPS) {
            return coplanarTrianglesOverlap(nA, a0, a1, a2, b0, b1, b2);   // planes parallel -> coplanar case
        }
        int axis = dominantAxis(dir);

        float[] isectA = triPlaneInterval(comp(a0, axis), comp(a1, axis), comp(a2, axis), da0, da1, da2);
        float[] isectB = triPlaneInterval(comp(b0, axis), comp(b1, axis), comp(b2, axis), db0, db1, db2);
        // Overlap of the two scalar intervals along the intersection line == triangles intersect.
        return isectA[0] <= isectB[1] && isectB[0] <= isectA[1];
    }

    /** Möller interval: where a triangle crosses the intersection line, as [min,max] along the projection axis. */
    private static float[] triPlaneInterval(float p0, float p1, float p2, float d0, float d1, float d2) {
        // Reorder so the odd-one-out vertex (opposite sign) is vertex 1.
        float t0, t1;
        if (d0 * d1 > 0) {                 // 0 and 1 same side -> 2 is the odd one
            t0 = isectPoint(p0, p2, d0, d2);
            t1 = isectPoint(p1, p2, d1, d2);
        } else if (d0 * d2 > 0) {          // 0 and 2 same side -> 1 is the odd one
            t0 = isectPoint(p0, p1, d0, d1);
            t1 = isectPoint(p2, p1, d2, d1);
        } else if (d1 * d2 > 0 || d0 != 0) {
            t0 = isectPoint(p1, p0, d1, d0);
            t1 = isectPoint(p2, p0, d2, d0);
        } else if (d1 != 0) {
            t0 = isectPoint(p0, p1, d0, d1);
            t1 = isectPoint(p2, p1, d2, d1);
        } else {                            // d2 != 0
            t0 = isectPoint(p0, p2, d0, d2);
            t1 = isectPoint(p1, p2, d1, d2);
        }
        return t0 <= t1 ? new float[] { t0, t1 } : new float[] { t1, t0 };
    }

    /** Parametric crossing point along the projection axis where the segment (p,dp)-(q,dq) crosses the plane. */
    private static float isectPoint(float p, float q, float dp, float dq) {
        return p + (q - p) * (dp / (dp - dq));
    }

    /** Coplanar triangle overlap: project to 2D on the dominant plane axis, then edge-edge + containment tests. */
    private static boolean coplanarTrianglesOverlap(Vector3f nA, Point3f a0, Point3f a1, Point3f a2, Point3f b0,
                                                    Point3f b1, Point3f b2) {
        int ax = dominantAxis(new Vector3f(Math.abs(nA.x), Math.abs(nA.y), Math.abs(nA.z)));
        int i0 = (ax == 0) ? 1 : 0;
        int i1 = (ax == 2) ? 1 : 2;
        float[] A0 = { comp(a0, i0), comp(a0, i1) }, A1 = { comp(a1, i0), comp(a1, i1) }, A2 = { comp(a2, i0),
                                                                                                 comp(a2, i1) };
        float[] B0 = { comp(b0, i0), comp(b0, i1) }, B1 = { comp(b1, i0), comp(b1, i1) }, B2 = { comp(b2, i0),
                                                                                                 comp(b2, i1) };
        float[][] ta = { A0, A1, A2 }, tb = { B0, B1, B2 };
        // Any edge pair crossing?
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (seg2DIntersect(ta[i], ta[(i + 1) % 3], tb[j], tb[(j + 1) % 3])) {
                    return true;
                }
            }
        }
        // Containment either way (one triangle fully inside the other).
        return pointInTri2D(A0, B0, B1, B2) || pointInTri2D(B0, A0, A1, A2);
    }

    private static boolean seg2DIntersect(float[] p1, float[] p2, float[] p3, float[] p4) {
        float d1 = cross2D(p3, p4, p1), d2 = cross2D(p3, p4, p2), d3 = cross2D(p1, p2, p3), d4 = cross2D(p1, p2, p4);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return false;
    }

    private static float cross2D(float[] a, float[] b, float[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static boolean pointInTri2D(float[] p, float[] a, float[] b, float[] c) {
        float d1 = cross2D(a, b, p), d2 = cross2D(b, c, p), d3 = cross2D(c, a, p);
        boolean hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(hasNeg && hasPos);
    }

    private static Vector3f triNormalRaw(Point3f v0, Point3f v1, Point3f v2) {
        var e1 = new Vector3f();
        e1.sub(v1, v0);
        var e2 = new Vector3f();
        e2.sub(v2, v0);
        var n = new Vector3f();
        n.cross(e1, e2);
        return n;
    }

    private static float dot(Vector3f n, Point3f p) {
        return n.x * p.x + n.y * p.y + n.z * p.z;
    }

    private static int dominantAxis(Vector3f d) {
        float ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        return ax >= ay ? (ax >= az ? 0 : 2) : (ay >= az ? 1 : 2);
    }

    private static float comp(Point3f p, int axis) {
        return axis == 0 ? p.x : axis == 1 ? p.y : p.z;
    }

    /**
     * Geometry-derived penetration for two intersecting triangles (Luciferase-p6e5g): the contact plane is
     * triangle B's plane, and penetration is how far triangle A's deepest vertex sits behind it. Returns a
     * positive depth (clamped to a small floor so the contact is non-degenerate) and the oriented contact normal.
     */
    private static float triTriPenetration(Point3f a0, Point3f a1, Point3f a2, Point3f b0, Point3f b1, Point3f b2,
                                            Vector3f outNormal) {
        var nB = triNormalRaw(b0, b1, b2);
        var unit = new Vector3f(nB.x, nB.y, nB.z);
        if (unit.lengthSquared() < TRI_EPS * TRI_EPS) {
            outNormal.set(0, 1, 0);
            return TRI_EPS;
        }
        unit.normalize();
        float dB = -dot(nB, b0);
        // Signed distances of A's vertices to B's (unnormalized) plane, normalized to true distance.
        float invLen = 1.0f / (float) Math.sqrt(nB.x * nB.x + nB.y * nB.y + nB.z * nB.z);
        float s0 = (dot(nB, a0) + dB) * invLen, s1 = (dot(nB, a1) + dB) * invLen, s2 = (dot(nB, a2) + dB) * invLen;
        float maxAbove = Math.max(s0, Math.max(s1, s2));
        float minBelow = Math.min(s0, Math.min(s1, s2));
        // The triangle straddles the plane; penetration is the smaller of the two protrusions (true overlap depth).
        float depth = Math.min(Math.abs(minBelow), Math.abs(maxAbove));
        // Orient the normal from B toward A (use the side A protrudes furthest).
        if (Math.abs(minBelow) > Math.abs(maxAbove)) {
            unit.negate();
        }
        outNormal.set(unit);
        return Math.max(depth, TRI_EPS);
    }

    /** Proximity threshold for treating a hull vertex as resting on a mesh triangle (Luciferase-p6e5g). */
    private static final float HULL_TRI_CONTACT_EPS = 0.05f;

    /**
     * A point inside a convex hull is pushed out along the nearest face: the penetration depth is the distance to
     * that face and the contact normal is the face's outward normal (Luciferase-p6e5g). Geometry-derived, replacing
     * the fabricated 0.1f; mirrors the inside-hull handling already used by sphereVsConvexHull.
     */
    private static CollisionResult hullInteriorPush(Point3f point, ConvexHullShape hull) {
        int closest = -1;
        float minDist = Float.MAX_VALUE;
        for (int i = 0; i < hull.getFaces().size(); i++) {
            var face = hull.getFaces().get(i);
            var fv0 = hull.getVertices().get(face.v0);
            var toPoint = new Vector3f();
            toPoint.sub(point, fv0);
            float dist = Math.abs(toPoint.dot(face.normal));
            if (dist < minDist) {
                minDist = dist;
                closest = i;
            }
        }
        if (closest < 0) {
            return CollisionResult.collision(new Point3f(point), new Vector3f(0, 1, 0), TRI_EPS);
        }
        var normal = new Vector3f(hull.getFaces().get(closest).normal);   // outward
        return CollisionResult.collision(new Point3f(point), normal, Math.max(minDist, TRI_EPS));
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
        if (!CollisionShape.boundsIntersect(box.getAABB(), hull.getAABB())) {
            return CollisionResult.noCollision();
        }
        // GJK intersection + EPA depth/normal via support functions (Luciferase-i1mlg).
        return gjkEpa(box, hull);
    }

    private static CollisionResult orientedBoxVsConvexHull(OrientedBoxShape obb, ConvexHullShape hull) {
        if (!CollisionShape.boundsIntersect(obb.getAABB(), hull.getAABB())) {
            return CollisionResult.noCollision();
        }
        return gjkEpa(obb, hull);
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
            
            // Mesh vertex embedded in the hull: real penetration is the depth to the nearest hull face, with the
            // outward face normal as the contact normal (Luciferase-p6e5g) — not a fabricated 0.1f.
            for (var meshVertex : new Point3f[] { v0, v1, v2 }) {
                if (isPointInsideConvexHull(meshVertex, hull)) {
                    return hullInteriorPush(meshVertex, hull);
                }
            }

            // Hull vertex resting on/through this triangle: penetration is the (small) distance from the hull
            // vertex to the triangle surface, normal is the triangle normal. Uses a real closest-point-on-triangle
            // proximity test instead of the prior unsound edge-projection (which fired for far vertices that merely
            // projected within the triangle). Exact tri-vs-hull min-translation (EPA/SAT) is deferred; this gives a
            // geometry-derived contact for the resting-vertex case.
            var triNormal = new Vector3f();
            {
                var e1 = new Vector3f();
                e1.sub(v1, v0);
                var e2 = new Vector3f();
                e2.sub(v2, v0);
                triNormal.cross(e1, e2);
                if (triNormal.lengthSquared() > TRI_EPS * TRI_EPS) {
                    triNormal.normalize();
                }
            }
            for (var hullVertex : hull.getVertices()) {
                var closest = closestPointOnTriangle(hullVertex, v0, v1, v2);
                var diff = new Vector3f();
                diff.sub(hullVertex, closest);
                float dist = diff.length();
                if (dist <= HULL_TRI_CONTACT_EPS) {
                    return CollisionResult.collision(new Point3f(hullVertex), triNormal,
                                                     Math.max(HULL_TRI_CONTACT_EPS - dist, TRI_EPS));
                }
            }
        }
        
        return CollisionResult.noCollision();
    }
    
    private static CollisionResult convexHullVsConvexHull(ConvexHullShape hull1, ConvexHullShape hull2) {
        if (!CollisionShape.boundsIntersect(hull1.getAABB(), hull2.getAABB())) {
            return CollisionResult.noCollision();
        }
        // GJK intersection + EPA depth/normal via support functions (Luciferase-i1mlg).
        return gjkEpa(hull1, hull2);
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
        // Heightmap-vs-heightmap narrow phase is not implemented. Returning a fabricated overlap (the previous
        // bounds-only "collision" with penetration=1.0) injected energy into the resolver. Report no collision
        // explicitly until a real height-field-vs-height-field test exists (Luciferase-i1mlg).
        return CollisionResult.noCollision();
    }

    // ---- Exact narrow-phase geometry (Luciferase-i1mlg) ----------------------------------------------------------

    private static final float SAT_EPSILON = 1.0e-6f;

    /**
     * Unit world-space axes of an oriented box (columns of its orientation matrix).
     */
    private static Vector3f[] obbAxes(OrientedBoxShape obb) {
        var m = obb.getOrientation();
        var axes = new Vector3f[3];
        for (int i = 0; i < 3; i++) {
            var col = new Vector3f();
            m.getColumn(i, col);
            if (col.length() > SAT_EPSILON) {
                col.normalize();
            }
            axes[i] = col;
        }
        return axes;
    }

    private static Vector3f[] aabbAxes() {
        return new Vector3f[] { new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1) };
    }

    /**
     * Exact 15-axis separating-axis test for two oriented boxes (3 face normals of A, 3 of B, 9 edge-edge cross
     * products). Returns the minimum-penetration axis as the contact normal and the overlap along it as the
     * penetration depth — geometry-derived, not fabricated. A box is passed as an axis-aligned OBB.
     *
     * @param shapeA   shape A (for support-based contact point)
     * @param cA       centre of A
     * @param axesA    unit world axes of A
     * @param halfA    half-extents of A along axesA
     * @param shapeB   shape B
     * @param cB       centre of B
     * @param axesB    unit world axes of B
     * @param halfB    half-extents of B along axesB
     */
    private static CollisionResult satBoxes(CollisionShape shapeA, Point3f cA, Vector3f[] axesA, Vector3f halfA,
                                            CollisionShape shapeB, Point3f cB, Vector3f[] axesB, Vector3f halfB) {
        var t = new Vector3f();
        t.sub(cB, cA);

        float[] hA = { halfA.x, halfA.y, halfA.z };
        float[] hB = { halfB.x, halfB.y, halfB.z };

        var candidates = new java.util.ArrayList<Vector3f>(15);
        for (var a : axesA) candidates.add(a);
        for (var b : axesB) candidates.add(b);
        for (var a : axesA) {
            for (var b : axesB) {
                var cross = new Vector3f();
                cross.cross(a, b);
                if (cross.length() > SAT_EPSILON) {
                    cross.normalize();
                    candidates.add(cross);
                }
            }
        }

        float minOverlap = Float.MAX_VALUE;
        Vector3f bestAxis = null;
        int bestIndex = -1;
        for (int ci = 0; ci < candidates.size(); ci++) {
            var l = candidates.get(ci);
            float rA = hA[0] * Math.abs(axesA[0].dot(l)) + hA[1] * Math.abs(axesA[1].dot(l))
                     + hA[2] * Math.abs(axesA[2].dot(l));
            float rB = hB[0] * Math.abs(axesB[0].dot(l)) + hB[1] * Math.abs(axesB[1].dot(l))
                     + hB[2] * Math.abs(axesB[2].dot(l));
            float dist = Math.abs(t.dot(l));
            float overlap = rA + rB - dist;
            if (overlap < 0) {
                return CollisionResult.noCollision(); // separating axis found
            }
            if (overlap < minOverlap) {
                minOverlap = overlap;
                bestAxis = l;
                bestIndex = ci;
            }
        }
        if (bestAxis == null) {
            return CollisionResult.noCollision();
        }

        // Orient the contact normal from A toward B.
        var normal = new Vector3f(bestAxis);
        if (t.dot(normal) < 0) {
            normal.scale(-1);
        }

        // Candidate ordering: [0..2] = A face axes, [3..5] = B face axes, [6+] = edge-edge cross products.
        // Face contacts (face-face / face-edge) yield a contact manifold via incident-face clipping (Luciferase-nm9dj);
        // edge-edge contacts are a single point.
        if (bestIndex < 6) {
            boolean refIsA = bestIndex < 3;
            var refC = refIsA ? cA : cB;
            var refAxes = refIsA ? axesA : axesB;
            var refH = refIsA ? hA : hB;
            var incC = refIsA ? cB : cA;
            var incAxes = refIsA ? axesB : axesA;
            var incH = refIsA ? hB : hA;
            // Reference face points toward the other box: +normal if A is reference, -normal if B is reference.
            var refDir = new Vector3f(normal);
            if (!refIsA) {
                refDir.scale(-1);
            }
            var manifold = clipFaceManifold(refC, refAxes, refH, incC, incAxes, incH, refDir);
            if (!manifold.isEmpty()) {
                var rep = centroid(manifold);
                return CollisionResult.collision(rep, normal, minOverlap, manifold);
            }
        }

        // Edge-edge (or degenerate) contact: single point = midpoint of the two surface support points.
        var oppositeNormal = new Vector3f(normal);
        oppositeNormal.scale(-1);
        var supportA = shapeA.getSupport(normal);
        var supportB = shapeB.getSupport(oppositeNormal);
        var contactPoint = new Point3f();
        contactPoint.interpolate(supportA, supportB, 0.5f);

        return CollisionResult.collision(contactPoint, normal, minOverlap);
    }

    /** A box face: outward normal, centre, and the two lateral (in-plane) unit axes with their half-extents. */
    private record BoxFace(Vector3f normal, Point3f center, Vector3f u, float hu, Vector3f v, float hv) {}

    /** Pick the face of a box (centre, unit axes, half-extents) whose outward normal is most aligned with {@code dir}. */
    private static BoxFace pickFace(Point3f c, Vector3f[] axes, float[] h, Vector3f dir) {
        int bi = 0;
        float bestDot = -Float.MAX_VALUE;
        float sign = 1;
        for (int i = 0; i < 3; i++) {
            float d = axes[i].dot(dir);
            if (Math.abs(d) > bestDot) {
                bestDot = Math.abs(d);
                bi = i;
                sign = d >= 0 ? 1 : -1;
            }
        }
        var normal = new Vector3f(axes[bi]);
        normal.scale(sign);
        var center = new Point3f(c.x + normal.x * h[bi], c.y + normal.y * h[bi], c.z + normal.z * h[bi]);
        int j = (bi + 1) % 3, k = (bi + 2) % 3;
        return new BoxFace(normal, center, new Vector3f(axes[j]), h[j], new Vector3f(axes[k]), h[k]);
    }

    /**
     * Build the contact manifold (Luciferase-nm9dj) by clipping the incident box's face against the side planes of
     * the reference box's face (Sutherland-Hodgman), keeping the points on or below the reference face.
     */
    private static java.util.List<Point3f> clipFaceManifold(Point3f refC, Vector3f[] refAxes, float[] refH,
                                                            Point3f incC, Vector3f[] incAxes, float[] incH,
                                                            Vector3f refDir) {
        var ref = pickFace(refC, refAxes, refH, refDir);
        // Incident face: the incident box face most anti-parallel to the reference face normal.
        var antiRef = new Vector3f(ref.normal());
        antiRef.scale(-1);
        var inc = pickFace(incC, incAxes, incH, antiRef);

        // Incident face quad (loop order).
        var poly = new java.util.ArrayList<Point3f>(8);
        poly.add(faceVertex(inc.center(), inc.u(), inc.hu(), inc.v(), inc.hv(), +1, +1));
        poly.add(faceVertex(inc.center(), inc.u(), inc.hu(), inc.v(), inc.hv(), +1, -1));
        poly.add(faceVertex(inc.center(), inc.u(), inc.hu(), inc.v(), inc.hv(), -1, -1));
        poly.add(faceVertex(inc.center(), inc.u(), inc.hu(), inc.v(), inc.hv(), -1, +1));

        // Clip against the 4 side planes of the reference face (inward normals).
        poly = clipToPlane(poly, ref.center(), ref.u(), ref.hu(), +1);
        poly = clipToPlane(poly, ref.center(), ref.u(), ref.hu(), -1);
        poly = clipToPlane(poly, ref.center(), ref.v(), ref.hv(), +1);
        poly = clipToPlane(poly, ref.center(), ref.v(), ref.hv(), -1);

        // Keep points on or below the reference face (penetrating side), then PROJECT each onto the reference face
        // plane (standard contact-manifold convention, Bullet/Box2D): all manifold points share the contact plane so
        // moment arms are computed at the true contact surface, not the incident face.
        var manifold = new java.util.ArrayList<Point3f>(poly.size());
        for (var p : poly) {
            var rel = new Vector3f(p.x - ref.center().x, p.y - ref.center().y, p.z - ref.center().z);
            float depth = rel.dot(ref.normal());
            if (depth <= 1e-4f) {
                manifold.add(new Point3f(p.x - depth * ref.normal().x, p.y - depth * ref.normal().y,
                                         p.z - depth * ref.normal().z));
            }
        }
        return manifold;
    }

    private static Point3f faceVertex(Point3f c, Vector3f u, float hu, Vector3f v, float hv, float su, float sv) {
        return new Point3f(c.x + su * hu * u.x + sv * hv * v.x, c.y + su * hu * u.y + sv * hv * v.y,
                           c.z + su * hu * u.z + sv * hv * v.z);
    }

    /**
     * Sutherland-Hodgman clip of a convex polygon to the half-space bounded by the plane {@code planePoint ± h*axis}
     * (side selected by {@code sideSign}) with the inward normal pointing into the face extent.
     */
    private static java.util.ArrayList<Point3f> clipToPlane(java.util.List<Point3f> poly, Point3f center,
                                                            Vector3f axis, float h, float sideSign) {
        // Plane through center + sideSign*h*axis; inward normal = -sideSign*axis (points toward the face interior).
        var planePoint = new Point3f(center.x + sideSign * h * axis.x, center.y + sideSign * h * axis.y,
                                     center.z + sideSign * h * axis.z);
        var inward = new Vector3f(axis);
        inward.scale(-sideSign);

        var out = new java.util.ArrayList<Point3f>(poly.size() + 1);
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            var cur = poly.get(i);
            var nxt = poly.get((i + 1) % n);
            float dc = signedDist(cur, planePoint, inward);
            float dn = signedDist(nxt, planePoint, inward);
            if (dc >= 0) {
                out.add(cur);
            }
            if ((dc >= 0) != (dn >= 0)) {
                float tt = dc / (dc - dn); // intersection parameter
                out.add(new Point3f(cur.x + tt * (nxt.x - cur.x), cur.y + tt * (nxt.y - cur.y),
                                    cur.z + tt * (nxt.z - cur.z)));
            }
        }
        return out;
    }

    private static float signedDist(Point3f p, Point3f planePoint, Vector3f inwardNormal) {
        return (p.x - planePoint.x) * inwardNormal.x + (p.y - planePoint.y) * inwardNormal.y
               + (p.z - planePoint.z) * inwardNormal.z;
    }

    private static Point3f centroid(java.util.List<Point3f> pts) {
        var c = new Point3f();
        for (var p : pts) {
            c.add(p);
        }
        c.scale(1.0f / pts.size());
        return c;
    }

    /**
     * GJK intersection test followed by EPA penetration extraction for two convex shapes, using each shape's
     * support function (the Minkowski-difference support is {@code A.getSupport(d) - B.getSupport(-d)}). Returns a
     * geometry-derived penetration depth and contact normal — replacing the fabricated 0.1f for convex-hull pairs
     * (Luciferase-i1mlg).
     */
    private static CollisionResult gjkEpa(CollisionShape shapeA, CollisionShape shapeB) {
        var simplex = new java.util.ArrayList<Vector3f>(4);
        var dir = new Vector3f();
        dir.sub(shapeB.getPosition(), shapeA.getPosition());
        if (dir.length() < SAT_EPSILON) {
            dir.set(1, 0, 0);
        }

        simplex.add(minkowskiSupport(shapeA, shapeB, dir));
        dir.scale(-1); // toward the origin

        for (int iter = 0; iter < 64; iter++) {
            var a = minkowskiSupport(shapeA, shapeB, dir);
            if (a.dot(dir) < 0) {
                return CollisionResult.noCollision(); // no overlap along the search direction
            }
            simplex.add(a);
            if (gjkUpdateSimplex(simplex, dir)) {
                return epaPenetration(shapeA, shapeB, simplex);
            }
        }
        log.warn("GJK did not converge in 64 iterations for {} vs {}; reporting no collision",
                 shapeA.getClass().getSimpleName(), shapeB.getClass().getSimpleName());
        return CollisionResult.noCollision();
    }

    private static Vector3f minkowskiSupport(CollisionShape a, CollisionShape b, Vector3f dir) {
        var negDir = new Vector3f(dir);
        negDir.scale(-1);
        var pa = a.getSupport(dir);
        var pb = b.getSupport(negDir);
        return new Vector3f(pa.x - pb.x, pa.y - pb.y, pa.z - pb.z);
    }

    /**
     * Evolve the GJK simplex toward the origin (newest vertex stored last). Returns true once a tetrahedron
     * encloses the origin; otherwise {@code dir} is updated to the next search direction and the simplex is
     * reduced to its origin-facing Voronoi feature. Canonical line/triangle/tetrahedron region handling.
     */
    private static boolean gjkUpdateSimplex(java.util.List<Vector3f> simplex, Vector3f dir) {
        return switch (simplex.size()) {
            case 2 -> gjkLine(simplex, dir);
            case 3 -> gjkTriangle(simplex, dir);
            case 4 -> gjkTetra(simplex, dir);
            default -> false;
        };
    }

    private static void resetSimplex(java.util.List<Vector3f> simplex, Vector3f... verts) {
        simplex.clear();
        for (var v : verts) {
            simplex.add(v);
        }
    }

    /** Vector triple product (a x b) x c — points perpendicular to a, toward c's side. */
    private static Vector3f tripleCross(Vector3f a, Vector3f b, Vector3f c) {
        return cross(cross(a, b), c);
    }

    private static boolean gjkLine(java.util.List<Vector3f> simplex, Vector3f dir) {
        var b = simplex.get(0);
        var a = simplex.get(1); // newest
        var ab = sub(b, a);
        var ao = neg(a);
        if (ab.dot(ao) > 0) {
            var perp = tripleCross(ab, ao, ab);
            if (perp.lengthSquared() < SAT_EPSILON) {
                // Origin lies on the line through ab: any direction perpendicular to ab heads toward it.
                perp = cross(ab, new Vector3f(1, 0, 0));
                if (perp.lengthSquared() < SAT_EPSILON) {
                    perp = cross(ab, new Vector3f(0, 1, 0));
                }
            }
            setDir(dir, perp);
        } else {
            resetSimplex(simplex, a);
            setDir(dir, ao);
        }
        return false;
    }

    private static boolean gjkTriangle(java.util.List<Vector3f> simplex, Vector3f dir) {
        var c = simplex.get(0);
        var b = simplex.get(1);
        var a = simplex.get(2); // newest
        var ab = sub(b, a);
        var ac = sub(c, a);
        var ao = neg(a);
        var abc = cross(ab, ac);

        if (cross(abc, ac).dot(ao) > 0) {           // origin outside edge ac
            if (ac.dot(ao) > 0) {
                resetSimplex(simplex, c, a);
                setDir(dir, tripleCross(ac, ao, ac));
                return false;
            }
            resetSimplex(simplex, b, a);
            return gjkLine(simplex, dir);
        }
        if (cross(ab, abc).dot(ao) > 0) {           // origin outside edge ab
            resetSimplex(simplex, b, a);
            return gjkLine(simplex, dir);
        }
        if (abc.lengthSquared() < SAT_EPSILON) {     // degenerate (colinear) triangle — fall back to an edge
            resetSimplex(simplex, b, a);
            return gjkLine(simplex, dir);
        }
        if (abc.dot(ao) > 0) {                       // origin above the triangle
            setDir(dir, abc);
        } else {                                     // origin below — flip winding
            resetSimplex(simplex, b, c, a);
            setDir(dir, neg(abc));
        }
        return false;
    }

    private static boolean gjkTetra(java.util.List<Vector3f> simplex, Vector3f dir) {
        var d = simplex.get(0);
        var c = simplex.get(1);
        var b = simplex.get(2);
        var a = simplex.get(3); // newest
        var ao = neg(a);
        var abc = cross(sub(b, a), sub(c, a));
        var acd = cross(sub(c, a), sub(d, a));
        var adb = cross(sub(d, a), sub(b, a));

        if (abc.dot(ao) > 0) {
            resetSimplex(simplex, c, b, a);
            return gjkTriangle(simplex, dir);
        }
        if (acd.dot(ao) > 0) {
            resetSimplex(simplex, d, c, a);
            return gjkTriangle(simplex, dir);
        }
        if (adb.dot(ao) > 0) {
            resetSimplex(simplex, b, d, a);
            return gjkTriangle(simplex, dir);
        }
        return true; // origin enclosed
    }

    /**
     * Expanding Polytope Algorithm: grow the GJK tetrahedron toward the Minkowski-difference boundary to recover
     * the minimum penetration depth and its normal.
     */
    private static CollisionResult epaPenetration(CollisionShape shapeA, CollisionShape shapeB,
                                                  java.util.List<Vector3f> simplexVerts) {
        var verts = new java.util.ArrayList<>(simplexVerts);
        // Faces as index triples with outward-consistent winding relative to the polytope centroid.
        var faces = new java.util.ArrayList<int[]>();
        faces.add(new int[] { 0, 1, 2 });
        faces.add(new int[] { 0, 2, 3 });
        faces.add(new int[] { 0, 3, 1 });
        faces.add(new int[] { 1, 3, 2 });

        for (int iter = 0; iter < 64; iter++) {
            // Find the face closest to the origin.
            int closest = -1;
            float minDist = Float.MAX_VALUE;
            Vector3f minNormal = null;
            for (int f = 0; f < faces.size(); f++) {
                var face = faces.get(f);
                var n = faceNormal(verts, face);
                if (n.length() < SAT_EPSILON) {
                    continue;
                }
                n.normalize();
                float d = n.dot(verts.get(face[0]));
                if (d < 0) { // ensure outward-facing
                    n.scale(-1);
                    d = -d;
                }
                if (d < minDist) {
                    minDist = d;
                    minNormal = n;
                    closest = f;
                }
            }
            if (closest < 0 || minNormal == null) {
                return CollisionResult.noCollision();
            }

            var support = minkowskiSupport(shapeA, shapeB, minNormal);
            float supportDist = support.dot(minNormal);
            if (supportDist - minDist < 1.0e-4f) {
                // Converged: minNormal is the penetration normal (A->B), minDist the depth.
                var normal = new Vector3f(minNormal);
                var oppositeNormal = new Vector3f(normal);
                oppositeNormal.scale(-1);
                var supportA = shapeA.getSupport(normal);
                var supportB = shapeB.getSupport(oppositeNormal);
                var contactPoint = new Point3f();
                contactPoint.interpolate(supportA, supportB, 0.5f);
                return CollisionResult.collision(contactPoint, normal, Math.max(minDist, 0));
            }

            // Expand: remove faces that the new support point can "see", re-triangulate the hole.
            int newIndex = verts.size();
            verts.add(support);
            var edges = new java.util.ArrayList<int[]>();
            for (int f = faces.size() - 1; f >= 0; f--) {
                var face = faces.get(f);
                var n = faceNormal(verts, face);
                // Orient n outward (away from the origin) — the same sign convention the closest-face loop uses —
                // before the visibility test, else an inward-wound face inverts the test and corrupts the hull.
                if (n.dot(verts.get(face[0])) < 0) {
                    n.scale(-1);
                }
                var toSupport = sub(support, verts.get(face[0]));
                if (n.dot(toSupport) > 0) {
                    addEdge(edges, face[0], face[1]);
                    addEdge(edges, face[1], face[2]);
                    addEdge(edges, face[2], face[0]);
                    faces.remove(f);
                }
            }
            for (var e : edges) {
                faces.add(new int[] { e[0], e[1], newIndex });
            }
            if (faces.isEmpty()) {
                return CollisionResult.noCollision();
            }
        }
        log.warn("EPA did not converge in 64 iterations for {} vs {}; reporting no collision",
                 shapeA.getClass().getSimpleName(), shapeB.getClass().getSimpleName());
        return CollisionResult.noCollision();
    }

    private static void addEdge(java.util.List<int[]> edges, int a, int b) {
        // Cancel a shared edge (the reverse direction already present) so only boundary edges remain.
        for (int i = 0; i < edges.size(); i++) {
            if (edges.get(i)[0] == b && edges.get(i)[1] == a) {
                edges.remove(i);
                return;
            }
        }
        edges.add(new int[] { a, b });
    }

    private static Vector3f faceNormal(java.util.List<Vector3f> verts, int[] face) {
        return cross(sub(verts.get(face[1]), verts.get(face[0])), sub(verts.get(face[2]), verts.get(face[0])));
    }

    private static Vector3f sub(Vector3f a, Vector3f b) {
        return new Vector3f(a.x - b.x, a.y - b.y, a.z - b.z);
    }

    private static Vector3f neg(Vector3f a) {
        return new Vector3f(-a.x, -a.y, -a.z);
    }

    private static Vector3f cross(Vector3f a, Vector3f b) {
        var r = new Vector3f();
        r.cross(a, b);
        return r;
    }

    private static void setDir(Vector3f dir, Vector3f value) {
        dir.set(value);
    }
}

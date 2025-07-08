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

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Axis-aligned box collision shape for narrow-phase collision detection.
 *
 * @author hal.hildebrand
 */
public final class BoxShape extends CollisionShape {

    private final Vector3f     halfExtents;
    private       EntityBounds bounds;

    public BoxShape(Point3f center, Vector3f halfExtents) {
        super(center);
        this.halfExtents = new Vector3f(halfExtents);
        this.bounds = new EntityBounds(center, halfExtents.x, halfExtents.y, halfExtents.z);
    }

    public BoxShape(Point3f center, float halfWidth, float halfHeight, float halfDepth) {
        this(center, new Vector3f(halfWidth, halfHeight, halfDepth));
    }

    @Override
    public CollisionResult collidesWith(CollisionShape other) {
        return CollisionDetector.detectCollision(this, other);
    }

    @Override
    public EntityBounds getAABB() {
        return bounds;
    }

    /**
     * Get the normal of the closest face to a point (assumes point is inside)
     */
    public Vector3f getClosestFaceNormal(Point3f point) {
        // Find which face is closest
        var distances = new float[6];
        distances[0] = point.x - bounds.getMinX(); // Left face
        distances[1] = bounds.getMaxX() - point.x; // Right face
        distances[2] = point.y - bounds.getMinY(); // Bottom face
        distances[3] = bounds.getMaxY() - point.y; // Top face
        distances[4] = point.z - bounds.getMinZ(); // Back face
        distances[5] = bounds.getMaxZ() - point.z; // Front face

        var minIndex = 0;
        var minDistance = distances[0];
        for (var i = 1; i < 6; i++) {
            if (distances[i] < minDistance) {
                minDistance = distances[i];
                minIndex = i;
            }
        }

        return switch (minIndex) {
            case 0 -> new Vector3f(-1, 0, 0);
            case 1 -> new Vector3f(1, 0, 0);
            case 2 -> new Vector3f(0, -1, 0);
            case 3 -> new Vector3f(0, 1, 0);
            case 4 -> new Vector3f(0, 0, -1);
            case 5 -> new Vector3f(0, 0, 1);
            default -> new Vector3f(1, 0, 0);
        };
    }

    /**
     * Get the closest point on this box to a given point
     */
    public Point3f getClosestPoint(Point3f point) {
        var x = Math.max(bounds.getMinX(), Math.min(point.x, bounds.getMaxX()));
        var y = Math.max(bounds.getMinY(), Math.min(point.y, bounds.getMaxY()));
        var z = Math.max(bounds.getMinZ(), Math.min(point.z, bounds.getMaxZ()));
        return new Point3f(x, y, z);
    }

    public Vector3f getHalfExtents() {
        return new Vector3f(halfExtents);
    }

    @Override
    public Point3f getSupport(Vector3f direction) {
        var support = new Point3f(position);

        support.x += (direction.x >= 0) ? halfExtents.x : -halfExtents.x;
        support.y += (direction.y >= 0) ? halfExtents.y : -halfExtents.y;
        support.z += (direction.z >= 0) ? halfExtents.z : -halfExtents.z;

        return support;
    }

    @Override
    public RayIntersectionResult intersectRay(Ray3D ray) {
        var tmin = 0.0f;
        var tmax = ray.maxDistance();

        // For each axis
        for (var i = 0; i < 3; i++) {
            var origin = switch (i) {
                case 0 -> ray.origin().x;
                case 1 -> ray.origin().y;
                case 2 -> ray.origin().z;
                default -> throw new IllegalStateException();
            };

            var direction = switch (i) {
                case 0 -> ray.direction().x;
                case 1 -> ray.direction().y;
                case 2 -> ray.direction().z;
                default -> throw new IllegalStateException();
            };

            var min = switch (i) {
                case 0 -> bounds.getMinX();
                case 1 -> bounds.getMinY();
                case 2 -> bounds.getMinZ();
                default -> throw new IllegalStateException();
            };

            var max = switch (i) {
                case 0 -> bounds.getMaxX();
                case 1 -> bounds.getMaxY();
                case 2 -> bounds.getMaxZ();
                default -> throw new IllegalStateException();
            };

            if (Math.abs(direction) < 1e-6f) {
                // Ray is parallel to slab
                if (origin < min || origin > max) {
                    return RayIntersectionResult.noIntersection();
                }
            } else {
                var invD = 1.0f / direction;
                var t1 = (min - origin) * invD;
                var t2 = (max - origin) * invD;

                if (t1 > t2) {
                    var temp = t1;
                    t1 = t2;
                    t2 = temp;
                }

                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);

                if (tmin > tmax) {
                    return RayIntersectionResult.noIntersection();
                }
            }
        }

        var intersectionPoint = ray.pointAt(tmin);

        // Determine which face was hit for the normal
        var normal = new Vector3f();
        var epsilon = 1e-4f;

        if (Math.abs(intersectionPoint.x - bounds.getMinX()) < epsilon) {
            normal.set(-1, 0, 0);
        } else if (Math.abs(intersectionPoint.x - bounds.getMaxX()) < epsilon) {
            normal.set(1, 0, 0);
        } else if (Math.abs(intersectionPoint.y - bounds.getMinY()) < epsilon) {
            normal.set(0, -1, 0);
        } else if (Math.abs(intersectionPoint.y - bounds.getMaxY()) < epsilon) {
            normal.set(0, 1, 0);
        } else if (Math.abs(intersectionPoint.z - bounds.getMinZ()) < epsilon) {
            normal.set(0, 0, -1);
        } else if (Math.abs(intersectionPoint.z - bounds.getMaxZ()) < epsilon) {
            normal.set(0, 0, 1);
        }

        return RayIntersectionResult.intersection(tmin, intersectionPoint, normal);
    }

    @Override
    public void translate(Vector3f delta) {
        position.add(delta);
        bounds = new EntityBounds(position, halfExtents.x, halfExtents.y, halfExtents.z);
    }
}
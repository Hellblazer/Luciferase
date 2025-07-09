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

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Utility class for common spatial distance calculations used by spatial indices.
 * Provides shared implementations for distance calculations to reduce code duplication.
 *
 * @author hal.hildebrand
 */
public class SpatialDistanceCalculator {

    /**
     * Calculate distance from a point to the center of a spatial bounds.
     *
     * @param bounds the spatial bounds
     * @param point the query point
     * @return distance from point to bounds center
     */
    public static float distanceToCenter(Spatial bounds, Point3f point) {
        Point3f center = getCenter(bounds);
        return center.distance(point);
    }

    /**
     * Calculate distance from a plane to the center of a spatial bounds.
     *
     * @param bounds the spatial bounds
     * @param plane the query plane
     * @return signed distance from plane to bounds center
     */
    public static float distanceToPlane(Spatial bounds, Plane3D plane) {
        Point3f center = getCenter(bounds);
        return plane.distanceToPoint(center);
    }

    /**
     * Calculate the center point of a spatial bounds.
     *
     * @param bounds the spatial bounds
     * @return center point
     */
    public static Point3f getCenter(Spatial bounds) {
        return switch (bounds) {
            case Spatial.Cube cube -> new Point3f(
                cube.originX() + cube.extent() / 2.0f,
                cube.originY() + cube.extent() / 2.0f,
                cube.originZ() + cube.extent() / 2.0f
            );
            case Spatial.Sphere sphere -> new Point3f(
                sphere.centerX(),
                sphere.centerY(),
                sphere.centerZ()
            );
            default -> throw new IllegalArgumentException("Unsupported spatial type: " + bounds.getClass());
        };
    }

    /**
     * Calculate the closest point on a spatial bounds to a query point.
     *
     * @param bounds the spatial bounds
     * @param point the query point
     * @return closest point on bounds to query point
     */
    public static Point3f closestPointOnBounds(Spatial bounds, Point3f point) {
        return switch (bounds) {
            case Spatial.Cube cube -> {
                float closestX = Math.max(cube.originX(), Math.min(point.x, cube.originX() + cube.extent()));
                float closestY = Math.max(cube.originY(), Math.min(point.y, cube.originY() + cube.extent()));
                float closestZ = Math.max(cube.originZ(), Math.min(point.z, cube.originZ() + cube.extent()));
                yield new Point3f(closestX, closestY, closestZ);
            }
            case Spatial.Sphere sphere -> {
                Point3f center = new Point3f(sphere.centerX(), sphere.centerY(), sphere.centerZ());
                Vector3f direction = new Vector3f();
                direction.sub(point, center);
                float distance = direction.length();
                
                if (distance <= sphere.radius()) {
                    // Point is inside sphere, it is the closest point
                    yield point;
                } else {
                    // Point is outside, closest point is on sphere surface
                    direction.normalize();
                    direction.scale(sphere.radius());
                    Point3f result = new Point3f();
                    result.add(center, direction);
                    yield result;
                }
            }
            default -> throw new IllegalArgumentException("Unsupported spatial type: " + bounds.getClass());
        };
    }

    /**
     * Calculate minimum distance from a point to a spatial bounds.
     *
     * @param bounds the spatial bounds
     * @param point the query point
     * @return minimum distance from point to bounds
     */
    public static float minimumDistance(Spatial bounds, Point3f point) {
        Point3f closest = closestPointOnBounds(bounds, point);
        return closest.distance(point);
    }

    /**
     * Check if a ray intersects an axis-aligned bounding box.
     *
     * @param ray the ray to test
     * @param minX minimum X coordinate
     * @param minY minimum Y coordinate
     * @param minZ minimum Z coordinate
     * @param maxX maximum X coordinate
     * @param maxY maximum Y coordinate
     * @param maxZ maximum Z coordinate
     * @return distance to intersection, or -1 if no intersection
     */
    public static float rayIntersectsAABB(Ray3D ray, float minX, float minY, float minZ, 
                                         float maxX, float maxY, float maxZ) {
        float tmin = 0.0f;
        float tmax = ray.maxDistance();

        // X axis
        if (Math.abs(ray.direction().x) < 1e-6f) {
            if (ray.origin().x < minX || ray.origin().x > maxX) {
                return -1;
            }
        } else {
            float t1 = (minX - ray.origin().x) / ray.direction().x;
            float t2 = (maxX - ray.origin().x) / ray.direction().x;

            if (t1 > t2) {
                float temp = t1;
                t1 = t2;
                t2 = temp;
            }

            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);

            if (tmin > tmax) {
                return -1;
            }
        }

        // Y axis
        if (Math.abs(ray.direction().y) < 1e-6f) {
            if (ray.origin().y < minY || ray.origin().y > maxY) {
                return -1;
            }
        } else {
            float t1 = (minY - ray.origin().y) / ray.direction().y;
            float t2 = (maxY - ray.origin().y) / ray.direction().y;

            if (t1 > t2) {
                float temp = t1;
                t1 = t2;
                t2 = temp;
            }

            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);

            if (tmin > tmax) {
                return -1;
            }
        }

        // Z axis
        if (Math.abs(ray.direction().z) < 1e-6f) {
            if (ray.origin().z < minZ || ray.origin().z > maxZ) {
                return -1;
            }
        } else {
            float t1 = (minZ - ray.origin().z) / ray.direction().z;
            float t2 = (maxZ - ray.origin().z) / ray.direction().z;

            if (t1 > t2) {
                float temp = t1;
                t1 = t2;
                t2 = temp;
            }

            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);

            if (tmin > tmax) {
                return -1;
            }
        }

        return tmin;
    }
}
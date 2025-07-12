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
package com.hellblazer.luciferase.lucien.geometry;

import com.hellblazer.luciferase.lucien.Ray3D;

/**
 * Utility class for Axis-Aligned Bounding Box (AABB) intersection tests.
 * 
 * @author hal.hildebrand
 */
public class AABBIntersector {
    
    /**
     * Test if a ray intersects an AABB.
     * 
     * @param bounds The AABB bounds [minX, minY, minZ, maxX, maxY, maxZ]
     * @param ray The ray to test
     * @return true if the ray intersects the AABB
     */
    public static boolean intersectsRay(float[] bounds, Ray3D ray) {
        float[] t = computeRayAABBIntersection(bounds, ray);
        return t != null;
    }
    
    /**
     * Compute ray-AABB intersection parameters.
     * 
     * @param bounds The AABB bounds [minX, minY, minZ, maxX, maxY, maxZ]
     * @param ray The ray to test
     * @return Array of [tNear, tFar] or null if no intersection
     */
    public static float[] computeRayAABBIntersection(float[] bounds, Ray3D ray) {
        var origin = ray.origin();
        var direction = ray.direction();
        
        // Compute intersection parameters for each axis
        float tMin = Float.NEGATIVE_INFINITY;
        float tMax = Float.POSITIVE_INFINITY;
        
        // X axis
        if (Math.abs(direction.x) > 1e-6f) {
            float t1 = (bounds[0] - origin.x) / direction.x;
            float t2 = (bounds[3] - origin.x) / direction.x;
            
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        } else if (origin.x < bounds[0] || origin.x > bounds[3]) {
            return null; // Ray parallel to X axis and outside bounds
        }
        
        // Y axis
        if (Math.abs(direction.y) > 1e-6f) {
            float t1 = (bounds[1] - origin.y) / direction.y;
            float t2 = (bounds[4] - origin.y) / direction.y;
            
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        } else if (origin.y < bounds[1] || origin.y > bounds[4]) {
            return null; // Ray parallel to Y axis and outside bounds
        }
        
        // Z axis
        if (Math.abs(direction.z) > 1e-6f) {
            float t1 = (bounds[2] - origin.z) / direction.z;
            float t2 = (bounds[5] - origin.z) / direction.z;
            
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        } else if (origin.z < bounds[2] || origin.z > bounds[5]) {
            return null; // Ray parallel to Z axis and outside bounds
        }
        
        // Check if intersection exists
        if (tMax < 0 || tMin > tMax) {
            return null; // No intersection
        }
        
        // Check against ray's maximum distance
        if (!ray.isUnbounded() && tMin > ray.maxDistance()) {
            return null; // Intersection beyond ray's range
        }
        
        return new float[] { tMin, tMax };
    }
    
    /**
     * Test if two AABBs intersect.
     * 
     * @param bounds1 First AABB bounds [minX, minY, minZ, maxX, maxY, maxZ]
     * @param bounds2 Second AABB bounds [minX, minY, minZ, maxX, maxY, maxZ]
     * @return true if the AABBs intersect
     */
    public static boolean intersectsAABB(float[] bounds1, float[] bounds2) {
        return bounds1[0] <= bounds2[3] && bounds1[3] >= bounds2[0] &&
               bounds1[1] <= bounds2[4] && bounds1[4] >= bounds2[1] &&
               bounds1[2] <= bounds2[5] && bounds1[5] >= bounds2[2];
    }
    
    /**
     * Check if a point is inside an AABB.
     * 
     * @param bounds The AABB bounds [minX, minY, minZ, maxX, maxY, maxZ]
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return true if the point is inside the AABB
     */
    public static boolean containsPoint(float[] bounds, float x, float y, float z) {
        return x >= bounds[0] && x <= bounds[3] &&
               y >= bounds[1] && y <= bounds[4] &&
               z >= bounds[2] && z <= bounds[5];
    }
}
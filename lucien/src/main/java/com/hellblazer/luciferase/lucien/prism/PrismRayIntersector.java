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
package com.hellblazer.luciferase.lucien.prism;

import com.hellblazer.luciferase.lucien.Ray3D;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Ray-prism intersection algorithms for triangular prisms.
 * 
 * A triangular prism has:
 * - 2 triangular faces (bottom and top)
 * - 3 quadrilateral side faces
 * - 6 vertices
 * - 9 edges
 * 
 * @author hal.hildebrand
 */
public class PrismRayIntersector {
    
    /** Small epsilon for floating point comparisons */
    private static final float EPSILON = 1e-6f;
    
    /**
     * Result of a ray-prism intersection test.
     */
    public static class IntersectionResult {
        public final boolean hit;
        public final float tNear;
        public final float tFar;
        public final Point3f nearPoint;
        public final Point3f farPoint;
        public final int nearFace;  // Which face was hit first
        public final int farFace;   // Which face was hit last
        
        public IntersectionResult() {
            this.hit = false;
            this.tNear = Float.MAX_VALUE;
            this.tFar = Float.MIN_VALUE;
            this.nearPoint = null;
            this.farPoint = null;
            this.nearFace = -1;
            this.farFace = -1;
        }
        
        public IntersectionResult(boolean hit, float tNear, float tFar, 
                                 Point3f nearPoint, Point3f farPoint,
                                 int nearFace, int farFace) {
            this.hit = hit;
            this.tNear = tNear;
            this.tFar = tFar;
            this.nearPoint = nearPoint;
            this.farPoint = farPoint;
            this.nearFace = nearFace;
            this.farFace = farFace;
        }
    }
    
    /**
     * Test if a ray intersects a triangular prism.
     * 
     * @param ray The ray to test
     * @param prism The prism key defining the prism
     * @return Intersection result with hit info and intersection points
     */
    public static IntersectionResult intersectRayPrism(Ray3D ray, PrismKey prism) {
        // Check if ray origin is inside the prism
        boolean rayStartsInside = PrismGeometry.contains(prism, ray.origin());
        
        // Get prism vertices
        float[][] vertices = getVerticesAsFloatArray(prism);
        
        // Track closest and farthest intersections
        float tMin = Float.MAX_VALUE;
        float tMax = Float.MIN_VALUE;
        int minFace = -1;
        int maxFace = -1;
        
        // Test intersection with bottom triangle (face 3)
        float t = intersectRayTriangle(ray, vertices[0], vertices[1], vertices[2]);
        if (t >= 0 && t < Float.MAX_VALUE) {
            if (t < tMin) { tMin = t; minFace = 3; }
            if (t > tMax) { tMax = t; maxFace = 3; }
        }
        
        // Test intersection with top triangle (face 4)
        t = intersectRayTriangle(ray, vertices[3], vertices[4], vertices[5]);
        if (t >= 0 && t < Float.MAX_VALUE) {
            if (t < tMin) { tMin = t; minFace = 4; }
            if (t > tMax) { tMax = t; maxFace = 4; }
        }
        
        // Test intersection with side face 0 (quad: vertices 1,2,4,5)
        t = intersectRayQuad(ray, vertices[1], vertices[2], vertices[5], vertices[4]);
        if (t >= 0 && t < Float.MAX_VALUE) {
            if (t < tMin) { tMin = t; minFace = 0; }
            if (t > tMax) { tMax = t; maxFace = 0; }
        }
        
        // Test intersection with side face 1 (quad: vertices 0,2,3,5)
        t = intersectRayQuad(ray, vertices[0], vertices[2], vertices[5], vertices[3]);
        if (t >= 0 && t < Float.MAX_VALUE) {
            if (t < tMin) { tMin = t; minFace = 1; }
            if (t > tMax) { tMax = t; maxFace = 1; }
        }
        
        // Test intersection with side face 2 (quad: vertices 0,1,3,4)
        t = intersectRayQuad(ray, vertices[0], vertices[1], vertices[4], vertices[3]);
        if (t >= 0 && t < Float.MAX_VALUE) {
            if (t < tMin) { tMin = t; minFace = 2; }
            if (t > tMax) { tMax = t; maxFace = 2; }
        }
        
        // Determine the actual intersection results
        if (rayStartsInside) {
            // Ray starts inside the prism
            if (tMax > EPSILON) {
                // We found an exit point
                Point3f nearPoint = new Point3f(ray.origin());
                Point3f farPoint = new Point3f();
                farPoint.scaleAdd(tMax, ray.direction(), ray.origin());
                
                return new IntersectionResult(true, 0.0f, tMax, nearPoint, farPoint, -1, maxFace);
            } else {
                // Degenerate case - ray doesn't exit (shouldn't happen with valid geometry)
                return new IntersectionResult();
            }
        } else {
            // Ray starts outside the prism
            if (tMin <= tMax && tMin < Float.MAX_VALUE && tMin >= 0) {
                // Calculate intersection points
                Point3f nearPoint = new Point3f();
                nearPoint.scaleAdd(tMin, ray.direction(), ray.origin());
                
                Point3f farPoint = new Point3f();
                farPoint.scaleAdd(tMax, ray.direction(), ray.origin());
                
                return new IntersectionResult(true, tMin, tMax, nearPoint, farPoint, minFace, maxFace);
            }
        }
        
        return new IntersectionResult();
    }
    
    /**
     * Test ray intersection with a triangle using Möller–Trumbore algorithm.
     * 
     * @param ray The ray
     * @param v0, v1, v2 Triangle vertices
     * @return t parameter of intersection, or Float.MAX_VALUE if no intersection
     */
    private static float intersectRayTriangle(Ray3D ray, float[] v0, float[] v1, float[] v2) {
        Vector3f edge1 = new Vector3f(v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2]);
        Vector3f edge2 = new Vector3f(v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2]);
        
        Vector3f h = new Vector3f();
        h.cross(ray.direction(), edge2);
        
        float a = edge1.dot(h);
        if (Math.abs(a) < EPSILON) {
            return Float.MAX_VALUE; // Ray is parallel to triangle
        }
        
        float f = 1.0f / a;
        Vector3f s = new Vector3f(
            ray.origin().x - v0[0],
            ray.origin().y - v0[1],
            ray.origin().z - v0[2]
        );
        
        float u = f * s.dot(h);
        if (u < 0.0f || u > 1.0f) {
            return Float.MAX_VALUE;
        }
        
        Vector3f q = new Vector3f();
        q.cross(s, edge1);
        
        float v = f * ray.direction().dot(q);
        if (v < 0.0f || u + v > 1.0f) {
            return Float.MAX_VALUE;
        }
        
        float t = f * edge2.dot(q);
        if (t >= 0) { // Accept t=0 for rays starting on the surface
            return t;
        }
        
        return Float.MAX_VALUE;
    }
    
    /**
     * Test ray intersection with a quadrilateral.
     * Splits the quad into two triangles and tests each.
     * 
     * @param ray The ray
     * @param v0, v1, v2, v3 Quad vertices in order
     * @return t parameter of intersection, or Float.MAX_VALUE if no intersection
     */
    private static float intersectRayQuad(Ray3D ray, float[] v0, float[] v1, float[] v2, float[] v3) {
        // Test first triangle (v0, v1, v2)
        float t1 = intersectRayTriangle(ray, v0, v1, v2);
        
        // Test second triangle (v0, v2, v3)
        float t2 = intersectRayTriangle(ray, v0, v2, v3);
        
        // Return the closer intersection
        return Math.min(t1, t2);
    }
    
    /**
     * Optimized ray-AABB intersection test for culling.
     * Tests if a ray intersects the axis-aligned bounding box of a prism.
     * 
     * @param ray The ray
     * @param prism The prism
     * @return true if ray intersects the prism's AABB
     */
    public static boolean intersectRayAABB(Ray3D ray, PrismKey prism) {
        float[] bounds = PrismGeometry.computeBoundingBox(prism);
        
        float tmin = (bounds[0] - ray.origin().x) / ray.direction().x;
        float tmax = (bounds[3] - ray.origin().x) / ray.direction().x;
        
        if (tmin > tmax) {
            float temp = tmin;
            tmin = tmax;
            tmax = temp;
        }
        
        float tymin = (bounds[1] - ray.origin().y) / ray.direction().y;
        float tymax = (bounds[4] - ray.origin().y) / ray.direction().y;
        
        if (tymin > tymax) {
            float temp = tymin;
            tymin = tymax;
            tymax = temp;
        }
        
        if ((tmin > tymax) || (tymin > tmax)) {
            return false;
        }
        
        if (tymin > tmin) tmin = tymin;
        if (tymax < tmax) tmax = tymax;
        
        float tzmin = (bounds[2] - ray.origin().z) / ray.direction().z;
        float tzmax = (bounds[5] - ray.origin().z) / ray.direction().z;
        
        if (tzmin > tzmax) {
            float temp = tzmin;
            tzmin = tzmax;
            tzmax = temp;
        }
        
        if ((tmin > tzmax) || (tzmin > tmax)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Find the entry and exit points of a ray through a prism.
     * Useful for volume rendering or transparency calculations.
     * 
     * @param ray The ray
     * @param prism The prism
     * @return Array of t values [tEntry, tExit], or null if no intersection
     */
    public static float[] findEntryExitPoints(Ray3D ray, PrismKey prism) {
        IntersectionResult result = intersectRayPrism(ray, prism);
        if (result.hit) {
            return new float[]{result.tNear, result.tFar};
        }
        return null;
    }
    
    /**
     * Convert prism vertices to float array format.
     */
    private static float[][] getVerticesAsFloatArray(PrismKey prism) {
        var vertices = PrismGeometry.getVertices(prism);
        float[][] result = new float[vertices.size()][];
        for (int i = 0; i < vertices.size(); i++) {
            var vertex = vertices.get(i);
            result[i] = new float[]{vertex.x, vertex.y, vertex.z};
        }
        return result;
    }
}
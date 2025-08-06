package com.hellblazer.luciferase.render.voxel.pipeline;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Triangle-box intersection using Separating Axis Theorem (SAT).
 * Based on NVIDIA ESVO implementation for accurate voxelization.
 */
public class TriangleBoxIntersection {
    
    private static final float EPSILON = 1e-6f;
    
    /**
     * Tests if a triangle intersects with an axis-aligned bounding box.
     * Uses the Separating Axis Theorem with 13 potential separating axes.
     *
     * @param v0 First vertex of triangle
     * @param v1 Second vertex of triangle
     * @param v2 Third vertex of triangle
     * @param boxCenter Center of the axis-aligned box
     * @param boxHalfSize Half dimensions of the box
     * @return true if triangle and box intersect
     */
    public static boolean intersects(Point3f v0, Point3f v1, Point3f v2,
                                    Point3f boxCenter, Vector3f boxHalfSize) {
        // Translate triangle to box coordinate system
        var tv0 = new Vector3f(v0.x - boxCenter.x, v0.y - boxCenter.y, v0.z - boxCenter.z);
        var tv1 = new Vector3f(v1.x - boxCenter.x, v1.y - boxCenter.y, v1.z - boxCenter.z);
        var tv2 = new Vector3f(v2.x - boxCenter.x, v2.y - boxCenter.y, v2.z - boxCenter.z);
        
        // Test 3 box face normals (AABB axes)
        if (!testBoxAxis(tv0, tv1, tv2, boxHalfSize)) {
            return false;
        }
        
        // Compute triangle edges
        var e0 = new Vector3f();
        e0.sub(tv1, tv0);
        var e1 = new Vector3f();
        e1.sub(tv2, tv1);
        var e2 = new Vector3f();
        e2.sub(tv0, tv2);
        
        // Test 9 edge cross products
        if (!testEdgeCrossProducts(tv0, tv1, tv2, e0, e1, e2, boxHalfSize)) {
            return false;
        }
        
        // Test triangle face normal
        var normal = new Vector3f();
        normal.cross(e0, e1);
        return testTrianglePlane(tv0, normal, boxHalfSize);
    }
    
    /**
     * Tests separation along box face normals (x, y, z axes).
     */
    private static boolean testBoxAxis(Vector3f v0, Vector3f v1, Vector3f v2,
                                      Vector3f boxHalfSize) {
        // X-axis
        float min = Math.min(Math.min(v0.x, v1.x), v2.x);
        float max = Math.max(Math.max(v0.x, v1.x), v2.x);
        if (min > boxHalfSize.x || max < -boxHalfSize.x) {
            return false;
        }
        
        // Y-axis
        min = Math.min(Math.min(v0.y, v1.y), v2.y);
        max = Math.max(Math.max(v0.y, v1.y), v2.y);
        if (min > boxHalfSize.y || max < -boxHalfSize.y) {
            return false;
        }
        
        // Z-axis
        min = Math.min(Math.min(v0.z, v1.z), v2.z);
        max = Math.max(Math.max(v0.z, v1.z), v2.z);
        if (min > boxHalfSize.z || max < -boxHalfSize.z) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Tests separation along edge cross products.
     */
    private static boolean testEdgeCrossProducts(Vector3f v0, Vector3f v1, Vector3f v2,
                                                Vector3f e0, Vector3f e1, Vector3f e2,
                                                Vector3f boxHalfSize) {
        // Edge0 x Box axes
        if (!testSeparatingAxis(v0, v1, v2, new Vector3f(0, -e0.z, e0.y), boxHalfSize)) return false;
        if (!testSeparatingAxis(v0, v1, v2, new Vector3f(e0.z, 0, -e0.x), boxHalfSize)) return false;
        if (!testSeparatingAxis(v0, v1, v2, new Vector3f(-e0.y, e0.x, 0), boxHalfSize)) return false;
        
        // Edge1 x Box axes
        if (!testSeparatingAxis(v0, v1, v2, new Vector3f(0, -e1.z, e1.y), boxHalfSize)) return false;
        if (!testSeparatingAxis(v0, v1, v2, new Vector3f(e1.z, 0, -e1.x), boxHalfSize)) return false;
        if (!testSeparatingAxis(v0, v1, v2, new Vector3f(-e1.y, e1.x, 0), boxHalfSize)) return false;
        
        // Edge2 x Box axes
        if (!testSeparatingAxis(v0, v1, v2, new Vector3f(0, -e2.z, e2.y), boxHalfSize)) return false;
        if (!testSeparatingAxis(v0, v1, v2, new Vector3f(e2.z, 0, -e2.x), boxHalfSize)) return false;
        if (!testSeparatingAxis(v0, v1, v2, new Vector3f(-e2.y, e2.x, 0), boxHalfSize)) return false;
        
        return true;
    }
    
    /**
     * Tests if there's separation along a given axis.
     */
    private static boolean testSeparatingAxis(Vector3f v0, Vector3f v1, Vector3f v2,
                                             Vector3f axis, Vector3f boxHalfSize) {
        // Skip degenerate axes
        if (axis.lengthSquared() < EPSILON * EPSILON) {
            return true;
        }
        
        // Project triangle vertices onto axis
        float p0 = v0.dot(axis);
        float p1 = v1.dot(axis);
        float p2 = v2.dot(axis);
        
        float triMin = Math.min(Math.min(p0, p1), p2);
        float triMax = Math.max(Math.max(p0, p1), p2);
        
        // Project box onto axis
        float boxRadius = boxHalfSize.x * Math.abs(axis.x) +
                         boxHalfSize.y * Math.abs(axis.y) +
                         boxHalfSize.z * Math.abs(axis.z);
        
        // Check for separation
        return !(triMin > boxRadius || triMax < -boxRadius);
    }
    
    /**
     * Tests separation along triangle plane normal.
     */
    private static boolean testTrianglePlane(Vector3f v0, Vector3f normal,
                                            Vector3f boxHalfSize) {
        // Skip degenerate triangles
        if (normal.lengthSquared() < EPSILON * EPSILON) {
            return true;
        }
        
        // Distance from origin to triangle plane
        float d = Math.abs(v0.dot(normal));
        
        // Project box onto normal
        float boxRadius = boxHalfSize.x * Math.abs(normal.x) +
                         boxHalfSize.y * Math.abs(normal.y) +
                         boxHalfSize.z * Math.abs(normal.z);
        
        return d <= boxRadius;
    }
    
    /**
     * Computes partial coverage of a voxel by a triangle.
     * Returns a value between 0.0 (no coverage) and 1.0 (full coverage).
     */
    public static float computeCoverage(Point3f v0, Point3f v1, Point3f v2,
                                       Point3f boxCenter, Vector3f boxHalfSize,
                                       int samplesPerDimension) {
        if (!intersects(v0, v1, v2, boxCenter, boxHalfSize)) {
            return 0.0f;
        }
        
        // Simplified coverage using regular sampling
        int hits = 0;
        int total = samplesPerDimension * samplesPerDimension * samplesPerDimension;
        
        float step = 2.0f * boxHalfSize.x / samplesPerDimension;
        float startX = boxCenter.x - boxHalfSize.x + step * 0.5f;
        float startY = boxCenter.y - boxHalfSize.y + step * 0.5f;
        float startZ = boxCenter.z - boxHalfSize.z + step * 0.5f;
        
        for (int i = 0; i < samplesPerDimension; i++) {
            for (int j = 0; j < samplesPerDimension; j++) {
                for (int k = 0; k < samplesPerDimension; k++) {
                    var samplePoint = new Point3f(
                        startX + i * step,
                        startY + j * step,
                        startZ + k * step
                    );
                    
                    if (isPointInTriangle(samplePoint, v0, v1, v2)) {
                        hits++;
                    }
                }
            }
        }
        
        return (float) hits / total;
    }
    
    /**
     * Tests if a point is inside a triangle using barycentric coordinates.
     */
    private static boolean isPointInTriangle(Point3f p, Point3f v0, Point3f v1, Point3f v2) {
        var v0v1 = new Vector3f();
        v0v1.sub(v1, v0);
        var v0v2 = new Vector3f();
        v0v2.sub(v2, v0);
        var v0p = new Vector3f();
        v0p.sub(p, v0);
        
        float dot00 = v0v2.dot(v0v2);
        float dot01 = v0v2.dot(v0v1);
        float dot02 = v0v2.dot(v0p);
        float dot11 = v0v1.dot(v0v1);
        float dot12 = v0v1.dot(v0p);
        
        float invDenom = 1.0f / (dot00 * dot11 - dot01 * dot01);
        float u = (dot11 * dot02 - dot01 * dot12) * invDenom;
        float v = (dot00 * dot12 - dot01 * dot02) * invDenom;
        
        return (u >= 0) && (v >= 0) && (u + v <= 1);
    }
}
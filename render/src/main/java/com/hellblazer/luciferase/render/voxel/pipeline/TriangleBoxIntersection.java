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
     * Computes partial coverage of a voxel by a triangle using ESVO-style triangle clipping.
     * Returns a value between 0.0 (no coverage) and 1.0 (full coverage).
     * This method is more accurate than sampling and follows NVIDIA ESVO implementation.
     */
    public static float computeCoverage(Point3f v0, Point3f v1, Point3f v2,
                                       Point3f boxCenter, Vector3f boxHalfSize) {
        if (!intersects(v0, v1, v2, boxCenter, boxHalfSize)) {
            return 0.0f;
        }
        
        // Use ESVO-style triangle clipping for precise coverage
        var clippedVertices = new java.util.ArrayList<BarycentricCoord>();
        int numClipped = clipTriangleToBox(clippedVertices, v0, v1, v2, boxCenter, boxHalfSize);
        
        if (numClipped == 0) {
            return 0.0f;
        }
        
        // Compute area of clipped polygon in world space
        return computeWorldSpaceCoverage(clippedVertices, v0, v1, v2, boxHalfSize);
    }
    
    /**
     * Clips a triangle against a box and returns vertices in barycentric coordinates.
     * Based on ESVO triangle clipping algorithm.
     * 
     * @param barycentricOut Output list of clipped vertices in barycentric coordinates
     * @param v0, v1, v2 Triangle vertices in world space
     * @param boxCenter Center of the voxel box
     * @param boxHalfSize Half-dimensions of the voxel box
     * @return Number of vertices in the clipped polygon
     */
    public static int clipTriangleToBox(java.util.List<BarycentricCoord> barycentricOut,
                                       Point3f v0, Point3f v1, Point3f v2,
                                       Point3f boxCenter, Vector3f boxHalfSize) {
        // Start with full triangle in barycentric space
        var vertices = new java.util.ArrayList<BarycentricCoord>();
        vertices.add(new BarycentricCoord(1.0f, 0.0f, 0.0f)); // v0
        vertices.add(new BarycentricCoord(0.0f, 1.0f, 0.0f)); // v1
        vertices.add(new BarycentricCoord(0.0f, 0.0f, 1.0f)); // v2
        
        // Define triangle edges for world space conversion
        var edge1 = new Vector3f();
        edge1.sub(v1, v0);
        var edge2 = new Vector3f();
        edge2.sub(v2, v0);
        
        // Clip against each box plane (6 planes total)
        for (int axis = 0; axis < 3; axis++) {
            // Positive plane
            float planePos = getBoxFace(boxCenter, boxHalfSize, axis, true);
            vertices = clipAgainstPlane(vertices, v0, edge1, edge2, axis, planePos, true);
            if (vertices.isEmpty()) return 0;
            
            // Negative plane  
            float planeNeg = getBoxFace(boxCenter, boxHalfSize, axis, false);
            vertices = clipAgainstPlane(vertices, v0, edge1, edge2, axis, planeNeg, false);
            if (vertices.isEmpty()) return 0;
        }
        
        // Copy results
        barycentricOut.clear();
        barycentricOut.addAll(vertices);
        return vertices.size();
    }
    
    /**
     * Clips a polygon in barycentric space against a plane.
     */
    private static java.util.ArrayList<BarycentricCoord> clipAgainstPlane(
            java.util.List<BarycentricCoord> inputVertices,
            Point3f triangleV0, Vector3f triangleEdge1, Vector3f triangleEdge2,
            int axis, float planePos, boolean isPositiveSide) {
        
        var clippedVertices = new java.util.ArrayList<BarycentricCoord>();
        
        if (inputVertices.isEmpty()) {
            return clippedVertices;
        }
        
        BarycentricCoord prevVertex = inputVertices.get(inputVertices.size() - 1);
        float prevDist = getSignedDistance(prevVertex, triangleV0, triangleEdge1, triangleEdge2, axis, planePos);
        // For positive side plane: inside means on negative side (distance <= 0)
        // For negative side plane: inside means on positive side (distance >= 0)
        boolean prevInside = isPositiveSide ? prevDist <= 0 : prevDist >= 0;
        
        for (var currentVertex : inputVertices) {
            float currentDist = getSignedDistance(currentVertex, triangleV0, triangleEdge1, triangleEdge2, axis, planePos);
            // For positive side plane: inside means on negative side (distance <= 0)
            // For negative side plane: inside means on positive side (distance >= 0)
            boolean currentInside = isPositiveSide ? currentDist <= 0 : currentDist >= 0;
            
            if (currentInside) {
                if (!prevInside) {
                    // Entering the plane - add intersection point
                    var intersection = computePlaneIntersection(prevVertex, currentVertex, prevDist, currentDist);
                    if (intersection != null) {
                        clippedVertices.add(intersection);
                    }
                }
                // Add the current vertex
                clippedVertices.add(currentVertex);
            } else if (prevInside) {
                // Exiting the plane - add intersection point
                var intersection = computePlaneIntersection(prevVertex, currentVertex, prevDist, currentDist);
                if (intersection != null) {
                    clippedVertices.add(intersection);
                }
            }
            
            prevVertex = currentVertex;
            prevDist = currentDist;
            prevInside = currentInside;
        }
        
        return clippedVertices;
    }
    
    /**
     * Gets the position of a box face along a given axis.
     */
    private static float getBoxFace(Point3f boxCenter, Vector3f boxHalfSize, int axis, boolean positive) {
        float center = (axis == 0) ? boxCenter.x : (axis == 1) ? boxCenter.y : boxCenter.z;
        float halfSize = (axis == 0) ? boxHalfSize.x : (axis == 1) ? boxHalfSize.y : boxHalfSize.z;
        return center + (positive ? halfSize : -halfSize);
    }
    
    /**
     * Computes signed distance from a barycentric point to a plane.
     */
    private static float getSignedDistance(BarycentricCoord baryCoord, 
                                          Point3f triangleV0, Vector3f triangleEdge1, Vector3f triangleEdge2,
                                          int axis, float planePos) {
        // Convert barycentric to world coordinates
        Point3f worldPos = barycentricToWorld(baryCoord, triangleV0, triangleEdge1, triangleEdge2);
        
        // Get coordinate along the specified axis
        float coord = (axis == 0) ? worldPos.x : (axis == 1) ? worldPos.y : worldPos.z;
        
        return coord - planePos;
    }
    
    /**
     * Converts barycentric coordinates to world space.
     */
    private static Point3f barycentricToWorld(BarycentricCoord baryCoord,
                                             Point3f v0, Vector3f edge1, Vector3f edge2) {
        // world = v0 + u*edge1 + v*edge2 where (u,v,w) are barycentric coords
        var result = new Point3f(v0);
        
        var temp1 = new Vector3f(edge1);
        temp1.scale(baryCoord.v); // Note: v1 coefficient maps to edge1
        result.add(temp1);
        
        var temp2 = new Vector3f(edge2);
        temp2.scale(baryCoord.w); // Note: v2 coefficient maps to edge2
        result.add(temp2);
        
        return result;
    }
    
    /**
     * Computes intersection point between two vertices and a plane.
     */
    private static BarycentricCoord computePlaneIntersection(BarycentricCoord v1, BarycentricCoord v2,
                                                           float dist1, float dist2) {
        if (Math.abs(dist1 - dist2) < EPSILON) {
            return null; // Parallel to plane
        }
        
        float t = -dist1 / (dist2 - dist1);
        t = Math.max(0.0f, Math.min(1.0f, t)); // Clamp to [0,1]
        
        return new BarycentricCoord(
            v1.u + t * (v2.u - v1.u),
            v1.v + t * (v2.v - v1.v), 
            v1.w + t * (v2.w - v1.w)
        );
    }
    
    /**
     * Computes the area of a polygon in barycentric space.
     * Since the full triangle has area 0.5 in barycentric space,
     * we normalize the result accordingly.
     */
    private static float computeBarycentricArea(java.util.List<BarycentricCoord> vertices) {
        if (vertices.size() < 3) {
            return 0.0f;
        }
        
        // Use shoelace formula in barycentric coordinates
        // We'll work in the (u,v) plane since w = 1 - u - v
        float area = 0.0f;
        
        for (int i = 0; i < vertices.size(); i++) {
            var v1 = vertices.get(i);
            var v2 = vertices.get((i + 1) % vertices.size());
            
            area += v1.u * v2.v - v2.u * v1.v;
        }
        
        area = Math.abs(area) * 0.5f;
        
        // Normalize: full triangle in barycentric space has area 0.5
        // So the coverage is the computed area / 0.5
        return Math.min(1.0f, area / 0.5f);
    }
    
    /**
     * Computes coverage by converting clipped polygon to world space and computing area.
     * Returns fraction of box face covered by the clipped polygon.
     */
    private static float computeWorldSpaceCoverage(java.util.List<BarycentricCoord> clippedVertices,
                                                  Point3f v0, Point3f v1, Point3f v2,
                                                  Vector3f boxHalfSize) {
        if (clippedVertices.size() < 3) {
            return 0.0f;
        }
        
        // Convert barycentric vertices to world space
        var worldVertices = new java.util.ArrayList<Point3f>();
        var edge1 = new Vector3f();
        edge1.sub(v1, v0);
        var edge2 = new Vector3f();
        edge2.sub(v2, v0);
        
        for (var baryCoord : clippedVertices) {
            worldVertices.add(barycentricToWorld(baryCoord, v0, edge1, edge2));
        }
        
        // Compute triangle normal to determine dominant axis
        var normal = new Vector3f();
        normal.cross(edge1, edge2);
        normal.normalize();
        
        // Find dominant axis (largest component of normal)
        float absX = Math.abs(normal.x);
        float absY = Math.abs(normal.y);
        float absZ = Math.abs(normal.z);
        
        int dominantAxis;
        float boxFaceArea;
        
        if (absX >= absY && absX >= absZ) {
            // Project onto YZ plane
            dominantAxis = 0;
            boxFaceArea = 4.0f * boxHalfSize.y * boxHalfSize.z;
        } else if (absY >= absX && absY >= absZ) {
            // Project onto XZ plane
            dominantAxis = 1;
            boxFaceArea = 4.0f * boxHalfSize.x * boxHalfSize.z;
        } else {
            // Project onto XY plane
            dominantAxis = 2;
            boxFaceArea = 4.0f * boxHalfSize.x * boxHalfSize.y;
        }
        
        // Compute area of projected polygon using shoelace formula
        float polygonArea = 0.0f;
        
        for (int i = 0; i < worldVertices.size(); i++) {
            var p1 = worldVertices.get(i);
            var p2 = worldVertices.get((i + 1) % worldVertices.size());
            
            if (dominantAxis == 0) {
                // YZ plane
                polygonArea += p1.y * p2.z - p2.y * p1.z;
            } else if (dominantAxis == 1) {
                // XZ plane
                polygonArea += p1.x * p2.z - p2.x * p1.z;
            } else {
                // XY plane
                polygonArea += p1.x * p2.y - p2.x * p1.y;
            }
        }
        
        polygonArea = Math.abs(polygonArea) * 0.5f;
        
        // Return coverage as fraction of box face area
        return Math.min(1.0f, polygonArea / boxFaceArea);
    }
    
    /**
     * Represents barycentric coordinates (u,v,w) where u+v+w=1.
     * u corresponds to vertex v0, v to vertex v1, w to vertex v2.
     */
    public static class BarycentricCoord {
        public float u, v, w;
        
        public BarycentricCoord(float u, float v, float w) {
            this.u = u;
            this.v = v;
            this.w = w;
        }
    }
    
    /**
     * Legacy coverage method using sampling - kept for compatibility.
     * For new code, use the parameterless version that uses triangle clipping.
     */
    public static float computeCoverageSampled(Point3f v0, Point3f v1, Point3f v2,
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
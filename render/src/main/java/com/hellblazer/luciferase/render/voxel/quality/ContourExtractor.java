package com.hellblazer.luciferase.render.voxel.quality;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Extracts and encodes contour information from triangle meshes within voxels.
 * Based on NVIDIA ESVO contour extraction for high-quality voxelization.
 * 
 * Contours represent dominant surfaces within voxels using a compact 32-bit encoding
 * that captures surface normal, position, and thickness information.
 */
public class ContourExtractor {
    
    private static final float EPSILON = 1e-6f;
    
    /**
     * Represents a contour - a dominant surface approximation within a voxel.
     */
    public static class Contour {
        public final Vector3f normal;      // Surface normal (normalized)
        public final float position;        // Distance from voxel center along normal
        public final float thickness;       // Contour thickness for filtering
        public final int encodedValue;      // 32-bit packed representation
        
        public Contour(Vector3f normal, float position, float thickness, int encodedValue) {
            this.normal = new Vector3f(normal);
            this.position = position;
            this.thickness = thickness;
            this.encodedValue = encodedValue;
        }
    }
    
    /**
     * Triangle data for contour extraction.
     */
    public static class Triangle {
        public final Point3f v0, v1, v2;
        public final Vector3f normal;
        
        public Triangle(Point3f v0, Point3f v1, Point3f v2) {
            this.v0 = new Point3f(v0);
            this.v1 = new Point3f(v1);
            this.v2 = new Point3f(v2);
            
            // Compute triangle normal
            var edge1 = new Vector3f();
            edge1.sub(v1, v0);
            var edge2 = new Vector3f();
            edge2.sub(v2, v0);
            
            this.normal = new Vector3f();
            this.normal.cross(edge1, edge2);
            if (this.normal.lengthSquared() > EPSILON * EPSILON) {
                this.normal.normalize();
            }
        }
    }
    
    /**
     * AABB (Axis-Aligned Bounding Box) representing a voxel.
     */
    public static class AABB {
        public final Point3f center;
        public final Vector3f halfSize;
        
        public AABB(Point3f center, Vector3f halfSize) {
            this.center = new Point3f(center);
            this.halfSize = new Vector3f(halfSize);
        }
        
        public Point3f getMin() {
            return new Point3f(
                center.x - halfSize.x,
                center.y - halfSize.y,
                center.z - halfSize.z
            );
        }
        
        public Point3f getMax() {
            return new Point3f(
                center.x + halfSize.x,
                center.y + halfSize.y,
                center.z + halfSize.z
            );
        }
    }
    
    /**
     * Extracts a contour from triangles within a voxel.
     * Uses convex hull construction and plane fitting to find the dominant surface.
     *
     * @param triangles List of triangles intersecting the voxel
     * @param voxel The voxel bounds
     * @return Extracted contour or null if no valid contour found
     */
    public Contour extractContour(List<Triangle> triangles, AABB voxel) {
        if (triangles == null || triangles.isEmpty()) {
            return null;
        }
        
        // Step 1: Build convex hull from triangle planes
        var planes = buildConvexHullPlanes(triangles, voxel);
        
        // Step 2: Find dominant plane through regression
        var dominantPlane = fitDominantPlane(planes, triangles, voxel);
        if (dominantPlane == null) {
            // Fallback to first triangle's normal if no dominant plane found
            for (var triangle : triangles) {
                if (triangle.normal.lengthSquared() > EPSILON * EPSILON) {
                    dominantPlane = new Vector3f(triangle.normal);
                    break;
                }
            }
            if (dominantPlane == null) {
                // Last resort - use up vector
                dominantPlane = new Vector3f(0, 0, 1);
            }
        }
        
        // Step 3: Calculate position and thickness
        float position = calculateContourPosition(dominantPlane, voxel);
        float thickness = calculateContourThickness(triangles, dominantPlane, voxel);
        
        // Step 4: Encode as 32-bit value
        int encoded = encodeContour(dominantPlane, position, thickness);
        
        return new Contour(dominantPlane, position, thickness, encoded);
    }
    
    /**
     * Builds a set of planes representing the convex hull of triangles.
     * These planes approximate the surface within the voxel.
     */
    private List<Plane> buildConvexHullPlanes(List<Triangle> triangles, AABB voxel) {
        var planes = new ArrayList<Plane>();
        
        // Collect all triangle vertices that are inside or near the voxel
        var points = new ArrayList<Point3f>();
        for (var triangle : triangles) {
            if (isPointNearVoxel(triangle.v0, voxel)) points.add(triangle.v0);
            if (isPointNearVoxel(triangle.v1, voxel)) points.add(triangle.v1);
            if (isPointNearVoxel(triangle.v2, voxel)) points.add(triangle.v2);
        }
        
        // Add intersection points between triangle edges and voxel faces
        for (var triangle : triangles) {
            addEdgeVoxelIntersections(points, triangle.v0, triangle.v1, voxel);
            addEdgeVoxelIntersections(points, triangle.v1, triangle.v2, voxel);
            addEdgeVoxelIntersections(points, triangle.v2, triangle.v0, voxel);
        }
        
        if (points.size() < 4) {
            // Not enough points for a convex hull
            // Fall back to using triangle normals directly
            for (var triangle : triangles) {
                if (triangle.normal.lengthSquared() > EPSILON * EPSILON) {
                    planes.add(new Plane(triangle.normal, triangle.v0));
                }
            }
            // Ensure we have at least one plane
            if (planes.isEmpty() && !triangles.isEmpty()) {
                // Create a default plane from the first triangle's centroid
                var t = triangles.get(0);
                var center = new Point3f(
                    (t.v0.x + t.v1.x + t.v2.x) / 3.0f,
                    (t.v0.y + t.v1.y + t.v2.y) / 3.0f,
                    (t.v0.z + t.v1.z + t.v2.z) / 3.0f
                );
                planes.add(new Plane(new Vector3f(0, 0, 1), center));
            }
            return planes;
        }
        
        // Build convex hull using QuickHull algorithm
        var hull = computeConvexHull(points);
        
        // Extract planes from convex hull faces
        for (var face : hull) {
            var edge1 = new Vector3f();
            edge1.sub(face.v1, face.v0);
            var edge2 = new Vector3f();
            edge2.sub(face.v2, face.v0);
            
            var normal = new Vector3f();
            normal.cross(edge1, edge2);
            if (normal.lengthSquared() > EPSILON * EPSILON) {
                normal.normalize();
                planes.add(new Plane(normal, face.v0));
            }
        }
        
        return planes;
    }
    
    /**
     * Fits a dominant plane through regression analysis.
     * Finds the plane that best represents all surfaces in the voxel.
     */
    private Vector3f fitDominantPlane(List<Plane> planes, List<Triangle> triangles, AABB voxel) {
        if (planes.isEmpty() && triangles.isEmpty()) {
            return null;
        }
        
        if (planes.isEmpty()) {
            // Use triangle normals directly
            var weightedNormal = new Vector3f(0, 0, 0);
            float totalWeight = 0;
            
            for (var triangle : triangles) {
                if (triangle.normal.lengthSquared() > EPSILON * EPSILON) {
                    float area = computeTriangleArea(triangle);
                    var scaledNormal = new Vector3f(triangle.normal);
                    scaledNormal.scale(area);
                    weightedNormal.add(scaledNormal);
                    totalWeight += area;
                }
            }
            
            if (totalWeight > EPSILON && weightedNormal.lengthSquared() > EPSILON * EPSILON) {
                weightedNormal.normalize();
                return weightedNormal;
            }
            return null;
        }
        
        // Weight planes by the area of triangles they represent
        var weightedNormal = new Vector3f(0, 0, 0);
        float totalWeight = 0;
        
        for (var triangle : triangles) {
            float area = computeTriangleArea(triangle);
            float coverage = computeVoxelCoverage(triangle, voxel);
            float weight = area * coverage;
            
            var scaledNormal = new Vector3f(triangle.normal);
            scaledNormal.scale(weight);
            weightedNormal.add(scaledNormal);
            totalWeight += weight;
        }
        
        if (totalWeight < EPSILON) {
            // Fall back to simple average
            for (var plane : planes) {
                weightedNormal.add(plane.normal);
            }
            totalWeight = planes.size();
        }
        
        if (totalWeight > EPSILON) {
            weightedNormal.scale(1.0f / totalWeight);
            if (weightedNormal.lengthSquared() > EPSILON * EPSILON) {
                weightedNormal.normalize();
                return weightedNormal;
            }
        }
        
        // Default to first plane if regression fails
        return planes.get(0).normal;
    }
    
    /**
     * Calculates the position of the contour relative to voxel center.
     */
    private float calculateContourPosition(Vector3f normal, AABB voxel) {
        // Project voxel center onto the plane normal
        // This gives us the signed distance from center to plane
        return 0.0f; // Simplified - assumes plane passes through center
    }
    
    /**
     * Calculates the thickness of the contour for filtering purposes.
     */
    private float calculateContourThickness(List<Triangle> triangles, Vector3f normal, AABB voxel) {
        // Measure spread of triangles along the normal direction
        float minProjection = Float.MAX_VALUE;
        float maxProjection = Float.MIN_VALUE;
        
        for (var triangle : triangles) {
            float proj0 = projectPointOntoNormal(triangle.v0, voxel.center, normal);
            float proj1 = projectPointOntoNormal(triangle.v1, voxel.center, normal);
            float proj2 = projectPointOntoNormal(triangle.v2, voxel.center, normal);
            
            minProjection = Math.min(minProjection, Math.min(proj0, Math.min(proj1, proj2)));
            maxProjection = Math.max(maxProjection, Math.max(proj0, Math.max(proj1, proj2)));
        }
        
        float thickness = maxProjection - minProjection;
        
        // Normalize to voxel size
        float voxelDiagonal = (float) Math.sqrt(
            4 * (voxel.halfSize.x * voxel.halfSize.x + voxel.halfSize.y * voxel.halfSize.y + voxel.halfSize.z * voxel.halfSize.z)
        );
        
        return Math.min(1.0f, thickness / voxelDiagonal);
    }
    
    /**
     * Encodes contour information into a 32-bit integer.
     * Format (32 bits total):
     * - Bits 0-9:   Normal X component (10 bits, signed)
     * - Bits 10-19: Normal Y component (10 bits, signed)  
     * - Bits 20-29: Normal Z component (10 bits, signed)
     * - Bits 30-31: Thickness (2 bits, quantized)
     *
     * Note: Position is stored separately in higher precision if needed
     */
    public int encodeContour(Vector3f normal, float position, float thickness) {
        // Ensure normal is normalized
        var n = new Vector3f(normal);
        n.normalize();
        
        // Quantize normal components to 10 bits each (range -511 to 511)
        int nx = quantizeNormalComponent(n.x, 10);
        int ny = quantizeNormalComponent(n.y, 10);
        int nz = quantizeNormalComponent(n.z, 10);
        
        // Quantize thickness to 2 bits (4 levels)
        int thicknessBits = (int)(thickness * 3.999f) & 0x3;
        
        // Pack into 32 bits
        int encoded = 0;
        encoded |= (nx & 0x3FF);           // Bits 0-9
        encoded |= (ny & 0x3FF) << 10;     // Bits 10-19
        encoded |= (nz & 0x3FF) << 20;     // Bits 20-29
        encoded |= thicknessBits << 30;    // Bits 30-31
        
        return encoded;
    }
    
    /**
     * Decodes a 32-bit contour value back to its components.
     */
    public static Contour decodeContour(int encoded) {
        // Extract normal components
        int nx = (encoded & 0x3FF);
        if ((nx & 0x200) != 0) nx |= 0xFFFFFC00; // Sign extend
        
        int ny = ((encoded >> 10) & 0x3FF);
        if ((ny & 0x200) != 0) ny |= 0xFFFFFC00; // Sign extend
        
        int nz = ((encoded >> 20) & 0x3FF);
        if ((nz & 0x200) != 0) nz |= 0xFFFFFC00; // Sign extend
        
        // Dequantize normal
        var normal = new Vector3f(
            dequantizeNormalComponent(nx, 10),
            dequantizeNormalComponent(ny, 10),
            dequantizeNormalComponent(nz, 10)
        );
        normal.normalize();
        
        // Extract thickness
        int thicknessBits = (encoded >> 30) & 0x3;
        float thickness = thicknessBits / 3.0f;
        
        return new Contour(normal, 0.0f, thickness, encoded);
    }
    
    /**
     * Evaluates the error between a contour and the original triangles.
     * Lower error means better approximation.
     */
    public float evaluateContourError(Contour contour, List<Triangle> triangles) {
        float totalError = 0.0f;
        float totalWeight = 0.0f;
        
        for (var triangle : triangles) {
            // Compute angle between contour normal and triangle normal
            float dotProduct = contour.normal.dot(triangle.normal);
            float angleError = 1.0f - Math.abs(dotProduct);
            
            // Weight by triangle area
            float area = computeTriangleArea(triangle);
            totalError += angleError * area;
            totalWeight += area;
        }
        
        return totalWeight > EPSILON ? totalError / totalWeight : 1.0f;
    }
    
    // Helper methods
    
    private int quantizeNormalComponent(float value, int bits) {
        int maxValue = (1 << (bits - 1)) - 1;
        return Math.max(-maxValue, Math.min(maxValue, 
            (int)(value * maxValue)));
    }
    
    private static float dequantizeNormalComponent(int value, int bits) {
        int maxValue = (1 << (bits - 1)) - 1;
        return (float)value / maxValue;
    }
    
    private boolean isPointNearVoxel(Point3f point, AABB voxel) {
        float margin = 0.01f; // Small margin for numerical stability
        var min = voxel.getMin();
        var max = voxel.getMax();
        
        return point.x >= min.x - margin && point.x <= max.x + margin &&
               point.y >= min.y - margin && point.y <= max.y + margin &&
               point.z >= min.z - margin && point.z <= max.z + margin;
    }
    
    private void addEdgeVoxelIntersections(List<Point3f> points, 
                                          Point3f v0, Point3f v1, AABB voxel) {
        var min = voxel.getMin();
        var max = voxel.getMax();
        
        // Check intersection with each voxel face
        var intersections = new ArrayList<Point3f>();
        
        // X faces
        addPlaneIntersection(intersections, v0, v1, 0, min.x);
        addPlaneIntersection(intersections, v0, v1, 0, max.x);
        
        // Y faces
        addPlaneIntersection(intersections, v0, v1, 1, min.y);
        addPlaneIntersection(intersections, v0, v1, 1, max.y);
        
        // Z faces
        addPlaneIntersection(intersections, v0, v1, 2, min.z);
        addPlaneIntersection(intersections, v0, v1, 2, max.z);
        
        // Add valid intersections that are within voxel bounds
        for (var point : intersections) {
            if (isPointNearVoxel(point, voxel)) {
                points.add(point);
            }
        }
    }
    
    private void addPlaneIntersection(List<Point3f> intersections,
                                     Point3f v0, Point3f v1, 
                                     int axis, float planePos) {
        float p0 = (axis == 0) ? v0.x : (axis == 1) ? v0.y : v0.z;
        float p1 = (axis == 0) ? v1.x : (axis == 1) ? v1.y : v1.z;
        
        if (Math.abs(p1 - p0) < EPSILON) {
            return; // Edge parallel to plane
        }
        
        float t = (planePos - p0) / (p1 - p0);
        if (t >= 0.0f && t <= 1.0f) {
            var point = new Point3f();
            point.interpolate(v0, v1, t);
            intersections.add(point);
        }
    }
    
    private float computeTriangleArea(Triangle triangle) {
        var edge1 = new Vector3f();
        edge1.sub(triangle.v1, triangle.v0);
        var edge2 = new Vector3f();
        edge2.sub(triangle.v2, triangle.v0);
        
        var cross = new Vector3f();
        cross.cross(edge1, edge2);
        
        return 0.5f * cross.length();
    }
    
    private float computeVoxelCoverage(Triangle triangle, AABB voxel) {
        // Simplified coverage estimation
        // In production, use the accurate SAT-based coverage from TriangleBoxIntersection
        return 1.0f;
    }
    
    private float projectPointOntoNormal(Point3f point, Point3f origin, Vector3f normal) {
        var vec = new Vector3f();
        vec.sub(point, origin);
        return vec.dot(normal);
    }
    
    /**
     * Simplified convex hull computation using gift wrapping algorithm.
     * For production, consider using a more efficient QuickHull implementation.
     */
    private List<HullFace> computeConvexHull(List<Point3f> points) {
        var faces = new ArrayList<HullFace>();
        
        if (points.size() < 4) {
            return faces; // Need at least 4 points for 3D hull
        }
        
        // Find initial tetrahedron (simplified)
        var p0 = points.get(0);
        var p1 = findFarthestPoint(points, p0);
        if (p1 == null || p1.equals(p0)) {
            return faces;  // Points are degenerate
        }
        
        var p2 = findFarthestFromLine(points, p0, p1);
        if (p2 == null || p2.equals(p0) || p2.equals(p1)) {
            return faces;  // Points are collinear
        }
        
        var p3 = findFarthestFromPlane(points, p0, p1, p2);
        
        if (p3 == null) {
            // Points are coplanar
            return faces;
        }
        
        // Create initial tetrahedron faces
        faces.add(new HullFace(p0, p1, p2));
        faces.add(new HullFace(p0, p1, p3));
        faces.add(new HullFace(p0, p2, p3));
        faces.add(new HullFace(p1, p2, p3));
        
        // Expand hull to include all points (simplified)
        // In production, use proper QuickHull or incremental algorithm
        
        return faces;
    }
    
    private Point3f findFarthestPoint(List<Point3f> points, Point3f from) {
        if (points.isEmpty()) {
            return null;
        }
        
        Point3f farthest = points.get(0);
        float maxDist = from.distanceSquared(farthest);
        
        for (int i = 1; i < points.size(); i++) {
            var point = points.get(i);
            float dist = from.distanceSquared(point);
            if (dist > maxDist) {
                maxDist = dist;
                farthest = point;
            }
        }
        
        return farthest;
    }
    
    private Point3f findFarthestFromLine(List<Point3f> points, Point3f p0, Point3f p1) {
        Point3f farthest = null;
        float maxDist = 0;
        
        var lineDir = new Vector3f();
        lineDir.sub(p1, p0);
        lineDir.normalize();
        
        for (var point : points) {
            if (point.equals(p0) || point.equals(p1)) continue;
            
            var toPoint = new Vector3f();
            toPoint.sub(point, p0);
            
            float projection = toPoint.dot(lineDir);
            var projectedPoint = new Vector3f(lineDir);
            projectedPoint.scale(projection);
            
            var perpendicular = new Vector3f();
            perpendicular.sub(toPoint, projectedPoint);
            
            float dist = perpendicular.lengthSquared();
            if (dist > maxDist) {
                maxDist = dist;
                farthest = point;
            }
        }
        
        return farthest;
    }
    
    private Point3f findFarthestFromPlane(List<Point3f> points, 
                                         Point3f p0, Point3f p1, Point3f p2) {
        var edge1 = new Vector3f();
        edge1.sub(p1, p0);
        var edge2 = new Vector3f();
        edge2.sub(p2, p0);
        
        var normal = new Vector3f();
        normal.cross(edge1, edge2);
        
        if (normal.lengthSquared() < EPSILON * EPSILON) {
            return null; // Points are collinear
        }
        normal.normalize();
        
        Point3f farthest = null;
        float maxDist = 0;
        
        for (var point : points) {
            if (point.equals(p0) || point.equals(p1) || point.equals(p2)) continue;
            
            var toPoint = new Vector3f();
            toPoint.sub(point, p0);
            
            float dist = Math.abs(toPoint.dot(normal));
            if (dist > maxDist) {
                maxDist = dist;
                farthest = point;
            }
        }
        
        return maxDist > EPSILON ? farthest : null;
    }
    
    /**
     * Represents a plane in 3D space.
     */
    private static class Plane {
        public final Vector3f normal;
        public final float d; // Distance from origin
        
        public Plane(Vector3f normal, Point3f point) {
            this.normal = new Vector3f(normal);
            this.normal.normalize();
            this.d = this.normal.dot(new Vector3f(point));
        }
    }
    
    /**
     * Represents a triangular face of a convex hull.
     */
    private static class HullFace {
        public final Point3f v0, v1, v2;
        
        public HullFace(Point3f v0, Point3f v1, Point3f v2) {
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
        }
    }
}
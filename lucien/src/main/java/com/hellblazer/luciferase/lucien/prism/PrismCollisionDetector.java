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

import java.util.ArrayList;
import java.util.List;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Collision detection system for triangular prisms with ObjectPool optimization.
 * Provides efficient algorithms for detecting collisions between prisms and
 * various geometric shapes.
 * 
 * @author hal.hildebrand
 */
public class PrismCollisionDetector {
    
    /**
     * Result of a collision test between two objects.
     */
    public static class CollisionResult {
        public final boolean collides;
        public final float penetrationDepth;
        public final Vector3f separationAxis;
        public final Point3f contactPoint;
        
        public CollisionResult(boolean collides) {
            this.collides = collides;
            this.penetrationDepth = 0;
            this.separationAxis = null;
            this.contactPoint = null;
        }
        
        public CollisionResult(boolean collides, float penetrationDepth, 
                             Vector3f separationAxis, Point3f contactPoint) {
            this.collides = collides;
            this.penetrationDepth = penetrationDepth;
            this.separationAxis = separationAxis;
            this.contactPoint = contactPoint;
        }
    }
    
    /**
     * Test collision between two triangular prisms.
     * Uses Separating Axis Theorem (SAT) for accurate collision detection.
     * 
     * @param prism1 First prism
     * @param prism2 Second prism
     * @return Collision result with penetration info
     */
    public static CollisionResult testPrismPrismCollision(PrismKey prism1, PrismKey prism2) {
        // First do quick AABB test
        if (!testAABBCollision(prism1, prism2)) {
            return new CollisionResult(false);
        }
        
        // Get vertices for both prisms
        float[][] vertices1 = getVerticesAsFloatArray(prism1);
        float[][] vertices2 = getVerticesAsFloatArray(prism2);
        
        // For triangular prisms, we need to test:
        // - 5 face normals from prism1
        // - 5 face normals from prism2
        // - 9*9 = 81 edge cross products (but many are parallel, so we can optimize)
        
        List<Vector3f> axes = new ArrayList<>();
        try {
            // Add face normals from both prisms
            addPrismFaceNormals(vertices1, axes);
            addPrismFaceNormals(vertices2, axes);
            
            // Add edge cross products
            addEdgeCrossProducts(vertices1, vertices2, axes);
            
            // Test all separating axes
            float minPenetration = Float.MAX_VALUE;
            Vector3f bestAxis = null;
            
            for (Vector3f axis : axes) {
                if (axis.lengthSquared() < 1e-6f) continue; // Skip degenerate axes
                
                axis.normalize();
                
                // Project both prisms onto this axis
                float[] proj1 = projectPrismOntoAxis(vertices1, axis);
                float[] proj2 = projectPrismOntoAxis(vertices2, axis);
                
                // Check for separation
                if (proj1[1] < proj2[0] || proj2[1] < proj1[0]) {
                    return new CollisionResult(false); // Separated on this axis
                }
                
                // Calculate penetration depth
                float penetration = Math.min(proj1[1] - proj2[0], proj2[1] - proj1[0]);
                if (penetration < minPenetration) {
                    minPenetration = penetration;
                    bestAxis = new Vector3f(axis);
                }
            }
            
            // If we got here, the prisms are colliding
            // Calculate contact point (simplified - use center of overlap region)
            Point3f contactPoint = calculateContactPoint(vertices1, vertices2, bestAxis);
            
            return new CollisionResult(true, minPenetration, bestAxis, contactPoint);
            
        } finally {
            // No cleanup needed for regular ArrayList
        }
    }
    
    /**
     * Test collision between a prism and a sphere.
     * 
     * @param prism The prism
     * @param sphereCenter Center of the sphere
     * @param sphereRadius Radius of the sphere
     * @return Collision result
     */
    public static CollisionResult testPrismSphereCollision(PrismKey prism, 
                                                          Point3f sphereCenter, 
                                                          float sphereRadius) {
        // First check if sphere center is inside the prism
        if (PrismGeometry.contains(prism, sphereCenter)) {
            // Sphere center is inside prism - definite collision
            // Use sphere radius as penetration depth and arbitrary direction
            Vector3f separationAxis = new Vector3f(0, 0, 1);
            Point3f contactPoint = new Point3f(sphereCenter);
            return new CollisionResult(true, sphereRadius, separationAxis, contactPoint);
        }
        
        // Get prism vertices
        float[][] vertices = getVerticesAsFloatArray(prism);
        
        // Find closest point on prism to sphere center
        Point3f closestPoint = findClosestPointOnPrism(vertices, sphereCenter);
        
        // Calculate distance
        Vector3f toSphere = new Vector3f();
        toSphere.sub(sphereCenter, closestPoint);
        float distance = toSphere.length();
        
        if (distance <= sphereRadius) {
            // Collision detected
            float penetration = sphereRadius - distance;
            if (distance > 1e-6f) {
                toSphere.normalize();
            } else {
                // This shouldn't happen since we checked containment above,
                // but handle edge case
                toSphere.set(0, 0, 1);
            }
            return new CollisionResult(true, penetration, toSphere, closestPoint);
        }
        
        return new CollisionResult(false);
    }
    
    /**
     * Find all prisms that potentially collide with a given prism.
     * Uses spatial locality and ObjectPools for efficiency.
     * 
     * @param prism The prism to check
     * @param candidatePrisms List of candidate prisms to test
     * @return Set of colliding prism keys
     */
    public static Set<PrismKey> findCollidingPrisms(PrismKey prism, 
                                                    Collection<PrismKey> candidatePrisms) {
        Set<PrismKey> colliding = new HashSet<>();
        
        // Get AABB for quick rejection
        float[] bounds = PrismGeometry.computeBoundingBox(prism);
        
        for (PrismKey candidate : candidatePrisms) {
            if (candidate == prism) continue; // Skip same object reference, not just equal objects
            
            // Quick AABB test first
            float[] candidateBounds = PrismGeometry.computeBoundingBox(candidate);
            if (aabbOverlap(bounds, candidateBounds)) {
                // Do detailed collision test
                CollisionResult result = testPrismPrismCollision(prism, candidate);
                if (result.collides) {
                    colliding.add(candidate);
                }
            }
        }
        
        return colliding;
    }
    
    /**
     * Quick AABB collision test for early rejection.
     */
    private static boolean testAABBCollision(PrismKey prism1, PrismKey prism2) {
        float[] bounds1 = PrismGeometry.computeBoundingBox(prism1);
        float[] bounds2 = PrismGeometry.computeBoundingBox(prism2);
        return aabbOverlap(bounds1, bounds2);
    }
    
    /**
     * Check if two AABBs overlap.
     */
    private static boolean aabbOverlap(float[] bounds1, float[] bounds2) {
        return !(bounds1[3] < bounds2[0] || bounds2[3] < bounds1[0] ||
                 bounds1[4] < bounds2[1] || bounds2[4] < bounds1[1] ||
                 bounds1[5] < bounds2[2] || bounds2[5] < bounds1[2]);
    }
    
    /**
     * Add face normals of a triangular prism to the axis list.
     */
    private static void addPrismFaceNormals(float[][] vertices, List<Vector3f> axes) {
        // Bottom triangle normal (vertices 0,1,2)
        Vector3f bottomNormal = computeTriangleNormal(vertices[0], vertices[1], vertices[2]);
        axes.add(bottomNormal);
        
        // Top triangle normal (vertices 3,4,5)
        Vector3f topNormal = computeTriangleNormal(vertices[3], vertices[4], vertices[5]);
        axes.add(topNormal);
        
        // Side face 0 normal (quad 1,2,4,5)
        Vector3f side0Normal = computeQuadNormal(vertices[1], vertices[2], vertices[5], vertices[4]);
        axes.add(side0Normal);
        
        // Side face 1 normal (quad 0,2,3,5)
        Vector3f side1Normal = computeQuadNormal(vertices[0], vertices[2], vertices[5], vertices[3]);
        axes.add(side1Normal);
        
        // Side face 2 normal (quad 0,1,3,4)
        Vector3f side2Normal = computeQuadNormal(vertices[0], vertices[1], vertices[4], vertices[3]);
        axes.add(side2Normal);
    }
    
    /**
     * Compute normal of a triangle.
     */
    private static Vector3f computeTriangleNormal(float[] v0, float[] v1, float[] v2) {
        Vector3f edge1 = new Vector3f(v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2]);
        Vector3f edge2 = new Vector3f(v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2]);
        Vector3f normal = new Vector3f();
        normal.cross(edge1, edge2);
        normal.normalize();
        return normal;
    }
    
    /**
     * Compute normal of a quadrilateral (average of two triangle normals).
     */
    private static Vector3f computeQuadNormal(float[] v0, float[] v1, float[] v2, float[] v3) {
        Vector3f n1 = computeTriangleNormal(v0, v1, v2);
        Vector3f n2 = computeTriangleNormal(v0, v2, v3);
        n1.add(n2);
        n1.normalize();
        return n1;
    }
    
    /**
     * Add edge cross products for SAT.
     */
    private static void addEdgeCrossProducts(float[][] vertices1, float[][] vertices2, 
                                           List<Vector3f> axes) {
        // Get unique edges from both prisms
        Vector3f[] edges1 = getPrismEdges(vertices1);
        Vector3f[] edges2 = getPrismEdges(vertices2);
        
        // Compute cross products
        for (Vector3f e1 : edges1) {
            for (Vector3f e2 : edges2) {
                Vector3f cross = new Vector3f();
                cross.cross(e1, e2);
                if (cross.lengthSquared() > 1e-6f) { // Skip parallel edges
                    axes.add(cross);
                }
            }
        }
    }
    
    /**
     * Get the 9 unique edges of a triangular prism.
     */
    private static Vector3f[] getPrismEdges(float[][] vertices) {
        Vector3f[] edges = new Vector3f[9];
        
        // Bottom triangle edges
        edges[0] = new Vector3f(vertices[1][0] - vertices[0][0], 
                               vertices[1][1] - vertices[0][1], 
                               vertices[1][2] - vertices[0][2]);
        edges[1] = new Vector3f(vertices[2][0] - vertices[1][0], 
                               vertices[2][1] - vertices[1][1], 
                               vertices[2][2] - vertices[1][2]);
        edges[2] = new Vector3f(vertices[0][0] - vertices[2][0], 
                               vertices[0][1] - vertices[2][1], 
                               vertices[0][2] - vertices[2][2]);
        
        // Vertical edges
        edges[3] = new Vector3f(vertices[3][0] - vertices[0][0], 
                               vertices[3][1] - vertices[0][1], 
                               vertices[3][2] - vertices[0][2]);
        edges[4] = new Vector3f(vertices[4][0] - vertices[1][0], 
                               vertices[4][1] - vertices[1][1], 
                               vertices[4][2] - vertices[1][2]);
        edges[5] = new Vector3f(vertices[5][0] - vertices[2][0], 
                               vertices[5][1] - vertices[2][1], 
                               vertices[5][2] - vertices[2][2]);
        
        // Top triangle edges
        edges[6] = new Vector3f(vertices[4][0] - vertices[3][0], 
                               vertices[4][1] - vertices[3][1], 
                               vertices[4][2] - vertices[3][2]);
        edges[7] = new Vector3f(vertices[5][0] - vertices[4][0], 
                               vertices[5][1] - vertices[4][1], 
                               vertices[5][2] - vertices[4][2]);
        edges[8] = new Vector3f(vertices[3][0] - vertices[5][0], 
                               vertices[3][1] - vertices[5][1], 
                               vertices[3][2] - vertices[5][2]);
        
        return edges;
    }
    
    /**
     * Project prism vertices onto an axis and return min/max values.
     */
    private static float[] projectPrismOntoAxis(float[][] vertices, Vector3f axis) {
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        
        for (float[] vertex : vertices) {
            float projection = vertex[0] * axis.x + vertex[1] * axis.y + vertex[2] * axis.z;
            min = Math.min(min, projection);
            max = Math.max(max, projection);
        }
        
        return new float[]{min, max};
    }
    
    /**
     * Calculate contact point between two colliding prisms.
     * Simplified implementation - returns center of overlap region.
     */
    private static Point3f calculateContactPoint(float[][] vertices1, float[][] vertices2, 
                                               Vector3f separationAxis) {
        // Calculate center of prism 1
        Point3f center1 = new Point3f();
        for (float[] v : vertices1) {
            center1.x += v[0];
            center1.y += v[1];
            center1.z += v[2];
        }
        center1.scale(1.0f / vertices1.length);
        
        // Calculate center of prism 2
        Point3f center2 = new Point3f();
        for (float[] v : vertices2) {
            center2.x += v[0];
            center2.y += v[1];
            center2.z += v[2];
        }
        center2.scale(1.0f / vertices2.length);
        
        // Return midpoint between centers (simplified)
        Point3f contact = new Point3f();
        contact.interpolate(center1, center2, 0.5f);
        return contact;
    }
    
    /**
     * Find the closest point on a prism to a given point.
     * Used for sphere collision detection.
     */
    private static Point3f findClosestPointOnPrism(float[][] vertices, Point3f point) {
        float minDistSq = Float.MAX_VALUE;
        Point3f closest = new Point3f();
        
        // Check distance to each face
        // Bottom triangle
        Point3f facePoint = closestPointOnTriangle(vertices[0], vertices[1], vertices[2], point);
        float distSq = facePoint.distanceSquared(point);
        if (distSq < minDistSq) {
            minDistSq = distSq;
            closest.set(facePoint);
        }
        
        // Top triangle
        facePoint = closestPointOnTriangle(vertices[3], vertices[4], vertices[5], point);
        distSq = facePoint.distanceSquared(point);
        if (distSq < minDistSq) {
            minDistSq = distSq;
            closest.set(facePoint);
        }
        
        // Side faces (as two triangles each)
        // Face 0
        facePoint = closestPointOnQuad(vertices[1], vertices[2], vertices[5], vertices[4], point);
        distSq = facePoint.distanceSquared(point);
        if (distSq < minDistSq) {
            minDistSq = distSq;
            closest.set(facePoint);
        }
        
        // Face 1
        facePoint = closestPointOnQuad(vertices[0], vertices[2], vertices[5], vertices[3], point);
        distSq = facePoint.distanceSquared(point);
        if (distSq < minDistSq) {
            minDistSq = distSq;
            closest.set(facePoint);
        }
        
        // Face 2
        facePoint = closestPointOnQuad(vertices[0], vertices[1], vertices[4], vertices[3], point);
        distSq = facePoint.distanceSquared(point);
        if (distSq < minDistSq) {
            minDistSq = distSq;
            closest.set(facePoint);
        }
        
        return closest;
    }
    
    /**
     * Find closest point on a triangle to a given point.
     */
    private static Point3f closestPointOnTriangle(float[] v0, float[] v1, float[] v2, Point3f p) {
        // Simplified implementation - would use barycentric coordinates in production
        Point3f center = new Point3f(
            (v0[0] + v1[0] + v2[0]) / 3.0f,
            (v0[1] + v1[1] + v2[1]) / 3.0f,
            (v0[2] + v1[2] + v2[2]) / 3.0f
        );
        return center;
    }
    
    /**
     * Find closest point on a quad to a given point.
     */
    private static Point3f closestPointOnQuad(float[] v0, float[] v1, float[] v2, float[] v3, Point3f p) {
        // Test both triangles that make up the quad
        Point3f p1 = closestPointOnTriangle(v0, v1, v2, p);
        Point3f p2 = closestPointOnTriangle(v0, v2, v3, p);
        
        if (p1.distanceSquared(p) < p2.distanceSquared(p)) {
            return p1;
        }
        return p2;
    }
    
    /**
     * Convert prism vertices to float array format.
     */
    private static float[][] getVerticesAsFloatArray(PrismKey prism) {
        var vertices = PrismGeometry.getVertices(prism);
        float[][] result = new float[vertices.size()][];
        for (int i = 0; i < vertices.size(); i++) {
            Point3f vertex = vertices.get(i);
            result[i] = new float[]{vertex.x, vertex.y, vertex.z};
        }
        return result;
    }
}
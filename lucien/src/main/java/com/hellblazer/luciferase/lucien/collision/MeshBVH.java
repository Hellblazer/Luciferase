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
import java.util.ArrayList;
import java.util.List;

/**
 * Bounding Volume Hierarchy (BVH) for accelerating triangle mesh collision detection.
 * Uses axis-aligned bounding boxes (AABBs) as bounding volumes.
 *
 * @author hal.hildebrand
 */
public class MeshBVH {
    
    private final TriangleMeshData meshData;
    private final BVHNode root;
    private static final int MAX_TRIANGLES_PER_LEAF = 4;
    
    public MeshBVH(TriangleMeshData meshData) {
        this.meshData = meshData;
        
        // Build list of all triangle indices
        var triangleIndices = new ArrayList<Integer>();
        for (int i = 0; i < meshData.getTriangleCount(); i++) {
            triangleIndices.add(i);
        }
        
        // Build the BVH tree
        this.root = buildBVH(triangleIndices, 0);
    }
    
    private static boolean boundsOverlap(EntityBounds b1, EntityBounds b2) {
        return !(b1.getMaxX() < b2.getMinX() || b1.getMinX() > b2.getMaxX() ||
                b1.getMaxY() < b2.getMinY() || b1.getMinY() > b2.getMaxY() ||
                b1.getMaxZ() < b2.getMinZ() || b1.getMinZ() > b2.getMaxZ());
    }
    
    /**
     * Find all triangles that intersect with the given AABB
     */
    public List<Integer> getTrianglesInAABB(EntityBounds bounds) {
        var result = new ArrayList<Integer>();
        if (root != null) {
            collectTrianglesInAABB(root, bounds, result);
        }
        return result;
    }
    
    /**
     * Find the closest triangle intersected by the ray
     */
    public RayTriangleIntersection intersectRay(Ray3D ray) {
        if (root == null) {
            return null;
        }
        return intersectRayNode(root, ray, 0, Float.MAX_VALUE);
    }
    
    /**
     * Test if a sphere intersects any triangles in the mesh
     */
    public List<Integer> getTrianglesIntersectingSphere(Point3f center, float radius) {
        var result = new ArrayList<Integer>();
        if (root != null) {
            collectTrianglesInSphere(root, center, radius, result);
        }
        return result;
    }
    
    private BVHNode buildBVH(List<Integer> triangleIndices, int depth) {
        if (triangleIndices.isEmpty()) {
            return null;
        }
        
        var node = new BVHNode();
        node.bounds = computeBounds(triangleIndices);
        
        // Create leaf node if we have few enough triangles
        if (triangleIndices.size() <= MAX_TRIANGLES_PER_LEAF) {
            node.triangleIndices = new ArrayList<>(triangleIndices);
            return node;
        }
        
        // Find the best split axis and position
        int splitAxis = chooseSplitAxis(node.bounds);
        float splitPos = computeSplitPosition(triangleIndices, splitAxis);
        
        // Partition triangles
        var leftTriangles = new ArrayList<Integer>();
        var rightTriangles = new ArrayList<Integer>();
        
        for (var triIndex : triangleIndices) {
            var centroid = computeTriangleCentroid(triIndex);
            float pos = switch (splitAxis) {
                case 0 -> centroid.x;
                case 1 -> centroid.y;
                case 2 -> centroid.z;
                default -> throw new IllegalStateException();
            };
            
            if (pos < splitPos) {
                leftTriangles.add(triIndex);
            } else {
                rightTriangles.add(triIndex);
            }
        }
        
        // Handle degenerate case where all triangles end up on one side
        if (leftTriangles.isEmpty() || rightTriangles.isEmpty()) {
            node.triangleIndices = new ArrayList<>(triangleIndices);
            return node;
        }
        
        // Recursively build child nodes
        node.left = buildBVH(leftTriangles, depth + 1);
        node.right = buildBVH(rightTriangles, depth + 1);
        
        return node;
    }
    
    private EntityBounds computeBounds(List<Integer> triangleIndices) {
        var min = new Point3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        var max = new Point3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        
        for (var triIndex : triangleIndices) {
            var tri = meshData.getTriangle(triIndex);
            for (int i = 0; i < 3; i++) {
                var vertex = meshData.getVertex(switch (i) {
                    case 0 -> tri.v0;
                    case 1 -> tri.v1;
                    case 2 -> tri.v2;
                    default -> throw new IllegalStateException();
                });
                
                min.x = Math.min(min.x, vertex.x);
                min.y = Math.min(min.y, vertex.y);
                min.z = Math.min(min.z, vertex.z);
                max.x = Math.max(max.x, vertex.x);
                max.y = Math.max(max.y, vertex.y);
                max.z = Math.max(max.z, vertex.z);
            }
        }
        
        return new EntityBounds(min, max);
    }
    
    private int chooseSplitAxis(EntityBounds bounds) {
        float dx = bounds.getMax().x - bounds.getMin().x;
        float dy = bounds.getMax().y - bounds.getMin().y;
        float dz = bounds.getMax().z - bounds.getMin().z;
        
        if (dx >= dy && dx >= dz) return 0;
        if (dy >= dz) return 1;
        return 2;
    }
    
    private float computeSplitPosition(List<Integer> triangleIndices, int axis) {
        float sum = 0;
        for (var triIndex : triangleIndices) {
            var centroid = computeTriangleCentroid(triIndex);
            sum += switch (axis) {
                case 0 -> centroid.x;
                case 1 -> centroid.y;
                case 2 -> centroid.z;
                default -> throw new IllegalStateException();
            };
        }
        return sum / triangleIndices.size();
    }
    
    private Point3f computeTriangleCentroid(int triangleIndex) {
        var v0 = new Point3f();
        var v1 = new Point3f();
        var v2 = new Point3f();
        meshData.getTriangleVertices(triangleIndex, v0, v1, v2);
        
        var centroid = new Point3f(v0);
        centroid.add(v1);
        centroid.add(v2);
        centroid.scale(1.0f / 3.0f);
        return centroid;
    }
    
    private void collectTrianglesInAABB(BVHNode node, EntityBounds bounds, List<Integer> result) {
        if (!boundsOverlap(node.bounds, bounds)) {
            return;
        }
        
        if (node.isLeaf()) {
            // For leaf nodes, check each triangle
            for (var triIndex : node.triangleIndices) {
                if (triangleIntersectsAABB(triIndex, bounds)) {
                    result.add(triIndex);
                }
            }
        } else {
            // For internal nodes, recurse to children
            if (node.left != null) {
                collectTrianglesInAABB(node.left, bounds, result);
            }
            if (node.right != null) {
                collectTrianglesInAABB(node.right, bounds, result);
            }
        }
    }
    
    private void collectTrianglesInSphere(BVHNode node, Point3f center, float radius, List<Integer> result) {
        if (!sphereIntersectsAABB(center, radius, node.bounds)) {
            return;
        }
        
        if (node.isLeaf()) {
            for (var triIndex : node.triangleIndices) {
                if (triangleIntersectsSphere(triIndex, center, radius)) {
                    result.add(triIndex);
                }
            }
        } else {
            if (node.left != null) {
                collectTrianglesInSphere(node.left, center, radius, result);
            }
            if (node.right != null) {
                collectTrianglesInSphere(node.right, center, radius, result);
            }
        }
    }
    
    private RayTriangleIntersection intersectRayNode(BVHNode node, Ray3D ray, float tMin, float tMax) {
        // Check if ray intersects node's bounding box
        if (!rayIntersectsAABB(ray, node.bounds, tMin, tMax)) {
            return null;
        }
        
        if (node.isLeaf()) {
            RayTriangleIntersection closest = null;
            float closestT = tMax;
            
            for (var triIndex : node.triangleIndices) {
                var hit = rayIntersectsTriangle(ray, triIndex);
                if (hit != null && hit.t >= tMin && hit.t < closestT) {
                    closest = hit;
                    closestT = hit.t;
                }
            }
            return closest;
        } else {
            var leftHit = node.left != null ? intersectRayNode(node.left, ray, tMin, tMax) : null;
            var rightHit = node.right != null ? intersectRayNode(node.right, ray, tMin, tMax) : null;
            
            if (leftHit == null) return rightHit;
            if (rightHit == null) return leftHit;
            return leftHit.t < rightHit.t ? leftHit : rightHit;
        }
    }
    
    private boolean triangleIntersectsAABB(int triangleIndex, EntityBounds bounds) {
        var v0 = new Point3f();
        var v1 = new Point3f();
        var v2 = new Point3f();
        meshData.getTriangleVertices(triangleIndex, v0, v1, v2);
        
        // Simple triangle-AABB test
        // First check if triangle AABB overlaps box
        var triMin = new Point3f(
            Math.min(Math.min(v0.x, v1.x), v2.x),
            Math.min(Math.min(v0.y, v1.y), v2.y),
            Math.min(Math.min(v0.z, v1.z), v2.z)
        );
        var triMax = new Point3f(
            Math.max(Math.max(v0.x, v1.x), v2.x),
            Math.max(Math.max(v0.y, v1.y), v2.y),
            Math.max(Math.max(v0.z, v1.z), v2.z)
        );
        
        return !(triMax.x < bounds.getMin().x || triMin.x > bounds.getMax().x ||
                triMax.y < bounds.getMin().y || triMin.y > bounds.getMax().y ||
                triMax.z < bounds.getMin().z || triMin.z > bounds.getMax().z);
    }
    
    private boolean triangleIntersectsSphere(int triangleIndex, Point3f center, float radius) {
        var v0 = new Point3f();
        var v1 = new Point3f();
        var v2 = new Point3f();
        meshData.getTriangleVertices(triangleIndex, v0, v1, v2);
        
        // Find closest point on triangle to sphere center
        var closest = closestPointOnTriangle(center, v0, v1, v2);
        var dist = closest.distance(center);
        return dist <= radius;
    }
    
    private boolean sphereIntersectsAABB(Point3f center, float radius, EntityBounds bounds) {
        float sqDist = 0;
        
        if (center.x < bounds.getMin().x) sqDist += (bounds.getMin().x - center.x) * (bounds.getMin().x - center.x);
        else if (center.x > bounds.getMax().x) sqDist += (center.x - bounds.getMax().x) * (center.x - bounds.getMax().x);
        
        if (center.y < bounds.getMin().y) sqDist += (bounds.getMin().y - center.y) * (bounds.getMin().y - center.y);
        else if (center.y > bounds.getMax().y) sqDist += (center.y - bounds.getMax().y) * (center.y - bounds.getMax().y);
        
        if (center.z < bounds.getMin().z) sqDist += (bounds.getMin().z - center.z) * (bounds.getMin().z - center.z);
        else if (center.z > bounds.getMax().z) sqDist += (center.z - bounds.getMax().z) * (center.z - bounds.getMax().z);
        
        return sqDist <= radius * radius;
    }
    
    private boolean rayIntersectsAABB(Ray3D ray, EntityBounds bounds, float tMin, float tMax) {
        var invDir = new Vector3f(
            1.0f / ray.direction().x,
            1.0f / ray.direction().y,
            1.0f / ray.direction().z
        );
        
        float t1 = (bounds.getMin().x - ray.origin().x) * invDir.x;
        float t2 = (bounds.getMax().x - ray.origin().x) * invDir.x;
        
        tMin = Math.max(tMin, Math.min(t1, t2));
        tMax = Math.min(tMax, Math.max(t1, t2));
        
        t1 = (bounds.getMin().y - ray.origin().y) * invDir.y;
        t2 = (bounds.getMax().y - ray.origin().y) * invDir.y;
        
        tMin = Math.max(tMin, Math.min(t1, t2));
        tMax = Math.min(tMax, Math.max(t1, t2));
        
        t1 = (bounds.getMin().z - ray.origin().z) * invDir.z;
        t2 = (bounds.getMax().z - ray.origin().z) * invDir.z;
        
        tMin = Math.max(tMin, Math.min(t1, t2));
        tMax = Math.min(tMax, Math.max(t1, t2));
        
        return tMax >= tMin && tMax >= 0;
    }
    
    private RayTriangleIntersection rayIntersectsTriangle(Ray3D ray, int triangleIndex) {
        var v0 = new Point3f();
        var v1 = new Point3f();
        var v2 = new Point3f();
        meshData.getTriangleVertices(triangleIndex, v0, v1, v2);
        
        // Möller–Trumbore intersection algorithm
        var edge1 = new Vector3f();
        edge1.sub(v1, v0);
        var edge2 = new Vector3f();
        edge2.sub(v2, v0);
        
        var h = new Vector3f();
        h.cross(ray.direction(), edge2);
        var a = edge1.dot(h);
        
        if (a > -1e-6f && a < 1e-6f) {
            return null;
        }
        
        var f = 1.0f / a;
        var s = new Vector3f();
        s.sub(ray.origin(), v0);
        var u = f * s.dot(h);
        
        if (u < 0.0f || u > 1.0f) {
            return null;
        }
        
        var q = new Vector3f();
        q.cross(s, edge1);
        var v = f * ray.direction().dot(q);
        
        if (v < 0.0f || u + v > 1.0f) {
            return null;
        }
        
        var t = f * edge2.dot(q);
        if (t > 1e-6f) {
            var intersectionPoint = new Point3f(ray.direction());
            intersectionPoint.scale(t);
            intersectionPoint.add(ray.origin());
            
            var normal = new Vector3f();
            normal.cross(edge1, edge2);
            normal.normalize();
            
            return new RayTriangleIntersection(triangleIndex, t, intersectionPoint, normal);
        }
        
        return null;
    }
    
    private Point3f closestPointOnTriangle(Point3f p, Point3f a, Point3f b, Point3f c) {
        // Compute vectors
        var ab = new Vector3f();
        ab.sub(b, a);
        var ac = new Vector3f();
        ac.sub(c, a);
        var ap = new Vector3f();
        ap.sub(p, a);
        
        // Compute dot products
        float d1 = ab.dot(ap);
        float d2 = ac.dot(ap);
        if (d1 <= 0 && d2 <= 0) return new Point3f(a);
        
        // Check if P in vertex region outside B
        var bp = new Vector3f();
        bp.sub(p, b);
        float d3 = ab.dot(bp);
        float d4 = ac.dot(bp);
        if (d3 >= 0 && d4 <= d3) return new Point3f(b);
        
        // Check if P in edge region of AB
        float vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            float v = d1 / (d1 - d3);
            var result = new Point3f(ab);
            result.scale(v);
            result.add(a);
            return result;
        }
        
        // Check if P in vertex region outside C
        var cp = new Vector3f();
        cp.sub(p, c);
        float d5 = ab.dot(cp);
        float d6 = ac.dot(cp);
        if (d6 >= 0 && d5 <= d6) return new Point3f(c);
        
        // Check if P in edge region of AC
        float vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            float w = d2 / (d2 - d6);
            var result = new Point3f(ac);
            result.scale(w);
            result.add(a);
            return result;
        }
        
        // Check if P in edge region of BC
        float va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            var result = new Point3f();
            result.sub(c, b);
            result.scale(w);
            result.add(b);
            return result;
        }
        
        // P inside face region
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
    
    /**
     * BVH node structure
     */
    private static class BVHNode {
        EntityBounds bounds;
        BVHNode left;
        BVHNode right;
        List<Integer> triangleIndices;
        
        boolean isLeaf() {
            return triangleIndices != null;
        }
    }
    
    /**
     * Ray-triangle intersection result
     */
    public static class RayTriangleIntersection {
        public final int triangleIndex;
        public final float t;
        public final Point3f point;
        public final Vector3f normal;
        
        public RayTriangleIntersection(int triangleIndex, float t, Point3f point, Vector3f normal) {
            this.triangleIndex = triangleIndex;
            this.t = t;
            this.point = point;
            this.normal = normal;
        }
    }
}
package com.hellblazer.luciferase.esvo.cpu;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * CPU implementation of ESVO ray traversal that matches the GPU kernel algorithm.
 * This provides a reference implementation for validation and cross-checking.
 */
public class ESVOCPUTraversal {
    
    private static final float EPSILON = 1e-6f;
    
    /**
     * Ray structure for CPU traversal
     */
    public static class Ray {
        public float originX, originY, originZ;
        public float directionX, directionY, directionZ;
        public float tMin, tMax;
        
        public Ray(float ox, float oy, float oz, float dx, float dy, float dz, float tMin, float tMax) {
            this.originX = ox; this.originY = oy; this.originZ = oz;
            this.directionX = dx; this.directionY = dy; this.directionZ = dz;
            this.tMin = tMin; this.tMax = tMax;
        }
    }
    
    /**
     * Octree node structure matching GPU version
     */
    public static class OctreeNode {
        public int childDescriptor;  // childMask (8 bits) + childPtr (24 bits)
        public int attributes;       // Material/color/voxel data
        
        public OctreeNode(int childDescriptor, int attributes) {
            this.childDescriptor = childDescriptor;
            this.attributes = attributes;
        }
    }
    
    /**
     * Intersection result structure
     */
    public static class IntersectionResult {
        public int hit;              // 0 = miss, 1 = hit
        public float t;              // Distance along ray
        public float normalX, normalY, normalZ;  // Surface normal
        public int voxelValue;       // Voxel data
        public int nodeIndex;        // Node that was hit
        public int iterations;       // Traversal iterations
        
        public IntersectionResult() {
            this.hit = 0;
            this.t = Float.MAX_VALUE;
            this.normalX = 0; this.normalY = 1; this.normalZ = 0;
            this.voxelValue = 0;
            this.nodeIndex = 0;
            this.iterations = 0;
        }
    }
    
    /**
     * Stack entry for traversal state
     */
    private static class StackEntry {
        int nodeIdx;
        float tEntry;
        float tExit;
        int scale;
        float[] pos;
        
        StackEntry(int nodeIdx, float tEntry, float tExit, int scale, float[] pos) {
            this.nodeIdx = nodeIdx;
            this.tEntry = tEntry;
            this.tExit = tExit;
            this.scale = scale;
            this.pos = pos.clone();
        }
    }
    
    /**
     * Traverse a single ray through the octree.
     * This implementation matches the GPU kernel algorithm exactly.
     */
    public static IntersectionResult traverseRay(Ray ray, OctreeNode[] octree, 
                                                 float[] sceneMin, float[] sceneMax,
                                                 int maxDepth) {
        IntersectionResult result = new IntersectionResult();
        result.hit = 0;
        result.t = Float.MAX_VALUE;
        
        // Check scene bounds
        float[] tBounds = intersectAABB(ray, sceneMin, sceneMax);
        if (tBounds == null) {
            return result;
        }
        
        float tEntry = tBounds[0];
        float tExit = tBounds[1];
        
        // Stack for traversal
        Deque<StackEntry> stack = new ArrayDeque<>();
        stack.push(new StackEntry(0, tEntry, tExit, 0, sceneMin));
        
        float closestHit = ray.tMax;
        int hitVoxel = 0;
        float[] hitPoint = new float[3];
        int iterations = 0;
        
        // Traverse octree
        while (!stack.isEmpty()) {
            iterations++;
            StackEntry current = stack.pop();
            
            // Skip if beyond closest hit
            if (current.tEntry > closestHit) {
                continue;
            }
            
            if (current.nodeIdx >= octree.length) {
                continue;
            }
            
            OctreeNode node = octree[current.nodeIdx];
            
            // Leaf node - check for voxel
            boolean isLeaf = (node.childDescriptor & 0xFF) == 0; // childMask == 0
            if (isLeaf || current.scale >= maxDepth) {
                if (node.attributes != 0 && current.tEntry < closestHit) {
                    closestHit = current.tEntry;
                    hitVoxel = node.attributes;
                    hitPoint[0] = ray.originX + ray.directionX * closestHit;
                    hitPoint[1] = ray.originY + ray.directionY * closestHit;
                    hitPoint[2] = ray.originZ + ray.directionZ * closestHit;
                }
                continue;
            }
            
            // Internal node - traverse children
            int childMask = node.childDescriptor & 0xFF;
            if (childMask == 0) {
                continue; // No children
            }
            
            float nodeSize = (sceneMax[0] - sceneMin[0]) / (1 << (current.scale + 1));
            float[] center = new float[] {
                current.pos[0] + nodeSize,
                current.pos[1] + nodeSize,
                current.pos[2] + nodeSize
            };
            
            // Determine traversal order based on ray direction
            int firstChild = getChildIndex(
                ray.originX + ray.directionX * current.tEntry,
                ray.originY + ray.directionY * current.tEntry,
                ray.originZ + ray.directionZ * current.tEntry,
                center
            );
            
            // Add children to stack in reverse order for correct traversal
            for (int i = 7; i >= 0; i--) {
                if ((childMask & (1 << i)) == 0) {
                    continue;
                }
                
                // Calculate child bounds
                float[] childMin = current.pos.clone();
                float[] childMax = new float[] {
                    current.pos[0] + nodeSize * 2.0f,
                    current.pos[1] + nodeSize * 2.0f,
                    current.pos[2] + nodeSize * 2.0f
                };
                
                if ((i & 1) != 0) childMin[0] += nodeSize;
                else childMax[0] -= nodeSize;
                if ((i & 2) != 0) childMin[1] += nodeSize;
                else childMax[1] -= nodeSize;
                if ((i & 4) != 0) childMin[2] += nodeSize;
                else childMax[2] -= nodeSize;
                
                float[] childTimes = intersectAABB(ray, childMin, childMax);
                if (childTimes != null) {
                    float childTEntry = childTimes[0];
                    float childTExit = childTimes[1];
                    
                    if (childTEntry < closestHit) {
                        // childPtr is a relative offset from current node
                        int childPtr = (node.childDescriptor >> 8) & 0xFFFFFF;
                        int childIdx = current.nodeIdx + childPtr + Integer.bitCount(childMask & ((1 << i) - 1));
                        stack.push(new StackEntry(childIdx, childTEntry, childTExit,
                                                 current.scale + 1, childMin));
                    }
                }
            }
        }
        
        // Write results - always record iterations regardless of hit
        result.iterations = iterations;

        if (hitVoxel != 0) {
            result.hit = 1;
            result.t = closestHit;
            result.normalX = 0; // Would need proper normal calculation
            result.normalY = 1;
            result.normalZ = 0;
            result.voxelValue = hitVoxel;
            result.nodeIndex = 0; // Would need to track
        }

        return result;
    }
    
    /**
     * Ray-AABB intersection test
     * Returns [tEntry, tExit] or null if no intersection
     */
    private static float[] intersectAABB(Ray ray, float[] boxMin, float[] boxMax) {
        float invDirX = 1.0f / ray.directionX;
        float invDirY = 1.0f / ray.directionY;
        float invDirZ = 1.0f / ray.directionZ;
        
        float t0x = (boxMin[0] - ray.originX) * invDirX;
        float t1x = (boxMax[0] - ray.originX) * invDirX;
        float t0y = (boxMin[1] - ray.originY) * invDirY;
        float t1y = (boxMax[1] - ray.originY) * invDirY;
        float t0z = (boxMin[2] - ray.originZ) * invDirZ;
        float t1z = (boxMax[2] - ray.originZ) * invDirZ;
        
        float tMinX = Math.min(t0x, t1x);
        float tMaxX = Math.max(t0x, t1x);
        float tMinY = Math.min(t0y, t1y);
        float tMaxY = Math.max(t0y, t1y);
        float tMinZ = Math.min(t0z, t1z);
        float tMaxZ = Math.max(t0z, t1z);
        
        float tEntry = Math.max(Math.max(tMinX, tMinY), Math.max(tMinZ, ray.tMin));
        float tExit = Math.min(Math.min(tMaxX, tMaxY), Math.min(tMaxZ, ray.tMax));
        
        if (tEntry <= tExit && tExit >= 0.0f) {
            return new float[] { tEntry, tExit };
        }
        return null;
    }
    
    /**
     * Get child index from position within parent
     */
    private static int getChildIndex(float x, float y, float z, float[] center) {
        int idx = 0;
        if (x >= center[0]) idx |= 1;
        if (y >= center[1]) idx |= 2;
        if (z >= center[2]) idx |= 4;
        return idx;
    }
    
    /**
     * Batch traversal for multiple rays
     */
    public static IntersectionResult[] traverseRays(Ray[] rays, OctreeNode[] octree,
                                                    float[] sceneMin, float[] sceneMax,
                                                    int maxDepth) {
        IntersectionResult[] results = new IntersectionResult[rays.length];
        for (int i = 0; i < rays.length; i++) {
            results[i] = traverseRay(rays[i], octree, sceneMin, sceneMax, maxDepth);
        }
        return results;
    }
    
    /**
     * Optimized beam traversal for coherent rays
     */
    public static IntersectionResult[] traverseBeam(Ray[] rays, OctreeNode[] octree,
                                                    float[] sceneMin, float[] sceneMax,
                                                    int maxDepth, int raysPerBeam) {
        // This would implement beam optimization for coherent rays
        // For now, fall back to individual traversal
        return traverseRays(rays, octree, sceneMin, sceneMax, maxDepth);
    }
}
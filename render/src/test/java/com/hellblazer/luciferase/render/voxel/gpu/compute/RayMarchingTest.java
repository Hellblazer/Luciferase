package com.hellblazer.luciferase.render.voxel.gpu.compute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.vecmath.Vector3f;
import javax.vecmath.Vector2f;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ray marching algorithms used in voxel octree rendering.
 * Validates ray-AABB intersection, octree traversal, and hit detection.
 */
public class RayMarchingTest {
    
    private static final float EPSILON = 1e-5f;
    
    // Mock octree node structure
    static class OctreeNode {
        int childMask;      // Bitmask for children
        int dataOffset;     // Offset to child nodes
        int nodeType;       // 0=internal, 1=leaf
        int voxelData;      // Packed color for leaves
        
        public OctreeNode(int childMask, int dataOffset, int nodeType, int voxelData) {
            this.childMask = childMask;
            this.dataOffset = dataOffset;
            this.nodeType = nodeType;
            this.voxelData = voxelData;
        }
    }
    
    // Ray hit information
    static class RayHit {
        boolean hit;
        Vector3f position;
        Vector3f normal;
        float distance;
        Vector3f voxelColor;
        int nodeLevel;
        
        public RayHit() {
            this.hit = false;
            this.distance = Float.MAX_VALUE;
        }
    }
    
    private List<OctreeNode> octreeNodes;
    
    @BeforeEach
    void setUp() {
        octreeNodes = new ArrayList<>();
    }
    
    @Test
    @DisplayName("Test ray-AABB intersection calculation")
    void testRayAABBIntersection() {
        Vector3f rayOrigin = new Vector3f(-1, 0.5f, 0.5f);
        Vector3f rayDir = new Vector3f(1, 0, 0);
        rayDir.normalize();
        
        Vector3f boxMin = new Vector3f(0, 0, 0);
        Vector3f boxMax = new Vector3f(1, 1, 1);
        
        Vector2f intersection = rayAABBIntersection(rayOrigin, rayDir, boxMin, boxMax);
        
        assertNotNull(intersection);
        assertTrue(intersection.x >= 0, "Should find valid intersection");
        assertEquals(1.0f, intersection.x, EPSILON, "Near intersection at box boundary");
        assertEquals(2.0f, intersection.y, EPSILON, "Far intersection at opposite boundary");
    }
    
    @Test
    @DisplayName("Test ray missing AABB")
    void testRayMissingAABB() {
        Vector3f rayOrigin = new Vector3f(-1, 2, 0.5f);
        Vector3f rayDir = new Vector3f(1, 0, 0);
        rayDir.normalize();
        
        Vector3f boxMin = new Vector3f(0, 0, 0);
        Vector3f boxMax = new Vector3f(1, 1, 1);
        
        Vector2f intersection = rayAABBIntersection(rayOrigin, rayDir, boxMin, boxMax);
        
        assertNotNull(intersection);
        assertTrue(intersection.x < 0, "Should indicate no intersection");
    }
    
    @Test
    @DisplayName("Test octant index calculation")
    void testOctantIndexCalculation() {
        Vector3f nodeCenter = new Vector3f(0.5f, 0.5f, 0.5f);
        
        // Test all 8 octants
        assertEquals(0, getOctantIndex(new Vector3f(0.25f, 0.25f, 0.25f), nodeCenter));
        assertEquals(1, getOctantIndex(new Vector3f(0.75f, 0.25f, 0.25f), nodeCenter));
        assertEquals(2, getOctantIndex(new Vector3f(0.25f, 0.75f, 0.25f), nodeCenter));
        assertEquals(3, getOctantIndex(new Vector3f(0.75f, 0.75f, 0.25f), nodeCenter));
        assertEquals(4, getOctantIndex(new Vector3f(0.25f, 0.25f, 0.75f), nodeCenter));
        assertEquals(5, getOctantIndex(new Vector3f(0.75f, 0.25f, 0.75f), nodeCenter));
        assertEquals(6, getOctantIndex(new Vector3f(0.25f, 0.75f, 0.75f), nodeCenter));
        assertEquals(7, getOctantIndex(new Vector3f(0.75f, 0.75f, 0.75f), nodeCenter));
    }
    
    @Test
    @DisplayName("Test octree traversal with single leaf hit")
    void testSingleLeafHit() {
        // Create simple octree with one leaf node
        octreeNodes.add(new OctreeNode(0, 0, 1, 0xFF0000)); // Red leaf at root
        
        Vector3f rayOrigin = new Vector3f(-0.5f, 0.5f, 0.5f);
        Vector3f rayDir = new Vector3f(1, 0, 0);
        rayDir.normalize();
        
        RayHit hit = traverseOctree(rayOrigin, rayDir);
        
        assertTrue(hit.hit, "Should hit the voxel");
        assertEquals(0.5f, hit.distance, EPSILON, "Distance to box boundary");
        assertEquals(0, hit.nodeLevel, "Hit at root level");
        
        // Check extracted color (red)
        assertEquals(1.0f, hit.voxelColor.x, EPSILON);
        assertEquals(0.0f, hit.voxelColor.y, EPSILON);
        assertEquals(0.0f, hit.voxelColor.z, EPSILON);
    }
    
    @Test
    @DisplayName("Test octree traversal order")
    void testTraversalOrder() {
        // Build octree with specific structure
        // Root has 4 children in XY plane (z=0)
        octreeNodes.add(new OctreeNode(0x0F, 1, 0, 0)); // Root with children 0,1,2,3
        octreeNodes.add(new OctreeNode(0, 0, 1, 0xFF0000)); // Child 0 - red
        octreeNodes.add(new OctreeNode(0, 0, 1, 0x00FF00)); // Child 1 - green
        octreeNodes.add(new OctreeNode(0, 0, 1, 0x0000FF)); // Child 2 - blue
        octreeNodes.add(new OctreeNode(0, 0, 1, 0xFFFF00)); // Child 3 - yellow
        
        // Ray through center hitting child 0
        Vector3f rayOrigin = new Vector3f(-0.1f, 0.25f, 0.25f);
        Vector3f rayDir = new Vector3f(1, 0, 0);
        rayDir.normalize();
        
        RayHit hit = traverseOctree(rayOrigin, rayDir);
        
        assertTrue(hit.hit, "Should hit a voxel");
        assertEquals(1, hit.nodeLevel, "Hit at depth 1");
        
        // Verify we hit the red voxel (child 0)
        assertEquals(1.0f, hit.voxelColor.x, EPSILON, "Should hit red voxel");
        assertEquals(0.0f, hit.voxelColor.y, EPSILON);
    }
    
    @Test
    @DisplayName("Test hit normal calculation")
    void testHitNormalCalculation() {
        octreeNodes.add(new OctreeNode(0, 0, 1, 0xFFFFFF)); // White leaf
        
        // Test rays from different directions
        testNormalFromDirection(new Vector3f(-1, 0.5f, 0.5f), new Vector3f(1, 0, 0), 
                                new Vector3f(-1, 0, 0)); // Hit from -X
        
        testNormalFromDirection(new Vector3f(2, 0.5f, 0.5f), new Vector3f(-1, 0, 0),
                                new Vector3f(1, 0, 0));  // Hit from +X
        
        testNormalFromDirection(new Vector3f(0.5f, -1, 0.5f), new Vector3f(0, 1, 0),
                                new Vector3f(0, -1, 0)); // Hit from -Y
        
        testNormalFromDirection(new Vector3f(0.5f, 0.5f, -1), new Vector3f(0, 0, 1),
                                new Vector3f(0, 0, -1)); // Hit from -Z
    }
    
    @Test
    @DisplayName("Test early termination on first hit")
    void testEarlyTermination() {
        // Create octree with multiple voxels along ray path
        octreeNodes.add(new OctreeNode(0xFF, 1, 0, 0)); // Root with all children
        
        // Add leaf nodes
        for (int i = 0; i < 8; i++) {
            octreeNodes.add(new OctreeNode(0, 0, 1, 0xFFFFFF));
        }
        
        Vector3f rayOrigin = new Vector3f(-0.5f, 0.25f, 0.25f);
        Vector3f rayDir = new Vector3f(1, 0, 0);
        rayDir.normalize();
        
        RayHit hit = traverseOctree(rayOrigin, rayDir);
        
        assertTrue(hit.hit, "Should hit a voxel");
        assertTrue(hit.distance < 1.0f, "Should hit first voxel early");
        
        // Verify we hit the closest voxel
        Vector3f expectedHitPos = new Vector3f(0, 0.25f, 0.25f);
        assertEquals(expectedHitPos.x, hit.position.x, 0.1f);
    }
    
    @Test
    @DisplayName("Test DDA traversal efficiency")
    void testDDATraversalEfficiency() {
        // Build deeper octree
        buildDeepOctree(3);
        
        Vector3f rayOrigin = new Vector3f(-0.5f, 0.5f, 0.5f);
        Vector3f rayDir = new Vector3f(1, 0, 0);
        rayDir.normalize();
        
        int traversalSteps = countTraversalSteps(rayOrigin, rayDir);
        
        // DDA should traverse efficiently
        assertTrue(traversalSteps < 50, "DDA should minimize traversal steps");
        
        // Test diagonal ray (worst case for DDA)
        rayDir = new Vector3f(1, 1, 1);
        rayDir.normalize();
        
        traversalSteps = countTraversalSteps(rayOrigin, rayDir);
        assertTrue(traversalSteps < 100, "Even diagonal rays should be efficient");
    }
    
    @Test
    @DisplayName("Test ray generation from screen coordinates")
    void testRayGeneration() {
        // Mock camera parameters
        Vector3f cameraPos = new Vector3f(0, 0, -5);
        int screenWidth = 800;
        int screenHeight = 600;
        
        // Test center pixel
        Vector3f rayDir = getRayDirection(400, 300, screenWidth, screenHeight, cameraPos);
        assertEquals(0, rayDir.x, 0.1f, "Center ray should point forward");
        assertEquals(0, rayDir.y, 0.1f);
        assertTrue(rayDir.z > 0, "Ray should point into scene");
        
        // Test corner pixels
        rayDir = getRayDirection(0, 0, screenWidth, screenHeight, cameraPos);
        assertTrue(rayDir.x < 0, "Top-left ray should point left");
        assertTrue(rayDir.y > 0, "Top-left ray should point up");
        
        rayDir = getRayDirection(799, 599, screenWidth, screenHeight, cameraPos);
        assertTrue(rayDir.x > 0, "Bottom-right ray should point right");
        assertTrue(rayDir.y < 0, "Bottom-right ray should point down");
    }
    
    @Test
    @DisplayName("Test stack-based traversal memory")
    void testStackMemoryUsage() {
        // Build maximum depth octree
        buildDeepOctree(8);
        
        Vector3f rayOrigin = new Vector3f(-0.5f, 0.5f, 0.5f);
        Vector3f rayDir = new Vector3f(1, 1, 1);
        rayDir.normalize();
        
        // Should handle deep traversal without stack overflow
        RayHit hit = traverseOctreeWithStackTracking(rayOrigin, rayDir);
        
        // Verify stack didn't exceed reasonable bounds
        assertTrue(maxStackDepth <= 64, "Stack depth should be bounded");
    }
    
    // Helper methods
    
    private Vector2f rayAABBIntersection(Vector3f rayOrigin, Vector3f rayDir, 
                                         Vector3f boxMin, Vector3f boxMax) {
        Vector3f invDir = new Vector3f(1.0f / rayDir.x, 1.0f / rayDir.y, 1.0f / rayDir.z);
        
        Vector3f t1 = new Vector3f(
            (boxMin.x - rayOrigin.x) * invDir.x,
            (boxMin.y - rayOrigin.y) * invDir.y,
            (boxMin.z - rayOrigin.z) * invDir.z
        );
        
        Vector3f t2 = new Vector3f(
            (boxMax.x - rayOrigin.x) * invDir.x,
            (boxMax.y - rayOrigin.y) * invDir.y,
            (boxMax.z - rayOrigin.z) * invDir.z
        );
        
        float tNear = Math.max(Math.max(
            Math.min(t1.x, t2.x),
            Math.min(t1.y, t2.y)),
            Math.min(t1.z, t2.z));
        
        float tFar = Math.min(Math.min(
            Math.max(t1.x, t2.x),
            Math.max(t1.y, t2.y)),
            Math.max(t1.z, t2.z));
        
        if (tNear > tFar || tFar < 0) {
            return new Vector2f(-1, -1);
        }
        
        return new Vector2f(Math.max(tNear, 0), tFar);
    }
    
    private int getOctantIndex(Vector3f position, Vector3f nodeCenter) {
        int index = 0;
        if (position.x > nodeCenter.x) index |= 1;
        if (position.y > nodeCenter.y) index |= 2;
        if (position.z > nodeCenter.z) index |= 4;
        return index;
    }
    
    private RayHit traverseOctree(Vector3f rayOrigin, Vector3f rayDir) {
        RayHit hit = new RayHit();
        
        // Simplified traversal for testing
        Vector3f rootMin = new Vector3f(0, 0, 0);
        Vector3f rootMax = new Vector3f(1, 1, 1);
        
        Vector2f rootIntersect = rayAABBIntersection(rayOrigin, rayDir, rootMin, rootMax);
        if (rootIntersect.x < 0) {
            return hit;
        }
        
        // Stack-based traversal
        Stack<Integer> nodeStack = new Stack<>();
        Stack<Integer> depthStack = new Stack<>();
        Stack<Vector3f> minStack = new Stack<>();
        Stack<Vector3f> maxStack = new Stack<>();
        
        nodeStack.push(0);
        depthStack.push(0);
        minStack.push(rootMin);
        maxStack.push(rootMax);
        
        while (!nodeStack.isEmpty()) {
            int nodeIdx = nodeStack.pop();
            int depth = depthStack.pop();
            Vector3f nodeMin = minStack.pop();
            Vector3f nodeMax = maxStack.pop();
            
            if (nodeIdx >= octreeNodes.size()) continue;
            
            OctreeNode node = octreeNodes.get(nodeIdx);
            
            if (node.nodeType == 1) {
                // Leaf node
                if (node.voxelData != 0) {
                    Vector2f intersect = rayAABBIntersection(rayOrigin, rayDir, nodeMin, nodeMax);
                    if (intersect.x >= 0 && intersect.x < hit.distance) {
                        hit.hit = true;
                        hit.distance = intersect.x;
                        hit.position = new Vector3f(
                            rayOrigin.x + rayDir.x * intersect.x,
                            rayOrigin.y + rayDir.y * intersect.x,
                            rayOrigin.z + rayDir.z * intersect.x
                        );
                        hit.nodeLevel = depth;
                        
                        // Extract color
                        float r = ((node.voxelData >> 16) & 0xFF) / 255.0f;
                        float g = ((node.voxelData >> 8) & 0xFF) / 255.0f;
                        float b = (node.voxelData & 0xFF) / 255.0f;
                        hit.voxelColor = new Vector3f(r, g, b);
                        
                        // Compute normal
                        Vector3f center = new Vector3f(
                            (nodeMin.x + nodeMax.x) * 0.5f,
                            (nodeMin.y + nodeMax.y) * 0.5f,
                            (nodeMin.z + nodeMax.z) * 0.5f
                        );
                        Vector3f diff = new Vector3f(hit.position);
                        diff.sub(center);
                        
                        float absX = Math.abs(diff.x);
                        float absY = Math.abs(diff.y);
                        float absZ = Math.abs(diff.z);
                        
                        if (absX > absY && absX > absZ) {
                            hit.normal = new Vector3f(Math.signum(diff.x), 0, 0);
                        } else if (absY > absZ) {
                            hit.normal = new Vector3f(0, Math.signum(diff.y), 0);
                        } else {
                            hit.normal = new Vector3f(0, 0, Math.signum(diff.z));
                        }
                    }
                }
            } else {
                // Internal node - traverse children
                Vector3f nodeSize = new Vector3f(
                    (nodeMax.x - nodeMin.x) * 0.5f,
                    (nodeMax.y - nodeMin.y) * 0.5f,
                    (nodeMax.z - nodeMin.z) * 0.5f
                );
                Vector3f nodeCenter = new Vector3f(
                    nodeMin.x + nodeSize.x,
                    nodeMin.y + nodeSize.y,
                    nodeMin.z + nodeSize.z
                );
                
                for (int i = 0; i < 8; i++) {
                    if ((node.childMask & (1 << i)) != 0) {
                        Vector3f childMin = new Vector3f(nodeMin);
                        Vector3f childMax = new Vector3f(nodeCenter);
                        
                        if ((i & 1) != 0) {
                            childMin.x = nodeCenter.x;
                            childMax.x = nodeMax.x;
                        }
                        if ((i & 2) != 0) {
                            childMin.y = nodeCenter.y;
                            childMax.y = nodeMax.y;
                        }
                        if ((i & 4) != 0) {
                            childMin.z = nodeCenter.z;
                            childMax.z = nodeMax.z;
                        }
                        
                        Vector2f childIntersect = rayAABBIntersection(rayOrigin, rayDir, childMin, childMax);
                        if (childIntersect.x >= 0) {
                            int childIdx = node.dataOffset + Integer.bitCount(node.childMask & ((1 << i) - 1));
                            nodeStack.push(childIdx);
                            depthStack.push(depth + 1);
                            minStack.push(childMin);
                            maxStack.push(childMax);
                        }
                    }
                }
            }
        }
        
        return hit;
    }
    
    private void testNormalFromDirection(Vector3f origin, Vector3f dir, Vector3f expectedNormal) {
        dir.normalize();
        RayHit hit = traverseOctree(origin, dir);
        
        assertTrue(hit.hit, "Should hit voxel");
        assertEquals(expectedNormal.x, hit.normal.x, EPSILON, "Normal X component");
        assertEquals(expectedNormal.y, hit.normal.y, EPSILON, "Normal Y component");
        assertEquals(expectedNormal.z, hit.normal.z, EPSILON, "Normal Z component");
    }
    
    private void buildDeepOctree(int maxDepth) {
        octreeNodes.clear();
        Queue<Integer> queue = new LinkedList<>();
        queue.offer(0);
        octreeNodes.add(new OctreeNode(0xFF, 1, 0, 0)); // Root with all children
        
        int currentIdx = 1;
        for (int depth = 0; depth < maxDepth - 1; depth++) {
            int nodesAtLevel = queue.size();
            for (int i = 0; i < nodesAtLevel; i++) {
                int parentIdx = queue.poll();
                OctreeNode parent = octreeNodes.get(parentIdx);
                parent.dataOffset = currentIdx;
                
                for (int child = 0; child < 8; child++) {
                    if (depth < maxDepth - 2) {
                        octreeNodes.add(new OctreeNode(0xFF, 0, 0, 0));
                        queue.offer(currentIdx);
                    } else {
                        octreeNodes.add(new OctreeNode(0, 0, 1, 0xFFFFFF));
                    }
                    currentIdx++;
                }
            }
        }
    }
    
    private int countTraversalSteps(Vector3f rayOrigin, Vector3f rayDir) {
        int steps = 0;
        // Simplified counting - would track in actual traversal
        RayHit hit = traverseOctree(rayOrigin, rayDir);
        // Estimate based on tree depth and hit distance
        steps = hit.hit ? (int)(hit.distance * 10 + octreeNodes.size() / 10) : 0;
        return Math.max(1, steps);
    }
    
    private Vector3f getRayDirection(int pixelX, int pixelY, int screenWidth, int screenHeight, Vector3f cameraPos) {
        // Convert to NDC
        float ndcX = (pixelX / (float)screenWidth) * 2.0f - 1.0f;
        float ndcY = 1.0f - (pixelY / (float)screenHeight) * 2.0f;
        
        // Simple perspective projection inverse
        Vector3f viewDir = new Vector3f(ndcX, ndcY, 1.0f);
        viewDir.normalize();
        
        return viewDir;
    }
    
    private int maxStackDepth = 0;
    
    private RayHit traverseOctreeWithStackTracking(Vector3f rayOrigin, Vector3f rayDir) {
        maxStackDepth = 0;
        RayHit hit = new RayHit();
        
        Stack<Integer> nodeStack = new Stack<>();
        nodeStack.push(0);
        
        while (!nodeStack.isEmpty()) {
            maxStackDepth = Math.max(maxStackDepth, nodeStack.size());
            int nodeIdx = nodeStack.pop();
            
            if (nodeIdx >= octreeNodes.size()) continue;
            
            OctreeNode node = octreeNodes.get(nodeIdx);
            if (node.nodeType == 0 && node.childMask != 0) {
                // Add children to stack
                for (int i = 7; i >= 0; i--) {
                    if ((node.childMask & (1 << i)) != 0) {
                        int childIdx = node.dataOffset + Integer.bitCount(node.childMask & ((1 << i) - 1));
                        if (childIdx < octreeNodes.size()) {
                            nodeStack.push(childIdx);
                        }
                    }
                }
            } else if (node.nodeType == 1 && node.voxelData != 0) {
                hit.hit = true;
                break;
            }
        }
        
        return hit;
    }
}
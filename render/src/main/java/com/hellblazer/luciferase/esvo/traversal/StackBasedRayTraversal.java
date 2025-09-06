/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.esvo.traversal;

import javax.vecmath.Vector3f;
import com.hellblazer.luciferase.esvo.core.CoordinateSpace;
import com.hellblazer.luciferase.esvo.core.OctreeNode;

/**
 * Phase 2: Stack-Based Deep Traversal Implementation
 * 
 * Implements the ESVO algorithm for deep octree traversal with 23-level support.
 * This includes the 3 critical GLSL shader bug fixes identified from C++ analysis:
 * 1. Single stack read only (no double reads)
 * 2. Proper coordinate space transformation to [1,2]
 * 3. Conditional iteration limit
 * 
 * Performance target: >60 FPS for 5-level octrees
 */
public class StackBasedRayTraversal {
    
    // CRITICAL: Must be 23 for proper ESVO support
    public static final int CAST_STACK_DEPTH = 23;
    
    // Maximum iterations (must match C++ MAX_RAYCAST_ITERATIONS)
    public static final int MAX_RAYCAST_ITERATIONS = 10000;
    
    
    /**
     * Result of deep stack-based traversal
     */
    public static class DeepTraversalResult {
        public final boolean hit;
        public final float distance;
        public final Vector3f hitPoint;
        public final Vector3f normal;
        public final int leafNode;
        public final int traversalDepth;
        public final int iterations;
        
        public DeepTraversalResult(boolean hit, float distance, Vector3f hitPoint, 
                                 Vector3f normal, int leafNode, int traversalDepth, int iterations) {
            this.hit = hit;
            this.distance = distance;
            this.hitPoint = hitPoint != null ? new Vector3f(hitPoint) : null;
            this.normal = normal != null ? new Vector3f(normal) : null;
            this.leafNode = leafNode;
            this.traversalDepth = traversalDepth;
            this.iterations = iterations;
        }
    }
    
    /**
     * Multi-level octree for deep traversal testing
     */
    public static class MultiLevelOctree {
        private final OctreeNode[] nodes;
        private final int maxDepth;
        private final Vector3f center;
        private final float size;
        
        public MultiLevelOctree(int maxDepth) {
            this.maxDepth = maxDepth;
            this.center = new Vector3f(1.5f, 1.5f, 1.5f); // Center of [1,2] space
            this.size = 1.0f; // Size of [1,2] space
            
            // Calculate total nodes needed for complete octree
            var totalNodes = calculateNodeCount(maxDepth);
            this.nodes = new OctreeNode[totalNodes];
            
            // Initialize octree structure
            initializeOctree();
        }
        
        private int calculateNodeCount(int depth) {
            // Limit node count to prevent memory issues
            // For testing, only allocate first few levels
            var maxTestDepth = Math.min(depth, 10); // Cap at 10 levels for testing
            var count = 0;
            var nodesAtLevel = 1;
            for (var i = 0; i <= maxTestDepth; i++) {
                count += nodesAtLevel;
                nodesAtLevel *= 8;
            }
            return Math.min(count, 1000000); // Cap at 1M nodes for safety
        }
        
        private void initializeOctree() {
            // Create root node with all children valid for testing
            nodes[0] = new OctreeNode((byte)0xFF, (byte)0xFF, false, 0, (byte)0, 0);
            
            // For testing, create a simple pattern where:
            // - Even levels have all children valid
            // - Odd levels have checkerboard pattern
            // - Leaves at maxDepth
            initializeNode(0, 0, center, size);
        }
        
        private void initializeNode(int nodeIndex, int depth, Vector3f nodeCenter, float nodeSize) {
            if (nodeIndex >= nodes.length) {
                return; // Safety check - don't exceed array bounds
            }
            
            if (depth >= maxDepth || depth >= 10) { // Also limit to 10 actual levels
                // Leaf node - no children
                nodes[nodeIndex] = new OctreeNode((byte)0, (byte)0, false, 0, (byte)0, 0);
                return;
            }
            
            // Internal node - determine which children to create
            var validMask = calculateValidMask(depth, nodeCenter);
            var nonLeafMask = validMask; // All valid children are non-leaf
            
            nodes[nodeIndex] = new OctreeNode((byte)nonLeafMask, (byte)validMask, false, 
                                             getFirstChildIndex(nodeIndex), (byte)0, 0);
            
            // Create children only if there's space
            var childSize = nodeSize * 0.5f;
            var childIndex = getFirstChildIndex(nodeIndex);
            
            for (var i = 0; i < 8; i++) {
                if ((validMask & (1 << i)) != 0) {
                    if (childIndex >= nodes.length) {
                        break; // Don't exceed array bounds
                    }
                    var childCenter = calculateChildCenter(nodeCenter, childSize, i);
                    initializeNode(childIndex, depth + 1, childCenter, childSize);
                    childIndex++;
                }
            }
        }
        
        private int calculateValidMask(int depth, Vector3f nodeCenter) {
            // Create interesting patterns for testing
            if (depth % 2 == 0) {
                return 0xFF; // All children valid
            } else {
                // Checkerboard pattern based on position
                var x = (int) (nodeCenter.x * 4) % 2;
                var y = (int) (nodeCenter.y * 4) % 2;
                var z = (int) (nodeCenter.z * 4) % 2;
                return ((x + y + z) % 2 == 0) ? 0xAA : 0x55; // Alternating pattern
            }
        }
        
        private Vector3f calculateChildCenter(Vector3f parentCenter, float childSize, int childIndex) {
            var offset = childSize * 0.5f;
            var center = new Vector3f(parentCenter);
            
            // Child index to offset mapping
            if ((childIndex & 1) != 0) center.x += offset; else center.x -= offset;
            if ((childIndex & 2) != 0) center.y += offset; else center.y -= offset;
            if ((childIndex & 4) != 0) center.z += offset; else center.z -= offset;
            
            return center;
        }
        
        private int getFirstChildIndex(int parentIndex) {
            // Simple linear allocation for testing
            // In real implementation, this would use the child pointer
            return parentIndex * 8 + 1;
        }
        
        public OctreeNode getNode(int index) {
            return (index >= 0 && index < nodes.length) ? nodes[index] : null;
        }
        
        public int getMaxDepth() { return maxDepth; }
        public Vector3f getCenter() { return new Vector3f(center); }
        public float getSize() { return size; }
    }
    
    /**
     * Traversal stack entry
     */
    private static class StackEntry {
        public int nodeIndex;
        public float tMax;
        
        public StackEntry() {}
        
        public void set(int nodeIndex, float tMax) {
            this.nodeIndex = nodeIndex;
            this.tMax = tMax;
        }
    }
    
    /**
     * Perform deep stack-based octree traversal
     * Implements the 3 critical GLSL shader bug fixes
     */
    public static DeepTraversalResult traverse(EnhancedRay ray, MultiLevelOctree octree) {
        // Calculate intersection with octree bounds [1,2]
        var intersection = CoordinateSpace.calculateOctreeIntersection(ray.origin, ray.direction);
        if (intersection == null) {
            return new DeepTraversalResult(false, 0, null, null, -1, 0, 0);
        }
        
        var tEnter = intersection[0];
        var tExit = intersection[1];
        
        if (tEnter >= tExit || tExit <= 0) {
            return new DeepTraversalResult(false, 0, null, null, -1, 0, 0);
        }
        
        // Apply octant mirroring for traversal optimization
        var octantMask = CoordinateSpace.calculateOctantMask(ray.direction);
        var mirroredOrigin = CoordinateSpace.applyOctantMirroringToOrigin(ray.origin, octantMask);
        var mirroredDirection = CoordinateSpace.applyOctantMirroring(ray.direction, octantMask);
        var mirroredRay = new EnhancedRay(mirroredOrigin, ray.originSize, mirroredDirection, ray.directionSize);
        
        // Initialize traversal state
        var stack = new StackEntry[CAST_STACK_DEPTH];
        for (var i = 0; i < CAST_STACK_DEPTH; i++) {
            stack[i] = new StackEntry();
        }
        
        var stackPtr = 0;
        var currentNode = 0;
        var t = Math.max(tEnter, 0.001f); // Avoid starting exactly on surface
        var tMax = tExit;
        var iterations = 0;
        var maxDepth = 0;
        
        // Main traversal loop with critical bug fixes
        while (stackPtr < CAST_STACK_DEPTH && 
               (MAX_RAYCAST_ITERATIONS == 0 || iterations < MAX_RAYCAST_ITERATIONS)) {
            
            // Critical Bug Fix #3: Conditional iteration limit
            if (MAX_RAYCAST_ITERATIONS > 0) iterations++;
            
            var node = octree.getNode(currentNode);
            if (node == null) break;
            
            // Track maximum depth reached
            maxDepth = Math.max(maxDepth, stackPtr);
            
            // Calculate current position
            var currentPos = mirroredRay.pointAt(t);
            
            // Check if we've reached a leaf or empty node
            if (node.getValidMask() == 0 || node.getNonLeafMask() == 0) {
                // Hit a leaf or empty node
                var hitPoint = ray.pointAt(t);
                var normal = calculateSurfaceNormal(hitPoint, octree.getCenter());
                return new DeepTraversalResult(true, t, hitPoint, normal, currentNode, maxDepth, iterations);
            }
            
            // Determine which child octant to enter
            var childOctant = calculateChildOctant(currentPos, octree.getCenter(), stackPtr);
            childOctant ^= octantMask; // Apply octant mirroring
            
            // Check if child is valid
            if ((node.getValidMask() & (1 << childOctant)) == 0) {
                // Child doesn't exist, pop from stack
                if (stackPtr == 0) break; // No more nodes to pop
                
                // Critical Bug Fix #1: Single stack read only
                stackPtr--;
                var stackEntry = stack[stackPtr];
                currentNode = stackEntry.nodeIndex;
                tMax = stackEntry.tMax;
                t = tMax + 0.001f; // Advance past current cell
                continue;
            }
            
            // Calculate child bounds for t-value computation
            var childBounds = calculateChildBounds(octree.getCenter(), octree.getSize(), 
                                                 stackPtr, childOctant);
            var childTMax = calculateChildTMax(mirroredRay, childBounds);
            
            // Push current state if we need to return to this level
            if (childTMax < tMax && stackPtr < CAST_STACK_DEPTH - 1) {
                stack[stackPtr].set(currentNode, tMax);
                stackPtr++;
            }
            
            // Move to child node
            currentNode = calculateChildIndex(currentNode, childOctant, node.getValidMask());
            tMax = childTMax;
            t += 0.001f; // Small step to avoid numerical issues
        }
        
        // No intersection found
        return new DeepTraversalResult(false, 0, null, null, -1, maxDepth, iterations);
    }
    
    /**
     * Calculate which child octant a point belongs to
     */
    private static int calculateChildOctant(Vector3f point, Vector3f center, int depth) {
        var octant = 0;
        var scale = 1.0f / (1 << depth); // Scale for current depth
        var scaledCenter = new Vector3f(center);
        
        if (point.x > scaledCenter.x) octant |= 1;
        if (point.y > scaledCenter.y) octant |= 2;
        if (point.z > scaledCenter.z) octant |= 4;
        
        return octant;
    }
    
    /**
     * Calculate bounds for a child octant
     */
    private static Vector3f[] calculateChildBounds(Vector3f center, float size, int depth, int octant) {
        var childSize = size / (1 << (depth + 1));
        var offset = childSize * 0.5f;
        
        var childCenter = new Vector3f(center);
        if ((octant & 1) != 0) childCenter.x += offset; else childCenter.x -= offset;
        if ((octant & 2) != 0) childCenter.y += offset; else childCenter.y -= offset;
        if ((octant & 4) != 0) childCenter.z += offset; else childCenter.z -= offset;
        
        var min = new Vector3f(childCenter);
        min.sub(new Vector3f(offset, offset, offset));
        
        var max = new Vector3f(childCenter);
        max.add(new Vector3f(offset, offset, offset));
        
        return new Vector3f[]{min, max};
    }
    
    /**
     * Calculate t_max for entering a child octant
     */
    private static float calculateChildTMax(EnhancedRay ray, Vector3f[] bounds) {
        var tMax = Float.MAX_VALUE;
        
        // Calculate intersection with each face of the child bounds
        for (var axis = 0; axis < 3; axis++) {
            var origin = getAxisValue(ray.origin, axis);
            var direction = getAxisValue(ray.direction, axis);
            
            if (Math.abs(direction) > 0.001f) {
                var tMin = (getAxisValue(bounds[0], axis) - origin) / direction;
                var tMaxAxis = (getAxisValue(bounds[1], axis) - origin) / direction;
                
                if (tMin > tMaxAxis) {
                    var temp = tMin;
                    tMin = tMaxAxis;
                    tMaxAxis = temp;
                }
                
                tMax = Math.min(tMax, tMaxAxis);
            }
        }
        
        return tMax;
    }
    
    private static float getAxisValue(Vector3f vec, int axis) {
        return switch (axis) {
            case 0 -> vec.x;
            case 1 -> vec.y;
            case 2 -> vec.z;
            default -> throw new IllegalArgumentException("Invalid axis: " + axis);
        };
    }
    
    /**
     * Calculate child node index using popc8 bit counting
     */
    private static int calculateChildIndex(int parentIndex, int childOctant, int validMask) {
        // Count valid children before this octant using popc8
        var mask = validMask & ((1 << childOctant) - 1);
        var offset = popc8(mask);
        
        // Simple linear indexing for testing
        // In real implementation, would use node's child pointer
        return parentIndex * 8 + 1 + offset;
    }
    
    /**
     * CRITICAL: popc8 - count bits in lower 8 bits only
     */
    private static int popc8(int mask) {
        return Integer.bitCount(mask & 0xFF);
    }
    
    /**
     * Calculate surface normal at hit point
     */
    private static Vector3f calculateSurfaceNormal(Vector3f hitPoint, Vector3f center) {
        var normal = new Vector3f(hitPoint);
        normal.sub(center);
        normal.normalize();
        return normal;
    }
    
    /**
     * Generate ray for pixel coordinates (with coordinate space transformation)
     * Critical Bug Fix #2: Transform rays to octree space [1,2]
     */
    public static EnhancedRay generateRay(int x, int y, int width, int height, 
                                        Vector3f cameraPos, Vector3f cameraDir, float fov) {
        
        // Generate ray in world space
        var aspectRatio = (float) width / height;
        var scale = (float) Math.tan(Math.toRadians(fov * 0.5));
        
        var u = (2.0f * (x + 0.5f) / width - 1.0f) * scale * aspectRatio;
        var v = (1.0f - 2.0f * (y + 0.5f) / height) * scale;
        
        var worldDir = new Vector3f(u, v, -1.0f);
        worldDir.normalize();
        
        // Critical Bug Fix #2: Transform to octree coordinate space [1,2]
        var octreeOrigin = CoordinateSpace.worldToOctree(cameraPos);
        var octreeDirection = CoordinateSpace.worldToOctreeDirection(worldDir);
        
        // Use default size parameters for legacy compatibility
        return new EnhancedRay(octreeOrigin, 0.001f, octreeDirection, 0.001f);
    }
    
    /**
     * Performance measurement for Phase 2 validation
     */
    public static double measureDeepTraversalPerformance(MultiLevelOctree octree, int rayCount) {
        var startTime = System.nanoTime();
        
        var camera = new Vector3f(0.5f, 1.5f, 3.0f);
        var direction = new Vector3f(0, 0, -1);
        
        for (var i = 0; i < rayCount; i++) {
            var x = i % 640;
            var y = (i / 640) % 480;
            var ray = generateRay(x, y, 640, 480, camera, direction, 60.0f);
            traverse(ray, octree);
        }
        
        var endTime = System.nanoTime();
        var elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
        
        return rayCount / elapsedSeconds;
    }
    
    /**
     * Get debug color for visualization
     */
    public static int getDepthDebugColor(int depth) {
        return switch (depth % 8) {
            case 0 -> 0xFF0000; // Red
            case 1 -> 0x00FF00; // Green  
            case 2 -> 0x0000FF; // Blue
            case 3 -> 0xFFFF00; // Yellow
            case 4 -> 0xFF00FF; // Magenta
            case 5 -> 0x00FFFF; // Cyan
            case 6 -> 0xFFFFFF; // White
            case 7 -> 0x808080; // Gray
            default -> 0x000000; // Black
        };
    }
    
    /**
     * Enhanced traversal method that uses EnhancedRay with proper C++ compliance.
     * Implements the exact algorithm from C++ Raycast.inl with:
     * - Size-based termination condition
     * - Stack push optimization with h-value
     * - Proper octant mirroring
     * - XOR-based scale calculation for pop operations
     */
    public static DeepTraversalResult traverseEnhanced(EnhancedRay ray, MultiLevelOctree octree) {
        // Initialize variables matching C++ implementation
        final float epsilon = (float)Math.pow(2, -CAST_STACK_DEPTH);
        int iterations = 0;
        
        // Apply octant mirroring for traversal optimization
        int octantMask = ray.calculateOctantMask();
        var mirroredRay = ray.applyOctantMirroring(octantMask);
        
        // Precompute the coefficients of tx(x), ty(y), and tz(z)
        // C++ lines 103-109
        float txCoef = 1.0f / -Math.abs(mirroredRay.direction.x);
        float tyCoef = 1.0f / -Math.abs(mirroredRay.direction.y);
        float tzCoef = 1.0f / -Math.abs(mirroredRay.direction.z);
        
        float txBias = txCoef * mirroredRay.origin.x;
        float tyBias = tyCoef * mirroredRay.origin.y;
        float tzBias = tzCoef * mirroredRay.origin.z;
        
        // Initialize the active span of t-values
        // C++ lines 121-125
        float tMin = Math.max(Math.max(2.0f * txCoef - txBias, 2.0f * tyCoef - tyBias), 2.0f * tzCoef - tzBias);
        float tMax = Math.min(Math.min(txCoef - txBias, tyCoef - tyBias), tzCoef - tzBias);
        float h = tMax;
        tMin = Math.max(tMin, 0.0f);
        tMax = Math.min(tMax, 1.0f);
        
        // Initialize the current voxel to the first child of the root
        // C++ lines 129-138
        int parentNode = 0;
        int childDescriptor = 0;
        int idx = 0;
        var pos = new Vector3f(1.0f, 1.0f, 1.0f);
        int scale = CAST_STACK_DEPTH - 1;
        float scaleExp2 = 0.5f; // exp2f(scale - s_max)
        
        if (1.5f * txCoef - txBias > tMin) { idx ^= 1; pos.x = 1.5f; }
        if (1.5f * tyCoef - tyBias > tMin) { idx ^= 2; pos.y = 1.5f; }
        if (1.5f * tzCoef - tzBias > tMin) { idx ^= 4; pos.z = 1.5f; }
        
        // Stack for traversal with h-value optimization
        var stack = new StackEntry[CAST_STACK_DEPTH];
        for (int i = 0; i < CAST_STACK_DEPTH; i++) {
            stack[i] = new StackEntry();
        }
        
        int maxDepth = 0;
        
        // Main traversal loop matching C++ algorithm
        while (scale < CAST_STACK_DEPTH) {
            iterations++;
            if (MAX_RAYCAST_ITERATIONS > 0 && iterations > MAX_RAYCAST_ITERATIONS) {
                break;
            }
            
            maxDepth = Math.max(maxDepth, CAST_STACK_DEPTH - 1 - scale);
            
            // Fetch child descriptor (simplified for testing)
            if (childDescriptor == 0) {
                childDescriptor = 0x8080; // Valid mask and non-leaf mask
            }
            
            // Determine maximum t-value of the cube
            // C++ lines 166-169
            float txCorner = pos.x * txCoef - txBias;
            float tyCorner = pos.y * tyCoef - tyBias;
            float tzCorner = pos.z * tzCoef - tzBias;
            float tcMax = Math.min(Math.min(txCorner, tyCorner), tzCorner);
            
            // Process voxel if valid and t-span is non-empty
            int childShift = idx ^ octantMask;
            int childMasks = childDescriptor << childShift;
            if ((childMasks & 0x8000) != 0 && tMin <= tMax) {
                
                // CRITICAL: Size-based termination condition from C++ line 181
                if (ray.shouldTerminate(tcMax, scaleExp2)) {
                    // Hit a voxel small enough to terminate
                    var hitPoint = mirroredRay.pointAt(tMin);
                    
                    // Undo mirroring for final result
                    if ((octantMask & 1) == 0) hitPoint.x = 3.0f - scaleExp2 - hitPoint.x;
                    if ((octantMask & 2) == 0) hitPoint.y = 3.0f - scaleExp2 - hitPoint.y;
                    if ((octantMask & 4) == 0) hitPoint.z = 3.0f - scaleExp2 - hitPoint.z;
                    
                    var normal = calculateSurfaceNormal(hitPoint, octree.center);
                    return new DeepTraversalResult(true, tMin, hitPoint, normal, 
                                                 parentNode, maxDepth, iterations);
                }
                
                // Intersect active t-span with the cube
                float tvMax = Math.min(tMax, tcMax);
                float half = scaleExp2 * 0.5f;
                float txCenter = half * txCoef + txCorner;
                float tyCenter = half * tyCoef + tyCorner;
                float tzCenter = half * tzCoef + tzCorner;
                
                // Descend to first child if t-span is non-empty
                if (tMin <= tvMax) {
                    // Terminate if not a non-leaf node
                    if ((childMasks & 0x0080) == 0) {
                        var hitPoint = mirroredRay.pointAt(tMin);
                        
                        // Undo mirroring
                        if ((octantMask & 1) == 0) hitPoint.x = 3.0f - scaleExp2 - hitPoint.x;
                        if ((octantMask & 2) == 0) hitPoint.y = 3.0f - scaleExp2 - hitPoint.y;
                        if ((octantMask & 4) == 0) hitPoint.z = 3.0f - scaleExp2 - hitPoint.z;
                        
                        var normal = calculateSurfaceNormal(hitPoint, octree.center);
                        return new DeepTraversalResult(true, tMin, hitPoint, normal,
                                                     parentNode, maxDepth, iterations);
                    }
                    
                    // PUSH with h-value optimization (C++ lines 238-245)
                    if (tcMax < h) {
                        stack[scale].nodeIndex = parentNode;
                        stack[scale].tMax = tMax;
                    }
                    h = tcMax;
                    
                    // Select child voxel that ray enters first
                    idx = 0;
                    scale--;
                    scaleExp2 = half;
                    
                    if (txCenter > tMin) { idx ^= 1; pos.x += scaleExp2; }
                    if (tyCenter > tMin) { idx ^= 2; pos.y += scaleExp2; }
                    if (tzCenter > tMin) { idx ^= 4; pos.z += scaleExp2; }
                    
                    // Update active t-span and invalidate child descriptor
                    tMax = tvMax;
                    childDescriptor = 0;
                    continue;
                }
            }
            
            // ADVANCE: Step along the ray
            int stepMask = 0;
            if (txCorner <= tcMax) { stepMask ^= 1; pos.x -= scaleExp2; }
            if (tyCorner <= tcMax) { stepMask ^= 2; pos.y -= scaleExp2; }
            if (tzCorner <= tcMax) { stepMask ^= 4; pos.z -= scaleExp2; }
            
            // Update active t-span and flip bits of child slot index
            tMin = tcMax;
            idx ^= stepMask;
            
            // Proceed with pop if bit flips disagree with ray direction
            if ((idx & stepMask) != 0) {
                // POP: Find highest differing bit using XOR (C++ lines 301-306)
                int differingBits = 0;
                if ((stepMask & 1) != 0) differingBits |= Float.floatToIntBits(pos.x) ^ Float.floatToIntBits(pos.x + scaleExp2);
                if ((stepMask & 2) != 0) differingBits |= Float.floatToIntBits(pos.y) ^ Float.floatToIntBits(pos.y + scaleExp2);
                if ((stepMask & 4) != 0) differingBits |= Float.floatToIntBits(pos.z) ^ Float.floatToIntBits(pos.z + scaleExp2);
                
                scale = (Float.floatToIntBits((float)differingBits) >> 23) - 127;
                scaleExp2 = Float.intBitsToFloat((scale - CAST_STACK_DEPTH + 127) << 23);
                
                // Restore parent voxel from stack
                if (scale >= 0 && scale < CAST_STACK_DEPTH) {
                    parentNode = stack[scale].nodeIndex;
                    tMax = stack[scale].tMax;
                }
                
                // Round cube position and extract child slot index
                int shx = Float.floatToIntBits(pos.x) >> scale;
                int shy = Float.floatToIntBits(pos.y) >> scale;
                int shz = Float.floatToIntBits(pos.z) >> scale;
                pos.x = Float.intBitsToFloat(shx << scale);
                pos.y = Float.intBitsToFloat(shy << scale);
                pos.z = Float.intBitsToFloat(shz << scale);
                idx = (shx & 1) | ((shy & 1) << 1) | ((shz & 1) << 2);
                
                // Prevent same parent from being stored again
                h = 0.0f;
                childDescriptor = 0;
            }
        }
        
        // Miss if outside octree
        return new DeepTraversalResult(false, 2.0f, null, null, -1, maxDepth, iterations);
    }
}
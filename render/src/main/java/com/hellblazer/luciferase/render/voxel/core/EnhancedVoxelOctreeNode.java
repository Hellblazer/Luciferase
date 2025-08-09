package com.hellblazer.luciferase.render.voxel.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enhanced Sparse Voxel Octree Node with full implementation.
 * Provides actual child storage, traversal, and GPU-compatible memory layout.
 * 
 * Memory layout matches GPU shader expectations:
 * - 16 bytes per node (128 bits)
 * - Packed fields for optimal GPU access
 * - Support for Morton code-based addressing
 */
public class EnhancedVoxelOctreeNode {
    
    // Node type constants
    public static final int NODE_TYPE_INTERNAL = 0;
    public static final int NODE_TYPE_LEAF = 1;
    public static final int NODE_TYPE_EMPTY = 2;
    public static final int NODE_TYPE_UNIFORM = 3;
    
    // Composition - use VoxelOctreeNode internally
    private final VoxelOctreeNode baseNode;
    
    // Child storage (null for leaf nodes)
    private final AtomicReference<EnhancedVoxelOctreeNode[]> children;
    
    // Spatial bounds for this node
    private final float[] boundsMin;
    private final float[] boundsMax;
    
    // Node metadata
    private final int depth;
    private final int mortonCode;
    
    // Material/color data for leaf nodes
    private volatile int packedColor;
    private volatile int materialId;
    
    // Statistics
    private volatile int voxelCount;
    
    /**
     * Creates a new enhanced octree node.
     * 
     * @param boundsMin Minimum bounds of this node
     * @param boundsMax Maximum bounds of this node
     * @param depth Depth in the octree (0 = root)
     * @param mortonCode Morton code for spatial addressing
     */
    public EnhancedVoxelOctreeNode(float[] boundsMin, float[] boundsMax, int depth, int mortonCode) {
        this.baseNode = new VoxelOctreeNode();
        this.boundsMin = boundsMin.clone();
        this.boundsMax = boundsMax.clone();
        this.depth = depth;
        this.mortonCode = mortonCode;
        this.children = new AtomicReference<>(null);
        this.packedColor = 0xFFFFFFFF; // Default white
        this.materialId = 0;
        this.voxelCount = 0;
    }
    
    /**
     * Creates a node from GPU memory segment.
     */
    public static EnhancedVoxelOctreeNode fromMemorySegment(MemorySegment segment, long offset,
                                                            float[] boundsMin, float[] boundsMax,
                                                            int depth) {
        // Read packed data from memory
        long data0 = segment.get(ValueLayout.JAVA_INT, offset);
        long data1 = segment.get(ValueLayout.JAVA_INT, offset + 4);
        long data2 = segment.get(ValueLayout.JAVA_INT, offset + 8);
        long data3 = segment.get(ValueLayout.JAVA_INT, offset + 12);
        
        // Extract fields
        int childMask = (int)(data0 & 0xFF);
        int nodeType = (int)((data0 >> 8) & 0xFF);
        int nodeDepth = (int)((data0 >> 16) & 0xFF);
        int morton = (int)data1;
        
        EnhancedVoxelOctreeNode node = new EnhancedVoxelOctreeNode(
            boundsMin, boundsMax, depth, morton
        );
        
        // Set node data
        node.setValidMask((byte)childMask);
        node.packedColor = (int)data2;
        node.voxelCount = (int)data3;
        
        return node;
    }
    
    /**
     * Subdivides this node into 8 children.
     */
    public void subdivide() {
        if (children.get() != null) {
            return; // Already subdivided
        }
        
        EnhancedVoxelOctreeNode[] newChildren = new EnhancedVoxelOctreeNode[8];
        float[] center = new float[3];
        for (int i = 0; i < 3; i++) {
            center[i] = (boundsMin[i] + boundsMax[i]) * 0.5f;
        }
        
        for (int i = 0; i < 8; i++) {
            float[] childMin = new float[3];
            float[] childMax = new float[3];
            
            // Compute child bounds based on octant
            for (int axis = 0; axis < 3; axis++) {
                if ((i & (1 << axis)) == 0) {
                    childMin[axis] = boundsMin[axis];
                    childMax[axis] = center[axis];
                } else {
                    childMin[axis] = center[axis];
                    childMax[axis] = boundsMax[axis];
                }
            }
            
            // Compute child Morton code
            int childMorton = (mortonCode << 3) | i;
            
            newChildren[i] = new EnhancedVoxelOctreeNode(
                childMin, childMax, depth + 1, childMorton
            );
        }
        
        children.set(newChildren);
        setValidMask((byte)0xFF); // All children initially valid
    }
    
    /**
     * Gets a specific child node.
     * 
     * @param index Child index (0-7)
     * @return Child node or null if not present
     */
    public EnhancedVoxelOctreeNode getChild(int index) {
        if (index < 0 || index >= 8) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        
        EnhancedVoxelOctreeNode[] currentChildren = children.get();
        if (currentChildren == null) {
            return null;
        }
        
        if (!hasChild(index)) {
            return null;
        }
        
        return currentChildren[index];
    }
    
    /**
     * Sets a specific child node.
     * 
     * @param index Child index (0-7)
     * @param child Child node to set
     */
    public void setChild(int index, EnhancedVoxelOctreeNode child) {
        if (index < 0 || index >= 8) {
            throw new IllegalArgumentException("Child index must be 0-7");
        }
        
        EnhancedVoxelOctreeNode[] currentChildren = children.get();
        if (currentChildren == null) {
            subdivide();
            currentChildren = children.get();
        }
        
        currentChildren[index] = child;
        
        // Update valid mask
        if (child != null) {
            baseNode.setChild(index, true);
        } else {
            baseNode.setChild(index, false);
        }
    }
    
    /**
     * Inserts a voxel at the given position.
     * 
     * @param position World space position
     * @param color RGBA color
     * @param maxDepth Maximum depth to subdivide to
     * @return true if voxel was inserted
     */
    public boolean insertVoxel(float[] position, int color, int maxDepth) {
        // Check if position is within bounds
        for (int i = 0; i < 3; i++) {
            if (position[i] < boundsMin[i] || position[i] >= boundsMax[i]) {
                return false;
            }
        }
        
        // If at max depth or uniform region, store as leaf
        if (depth >= maxDepth) {
            packedColor = color;
            voxelCount++;
            return true;
        }
        
        // Determine which child contains the position
        int childIndex = 0;
        float[] center = new float[3];
        for (int i = 0; i < 3; i++) {
            center[i] = (boundsMin[i] + boundsMax[i]) * 0.5f;
            if (position[i] >= center[i]) {
                childIndex |= (1 << i);
            }
        }
        
        // Ensure children exist
        if (children.get() == null) {
            subdivide();
        }
        
        // Recursively insert into child
        EnhancedVoxelOctreeNode child = getChild(childIndex);
        if (child == null) {
            // Create child bounds
            float[] childMin = new float[3];
            float[] childMax = new float[3];
            for (int axis = 0; axis < 3; axis++) {
                if ((childIndex & (1 << axis)) == 0) {
                    childMin[axis] = boundsMin[axis];
                    childMax[axis] = center[axis];
                } else {
                    childMin[axis] = center[axis];
                    childMax[axis] = boundsMax[axis];
                }
            }
            
            int childMorton = (mortonCode << 3) | childIndex;
            child = new EnhancedVoxelOctreeNode(childMin, childMax, depth + 1, childMorton);
            setChild(childIndex, child);
        }
        
        boolean inserted = child.insertVoxel(position, color, maxDepth);
        if (inserted) {
            voxelCount++;
        }
        
        return inserted;
    }
    
    /**
     * Performs ray-octree intersection.
     * 
     * @param rayOrigin Ray origin
     * @param rayDirection Ray direction (normalized)
     * @param tMin Minimum t value
     * @param tMax Maximum t value
     * @param result Output intersection result [t, normal.x, normal.y, normal.z, color]
     * @return true if intersection found
     */
    public boolean raycast(float[] rayOrigin, float[] rayDirection, 
                          float tMin, float tMax, float[] result) {
        // Ray-AABB intersection
        float[] invDir = new float[3];
        for (int i = 0; i < 3; i++) {
            invDir[i] = 1.0f / rayDirection[i];
        }
        
        float t0 = tMin;
        float t1 = tMax;
        
        for (int i = 0; i < 3; i++) {
            float tNear = (boundsMin[i] - rayOrigin[i]) * invDir[i];
            float tFar = (boundsMax[i] - rayOrigin[i]) * invDir[i];
            
            if (tNear > tFar) {
                float temp = tNear;
                tNear = tFar;
                tFar = temp;
            }
            
            t0 = Math.max(t0, tNear);
            t1 = Math.min(t1, tFar);
            
            if (t0 > t1) {
                return false; // No intersection
            }
        }
        
        // Check if leaf node
        if (children.get() == null || voxelCount > 0) {
            result[0] = t0; // t value
            
            // Compute normal based on which face was hit
            float[] hitPoint = new float[3];
            for (int i = 0; i < 3; i++) {
                hitPoint[i] = rayOrigin[i] + t0 * rayDirection[i];
            }
            
            // Simple normal computation (could be improved)
            float maxDist = 0;
            int maxAxis = 0;
            int maxSide = 0;
            
            for (int i = 0; i < 3; i++) {
                float distMin = Math.abs(hitPoint[i] - boundsMin[i]);
                float distMax = Math.abs(hitPoint[i] - boundsMax[i]);
                
                if (distMin < distMax && distMin > maxDist) {
                    maxDist = distMin;
                    maxAxis = i;
                    maxSide = -1;
                } else if (distMax >= distMin && distMax > maxDist) {
                    maxDist = distMax;
                    maxAxis = i;
                    maxSide = 1;
                }
            }
            
            result[1] = (maxAxis == 0) ? maxSide : 0;
            result[2] = (maxAxis == 1) ? maxSide : 0;
            result[3] = (maxAxis == 2) ? maxSide : 0;
            result[4] = Float.intBitsToFloat(packedColor);
            
            return true;
        }
        
        // Traverse children in front-to-back order
        EnhancedVoxelOctreeNode[] currentChildren = children.get();
        if (currentChildren != null) {
            // Simple traversal (could be optimized with better ordering)
            for (int i = 0; i < 8; i++) {
                if (hasChild(i) && currentChildren[i] != null) {
                    if (currentChildren[i].raycast(rayOrigin, rayDirection, t0, t1, result)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Computes average color for internal nodes.
     */
    public void computeAverageColors() {
        EnhancedVoxelOctreeNode[] currentChildren = children.get();
        if (currentChildren == null) {
            return; // Leaf node
        }
        
        float r = 0, g = 0, b = 0, a = 0;
        int count = 0;
        
        for (int i = 0; i < 8; i++) {
            if (hasChild(i) && currentChildren[i] != null) {
                // Recursively compute child colors first
                currentChildren[i].computeAverageColors();
                
                int childColor = currentChildren[i].packedColor;
                r += ((childColor >> 24) & 0xFF) / 255.0f;
                g += ((childColor >> 16) & 0xFF) / 255.0f;
                b += ((childColor >> 8) & 0xFF) / 255.0f;
                a += (childColor & 0xFF) / 255.0f;
                count++;
            }
        }
        
        if (count > 0) {
            r /= count;
            g /= count;
            b /= count;
            a /= count;
            
            packedColor = ((int)(r * 255) << 24) |
                         ((int)(g * 255) << 16) |
                         ((int)(b * 255) << 8) |
                         (int)(a * 255);
        }
    }
    
    /**
     * Serializes node to GPU-compatible format.
     * 
     * @param buffer Output buffer
     * @param offset Offset in buffer
     * @return Bytes written
     */
    public int serialize(ByteBuffer buffer, int offset) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(offset);
        
        // data0: childMask(8) | nodeType(8) | depth(8) | flags(8)
        int nodeType = (children.get() == null) ? NODE_TYPE_LEAF : NODE_TYPE_INTERNAL;
        int data0 = getValidMask() | (nodeType << 8) | (depth << 16);
        buffer.putInt(data0);
        
        // data1: morton code or child pointer
        buffer.putInt(mortonCode);
        
        // data2: packed color
        buffer.putInt(packedColor);
        
        // data3: voxel count or material
        buffer.putInt(voxelCount);
        
        return 16; // Size of packed node
    }
    
    /**
     * Gets the memory size of this subtree.
     * 
     * @return Size in bytes
     */
    public int getSubtreeSize() {
        int size = 16; // This node
        
        EnhancedVoxelOctreeNode[] currentChildren = children.get();
        if (currentChildren != null) {
            for (int i = 0; i < 8; i++) {
                if (hasChild(i) && currentChildren[i] != null) {
                    size += currentChildren[i].getSubtreeSize();
                }
            }
        }
        
        return size;
    }
    
    /**
     * Counts nodes in subtree.
     * 
     * @return Node count
     */
    public int getNodeCount() {
        int count = 1; // This node
        
        EnhancedVoxelOctreeNode[] currentChildren = children.get();
        if (currentChildren != null) {
            for (int i = 0; i < 8; i++) {
                if (hasChild(i) && currentChildren[i] != null) {
                    count += currentChildren[i].getNodeCount();
                }
            }
        }
        
        return count;
    }
    
    // Getters
    public float[] getBoundsMin() { return boundsMin.clone(); }
    public float[] getBoundsMax() { return boundsMax.clone(); }
    public int getDepth() { return depth; }
    public int getMortonCode() { return mortonCode; }
    public int getPackedColor() { return packedColor; }
    public int getVoxelCount() { return voxelCount; }
    
    public boolean isLeaf() {
        return children.get() == null;
    }
    
    // Delegate methods to baseNode
    public boolean hasChild(int index) {
        return baseNode.hasChild(index);
    }
    
    public byte getValidMask() {
        return baseNode.getValidMask();
    }
    
    public void setValidMask(byte mask) {
        baseNode.setValidMask(mask);
    }
}
package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.voxel.memory.FFMLayouts;
import com.hellblazer.luciferase.render.voxel.memory.FFMMemoryPool;
import com.hellblazer.luciferase.render.voxel.core.VoxelOctreeNode;
import com.hellblazer.luciferase.webgpu.wrapper.Buffer;

import java.lang.foreign.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages voxel data on the GPU using FFM and WebGPU.
 * 
 * This class handles the transfer of voxel octree data to GPU memory,
 * leveraging FFM for zero-copy operations and WebGPU for cross-platform
 * GPU compute.
 */
public class VoxelGPUManager implements AutoCloseable {
    
    private final WebGPUContext context;
    private final Arena arena;
    private final FFMMemoryPool nodePool;
    private final AtomicLong nextBufferId;
    
    // GPU buffers
    private Buffer octreeBuffer;
    private Buffer materialBuffer;
    private Buffer rayBuffer;
    private Buffer hitResultBuffer;
    
    // Buffer sizes
    private long currentOctreeSize;
    private long currentMaterialSize;
    
    /**
     * Creates a new voxel GPU manager.
     * 
     * @param context The WebGPU context to use
     */
    public VoxelGPUManager(WebGPUContext context) {
        this.context = context;
        this.arena = Arena.ofShared();
        this.nodePool = new FFMMemoryPool(
            FFMLayouts.VOXEL_NODE_LAYOUT.byteSize() * 1024, // 1024 nodes per segment
            32,  // Keep up to 32 segments in pool
            true, // Clear on release
            arena
        );
        this.nextBufferId = new AtomicLong(1);
    }
    
    /**
     * Uploads a voxel octree to the GPU.
     * 
     * @param root The root node of the octree
     * @return The number of nodes uploaded
     */
    public int uploadOctree(VoxelOctreeNode root) {
        // Count nodes
        int nodeCount = countNodes(root);
        
        // Allocate native memory for octree data
        long bufferSize = FFMLayouts.calculateArraySize(FFMLayouts.VOXEL_NODE_LAYOUT, nodeCount);
        var octreeData = arena.allocate(bufferSize, 256); // 256-byte alignment for GPU
        
        // Pack nodes into native memory
        var nodeIndex = new int[]{0};
        packNode(root, octreeData, nodeIndex);
        
        // Create or resize GPU buffer
        if (octreeBuffer == null || currentOctreeSize < bufferSize) {
            if (octreeBuffer != null) {
                // Buffer will be garbage collected, no explicit destroy needed
                octreeBuffer = null;
            }
            octreeBuffer = context.createBuffer(
                bufferSize,
                WebGPUContext.BufferUsage.STORAGE | WebGPUContext.BufferUsage.COPY_DST
            );
            currentOctreeSize = bufferSize;
        }
        
        // Upload to GPU with zero-copy
        // Convert MemorySegment to byte array for now
        byte[] dataBytes = octreeData.toArray(ValueLayout.JAVA_BYTE);
        context.writeBuffer(octreeBuffer, dataBytes, 0);
        
        return nodeCount;
    }
    
    /**
     * Uploads material data to the GPU.
     * 
     * @param materials List of material properties
     */
    public void uploadMaterials(List<Material> materials) {
        int materialCount = materials.size();
        long bufferSize = FFMLayouts.calculateArraySize(FFMLayouts.MATERIAL_LAYOUT, materialCount);
        
        // Allocate native memory
        var materialData = arena.allocate(bufferSize, 256);
        
        // Pack materials
        for (int i = 0; i < materialCount; i++) {
            var material = materials.get(i);
            var offset = i * FFMLayouts.MATERIAL_LAYOUT.byteSize();
            var materialSegment = materialData.asSlice(offset, FFMLayouts.MATERIAL_LAYOUT.byteSize());
            
            // Set material properties using VarHandles would be more efficient,
            // but for clarity using direct memory access
            materialSegment.set(ValueLayout.JAVA_FLOAT, 0, material.albedo.x);
            materialSegment.set(ValueLayout.JAVA_FLOAT, 4, material.albedo.y);
            materialSegment.set(ValueLayout.JAVA_FLOAT, 8, material.albedo.z);
            materialSegment.set(ValueLayout.JAVA_FLOAT, 12, material.albedo.w);
            materialSegment.set(ValueLayout.JAVA_FLOAT, 16, material.metallic);
            materialSegment.set(ValueLayout.JAVA_FLOAT, 20, material.roughness);
            materialSegment.set(ValueLayout.JAVA_FLOAT, 24, material.emission);
        }
        
        // Create or resize GPU buffer
        if (materialBuffer == null || currentMaterialSize < bufferSize) {
            if (materialBuffer != null) {
                // Buffer will be garbage collected
                materialBuffer = null;
            }
            materialBuffer = context.createBuffer(
                bufferSize,
                WebGPUContext.BufferUsage.STORAGE | WebGPUContext.BufferUsage.COPY_DST
            );
            currentMaterialSize = bufferSize;
        }
        
        // Upload to GPU
        byte[] materialBytes = materialData.toArray(ValueLayout.JAVA_BYTE);
        context.writeBuffer(materialBuffer, materialBytes, 0);
    }
    
    /**
     * Performs batch upload of multiple octrees.
     * 
     * @param nodes List of octree root nodes
     * @return Total nodes uploaded
     */
    public int uploadBatch(List<VoxelOctreeNode> nodes) {
        int totalNodes = nodes.stream().mapToInt(this::countNodes).sum();
        long totalSize = FFMLayouts.calculateArraySize(FFMLayouts.VOXEL_NODE_LAYOUT, totalNodes);
        
        // Allocate single large buffer for entire batch
        var batchBuffer = arena.allocate(totalSize, 256);
        
        // Pack all nodes sequentially
        var nodeIndex = new int[]{0};
        for (var node : nodes) {
            packNode(node, batchBuffer, nodeIndex);
        }
        
        // Single GPU upload
        if (octreeBuffer == null || currentOctreeSize < totalSize) {
            if (octreeBuffer != null) {
                // Buffer will be garbage collected
                octreeBuffer = null;
            }
            octreeBuffer = context.createBuffer(
                totalSize,
                WebGPUContext.BufferUsage.STORAGE | WebGPUContext.BufferUsage.COPY_DST
            );
            currentOctreeSize = totalSize;
        }
        
        byte[] batchBytes = batchBuffer.toArray(ValueLayout.JAVA_BYTE);
        context.writeBuffer(octreeBuffer, batchBytes, 0);
        
        return totalNodes;
    }
    
    /**
     * Prepares ray buffers for GPU ray tracing.
     * 
     * @param rayCount Number of rays to allocate
     */
    public void prepareRayBuffers(int rayCount) {
        long rayBufferSize = FFMLayouts.calculateArraySize(FFMLayouts.RAY_LAYOUT, rayCount);
        long hitBufferSize = FFMLayouts.calculateArraySize(FFMLayouts.HIT_RESULT_LAYOUT, rayCount);
        
        // Create ray input buffer
        if (rayBuffer != null) {
            // Buffer will be garbage collected
            rayBuffer = null;
        }
        rayBuffer = context.createBuffer(
            rayBufferSize,
            WebGPUContext.BufferUsage.STORAGE | WebGPUContext.BufferUsage.COPY_DST
        );
        
        // Create hit result buffer
        if (hitResultBuffer != null) {
            // Buffer will be garbage collected
            hitResultBuffer = null;
        }
        hitResultBuffer = context.createBuffer(
            hitBufferSize,
            WebGPUContext.BufferUsage.STORAGE | WebGPUContext.BufferUsage.COPY_SRC
        );
    }
    
    /**
     * Counts the total number of nodes in an octree.
     */
    private int countNodes(VoxelOctreeNode node) {
        if (node == null || node.isEmpty()) return 0;
        
        // For now, just count this node
        // In a real implementation, we'd traverse the child pointer
        return 1 + node.getChildCount();
    }
    
    /**
     * Packs a node and its children into native memory.
     */
    private void packNode(VoxelOctreeNode node, MemorySegment buffer, int[] nodeIndex) {
        if (node == null || node.isEmpty()) return;
        
        int currentIndex = nodeIndex[0]++;
        long offset = currentIndex * FFMLayouts.VOXEL_NODE_LAYOUT.byteSize();
        var nodeSegment = buffer.asSlice(offset, FFMLayouts.VOXEL_NODE_LAYOUT.byteSize());
        
        // Pack node data from actual VoxelOctreeNode fields
        byte validMask = node.getValidMask();
        byte leafMask = node.getFlags(); // Flags indicate non-leaf status
        
        // Write node data using VarHandles for efficiency
        FFMLayouts.VOXEL_NODE_VALID_MASK.set(nodeSegment, 0L, validMask);
        FFMLayouts.VOXEL_NODE_LEAF_MASK.set(nodeSegment, 0L, leafMask);
        
        // Child pointer from node
        int childPointer = node.getChildPointer();
        FFMLayouts.VOXEL_NODE_CHILD_POINTER.set(nodeSegment, 0L, childPointer);
        
        // Use packed data for attachment (contains contour pointer and other data)
        long attachmentData = node.getPackedData();
        FFMLayouts.VOXEL_NODE_ATTACHMENT_DATA.set(nodeSegment, 0L, attachmentData);
        
        // Note: In a real implementation, we'd need to traverse the child pointer
        // to pack children recursively through the memory structure
    }
    
    /**
     * Gets statistics about GPU memory usage.
     */
    public String getStatistics() {
        return String.format(
            "VoxelGPUManager[octreeSize=%d, materialSize=%d, poolStats=%s]",
            currentOctreeSize, currentMaterialSize, nodePool.getStatistics()
        );
    }
    
    @Override
    public void close() {
        // Destroy GPU buffers
        // Buffers will be garbage collected when references are cleared
        octreeBuffer = null;
        materialBuffer = null;
        rayBuffer = null;
        hitResultBuffer = null;
        
        // Close memory pool and arena
        nodePool.close();
        arena.close();
    }
    
    /**
     * Simple material data class.
     */
    public static class Material {
        public final Vec4 albedo;
        public final float metallic;
        public final float roughness;
        public final float emission;
        
        public Material(Vec4 albedo, float metallic, float roughness, float emission) {
            this.albedo = albedo;
            this.metallic = metallic;
            this.roughness = roughness;
            this.emission = emission;
        }
    }
    
    /**
     * Simple vector class for material properties.
     */
    public static class Vec4 {
        public final float x, y, z, w;
        
        public Vec4(float x, float y, float z, float w) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
        }
    }
}
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
package com.hellblazer.luciferase.render.memory;

import com.hellblazer.luciferase.render.voxel.core.EnhancedVoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.core.VoxelData;
import com.hellblazer.luciferase.render.voxel.memory.FFMMemoryPool;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL45.*;

/**
 * Manages GPU memory allocation and data uploads for voxel rendering.
 * Handles buffer management, memory pooling, and efficient data transfers.
 */
public class GPUMemoryManager {
    
    private static final Logger log = Logger.getLogger(GPUMemoryManager.class.getName());
    
    // Buffer types
    public enum BufferType {
        VERTEX_BUFFER,
        INDEX_BUFFER,
        UNIFORM_BUFFER,
        STORAGE_BUFFER,
        TEXTURE_BUFFER
    }
    
    // GPU buffer info
    private static class GPUBuffer {
        final int id;
        final BufferType type;
        final long size;
        final int usage;
        volatile boolean mapped;
        ByteBuffer mappedBuffer;
        
        GPUBuffer(int id, BufferType type, long size, int usage) {
            this.id = id;
            this.type = type;
            this.size = size;
            this.usage = usage;
            this.mapped = false;
        }
    }
    
    // Memory statistics
    private final AtomicLong totalAllocated = new AtomicLong(0);
    private final AtomicLong totalUsed = new AtomicLong(0);
    private final AtomicLong peakUsage = new AtomicLong(0);
    
    // Buffer management
    private final Map<String, GPUBuffer> buffers = new ConcurrentHashMap<>();
    private final Map<Integer, String> bufferIdMap = new ConcurrentHashMap<>();
    
    // Memory pools
    private final FFMMemoryPool memoryPool;
    private final int maxBufferSize;
    
    // Voxel data layout (matches shader expectations)
    private static final int VOXEL_NODE_SIZE = 32; // Bytes per node
    private static final int MAX_NODES_PER_BUFFER = 65536; // 2MB chunks
    
    public GPUMemoryManager(long poolSize, int maxBufferSize) {
        this.memoryPool = new FFMMemoryPool(poolSize);
        this.maxBufferSize = maxBufferSize;
        log.info("GPU Memory Manager initialized with " + poolSize + " byte pool");
    }
    
    /**
     * Upload voxel octree to GPU.
     * 
     * @param rootNode Root of the voxel octree
     * @return Number of nodes uploaded
     */
    public int uploadOctree(EnhancedVoxelOctreeNode rootNode) {
        log.info("Uploading voxel octree to GPU");
        
        // Collect all nodes
        var nodes = collectNodes(rootNode);
        var nodeCount = nodes.size();
        
        // Calculate buffer size
        var bufferSize = nodeCount * VOXEL_NODE_SIZE;
        
        // Create or resize buffer
        var bufferName = "voxel_octree";
        var buffer = getOrCreateBuffer(bufferName, BufferType.STORAGE_BUFFER, bufferSize, GL_DYNAMIC_DRAW);
        
        // Pack node data
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.malloc((int)bufferSize);
            
            for (var node : nodes) {
                packNodeData(node, data);
            }
            
            data.flip();
            
            // Upload to GPU
            glNamedBufferSubData(buffer.id, 0, data);
        }
        
        log.info("Uploaded " + nodeCount + " nodes (" + bufferSize + " bytes)");
        updateMemoryStats(bufferSize);
        
        return nodeCount;
    }
    
    /**
     * Create or get a GPU buffer.
     * 
     * @param name Buffer name
     * @param type Buffer type
     * @param size Buffer size in bytes
     * @param usage Usage hint (GL_STATIC_DRAW, GL_DYNAMIC_DRAW, etc.)
     * @return GPU buffer
     */
    public GPUBuffer getOrCreateBuffer(String name, BufferType type, long size, int usage) {
        return buffers.computeIfAbsent(name, k -> {
            var bufferId = glCreateBuffers();
            glNamedBufferData(bufferId, size, usage);
            
            var buffer = new GPUBuffer(bufferId, type, size, usage);
            bufferIdMap.put(bufferId, name);
            totalAllocated.addAndGet(size);
            
            log.fine("Created GPU buffer '" + name + "' (ID: " + bufferId + ", size: " + size + ")");
            return buffer;
        });
    }
    
    /**
     * Update buffer data.
     * 
     * @param name Buffer name
     * @param offset Offset in bytes
     * @param data Data to upload
     */
    public void updateBuffer(String name, long offset, ByteBuffer data) {
        var buffer = buffers.get(name);
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer not found: " + name);
        }
        
        glNamedBufferSubData(buffer.id, offset, data);
        updateMemoryStats(data.remaining());
    }
    
    /**
     * Map buffer for direct CPU access.
     * 
     * @param name Buffer name
     * @param access Access flags (GL_READ_ONLY, GL_WRITE_ONLY, GL_READ_WRITE)
     * @return Mapped buffer
     */
    public ByteBuffer mapBuffer(String name, int access) {
        var buffer = buffers.get(name);
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer not found: " + name);
        }
        
        if (buffer.mapped) {
            return buffer.mappedBuffer;
        }
        
        buffer.mappedBuffer = glMapNamedBuffer(buffer.id, access, buffer.mappedBuffer);
        buffer.mapped = true;
        
        return buffer.mappedBuffer;
    }
    
    /**
     * Unmap a previously mapped buffer.
     * 
     * @param name Buffer name
     */
    public void unmapBuffer(String name) {
        var buffer = buffers.get(name);
        if (buffer == null || !buffer.mapped) {
            return;
        }
        
        glUnmapNamedBuffer(buffer.id);
        buffer.mapped = false;
        buffer.mappedBuffer = null;
    }
    
    /**
     * Delete a GPU buffer.
     * 
     * @param name Buffer name
     */
    public void deleteBuffer(String name) {
        var buffer = buffers.remove(name);
        if (buffer != null) {
            if (buffer.mapped) {
                unmapBuffer(name);
            }
            
            glDeleteBuffers(buffer.id);
            bufferIdMap.remove(buffer.id);
            totalAllocated.addAndGet(-buffer.size);
            
            log.fine("Deleted GPU buffer '" + name + "' (ID: " + buffer.id + ")");
        }
    }
    
    /**
     * Bind buffer to a binding point.
     * 
     * @param name Buffer name
     * @param bindingPoint Binding point index
     */
    public void bindBuffer(String name, int bindingPoint) {
        var buffer = buffers.get(name);
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer not found: " + name);
        }
        
        switch (buffer.type) {
            case UNIFORM_BUFFER -> glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, buffer.id);
            case STORAGE_BUFFER -> glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, buffer.id);
            default -> throw new IllegalArgumentException("Buffer type does not support binding points: " + buffer.type);
        }
    }
    
    /**
     * Get memory statistics.
     * 
     * @return Map of statistic names to values
     */
    public Map<String, Long> getMemoryStats() {
        var stats = new HashMap<String, Long>();
        stats.put("totalAllocated", totalAllocated.get());
        stats.put("totalUsed", totalUsed.get());
        stats.put("peakUsage", peakUsage.get());
        stats.put("bufferCount", (long)buffers.size());
        return stats;
    }
    
    /**
     * Clear all GPU buffers and reset memory.
     */
    public void clear() {
        for (var bufferName : new ArrayList<>(buffers.keySet())) {
            deleteBuffer(bufferName);
        }
        
        totalAllocated.set(0);
        totalUsed.set(0);
        peakUsage.set(0);
        
        log.info("GPU memory manager cleared");
    }
    
    /**
     * Cleanup resources.
     */
    public void dispose() {
        clear();
        memoryPool.close();
    }
    
    // ===== Private Helper Methods =====
    
    private List<EnhancedVoxelOctreeNode> collectNodes(EnhancedVoxelOctreeNode root) {
        var nodes = new ArrayList<EnhancedVoxelOctreeNode>();
        var queue = new LinkedList<EnhancedVoxelOctreeNode>();
        queue.add(root);
        
        while (!queue.isEmpty() && nodes.size() < MAX_NODES_PER_BUFFER) {
            var node = queue.poll();
            nodes.add(node);
            
            // Add children
            for (int i = 0; i < 8; i++) {
                var child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        
        return nodes;
    }
    
    private void packNodeData(EnhancedVoxelOctreeNode node, ByteBuffer buffer) {
        // Pack node data according to GPU layout
        // Format: [bounds(24 bytes)][metadata(4 bytes)][childMask(4 bytes)]
        
        // Bounds (6 floats)
        var bounds = node.getBounds();
        buffer.putFloat(bounds[0]); // minX
        buffer.putFloat(bounds[1]); // minY
        buffer.putFloat(bounds[2]); // minZ
        buffer.putFloat(bounds[3]); // maxX
        buffer.putFloat(bounds[4]); // maxY
        buffer.putFloat(bounds[5]); // maxZ
        
        // Metadata (packed into 32 bits)
        var voxelData = node.getVoxelData();
        int metadata = 0;
        if (voxelData != null) {
            // Use voxel count as density since VoxelData doesn't have getDensity()
            int density = Math.min(255, node.getVoxelCount());
            metadata = (voxelData.getMaterialId() & 0xFF) |
                      ((density & 0xFF) << 8) |
                      ((node.getNodeType() & 0xFF) << 16) |
                      ((node.getDepth() & 0xFF) << 24);
        }
        buffer.putInt(metadata);
        
        // Child mask (which children exist)
        int childMask = 0;
        for (int i = 0; i < 8; i++) {
            if (node.getChild(i) != null) {
                childMask |= (1 << i);
            }
        }
        buffer.putInt(childMask);
    }
    
    private void updateMemoryStats(long bytesUsed) {
        var currentUsed = totalUsed.addAndGet(bytesUsed);
        
        // Update peak usage
        long currentPeak;
        do {
            currentPeak = peakUsage.get();
        } while (currentUsed > currentPeak && !peakUsage.compareAndSet(currentPeak, currentUsed));
    }
}
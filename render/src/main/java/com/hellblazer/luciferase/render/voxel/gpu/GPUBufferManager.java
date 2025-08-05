package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.voxel.gpu.WebGPUStubs.*;
import static com.hellblazer.luciferase.render.voxel.gpu.WebGPUStubs.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages GPU buffers and provides efficient FFM-to-GPU memory transfers.
 * Handles buffer lifecycle, staging, and zero-copy operations where possible.
 */
public class GPUBufferManager {
    private static final Logger log = LoggerFactory.getLogger(GPUBufferManager.class);
    
    private static final long STAGING_THRESHOLD = 256 * 1024; // 256KB
    private static final long MAX_STAGING_SIZE = 64 * 1024 * 1024; // 64MB
    
    private final Device device;
    private final Queue queue;
    private final Map<String, Buffer> namedBuffers = new ConcurrentHashMap<>();
    private final Map<MemorySegment, Buffer> segmentBuffers = new ConcurrentHashMap<>();
    private final AtomicLong totalGPUMemory = new AtomicLong(0);
    
    public GPUBufferManager(Device device, Queue queue) {
        this.device = device;
        this.queue = queue;
    }
    
    /**
     * Create a GPU buffer with specific usage flags
     */
    public Buffer createBuffer(String name, long size, int usage) {
        // Check if named buffer already exists
        Buffer existing = namedBuffers.get(name);
        if (existing != null) {
            log.warn("Buffer '{}' already exists, returning existing buffer", name);
            return existing;
        }
        
        BufferDescriptor desc = new BufferDescriptor();
        desc.setLabel(name);
        desc.setSize(size);
        desc.setUsage(usage);
        desc.setMappedAtCreation(false);
        
        Buffer buffer = device.createBuffer(desc);
        if (buffer == null) {
            throw new RuntimeException("Failed to create buffer: " + name);
        }
        
        namedBuffers.put(name, buffer);
        totalGPUMemory.addAndGet(size);
        
        log.debug("Created GPU buffer '{}' with size {} bytes", name, size);
        return buffer;
    }
    
    /**
     * Create a GPU buffer from an FFM MemorySegment
     */
    public Buffer createBufferFromSegment(MemorySegment segment, int usage) {
        // Check if we already have a buffer for this segment
        Buffer existing = segmentBuffers.get(segment);
        if (existing != null) {
            return existing;
        }
        
        long size = segment.byteSize();
        
        // Create buffer with COPY_DST for uploads
        int finalUsage = usage | BufferUsage.COPY_DST;
        
        BufferDescriptor desc = new BufferDescriptor();
        desc.setLabel("FFM Buffer");
        desc.setSize(size);
        desc.setUsage(finalUsage);
        desc.setMappedAtCreation(false);
        
        Buffer buffer = device.createBuffer(desc);
        if (buffer == null) {
            throw new RuntimeException("Failed to create buffer from segment");
        }
        
        // Upload data
        uploadToBuffer(buffer, segment);
        
        // Cache the buffer
        segmentBuffers.put(segment, buffer);
        totalGPUMemory.addAndGet(size);
        
        return buffer;
    }
    
    /**
     * Upload data from FFM MemorySegment to GPU buffer
     */
    public void uploadToBuffer(Buffer buffer, MemorySegment data) {
        long size = data.byteSize();
        
        if (size <= STAGING_THRESHOLD) {
            // Small data - use queue write directly
            uploadDirect(buffer, data);
        } else {
            // Large data - use staging buffer
            uploadViaStaging(buffer, data);
        }
    }
    
    private void uploadDirect(Buffer buffer, MemorySegment data) {
        // Convert MemorySegment to byte array for WebGPU API
        byte[] bytes = data.toArray(ValueLayout.JAVA_BYTE);
        queue.writeBuffer(buffer, 0, bytes, 0, bytes.length);
        
        log.trace("Direct upload of {} bytes completed", bytes.length);
    }
    
    private void uploadViaStaging(Buffer dst, MemorySegment data) {
        long size = data.byteSize();
        
        // Split very large uploads into chunks
        if (size > MAX_STAGING_SIZE) {
            uploadInChunks(dst, data);
            return;
        }
        
        // Create staging buffer
        BufferDescriptor stagingDesc = new BufferDescriptor();
        stagingDesc.setLabel("Staging Buffer");
        stagingDesc.setSize(size);
        stagingDesc.setUsage(BufferUsage.MAP_WRITE | BufferUsage.COPY_SRC);
        stagingDesc.setMappedAtCreation(true);
        
        Buffer staging = device.createBuffer(stagingDesc);
        if (staging == null) {
            throw new RuntimeException("Failed to create staging buffer");
        }
        
        try {
            // Get mapped range and copy data
            ByteBuffer mapped = staging.getMappedRange(0, size);
            byte[] bytes = data.toArray(ValueLayout.JAVA_BYTE);
            mapped.put(bytes);
            staging.unmap();
            
            // Copy staging to destination
            CommandEncoder encoder = device.createCommandEncoder();
            encoder.copyBufferToBuffer(staging, 0, dst, 0, size);
            
            CommandBuffer commands = encoder.finish();
            queue.submit(commands);
            
            log.trace("Staged upload of {} MB completed", size / (1024 * 1024));
            
        } finally {
            // Always clean up staging buffer
            staging.release();
        }
    }
    
    private void uploadInChunks(Buffer dst, MemorySegment data) {
        long totalSize = data.byteSize();
        long offset = 0;
        
        while (offset < totalSize) {
            long chunkSize = Math.min(MAX_STAGING_SIZE, totalSize - offset);
            
            // Create staging buffer for this chunk
            BufferDescriptor stagingDesc = new BufferDescriptor();
            stagingDesc.setLabel("Chunk Staging Buffer");
            stagingDesc.setSize(chunkSize);
            stagingDesc.setUsage(BufferUsage.MAP_WRITE | BufferUsage.COPY_SRC);
            stagingDesc.setMappedAtCreation(true);
            
            Buffer staging = device.createBuffer(stagingDesc);
            
            try {
                // Copy chunk data
                ByteBuffer mapped = staging.getMappedRange(0, chunkSize);
                MemorySegment chunk = data.asSlice(offset, chunkSize);
                byte[] bytes = chunk.toArray(ValueLayout.JAVA_BYTE);
                mapped.put(bytes);
                staging.unmap();
                
                // Copy to destination
                CommandEncoder encoder = device.createCommandEncoder();
                encoder.copyBufferToBuffer(staging, 0, dst, offset, chunkSize);
                
                CommandBuffer commands = encoder.finish();
                queue.submit(commands);
                
            } finally {
                staging.release();
            }
            
            offset += chunkSize;
            log.trace("Uploaded chunk {}/{} MB", offset / (1024 * 1024), totalSize / (1024 * 1024));
        }
    }
    
    /**
     * Create specialized buffer for voxel octree nodes
     */
    public Buffer createOctreeBuffer(MemorySegment octreeData) {
        return createBufferFromSegment(octreeData, 
            BufferUsage.STORAGE | BufferUsage.COPY_SRC);
    }
    
    /**
     * Create buffer for ray data
     */
    public Buffer createRayBuffer(int maxRays) {
        long size = maxRays * 32L; // 32 bytes per ray (2 * vec3<f32> + 2 * f32)
        return createBuffer("Ray Buffer", size, 
            BufferUsage.STORAGE | BufferUsage.COPY_DST);
    }
    
    /**
     * Create buffer for hit results
     */
    public Buffer createResultBuffer(int maxResults) {
        long size = maxResults * 32L; // 32 bytes per result
        return createBuffer("Result Buffer", size,
            BufferUsage.STORAGE | BufferUsage.COPY_SRC);
    }
    
    /**
     * Create uniform buffer
     */
    public Buffer createUniformBuffer(String name, long size) {
        return createBuffer(name, size,
            BufferUsage.UNIFORM | BufferUsage.COPY_DST);
    }
    
    /**
     * Read back data from GPU buffer
     */
    public CompletableFuture<byte[]> readBuffer(Buffer buffer, long offset, long size) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        
        // Create staging buffer for readback
        BufferDescriptor stagingDesc = new BufferDescriptor();
        stagingDesc.setLabel("Readback Staging");
        stagingDesc.setSize(size);
        stagingDesc.setUsage(BufferUsage.MAP_READ | BufferUsage.COPY_DST);
        
        Buffer staging = device.createBuffer(stagingDesc);
        if (staging == null) {
            future.completeExceptionally(new RuntimeException("Failed to create readback buffer"));
            return future;
        }
        
        // Copy GPU buffer to staging
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.copyBufferToBuffer(buffer, offset, staging, 0, size);
        CommandBuffer commands = encoder.finish();
        queue.submit(commands);
        
        // Map and read after GPU completes
        staging.mapAsync(MapMode.READ, 0, size, (status) -> {
            if (status != BufferMapAsyncStatus.SUCCESS) {
                staging.release();
                future.completeExceptionally(new RuntimeException("Buffer mapping failed"));
                return;
            }
            
            try {
                ByteBuffer mapped = staging.getMappedRange(0, size);
                byte[] result = new byte[(int) size];
                mapped.get(result);
                staging.unmap();
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                staging.release();
            }
        });
        
        return future;
    }
    
    /**
     * Create a bind group for compute operations
     */
    public BindGroup createBindGroup(BindGroupLayout layout, BindGroupEntry... entries) {
        BindGroupDescriptor desc = new BindGroupDescriptor();
        desc.setLabel("Compute Bind Group");
        desc.setLayout(layout);
        desc.setEntries(entries);
        
        BindGroup bindGroup = device.createBindGroup(desc);
        if (bindGroup == null) {
            throw new RuntimeException("Failed to create bind group");
        }
        
        return bindGroup;
    }
    
    /**
     * Helper to create buffer binding entry
     */
    public BindGroupEntry createBufferBinding(int binding, Buffer buffer, long offset, long size) {
        BindGroupEntry entry = new BindGroupEntry();
        entry.setBinding(binding);
        
        BufferBinding bufferBinding = new BufferBinding();
        bufferBinding.setBuffer(buffer);
        bufferBinding.setOffset(offset);
        bufferBinding.setSize(size > 0 ? size : buffer.getSize());
        
        entry.setBufferBinding(bufferBinding);
        return entry;
    }
    
    /**
     * Get total GPU memory usage
     */
    public long getTotalGPUMemory() {
        return totalGPUMemory.get();
    }
    
    /**
     * Release a named buffer
     */
    public void releaseBuffer(String name) {
        Buffer buffer = namedBuffers.remove(name);
        if (buffer != null) {
            long size = buffer.getSize();
            buffer.release();
            totalGPUMemory.addAndGet(-size);
            log.debug("Released buffer '{}', freed {} bytes", name, size);
        }
    }
    
    /**
     * Release all resources
     */
    public void cleanup() {
        // Release named buffers
        namedBuffers.forEach((name, buffer) -> {
            buffer.release();
            log.debug("Destroyed buffer: {}", name);
        });
        namedBuffers.clear();
        
        // Release segment buffers
        segmentBuffers.values().forEach(Buffer::release);
        segmentBuffers.clear();
        
        totalGPUMemory.set(0);
        log.info("GPU buffer manager cleanup complete");
    }
}

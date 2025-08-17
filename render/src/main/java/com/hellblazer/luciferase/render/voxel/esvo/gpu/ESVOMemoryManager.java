package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import com.hellblazer.luciferase.render.voxel.esvo.ESVOPage;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Manages GPU memory allocation for ESVO structures.
 * Handles page-based memory layout and resource tracking.
 */
public class ESVOMemoryManager {
    
    private final long maxMemory;
    private long allocatedMemory;
    
    // Buffer management
    private final Map<String, BufferInfo> buffers;
    private final Queue<BufferInfo> freeBuffers;
    
    // Page management
    private int pageBufferSSBO;
    private final List<PageAllocation> pageAllocations;
    
    // Statistics
    private long totalAllocations;
    private long totalDeallocations;
    private long peakMemoryUsage;
    
    public ESVOMemoryManager(long maxMemory) {
        this.maxMemory = maxMemory;
        this.allocatedMemory = 0;
        this.buffers = new HashMap<>();
        this.freeBuffers = new LinkedList<>();
        this.pageAllocations = new ArrayList<>();
    }
    
    /**
     * Initialize GPU resources
     */
    public void initialize() {
        // Create page buffer
        pageBufferSSBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, pageBufferSSBO);
        
        // Pre-allocate initial page pool
        long initialPagePoolSize = Math.min(maxMemory / 4, 256 * ESVOPage.PAGE_BYTES);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, initialPagePoolSize, GL15.GL_DYNAMIC_DRAW);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, pageBufferSSBO);
    }
    
    /**
     * Allocate GPU memory
     */
    public boolean allocate(long size) {
        if (allocatedMemory + size > maxMemory) {
            // Try to free unused buffers
            compactMemory();
            
            if (allocatedMemory + size > maxMemory) {
                return false; // Not enough memory
            }
        }
        
        allocatedMemory += size;
        totalAllocations++;
        peakMemoryUsage = Math.max(peakMemoryUsage, allocatedMemory);
        
        return true;
    }
    
    /**
     * Deallocate GPU memory
     */
    public void deallocate(long size) {
        allocatedMemory = Math.max(0, allocatedMemory - size);
        totalDeallocations++;
    }
    
    /**
     * Create a named buffer
     */
    public int createBuffer(String name, long size, int usage) {
        if (!allocate(size)) {
            throw new RuntimeException("Failed to allocate buffer: " + name);
        }
        
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, size, usage);
        
        BufferInfo info = new BufferInfo(name, buffer, size, usage);
        buffers.put(name, info);
        
        return buffer;
    }
    
    /**
     * Get buffer by name
     */
    public int getBuffer(String name) {
        BufferInfo info = buffers.get(name);
        return info != null ? info.buffer : 0;
    }
    
    /**
     * Upload pages to GPU
     */
    public void uploadPages(List<ESVOPage> pages) {
        if (pages.isEmpty()) {
            return;
        }
        
        // Calculate total size
        long totalSize = pages.size() * (long) ESVOPage.PAGE_BYTES;
        
        // Allocate staging buffer
        ByteBuffer stagingBuffer = MemoryUtil.memAlloc((int) totalSize);
        
        // Pack pages into buffer
        for (ESVOPage page : pages) {
            byte[] pageData = page.serialize();
            stagingBuffer.put(pageData);
        }
        stagingBuffer.flip();
        
        // Upload to GPU
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, pageBufferSSBO);
        
        // Find free page slots
        long offset = findFreePageSlots(pages.size());
        if (offset < 0) {
            // Need to resize buffer
            resizePageBuffer(pages.size());
            offset = findFreePageSlots(pages.size());
        }
        
        // Upload data
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, offset, stagingBuffer);
        
        // Track allocation
        pageAllocations.add(new PageAllocation(offset, pages.size()));
        
        // Free staging buffer
        MemoryUtil.memFree(stagingBuffer);
    }
    
    /**
     * Find free page slots in buffer
     */
    private long findFreePageSlots(int pageCount) {
        long requiredSize = pageCount * (long) ESVOPage.PAGE_BYTES;
        
        // Simple first-fit allocation
        if (pageAllocations.isEmpty()) {
            return 0;
        }
        
        // Sort allocations by offset
        pageAllocations.sort(Comparator.comparingLong(a -> a.offset));
        
        // Check gaps between allocations
        long currentOffset = 0;
        for (PageAllocation alloc : pageAllocations) {
            long gap = alloc.offset - currentOffset;
            if (gap >= requiredSize) {
                return currentOffset;
            }
            currentOffset = alloc.offset + alloc.pageCount * ESVOPage.PAGE_BYTES;
        }
        
        // Check space after last allocation
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, pageBufferSSBO);
        long bufferSize = GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER, GL15.GL_BUFFER_SIZE);
        
        if (bufferSize - currentOffset >= requiredSize) {
            return currentOffset;
        }
        
        return -1; // No space available
    }
    
    /**
     * Resize page buffer to accommodate more pages
     */
    private void resizePageBuffer(int additionalPages) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, pageBufferSSBO);
        long currentSize = GL15.glGetBufferParameteri(GL43.GL_SHADER_STORAGE_BUFFER, GL15.GL_BUFFER_SIZE);
        
        long additionalSize = additionalPages * (long) ESVOPage.PAGE_BYTES;
        long newSize = currentSize + additionalSize;
        
        // Check memory limit
        if (!allocate(additionalSize)) {
            throw new RuntimeException("Cannot resize page buffer: memory limit exceeded");
        }
        
        // Create new buffer with larger size
        int newBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, newBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, newSize, GL15.GL_DYNAMIC_DRAW);
        
        // Copy existing data
        GL43.glCopyBufferSubData(pageBufferSSBO, newBuffer, 0, 0, currentSize);
        
        // Delete old buffer
        GL15.glDeleteBuffers(pageBufferSSBO);
        pageBufferSSBO = newBuffer;
        
        // Update binding
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, pageBufferSSBO);
    }
    
    /**
     * Compact memory by freeing unused buffers
     */
    private void compactMemory() {
        // Move unused buffers to free list
        Iterator<Map.Entry<String, BufferInfo>> it = buffers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BufferInfo> entry = it.next();
            BufferInfo info = entry.getValue();
            
            if (info.lastAccessTime < System.currentTimeMillis() - 60000) { // 1 minute timeout
                freeBuffers.offer(info);
                it.remove();
                deallocate(info.size);
            }
        }
        
        // Delete oldest free buffers if needed
        while (!freeBuffers.isEmpty() && allocatedMemory > maxMemory * 0.9) {
            BufferInfo info = freeBuffers.poll();
            GL15.glDeleteBuffers(info.buffer);
            deallocate(info.size);
        }
    }
    
    /**
     * Get memory statistics
     */
    public MemoryStatistics getStatistics() {
        return new MemoryStatistics(
            allocatedMemory,
            maxMemory,
            totalAllocations,
            totalDeallocations,
            peakMemoryUsage,
            buffers.size(),
            pageAllocations.size()
        );
    }
    
    /**
     * Clean up all resources
     */
    public void dispose() {
        // Delete all buffers
        for (BufferInfo info : buffers.values()) {
            GL15.glDeleteBuffers(info.buffer);
        }
        buffers.clear();
        
        for (BufferInfo info : freeBuffers) {
            GL15.glDeleteBuffers(info.buffer);
        }
        freeBuffers.clear();
        
        // Delete page buffer
        if (pageBufferSSBO != 0) {
            GL15.glDeleteBuffers(pageBufferSSBO);
            pageBufferSSBO = 0;
        }
        
        allocatedMemory = 0;
    }
    
    /**
     * Buffer information
     */
    private static class BufferInfo {
        final String name;
        final int buffer;
        final long size;
        final int usage;
        long lastAccessTime;
        
        BufferInfo(String name, int buffer, long size, int usage) {
            this.name = name;
            this.buffer = buffer;
            this.size = size;
            this.usage = usage;
            this.lastAccessTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Page allocation tracking
     */
    private static class PageAllocation {
        final long offset;
        final int pageCount;
        
        PageAllocation(long offset, int pageCount) {
            this.offset = offset;
            this.pageCount = pageCount;
        }
    }
    
    /**
     * Memory statistics
     */
    public static class MemoryStatistics {
        public final long allocatedMemory;
        public final long maxMemory;
        public final long totalAllocations;
        public final long totalDeallocations;
        public final long peakMemoryUsage;
        public final int activeBuffers;
        public final int pageAllocations;
        
        public MemoryStatistics(long allocatedMemory, long maxMemory,
                               long totalAllocations, long totalDeallocations,
                               long peakMemoryUsage, int activeBuffers,
                               int pageAllocations) {
            this.allocatedMemory = allocatedMemory;
            this.maxMemory = maxMemory;
            this.totalAllocations = totalAllocations;
            this.totalDeallocations = totalDeallocations;
            this.peakMemoryUsage = peakMemoryUsage;
            this.activeBuffers = activeBuffers;
            this.pageAllocations = pageAllocations;
        }
        
        public double getMemoryUtilization() {
            return (double) allocatedMemory / maxMemory;
        }
    }
}
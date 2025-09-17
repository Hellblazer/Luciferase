package com.hellblazer.luciferase.resource.memory;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * RAII handle for native memory allocated via LWJGL.
 * Ensures proper cleanup of off-heap memory to prevent native memory leaks.
 */
public class NativeMemoryHandle extends ResourceHandle<ByteBuffer> {
    private static final Logger log = LoggerFactory.getLogger(NativeMemoryHandle.class);
    
    private final long address;
    private final long size;
    private final boolean aligned;
    
    /**
     * Allocate native memory.
     * 
     * @param size Size in bytes
     * @param tracker Optional resource tracker
     * @return A new memory handle
     */
    public static NativeMemoryHandle allocate(long size, ResourceTracker tracker) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }
        
        var buffer = MemoryUtil.memAlloc((int) size);
        var address = MemoryUtil.memAddress(buffer);
        
        log.trace("Allocated {} bytes of native memory at address 0x{}", size, Long.toHexString(address));
        
        return new NativeMemoryHandle(buffer, address, size, false, tracker);
    }
    
    /**
     * Allocate aligned native memory (for GPU resources).
     * 
     * @param size Size in bytes
     * @param alignment Alignment in bytes (must be power of 2)
     * @param tracker Optional resource tracker
     * @return A new aligned memory handle
     */
    public static NativeMemoryHandle allocateAligned(long size, int alignment, ResourceTracker tracker) {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("Alignment must be power of 2: " + alignment);
        }
        
        var buffer = MemoryUtil.memAlignedAlloc(alignment, (int) size);
        var address = MemoryUtil.memAddress(buffer);
        
        log.trace("Allocated {} bytes of aligned ({}) native memory at address 0x{}", 
            size, alignment, Long.toHexString(address));
        
        return new NativeMemoryHandle(buffer, address, size, true, tracker);
    }
    
    /**
     * Wrap an existing ByteBuffer in a managed handle.
     * 
     * @param buffer The buffer to wrap
     * @param tracker Optional resource tracker
     * @return A new memory handle
     */
    public static NativeMemoryHandle wrap(ByteBuffer buffer, ResourceTracker tracker) {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be direct");
        }
        
        var address = MemoryUtil.memAddress(buffer);
        var size = buffer.remaining();
        
        return new NativeMemoryHandle(buffer, address, size, false, tracker);
    }
    
    protected NativeMemoryHandle(ByteBuffer buffer, long address, long size, boolean aligned, ResourceTracker tracker) {
        super(buffer, tracker);
        this.address = address;
        this.size = size;
        this.aligned = aligned;
    }
    
    /**
     * Get the native memory address.
     * 
     * @return The memory address
     */
    public long getAddress() {
        if (!isValid()) {
            throw new IllegalStateException("Cannot access address of closed memory handle");
        }
        return address;
    }
    
    /**
     * Get the size in bytes.
     * 
     * @return The size
     */
    public long getSize() {
        return size;
    }
    
    /**
     * Check if this memory is aligned.
     * 
     * @return true if aligned allocation
     */
    public boolean isAligned() {
        return aligned;
    }
    
    /**
     * Clear the memory (set to zero).
     */
    public void clear() {
        var buffer = get();
        if (buffer != null) {
            MemoryUtil.memSet(buffer, 0);
        }
    }
    
    /**
     * Copy data from this memory to another.
     * 
     * @param dst Destination memory handle
     * @param srcOffset Source offset in bytes
     * @param dstOffset Destination offset in bytes
     * @param length Number of bytes to copy
     */
    public void copyTo(NativeMemoryHandle dst, long srcOffset, long dstOffset, long length) {
        if (!isValid() || !dst.isValid()) {
            throw new IllegalStateException("Cannot copy between closed memory handles");
        }
        
        if (srcOffset < 0 || srcOffset + length > size) {
            throw new IllegalArgumentException("Source range out of bounds");
        }
        if (dstOffset < 0 || dstOffset + length > dst.size) {
            throw new IllegalArgumentException("Destination range out of bounds");
        }
        
        MemoryUtil.memCopy(address + srcOffset, dst.address + dstOffset, length);
    }
    
    /**
     * Create a slice of this memory.
     * 
     * @param offset Offset in bytes
     * @param length Length in bytes
     * @return A new ByteBuffer view
     */
    public ByteBuffer slice(long offset, long length) {
        var buffer = get();
        if (buffer == null) {
            throw new IllegalStateException("Cannot slice closed memory handle");
        }
        
        if (offset < 0 || offset + length > size) {
            throw new IllegalArgumentException("Slice range out of bounds");
        }
        
        return MemoryUtil.memSlice(buffer, (int) offset, (int) length);
    }
    
    @Override
    protected void doCleanup(ByteBuffer buffer) {
        if (aligned) {
            MemoryUtil.memAlignedFree(buffer);
            log.trace("Freed {} bytes of aligned native memory at address 0x{}", 
                size, Long.toHexString(address));
        } else {
            MemoryUtil.memFree(buffer);
            log.trace("Freed {} bytes of native memory at address 0x{}", 
                size, Long.toHexString(address));
        }
    }
    
    @Override
    public String toString() {
        return String.format("NativeMemoryHandle[address=0x%x, size=%d, aligned=%b, state=%s]",
            address, size, aligned, getState());
    }
    
    /**
     * Get the underlying ByteBuffer.
     */
    public ByteBuffer getBuffer() {
        return get();
    }
}
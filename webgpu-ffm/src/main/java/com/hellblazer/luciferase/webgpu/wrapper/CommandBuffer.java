package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wrapper for WebGPU command buffer.
 * Represents a recorded sequence of GPU commands ready for submission.
 */
public class CommandBuffer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CommandBuffer.class);
    
    private final Device device;
    private final MemorySegment handle;
    private final AtomicBoolean released = new AtomicBoolean(false);
    private final long id; // For compatibility
    
    /**
     * Create a new command buffer.
     * 
     * @param device the device that created this buffer
     * @param handle the native handle
     */
    CommandBuffer(Device device, MemorySegment handle) {
        this.device = device;
        this.handle = handle;
        this.id = handle != null ? handle.address() : 0;
        log.debug("Created command buffer: {}", handle);
    }
    
    /**
     * Legacy constructor for compatibility.
     * @param id the buffer ID
     */
    protected CommandBuffer(long id) {
        this.device = null;
        this.handle = MemorySegment.ofAddress(id);
        this.id = id;
    }
    
    /**
     * Get the native handle for submission.
     * 
     * @return the native handle
     */
    public MemorySegment getHandle() {
        if (released.get()) {
            throw new IllegalStateException("Command buffer already released");
        }
        return handle;
    }
    
    /**
     * Get the buffer ID (for compatibility).
     * 
     * @return the buffer ID
     */
    public long getId() {
        return id;
    }
    
    /**
     * Check if this command buffer is valid.
     * 
     * @return true if valid
     */
    public boolean isValid() {
        return !released.get() && handle != null && !handle.equals(MemorySegment.NULL);
    }
    
    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            // TODO: Implement native release if needed
            log.debug("Released command buffer");
        }
    }
}
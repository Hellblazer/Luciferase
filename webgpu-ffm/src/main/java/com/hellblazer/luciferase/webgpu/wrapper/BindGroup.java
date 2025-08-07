package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wrapper for WebGPU bind group.
 * Represents a collection of resources bound together for shader access.
 */
public class BindGroup implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BindGroup.class);
    
    private final Device device;
    private final MemorySegment handle;
    private final AtomicBoolean released = new AtomicBoolean(false);
    
    /**
     * Create a new bind group.
     * 
     * @param device the device that created this bind group
     * @param handle the native handle
     */
    BindGroup(Device device, MemorySegment handle) {
        this.device = device;
        this.handle = handle;
        log.debug("Created bind group: {}", handle);
    }
    
    /**
     * Get the native handle.
     * 
     * @return the native handle
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    @Override
    public void close() {
        if (released.compareAndSet(false, true)) {
            // TODO: Implement native release
            log.debug("Released bind group");
        }
    }
}
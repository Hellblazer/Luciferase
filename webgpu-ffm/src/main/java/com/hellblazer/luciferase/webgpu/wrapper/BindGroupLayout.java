package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wrapper for WebGPU bind group layout.
 * Defines the structure and types of resources in a bind group.
 */
public class BindGroupLayout implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BindGroupLayout.class);
    
    private final Device device;
    private final MemorySegment handle;
    private final AtomicBoolean released = new AtomicBoolean(false);
    
    /**
     * Create a new bind group layout.
     * 
     * @param device the device that created this layout
     * @param handle the native handle
     */
    BindGroupLayout(Device device, MemorySegment handle) {
        this.device = device;
        this.handle = handle;
        log.debug("Created bind group layout: 0x{}", Long.toHexString(handle.address()));
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
            com.hellblazer.luciferase.webgpu.WebGPU.releaseBindGroupLayout(handle);
            log.debug("Released bind group layout");
        }
    }
}
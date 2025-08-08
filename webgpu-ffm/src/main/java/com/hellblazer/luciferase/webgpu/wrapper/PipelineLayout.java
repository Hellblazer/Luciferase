package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wrapper for WebGPU pipeline layout.
 * Defines the bind group layouts that can be used with a pipeline.
 */
public class PipelineLayout implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PipelineLayout.class);
    
    private final Device device;
    private final MemorySegment handle;
    private final AtomicBoolean released = new AtomicBoolean(false);
    
    /**
     * Create a new pipeline layout.
     * 
     * @param device the device that created this layout
     * @param handle the native handle
     */
    PipelineLayout(Device device, MemorySegment handle) {
        this.device = device;
        this.handle = handle;
        log.debug("Created pipeline layout: 0x{}", Long.toHexString(handle.address()));
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
            com.hellblazer.luciferase.webgpu.WebGPU.releasePipelineLayout(handle);
            log.debug("Released pipeline layout");
        }
    }
}
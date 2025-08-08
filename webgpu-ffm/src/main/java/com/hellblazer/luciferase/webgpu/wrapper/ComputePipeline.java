package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Compute Pipeline.
 */
public class ComputePipeline implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ComputePipeline.class);
    
    private final MemorySegment handle;
    private final Device device;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * Create a compute pipeline wrapper.
     */
    protected ComputePipeline(MemorySegment handle, Device device) {
        this.handle = handle;
        this.device = device;
    }
    
    /**
     * Get the native handle.
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Release the native compute pipeline
            com.hellblazer.luciferase.webgpu.WebGPU.releaseComputePipeline(handle);
            log.debug("Released compute pipeline {}", handle);
        }
    }
}
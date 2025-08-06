package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Compute Pipeline.
 */
public class ComputePipeline implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ComputePipeline.class);
    
    private final long id;
    private final Device device;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * Create a compute pipeline wrapper.
     */
    protected ComputePipeline(long id, Device device) {
        this.id = id;
        this.device = device;
    }
    
    /**
     * Get the pipeline ID.
     */
    public long getId() {
        return id;
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // TODO: Call wgpuComputePipelineRelease when available
            log.debug("Released compute pipeline {}", id);
        }
    }
}
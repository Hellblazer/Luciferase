package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Shader Module.
 */
public class ShaderModule implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ShaderModule.class);
    
    private final MemorySegment handle;
    private final String code;
    private final Device device;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * Create a shader module wrapper.
     */
    protected ShaderModule(MemorySegment handle, String code, Device device) {
        this.handle = handle;
        this.code = code;
        this.device = device;
    }
    
    /**
     * Get the native handle.
     */
    public MemorySegment getHandle() {
        return handle;
    }
    
    /**
     * Get the WGSL source code.
     */
    public String getCode() {
        return code;
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Release the native shader module
            com.hellblazer.luciferase.webgpu.WebGPU.releaseShaderModule(handle);
            log.debug("Released shader module {}", handle);
        }
    }
}
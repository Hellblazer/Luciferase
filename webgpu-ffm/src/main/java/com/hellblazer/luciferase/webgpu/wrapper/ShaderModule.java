package com.hellblazer.luciferase.webgpu.wrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Type-safe wrapper for WebGPU Shader Module.
 */
public class ShaderModule implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ShaderModule.class);
    
    private final long id;
    private final String code;
    private final Device device;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    /**
     * Create a shader module wrapper.
     */
    protected ShaderModule(long id, String code, Device device) {
        this.id = id;
        this.code = code;
        this.device = device;
    }
    
    /**
     * Get the shader module ID.
     */
    public long getId() {
        return id;
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
            device.removeShaderModule(id);
            // TODO: Call wgpuShaderModuleRelease when available
            log.debug("Released shader module {}", id);
        }
    }
}
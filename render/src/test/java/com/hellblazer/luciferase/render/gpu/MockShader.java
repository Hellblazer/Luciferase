/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.gpu;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock shader implementation for testing GPU shader functionality across different backends.
 * Simulates shader compilation, binding, and execution for OpenGL, BGFX Metal, and BGFX Vulkan.
 */
public class MockShader implements IGPUShader {
    
    private static final AtomicLong idGenerator = new AtomicLong(1);
    
    private final long id;
    private final String source;
    private final Map<String, String> defines;
    private final GPUConfig.Backend backend;
    private boolean compiled = false;
    private boolean bound = false;
    private String compilationError = null;
    private IShaderFactory.CompilationResult compilationResult;
    
    public MockShader(long id, String source, Map<String, String> defines, GPUConfig.Backend backend) {
        this.id = id;
        this.source = source;
        this.defines = Map.copyOf(defines);
        this.backend = backend;
    }
    
    public MockShader(String source, Map<String, String> defines, GPUConfig.Backend backend) {
        this(idGenerator.getAndIncrement(), source, defines, backend);
    }
    
    @Override
    public boolean compile(String source, Map<String, String> defines) {
        if (compiled) {
            return true;
        }
        
        // Use provided source and defines or fall back to instance fields
        var actualSource = source != null ? source : this.source;
        var actualDefines = defines != null ? defines : this.defines;
        
        // Simulate compilation based on backend
        try {
            switch (backend) {
                case OPENGL -> compileOpenGL(actualSource);
                case BGFX_METAL -> compileBGFXMetal(actualSource);
                case BGFX_VULKAN -> compileBGFXVulkan(actualSource);
                case BGFX_OPENGL -> compileOpenGL(actualSource);
                case AUTO -> compileOpenGL(actualSource); // Auto defaults to OpenGL for mock
            }
            compiled = true;
            return true;
        } catch (Exception e) {
            compilationError = e.getMessage();
            return false;
        }
    }
    
    public boolean compile() {
        return compile(this.source, this.defines);
    }
    
    private void compileOpenGL(String source) {
        // Simulate OpenGL compute shader compilation
        if (source.contains("#version") && !source.contains("430")) {
            throw new RuntimeException("OpenGL compute shaders require #version 430 or higher");
        }
        
        if (!source.contains("layout(local_size_x")) {
            throw new RuntimeException("OpenGL compute shader missing local work group size specification");
        }
        
        // Check for unsupported OpenGL features
        if (source.contains("atomic_fetch_add_explicit")) {
            throw new RuntimeException("Metal-specific atomic functions not supported in OpenGL");
        }
    }
    
    private void compileBGFXMetal(String source) {
        // Simulate BGFX Metal shader compilation
        if (source.contains("gl_GlobalInvocationID")) {
            throw new RuntimeException("GLSL built-ins must be translated to Metal equivalents");
        }
        
        if (source.contains("atomicAdd") && !source.contains("atomic_fetch_add_explicit")) {
            throw new RuntimeException("GLSL atomic functions must be translated to Metal equivalents");
        }
        
        // Validate Metal-specific requirements
        if (!source.contains("using namespace metal")) {
            throw new RuntimeException("Metal shaders must include 'using namespace metal' directive");
        }
    }
    
    private void compileBGFXVulkan(String source) {
        // Simulate BGFX Vulkan shader compilation
        if (source.contains("atomic_fetch_add_explicit")) {
            throw new RuntimeException("Metal-specific atomic functions not supported in Vulkan");
        }
        
        // Check for Vulkan-specific requirements
        if (!source.contains("layout(binding")) {
            throw new RuntimeException("Vulkan shaders require explicit binding layouts");
        }
    }
    
    public boolean bind() {
        if (!compiled) {
            throw new IllegalStateException("Shader must be compiled before binding");
        }
        
        bound = true;
        return true;
    }
    
    public void unbind() {
        bound = false;
    }
    
    public void dispatch(int groupsX, int groupsY, int groupsZ) {
        if (!bound) {
            throw new IllegalStateException("Shader must be bound before dispatch");
        }
        
        if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
            throw new IllegalArgumentException("Dispatch groups must be positive");
        }
        
        // Simulate compute dispatch based on backend
        simulateDispatch(groupsX, groupsY, groupsZ);
    }
    
    private void simulateDispatch(int groupsX, int groupsY, int groupsZ) {
        // Simulate work group execution
        var totalGroups = (long) groupsX * groupsY * groupsZ;
        
        switch (backend) {
            case OPENGL -> {
                // OpenGL compute dispatch simulation
                if (totalGroups > 65535) {
                    throw new RuntimeException("OpenGL dispatch group count exceeds limits");
                }
            }
            case BGFX_METAL -> {
                // Metal compute dispatch simulation
                if (totalGroups > 2147483647L) {
                    throw new RuntimeException("Metal dispatch group count exceeds limits");
                }
            }
            case BGFX_VULKAN -> {
                // Vulkan compute dispatch simulation
                if (groupsX > 65535 || groupsY > 65535 || groupsZ > 65535) {
                    throw new RuntimeException("Vulkan dispatch group dimensions exceed limits");
                }
            }
            case BGFX_OPENGL -> {
                // BGFX OpenGL compute dispatch simulation
                if (totalGroups > 65535) {
                    throw new RuntimeException("BGFX OpenGL dispatch group count exceeds limits");
                }
            }
            case AUTO -> {
                // Auto backend uses OpenGL limits
                if (totalGroups > 65535) {
                    throw new RuntimeException("Auto backend dispatch group count exceeds limits");
                }
            }
        }
    }
    
    public void setBuffer(int binding, IGPUBuffer buffer) {
        if (!bound) {
            throw new IllegalStateException("Shader must be bound before setting buffers");
        }
        
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        
        // Validate buffer compatibility with backend
        validateBufferCompatibility(buffer);
    }
    
    private void validateBufferCompatibility(IGPUBuffer buffer) {
        var bufferBackend = getBufferBackend(buffer);
        if (bufferBackend != backend) {
            throw new IllegalArgumentException(
                String.format("Buffer backend %s incompatible with shader backend %s", 
                    bufferBackend, backend)
            );
        }
    }
    
    private GPUConfig.Backend getBufferBackend(IGPUBuffer buffer) {
        if (buffer instanceof MockBuffer mockBuffer) {
            return mockBuffer.getBackend();
        }
        
        // For real buffers, infer from native handle
        var handle = buffer.getNativeHandle();
        if (handle instanceof Integer) {
            return GPUConfig.Backend.OPENGL;
        } else if (handle instanceof String str) {
            if (str.startsWith("metal_")) {
                return GPUConfig.Backend.BGFX_METAL;
            } else if (str.startsWith("vk_")) {
                return GPUConfig.Backend.BGFX_VULKAN;
            }
        }
        
        return GPUConfig.Backend.OPENGL; // Default fallback
    }
    
    @Override
    public void setUniform(String name, Object value) {
        if (!bound) {
            throw new IllegalStateException("Shader must be bound before setting uniforms");
        }
        
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Uniform name cannot be null or empty");
        }
        
        if (value == null) {
            throw new IllegalArgumentException("Uniform value cannot be null");
        }
        
        // Validate uniform type compatibility
        validateUniformType(value);
    }
    
    private void validateUniformType(Object value) {
        // Check supported uniform types
        if (!(value instanceof Integer || value instanceof Float || 
              value instanceof int[] || value instanceof float[])) {
            throw new IllegalArgumentException("Unsupported uniform type: " + value.getClass());
        }
    }
    
    @Override
    public Map<String, String> getUniforms() {
        // Return mock uniforms for testing
        Map<String, String> uniforms = new HashMap<>();
        uniforms.put("u_time", "float");
        uniforms.put("u_resolution", "vec2");
        uniforms.put("u_modelMatrix", "mat4");
        return uniforms;
    }
    
    @Override
    public boolean hasUniform(String name) {
        return getUniforms().containsKey(name);
    }
    
    @Override
    public int[] getWorkGroupSize() {
        return new int[]{64, 1, 1}; // Mock work group size
    }
    
    @Override
    public void setUniformVector(String name, float... values) {
        setUniform(name, values);
    }
    
    @Override
    public void setUniformMatrix(String name, float[] matrix) {
        setUniform(name, matrix);
    }
    
    @Override
    public void setUniformInt(String name, int value) {
        setUniform(name, value);
    }
    
    @Override
    public void setUniformFloat(String name, float value) {
        setUniform(name, value);
    }
    
    @Override
    public boolean isValid() {
        return compiled;
    }
    
    @Override
    public String getCompilationLog() {
        return compilationError != null ? compilationError : "No errors";
    }
    
    @Override
    public Object getNativeHandle() {
        return switch (backend) {
            case OPENGL -> (int) id;
            case BGFX_METAL -> "metal_shader_" + id;
            case BGFX_VULKAN -> "vk_shader_" + id;
            case BGFX_OPENGL -> "bgfx_gl_shader_" + id;
            case AUTO -> "auto_shader_" + id;
        };
    }
    
    @Override
    public void destroy() {
        unbind();
        compiled = false;
        compilationError = null;
        compilationResult = null;
    }
    
    public void cleanup() {
        destroy();
    }
    
    // Additional methods for testing
    
    public long getId() {
        return id;
    }
    
    public String getSource() {
        return source;
    }
    
    public Map<String, String> getDefines() {
        return defines;
    }
    
    public GPUConfig.Backend getBackend() {
        return backend;
    }
    
    public boolean isCompiled() {
        return compiled;
    }
    
    public boolean isBound() {
        return bound;
    }
    
    public String getCompilationError() {
        return compilationError;
    }
    
    public void setCompilationResult(IShaderFactory.CompilationResult result) {
        this.compilationResult = result;
        if (result != null && result.isSuccess()) {
            this.compiled = true;
            this.compilationError = null;
        } else if (result != null) {
            this.compiled = false;
            this.compilationError = result.getErrorMessage().orElse("Unknown compilation error");
        }
    }
    
    public IShaderFactory.CompilationResult getCompilationResult() {
        return compilationResult;
    }
    
    /**
     * Creates a mock shader with realistic test source code for the specified backend.
     */
    public static MockShader createTestShader(GPUConfig.Backend backend) {
        var source = switch (backend) {
            case OPENGL -> """
                #version 430
                layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
                
                layout(std430, binding = 0) restrict readonly buffer InputBuffer {
                    uint inputData[];
                };
                
                layout(std430, binding = 1) restrict writeonly buffer OutputBuffer {
                    uint outputData[];
                };
                
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index < inputData.length()) {
                        outputData[index] = inputData[index] * 2;
                    }
                }
                """;
            case BGFX_METAL -> """
                #include <metal_stdlib>
                using namespace metal;
                
                kernel void computeMain(
                    device const uint* inputData [[buffer(0)]],
                    device uint* outputData [[buffer(1)]],
                    uint3 gid [[thread_position_in_grid]]
                ) {
                    uint index = gid.x;
                    outputData[index] = inputData[index] * 2;
                }
                """;
            case BGFX_VULKAN -> """
                #version 450
                layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
                
                layout(binding = 0) restrict readonly buffer InputBuffer {
                    uint inputData[];
                };
                
                layout(binding = 1) restrict writeonly buffer OutputBuffer {
                    uint outputData[];
                };
                
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index < inputData.length()) {
                        outputData[index] = inputData[index] * 2;
                    }
                }
                """;
            case BGFX_OPENGL -> """
                #version 430
                layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
                
                layout(std430, binding = 0) restrict readonly buffer InputBuffer {
                    uint inputData[];
                };
                
                layout(std430, binding = 1) restrict writeonly buffer OutputBuffer {
                    uint outputData[];
                };
                
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index < inputData.length()) {
                        outputData[index] = inputData[index] * 2;
                    }
                }
                """;
            case AUTO -> """
                // Auto backend test shader
                #version 430
                layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
                void main() {
                    // Placeholder for auto-detected backend
                }
                """;
        };
        
        return new MockShader(source, Map.of(), backend);
    }
}
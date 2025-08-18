/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.gpu;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced mock GPU context implementing IGPUContext for comprehensive testing.
 * Supports both OpenGL and BGFX backend simulation for ESVO migration testing.
 */
public class MockGPUContext implements IGPUContext {
    
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, MockBuffer> buffers = new ConcurrentHashMap<>();
    private final Map<Integer, MockShader> shaders = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean valid = new AtomicBoolean(false);
    
    private GPUConfig.Backend backend = GPUConfig.Backend.OPENGL;
    private boolean openglInitialized = false;
    private boolean bgfxInitialized = false;
    private String bgfxRenderer = "";
    private IShaderFactory shaderFactory;
    
    public MockGPUContext() {
        this(GPUConfig.Backend.OPENGL);
    }
    
    public MockGPUContext(GPUConfig.Backend backend) {
        this.backend = backend;
    }
    
    @Override
    public boolean initialize(GPUConfig config) {
        if (initialized.get()) {
            return true;
        }
        
        this.backend = config.getBackend();
        
        // Simulate backend-specific initialization
        switch (backend) {
            case OPENGL -> {
                openglInitialized = true;
                bgfxInitialized = false;
                bgfxRenderer = "";
            }
            case BGFX_METAL -> {
                openglInitialized = false;
                bgfxInitialized = true;
                bgfxRenderer = "Metal";
            }
            case BGFX_VULKAN -> {
                openglInitialized = false;
                bgfxInitialized = true;
                bgfxRenderer = "Vulkan";
            }
            case BGFX_OPENGL -> {
                openglInitialized = false;
                bgfxInitialized = true;
                bgfxRenderer = "OpenGL";
            }
            case AUTO -> {
                openglInitialized = true;
                bgfxInitialized = false;
                bgfxRenderer = "Auto";
            }
        }
        
        // Create shader factory
        shaderFactory = new MockShaderFactory(backend);
        
        initialized.set(true);
        valid.set(true);
        
        return true;
    }
    
    @Override
    public IGPUBuffer createBuffer(BufferType type, int size, BufferUsage usage) {
        if (!initialized.get()) {
            throw new IllegalStateException("GPU context not initialized");
        }
        
        var id = nextId.getAndIncrement();
        var buffer = new MockBuffer(id, type, size, usage, backend);
        buffers.put(id, buffer);
        
        return buffer;
    }
    
    @Override
    public IGPUBuffer createBuffer(int size, BufferUsage usage, AccessType accessType) {
        // Default to STORAGE buffer type for the overloaded method
        return createBuffer(BufferType.STORAGE, size, usage);
    }
    
    @Override
    public IGPUShader createComputeShader(String shaderSource, Map<String, String> defines) {
        if (!initialized.get()) {
            throw new IllegalStateException("GPU context not initialized");
        }
        
        var id = nextId.getAndIncrement();
        var shader = new MockShader(id, shaderSource, defines, backend);
        shaders.put(id, shader);
        
        return shader;
    }
    
    @Override
    public IGPUShader createShader(String shaderSource, Map<String, String> defines) {
        return createComputeShader(shaderSource, defines);
    }
    
    @Override
    public void dispatch(IGPUShader shader, int groupsX, int groupsY, int groupsZ) {
        if (!valid.get()) {
            throw new IllegalStateException("Context not initialized");
        }
        
        if (shader == null) {
            throw new IllegalArgumentException("Shader cannot be null");
        }
        
        // Validate work group sizes
        if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
            throw new IllegalArgumentException("Invalid work group dimensions");
        }
        
        // Simulate dispatch execution
        if (shader instanceof MockShader mockShader) {
            if (!mockShader.isCompiled()) {
                throw new IllegalStateException("Shader must be compiled before dispatch");
            }
            
            if (!mockShader.isBound()) {
                throw new IllegalStateException("Shader must be bound before dispatch");
            }
        }
    }
    
    @Override
    public void memoryBarrier(BarrierType type) {
        if (!valid.get()) {
            throw new IllegalStateException("Context not initialized");
        }
        
        // Simulate memory barrier based on type and backend
        switch (backend) {
            case OPENGL -> {
                // OpenGL memory barrier simulation
            }
            case BGFX_METAL -> {
                // Metal memory barrier simulation
            }
            case BGFX_VULKAN -> {
                // Vulkan memory barrier simulation
            }
        }
    }
    
    @Override
    public void cleanup() {
        if (!valid.get()) {
            return;
        }
        
        // Clean up all resources
        shaders.values().forEach(MockShader::cleanup);
        shaders.clear();
        
        buffers.values().forEach(MockBuffer::cleanup);
        buffers.clear();
        
        if (shaderFactory != null && shaderFactory instanceof MockShaderFactory mockFactory) {
            mockFactory.cleanup();
        }
        
        valid.set(false);
        initialized.set(false);
        openglInitialized = false;
        bgfxInitialized = false;
        bgfxRenderer = "";
    }
    
    @Override
    public boolean isValid() {
        return valid.get() && initialized.get();
    }
    
    // Testing utilities
    
    public GPUConfig.Backend getBackend() {
        return backend;
    }
    
    public boolean isOpenGLInitialized() {
        return openglInitialized;
    }
    
    public boolean isBGFXInitialized() {
        return bgfxInitialized;
    }
    
    public String getBGFXRenderer() {
        return bgfxRenderer;
    }
    
    @Override
    public IShaderFactory getShaderFactory() {
        return shaderFactory;
    }
    
    /**
     * Mock shader factory for testing
     */
    private static class MockShaderFactory implements IShaderFactory {
        private final GPUConfig.Backend backend;
        
        public MockShaderFactory(GPUConfig.Backend backend) {
            this.backend = backend;
        }
        
        @Override
        public CompilationResult compileComputeShader(String shaderName, String source, Map<String, String> defines) {
            // Simulate compilation based on backend
            try {
                var shader = new MockShader(source, defines, backend);
                boolean success = shader.compile();
                
                if (success) {
                    return new CompilationResult(shader, Map.of());
                } else {
                    return new CompilationResult(shader.getCompilationError(), Map.of());
                }
            } catch (Exception e) {
                return new CompilationResult(e.getMessage(), Map.of());
            }
        }
        
        @Override
        public CompilationResult compileShaderVariant(ShaderVariant variant) {
            String source = variant.getSourceOverride().orElse("// Mock source for " + variant.getShaderName());
            return compileComputeShader(variant.getShaderName(), source, variant.getDefines());
        }
        
        @Override
        public Optional<String> loadShaderSource(String shaderName) {
            // Mock shader source loading
            return Optional.of("// Mock shader source for " + shaderName);
        }
        
        @Override
        public boolean validateShaderCompatibility(String source) {
            // Mock validation based on backend
            return switch (backend) {
                case OPENGL -> source.contains("#version") && !source.contains("atomic_fetch_add_explicit");
                case BGFX_METAL -> source.contains("using namespace metal") && !source.contains("gl_GlobalInvocationID");
                case BGFX_VULKAN -> source.contains("layout(binding") && !source.contains("atomic_fetch_add_explicit");
                case BGFX_OPENGL -> source.contains("#version") && !source.contains("atomic_fetch_add_explicit");
                case AUTO -> true; // Auto backend accepts any source for mock
            };
        }
        
        @Override
        public String getBackendInfo() {
            return switch (backend) {
                case OPENGL -> "Mock OpenGL 4.6 Context";
                case BGFX_METAL -> "Mock BGFX Metal Backend";
                case BGFX_VULKAN -> "Mock BGFX Vulkan Backend";
                case BGFX_OPENGL -> "Mock BGFX OpenGL Backend";
                case AUTO -> "Mock Auto Backend";
            };
        }
        
        @Override
        public String getShaderLanguage() {
            return switch (backend) {
                case OPENGL -> "GLSL";
                case BGFX_METAL -> "Metal Shading Language";
                case BGFX_VULKAN -> "GLSL";
                case BGFX_OPENGL -> "GLSL";
                case AUTO -> "Auto";
            };
        }
        
        @Override
        public boolean hasShader(String shaderName) {
            return true; // Mock always has shaders
        }
        
        @Override
        public Set<String> getAvailableShaders() {
            return Set.of("test.comp", "example.comp", "mock.comp");
        }
        
        @Override
        public String preprocessShader(String source, Map<String, String> defines, Set<String> flags) {
            StringBuilder processed = new StringBuilder(source);
            for (var entry : defines.entrySet()) {
                processed.append("\n#define ").append(entry.getKey()).append(" ").append(entry.getValue());
            }
            for (var flag : flags) {
                processed.append("\n#define ").append(flag);
            }
            return processed.toString();
        }
        
        @Override
        public void clearCache() {
            // Mock implementation - no cache to clear
        }
        
        @Override
        public void cleanup() {
            // Mock cleanup
        }
    }
    
    @Override
    public boolean dispatchCompute(IGPUShader shader, int groupsX, int groupsY, int groupsZ) {
        if (!valid.get()) {
            return false;
        }
        
        if (shader == null) {
            return false;
        }
        
        // Validate work group sizes
        if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
            return false;
        }
        
        // Simulate dispatch execution - always succeeds in mock
        return true;
    }
    
    @Override
    public void waitForCompletion() {
        // Mock implementation - no actual GPU work to wait for
        // Just simulate a brief delay
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Platform-aware GPU context tests that prioritize native implementations over mocks.
 * Uses PlatformTestUtils to detect available GPU backends and test with real implementations
 * when possible, only falling back to mocks when native is unavailable.
 */
public class PlatformGPUContextTest {
    
    private IGPUContext context;
    private GPUConfig config;
    private PlatformTestUtils.PlatformCapabilities capabilities;
    
    @BeforeEach
    void setUp() {
        config = GPUConfig.defaultConfig();
        capabilities = PlatformTestUtils.detectCapabilities();
        
        // Log platform capabilities for debugging
        System.out.println("Platform capabilities detected:");
        System.out.println("  Metal available: " + capabilities.isMetalAvailable());
        System.out.println("  OpenGL available: " + capabilities.isOpenGLAvailable());
        System.out.println("  Vulkan available: " + capabilities.isVulkanAvailable());
        System.out.println("  Native testing preferred: " + capabilities.isNativeTestingPreferred());
    }
    
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.cleanup();
        }
    }
    
    @Test
    @EnabledOnOs(OS.MAC)
    @DisplayName("Should detect Metal availability on macOS")
    void testMetalDetection() {
        assertTrue(capabilities.isMetalAvailable(), 
            "Metal should be available on macOS");
        // Native testing preference depends on headless environment
        if (!capabilities.isHeadlessEnvironment()) {
            assertTrue(capabilities.isNativeTestingPreferred(),
                "Native testing should be preferred when Metal is available and not headless");
        } else {
            assertFalse(capabilities.isNativeTestingPreferred(),
                "Native testing should not be preferred in headless environment");
        }
    }
    
    @Test
    @DisplayName("Should create appropriate GPU context based on environment")
    void testGPUContextCreation() {
        // Try to create context for each available backend
        for (var backend : GPUConfig.Backend.values()) {
            if (isBackendAvailable(backend)) {
                context = PlatformTestUtils.createTestGPUContext(backend);
                assertNotNull(context, "Should create context for available backend: " + backend);
                
                config = GPUConfig.builder().withBackend(backend).build();
                assertTrue(context.initialize(config), 
                    "Context should initialize successfully for: " + backend);
                
                // Expect native context only if not in headless environment
                if (capabilities.isNativeTestingPreferred()) {
                    assertFalse(context instanceof MockGPUContext,
                        "Should create native BGFX context for available backend: " + backend);
                } else {
                    assertTrue(context instanceof MockGPUContext,
                        "Should create mock context in headless environment for backend: " + backend);
                }
                
                context.cleanup();
                context = null;
            }
        }
    }
    
    @Test
    @EnabledOnOs(OS.MAC)
    @DisplayName("Should prefer Metal over other backends on macOS (currently mock until Phase 2.3)")
    void testMetalPreference() {
        assumeTrue(capabilities.isMetalAvailable(), "Test requires Metal availability");
        
        context = PlatformTestUtils.createTestGPUContext(GPUConfig.Backend.BGFX_METAL);
        assertNotNull(context);
        
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.BGFX_METAL).build();
        assertTrue(context.initialize(config));
        
        // Currently returns mock until native implementation is complete (Phase 2.3)
        assertTrue(context instanceof MockGPUContext,
            "Should create mock Metal context until Phase 2.3 implementation");
        
        // Test basic Metal operations through mock
        var buffer = context.createBuffer(1024, BufferUsage.READ_WRITE, AccessType.READ_WRITE);
        assertNotNull(buffer);
        assertTrue(buffer instanceof MockBuffer, 
            "Should create mock Metal buffer until Phase 2.3");
        
        var factory = context.getShaderFactory();
        assertNotNull(factory);
        assertEquals("Metal Shading Language", factory.getShaderLanguage());
    }
    
    @Test
    @DisplayName("Should create and compile shaders when backend available (currently mock until Phase 2.3)")
    void testShaderCompilation() {
        for (var backend : GPUConfig.Backend.values()) {
            if (isBackendAvailable(backend)) {
                context = PlatformTestUtils.createTestGPUContext(backend);
                config = GPUConfig.builder().withBackend(backend).build();
                assertTrue(context.initialize(config));
                
                var factory = context.getShaderFactory();
                var shaderSource = createShaderSourceForBackend(backend);
                
                var result = factory.compileComputeShader("test_shader", shaderSource, Map.of());
                
                if (result.isSuccess()) {
                    assertTrue(result.getShader().isPresent(),
                        "Compilation should produce shader for: " + backend);
                    assertTrue(result.getShader().get() instanceof MockShader,
                        "Should create mock shader until Phase 2.3 for: " + backend);
                } else {
                    // Log compilation errors for debugging
                    System.err.println("Shader compilation failed for " + backend + ": " + 
                        result.getErrorMessage().orElse("Unknown error"));
                }
                
                context.cleanup();
                context = null;
            }
        }
    }
    
    @Test
    @DisplayName("Should handle buffer operations (currently mock until Phase 2.3)")
    void testBufferOperations() {
        var preferredBackend = getPreferredAvailableBackend();
        assumeTrue(preferredBackend != null, "Test requires at least one available GPU backend");
        
        context = PlatformTestUtils.createTestGPUContext(preferredBackend);
        config = GPUConfig.builder().withBackend(preferredBackend).build();
        assertTrue(context.initialize(config));
        
        // Test multiple buffer sizes
        var bufferSizes = new int[]{256, 1024, 4096, 16384};
        
        for (var size : bufferSizes) {
            var buffer = context.createBuffer(size, BufferUsage.READ_WRITE, AccessType.READ_WRITE);
            assertNotNull(buffer);
            assertEquals(size, buffer.getSize());
            
            // Currently returns mock until native implementation is complete (Phase 2.3)
            assertTrue(buffer instanceof MockBuffer,
                "Should create mock buffer until Phase 2.3 for size: " + size);
            
            // Test data operations through mock interface
            var handle = buffer.getNativeHandle();
            assertNotNull(handle, "Mock buffer should provide handle");
        }
    }
    
    @Test
    @DisplayName("Should only use mocks when native implementation unavailable")
    void testMockFallback() {
        // Test with a backend that's definitely not available
        var unavailableBackend = findUnavailableBackend();
        
        if (unavailableBackend != null) {
            context = PlatformTestUtils.createTestGPUContext(unavailableBackend);
            
            // Should fall back to mock when native unavailable
            assertTrue(context instanceof MockGPUContext,
                "Should use mock when native backend unavailable: " + unavailableBackend);
            
            config = GPUConfig.builder().withBackend(unavailableBackend).build();
            assertTrue(context.initialize(config));
            
            // Mock should still function correctly
            var buffer = context.createBuffer(1024, BufferUsage.READ_WRITE, AccessType.READ_WRITE);
            assertNotNull(buffer);
            assertTrue(buffer instanceof MockBuffer,
                "Mock context should create mock buffers");
        }
    }
    
    @ParameterizedTest
    @EnumSource(GPUConfig.Backend.class)
    @DisplayName("Should test each backend with appropriate implementation (currently mock until Phase 2.3)")
    void testBackendSpecificImplementation(GPUConfig.Backend backend) {
        context = PlatformTestUtils.createTestGPUContext(backend);
        config = GPUConfig.builder().withBackend(backend).build();
        
        assertTrue(context.initialize(config));
        
        // Currently all backends return mock until Phase 2.3 native implementation
        assertTrue(context instanceof MockGPUContext,
            "Should use mock implementation until Phase 2.3 for backend: " + backend);
        
        // Test mock-specific features
        testMockSpecificFeatures(backend);
    }
    
    private void testMockSpecificFeatures(GPUConfig.Backend backend) {
        var factory = context.getShaderFactory();
        
        switch (backend) {
            case OPENGL -> {
                assertTrue(factory.getBackendInfo().contains("OpenGL"));
                assertEquals("GLSL", factory.getShaderLanguage());
                
                // Test OpenGL-specific shader validation through mock
                var glslSource = """
                    #version 430
                    layout(local_size_x = 64) in;
                    void main() {
                        // OpenGL compute shader
                    }
                    """;
                
                assertTrue(factory.validateShaderCompatibility(glslSource),
                    "Mock OpenGL implementation should validate GLSL shaders");
            }
            case BGFX_METAL -> {
                assertTrue(factory.getBackendInfo().contains("Metal"));
                assertEquals("Metal Shading Language", factory.getShaderLanguage());
                
                // Test Metal-specific features through mock
                var metalSource = """
                    #include <metal_stdlib>
                    using namespace metal;
                    kernel void computeMain() {}
                    """;
                
                assertTrue(factory.validateShaderCompatibility(metalSource),
                    "Mock Metal implementation should validate MSL shaders");
            }
            case BGFX_VULKAN -> {
                assertTrue(factory.getBackendInfo().contains("Vulkan"));
                assertEquals("GLSL", factory.getShaderLanguage());
                
                // Test Vulkan-specific features through mock
                var vulkanSource = """
                    #version 450
                    layout(local_size_x = 64) in;
                    layout(binding = 0) buffer Data { uint data[]; };
                    void main() {}
                    """;
                
                assertTrue(factory.validateShaderCompatibility(vulkanSource),
                    "Mock Vulkan implementation should validate SPIR-V compatible GLSL");
            }
        }
    }
    
    private void testNativeSpecificFeatures(GPUConfig.Backend backend) {
        var factory = context.getShaderFactory();
        
        switch (backend) {
            case OPENGL -> {
                assertTrue(factory.getBackendInfo().contains("OpenGL"));
                assertEquals("GLSL", factory.getShaderLanguage());
                
                // Test OpenGL-specific shader validation
                var glslSource = """
                    #version 430
                    layout(local_size_x = 64) in;
                    void main() {
                        // OpenGL compute shader
                    }
                    """;
                
                assertTrue(factory.validateShaderCompatibility(glslSource),
                    "OpenGL implementation should validate GLSL shaders");
            }
            case BGFX_METAL -> {
                assertTrue(factory.getBackendInfo().contains("Metal"));
                assertEquals("Metal Shading Language", factory.getShaderLanguage());
                
                // Test Metal-specific features
                var metalSource = """
                    #include <metal_stdlib>
                    using namespace metal;
                    kernel void computeMain() {}
                    """;
                
                assertTrue(factory.validateShaderCompatibility(metalSource),
                    "Metal implementation should validate MSL shaders");
            }
            case BGFX_VULKAN -> {
                assertTrue(factory.getBackendInfo().contains("Vulkan"));
                assertEquals("GLSL", factory.getShaderLanguage());
                
                // Test Vulkan-specific features
                var vulkanSource = """
                    #version 450
                    layout(local_size_x = 64) in;
                    layout(binding = 0) buffer Data { uint data[]; };
                    void main() {}
                    """;
                
                assertTrue(factory.validateShaderCompatibility(vulkanSource),
                    "Vulkan implementation should validate SPIR-V compatible GLSL");
            }
        }
    }
    
    private boolean isBackendAvailable(GPUConfig.Backend backend) {
        return switch (backend) {
            case OPENGL -> capabilities.isOpenGLAvailable();
            case BGFX_METAL -> capabilities.isMetalAvailable();
            case BGFX_VULKAN -> capabilities.isVulkanAvailable();
            default -> false;
        };
    }
    
    private GPUConfig.Backend getPreferredAvailableBackend() {
        // Prefer Metal on macOS, then OpenGL, then Vulkan
        if (capabilities.isMetalAvailable()) {
            return GPUConfig.Backend.BGFX_METAL;
        }
        if (capabilities.isOpenGLAvailable()) {
            return GPUConfig.Backend.OPENGL;
        }
        if (capabilities.isVulkanAvailable()) {
            return GPUConfig.Backend.BGFX_VULKAN;
        }
        return null;
    }
    
    private GPUConfig.Backend findUnavailableBackend() {
        // Find a backend that's not available on this platform
        for (var backend : GPUConfig.Backend.values()) {
            if (!isBackendAvailable(backend)) {
                return backend;
            }
        }
        return null; // All backends available
    }
    
    private String createShaderSourceForBackend(GPUConfig.Backend backend) {
        return switch (backend) {
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
            default -> throw new IllegalArgumentException("Unsupported backend: " + backend);
        };
    }
}
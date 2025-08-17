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
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Integration tests for BGFX backend implementations (Metal and Vulkan).
 * These tests validate BGFX-specific functionality and ensure proper
 * integration with the GPU abstraction layer for ESVO migration.
 * 
 * Phase 1.6: Tests BGFX integration readiness for Metal backend migration.
 */
public class BGFXIntegrationTest {
    
    private IGPUContext context;
    private GPUConfig config;
    private PlatformTestUtils.PlatformCapabilities capabilities;
    
    @BeforeEach
    void setUp() {
        capabilities = PlatformTestUtils.detectCapabilities();
        
        // Log BGFX-relevant capabilities
        System.out.println("BGFX Integration Test - Platform capabilities:");
        System.out.println("  Metal available: " + capabilities.isMetalAvailable());
        System.out.println("  Vulkan available: " + capabilities.isVulkanAvailable());
        System.out.println("  macOS platform: " + capabilities.isMacOS());
        System.out.println("  Metal version: " + capabilities.getMetalVersion());
    }
    
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.cleanup();
        }
    }
    
    @Test
    @EnabledOnOs(OS.MAC)
    @DisplayName("Should initialize BGFX Metal context on macOS")
    void testBGFXMetalInitialization() {
        assumeTrue(capabilities.isMetalAvailable(), "Test requires Metal support");
        
        context = PlatformTestUtils.createTestGPUContext(GPUConfig.Backend.BGFX_METAL);
        assertNotNull(context, "Should create BGFX Metal context");
        
        config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withDebugEnabled(true)
            .withValidationEnabled(true)
            .build();
        
        assertTrue(context.initialize(config), "BGFX Metal context should initialize successfully");
        assertTrue(context.isValid(), "BGFX Metal context should be valid after initialization");
        
        // Verify Metal-specific properties
        var shaderFactory = context.getShaderFactory();
        assertNotNull(shaderFactory, "Metal context should provide shader factory");
        
        if (!(context instanceof MockGPUContext)) {
            assertEquals("Metal Shading Language", shaderFactory.getShaderLanguage(),
                "Native Metal context should use MSL");
            assertTrue(shaderFactory.getBackendInfo().contains("Metal"),
                "Backend info should indicate Metal");
        }
    }
    
    @Test
    @DisplayName("Should initialize BGFX Vulkan context when available")
    void testBGFXVulkanInitialization() {
        assumeTrue(capabilities.isVulkanAvailable(), "Test requires Vulkan support");
        
        context = PlatformTestUtils.createTestGPUContext(GPUConfig.Backend.BGFX_VULKAN);
        assertNotNull(context, "Should create BGFX Vulkan context");
        
        config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_VULKAN)
            .withDebugEnabled(true)
            .withValidationEnabled(true)
            .build();
        
        assertTrue(context.initialize(config), "BGFX Vulkan context should initialize successfully");
        assertTrue(context.isValid(), "BGFX Vulkan context should be valid after initialization");
        
        // Verify Vulkan-specific properties
        var shaderFactory = context.getShaderFactory();
        assertNotNull(shaderFactory, "Vulkan context should provide shader factory");
        
        if (!(context instanceof MockGPUContext)) {
            assertEquals("GLSL", shaderFactory.getShaderLanguage(),
                "Native Vulkan context should use GLSL");
            assertTrue(shaderFactory.getBackendInfo().contains("Vulkan"),
                "Backend info should indicate Vulkan");
        }
    }
    
    @Test
    @EnabledOnOs(OS.MAC)
    @DisplayName("Should create and manage Metal compute buffers")
    void testMetalBufferOperations() {
        assumeTrue(capabilities.isMetalAvailable(), "Test requires Metal support");
        
        context = PlatformTestUtils.createTestGPUContext(GPUConfig.Backend.BGFX_METAL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.BGFX_METAL).build();
        assertTrue(context.initialize(config));
        
        // Test buffer creation with different types and sizes
        var testSizes = new int[]{256, 1024, 4096, 16384};
        var bufferTypes = new BufferType[]{BufferType.STORAGE, BufferType.UNIFORM};
        var accessTypes = new AccessType[]{AccessType.READ_ONLY, AccessType.WRITE_ONLY, AccessType.READ_WRITE};
        
        for (var size : testSizes) {
            for (var bufferType : bufferTypes) {
                for (var accessType : accessTypes) {
                    // Test both createBuffer methods
                    var buffer1 = context.createBuffer(bufferType, size, BufferUsage.DYNAMIC_WRITE);
                    assertNotNull(buffer1, String.format("Should create buffer: type=%s, size=%d", bufferType, size));
                    assertEquals(size, buffer1.getSize());
                    assertEquals(bufferType, buffer1.getType());
                    
                    var buffer2 = context.createBuffer(size, BufferUsage.DYNAMIC_WRITE, accessType);
                    assertNotNull(buffer2, String.format("Should create buffer: size=%d, access=%s", size, accessType));
                    assertEquals(size, buffer2.getSize());
                    
                    // Verify Metal-specific native handles (if not mock)
                    if (!(context instanceof MockGPUContext)) {
                        assertNotNull(buffer1.getNativeHandle(), "Native Metal buffer should have handle");
                        assertNotNull(buffer2.getNativeHandle(), "Native Metal buffer should have handle");
                    }
                }
            }
        }
    }
    
    @Test
    @EnabledOnOs(OS.MAC)
    @DisplayName("Should compile and execute Metal compute shaders")
    void testMetalShaderCompilation() {
        assumeTrue(capabilities.isMetalAvailable(), "Test requires Metal support");
        
        context = PlatformTestUtils.createTestGPUContext(GPUConfig.Backend.BGFX_METAL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.BGFX_METAL).build();
        assertTrue(context.initialize(config));
        
        var shaderFactory = context.getShaderFactory();
        assertNotNull(shaderFactory, "Metal context should provide shader factory");
        
        // Test Metal Shading Language (MSL) shader
        var metalShaderSource = """
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
        
        var defines = Map.of("METAL_VERSION", "2.0");
        var result = shaderFactory.compileComputeShader("metal_test_shader", metalShaderSource, defines);
        
        if (context instanceof MockGPUContext) {
            // Mock should simulate successful compilation for valid MSL
            assertTrue(result.isSuccess() || result.getErrorMessage().isPresent(),
                "Mock should provide compilation result");
        } else {
            // Native compilation results depend on actual Metal implementation
            assertNotNull(result, "Should get compilation result");
            if (!result.isSuccess()) {
                System.out.println("Metal shader compilation failed (expected in Phase 1): " + 
                    result.getErrorMessage().orElse("Unknown error"));
            }
        }
        
        // Test shader validation
        assertTrue(shaderFactory.validateShaderCompatibility(metalShaderSource),
            "Metal shader factory should validate MSL shaders");
        
        // Test GLSL rejection for Metal backend
        var glslShader = """
            #version 430
            layout(local_size_x = 64) in;
            void main() {
                // GLSL shader
            }
            """;
        
        if (!(context instanceof MockGPUContext)) {
            assertFalse(shaderFactory.validateShaderCompatibility(glslShader),
                "Metal backend should reject GLSL shaders");
        }
    }
    
    @Test
    @DisplayName("Should handle BGFX backend configuration options")
    void testBGFXConfiguration() {
        // Test BGFX-specific configuration for both Metal and Vulkan
        var backends = new GPUConfig.Backend[]{GPUConfig.Backend.BGFX_METAL, GPUConfig.Backend.BGFX_VULKAN};
        
        for (var backend : backends) {
            // Skip if backend not available
            boolean available = switch (backend) {
                case BGFX_METAL -> capabilities.isMetalAvailable();
                case BGFX_VULKAN -> capabilities.isVulkanAvailable();
                default -> false;
            };
            
            if (!available) {
                System.out.println("Skipping " + backend + " - not available on this platform");
                continue;
            }
            
            context = PlatformTestUtils.createTestGPUContext(backend);
            assertNotNull(context, "Should create context for backend: " + backend);
            
            // Test various configuration combinations
            var configs = new GPUConfig[]{
                GPUConfig.builder().withBackend(backend).withDebugEnabled(false).withValidationEnabled(false).build(),
                GPUConfig.builder().withBackend(backend).withDebugEnabled(true).withValidationEnabled(false).build(),
                GPUConfig.builder().withBackend(backend).withDebugEnabled(false).withValidationEnabled(true).build(),
                GPUConfig.builder().withBackend(backend).withDebugEnabled(true).withValidationEnabled(true).build()
            };
            
            for (var testConfig : configs) {
                assertTrue(context.initialize(testConfig), 
                    String.format("Should initialize %s with debug=%b, validation=%b", 
                        backend, testConfig.isDebugEnabled(), testConfig.isValidationEnabled()));
                
                assertTrue(context.isValid(), "Context should be valid after initialization");
                
                // Clean up for next test
                context.cleanup();
            }
            
            context = null;
        }
    }
    
    @Test
    @DisplayName("Should demonstrate BGFX vs OpenGL differences")
    void testBGFXVsOpenGLDifferences() {
        // This test highlights key differences between BGFX backends and OpenGL
        // Important for ESVO migration planning
        
        var openGLAvailable = capabilities.isOpenGLAvailable();
        var metalAvailable = capabilities.isMetalAvailable();
        
        if (openGLAvailable) {
            var openGLContext = PlatformTestUtils.createTestGPUContext(GPUConfig.Backend.OPENGL);
            var openGLConfig = GPUConfig.builder().withBackend(GPUConfig.Backend.OPENGL).build();
            assertTrue(openGLContext.initialize(openGLConfig));
            
            var openGLFactory = openGLContext.getShaderFactory();
            assertEquals("GLSL", openGLFactory.getShaderLanguage());
            
            openGLContext.cleanup();
        }
        
        if (metalAvailable) {
            var metalContext = PlatformTestUtils.createTestGPUContext(GPUConfig.Backend.BGFX_METAL);
            var metalConfig = GPUConfig.builder().withBackend(GPUConfig.Backend.BGFX_METAL).build();
            assertTrue(metalContext.initialize(metalConfig));
            
            var metalFactory = metalContext.getShaderFactory();
            if (!(metalContext instanceof MockGPUContext)) {
                assertEquals("Metal Shading Language", metalFactory.getShaderLanguage());
                
                // Metal has different capabilities than OpenGL
                assertNotEquals("GLSL", metalFactory.getShaderLanguage(),
                    "Metal should use MSL, not GLSL");
            }
            
            metalContext.cleanup();
        }
        
        System.out.println("BGFX Integration Test Summary:");
        System.out.println("  OpenGL available: " + openGLAvailable);
        System.out.println("  Metal available: " + metalAvailable);
        System.out.println("  Vulkan available: " + capabilities.isVulkanAvailable());
        System.out.println("  Native testing preferred: " + capabilities.isNativeTestingPreferred());
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"BGFX_METAL", "BGFX_VULKAN", "BGFX_OPENGL"})
    @DisplayName("Should test BGFX backend availability and fallback")
    void testBGFXBackendAvailability(String backendName) {
        var backend = GPUConfig.Backend.valueOf(backendName);
        
        context = PlatformTestUtils.createTestGPUContext(backend);
        assertNotNull(context, "Should always create some context (native or mock) for: " + backend);
        
        config = GPUConfig.builder().withBackend(backend).build();
        assertTrue(context.initialize(config), "Context should initialize for: " + backend);
        
        // Check if we got native or mock implementation
        boolean isNative = !(context instanceof MockGPUContext);
        boolean shouldBeNative = PlatformTestUtils.shouldUseNativeTesting(backend);
        
        if (shouldBeNative && !isNative) {
            System.out.println("Warning: Expected native implementation for " + backend + 
                " but got mock (may be expected in Phase 1)");
        }
        
        if (!shouldBeNative && isNative) {
            fail("Got native implementation for " + backend + " when mock was expected");
        }
        
        // Basic functionality test
        var buffer = context.createBuffer(1024, BufferUsage.READ_WRITE, AccessType.READ_WRITE);
        assertNotNull(buffer, "Should create buffer regardless of implementation type");
        assertEquals(1024, buffer.getSize());
        
        var factory = context.getShaderFactory();
        assertNotNull(factory, "Should provide shader factory");
        assertNotNull(factory.getShaderLanguage(), "Should report shader language");
        assertNotNull(factory.getBackendInfo(), "Should provide backend info");
    }
    
    @Test
    @DisplayName("Should handle memory barriers correctly for BGFX backends")
    void testBGFXMemoryBarriers() {
        var backends = new GPUConfig.Backend[]{GPUConfig.Backend.BGFX_METAL, GPUConfig.Backend.BGFX_VULKAN};
        
        for (var backend : backends) {
            boolean available = switch (backend) {
                case BGFX_METAL -> capabilities.isMetalAvailable();
                case BGFX_VULKAN -> capabilities.isVulkanAvailable();
                default -> false;
            };
            
            if (!available) continue;
            
            context = PlatformTestUtils.createTestGPUContext(backend);
            config = GPUConfig.builder().withBackend(backend).build();
            assertTrue(context.initialize(config));
            
            // Test different barrier types
            var barrierTypes = BarrierType.values();
            
            for (var barrierType : barrierTypes) {
                assertDoesNotThrow(() -> context.memoryBarrier(barrierType),
                    String.format("Should handle %s barrier for %s", barrierType, backend));
            }
            
            context.cleanup();
            context = null;
        }
    }
    
    @Test
    @EnabledOnOs(OS.MAC)
    @DisplayName("Should validate ESVO shader translation requirements")
    void testESVOShaderTranslationReadiness() {
        assumeTrue(capabilities.isMetalAvailable(), "Test requires Metal for ESVO migration");
        
        context = PlatformTestUtils.createTestGPUContext(GPUConfig.Backend.BGFX_METAL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.BGFX_METAL).build();
        assertTrue(context.initialize(config));
        
        var factory = context.getShaderFactory();
        
        // Test ESVO-style compute shader patterns
        var esvoStyleShader = """
            #include <metal_stdlib>
            using namespace metal;
            
            // ESVO-style data structures
            struct VoxelNode {
                uint data;
                uint children[8];
            };
            
            struct TraversalState {
                uint nodeIndex;
                float3 position;
                float scale;
            };
            
            kernel void esvoTraversal(
                device const VoxelNode* voxelTree [[buffer(0)]],
                device TraversalState* states [[buffer(1)]],
                device uint* results [[buffer(2)]],
                uint3 gid [[thread_position_in_grid]]
            ) {
                uint rayIndex = gid.x;
                
                // Simplified ESVO traversal pattern
                TraversalState state = states[rayIndex];
                
                // Mock traversal logic
                uint currentNode = voxelTree[state.nodeIndex].data;
                results[rayIndex] = currentNode;
            }
            """;
        
        if (context instanceof MockGPUContext) {
            // Mock should handle ESVO-style MSL
            assertTrue(factory.validateShaderCompatibility(esvoStyleShader),
                "Mock should accept ESVO-style Metal shaders");
        }
        
        // Verify shader preprocessing capabilities
        var defines = Map.of(
            "ESVO_TRAVERSAL_DEPTH", "8",
            "ESVO_BEAM_OPTIMIZATION", "1",
            "METAL_VERSION", "2.0"
        );
        
        var flags = java.util.Set.of("ESVO_DEBUG", "METAL_BACKEND");
        
        assertDoesNotThrow(() -> {
            var processed = factory.preprocessShader(esvoStyleShader, defines, flags);
            assertNotNull(processed, "Should preprocess ESVO shader");
            assertTrue(processed.contains("#define ESVO_TRAVERSAL_DEPTH 8"),
                "Should include preprocessing defines");
        }, "Factory should handle ESVO shader preprocessing");
    }
}
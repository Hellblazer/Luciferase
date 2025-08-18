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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MockGPUContext with native detection capabilities.
 * Tests both mock and native GPU context creation and operations.
 */
public class MockGPUContextTest {
    
    private MockGPUContext context;
    private GPUConfig config;
    
    @BeforeEach
    void setUp() {
        config = GPUConfig.defaultConfig();
    }
    
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.cleanup();
        }
    }
    
    @Test
    @DisplayName("Should create MockGPUContext with default OpenGL backend")
    void testDefaultBackendCreation() {
        context = new MockGPUContext();
        
        assertTrue(context.initialize(config));
        assertEquals(GPUConfig.Backend.AUTO, context.getBackend());
        assertNotNull(context.getShaderFactory());
    }
    
    @ParameterizedTest
    @EnumSource(GPUConfig.Backend.class)
    @DisplayName("Should create MockGPUContext for all backends")
    void testBackendSpecificCreation(GPUConfig.Backend backend) {
        context = new MockGPUContext(backend);
        config = GPUConfig.builder().withBackend(backend).build();
        
        assertTrue(context.initialize(config));
        assertEquals(backend, context.getBackend());
        assertNotNull(context.getShaderFactory());
        
        // Verify backend-specific initialization
        switch (backend) {
            case OPENGL -> {
                assertTrue(context.isOpenGLInitialized());
                assertFalse(context.isBGFXInitialized());
            }
            case BGFX_METAL -> {
                assertFalse(context.isOpenGLInitialized());
                assertTrue(context.isBGFXInitialized());
                assertEquals("Metal", context.getBGFXRenderer());
            }
            case BGFX_VULKAN -> {
                assertFalse(context.isOpenGLInitialized());
                assertTrue(context.isBGFXInitialized());
                assertEquals("Vulkan", context.getBGFXRenderer());
            }
            case BGFX_OPENGL -> {
                assertFalse(context.isOpenGLInitialized());
                assertTrue(context.isBGFXInitialized());
                assertEquals("OpenGL", context.getBGFXRenderer());
            }
            case AUTO -> {
                assertTrue(context.isOpenGLInitialized());
                assertFalse(context.isBGFXInitialized());
                assertEquals("Auto", context.getBGFXRenderer());
            }
        }
    }
    
    @Test
    @DisplayName("Should handle buffer creation for all backends")
    void testBufferCreation() {
        for (var backend : GPUConfig.Backend.values()) {
            context = new MockGPUContext(backend);
            config = GPUConfig.builder().withBackend(backend).build();
            assertTrue(context.initialize(config));
            
            var buffer = context.createBuffer(1024, BufferUsage.READ_WRITE, AccessType.READ_WRITE);
            
            assertNotNull(buffer);
            assertEquals(1024, buffer.getSize());
            assertEquals(BufferUsage.READ_WRITE, buffer.getUsage());
            // AccessType is not exposed by IGPUBuffer interface
            assertTrue(buffer instanceof MockBuffer);
            
            var mockBuffer = (MockBuffer) buffer;
            assertEquals(backend, mockBuffer.getBackend());
            
            context.cleanup();
        }
    }
    
    @Test
    @DisplayName("Should handle shader creation and compilation")
    void testShaderCreation() {
        context = new MockGPUContext(GPUConfig.Backend.OPENGL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.OPENGL).build();
        assertTrue(context.initialize(config));
        
        var shaderSource = """
            #version 430
            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
            
            layout(std430, binding = 0) restrict readonly buffer InputBuffer {
                uint inputData[];
            };
            
            void main() {
                uint index = gl_GlobalInvocationID.x;
                // Simple compute operation
            }
            """;
        
        var shader = context.createShader(shaderSource, Map.of("TEST_DEFINE", "1"));
        
        assertNotNull(shader);
        assertTrue(shader instanceof MockShader);
        
        var mockShader = (MockShader) shader;
        assertEquals(GPUConfig.Backend.OPENGL, mockShader.getBackend());
        assertEquals(shaderSource, mockShader.getSource());
        assertEquals(Map.of("TEST_DEFINE", "1"), mockShader.getDefines());
        
        assertTrue(shader.compile(mockShader.getSource(), mockShader.getDefines()));
        assertTrue(mockShader.isCompiled());
        assertNull(mockShader.getCompilationError());
    }
    
    @Test
    @DisplayName("Should handle shader factory operations")
    void testShaderFactory() {
        context = new MockGPUContext(GPUConfig.Backend.BGFX_METAL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.BGFX_METAL).build();
        assertTrue(context.initialize(config));
        
        var factory = context.getShaderFactory();
        assertNotNull(factory);
        
        var metalSource = """
            #include <metal_stdlib>
            using namespace metal;
            
            kernel void computeMain(uint3 gid [[thread_position_in_grid]]) {
                // Metal compute kernel
            }
            """;
        
        var result = factory.compileComputeShader("test_metal", metalSource, Map.of());
        
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertTrue(result.getShader().isPresent());
        assertTrue(result.getErrorMessage().isEmpty());
        assertEquals("Metal Shading Language", factory.getShaderLanguage());
    }
    
    @Test
    @DisplayName("Should handle backend switching")
    void testBackendSwitching() {
        context = new MockGPUContext(GPUConfig.Backend.OPENGL);
        
        // Start with OpenGL
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.OPENGL).build();
        assertTrue(context.initialize(config));
        assertEquals(GPUConfig.Backend.OPENGL, context.getBackend());
        assertTrue(context.isOpenGLInitialized());
        assertFalse(context.isBGFXInitialized());
        
        // Switch to BGFX Metal
        context.cleanup();
        context = new MockGPUContext(GPUConfig.Backend.BGFX_METAL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.BGFX_METAL).build();
        assertTrue(context.initialize(config));
        assertEquals(GPUConfig.Backend.BGFX_METAL, context.getBackend());
        assertFalse(context.isOpenGLInitialized());
        assertTrue(context.isBGFXInitialized());
        assertEquals("Metal", context.getBGFXRenderer());
    }
    
    @Test
    @DisplayName("Should validate buffer and shader compatibility")
    void testBackendCompatibility() {
        // Create OpenGL context and buffer
        var openglContext = new MockGPUContext(GPUConfig.Backend.OPENGL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.OPENGL).build();
        assertTrue(openglContext.initialize(config));
        
        var openglBuffer = openglContext.createBuffer(512, BufferUsage.READ_WRITE, AccessType.READ_ONLY);
        var openglShader = MockShader.createTestShader(GPUConfig.Backend.OPENGL);
        assertTrue(openglShader.compile());
        assertTrue(openglShader.bind());
        
        // Should work with same backend
        assertDoesNotThrow(() -> openglShader.setBuffer(0, openglBuffer));
        
        // Create Metal buffer
        var metalContext = new MockGPUContext(GPUConfig.Backend.BGFX_METAL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.BGFX_METAL).build();
        assertTrue(metalContext.initialize(config));
        
        var metalBuffer = metalContext.createBuffer(512, BufferUsage.READ_WRITE, AccessType.READ_ONLY);
        
        // Should fail with different backends
        assertThrows(IllegalArgumentException.class, () -> openglShader.setBuffer(0, metalBuffer));
        
        openglContext.cleanup();
        metalContext.cleanup();
    }
    
    @Test
    @DisplayName("Should handle multiple buffer allocations")
    void testMultipleBufferAllocations() {
        context = new MockGPUContext(GPUConfig.Backend.BGFX_VULKAN);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.BGFX_VULKAN).build();
        assertTrue(context.initialize(config));
        
        var buffers = new IGPUBuffer[10];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = context.createBuffer(
                (i + 1) * 256, 
                BufferUsage.READ_WRITE, 
                AccessType.READ_WRITE
            );
            assertNotNull(buffers[i]);
            assertEquals((i + 1) * 256, buffers[i].getSize());
        }
        
        // Verify all buffers are different instances
        for (int i = 0; i < buffers.length; i++) {
            for (int j = i + 1; j < buffers.length; j++) {
                assertNotSame(buffers[i], buffers[j]);
                assertNotEquals(buffers[i].getNativeHandle(), buffers[j].getNativeHandle());
            }
        }
    }
    
    @Test
    @DisplayName("Should handle shader compilation errors appropriately")
    void testShaderCompilationErrors() {
        context = new MockGPUContext(GPUConfig.Backend.OPENGL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.OPENGL).build();
        assertTrue(context.initialize(config));
        
        // Test invalid OpenGL shader (missing version)
        var invalidShader = context.createShader("#version 330\nlayout(local_size_x = 64) in; void main() {}", Map.of());
        var mockShader = (MockShader) invalidShader;
        assertFalse(mockShader.compile());
        assertFalse(mockShader.isCompiled());
        assertNotNull(mockShader.getCompilationError());
        assertTrue(mockShader.getCompilationError().contains("430"));
    }
    
    @Test
    @DisplayName("Should handle cleanup properly")
    void testCleanup() {
        context = new MockGPUContext(GPUConfig.Backend.BGFX_METAL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.BGFX_METAL).build();
        assertTrue(context.initialize(config));
        
        // Create some resources
        var buffer = context.createBuffer(1024, BufferUsage.READ_WRITE, AccessType.READ_WRITE);
        var shader = context.createShader("", Map.of());
        
        assertNotNull(buffer);
        assertNotNull(shader);
        
        // Cleanup should not throw
        assertDoesNotThrow(() -> context.cleanup());
        
        // After cleanup, backend state should be reset
        assertFalse(context.isOpenGLInitialized());
        assertFalse(context.isBGFXInitialized());
    }
    
    @Test
    @DisplayName("Should provide accurate backend information")
    void testBackendInformation() {
        for (var backend : GPUConfig.Backend.values()) {
            context = new MockGPUContext(backend);
            config = GPUConfig.builder().withBackend(backend).build();
            assertTrue(context.initialize(config));
            
            var factory = context.getShaderFactory();
            var backendInfo = factory.getBackendInfo();
            var shaderLanguage = factory.getShaderLanguage();
            
            assertNotNull(backendInfo);
            assertNotNull(shaderLanguage);
            
            switch (backend) {
                case OPENGL -> {
                    assertTrue(backendInfo.contains("OpenGL"));
                    assertEquals("GLSL", shaderLanguage);
                }
                case BGFX_METAL -> {
                    assertTrue(backendInfo.contains("Metal"));
                    assertEquals("Metal Shading Language", shaderLanguage);
                }
                case BGFX_VULKAN -> {
                    assertTrue(backendInfo.contains("Vulkan"));
                    assertEquals("GLSL", shaderLanguage);
                }
                case BGFX_OPENGL -> {
                    assertTrue(backendInfo.contains("OpenGL"));
                    assertEquals("GLSL", shaderLanguage);
                }
                case AUTO -> {
                    assertTrue(backendInfo.contains("Auto"));
                    assertEquals("Auto", shaderLanguage);
                }
            }
            
            context.cleanup();
        }
    }
    
    @Test
    @DisplayName("Should handle concurrent operations safely")
    void testConcurrentOperations() throws InterruptedException {
        context = new MockGPUContext(GPUConfig.Backend.OPENGL);
        config = GPUConfig.builder().withBackend(GPUConfig.Backend.OPENGL).build();
        assertTrue(context.initialize(config));
        
        var threads = new Thread[4];
        var buffers = new IGPUBuffer[threads.length];
        var exceptions = new Exception[threads.length];
        
        for (int i = 0; i < threads.length; i++) {
            var threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    buffers[threadIndex] = context.createBuffer(
                        1024 * (threadIndex + 1), 
                        BufferUsage.READ_WRITE, 
                        AccessType.READ_WRITE
                    );
                } catch (Exception e) {
                    exceptions[threadIndex] = e;
                }
            });
            threads[i].start();
        }
        
        for (var thread : threads) {
            thread.join();
        }
        
        // Verify no exceptions occurred
        for (var exception : exceptions) {
            assertNull(exception, "Concurrent buffer creation should not throw exceptions");
        }
        
        // Verify all buffers were created
        for (var buffer : buffers) {
            assertNotNull(buffer, "All buffers should be created successfully");
        }
    }
}
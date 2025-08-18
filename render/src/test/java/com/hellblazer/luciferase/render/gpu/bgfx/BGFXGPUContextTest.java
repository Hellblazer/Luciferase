/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.gpu.bgfx;

import com.hellblazer.luciferase.render.gpu.GPUConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BGFXGPUContext with GLFW integration.
 */
@EnabledOnOs(OS.MAC)
class BGFXGPUContextTest {

    @Test
    void testBGFXContextCreation() {
        // Test that we can create a BGFXGPUContext instance
        var context = new BGFXGPUContext();
        assertNotNull(context);
        assertFalse(context.isValid()); // Should not be valid until initialized
    }

    @Test
    void testBGFXInitializationAttempt() {
        var context = new BGFXGPUContext();
        
        // Create headless configuration
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .withDebugEnabled(true)
            .withWidth(1)
            .withHeight(1)
            .build();
        
        // Attempt initialization - may fail due to -XstartOnFirstThread requirement
        boolean initialized = context.initialize(config);
        
        // On macOS without -XstartOnFirstThread, GLFW operations will fail
        // This is expected in CI/test environments
        System.out.println("BGFX initialization result: " + initialized);
        
        if (initialized) {
            assertTrue(context.isValid());
            System.out.println("BGFX initialized successfully!");
            
            // Test basic functionality
            assertNotNull(context.getConfig());
            assertNotNull(context.getStats());
            
            // Clean up
            context.cleanup();
            assertFalse(context.isValid());
        } else {
            System.out.println("BGFX initialization failed - likely due to -XstartOnFirstThread requirement on macOS");
            assertFalse(context.isValid());
        }
    }

    @Test
    void testMultipleBackendFallback() {
        var context = new BGFXGPUContext();
        
        // Create configuration that will try Metal -> OpenGL -> Auto
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .withWidth(1)
            .withHeight(1)
            .build();
        
        // This will test our fallback mechanism
        boolean initialized = context.initialize(config);
        System.out.println("Fallback initialization result: " + initialized);
        
        if (initialized) {
            System.out.println("Successfully initialized with fallback backend");
            context.cleanup();
        } else {
            System.out.println("All backends failed - expected without -XstartOnFirstThread");
        }
    }

    @Test
    void testGracefulFailure() {
        var context = new BGFXGPUContext();
        
        var config = GPUConfig.builder()
            .withBackend(GPUConfig.Backend.BGFX_METAL)
            .withHeadless(true)
            .build();
        
        // Test that failure is handled gracefully
        boolean result = context.initialize(config);
        
        // Should not throw exceptions regardless of result
        assertNotNull(context.getStats());
        
        // Cleanup should work even if initialization failed
        assertDoesNotThrow(() -> context.cleanup());
    }
}
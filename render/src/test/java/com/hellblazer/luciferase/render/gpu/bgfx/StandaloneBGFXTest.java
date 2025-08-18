/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * Licensed under the AGPL License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hellblazer.luciferase.render.gpu.bgfx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXPlatformData;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Standalone test for BGFX initialization to isolate issues.
 */
@EnabledOnOs(OS.MAC)
class StandaloneBGFXTest {

    @Test
    void testBasicBGFXInitialization() {
        System.out.println("Testing basic BGFX initialization...");
        
        try (var stack = MemoryStack.stackPush()) {
            var init = BGFXInit.malloc(stack);
            BGFX.bgfx_init_ctor(init);
            
            // Try minimal configuration
            init.type(BGFX.BGFX_RENDERER_TYPE_COUNT); // Let BGFX choose
            init.resolution().width(1);
            init.resolution().height(1);
            init.resolution().reset(BGFX.BGFX_RESET_NONE);
            
            System.out.println("Calling bgfx_init...");
            boolean result = BGFX.bgfx_init(init);
            System.out.println("bgfx_init result: " + result);
            
            if (result) {
                var caps = BGFX.bgfx_get_caps();
                if (caps != null) {
                    System.out.println("BGFX capabilities:");
                    System.out.println("  Renderer type: " + caps.rendererType());
                    System.out.println("  Supported features: " + caps.supported());
                    System.out.println("  Compute support: " + ((caps.supported() & BGFX.BGFX_CAPS_COMPUTE) != 0));
                }
                
                BGFX.bgfx_shutdown();
                System.out.println("BGFX shutdown completed");
            } else {
                System.out.println("BGFX initialization failed");
            }
        } catch (Exception e) {
            System.out.println("Exception during BGFX initialization: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test 
    void testMetalSpecificInitialization() {
        System.out.println("Testing Metal-specific BGFX initialization...");
        
        try (var stack = MemoryStack.stackPush()) {
            var init = BGFXInit.malloc(stack);
            BGFX.bgfx_init_ctor(init);
            
            // Force Metal backend
            init.type(BGFX.BGFX_RENDERER_TYPE_METAL);
            init.resolution().width(1);
            init.resolution().height(1);
            init.resolution().reset(BGFX.BGFX_RESET_NONE);
            
            System.out.println("Calling bgfx_init with Metal renderer...");
            boolean result = BGFX.bgfx_init(init);
            System.out.println("Metal bgfx_init result: " + result);
            
            if (result) {
                System.out.println("Metal backend initialized successfully");
                BGFX.bgfx_shutdown();
            } else {
                System.out.println("Metal backend initialization failed");
            }
        } catch (Exception e) {
            System.out.println("Exception during Metal initialization: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    void testWithGLFWWindow() {
        System.out.println("Testing BGFX with GLFW window context...");
        
        // Initialize GLFW
        if (!GLFW.glfwInit()) {
            System.out.println("Failed to initialize GLFW");
            return;
        }
        
        long window = 0;
        try {
            // Create minimal hidden window for context
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API); // For Metal/Vulkan
            
            window = GLFW.glfwCreateWindow(1, 1, "BGFX Test", MemoryUtil.NULL, MemoryUtil.NULL);
            if (window == MemoryUtil.NULL) {
                System.out.println("Failed to create GLFW window");
                return;
            }
            
            try (var stack = MemoryStack.stackPush()) {
                var init = BGFXInit.malloc(stack);
                BGFX.bgfx_init_ctor(init);
                
                // Configure for Metal with window context
                init.type(BGFX.BGFX_RENDERER_TYPE_METAL);
                init.resolution().width(1);
                init.resolution().height(1);
                init.resolution().reset(BGFX.BGFX_RESET_NONE);
                
                // Set platform data with window handle
                var platformData = BGFXPlatformData.malloc(stack);
                // Note: BGFX will get the Metal layer from the GLFW window
                init.platformData(platformData);
                
                System.out.println("Calling bgfx_init with GLFW window context...");
                boolean result = BGFX.bgfx_init(init);
                System.out.println("BGFX with GLFW result: " + result);
                
                if (result) {
                    var caps = BGFX.bgfx_get_caps();
                    if (caps != null) {
                        System.out.println("BGFX initialized successfully!");
                        System.out.println("  Renderer: " + caps.rendererType());
                        System.out.println("  Compute support: " + ((caps.supported() & BGFX.BGFX_CAPS_COMPUTE) != 0));
                    }
                    BGFX.bgfx_shutdown();
                } else {
                    System.out.println("BGFX initialization with GLFW window failed");
                }
            }
        } finally {
            if (window != 0) {
                GLFW.glfwDestroyWindow(window);
            }
            GLFW.glfwTerminate();
        }
    }
    
    @Test
    void testOpenGLFallback() {
        System.out.println("Testing OpenGL fallback initialization...");
        
        try (var stack = MemoryStack.stackPush()) {
            var init = BGFXInit.malloc(stack);
            BGFX.bgfx_init_ctor(init);
            
            // Force OpenGL backend
            init.type(BGFX.BGFX_RENDERER_TYPE_OPENGL);
            init.resolution().width(1);
            init.resolution().height(1);
            init.resolution().reset(BGFX.BGFX_RESET_NONE);
            
            System.out.println("Calling bgfx_init with OpenGL renderer...");
            boolean result = BGFX.bgfx_init(init);
            System.out.println("OpenGL bgfx_init result: " + result);
            
            if (result) {
                System.out.println("OpenGL backend initialized successfully");
                BGFX.bgfx_shutdown();
            } else {
                System.out.println("OpenGL backend initialization failed");
            }
        } catch (Exception e) {
            System.out.println("Exception during OpenGL initialization: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
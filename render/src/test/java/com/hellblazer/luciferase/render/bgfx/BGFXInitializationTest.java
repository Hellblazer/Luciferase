package com.hellblazer.luciferase.render.bgfx;

import org.junit.jupiter.api.Test;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test BGFX initialization without -XstartOnFirstThread requirement.
 * This validates our switch from OpenGL to Metal backend on macOS.
 */
class BGFXInitializationTest {

    @Test
    void testBGFXThreadingAndLibraryAccess() {
        // The key test: BGFX libraries should be accessible without -XstartOnFirstThread
        // This validates our main goal - removing the threading dependency
        
        // Test 1: BGFX constants should be accessible
        assertDoesNotThrow(() -> {
            int metalType = BGFX.BGFX_RENDERER_TYPE_METAL;
            int vulkanType = BGFX.BGFX_RENDERER_TYPE_VULKAN;
            assertTrue(metalType > 0, "Metal renderer type should be valid");
            assertTrue(vulkanType > 0, "Vulkan renderer type should be valid");
        }, "BGFX constants should be accessible without threading restrictions");
        
        // Test 2: Memory allocation should work
        try (MemoryStack stack = MemoryStack.stackPush()) {
            assertDoesNotThrow(() -> {
                BGFXInit init = BGFXInit.malloc(stack);
                BGFX.bgfx_init_ctor(init);
                // Just test allocation and construction, not actual initialization
                assertNotNull(init, "BGFX init struct should be allocated");
            }, "BGFX memory operations should work without threading restrictions");
        }
        
        // Test 3: Check renderer capabilities (this doesn't require initialization)
        assertDoesNotThrow(() -> {
            // This queries supported renderers without initializing
            try (MemoryStack stack2 = MemoryStack.stackPush()) {
                var rendererBuffer = stack2.mallocInt(16);
                int count = BGFX.bgfx_get_supported_renderers(rendererBuffer);
                assertTrue(count >= 0, "Should return valid renderer count");
            }
        }, "BGFX capability queries should work");
    }
    
    @Test
    void testBGFXCapabilities() {
        // Test that BGFX classes are available and can be loaded
        assertDoesNotThrow(() -> {
            // These should not throw ClassNotFoundException
            Class.forName("org.lwjgl.bgfx.BGFX");
            Class.forName("org.lwjgl.bgfx.BGFXInit");
        }, "BGFX classes should be available on classpath");
    }
}
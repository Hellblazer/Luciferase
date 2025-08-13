package com.hellblazer.luciferase.render.webgpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

/**
 * Test for WebGPUVoxelDemo - currently disabled as it requires a display
 */
public class WebGPUVoxelDemoTest {
    
    @Test
    @Disabled("Requires display and WebGPU support")
    public void testDemoInitialization() {
        // This would test the demo if we had a headless mode
        // For now, just verify the class loads
        try {
            Class<?> demoClass = Class.forName("com.hellblazer.luciferase.render.webgpu.WebGPUVoxelDemo");
            assert demoClass != null;
            System.out.println("WebGPUVoxelDemo class loads successfully");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load WebGPUVoxelDemo", e);
        }
    }
    
    @Test
    public void testDemoMainMethod() {
        // Verify main method exists
        try {
            var demoClass = Class.forName("com.hellblazer.luciferase.render.webgpu.WebGPUVoxelDemo");
            var mainMethod = demoClass.getMethod("main", String[].class);
            assert mainMethod != null;
            System.out.println("Main method found in WebGPUVoxelDemo");
        } catch (Exception e) {
            throw new RuntimeException("Failed to find main method", e);
        }
    }
}
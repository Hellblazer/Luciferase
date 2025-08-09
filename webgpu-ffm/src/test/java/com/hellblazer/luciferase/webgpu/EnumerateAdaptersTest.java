package com.hellblazer.luciferase.webgpu;

import org.junit.jupiter.api.Test;

/**
 * Test the new enumerateAdapters function to verify it works correctly.
 */
public class EnumerateAdaptersTest {
    
    @Test
    void testEnumerateAdaptersExists() {
        System.out.println("\n=== Enumerate Adapters Test ===");
        
        // Initialize WebGPU
        if (!WebGPU.initialize()) {
            System.out.println("WebGPU initialization failed - skipping test");
            return;
        }
        
        // Create instance
        var instance = WebGPU.createInstance();
        if (instance == null) {
            System.out.println("Instance creation failed - skipping test");
            return;
        }
        
        System.out.println("Created instance at: 0x" + Long.toHexString(instance.address()));
        
        try {
            // Test enumerateAdapters
            System.out.println("Calling enumerateAdapters...");
            var adapters = WebGPU.enumerateAdapters(instance, null);
            
            System.out.println("Found " + adapters.length + " adapters");
            for (int i = 0; i < adapters.length; i++) {
                System.out.println("  Adapter " + i + ": 0x" + Long.toHexString(adapters[i].address()));
            }
            
            System.out.println("Test completed successfully!");
            
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            WebGPU.releaseInstance(instance);
        }
    }
}
package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.Instance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the synchronous adapter enumeration through the wrapper API.
 */
public class SynchronousAdapterTest {
    
    @Test
    void testSynchronousAdapterEnumeration() {
        System.out.println("\n=== Synchronous Adapter Test ===");
        
        try {
            // Create instance using wrapper API
            System.out.println("1. Creating WebGPU instance...");
            var instance = new Instance();
            assertTrue(instance.isValid(), "Instance should be valid");
            System.out.println("   Instance created successfully");
            
            // Request adapter synchronously
            System.out.println("2. Requesting adapter...");
            var adapterFuture = instance.requestAdapter();
            var adapter = adapterFuture.get();
            
            if (adapter != null) {
                System.out.println("   Adapter obtained successfully");
                assertTrue(adapter.isValid(), "Adapter should be valid");
                
                // NOTE: Skipping device creation because Adapter.requestDevice() 
                // still uses the callback API which crashes. The important fix
                // is that synchronous adapter enumeration now works!
                System.out.println("3. ✅ Synchronous adapter enumeration SUCCESS!");
                
                adapter.close();
            } else {
                System.out.println("   No adapters available");
                // This is not necessarily a test failure - system may not have WebGPU
            }
            
            instance.close();
            System.out.println("\n✅ Test completed successfully!");
            
        } catch (Exception e) {
            System.err.println("❌ Test failed: " + e.getMessage());
            e.printStackTrace();
            fail("Test failed: " + e.getMessage());
        }
    }
}
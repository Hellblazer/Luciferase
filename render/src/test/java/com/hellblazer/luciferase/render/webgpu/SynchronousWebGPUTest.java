package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.CallbackHelper;
import org.junit.jupiter.api.Test;

import java.lang.foreign.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test WebGPU synchronously without CompletableFuture to isolate the crash.
 */
public class SynchronousWebGPUTest {
    
    @Test
    void testSynchronousWebGPU() {
        System.out.println("\n=== Synchronous WebGPU Test ===");
        
        try {
            // Step 1: Initialize WebGPU
            System.out.println("1. Initializing WebGPU...");
            boolean initialized = WebGPU.initialize();
            System.out.println("   WebGPU initialized: " + initialized);
            assertTrue(initialized, "WebGPU should initialize");
            
            // Step 2: Create instance directly
            System.out.println("2. Creating instance directly...");
            var instanceHandle = WebGPU.createInstance();
            assertNotNull(instanceHandle);
            assertNotEquals(MemorySegment.NULL, instanceHandle);
            System.out.println("   Instance handle: 0x" + Long.toHexString(instanceHandle.address()));
            
            // Step 3: Request adapter synchronously in main thread
            System.out.println("3. Requesting adapter synchronously in main thread...");
            System.out.println("   Thread: " + Thread.currentThread().getName());
            
            // Create a confined arena for the callback
            try (var arena = Arena.ofConfined()) {
                var callback = new CallbackHelper.AdapterCallback(arena);
                System.out.println("   Created callback stub: 0x" + 
                    Long.toHexString(callback.getCallbackStub().address()));
                
                // Call WebGPU directly - this should trigger the callback
                System.out.println("   Calling wgpuInstanceRequestAdapter...");
                var adapterHandle = WebGPU.requestAdapter(instanceHandle, null);
                
                System.out.println("   Adapter handle: " + 
                    (adapterHandle != null ? "0x" + Long.toHexString(adapterHandle.address()) : "null"));
                
                // Clean up
                if (adapterHandle != null && !adapterHandle.equals(MemorySegment.NULL)) {
                    WebGPU.releaseAdapter(adapterHandle);
                }
            }
            
            // Clean up instance
            WebGPU.releaseInstance(instanceHandle);
            
            System.out.println("Test completed successfully!");
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            fail("Test failed: " + e.getMessage());
        }
    }
}
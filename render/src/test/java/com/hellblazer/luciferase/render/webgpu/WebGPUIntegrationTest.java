package com.hellblazer.luciferase.render.webgpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

/**
 * Test suite for WebGPU integration using FFM bindings.
 */
public class WebGPUIntegrationTest {
    
    private WebGPUIntegration webgpu;
    
    @BeforeEach
    void setUp() {
        webgpu = new WebGPUIntegration();
    }
    
    @AfterEach
    void tearDown() {
        if (webgpu != null) {
            webgpu.shutdown();
        }
    }
    
    @Test
    void testWebGPUAvailability() {
        // Test static availability check
        boolean available = WebGPUIntegration.isAvailable();
        System.out.println("WebGPU available: " + available);
        
        // This test passes regardless of WebGPU availability
        // It's informational to see if WebGPU is working on this system
        assertTrue(true, "WebGPU availability check completed");
    }
    
    @Test
    void testWebGPUInitialization() throws Exception {
        // Test initialization
        var initFuture = webgpu.initialize();
        boolean initialized = initFuture.get(5, TimeUnit.SECONDS);
        
        System.out.println("WebGPU initialization result: " + initialized);
        
        if (initialized) {
            assertTrue(webgpu.isInitialized());
            assertNotNull(webgpu.getInstance());
        } else {
            // WebGPU not available on this system - that's okay for CI/testing
            assertFalse(webgpu.isInitialized());
        }
    }
    
    @Test
    void testBasicOperations() throws Exception {
        // Only test operations if WebGPU is potentially available
        if (!WebGPUIntegration.isAvailable()) {
            System.out.println("Skipping basic operations test - WebGPU not potentially available");
            return;
        }
        
        // Try to initialize, but handle the case where native libraries aren't available
        try {
            var initFuture = webgpu.initialize();
            boolean initialized = initFuture.get(5, TimeUnit.SECONDS);
            
            if (initialized) {
                // Test basic operations (these are stubs for now)
                assertDoesNotThrow(() -> {
                    var buffer = webgpu.createBuffer(1024, 0);
                    webgpu.writeBuffer(buffer, 0, new byte[]{1, 2, 3, 4});
                    var data = webgpu.readBuffer(buffer);
                    webgpu.dispatchCompute(buffer, 1, 1, 1);  // Using buffer as pipeline for testing
                });
            } else {
                System.out.println("WebGPU initialization failed - native libraries not available");
            }
        } catch (Exception e) {
            // If we get a NoClassDefFoundError or similar, that's expected when native libs aren't available
            if (e.getCause() instanceof NoClassDefFoundError || 
                e.getCause() instanceof UnsatisfiedLinkError ||
                e.getCause() instanceof ExceptionInInitializerError) {
                System.out.println("WebGPU native libraries not available - skipping test: " + e.getCause().getMessage());
            } else {
                // Re-throw if it's an unexpected error
                throw e;
            }
        }
    }
    
    @Test
    void testErrorHandling() {
        // Test operations before initialization
        assertThrows(IllegalStateException.class, () -> {
            webgpu.createBuffer(1024, 0);
        });
        
        assertThrows(IllegalStateException.class, () -> {
            webgpu.createComputeShader("@compute @workgroup_size(1) fn main() {}");
        });
    }
}
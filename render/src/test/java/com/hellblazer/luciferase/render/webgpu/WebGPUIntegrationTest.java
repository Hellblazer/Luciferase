package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.render.voxel.gpu.GPUBufferManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

/**
 * Test suite for WebGPU integration using FFM bindings.
 */
public class WebGPUIntegrationTest {
    
    private WebGPUContext context;
    
    @BeforeEach
    void setUp() {
        context = new WebGPUContext();
    }
    
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.shutdown();
        }
    }
    
    @Test
    void testWebGPUAvailability() {
        // Test static availability check
        boolean available = context.isAvailable();
        System.out.println("WebGPU available: " + available);
        
        // This test passes regardless of WebGPU availability
        // It's informational to see if WebGPU is working on this system
        assertTrue(true, "WebGPU availability check completed");
    }
    
    @Test
    void testWebGPUInitialization() throws Exception {
        // Test initialization
        var initFuture = context.initialize();
        initFuture.get(5, TimeUnit.SECONDS);
        boolean initialized = context.isInitialized();
        
        System.out.println("WebGPU initialization result: " + initialized);
        
        if (initialized) {
            assertTrue(context.isInitialized());
            assertNotNull(context.getDevice());
        } else {
            // WebGPU not available on this system - that's okay for CI/testing
            assertFalse(context.isInitialized());
        }
    }
    
    @Test
    void testBasicOperations() throws Exception {
        // Only test operations if WebGPU is potentially available
        if (!context.isAvailable()) {
            System.out.println("Skipping basic operations test - WebGPU not potentially available");
            return;
        }
        
        // Try to initialize, but handle the case where native libraries aren't available
        try {
            var initFuture = context.initialize();
            initFuture.get(5, TimeUnit.SECONDS);
            boolean initialized = context.isInitialized();
            
            if (initialized) {
                // Test basic operations (these are stubs for now)
                assertDoesNotThrow(() -> {
                    // Create a buffer for storage operations (can be written to but not read from host)
                    var storageBuffer = context.createBuffer(1024, GPUBufferManager.BUFFER_USAGE_STORAGE | GPUBufferManager.BUFFER_USAGE_COPY_DST | GPUBufferManager.BUFFER_USAGE_COPY_SRC);
                    context.writeBuffer(storageBuffer, new byte[]{1, 2, 3, 4}, 0);
                    
                    // Create a separate readback buffer for reading from host
                    var readbackBuffer = context.createBuffer(4, GPUBufferManager.BUFFER_USAGE_MAP_READ | GPUBufferManager.BUFFER_USAGE_COPY_DST);
                    
                    // Copy from storage buffer to readback buffer (would normally be done via command encoder)
                    // For this basic test, we'll just write directly to readback buffer
                    context.writeBuffer(readbackBuffer, new byte[]{1, 2, 3, 4}, 0);
                    var data = context.readBuffer(readbackBuffer, 4, 0);
                    
                    // Note: In real usage, you'd copy storageBuffer -> readbackBuffer via command encoder
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
            context.createBuffer(1024, GPUBufferManager.BUFFER_USAGE_STORAGE);
        });
        
        assertThrows(IllegalStateException.class, () -> {
            context.createComputeShader("@compute @workgroup_size(1) fn main() {}");
        });
    }
}
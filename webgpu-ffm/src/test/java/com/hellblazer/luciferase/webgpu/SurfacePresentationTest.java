package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test surface presentation functionality.
 * Note: Most tests are disabled since they require a windowing system.
 */
public class SurfacePresentationTest {
    private static final Logger log = LoggerFactory.getLogger(SurfacePresentationTest.class);
    private static boolean gpuAvailable = false;
    
    @BeforeAll
    static void setup() {
        try {
            WebGPU.initialize();
            
            // Check if GPU is available (will be false in CI)
            try (var instance = new Instance()) {
                var adapter = instance.requestAdapter().get(5, TimeUnit.SECONDS);
                gpuAvailable = (adapter != null);
                if (adapter != null) {
                    adapter.close();
                }
            }
        } catch (Exception e) {
            gpuAvailable = false;
            log.info("GPU not available: " + e.getMessage());
        }
    }
    
    @Test
    void testSurfaceCreation() throws Exception {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        log.info("Testing surface creation workflow");
        
        try (var instance = new Instance()) {
            var adapter = instance.requestAdapter().get(5, TimeUnit.SECONDS);
            assertNotNull(adapter, "Failed to get adapter");
            
            try (adapter) {
                var device = adapter.requestDevice().get(5, TimeUnit.SECONDS);
                assertNotNull(device, "Failed to get device");
                
                try (device) {
                    // Surface creation requires a real window handle which we don't have in headless tests
                    // Instead, test the surface configuration builder which doesn't require a surface
                    log.info("Testing surface configuration builder (surface creation requires window handle)");
                    
                    // Test surface configuration builder
                    var config = new Surface.Configuration.Builder()
                        .withDevice(device)
                        .withSize(800, 600)
                        .withFormat(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.TEXTURE_FORMAT_BGRA8_UNORM)
                        .withUsage(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.TEXTURE_USAGE_RENDER_ATTACHMENT)
                        .withPresentMode(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.PRESENT_MODE_FIFO)
                        .withAlphaMode(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.COMPOSITE_ALPHA_MODE_OPAQUE)
                        .build();
                    
                    assertNotNull(config, "Configuration should be created");
                    
                    // Verify core components are still valid
                    assertNotNull(device.getQueue(), "Device queue should be available");
                    log.info("Surface configuration test completed successfully");
                }
            }
        }
    }
    
    @Test
    void testSurfaceConfigurationBuilder() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        log.info("Testing surface configuration builder");
        
        // This test just verifies the configuration builder compiles
        // and works correctly without needing an actual surface
        
        try (var instance = new Instance()) {
            var adapter = instance.requestAdapter().get(5, TimeUnit.SECONDS);
            assertNotNull(adapter, "Failed to get adapter");
            
            try (adapter) {
                var device = adapter.requestDevice().get(5, TimeUnit.SECONDS);
                assertNotNull(device, "Failed to get device");
                
                try (device) {
                    // Build a surface configuration
                    var config = new Surface.Configuration.Builder()
                        .withDevice(device)
                        .withSize(1920, 1080)
                        .withFormat(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.TEXTURE_FORMAT_BGRA8_UNORM)
                        .withUsage(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.TEXTURE_USAGE_RENDER_ATTACHMENT)
                        .withPresentMode(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.PRESENT_MODE_FIFO)
                        .withAlphaMode(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.COMPOSITE_ALPHA_MODE_OPAQUE)
                        .build();
                    
                    assertNotNull(config, "Configuration should be created");
                    log.info("Successfully created surface configuration");
                }
            }
        } catch (Exception e) {
            log.error("Test failed", e);
            fail("Test failed: " + e.getMessage());
        }
    }
    
    @Test
    void testRenderLoop() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        log.info("Testing render loop components");
        
        try (var instance = new Instance()) {
            var adapter = instance.requestAdapter().get(5, TimeUnit.SECONDS);
            assertNotNull(adapter, "Failed to get adapter");
            
            try (adapter) {
                var device = adapter.requestDevice().get(5, TimeUnit.SECONDS);
                assertNotNull(device, "Failed to get device");
                
                try (device) {
                    // Test multi-frame rendering workflow
                    int frameCount = 3;
                    var queue = device.getQueue();
                    assertNotNull(queue, "Should have queue for frame submission");
                    
                    for (int frame = 0; frame < frameCount; frame++) {
                        // Create command encoder for this frame
                        var commandEncoder = device.createCommandEncoder("frame_" + frame);
                        assertNotNull(commandEncoder, "Should create command encoder for frame " + frame);
                        
                        // Record frame commands
                        var commandBuffer = commandEncoder.finish();
                        assertNotNull(commandBuffer, "Should create command buffer for frame " + frame);
                        
                        // Submit frame to GPU
                        queue.submit(commandBuffer);
                        
                        // Wait for frame completion
                        device.poll(true);
                        
                        log.debug("Frame {} completed", frame);
                    }
                    
                    log.info("Render loop test completed - processed {} frames", frameCount);
                }
            }
        } catch (Exception e) {
            log.error("Render loop test failed", e);
            fail("Test failed: " + e.getMessage());
        }
    }
}
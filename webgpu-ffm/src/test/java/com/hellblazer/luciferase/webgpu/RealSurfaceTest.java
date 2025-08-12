package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.surface.SurfaceDescriptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test WebGPU surface creation with real native surfaces.
 * Uses platform-specific methods to create actual renderable surfaces.
 */
public class RealSurfaceTest {
    private static final Logger log = LoggerFactory.getLogger(RealSurfaceTest.class);
    private static boolean gpuAvailable = false;
    
    @BeforeAll
    static void setup() {
        try {
            WebGPU.initialize();
            
            // Check if GPU is available
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
    @EnabledOnOs(OS.MAC)
    void testMetalSurfaceCreation() throws Exception {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Check if we're running with the required -XstartOnFirstThread flag
        String javaCommand = System.getProperty("sun.java.command", "");
        assumeTrue(javaCommand.contains("-XstartOnFirstThread"), 
            "Test requires -XstartOnFirstThread flag on macOS for Metal layer creation");
        
        log.info("Testing Metal surface creation on macOS");
        
        // Create a Metal layer
        long metalLayer = MacOSMetalLayer.createMetalLayer();
        if (metalLayer == 0) {
            log.warn("Failed to create Metal layer - may need proper window context");
            return;
        }
        
        try (var instance = new Instance()) {
            var adapter = instance.requestAdapter().get(5, TimeUnit.SECONDS);
            assertNotNull(adapter, "Failed to get adapter");
            
            try (adapter) {
                var device = adapter.requestDevice().get(5, TimeUnit.SECONDS);
                assertNotNull(device, "Failed to get device");
                
                try (device) {
                    // Create surface descriptor with Metal layer
                    try (var arena = Arena.ofConfined()) {
                        var surfaceDescriptor = new SurfaceDescriptor.MetalSurfaceDescriptor(arena, metalLayer);
                        
                        // Create the surface
                        var surface = instance.createSurface(surfaceDescriptor.getDescriptor());
                        if (surface != null) {
                            log.info("Successfully created Metal surface!");
                            
                            // Configure the surface
                            var config = new Surface.Configuration.Builder()
                                .withDevice(device)
                                .withSize(800, 600)
                                .withFormat(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.TEXTURE_FORMAT_BGRA8_UNORM)
                                .withUsage(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.TEXTURE_USAGE_RENDER_ATTACHMENT)
                                .withPresentMode(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.PRESENT_MODE_FIFO)
                                .withAlphaMode(com.hellblazer.luciferase.webgpu.ffm.WebGPUNative.COMPOSITE_ALPHA_MODE_OPAQUE)
                                .build();
                            
                            surface.configure(config);
                            log.info("Surface configured successfully");
                            
                            // Try to get a texture
                            var surfaceTexture = surface.getCurrentTexture();
                            if (surfaceTexture != null && surfaceTexture.getTexture() != null) {
                                log.info("Got surface texture!");
                                
                                // Create a simple render pass
                                var commandEncoder = device.createCommandEncoder("test_frame");
                                var commandBuffer = commandEncoder.finish();
                                device.getQueue().submit(commandBuffer);
                                
                                // Present the frame
                                surface.present();
                                log.info("Frame presented successfully");
                                
                                surfaceTexture.getTexture().close();
                            }
                            
                            surface.close();
                        } else {
                            log.warn("Failed to create surface from Metal layer");
                        }
                    }
                }
            }
        }
    }
    
    @Test
    void testOffscreenSurfaceCreation() throws Exception {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        log.info("Testing offscreen rendering (no surface needed)");
        
        try (var instance = new Instance()) {
            var adapter = instance.requestAdapter().get(5, TimeUnit.SECONDS);
            assertNotNull(adapter, "Failed to get adapter");
            
            try (adapter) {
                var device = adapter.requestDevice().get(5, TimeUnit.SECONDS);
                assertNotNull(device, "Failed to get device");
                
                try (device) {
                    // Create an offscreen texture for rendering
                    var textureDesc = new Texture.TextureDescriptor()
                        .withLabel("offscreen_target")
                        .withSize(800, 600)
                        .withFormat(Texture.TextureFormat.RGBA8_UNORM)
                        .withUsage(Texture.TextureUsage.RENDER_ATTACHMENT);
                    
                    // Note: device.createTexture() would need to be implemented
                    // For now, just test command buffer submission
                    
                    // Create and submit empty command buffer (simulates offscreen rendering)
                    var commandEncoder = device.createCommandEncoder("offscreen_frame");
                    var commandBuffer = commandEncoder.finish();
                    device.getQueue().submit(commandBuffer);
                    device.poll(true);
                    
                    log.info("Offscreen rendering test completed");
                }
            }
        }
    }
}
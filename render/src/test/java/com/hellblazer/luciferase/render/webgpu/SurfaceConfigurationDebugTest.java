package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.webgpu.WebGPU;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class SurfaceConfigurationDebugTest {
    private static final Logger log = LoggerFactory.getLogger(SurfaceConfigurationDebugTest.class);
    
    @BeforeAll
    static void setupClass() {
        WebGPU.initialize();
    }
    
    @Test
    void testSurfaceConfigurationMemoryLayout() throws Exception {
        log.info("Testing surface configuration memory layout");
        
        // Initialize GLFW
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }
        
        try {
            // Create window
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            long window = glfwCreateWindow(800, 600, "Test", NULL, NULL);
            
            // Create WebGPU instance
            var instance = new Instance();
            
            // Create surface
            var surfaceManager = new com.hellblazer.luciferase.render.webgpu.platform.PlatformSurfaceManager();
            var surface = surfaceManager.createSurface(instance, window);
            
            // Get adapter
            var options = new Instance.AdapterOptions()
                .withPowerPreference(Instance.PowerPreference.HIGH_PERFORMANCE);
            var adapter = instance.requestAdapter(options).get();
            
            // Get device  
            var descriptor = new Adapter.DeviceDescriptor()
                .withLabel("Test Device");
            var device = adapter.requestDevice(descriptor).get();
            
            // Log device and surface handles
            log.info("Device handle: 0x{}", Long.toHexString(device.getHandle().address()));
            log.info("Surface handle: 0x{}", Long.toHexString(surface.getHandle().address()));
            
            // Create configuration with explicit memory layout verification
            try (var arena = Arena.ofConfined()) {
                var config = arena.allocate(64); // WGPUSurfaceConfiguration size
                
                // Log each field as we set it
                config.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                log.info("nextInChain at offset 0: NULL");
                
                config.set(ValueLayout.ADDRESS, 8, device.getHandle());
                log.info("device at offset 8: 0x{}", Long.toHexString(device.getHandle().address()));
                
                config.set(ValueLayout.JAVA_INT, 16, 23); // BGRA8Unorm
                log.info("format at offset 16: 23 (BGRA8Unorm)");
                
                config.set(ValueLayout.JAVA_INT, 20, 0x10); // RENDER_ATTACHMENT
                log.info("usage at offset 20: 0x10");
                
                config.set(ValueLayout.JAVA_LONG, 32, 0L); // viewFormatCount
                log.info("viewFormatCount at offset 32: 0");
                
                config.set(ValueLayout.ADDRESS, 40, MemorySegment.NULL); // viewFormats
                log.info("viewFormats at offset 40: NULL");
                
                config.set(ValueLayout.JAVA_INT, 48, 0); // alphaMode = Opaque
                log.info("alphaMode at offset 48: 0 (Opaque)");
                
                config.set(ValueLayout.JAVA_INT, 52, 800); // width
                log.info("width at offset 52: 800");
                
                config.set(ValueLayout.JAVA_INT, 56, 600); // height
                log.info("height at offset 56: 600");
                
                config.set(ValueLayout.JAVA_INT, 60, 2); // presentMode = Fifo
                log.info("presentMode at offset 60: 2 (Fifo)");
                
                // Configure surface
                log.info("Calling wgpuSurfaceConfigure...");
                WebGPU.configureSurface(surface.getHandle(), config);
                
                // Try to get texture
                log.info("Attempting to get current texture...");
                var texture = surface.getCurrentTexture();
                
                if (texture != null && texture.getTexture() != null) {
                    log.info("SUCCESS: Got texture handle: 0x{}", 
                             Long.toHexString(texture.getTexture().getHandle().address()));
                } else {
                    log.error("FAILED: getCurrentTexture returned null");
                }
            }
            
            // Cleanup
            device.close();
            surface.close();
            instance.close();
            glfwDestroyWindow(window);
            
        } finally {
            glfwTerminate();
        }
    }
}
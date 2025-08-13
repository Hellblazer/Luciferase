package com.hellblazer.luciferase.render.webgpu.platform;

// import com.hellblazer.luciferase.render.demo.GLFWMetalHelperV2; // TODO: Move from test to main
import com.hellblazer.luciferase.webgpu.surface.SurfaceDescriptorV3;
import com.hellblazer.luciferase.webgpu.wrapper.Instance;
import com.hellblazer.luciferase.webgpu.wrapper.Surface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS Metal surface factory implementation.
 * Creates WebGPU surfaces using CAMetalLayer for macOS.
 */
public class MetalSurfaceFactory implements PlatformSurfaceFactory {
    private static final Logger log = LoggerFactory.getLogger(MetalSurfaceFactory.class);
    
    @Override
    public Surface createSurface(Instance instance, long windowHandle) {
        log.info("Creating Metal surface for window handle: {}", windowHandle);
        
        try {
            // TODO: Implement Metal layer creation
            // Need to move GLFWMetalHelperV2 from test to main or implement native Metal layer creation
            
            // Create CAMetalLayer for the window
            // long metalLayer = GLFWMetalHelperV2.createMetalLayerForWindow(windowHandle);
            // if (metalLayer == 0) {
            //     throw new RuntimeException("Failed to create CAMetalLayer");
            // }
            
            // Create surface descriptor for Metal
            SurfaceDescriptorV3 descriptor = new SurfaceDescriptorV3();
            // descriptor.setMetalLayer(metalLayer);
            
            // Create WebGPU surface
            // Surface surface = instance.createSurfaceFromMetalLayer(descriptor);
            // if (surface == null) {
            //     throw new RuntimeException("Failed to create WebGPU surface from Metal layer");
            // }
            
            log.warn("Metal surface creation not yet fully implemented");
            return null; // temporary
            
        } catch (Exception e) {
            log.error("Failed to create Metal surface", e);
            throw new RuntimeException("Metal surface creation failed", e);
        }
    }
    
    @Override
    public boolean isSupported() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }
    
    @Override
    public String getPlatformName() {
        return "macOS (Metal)";
    }
}
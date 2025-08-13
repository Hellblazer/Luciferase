package com.hellblazer.luciferase.render.webgpu.platform;

import com.hellblazer.luciferase.webgpu.surface.SurfaceDescriptorV3;
import com.hellblazer.luciferase.webgpu.wrapper.Instance;
import com.hellblazer.luciferase.webgpu.wrapper.Surface;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows DirectX surface factory implementation.
 * Creates WebGPU surfaces using HWND for Windows.
 */
public class WindowsSurfaceFactory implements PlatformSurfaceFactory {
    private static final Logger log = LoggerFactory.getLogger(WindowsSurfaceFactory.class);
    
    @Override
    public Surface createSurface(Instance instance, long windowHandle) {
        log.info("Creating DirectX surface for window handle: {}", windowHandle);
        
        try {
            // Get HWND from GLFW window
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(windowHandle);
            if (hwnd == 0) {
                throw new RuntimeException("Failed to get Win32 window handle");
            }
            
            // Create surface descriptor for Windows using static factory method
            var descriptorSegment = SurfaceDescriptorV3.createPersistent(hwnd);
            
            // Create WebGPU surface
            Surface surface = instance.createSurface(descriptorSegment);
            if (surface == null) {
                throw new RuntimeException("Failed to create WebGPU surface from HWND");
            }
            
            log.info("Successfully created DirectX surface");
            return surface;
            
        } catch (Exception e) {
            log.error("Failed to create Windows surface", e);
            throw new RuntimeException("Windows surface creation failed", e);
        }
    }
    
    @Override
    public boolean isSupported() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("win");
    }
    
    @Override
    public String getPlatformName() {
        return "Windows (DirectX 12)";
    }
    
    /**
     * Gets the module handle (HINSTANCE) for the current process.
     * This would typically use JNI or FFM to call GetModuleHandle(NULL).
     */
    private long getModuleHandle() {
        // This would need to be implemented with native code
        // For now, return 0 which should work in many cases
        return 0;
    }
}
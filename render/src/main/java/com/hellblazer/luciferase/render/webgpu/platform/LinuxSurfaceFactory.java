package com.hellblazer.luciferase.render.webgpu.platform;

import com.hellblazer.luciferase.webgpu.surface.SurfaceDescriptor;
import com.hellblazer.luciferase.webgpu.wrapper.Instance;
import com.hellblazer.luciferase.webgpu.wrapper.Surface;
import java.lang.foreign.Arena;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWayland;
import org.lwjgl.glfw.GLFWNativeX11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linux surface factory implementation.
 * Supports both X11 and Wayland display servers.
 */
public class LinuxSurfaceFactory implements PlatformSurfaceFactory {
    private static final Logger log = LoggerFactory.getLogger(LinuxSurfaceFactory.class);
    
    @Override
    public Surface createSurface(Instance instance, long windowHandle) {
        log.info("Creating Linux surface for window handle: {}", windowHandle);
        
        // Detect whether we're running on X11 or Wayland
        if (isWayland()) {
            return createWaylandSurface(instance, windowHandle);
        } else {
            return createX11Surface(instance, windowHandle);
        }
    }
    
    private Surface createX11Surface(Instance instance, long windowHandle) {
        log.info("Creating X11 surface");
        
        try {
            // Get X11 display and window
            long display = GLFWNativeX11.glfwGetX11Display();
            long x11Window = GLFWNativeX11.glfwGetX11Window(windowHandle);
            
            if (display == 0 || x11Window == 0) {
                throw new RuntimeException("Failed to get X11 display or window");
            }
            
            // Create WebGPU surface using SurfaceDescriptor
            try (Arena arena = Arena.ofConfined()) {
                SurfaceDescriptor desc = SurfaceDescriptor.create(arena, x11Window);
                Surface surface = instance.createSurface(desc.getDescriptor());
                if (surface == null) {
                    throw new RuntimeException("Failed to create WebGPU surface from X11 window");
                }
                
                log.info("Successfully created X11 surface");
                return surface;
            }
            
        } catch (Exception e) {
            log.error("Failed to create X11 surface", e);
            throw new RuntimeException("X11 surface creation failed", e);
        }
    }
    
    private Surface createWaylandSurface(Instance instance, long windowHandle) {
        log.info("Creating Wayland surface");
        
        try {
            // Get Wayland display and surface
            long display = GLFWNativeWayland.glfwGetWaylandDisplay();
            long waylandSurface = GLFWNativeWayland.glfwGetWaylandWindow(windowHandle);
            
            if (display == 0 || waylandSurface == 0) {
                throw new RuntimeException("Failed to get Wayland display or surface");
            }
            
            // Create WebGPU surface using SurfaceDescriptor
            try (Arena arena = Arena.ofConfined()) {
                SurfaceDescriptor desc = SurfaceDescriptor.create(arena, waylandSurface);
                Surface surface = instance.createSurface(desc.getDescriptor());
                if (surface == null) {
                    throw new RuntimeException("Failed to create WebGPU surface from Wayland surface");
                }
                
                log.info("Successfully created Wayland surface");
                return surface;
            }
            
        } catch (Exception e) {
            log.error("Failed to create Wayland surface", e);
            throw new RuntimeException("Wayland surface creation failed", e);
        }
    }
    
    private boolean isWayland() {
        try {
            return GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND;
        } catch (Exception e) {
            // Fall back to X11 if we can't determine
            return false;
        }
    }
    
    @Override
    public boolean isSupported() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("linux") || os.contains("nix") || os.contains("nux");
    }
    
    @Override
    public String getPlatformName() {
        return isWayland() ? "Linux (Wayland)" : "Linux (X11)";
    }
}
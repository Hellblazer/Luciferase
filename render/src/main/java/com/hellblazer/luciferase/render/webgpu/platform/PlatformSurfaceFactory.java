package com.hellblazer.luciferase.render.webgpu.platform;

import com.hellblazer.luciferase.webgpu.wrapper.Instance;
import com.hellblazer.luciferase.webgpu.wrapper.Surface;

/**
 * Factory interface for creating platform-specific WebGPU surfaces.
 * Each platform (Windows, macOS, Linux) requires different surface creation methods.
 */
public interface PlatformSurfaceFactory {
    
    /**
     * Creates a WebGPU surface for the given window handle.
     * 
     * @param instance The WebGPU instance
     * @param windowHandle The platform-specific window handle (HWND, NSWindow, X11 Window, etc.)
     * @return The created WebGPU surface
     * @throws RuntimeException if surface creation fails
     */
    Surface createSurface(Instance instance, long windowHandle);
    
    /**
     * Returns true if this factory supports the current platform.
     */
    boolean isSupported();
    
    /**
     * Gets the platform name for logging purposes.
     */
    String getPlatformName();
}
package com.hellblazer.luciferase.render.webgpu.platform;

import com.hellblazer.luciferase.webgpu.wrapper.Instance;
import com.hellblazer.luciferase.webgpu.wrapper.Surface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Manager for platform-specific surface creation.
 * Automatically detects the current platform and uses the appropriate factory.
 */
public class PlatformSurfaceManager {
    private static final Logger log = LoggerFactory.getLogger(PlatformSurfaceManager.class);
    
    private static final List<PlatformSurfaceFactory> FACTORIES = Arrays.asList(
        new MetalSurfaceFactory(),
        new WindowsSurfaceFactory(),
        new LinuxSurfaceFactory()
    );
    
    private final PlatformSurfaceFactory activeFactory;
    
    public PlatformSurfaceManager() {
        this.activeFactory = detectPlatform();
        log.info("Platform detected: {}", activeFactory.getPlatformName());
    }
    
    /**
     * Creates a WebGPU surface for the given window.
     * 
     * @param instance The WebGPU instance
     * @param windowHandle The GLFW window handle
     * @return The created surface
     */
    public Surface createSurface(Instance instance, long windowHandle) {
        log.info("Creating surface using {}", activeFactory.getPlatformName());
        return activeFactory.createSurface(instance, windowHandle);
    }
    
    /**
     * Gets the active platform name.
     */
    public String getPlatformName() {
        return activeFactory.getPlatformName();
    }
    
    /**
     * Checks if the current platform is macOS (requires -XstartOnFirstThread).
     */
    public boolean isMacOS() {
        return activeFactory instanceof MetalSurfaceFactory;
    }
    
    /**
     * Validates platform-specific requirements.
     */
    public void validatePlatformRequirements() {
        if (isMacOS()) {
            // Check for required JVM flag on macOS
            String javaCommand = System.getProperty("sun.java.command", "");
            if (!javaCommand.contains("-XstartOnFirstThread")) {
                log.warn("==========================================");
                log.warn("WARNING: macOS detected but -XstartOnFirstThread flag not found!");
                log.warn("GLFW requires this flag on macOS for proper thread handling.");
                log.warn("Please restart with: java -XstartOnFirstThread ...");
                log.warn("==========================================");
                // Don't throw exception, just warn - it might work in some cases
            }
        }
    }
    
    private PlatformSurfaceFactory detectPlatform() {
        for (PlatformSurfaceFactory factory : FACTORIES) {
            if (factory.isSupported()) {
                return factory;
            }
        }
        
        String os = System.getProperty("os.name");
        throw new UnsupportedOperationException(
            "Unsupported platform: " + os + ". WebGPU requires Windows, macOS, or Linux."
        );
    }
}
package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.webgpu.WebGPU;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to check WebGPU capabilities using the webgpu-ffm module.
 */
public class WebGPUCapabilities {
    private static final Logger log = LoggerFactory.getLogger(WebGPUCapabilities.class);
    
    private static Boolean available = null;
    
    /**
     * Check if WebGPU is potentially available.
     * This uses the webgpu-ffm module's initialization mechanism.
     */
    public static synchronized boolean isWebGPUPotentiallyAvailable() {
        if (available != null) {
            return available;
        }
        
        try {
            // Check operating system compatibility
            String osName = System.getProperty("os.name").toLowerCase();
            boolean osSupported = osName.contains("windows") || 
                                osName.contains("mac") || 
                                osName.contains("linux");
            
            if (!osSupported) {
                log.debug("Operating system not supported for WebGPU: {}", osName);
                available = false;
                return false;
            }
            
            // Check for Java version (WebGPU requires newer Java for FFM)
            int javaVersion = Runtime.version().version().get(0);
            if (javaVersion < 21) {
                log.debug("Java version {} is too old for WebGPU FFM bindings (requires 21+)", javaVersion);
                available = false;
                return false;
            }
            
            // Try to initialize WebGPU using the webgpu-ffm module
            // This will load the native library from JAR resources
            boolean initialized = WebGPU.initialize();
            if (initialized) {
                log.debug("WebGPU successfully initialized via webgpu-ffm module");
                available = true;
                return true;
            }
            
            log.debug("WebGPU initialization failed");
            available = false;
            return false;
            
        } catch (Exception e) {
            log.debug("Error checking WebGPU availability: {}", e.getMessage());
            available = false;
            return false;
        }
    }
    
    /**
     * Reset the availability cache - mainly for testing.
     */
    public static synchronized void resetAvailabilityCache() {
        available = null;
    }
    
    /**
     * Get system information for debugging WebGPU issues.
     */
    public static String getSystemInfo() {
        return String.format(
            "OS: %s %s, Java: %s, Arch: %s", 
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("java.version"),
            System.getProperty("os.arch")
        );
    }
}
package com.hellblazer.luciferase.render.webgpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class to check WebGPU capabilities without triggering native library loading.
 */
public class WebGPUCapabilities {
    private static final Logger log = LoggerFactory.getLogger(WebGPUCapabilities.class);
    
    private static Boolean available = null;
    private static String libraryPath = null;
    
    /**
     * Check if WebGPU is potentially available.
     * This does minimal checking to avoid triggering native library loading errors.
     */
    public static synchronized boolean isWebGPUPotentiallyAvailable() {
        if (available != null) {
            return available;
        }
        
        try {
            // TODO: Check for our own WebGPU FFM bindings when available
            // Class.forName("com.hellblazer.luciferase.webgpu.native.WebGPUNative");
            // For now, just check for native library availability
            
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
            
            // Check if native library exists
            String libPath = findWebGPULibrary();
            if (libPath != null) {
                libraryPath = libPath;
                log.debug("WebGPU native library found at: {}", libPath);
                available = true;
                return true;
            }
            
            log.debug("WebGPU classes found but native library not found");
            available = false;
            return false;
            
            // ClassNotFoundException catch removed - no longer checking for classes
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
     * Get the path to the WebGPU native library if found.
     */
    public static String getLibraryPath() {
        if (libraryPath == null) {
            libraryPath = findWebGPULibrary();
        }
        return libraryPath;
    }
    
    /**
     * Find the WebGPU native library in common locations.
     */
    private static String findWebGPULibrary() {
        String os = System.getProperty("os.name").toLowerCase();
        String[] libraryNames;
        
        if (os.contains("mac")) {
            libraryNames = new String[] {
                "libwgpu_native.dylib",
                "libdawn.dylib", 
                "libwebgpu.dylib"
            };
        } else if (os.contains("win")) {
            libraryNames = new String[] {
                "wgpu_native.dll",
                "dawn.dll",
                "webgpu.dll"
            };
        } else if (os.contains("linux")) {
            libraryNames = new String[] {
                "libwgpu_native.so",
                "libdawn.so",
                "libwebgpu.so"
            };
        } else {
            return null;
        }
        
        // Check in various locations
        String[] searchPaths = new String[] {
            "render/lib",
            "lib",
            "/usr/local/lib",
            "/opt/homebrew/lib",  // Mac M1/M2
            System.getProperty("java.library.path", "")
        };
        
        for (String path : searchPaths) {
            if (path.isEmpty()) continue;
            
            for (String libName : libraryNames) {
                Path libPath = Paths.get(path, libName);
                if (Files.exists(libPath)) {
                    return libPath.toAbsolutePath().toString();
                }
            }
        }
        
        return null;
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
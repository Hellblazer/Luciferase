package com.hellblazer.luciferase.webgpu.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects the current platform for loading the appropriate native WebGPU library.
 */
public class PlatformDetector {
    private static final Logger log = LoggerFactory.getLogger(PlatformDetector.class);
    
    /**
     * Detect the current platform based on system properties.
     * 
     * @return the detected platform
     * @throws UnsupportedPlatformException if the platform is not supported
     */
    public static Platform detectPlatform() {
        var osName = System.getProperty("os.name").toLowerCase();
        var osArch = System.getProperty("os.arch").toLowerCase();
        
        log.debug("Detecting platform - OS: {}, Arch: {}", osName, osArch);
        
        // Normalize architecture names
        var arch = normalizeArchitecture(osArch);
        
        // Detect OS and architecture combination
        if (osName.contains("mac") || osName.contains("darwin")) {
            if (arch.equals("aarch64")) {
                return Platform.MACOS_AARCH64;
            } else if (arch.equals("x86_64")) {
                return Platform.MACOS_X86_64;
            }
        } else if (osName.contains("linux")) {
            if (arch.equals("aarch64")) {
                return Platform.LINUX_AARCH64;
            } else if (arch.equals("x86_64")) {
                return Platform.LINUX_X86_64;
            }
        } else if (osName.contains("windows")) {
            if (arch.equals("aarch64")) {
                return Platform.WINDOWS_AARCH64;
            } else if (arch.equals("x86_64")) {
                return Platform.WINDOWS_X86_64;
            }
        }
        
        throw new UnsupportedPlatformException(
            String.format("Unsupported platform: OS=%s, Arch=%s", osName, osArch)
        );
    }
    
    /**
     * Normalize architecture names to standard values.
     * 
     * @param arch the architecture string from system properties
     * @return normalized architecture name
     */
    private static String normalizeArchitecture(String arch) {
        // ARM 64-bit variants
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        
        // x86 64-bit variants  
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) {
            return "x86_64";
        }
        
        // Return original if not recognized
        return arch;
    }
    
    /**
     * Check if the current platform is supported.
     * 
     * @return true if the platform is supported
     */
    public static boolean isPlatformSupported() {
        try {
            detectPlatform();
            return true;
        } catch (UnsupportedPlatformException e) {
            return false;
        }
    }
    
    /**
     * Get a human-readable description of the current platform.
     * 
     * @return platform description string
     */
    public static String getPlatformDescription() {
        try {
            var platform = detectPlatform();
            return String.format("%s (%s, %s)", 
                platform.toString(),
                System.getProperty("os.name"),
                System.getProperty("os.arch")
            );
        } catch (UnsupportedPlatformException e) {
            return String.format("Unsupported Platform (%s, %s)",
                System.getProperty("os.name"),
                System.getProperty("os.arch")
            );
        }
    }
}
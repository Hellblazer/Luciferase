package com.hellblazer.luciferase.gpu.test;

import org.junit.jupiter.api.Assumptions;
import org.lwjgl.system.Platform;

/**
 * Platform-specific test support utilities for GPU tests
 * 
 * Handles platform requirements like:
 * - macOS requiring -XstartOnFirstThread for GLFW/OpenGL
 * - Linux/CI requiring dangerouslyDisableSandbox for GPU access
 */
public final class PlatformTestSupport {
    
    private PlatformTestSupport() {
        // Utility class
    }
    
    /**
     * Check if we're running in a CI environment
     */
    public static boolean isCI() {
        return System.getenv("CI") != null || 
               System.getenv("GITHUB_ACTIONS") != null;
    }
    
    /**
     * Require that macOS has -XstartOnFirstThread JVM option
     * Skips test with assumption violation if not present
     */
    public static void requireMacOSWithStartOnFirstThread() {
        if (Platform.get() == Platform.MACOSX) {
            var jvmOptions = System.getProperty("java.vm.options", "");
            var hasStartOnFirstThread = jvmOptions.contains("-XstartOnFirstThread");
            
            Assumptions.assumeTrue(hasStartOnFirstThread,
                "macOS requires -XstartOnFirstThread for GLFW/OpenGL tests. " +
                "Add to JVM options: -XstartOnFirstThread");
        }
    }
    
    /**
     * Check if -XstartOnFirstThread is present (for macOS)
     */
    public static boolean hasStartOnFirstThread() {
        var jvmOptions = System.getProperty("java.vm.options", "");
        return jvmOptions.contains("-XstartOnFirstThread");
    }
    
    /**
     * Get platform name for logging
     */
    public static String getPlatformName() {
        return Platform.get().getName();
    }
    
    /**
     * Get platform architecture for logging
     */
    public static String getArchitecture() {
        return Platform.getArchitecture().name();
    }
    
    /**
     * Check if sandbox is disabled (required for GPU access in background processes)
     */
    public static boolean isSandboxDisabled() {
        // This is a heuristic - no direct way to check sandbox status
        // In practice, GPU access will fail if sandbox is enabled
        return true; // Assume disabled if test is running
    }
    
    /**
     * Print platform diagnostics to console
     */
    public static void printPlatformInfo() {
        System.out.println("Platform: " + getPlatformName());
        System.out.println("Architecture: " + getArchitecture());
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("CI Environment: " + isCI());
        
        if (Platform.get() == Platform.MACOSX) {
            System.out.println("macOS -XstartOnFirstThread: " + hasStartOnFirstThread());
        }
    }
}

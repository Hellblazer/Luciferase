package com.hellblazer.luciferase.gpu.test;

import org.lwjgl.system.Platform;
import org.junit.jupiter.api.Assumptions;

import java.util.Arrays;

/**
 * Utility class for platform-specific test execution control.
 * Provides methods to conditionally execute tests based on platform and architecture.
 * 
 * Uses JUnit 5 Assumptions for graceful test skipping.
 */
public final class PlatformTestSupport {
    
    private PlatformTestSupport() {
        // Utility class - no instantiation
    }
    
    /**
     * Executes test only on specified platforms.
     * 
     * @param supportedPlatforms platforms where the test should run
     * @throws org.opentest4j.TestAbortedException if current platform is not supported
     */
    public static void requirePlatform(Platform... supportedPlatforms) {
        var current = Platform.get();
        
        var isSupported = Arrays.stream(supportedPlatforms)
                                .anyMatch(platform -> platform == current);
        
        Assumptions.assumeTrue(isSupported, () -> 
            String.format("Test requires platforms: %s, but running on: %s", 
                         Arrays.toString(supportedPlatforms), current));
    }
    
    /**
     * Executes test only on specified architectures.
     * 
     * @param supportedArchs architectures where the test should run
     * @throws org.opentest4j.TestAbortedException if current architecture is not supported
     */
    public static void requireArchitecture(Platform.Architecture... supportedArchs) {
        var current = Platform.getArchitecture();
        
        var isSupported = Arrays.stream(supportedArchs)
                                .anyMatch(arch -> arch == current);
        
        Assumptions.assumeTrue(isSupported, () -> 
            String.format("Test requires architectures: %s, but running on: %s", 
                         Arrays.toString(supportedArchs), current));
    }
    
    /**
     * Skips test on specified platforms (useful for known issues).
     * 
     * @param unsupportedPlatforms platforms where the test should be skipped
     * @throws org.opentest4j.TestAbortedException if current platform is unsupported
     */
    public static void skipOnPlatform(Platform... unsupportedPlatforms) {
        var current = Platform.get();
        
        var shouldSkip = Arrays.stream(unsupportedPlatforms)
                               .anyMatch(platform -> platform == current);
        
        Assumptions.assumeFalse(shouldSkip, () -> 
            String.format("Test skipped on platform: %s", current));
    }
    
    /**
     * Skips test on specified architectures (useful for known issues).
     * 
     * @param unsupportedArchs architectures where the test should be skipped
     * @throws org.opentest4j.TestAbortedException if current architecture is unsupported
     */
    public static void skipOnArchitecture(Platform.Architecture... unsupportedArchs) {
        var current = Platform.getArchitecture();
        
        var shouldSkip = Arrays.stream(unsupportedArchs)
                               .anyMatch(arch -> arch == current);
        
        Assumptions.assumeFalse(shouldSkip, () -> 
            String.format("Test skipped on architecture: %s", current));
    }
    
    /**
     * Requires macOS with -XstartOnFirstThread for windowing tests.
     * 
     * @throws org.opentest4j.TestAbortedException if not on macOS or missing -XstartOnFirstThread
     */
    public static void requireMacOSWithStartOnFirstThread() {
        requirePlatform(Platform.MACOSX);
        
        var jvmOptions = System.getProperty("java.vm.options", "");
        var hasStartOnFirstThread = jvmOptions.contains("-XstartOnFirstThread");
        
        Assumptions.assumeTrue(hasStartOnFirstThread, 
            "macOS windowing tests require -XstartOnFirstThread JVM option");
    }
    
    /**
     * Requires 64-bit architecture.
     * 
     * @throws org.opentest4j.TestAbortedException if not on 64-bit architecture
     */
    public static void require64Bit() {
        var current = Platform.getArchitecture();
        boolean is64Bit = current == Platform.Architecture.X64 || 
                         current == Platform.Architecture.ARM64;
        Assumptions.assumeTrue(is64Bit, () -> 
            String.format("Test requires 64-bit architecture, but running on: %s", current));
    }
    
    /**
     * Skips test on ARM architectures (for JVM stack-related tests).
     * This addresses the known ARM limitation in LWJGL stack tests.
     * 
     * @throws org.opentest4j.TestAbortedException if running on ARM architecture
     */
    public static void skipOnARMForStackTests() {
        skipOnArchitecture(Platform.Architecture.ARM64, Platform.Architecture.ARM32);
    }
    
    /**
     * Gets a human-readable description of the current platform.
     * 
     * @return platform description string
     */
    public static String getCurrentPlatformDescription() {
        var platform = Platform.get();
        var arch = Platform.getArchitecture();
        
        boolean is64Bit = arch == Platform.Architecture.X64 || 
                         arch == Platform.Architecture.ARM64;
        return String.format("%s %s (%s-bit)", 
                           platform.getName(), 
                           arch, 
                           is64Bit ? "64" : "32");
    }
}

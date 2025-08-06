package com.hellblazer.luciferase.webgpu.platform;

/**
 * Represents supported platforms for WebGPU native libraries.
 */
public enum Platform {
    MACOS_AARCH64("macos-aarch64", "libwgpu_native.dylib", false),
    MACOS_X86_64("macos-x86_64", "libwgpu_native.dylib", false),
    LINUX_X86_64("linux-x86_64", "libwgpu_native.so", false),
    LINUX_AARCH64("linux-aarch64", "libwgpu_native.so", false),
    WINDOWS_X86_64("windows-x86_64", "wgpu_native.dll", true),
    WINDOWS_AARCH64("windows-aarch64", "wgpu_native.dll", true);
    
    private final String platformString;
    private final String libraryName;
    private final boolean isWindows;
    
    Platform(String platformString, String libraryName, boolean isWindows) {
        this.platformString = platformString;
        this.libraryName = libraryName;
        this.isWindows = isWindows;
    }
    
    /**
     * Get the platform string used for resource paths.
     * 
     * @return the platform string (e.g., "macos-aarch64")
     */
    public String getPlatformString() {
        return platformString;
    }
    
    /**
     * Get the native library name for this platform.
     * 
     * @return the library name (e.g., "libwgpu_native.dylib")
     */
    public String getLibraryName() {
        return libraryName;
    }
    
    /**
     * Check if this is a Windows platform.
     * 
     * @return true if Windows
     */
    public boolean isWindows() {
        return isWindows;
    }
    
    /**
     * Check if this is a macOS platform.
     * 
     * @return true if macOS
     */
    public boolean isMacOS() {
        return platformString.startsWith("macos");
    }
    
    /**
     * Check if this is a Linux platform.
     * 
     * @return true if Linux
     */
    public boolean isLinux() {
        return platformString.startsWith("linux");
    }
    
    /**
     * Check if this is an ARM64/AArch64 platform.
     * 
     * @return true if ARM64
     */
    public boolean isARM64() {
        return platformString.contains("aarch64");
    }
    
    /**
     * Check if this is an x86_64 platform.
     * 
     * @return true if x86_64
     */
    public boolean isX86_64() {
        return platformString.contains("x86_64");
    }
    
    @Override
    public String toString() {
        return platformString;
    }
}
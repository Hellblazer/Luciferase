package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.platform.PlatformDetector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test native library loading and version tracking.
 */
class NativeLibraryTest {
    
    @Test
    void testNativeLibraryLoading() {
        // Test that native library can be loaded from JAR resources
        var loaded = WebGPULoader.loadNativeLibrary();
        assertTrue(loaded, "Should be able to load native library from JAR");
        
        // Verify it's marked as loaded
        assertTrue(WebGPULoader.isLoaded(), "Library should be marked as loaded");
        
        // Try loading again - should be idempotent
        var loadedAgain = WebGPULoader.loadNativeLibrary();
        assertTrue(loadedAgain, "Should return true on subsequent calls");
    }
    
    @Test
    void testVersionInformation() {
        // Test that version information is available
        assertTrue(WebGPUVersion.isVersionInfoAvailable(), 
                   "Version information should be available");
        
        // Check version string
        var version = WebGPUVersion.getWgpuVersion();
        assertNotNull(version, "Version should not be null");
        assertNotEquals("unknown", version, "Version should be loaded from properties");
        assertTrue(version.startsWith("v"), "Version should start with 'v'");
        
        // Check release date
        var releaseDate = WebGPUVersion.getReleaseDate();
        assertNotNull(releaseDate, "Release date should not be null");
        assertNotEquals("unknown", releaseDate, "Release date should be loaded");
        
        // Check download date
        var downloadDate = WebGPUVersion.getDownloadDate();
        assertNotNull(downloadDate, "Download date should not be null");
        assertNotEquals("unknown", downloadDate, "Download date should be loaded");
        
        // Check formatted info
        var info = WebGPUVersion.getVersionInfo();
        assertNotNull(info, "Version info should not be null");
        assertTrue(info.contains("wgpu-native"), "Should contain library name");
        assertTrue(info.contains(version), "Should contain version number");
    }
    
    @Test
    void testNativeLibraryResourcePath() {
        // Verify that the native library exists in resources for current platform
        var platform = PlatformDetector.detectPlatform();
        var resourcePath = "/natives/" + platform.getPlatformString() + "/" + platform.getLibraryName();
        
        var resource = getClass().getResourceAsStream(resourcePath);
        assertNotNull(resource, "Native library should exist in JAR resources at: " + resourcePath);
        
        try {
            resource.close();
        } catch (Exception e) {
            // Ignore
        }
    }
}
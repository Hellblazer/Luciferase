package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.platform.Platform;
import com.hellblazer.luciferase.webgpu.platform.PlatformDetector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for platform detection.
 */
public class PlatformDetectorTest {
    
    @Test
    public void testPlatformDetection() {
        // Should detect current platform without throwing
        Platform platform = assertDoesNotThrow(() -> PlatformDetector.detectPlatform());
        
        assertNotNull(platform);
        System.out.println("Detected platform: " + platform);
        System.out.println("Platform description: " + PlatformDetector.getPlatformDescription());
        
        // Verify platform properties
        assertNotNull(platform.getLibraryName());
        assertNotNull(platform.getPlatformString());
        
        // Check platform type consistency
        if (platform.isMacOS()) {
            assertTrue(platform.getLibraryName().endsWith(".dylib"));
        } else if (platform.isLinux()) {
            assertTrue(platform.getLibraryName().endsWith(".so"));
        } else if (platform.isWindows()) {
            assertTrue(platform.getLibraryName().endsWith(".dll"));
        }
    }
    
    @Test
    public void testPlatformSupported() {
        // Current platform should be supported
        assertTrue(PlatformDetector.isPlatformSupported());
    }
    
    @Test
    public void testPlatformDescription() {
        String description = PlatformDetector.getPlatformDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
        System.out.println("Platform description: " + description);
    }
}
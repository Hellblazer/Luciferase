/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.hellblazer.luciferase.render.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Platform detection utilities for determining when to run native GPU tests
 * versus falling back to mocks. Prioritizes native testing when possible.
 * 
 * <p>On macOS, we can potentially test:
 * <ul>
 * <li>BGFX Metal backend - Native Metal support</li>
 * <li>OpenGL backend - If OpenGL context available</li>
 * <li>BGFX Vulkan - Via MoltenVK if available</li>
 * </ul>
 */
public class PlatformTestUtils {
    private static final Logger log = LoggerFactory.getLogger(PlatformTestUtils.class);
    
    private static final AtomicBoolean detectionPerformed = new AtomicBoolean(false);
    private static volatile PlatformCapabilities capabilities;
    
    /**
     * Platform capabilities detected at runtime.
     */
    public static class PlatformCapabilities {
        private final boolean macOS;
        private final boolean metalAvailable;
        private final boolean openGLAvailable;
        private final boolean vulkanAvailable;
        private final boolean headlessEnvironment;
        private final String metalVersion;
        private final String openGLVersion;
        private final boolean nativeTestingPreferred;
        
        public PlatformCapabilities(boolean macOS, boolean metalAvailable, boolean openGLAvailable, 
                                  boolean vulkanAvailable, boolean headlessEnvironment, 
                                  String metalVersion, String openGLVersion) {
            this.macOS = macOS;
            this.metalAvailable = metalAvailable;
            this.openGLAvailable = openGLAvailable;
            this.vulkanAvailable = vulkanAvailable;
            this.headlessEnvironment = headlessEnvironment;
            this.metalVersion = metalVersion;
            this.openGLVersion = openGLVersion;
            
            // Prefer native testing if we have any GPU capabilities and not in headless environment
            this.nativeTestingPreferred = !headlessEnvironment && (metalAvailable || openGLAvailable || vulkanAvailable);
        }
        
        public boolean isMacOS() { return macOS; }
        public boolean isMetalAvailable() { return metalAvailable; }
        public boolean isOpenGLAvailable() { return openGLAvailable; }
        public boolean isVulkanAvailable() { return vulkanAvailable; }
        public boolean isHeadlessEnvironment() { return headlessEnvironment; }
        public String getMetalVersion() { return metalVersion; }
        public String getOpenGLVersion() { return openGLVersion; }
        public boolean isNativeTestingPreferred() { return nativeTestingPreferred; }
        
        /**
         * Determines the best backend to test natively on this platform.
         */
        public GPUConfig.Backend getPreferredNativeBackend() {
            if (metalAvailable && macOS) {
                return GPUConfig.Backend.BGFX_METAL;
            } else if (openGLAvailable) {
                return GPUConfig.Backend.OPENGL;
            } else if (vulkanAvailable) {
                return GPUConfig.Backend.BGFX_VULKAN;
            }
            return null; // Fall back to mock
        }
        
        /**
         * Returns all backends that can be tested natively.
         */
        public GPUConfig.Backend[] getAvailableNativeBackends() {
            java.util.List<GPUConfig.Backend> backends = new java.util.ArrayList<>();
            
            if (metalAvailable && macOS) {
                backends.add(GPUConfig.Backend.BGFX_METAL);
            }
            if (openGLAvailable) {
                backends.add(GPUConfig.Backend.OPENGL);
            }
            if (vulkanAvailable) {
                backends.add(GPUConfig.Backend.BGFX_VULKAN);
            }
            
            return backends.toArray(new GPUConfig.Backend[0]);
        }
        
        @Override
        public String toString() {
            return "PlatformCapabilities{" +
                    "macOS=" + macOS +
                    ", metalAvailable=" + metalAvailable +
                    ", openGLAvailable=" + openGLAvailable +
                    ", vulkanAvailable=" + vulkanAvailable +
                    ", headlessEnvironment=" + headlessEnvironment +
                    ", metalVersion='" + metalVersion + '\'' +
                    ", openGLVersion='" + openGLVersion + '\'' +
                    ", nativeTestingPreferred=" + nativeTestingPreferred +
                    '}';
        }
    }
    
    /**
     * Detects platform capabilities for GPU testing.
     * Results are cached after first call.
     */
    public static PlatformCapabilities detectCapabilities() {
        if (detectionPerformed.get()) {
            return capabilities;
        }
        
        synchronized (PlatformTestUtils.class) {
            if (detectionPerformed.get()) {
                return capabilities;
            }
            
            log.info("Detecting platform capabilities for GPU testing...");
            
            boolean macOS = isMacOS();
            boolean headless = isHeadlessEnvironment();
            boolean metal = detectMetal();
            boolean openGL = detectOpenGL();
            boolean vulkan = detectVulkan();
            String metalVersion = getMetalVersion();
            String openGLVersion = getOpenGLVersion();
            
            capabilities = new PlatformCapabilities(macOS, metal, openGL, vulkan, headless, metalVersion, openGLVersion);
            detectionPerformed.set(true);
            
            log.info("Platform capabilities detected: {}", capabilities);
            
            return capabilities;
        }
    }
    
    /**
     * Creates the appropriate GPU context for testing.
     * Returns native context if available, otherwise mock context.
     */
    public static IGPUContext createTestGPUContext() {
        return createTestGPUContext(null);
    }
    
    /**
     * Creates the appropriate GPU context for testing with preferred backend.
     */
    public static IGPUContext createTestGPUContext(GPUConfig.Backend preferredBackend) {
        PlatformCapabilities caps = detectCapabilities();
        
        if (!caps.isNativeTestingPreferred()) {
            log.info("Using MockGPUContext for testing (no native GPU capabilities or headless environment)");
            return new MockGPUContext(preferredBackend != null ? preferredBackend : GPUConfig.Backend.OPENGL);
        }
        
        GPUConfig.Backend targetBackend = preferredBackend;
        if (targetBackend == null) {
            targetBackend = caps.getPreferredNativeBackend();
        }
        
        if (targetBackend == null) {
            log.info("No native backend available, using MockGPUContext");
            return new MockGPUContext(GPUConfig.Backend.OPENGL);
        }
        
        // Try to create native context first
        try {
            IGPUContext nativeContext = createNativeContext(targetBackend);
            if (nativeContext != null) {
                log.info("Created native GPU context for testing: {}", targetBackend);
                return nativeContext;
            }
        } catch (Exception e) {
            log.warn("Failed to create native GPU context for {}, falling back to mock: {}", targetBackend, e.getMessage());
        }
        
        // Fall back to mock
        log.info("Using MockGPUContext for testing with backend: {}", targetBackend);
        return new MockGPUContext(targetBackend);
    }
    
    /**
     * Determines if a specific backend should use native testing.
     */
    public static boolean shouldUseNativeTesting(GPUConfig.Backend backend) {
        PlatformCapabilities caps = detectCapabilities();
        
        if (!caps.isNativeTestingPreferred()) {
            return false;
        }
        
        return switch (backend) {
            case BGFX_METAL -> caps.isMetalAvailable() && caps.isMacOS();
            case OPENGL -> caps.isOpenGLAvailable();
            case BGFX_VULKAN -> caps.isVulkanAvailable();
            default -> false;
        };
    }
    
    // Platform detection methods
    
    private static boolean isMacOS() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }
    
    private static boolean isHeadlessEnvironment() {
        // Check for headless environment indicators
        String display = System.getenv("DISPLAY");
        String ci = System.getenv("CI");
        String headlessProperty = System.getProperty("java.awt.headless");
        
        return "true".equals(ci) || 
               "true".equals(headlessProperty) ||
               (display == null && isMacOS() == false) ||
               isRunningInContainer();
    }
    
    private static boolean isRunningInContainer() {
        try {
            // Check for container indicators
            return java.nio.file.Files.exists(java.nio.file.Paths.get("/.dockerenv")) ||
                   System.getenv("CONTAINER") != null ||
                   System.getenv("KUBERNETES_SERVICE_HOST") != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    private static boolean detectMetal() {
        if (!isMacOS()) {
            return false;
        }
        
        try {
            // Check if Metal framework is available
            ProcessBuilder pb = new ProcessBuilder("system_profiler", "SPDisplaysDataType");
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().contains("metal") && line.toLowerCase().contains("supported")) {
                        return true;
                    }
                }
            }
            
            // Also check for Metal via alternative method
            pb = new ProcessBuilder("xcrun", "--find", "metal");
            process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
            
        } catch (Exception e) {
            log.debug("Error detecting Metal support: {}", e.getMessage());
            // Assume Metal is available on macOS 10.11+ (most modern Macs)
            return true;
        }
    }
    
    private static boolean detectOpenGL() {
        try {
            // Try to detect OpenGL support without creating a context
            if (isMacOS()) {
                // On macOS, check for OpenGL framework
                ProcessBuilder pb = new ProcessBuilder("ls", "/System/Library/Frameworks/OpenGL.framework");
                Process process = pb.start();
                int exitCode = process.waitFor();
                return exitCode == 0;
            } else {
                // On other platforms, we'd need different detection
                return false;
            }
        } catch (Exception e) {
            log.debug("Error detecting OpenGL support: {}", e.getMessage());
            return false;
        }
    }
    
    private static boolean detectVulkan() {
        try {
            // Check for MoltenVK or Vulkan SDK
            ProcessBuilder pb = new ProcessBuilder("vulkaninfo", "--summary");
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            }
            
            // Check for MoltenVK library
            String[] moltenVKPaths = {
                "/usr/local/lib/libMoltenVK.dylib",
                "/opt/homebrew/lib/libMoltenVK.dylib",
                System.getProperty("user.home") + "/VulkanSDK/macOS/lib/libMoltenVK.dylib"
            };
            
            for (String path : moltenVKPaths) {
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(path))) {
                    return true;
                }
            }
            
        } catch (Exception e) {
            log.debug("Error detecting Vulkan support: {}", e.getMessage());
        }
        
        return false;
    }
    
    private static String getMetalVersion() {
        if (!isMacOS()) {
            return null;
        }
        
        try {
            ProcessBuilder pb = new ProcessBuilder("system_profiler", "SPSoftwareDataType");
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("System Version:")) {
                        // Extract macOS version, Metal version correlates with this
                        if (line.contains("macOS") && line.contains("15.")) {
                            return "3.0"; // macOS 15.x supports Metal 3.0
                        } else if (line.contains("macOS") && line.contains("14.")) {
                            return "3.0"; // macOS 14.x supports Metal 3.0
                        } else if (line.contains("macOS") && line.contains("13.")) {
                            return "2.4"; // macOS 13.x supports Metal 2.4
                        }
                        return "2.0"; // Default assumption
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error detecting Metal version: {}", e.getMessage());
        }
        
        return "2.0"; // Safe default for modern macOS
    }
    
    private static String getOpenGLVersion() {
        // This would typically require creating an OpenGL context
        // For now, return a reasonable default for macOS
        if (isMacOS()) {
            return "4.1"; // macOS typically supports OpenGL 4.1
        }
        return null;
    }
    
    private static IGPUContext createNativeContext(GPUConfig.Backend backend) {
        // This would create actual native contexts
        // For now, we'll enhance this in subsequent phases
        switch (backend) {
            case BGFX_METAL -> {
                // Would create actual BGFX Metal context
                // return new BGFXGPUContext(backend);
                return null; // Not implemented yet
            }
            case OPENGL -> {
                // Would create actual OpenGL context
                // return new OpenGLGPUContext();
                return null; // Not implemented yet
            }
            case BGFX_VULKAN -> {
                // Would create actual BGFX Vulkan context
                // return new BGFXGPUContext(backend);
                return null; // Not implemented yet
            }
            default -> {
                return null;
            }
        }
    }
    
    /**
     * Force reset of capabilities detection (for testing).
     */
    public static void resetDetection() {
        detectionPerformed.set(false);
        capabilities = null;
    }
}
package com.hellblazer.luciferase.webgpu.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Handles loading of native WebGPU libraries.
 * Simplified approach based on reference implementations.
 */
public class NativeLibraryLoader {
    private static final Logger log = LoggerFactory.getLogger(NativeLibraryLoader.class);
    
    private static boolean libraryLoaded = false;
    private static Throwable loadError = null;
    
    public enum Platform {
        WINDOWS("windows", "wgpu_native.dll"),
        LINUX("linux", "libwgpu_native.so"),
        MACOS("macos", "libwgpu_native.dylib"),
        UNKNOWN("unknown", "");
        
        private final String name;
        private final String libraryName;
        
        Platform(String name, String libraryName) {
            this.name = name;
            this.libraryName = libraryName;
        }
        
        public String getName() {
            return name;
        }
        
        public String getLibraryName() {
            return libraryName;
        }
    }
    
    public static synchronized boolean loadLibrary() {
        if (libraryLoaded) {
            return true;
        }
        
        if (loadError != null) {
            log.error("Previous library load failed", loadError);
            return false;
        }
        
        try {
            var platform = detectPlatform();
            if (platform == Platform.UNKNOWN) {
                throw new UnsupportedOperationException("Unsupported platform: " + System.getProperty("os.name"));
            }
            
            log.info("Detected platform: {}", platform.getName());
            
            // Try to load from system path first
            try {
                System.loadLibrary("wgpu_native");
                libraryLoaded = true;
                log.info("Loaded wgpu_native from system path");
                return true;
            } catch (UnsatisfiedLinkError e) {
                log.debug("Could not load from system path, trying bundled library");
            }
            
            // Try to load bundled library
            loadBundledLibrary(platform);
            libraryLoaded = true;
            log.info("Successfully loaded WebGPU native library");
            return true;
            
        } catch (Throwable t) {
            loadError = t;
            log.error("Failed to load WebGPU native library", t);
            return false;
        }
    }
    
    private static Platform detectPlatform() {
        var osName = System.getProperty("os.name").toLowerCase();
        
        if (osName.contains("win")) {
            return Platform.WINDOWS;
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            return Platform.MACOS;
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            return Platform.LINUX;
        }
        
        return Platform.UNKNOWN;
    }
    
    private static void loadBundledLibrary(Platform platform) throws IOException {
        var libraryName = platform.getLibraryName();
        var resourcePath = "/native/" + platform.getName() + "/" + libraryName;
        
        try (InputStream is = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Native library not found in resources: " + resourcePath);
            }
            
            // Create temp file
            Path tempFile = Files.createTempFile("wgpu_native", getLibraryExtension(platform));
            tempFile.toFile().deleteOnExit();
            
            // Copy library to temp file
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            
            // Load the library
            System.load(tempFile.toAbsolutePath().toString());
            log.info("Loaded bundled library from: {}", tempFile);
        }
    }
    
    private static String getLibraryExtension(Platform platform) {
        return switch (platform) {
            case WINDOWS -> ".dll";
            case LINUX -> ".so";
            case MACOS -> ".dylib";
            default -> "";
        };
    }
    
    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }
    
    public static Throwable getLoadError() {
        return loadError;
    }
}
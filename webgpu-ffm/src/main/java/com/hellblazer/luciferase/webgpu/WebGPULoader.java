package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.platform.Platform;
import com.hellblazer.luciferase.webgpu.platform.PlatformDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Responsible for loading the native WebGPU library for the current platform.
 * Handles extraction from JAR resources and platform-specific library naming.
 */
public class WebGPULoader {
    private static final Logger log = LoggerFactory.getLogger(WebGPULoader.class);
    private static final AtomicBoolean loaded = new AtomicBoolean(false);
    private static Path extractedLibraryPath = null;
    
    /**
     * Load the native WebGPU library for the current platform.
     * This method is idempotent - multiple calls will only load once.
     * 
     * @return true if the library was successfully loaded
     */
    public static synchronized boolean loadNativeLibrary() {
        if (loaded.get()) {
            log.debug("WebGPU native library already loaded");
            return true;
        }
        
        try {
            var platform = PlatformDetector.detectPlatform();
            log.info("Detected platform: {}", platform);
            
            var libraryName = platform.getLibraryName();
            log.info("Loading native library: {}", libraryName);
            
            // Try to load from system path first
            if (tryLoadFromSystemPath(libraryName)) {
                loaded.set(true);
                log.info("Loaded WebGPU library from system path");
                return true;
            }
            
            // Try to load from java.library.path
            if (tryLoadFromLibraryPath(libraryName)) {
                loaded.set(true);
                log.info("Loaded WebGPU library from java.library.path");
                return true;
            }
            
            // Extract from JAR resources
            if (extractAndLoad(platform, libraryName)) {
                loaded.set(true);
                log.info("Loaded WebGPU library from JAR resources");
                return true;
            }
            
            log.error("Failed to load WebGPU native library");
            return false;
            
        } catch (Exception e) {
            log.error("Error loading WebGPU native library", e);
            return false;
        }
    }
    
    /**
     * Get the path to the extracted native library.
     * 
     * @return the path to the extracted library, or null if not extracted
     */
    public static Path getExtractedLibraryPath() {
        return extractedLibraryPath;
    }
    
    /**
     * Check if the native library has been loaded.
     * 
     * @return true if the library is loaded
     */
    public static boolean isLoaded() {
        return loaded.get();
    }
    
    private static boolean tryLoadFromSystemPath(String libraryName) {
        try {
            // Try without extension
            var baseName = libraryName.substring(0, libraryName.lastIndexOf('.'));
            if (baseName.startsWith("lib")) {
                baseName = baseName.substring(3);
            }
            System.loadLibrary(baseName);
            return true;
        } catch (UnsatisfiedLinkError e) {
            log.debug("Library not found in system path: {}", e.getMessage());
            return false;
        }
    }
    
    private static boolean tryLoadFromLibraryPath(String libraryName) {
        var libraryPath = System.getProperty("java.library.path");
        if (libraryPath == null || libraryPath.isEmpty()) {
            return false;
        }
        
        for (var path : libraryPath.split(System.getProperty("path.separator"))) {
            var libPath = Path.of(path, libraryName);
            if (Files.exists(libPath)) {
                try {
                    System.load(libPath.toAbsolutePath().toString());
                    return true;
                } catch (UnsatisfiedLinkError e) {
                    log.debug("Failed to load library from path {}: {}", libPath, e.getMessage());
                }
            }
        }
        
        return false;
    }
    
    private static boolean extractAndLoad(Platform platform, String libraryName) {
        var resourcePath = "/natives/" + platform.getPlatformString() + "/" + libraryName;
        
        try (InputStream is = WebGPULoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.debug("Native library not found in resources: {}", resourcePath);
                return false;
            }
            
            // Create temporary file
            var tempDir = Files.createTempDirectory("webgpu-native-");
            extractedLibraryPath = tempDir.resolve(libraryName);
            
            // Copy library to temp file
            Files.copy(is, extractedLibraryPath, StandardCopyOption.REPLACE_EXISTING);
            
            // Make executable on Unix-like systems
            if (!platform.isWindows()) {
                extractedLibraryPath.toFile().setExecutable(true);
            }
            
            // Load the library
            System.load(extractedLibraryPath.toAbsolutePath().toString());
            
            // Register cleanup on JVM shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(extractedLibraryPath);
                    Files.deleteIfExists(tempDir);
                } catch (IOException e) {
                    log.debug("Failed to clean up extracted library: {}", e.getMessage());
                }
            }));
            
            return true;
            
        } catch (IOException e) {
            log.error("Failed to extract native library", e);
            return false;
        } catch (UnsatisfiedLinkError e) {
            log.error("Failed to load extracted library", e);
            return false;
        }
    }
    
    /**
     * Unload the native library (for testing purposes).
     * Note: This doesn't actually unload the native library from memory,
     * but resets the loader state.
     */
    static void reset() {
        loaded.set(false);
        extractedLibraryPath = null;
    }
}
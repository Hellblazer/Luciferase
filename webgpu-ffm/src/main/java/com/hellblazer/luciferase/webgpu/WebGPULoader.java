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
            
            // Load exclusively from JAR resources to ensure self-contained deployment
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
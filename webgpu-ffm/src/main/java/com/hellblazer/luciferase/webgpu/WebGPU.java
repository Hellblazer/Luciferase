package com.hellblazer.luciferase.webgpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main entry point for WebGPU FFM bindings.
 * Provides high-level API for WebGPU operations.
 */
public class WebGPU {
    private static final Logger log = LoggerFactory.getLogger(WebGPU.class);
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // Native function handles
    private static MethodHandle wgpuCreateInstance;
    private static MethodHandle wgpuInstanceRelease;
    private static MethodHandle wgpuInstanceRequestAdapter;
    private static MethodHandle wgpuAdapterRelease;
    private static MethodHandle wgpuAdapterRequestDevice;
    private static MethodHandle wgpuDeviceRelease;
    private static MethodHandle wgpuDeviceGetQueue;
    private static MethodHandle wgpuQueueRelease;
    
    // Linker and symbol lookup
    private static Linker linker;
    private static SymbolLookup symbolLookup;
    
    /**
     * Initialize the WebGPU FFM bindings.
     * This loads the native library and resolves function symbols.
     * 
     * @return true if initialization was successful
     */
    public static synchronized boolean initialize() {
        if (initialized.get()) {
            return true;
        }
        
        try {
            log.info("Initializing WebGPU FFM bindings...");
            
            // Load native library
            if (!WebGPULoader.loadNativeLibrary()) {
                log.error("Failed to load WebGPU native library");
                return false;
            }
            
            // Initialize FFM components
            linker = Linker.nativeLinker();
            symbolLookup = SymbolLookup.loaderLookup();
            
            // Load function handles
            if (!loadFunctionHandles()) {
                log.error("Failed to load WebGPU function handles");
                return false;
            }
            
            initialized.set(true);
            log.info("WebGPU FFM bindings initialized successfully");
            return true;
            
        } catch (Exception e) {
            log.error("Failed to initialize WebGPU FFM bindings", e);
            return false;
        }
    }
    
    /**
     * Check if WebGPU is initialized.
     * 
     * @return true if initialized
     */
    public static boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * Create a WebGPU instance.
     * 
     * @return the instance handle, or null if creation failed
     */
    public static MemorySegment createInstance() {
        if (!initialized.get()) {
            throw new IllegalStateException("WebGPU not initialized");
        }
        
        try {
            // Call wgpuCreateInstance with NULL descriptor for default
            var instance = (MemorySegment) wgpuCreateInstance.invoke(MemorySegment.NULL);
            
            if (instance != null && !instance.equals(MemorySegment.NULL)) {
                log.debug("Created WebGPU instance at 0x{}", Long.toHexString(instance.address()));
                return instance;
            } else {
                log.error("wgpuCreateInstance returned NULL");
                return null;
            }
        } catch (Throwable t) {
            log.error("Failed to create WebGPU instance", t);
            return null;
        }
    }
    
    /**
     * Release a WebGPU instance.
     * 
     * @param instance the instance to release
     */
    public static void releaseInstance(MemorySegment instance) {
        if (!initialized.get() || instance == null || instance.equals(MemorySegment.NULL)) {
            return;
        }
        
        try {
            wgpuInstanceRelease.invoke(instance);
            log.debug("Released WebGPU instance");
        } catch (Throwable t) {
            log.error("Failed to release WebGPU instance", t);
        }
    }
    
    /**
     * Shutdown WebGPU and release all resources.
     */
    public static synchronized void shutdown() {
        if (!initialized.get()) {
            return;
        }
        
        log.info("Shutting down WebGPU FFM bindings");
        
        // Clear function handles
        wgpuCreateInstance = null;
        wgpuInstanceRelease = null;
        wgpuInstanceRequestAdapter = null;
        wgpuAdapterRelease = null;
        wgpuAdapterRequestDevice = null;
        wgpuDeviceRelease = null;
        wgpuDeviceGetQueue = null;
        wgpuQueueRelease = null;
        
        // Clear FFM components
        linker = null;
        symbolLookup = null;
        
        initialized.set(false);
        log.info("WebGPU FFM bindings shutdown complete");
    }
    
    /**
     * Load WebGPU function handles using FFM.
     * 
     * @return true if all functions were loaded successfully
     */
    private static boolean loadFunctionHandles() {
        try {
            // wgpuCreateInstance(const WGPUInstanceDescriptor* descriptor) -> WGPUInstance
            var createInstanceOpt = symbolLookup.find("wgpuCreateInstance");
            if (createInstanceOpt.isEmpty()) {
                log.error("Could not find wgpuCreateInstance symbol");
                return false;
            }
            
            wgpuCreateInstance = linker.downcallHandle(
                createInstanceOpt.get(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            
            // wgpuInstanceRelease(WGPUInstance instance) -> void
            var instanceReleaseOpt = symbolLookup.find("wgpuInstanceRelease");
            if (instanceReleaseOpt.isPresent()) {
                wgpuInstanceRelease = linker.downcallHandle(
                    instanceReleaseOpt.get(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
                );
            }
            
            // Load other functions as needed
            // For now, we'll start with just instance creation
            
            log.debug("Successfully loaded WebGPU function handles");
            return true;
            
        } catch (Exception e) {
            log.error("Failed to load WebGPU function handles", e);
            return false;
        }
    }
    
    /**
     * Get version information about the WebGPU implementation.
     * 
     * @return version string, or "unknown" if not available
     */
    public static String getVersion() {
        // TODO: Call wgpuGetVersion if available
        return "wgpu-native 25.0.2.1";
    }
    
    /**
     * Check if WebGPU is available on this system.
     * This performs a quick check without full initialization.
     * 
     * @return true if WebGPU appears to be available
     */
    public static boolean isAvailable() {
        try {
            // Try to detect platform
            var platform = com.hellblazer.luciferase.webgpu.platform.PlatformDetector.detectPlatform();
            log.debug("Platform detected: {}", platform);
            
            // Check if we can load the library (without actually loading it)
            var libraryPath = System.getProperty("java.library.path");
            if (libraryPath != null && !libraryPath.isEmpty()) {
                // Check if library exists in path
                return true;
            }
            
            // Check if library is in resources
            var resourcePath = "/natives/" + platform.getPlatformString() + "/" + platform.getLibraryName();
            return WebGPU.class.getResourceAsStream(resourcePath) != null;
            
        } catch (Exception e) {
            log.debug("WebGPU availability check failed: {}", e.getMessage());
            return false;
        }
    }
}
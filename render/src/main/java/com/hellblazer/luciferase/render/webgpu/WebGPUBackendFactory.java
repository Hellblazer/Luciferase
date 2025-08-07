package com.hellblazer.luciferase.render.webgpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating WebGPU backend instances.
 * Automatically selects between FFM native and stub implementations.
 */
public class WebGPUBackendFactory {
    private static final Logger log = LoggerFactory.getLogger(WebGPUBackendFactory.class);
    
    private static volatile WebGPUBackend defaultBackend;
    private static final Object lock = new Object();
    
    /**
     * Create a smart WebGPU backend that automatically handles platform detection.
     * This is the recommended way to create a backend.
     */
    public static WebGPUBackend createBackend() {
        return new SmartWebGPUBackend();
    }
    
    /**
     * Create a WebGPU backend with optional native preference.
     * @param preferNative If true, attempts FFM backend first, otherwise uses stub
     * @deprecated Use createBackend() for automatic platform detection
     */
    @Deprecated
    public static WebGPUBackend createBackend(boolean preferNative) {
        if (preferNative) {
            // Try FFM backend first
            var ffmBackend = new FFMWebGPUBackend();
            if (ffmBackend.isAvailable()) {
                log.info("Created FFM WebGPU backend");
                return ffmBackend;
            } else {
                log.info("FFM WebGPU backend not available, falling back to stub");
            }
        }
        
        // Fall back to stub backend
        log.info("Created stub WebGPU backend");
        return new StubWebGPUBackend();
    }
    
    /**
     * Create a stub backend (useful for testing).
     */
    public static WebGPUBackend createStubBackend() {
        log.info("Created stub WebGPU backend");
        return new StubWebGPUBackend();
    }
    
    /**
     * Create an FFM backend (may fail if not available).
     */
    public static WebGPUBackend createFFMBackend() {
        log.info("Created FFM WebGPU backend");
        return new FFMWebGPUBackend();
    }
    
    /**
     * Get the default backend instance (singleton).
     * Creates a smart backend that auto-detects the platform.
     */
    public static WebGPUBackend getDefaultBackend() {
        if (defaultBackend == null) {
            synchronized (lock) {
                if (defaultBackend == null) {
                    defaultBackend = new SmartWebGPUBackend();
                    log.info("Initialized default WebGPU backend: {}", defaultBackend.getBackendName());
                }
            }
        }
        return defaultBackend;
    }
    
    /**
     * Set a custom default backend (useful for testing).
     * @param backend The backend to use as default
     */
    public static void setDefaultBackend(WebGPUBackend backend) {
        synchronized (lock) {
            if (defaultBackend != null && defaultBackend.isInitialized()) {
                defaultBackend.shutdown();
            }
            defaultBackend = backend;
            log.info("Set default WebGPU backend: {}", backend.getBackendName());
        }
    }
    
    /**
     * Shutdown the default backend and clear it.
     */
    public static void shutdownDefaultBackend() {
        synchronized (lock) {
            if (defaultBackend != null) {
                if (defaultBackend.isInitialized()) {
                    defaultBackend.shutdown();
                }
                defaultBackend = null;
                log.info("Shutdown default WebGPU backend");
            }
        }
    }
    
    /**
     * Check if native WebGPU is potentially available on this system.
     */
    public static boolean isNativeWebGPUAvailable() {
        return new FFMWebGPUBackend().isAvailable();
    }
}
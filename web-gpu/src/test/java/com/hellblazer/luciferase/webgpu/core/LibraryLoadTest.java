package com.hellblazer.luciferase.webgpu.core;

import com.hellblazer.luciferase.webgpu.util.NativeLibraryLoader;
import com.hellblazer.luciferase.webgpu.core.WebGPUTypes.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the native WebGPU library loads correctly and symbols are accessible.
 */
public class LibraryLoadTest {
    private static final Logger log = LoggerFactory.getLogger(LibraryLoadTest.class);
    
    @Test
    public void testLibraryLoads() {
        // Load the library
        boolean loaded = NativeLibraryLoader.loadLibrary();
        assertThat(loaded)
            .describedAs("Native library should load successfully")
            .isTrue();
        
        assertThat(NativeLibraryLoader.isLibraryLoaded())
            .describedAs("Library should report as loaded")
            .isTrue();
        
        assertThat(NativeLibraryLoader.getLoadError())
            .describedAs("No error should be present after successful load")
            .isNull();
        
        log.info("Library loaded successfully");
    }
    
    @Test
    public void testNativeInitialization() {
        // Ensure library is loaded
        boolean loaded = NativeLibraryLoader.loadLibrary();
        assertThat(loaded).isTrue();
        
        // Initialize WebGPU native bindings
        boolean initialized = WebGPUNative.initialize();
        assertThat(initialized)
            .describedAs("WebGPU native should initialize successfully")
            .isTrue();
        
        assertThat(WebGPUNative.isInitialized())
            .describedAs("WebGPU native should report as initialized")
            .isTrue();
        
        assertThat(WebGPUNative.getInitError())
            .describedAs("No init error should be present")
            .isNull();
        
        log.info("WebGPU native initialized successfully");
    }
    
    @Test
    public void testCanCreateInstance() {
        // Ensure library is loaded and initialized
        boolean loaded = NativeLibraryLoader.loadLibrary();
        assertThat(loaded).isTrue();
        
        boolean initialized = WebGPUNative.initialize();
        assertThat(initialized).isTrue();
        
        // Try to create a WebGPU instance using our bindings
        try (var arena = Arena.ofConfined()) {
            var instance = WebGPUNative.createInstance(arena);
            
            assertThat(instance)
                .describedAs("Instance should be created")
                .isNotNull();
            
            assertThat(instance.isNull())
                .describedAs("Instance should not be null")
                .isFalse();
            
            log.info("Successfully created WebGPU instance: {}", instance);
            
            // Clean up
            WebGPUNative.release(instance);
        }
    }
}
package com.hellblazer.luciferase.webgpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Dawn library as replacement for wgpu-native.
 * Dawn is Google's WebGPU implementation used in Chrome.
 */
public class DawnLibraryTest {
    private static final Logger log = LoggerFactory.getLogger(DawnLibraryTest.class);
    
    @BeforeAll
    static void setUp() {
        log.info("Testing with Dawn library as replacement for wgpu-native");
        log.info("Dawn library location: src/main/resources/natives/macos-aarch64/libwgpu_native.dylib");
    }
    
    @Test
    void testDawnInstance() {
        log.info("Creating WebGPU instance with Dawn library");
        
        try (var arena = Arena.ofConfined()) {
            var instance = WebGPU.createInstance();
            
            assertNotNull(instance);
            assertNotEquals(MemorySegment.NULL, instance);
            log.info("Successfully created WebGPU instance with Dawn: 0x{}", 
                    Long.toHexString(instance.address()));
                    
            WebGPU.releaseInstance(instance);
        }
    }
    
    @Test
    @Disabled("Testing instance creation first")
    void testDawnAdapter() {
        log.info("Testing adapter request with Dawn library");
        
        try (var arena = Arena.ofConfined()) {
            // Create instance
            var instance = WebGPU.createInstance();
            assertNotNull(instance);
            
            // Request adapter - this will likely fail with Dawn too
            // but let's see what error we get
            log.info("Attempting adapter request with Dawn...");
            
            WebGPU.releaseInstance(instance);
        }
    }
    
    @Test
    void testDawnLibraryLoaded() {
        log.info("Checking if Dawn library is loaded");
        
        // Try to create a simple instance to verify library loading
        try (var arena = Arena.ofConfined()) {
            var instance = WebGPU.createInstance();
            assertNotNull(instance, "Dawn library should create valid instance");
            
            // Check library info if available
            log.info("Dawn library loaded successfully");
            
            WebGPU.releaseInstance(instance);
        } catch (UnsatisfiedLinkError e) {
            fail("Dawn library failed to load: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error with Dawn library", e);
            fail("Dawn library test failed: " + e.getMessage());
        }
    }
}
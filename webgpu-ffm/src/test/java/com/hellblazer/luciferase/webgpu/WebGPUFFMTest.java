package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.*;

/**
 * Tests for WebGPU FFM bindings.
 */
public class WebGPUFFMTest {
    
    private boolean webgpuAvailable = false;
    
    @BeforeEach
    void setUp() {
        // Check if WebGPU is available
        webgpuAvailable = WebGPU.isAvailable();
        if (webgpuAvailable) {
            System.out.println("WebGPU appears to be available");
        } else {
            System.out.println("WebGPU not available - tests will be skipped");
        }
    }
    
    @AfterEach
    void tearDown() {
        WebGPU.shutdown();
    }
    
    @Test
    void testWebGPUInitialization() {
        if (!webgpuAvailable) {
            System.out.println("Skipping test - WebGPU not available");
            return;
        }
        
        // Try to initialize WebGPU
        boolean initialized = WebGPU.initialize();
        
        if (initialized) {
            assertTrue(WebGPU.isInitialized());
            System.out.println("WebGPU initialized successfully");
            
            // Try to create an instance
            var instance = WebGPU.createInstance();
            if (instance != null) {
                System.out.println("Created WebGPU instance at: 0x" + 
                    Long.toHexString(instance.address()));
                
                // Clean up
                WebGPU.releaseInstance(instance);
            } else {
                System.out.println("Failed to create WebGPU instance");
            }
        } else {
            System.out.println("WebGPU initialization failed - native library may not be available");
        }
    }
    
    @Test
    void testDescriptorLayouts() {
        // Test that descriptor layouts are properly defined
        assertNotNull(WebGPUNative.Descriptors.INSTANCE_DESCRIPTOR);
        assertNotNull(WebGPUNative.Descriptors.BUFFER_DESCRIPTOR);
        assertNotNull(WebGPUNative.Descriptors.DEVICE_DESCRIPTOR);
        assertNotNull(WebGPUNative.Descriptors.SHADER_MODULE_DESCRIPTOR);
        
        // Verify sizes are reasonable
        assertTrue(WebGPUNative.Descriptors.INSTANCE_DESCRIPTOR.byteSize() >= 8);
        assertTrue(WebGPUNative.Descriptors.BUFFER_DESCRIPTOR.byteSize() >= 24);
        
        System.out.println("Descriptor layouts:");
        System.out.println("  Instance: " + WebGPUNative.Descriptors.INSTANCE_DESCRIPTOR.byteSize() + " bytes");
        System.out.println("  Buffer: " + WebGPUNative.Descriptors.BUFFER_DESCRIPTOR.byteSize() + " bytes");
        System.out.println("  Device: " + WebGPUNative.Descriptors.DEVICE_DESCRIPTOR.byteSize() + " bytes");
    }
    
    @Test
    void testBufferUsageFlags() {
        // Test that buffer usage flags are defined
        assertEquals(0x00000001, WebGPUNative.BUFFER_USAGE_MAP_READ);
        assertEquals(0x00000002, WebGPUNative.BUFFER_USAGE_MAP_WRITE);
        assertEquals(0x00000004, WebGPUNative.BUFFER_USAGE_COPY_SRC);
        assertEquals(0x00000008, WebGPUNative.BUFFER_USAGE_COPY_DST);
        assertEquals(0x00000080, WebGPUNative.BUFFER_USAGE_STORAGE);
        
        // Test combining flags
        int storageAndCopy = WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST;
        assertEquals(0x00000088, storageAndCopy);
    }
    
    @Test
    void testCStringConversion() {
        try (var arena = Arena.ofConfined()) {
            // Test normal string
            var cstr = WebGPUNative.toCString("Hello WebGPU", arena);
            assertNotNull(cstr);
            assertNotEquals(MemorySegment.NULL, cstr);
            
            // Test null string
            var nullStr = WebGPUNative.toCString(null, arena);
            assertEquals(MemorySegment.NULL, nullStr);
            
            // Test empty string
            var emptyStr = WebGPUNative.toCString("", arena);
            assertNotNull(emptyStr);
            assertNotEquals(MemorySegment.NULL, emptyStr);
        }
    }
    
    @Test
    void testFunctionDescriptors() {
        // Verify function descriptors are properly defined
        assertNotNull(WebGPUNative.DESC_wgpuCreateInstance);
        assertNotNull(WebGPUNative.DESC_wgpuInstanceRelease);
        assertNotNull(WebGPUNative.DESC_wgpuDeviceCreateBuffer);
        assertNotNull(WebGPUNative.DESC_wgpuQueueWriteBuffer);
        
        // Verify return types
        var createInstanceReturn = WebGPUNative.DESC_wgpuCreateInstance.returnLayout();
        assertTrue(createInstanceReturn.isPresent());
        assertEquals(ValueLayout.ADDRESS, createInstanceReturn.get());
        
        // Verify void functions
        var releaseReturn = WebGPUNative.DESC_wgpuInstanceRelease.returnLayout();
        assertTrue(releaseReturn.isEmpty());
    }
}
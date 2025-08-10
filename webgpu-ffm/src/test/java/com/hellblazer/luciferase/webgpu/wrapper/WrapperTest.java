package com.hellblazer.luciferase.webgpu.wrapper;

import com.hellblazer.luciferase.webgpu.WebGPU;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for WebGPU wrapper classes.
 * These tests only run when WebGPU is actually available.
 */
public class WrapperTest {
    
    private static boolean webGPUAvailable = false;
    
    @BeforeAll
    static void checkWebGPU() {
        try {
            WebGPU.initialize();
            webGPUAvailable = WebGPU.isAvailable();
        } catch (Exception e) {
            webGPUAvailable = false;
        }
    }
    
    @Test
    void testWebGPUAvailability() {
        // This test always runs to verify WebGPU detection works
        assertDoesNotThrow(() -> {
            boolean available = WebGPU.isAvailable();
            // Just checking that we can query availability without crashing
        });
    }
    
    @Test
    void testEnumValues() {
        // Test enum values - these don't require WebGPU to be available
        
        // PowerPreference
        assertEquals(0, Instance.PowerPreference.UNDEFINED.getValue());
        assertEquals(1, Instance.PowerPreference.LOW_POWER.getValue());
        assertEquals(2, Instance.PowerPreference.HIGH_PERFORMANCE.getValue());
        
        // AdapterType
        assertNotNull(Adapter.AdapterType.UNKNOWN);
        assertNotNull(Adapter.AdapterType.INTEGRATED_GPU);
        assertNotNull(Adapter.AdapterType.DISCRETE_GPU);
        assertNotNull(Adapter.AdapterType.VIRTUAL_GPU);
        assertNotNull(Adapter.AdapterType.CPU);
        
        // BackendType
        assertNotNull(Adapter.BackendType.UNKNOWN);
        assertNotNull(Adapter.BackendType.VULKAN);
        assertNotNull(Adapter.BackendType.METAL);
        assertNotNull(Adapter.BackendType.D3D12);
        assertNotNull(Adapter.BackendType.OPENGL);
        
        // MapMode
        assertEquals(1, Buffer.MapMode.READ.getValue());
        assertEquals(2, Buffer.MapMode.WRITE.getValue());
    }
    
    @Test
    void testDescriptorBuilders() {
        // Test descriptor builders - these don't require WebGPU to be available
        
        // AdapterOptions
        var adapterOptions = new Instance.AdapterOptions()
            .withPowerPreference(Instance.PowerPreference.HIGH_PERFORMANCE)
            .withForceFallbackAdapter(true);
        
        assertEquals(Instance.PowerPreference.HIGH_PERFORMANCE, adapterOptions.getPowerPreference());
        assertTrue(adapterOptions.isForceFallbackAdapter());
        
        // DeviceDescriptor
        var deviceDesc = new Adapter.DeviceDescriptor()
            .withLabel("TestDevice")
            .withRequiredFeatures(1L, 2L, 3L);
        
        assertEquals("TestDevice", deviceDesc.getLabel());
        assertEquals(3, deviceDesc.getRequiredFeatures().length);
        
        // BufferDescriptor
        var bufferDesc = new Device.BufferDescriptor(1024, 0x80)
            .withLabel("TestBuffer")
            .withMappedAtCreation(false);
        
        assertEquals(1024, bufferDesc.getSize());
        assertEquals(0x80, bufferDesc.getUsage());
        assertEquals("TestBuffer", bufferDesc.getLabel());
        assertFalse(bufferDesc.isMappedAtCreation());
        
        // ShaderModuleDescriptor
        var shaderDesc = new Device.ShaderModuleDescriptor("@compute fn main() {}")
            .withLabel("TestShader");
        
        assertEquals("@compute fn main() {}", shaderDesc.getCode());
        assertEquals("TestShader", shaderDesc.getLabel());
    }
    
    @Test
    void testCommandBufferCreation() {
        // Simple object creation test
        var cmdBuffer = new CommandBuffer(42);
        assertEquals(42, cmdBuffer.getId());
    }
    
    @Test
    void testRealWebGPUInstance() {
        // Only run if WebGPU is actually available
        assumeTrue(webGPUAvailable, "WebGPU not available - skipping real instance test");
        
        // Create a real instance and verify it works
        var instance = new Instance();
        assertNotNull(instance);
        assertTrue(instance.isValid());
        
        // Request adapter with real instance
        var future = instance.requestAdapter();
        assertNotNull(future);
        
        try {
            var adapter = future.get();
            if (adapter != null) {
                // If we got an adapter, verify it's valid
                assertTrue(adapter.isValid());
                
                // Clean up
                adapter.close();
            }
        } catch (Exception e) {
            // Adapter request might fail on some systems, that's OK
            System.out.println("Adapter request failed (expected on some systems): " + e.getMessage());
        }
        
        // Clean up
        instance.close();
        assertFalse(instance.isValid());
    }
}
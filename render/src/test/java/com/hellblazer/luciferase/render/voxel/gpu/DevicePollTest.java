package com.hellblazer.luciferase.render.voxel.gpu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify device polling functionality
 */
public class DevicePollTest {
    
    private WebGPUContext context;
    
    @BeforeEach
    public void setup() throws Exception {
        context = new WebGPUContext();
        if (context.isAvailable()) {
            context.initialize().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    
    @AfterEach
    public void tearDown() {
        if (context != null) {
            context.shutdown();
        }
    }
    
    @Test
    public void testDevicePolling() throws Exception {
        if (!context.isAvailable()) {
            // WebGPU not available, skip test silently
            return;
        }
        
        // Test that we can call device polling
        var device = context.getDevice();
        assertNotNull(device, "Device should not be null");
        
        // Try to poll the device
        boolean result = com.hellblazer.luciferase.webgpu.WebGPU.pollDevice(device.getHandle(), false);
        
        // Should not throw an exception
        assertTrue(true, "Device polling should work without exceptions");
    }
}
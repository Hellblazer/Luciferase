package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that Dawn can create real devices without the callback crash.
 */
public class DawnDeviceCreationTest {
    private static final Logger log = LoggerFactory.getLogger(DawnDeviceCreationTest.class);
    
    @Test
    void testDawnCanCreateDevice() throws Exception {
        log.info("Testing Dawn device creation...");
        
        // Create instance
        var instance = new Instance();
        assertNotNull(instance);
        log.info("Created instance");
        
        // Request adapter
        var adapter = instance.requestAdapter()
            .get(5, TimeUnit.SECONDS);
        assertNotNull(adapter);
        log.info("Got adapter");
        
        // Request device - this is what crashes with wgpu-native but should work with Dawn
        log.info("Requesting device with Dawn...");
        var device = adapter.requestDevice()
            .get(5, TimeUnit.SECONDS);
        
        assertNotNull(device);
        log.info("✅ SUCCESS! Dawn created device without crashing!");
        
        // Verify device is valid
        assertTrue(device.isValid());
        
        // Get queue
        var queue = device.getQueue();
        assertNotNull(queue);
        log.info("Got queue from device");
        
        // Clean up
        device.close();
        adapter.close();
        instance.close();
        
        log.info("Test completed successfully - Dawn works!");
    }
    
    @Test
    void testDawnDeviceWithDescriptor() throws Exception {
        log.info("Testing Dawn device creation with descriptor...");
        
        var instance = new Instance();
        var adapter = instance.requestAdapter()
            .get(5, TimeUnit.SECONDS);
        
        // Create device with custom descriptor
        var descriptor = new Adapter.DeviceDescriptor()
            .withLabel("TestDevice");
        
        var device = adapter.requestDevice(descriptor)
            .get(5, TimeUnit.SECONDS);
        
        assertNotNull(device);
        assertTrue(device.isValid());
        log.info("✅ Created device with custom descriptor");
        
        device.close();
        adapter.close();
        instance.close();
    }
}
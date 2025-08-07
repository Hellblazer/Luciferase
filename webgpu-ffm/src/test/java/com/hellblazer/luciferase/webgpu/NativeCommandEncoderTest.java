package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test native command encoder functionality.
 * Verifies that the render module uses real native WebGPU calls.
 */
public class NativeCommandEncoderTest {
    private static final Logger log = LoggerFactory.getLogger(NativeCommandEncoderTest.class);
    private static Instance instance;
    private static Adapter adapter;
    private static Device device;
    
    private static boolean hasGPU() {
        return adapter != null && device != null;
    }
    
    @BeforeAll
    static void setUp() {
        try {
            // Initialize WebGPU
            WebGPU.initialize();
            assertTrue(WebGPU.isInitialized(), "WebGPU should be initialized");
            
            // Create instance
            instance = new Instance();
            assertNotNull(instance, "Instance should be created");
            
            // Get adapter - may fail in CI without GPU
            try {
                var adapterFuture = instance.requestAdapter(new Instance.AdapterOptions()
                    .withPowerPreference(Instance.PowerPreference.HIGH_PERFORMANCE));
                adapter = adapterFuture.get();
                
                if (adapter != null) {
                    // Get device
                    var deviceFuture = adapter.requestDevice(new Adapter.DeviceDescriptor()
                        .withLabel("Test Device"));
                    device = deviceFuture.get();
                    
                    if (device != null && device.isValid()) {
                        log.info("Native WebGPU initialized successfully");
                    } else {
                        log.info("Device creation failed - tests will be skipped");
                    }
                } else {
                    log.info("No GPU adapter available - tests will be skipped");
                }
            } catch (Exception e) {
                log.info("GPU initialization failed - tests will be skipped: {}", e.getMessage());
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize WebGPU instance", e);
            throw new RuntimeException("Failed to initialize WebGPU instance", e);
        }
    }
    
    @AfterAll
    static void tearDown() {
        if (device != null) {
            device.close();
        }
        if (adapter != null) {
            adapter.close();
        }
        if (instance != null) {
            instance.close();
        }
        // WebGPU cleanup is automatic
    }
    
    @Test
    void testNativeCommandEncoderCreation() {
        if (!hasGPU()) {
            log.info("Skipping test - no GPU adapter available");
            return;
        }
        
        log.info("Testing native command encoder creation");
        
        // Create command encoder
        var encoder = device.createCommandEncoder("Test Encoder");
        assertNotNull(encoder, "Command encoder should be created");
        assertNotNull(encoder.getHandle(), "Command encoder should have native handle");
        assertNotEquals(0L, encoder.getHandle().address(), 
            "Command encoder handle should not be null");
        
        log.info("Successfully created native command encoder");
        encoder.close();
    }
    
    @Test
    void testNativeComputePass() {
        if (!hasGPU()) {
            log.info("Skipping test - no GPU adapter available");
            return;
        }
        
        log.info("Testing native compute pass");
        
        // Create command encoder
        var encoder = device.createCommandEncoder("Compute Encoder");
        assertNotNull(encoder);
        
        // Begin compute pass
        var computePass = encoder.beginComputePass(
            new CommandEncoder.ComputePassDescriptor()
                .withLabel("Test Compute Pass"));
        assertNotNull(computePass, "Compute pass should be created");
        assertNotNull(computePass.getHandle(), "Compute pass should have native handle");
        
        // Note: Cannot dispatch workgroups without setting a pipeline first
        // This would cause a validation error from wgpu-native
        
        // End compute pass (native call)
        computePass.end();
        
        // Finish encoder (native call)
        var commandBuffer = encoder.finish();
        assertNotNull(commandBuffer, "Command buffer should be created");
        assertNotNull(commandBuffer.getHandle(), "Command buffer should have native handle");
        
        log.info("Successfully created native compute pass and command buffer");
    }
    
    @Test
    void testNativeQueueSubmission() {
        if (!hasGPU()) {
            log.info("Skipping test - no GPU adapter available");
            return;
        }
        
        log.info("Testing native queue submission");
        
        // Get queue
        var queue = device.getQueue();
        assertNotNull(queue, "Queue should exist");
        
        // Create and submit command buffer
        var encoder = device.createCommandEncoder("Submit Test");
        var computePass = encoder.beginComputePass(null);
        // Note: Not dispatching workgroups without pipeline
        computePass.end();
        var commandBuffer = encoder.finish();
        
        // Submit to native queue
        queue.submit(commandBuffer);
        
        log.info("Successfully submitted command buffer to native queue");
    }
    
    @Test
    void testMultipleCommandBuffers() {
        if (!hasGPU()) {
            log.info("Skipping test - no GPU adapter available");
            return;
        }
        
        log.info("Testing multiple command buffer submission");
        
        var queue = device.getQueue();
        
        // Create multiple command buffers
        var buffers = new CommandBuffer[3];
        for (int i = 0; i < buffers.length; i++) {
            var encoder = device.createCommandEncoder("Encoder " + i);
            var computePass = encoder.beginComputePass(null);
            // Note: Not dispatching workgroups without pipeline
            computePass.end();
            buffers[i] = encoder.finish();
        }
        
        // Submit all buffers at once
        queue.submit(buffers);
        
        log.info("Successfully submitted {} command buffers to native queue", buffers.length);
    }
    
    @Test
    void testCommandEncoderWithBuffer() {
        if (!hasGPU()) {
            log.info("Skipping test - no GPU adapter available");
            return;
        }
        
        log.info("Testing command encoder with buffer operations");
        
        // Create buffers
        var srcBuffer = device.createBuffer(new Device.BufferDescriptor(1024, 
            WebGPUNative.BUFFER_USAGE_COPY_SRC | WebGPUNative.BUFFER_USAGE_COPY_DST)
            .withLabel("Source Buffer"));
        var dstBuffer = device.createBuffer(new Device.BufferDescriptor(1024,
            WebGPUNative.BUFFER_USAGE_COPY_DST)
            .withLabel("Destination Buffer"));
        
        assertNotNull(srcBuffer);
        assertNotNull(dstBuffer);
        
        // Create command encoder for buffer operations
        var encoder = device.createCommandEncoder("Buffer Copy");
        
        // Note: copyBufferToBuffer is TODO in CommandEncoder
        // This test verifies the infrastructure is in place
        
        // For now, just create a compute pass to test the encoder
        var computePass = encoder.beginComputePass(null);
        // Note: Not dispatching workgroups without pipeline
        computePass.end();
        
        var commandBuffer = encoder.finish();
        device.getQueue().submit(commandBuffer);
        
        log.info("Successfully tested command encoder with buffers");
        
        srcBuffer.close();
        dstBuffer.close();
    }
    
    @Test
    void testNativeHandleValidation() {
        if (!hasGPU()) {
            log.info("Skipping test - no GPU adapter available");
            return;
        }
        
        log.info("Testing native handle validation");
        
        // Create multiple encoders and verify handles are unique
        var encoder1 = device.createCommandEncoder("Encoder 1");
        var encoder2 = device.createCommandEncoder("Encoder 2");
        
        assertNotNull(encoder1.getHandle());
        assertNotNull(encoder2.getHandle());
        assertNotEquals(encoder1.getHandle().address(), encoder2.getHandle().address(),
            "Each encoder should have a unique native handle");
        
        // Create compute passes and verify handles
        var pass1 = encoder1.beginComputePass(null);
        var pass2 = encoder2.beginComputePass(null);
        
        assertNotNull(pass1.getHandle());
        assertNotNull(pass2.getHandle());
        assertNotEquals(pass1.getHandle().address(), pass2.getHandle().address(),
            "Each compute pass should have a unique native handle");
        
        pass1.end();
        pass2.end();
        
        // Finish and verify command buffer handles
        var buffer1 = encoder1.finish();
        var buffer2 = encoder2.finish();
        
        assertNotNull(buffer1.getHandle());
        assertNotNull(buffer2.getHandle());
        assertNotEquals(buffer1.getHandle().address(), buffer2.getHandle().address(),
            "Each command buffer should have a unique native handle");
        
        log.info("All native handles are valid and unique");
    }
}
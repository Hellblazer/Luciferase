package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test native buffer operations.
 * Verifies that the render module uses real native WebGPU buffer operations.
 */
public class NativeBufferOperationsTest {
    private static final Logger log = LoggerFactory.getLogger(NativeBufferOperationsTest.class);
    private static Instance instance;
    private static Adapter adapter;
    private static Device device;
    
    @BeforeAll
    static void setUp() {
        try {
            // Initialize WebGPU
            WebGPU.initialize();
            assertTrue(WebGPU.isInitialized(), "WebGPU should be initialized");
            
            // Create instance
            instance = new Instance();
            assertNotNull(instance, "Instance should be created");
            
            // Get adapter
            var adapterFuture = instance.requestAdapter(new Instance.AdapterOptions()
                .withPowerPreference(Instance.PowerPreference.HIGH_PERFORMANCE));
            adapter = adapterFuture.get();
            assertNotNull(adapter, "Adapter should be available");
            
            // Get device
            var deviceFuture = adapter.requestDevice(new Adapter.DeviceDescriptor()
                .withLabel("Test Device"));
            device = deviceFuture.get();
            assertNotNull(device, "Device should be created");
            assertTrue(device.isValid(), "Device should be valid");
            
            log.info("Native WebGPU initialized successfully for buffer operations");
        } catch (Exception e) {
            log.error("Failed to initialize WebGPU", e);
            throw new RuntimeException("Failed to initialize WebGPU", e);
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
    }
    
    @Test
    void testNativeBufferCreation() {
        log.info("Testing native buffer creation");
        
        // Create a buffer
        var buffer = device.createBuffer(new Device.BufferDescriptor(1024,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST)
            .withLabel("Test Buffer"));
        
        assertNotNull(buffer, "Buffer should be created");
        assertNotNull(buffer.getHandle(), "Buffer should have native handle");
        assertNotEquals(0L, buffer.getHandle().address(), "Buffer handle should not be null");
        assertEquals(1024, buffer.getSize(), "Buffer size should match");
        
        log.info("Successfully created native buffer with size {}", buffer.getSize());
        buffer.close();
    }
    
    @Test
    void testNativeBufferWrite() {
        log.info("Testing native buffer write via queue");
        
        // Create a buffer
        var buffer = device.createBuffer(new Device.BufferDescriptor(256,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST)
            .withLabel("Write Test Buffer"));
        
        // Prepare test data
        byte[] testData = new byte[128];
        for (int i = 0; i < testData.length; i++) {
            testData[i] = (byte) (i % 256);
        }
        
        // Write data to buffer via native queue
        var queue = device.getQueue();
        queue.writeBuffer(buffer, 0, testData);
        
        log.info("Successfully wrote {} bytes to native buffer", testData.length);
        
        // Write more data at an offset
        byte[] moreData = new byte[64];
        for (int i = 0; i < moreData.length; i++) {
            moreData[i] = (byte) (255 - i);
        }
        queue.writeBuffer(buffer, 128, moreData);
        
        log.info("Successfully wrote {} more bytes at offset 128", moreData.length);
        
        buffer.close();
    }
    
    @Test
    void testNativeBufferWriteWithByteBuffer() {
        log.info("Testing native buffer write with ByteBuffer");
        
        // Create a buffer
        var buffer = device.createBuffer(new Device.BufferDescriptor(1024,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST)
            .withLabel("ByteBuffer Test"));
        
        // Create test data with ByteBuffer
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(256);
        FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
        
        // Write float data
        for (int i = 0; i < 64; i++) {
            floatBuffer.put(i * 0.5f);
        }
        byteBuffer.rewind();
        
        // Write to buffer
        var queue = device.getQueue();
        queue.writeBuffer(buffer, 0, byteBuffer);
        
        log.info("Successfully wrote {} bytes from ByteBuffer", byteBuffer.remaining());
        
        buffer.close();
    }
    
    @Test
    void testMultipleBufferCreation() {
        log.info("Testing multiple native buffer creation");
        
        var buffers = new Buffer[5];
        
        for (int i = 0; i < buffers.length; i++) {
            var size = 256 * (i + 1);
            buffers[i] = device.createBuffer(new Device.BufferDescriptor(size,
                WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST)
                .withLabel("Buffer " + i));
            
            assertNotNull(buffers[i], "Buffer " + i + " should be created");
            assertNotNull(buffers[i].getHandle(), "Buffer " + i + " should have native handle");
            assertEquals(size, buffers[i].getSize(), "Buffer " + i + " size should match");
        }
        
        // Verify all handles are unique
        for (int i = 0; i < buffers.length; i++) {
            for (int j = i + 1; j < buffers.length; j++) {
                assertNotEquals(buffers[i].getHandle().address(), buffers[j].getHandle().address(),
                    "Buffer handles should be unique");
            }
        }
        
        log.info("Successfully created {} native buffers with unique handles", buffers.length);
        
        // Clean up
        for (var buffer : buffers) {
            buffer.close();
        }
    }
    
    @Test
    void testBufferUsageFlags() {
        log.info("Testing buffer creation with different usage flags");
        
        // Test various usage combinations
        var usageTests = new int[][] {
            {WebGPUNative.BUFFER_USAGE_STORAGE},
            {WebGPUNative.BUFFER_USAGE_UNIFORM},
            {WebGPUNative.BUFFER_USAGE_VERTEX},
            {WebGPUNative.BUFFER_USAGE_INDEX},
            {WebGPUNative.BUFFER_USAGE_COPY_SRC | WebGPUNative.BUFFER_USAGE_COPY_DST},
            {WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST | WebGPUNative.BUFFER_USAGE_COPY_SRC}
        };
        
        for (var usage : usageTests) {
            var buffer = device.createBuffer(new Device.BufferDescriptor(512, usage[0])
                .withLabel("Usage Test Buffer"));
            
            assertNotNull(buffer, "Buffer should be created with usage " + usage[0]);
            assertNotNull(buffer.getHandle(), "Buffer should have native handle");
            assertEquals(usage[0], buffer.getUsage(), "Buffer usage should match");
            
            log.debug("Created buffer with usage flags: 0x{}", Integer.toHexString(usage[0]));
            
            buffer.close();
        }
        
        log.info("Successfully tested buffer creation with various usage flags");
    }
}
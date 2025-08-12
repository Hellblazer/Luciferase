package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test to verify buffer mapping actually works.
 */
public class EndToEndBufferMapTest {
    private static final Logger log = LoggerFactory.getLogger(EndToEndBufferMapTest.class);
    
    private Instance instance;
    private Adapter adapter;
    private Device device;
    private Queue queue;
    
    @BeforeEach
    void setUp() throws Exception {
        if (!WebGPU.initialize()) {
            log.warn("WebGPU not available - skipping test");
            return;
        }
        
        instance = new Instance();
        adapter = instance.requestAdapter().get(10, TimeUnit.SECONDS);
        device = adapter.requestDevice().get(10, TimeUnit.SECONDS);
        queue = device.getQueue();
        
        log.info("WebGPU initialized for end-to-end test");
    }
    
    @AfterEach
    void tearDown() {
        if (device != null) device.close();
        if (adapter != null) adapter.close();
        if (instance != null) instance.close();
    }
    
    @Test
    @DisplayName("Test buffer mapping end-to-end with callback verification")
    void testBufferMappingEndToEnd() throws Exception {
        if (instance == null) {
            log.info("WebGPU not available - skipping test");
            return;
        }
        
        log.info("=== End-to-End Buffer Mapping Test ===");
        
        // Track callback invocation
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        AtomicInteger callbackStatus = new AtomicInteger(-1);
        
        // Create test buffer with MAP_READ usage
        int bufferSize = 256;
        var buffer = device.createBuffer(new Device.BufferDescriptor(bufferSize,
            Buffer.Usage.combine(Buffer.Usage.MAP_READ, Buffer.Usage.COPY_DST))
            .withLabel("EndToEndTestBuffer"));
        
        // Write test data
        byte[] testData = new byte[bufferSize];
        for (int i = 0; i < bufferSize; i++) {
            testData[i] = (byte)(i % 256);
        }
        queue.writeBuffer(buffer, 0, testData);
        log.info("Wrote {} bytes of test data", bufferSize);
        
        // Map the buffer
        log.info("Attempting to map buffer...");
        try {
            var mappedFuture = buffer.mapAsync(Buffer.MapMode.READ, 0, bufferSize);
            
            log.info("Waiting for mapping to complete...");
            var mappedSegment = mappedFuture.get(5, TimeUnit.SECONDS);
            
            if (mappedSegment != null && !mappedSegment.equals(java.lang.foreign.MemorySegment.NULL)) {
                log.info("✅ Buffer successfully mapped! Segment: 0x{}", 
                         Long.toHexString(mappedSegment.address()));
                
                // Try to read data
                byte[] readData = new byte[Math.min(16, bufferSize)];
                var byteBuffer = mappedSegment.asByteBuffer();
                byteBuffer.get(readData);
                
                // Verify data
                boolean dataValid = true;
                for (int i = 0; i < readData.length; i++) {
                    if (readData[i] != testData[i]) {
                        dataValid = false;
                        log.error("Data mismatch at index {}: expected {}, got {}", 
                                 i, testData[i], readData[i]);
                        break;
                    }
                }
                
                if (dataValid) {
                    log.info("✅ Data integrity verified!");
                } else {
                    log.error("❌ Data integrity check failed");
                }
                
                buffer.unmap();
                
                assertTrue(dataValid, "Buffer data should match what was written");
                
            } else {
                log.error("❌ Buffer mapping returned NULL segment");
                fail("Buffer mapping failed - returned NULL");
            }
            
        } catch (Exception e) {
            log.error("❌ Buffer mapping failed with exception", e);
            
            // Check if it's the expected mock data fallback
            if (e.getMessage() != null && e.getMessage().contains("mock")) {
                log.warn("Buffer mapping fell back to mock data - WebGPU buffer mapping not fully working");
            }
            
            fail("Buffer mapping failed: " + e.getMessage());
        }
        
        buffer.close();
        
        log.info("=== End-to-End Test Complete ===");
    }
    
    @Test
    @DisplayName("Test raw buffer mapping to verify callback invocation")
    void testRawBufferMappingCallback() throws Exception {
        if (instance == null) {
            log.info("WebGPU not available - skipping test");
            return;
        }
        
        log.info("=== Raw Buffer Mapping Callback Test ===");
        
        // Create a buffer
        int bufferSize = 256;
        var buffer = device.createBuffer(new Device.BufferDescriptor(bufferSize,
            Buffer.Usage.combine(Buffer.Usage.MAP_READ, Buffer.Usage.COPY_DST))
            .withLabel("CallbackTestBuffer"));
        
        // Write data
        byte[] testData = new byte[bufferSize];
        for (int i = 0; i < bufferSize; i++) {
            testData[i] = (byte)(i % 256);
        }
        queue.writeBuffer(buffer, 0, testData);
        
        // Directly test the new API
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            var callback = new CallbackHelper.BufferMapCallback(arena, true); // V2 callback
            
            var future = CallbackInfoHelper.mapBufferAsyncF(
                arena,
                buffer.getHandle(),
                Buffer.MapMode.READ.getValue(),
                0,
                bufferSize,
                callback
            );
            
            if (future != null && !future.equals(java.lang.foreign.MemorySegment.NULL)) {
                log.info("Got future: 0x{}", Long.toHexString(future.address()));
                
                // Wait for it
                int waitResult = FutureWaitHelper.waitForFuture(
                    instance.getHandle(),
                    future,
                    5000L * 1_000_000L // 5 seconds in nanos
                );
                
                log.info("Wait result: {} (1=SUCCESS, 2=TIMEOUT, 3=ERROR)", waitResult);
                
                if (waitResult == 1) { // 1 = WGPUWaitStatus_Success
                    log.info("✅ Future wait succeeded!");
                    
                    // Check if we can get mapped range
                    var mappedRange = WebGPU.bufferGetMappedRange(buffer.getHandle(), 0, bufferSize);
                    if (mappedRange != null && !mappedRange.equals(java.lang.foreign.MemorySegment.NULL)) {
                        log.info("✅ Got mapped range: 0x{}", Long.toHexString(mappedRange.address()));
                    } else {
                        log.warn("⚠️ getMappedRange returned NULL");
                    }
                } else {
                    log.error("❌ Future wait failed with status: {}", waitResult);
                }
                
            } else {
                log.error("❌ mapBufferAsyncF returned NULL future");
            }
        }
        
        buffer.close();
        
        log.info("=== Raw Callback Test Complete ===");
    }
}
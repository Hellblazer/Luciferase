package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that basic buffer operations work without compute shaders.
 * This isolates whether the issue is with buffers or shaders.
 */
public class BufferCopyTest {
    private static final Logger log = LoggerFactory.getLogger(BufferCopyTest.class);
    
    @BeforeAll
    static void setup() {
        WebGPU.initialize();
    }
    
    @Test
    void testBufferWriteAndCopy() throws Exception {
        log.info("=== BUFFER COPY TEST START ===");
        
        try (var instance = new Instance()) {
            var adapter = instance.requestAdapter().get(5, TimeUnit.SECONDS);
            assertNotNull(adapter, "Failed to get adapter");
            
            try (adapter) {
                var device = adapter.requestDevice().get(5, TimeUnit.SECONDS);
                assertNotNull(device, "Failed to get device");
                
                try (device) {
                    int bufferSize = 64; // 16 floats
                    
                    // Source buffer - we'll write data here
                    var sourceBuffer = device.createBuffer(
                        new Device.BufferDescriptor(bufferSize,
                            WebGPUNative.BUFFER_USAGE_COPY_SRC | 
                            WebGPUNative.BUFFER_USAGE_COPY_DST)
                            .withLabel("source"));
                    
                    // Destination buffer - for GPU copy
                    var destBuffer = device.createBuffer(
                        new Device.BufferDescriptor(bufferSize,
                            WebGPUNative.BUFFER_USAGE_COPY_SRC | 
                            WebGPUNative.BUFFER_USAGE_COPY_DST)
                            .withLabel("dest"));
                    
                    // Staging buffer - for reading back
                    var stagingBuffer = device.createBuffer(
                        new Device.BufferDescriptor(bufferSize,
                            WebGPUNative.BUFFER_USAGE_MAP_READ | 
                            WebGPUNative.BUFFER_USAGE_COPY_DST)
                            .withLabel("staging"));
                    
                    // Write test data to source buffer
                    ByteBuffer testData = ByteBuffer.allocateDirect(bufferSize);
                    for (int i = 0; i < bufferSize / 4; i++) {
                        testData.putFloat(i * 10.0f); // 0, 10, 20, 30...
                    }
                    testData.flip();
                    
                    log.info("Writing test data to source buffer...");
                    device.getQueue().writeBuffer(sourceBuffer, 0, testData);
                    
                    // Synchronize
                    device.poll(true);
                    
                    // Create command encoder and copy source to dest
                    log.info("Copying source to destination buffer...");
                    var encoder = device.createCommandEncoder("copy_test");
                    encoder.copyBufferToBuffer(sourceBuffer, 0, destBuffer, 0, bufferSize);
                    encoder.copyBufferToBuffer(destBuffer, 0, stagingBuffer, 0, bufferSize);
                    var commandBuffer = encoder.finish();
                    
                    // Submit and wait
                    device.getQueue().submit(commandBuffer);
                    device.poll(true);
                    
                    // Read back results
                    log.info("Reading back results...");
                    var mappedSegment = stagingBuffer.mapAsync(
                        Buffer.MapMode.READ, 0, bufferSize)
                        .get(5, TimeUnit.SECONDS);
                    
                    ByteBuffer results = mappedSegment.asByteBuffer();
                    
                    // Verify the data
                    boolean allCorrect = true;
                    for (int i = 0; i < bufferSize / 4; i++) {
                        float expected = i * 10.0f;
                        float actual = results.getFloat();
                        log.info("Buffer[{}] = {} (expected {})", i, actual, expected);
                        
                        if (Math.abs(expected - actual) > 0.001f) {
                            allCorrect = false;
                        }
                    }
                    
                    stagingBuffer.unmap();
                    
                    assertTrue(allCorrect, "Buffer copy did not preserve data");
                    log.info("=== BUFFER COPY TEST PASSED ===");
                }
            }
        }
    }
}
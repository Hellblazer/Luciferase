package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test to debug compute shader execution.
 * Strips away all complexity to find the core issue.
 */
public class MinimalComputeTest {
    private static final Logger log = LoggerFactory.getLogger(MinimalComputeTest.class);
    
    @BeforeAll
    static void setup() {
        WebGPU.initialize();
    }
    
    @Test
    void testMinimalComputeShader() throws Exception {
        log.info("=== MINIMAL COMPUTE TEST START ===");
        
        try (var instance = new Instance()) {
            var adapter = instance.requestAdapter().get(5, TimeUnit.SECONDS);
            assertNotNull(adapter, "Failed to get adapter");
            
            try (adapter) {
                var device = adapter.requestDevice().get(5, TimeUnit.SECONDS);
                assertNotNull(device, "Failed to get device");
                
                try (device) {
                    // Try with explicit array size - some implementations require this
                    String shaderCode = """
                        @group(0) @binding(0)
                        var<storage, read_write> data: array<f32, 16>;
                        
                        @compute @workgroup_size(1, 1, 1)
                        fn main(@builtin(global_invocation_id) id: vec3<u32>) {
                            if (id.x == 0u) {
                                data[0] = 123.0;
                                data[1] = 456.0;
                            }
                        }
                        """;
                    
                    log.info("Creating shader module...");
                    var shader = device.createShaderModule(
                        new Device.ShaderModuleDescriptor(shaderCode)
                            .withLabel("minimal_compute"));
                    assertNotNull(shader, "Failed to create shader");
                    
                    // Single buffer for output (16 floats = 64 bytes)
                    int bufferSize = 64;
                    log.info("Creating buffer of {} bytes...", bufferSize);
                    
                    // Output buffer with STORAGE and COPY_SRC
                    // Also add COPY_DST so we can initialize it
                    var outputBuffer = device.createBuffer(
                        new Device.BufferDescriptor(bufferSize,
                            WebGPUNative.BUFFER_USAGE_STORAGE | 
                            WebGPUNative.BUFFER_USAGE_COPY_SRC |
                            WebGPUNative.BUFFER_USAGE_COPY_DST)
                            .withLabel("output"));
                    
                    // Initialize buffer with zeros
                    ByteBuffer zeros = ByteBuffer.allocateDirect(bufferSize);
                    for (int i = 0; i < bufferSize / 4; i++) {
                        zeros.putFloat(0.0f);
                    }
                    zeros.flip();
                    device.getQueue().writeBuffer(outputBuffer, 0, zeros);
                    log.info("Initialized output buffer with zeros");
                    
                    // Staging buffer for reading results
                    var stagingBuffer = device.createBuffer(
                        new Device.BufferDescriptor(bufferSize,
                            WebGPUNative.BUFFER_USAGE_MAP_READ | 
                            WebGPUNative.BUFFER_USAGE_COPY_DST)
                            .withLabel("staging"));
                    
                    // Create bind group layout with single buffer
                    log.info("Creating bind group layout...");
                    var bindGroupLayout = device.createBindGroupLayout(
                        new Device.BindGroupLayoutDescriptor()
                            .withLabel("minimal_layout")
                            .withEntry(new Device.BindGroupLayoutEntry(0, 
                                WebGPUNative.SHADER_STAGE_COMPUTE)
                                .withBuffer(new Device.BufferBindingLayout(
                                    WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)
                                    .withMinBindingSize(bufferSize))));
                    
                    // Create pipeline layout
                    log.info("Creating pipeline layout...");
                    var pipelineLayout = device.createPipelineLayout(
                        new Device.PipelineLayoutDescriptor()
                            .withLabel("minimal_pipeline_layout")
                            .addBindGroupLayout(bindGroupLayout));
                    
                    // Create compute pipeline
                    log.info("Creating compute pipeline...");
                    var pipeline = device.createComputePipeline(
                        new Device.ComputePipelineDescriptor(shader)
                            .withLabel("minimal_pipeline")
                            .withEntryPoint("main")
                            .withLayout(pipelineLayout));
                    
                    // Create bind group
                    log.info("Creating bind group...");
                    var bindGroup = device.createBindGroup(
                        new Device.BindGroupDescriptor(bindGroupLayout)
                            .withLabel("minimal_bind_group")
                            .withEntry(new Device.BindGroupEntry(0)
                                .withBuffer(outputBuffer, 0, bufferSize)));
                    
                    // Create and run compute pass
                    log.info("Creating command encoder...");
                    var encoder = device.createCommandEncoder("minimal_encoder");
                    
                    log.info("Beginning compute pass...");
                    var computePass = encoder.beginComputePass(
                        new CommandEncoder.ComputePassDescriptor()
                            .withLabel("minimal_pass"));
                    
                    log.info("Setting pipeline (handle: {})", pipeline.getHandle());
                    computePass.setPipeline(pipeline);
                    
                    log.info("Setting bind group (handle: {})", bindGroup.getHandle());
                    computePass.setBindGroup(0, bindGroup);
                    
                    log.info("Dispatching workgroups...");
                    computePass.dispatchWorkgroups(1, 1, 1);
                    
                    log.info("Ending compute pass...");
                    computePass.end();
                    
                    log.info("Copying buffer to staging...");
                    encoder.copyBufferToBuffer(outputBuffer, 0, stagingBuffer, 0, bufferSize);
                    
                    var commandBuffer = encoder.finish();
                    
                    log.info("Submitting to queue...");
                    device.getQueue().submit(commandBuffer);
                    
                    // Wait for GPU
                    log.info("Waiting for GPU...");
                    device.poll(true);
                    
                    // Read results
                    log.info("Mapping staging buffer...");
                    var mappedSegment = stagingBuffer.mapAsync(
                        Buffer.MapMode.READ, 0, bufferSize)
                        .get(5, TimeUnit.SECONDS);
                    
                    ByteBuffer data = mappedSegment.asByteBuffer();
                    // WebGPU uses native byte order which should be little-endian on most systems
                    data.order(ByteOrder.LITTLE_ENDIAN);
                    
                    // First, examine raw bytes to see what's actually in the buffer
                    log.info("Raw buffer bytes (first 32 bytes):");
                    byte[] rawBytes = new byte[Math.min(32, data.remaining())];
                    data.duplicate().get(rawBytes);
                    StringBuilder hexDump = new StringBuilder();
                    for (int i = 0; i < rawBytes.length; i++) {
                        if (i > 0 && i % 4 == 0) hexDump.append(" | ");
                        hexDump.append(String.format("%02X ", rawBytes[i] & 0xFF));
                    }
                    log.info("Hex: {}", hexDump);
                    
                    // Read floats using absolute positions
                    log.info("Reading floats from buffer using absolute offsets:");
                    for (int i = 0; i < 4; i++) {
                        float val = data.getFloat(i * 4);
                        log.info("Buffer[{}] at offset {} = {} (hex: {:08X})", 
                            i, i * 4, val, Float.floatToIntBits(val));
                    }
                    
                    // Get the first value for assertion
                    float firstValue = data.getFloat(0);
                    log.info("First value: {} (expected 123.0)", firstValue);
                    
                    stagingBuffer.unmap();
                    
                    // Assert the first value is what we expect
                    assertEquals(123.0f, firstValue, 0.001f, 
                        "Compute shader did not write expected value");
                    
                    log.info("=== TEST PASSED ===");
                }
            }
        }
    }
}
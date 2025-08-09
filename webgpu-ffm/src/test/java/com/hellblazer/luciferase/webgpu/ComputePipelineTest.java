package com.hellblazer.luciferase.webgpu;

import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test compute pipeline execution with real GPU.
 */
public class ComputePipelineTest {
    private static final Logger log = LoggerFactory.getLogger(ComputePipelineTest.class);
    
    @BeforeAll
    static void setup() {
        WebGPU.initialize();
    }
    
    @Test
    void testSimpleComputeShader() throws Exception {
        log.info("Testing simple compute shader execution");
        
        try (var instance = new Instance()) {
            var adapter = instance.requestAdapter().get(5, TimeUnit.SECONDS);
            assertNotNull(adapter, "Failed to get adapter");
            
            try (adapter) {
                var device = adapter.requestDevice().get(5, TimeUnit.SECONDS);
                assertNotNull(device, "Failed to get device");
                
                try (device) {
                    // Create a minimal compute shader that just writes a constant
                    // This is the simplest possible test
                    String shaderCode = """
                        @group(0) @binding(0)
                        var<storage, read> input: array<f32>;
                        
                        @group(0) @binding(1)
                        var<storage, read_write> output: array<f32>;
                        
                        @compute @workgroup_size(1, 1, 1)
                        fn main(@builtin(global_invocation_id) global_id: vec3<u32>) {
                            output[0] = 42.0;
                            output[1] = 2.0;
                            output[2] = 4.0;
                        }
                        """;
                    
                    var shaderDesc = new Device.ShaderModuleDescriptor(shaderCode)
                        .withLabel("compute");
                    var shader = device.createShaderModule(shaderDesc);
                    assertNotNull(shader, "Failed to create shader module");
                    
                    // Create input and output buffers
                    int dataSize = 256;
                    int bufferSize = dataSize * 4; // 4 bytes per float
                    
                    // Input buffer: COPY_DST (for write) | STORAGE (for compute)
                    var inputBufferDesc = new Device.BufferDescriptor(bufferSize,
                        WebGPUNative.BUFFER_USAGE_COPY_DST | WebGPUNative.BUFFER_USAGE_STORAGE)
                        .withLabel("input");
                    var inputBuffer = device.createBuffer(inputBufferDesc);
                    
                    // Output buffer: STORAGE (for compute) | COPY_SRC (to copy to staging)
                    var outputBufferDesc = new Device.BufferDescriptor(bufferSize,
                        WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_SRC)
                        .withLabel("output");
                    var outputBuffer = device.createBuffer(outputBufferDesc);
                    
                    // Staging buffer: MAP_READ | COPY_DST (for reading results)
                    var stagingBufferDesc = new Device.BufferDescriptor(bufferSize,
                        WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST)
                        .withLabel("staging");
                    var stagingBuffer = device.createBuffer(stagingBufferDesc);
                    
                    // Initialize input data
                    ByteBuffer inputData = ByteBuffer.allocateDirect(bufferSize);
                    for (int i = 0; i < dataSize; i++) {
                        inputData.putFloat((float) i);
                    }
                    inputData.flip();
                    
                    // Write input data to buffer
                    device.getQueue().writeBuffer(inputBuffer, 0, inputData);
                    log.info("Wrote {} floats to input buffer", dataSize);
                    
                    // Force GPU synchronization after write
                    device.poll(true);
                    log.info("Synchronized after buffer write");
                    
                    // Create bind group layout entries
                    var bindGroupLayoutDesc = new Device.BindGroupLayoutDescriptor()
                        .withLabel("compute bind group layout")
                        .withEntry(new Device.BindGroupLayoutEntry(0, WebGPUNative.SHADER_STAGE_COMPUTE)
                            .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)
                                .withMinBindingSize(bufferSize)))
                        .withEntry(new Device.BindGroupLayoutEntry(1, WebGPUNative.SHADER_STAGE_COMPUTE)
                            .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)
                                .withMinBindingSize(bufferSize)));
                    
                    var bindGroupLayout = device.createBindGroupLayout(bindGroupLayoutDesc);
                    assertNotNull(bindGroupLayout, "Failed to create bind group layout");
                    
                    // Create pipeline layout
                    var pipelineLayoutDesc = new Device.PipelineLayoutDescriptor()
                        .withLabel("compute pipeline layout")
                        .addBindGroupLayout(bindGroupLayout);
                    var pipelineLayout = device.createPipelineLayout(pipelineLayoutDesc);
                    assertNotNull(pipelineLayout, "Failed to create pipeline layout");
                    
                    // Create compute pipeline with explicit layout
                    var pipelineDesc = new Device.ComputePipelineDescriptor(shader)
                        .withLabel("compute")
                        .withEntryPoint("main")
                        .withLayout(pipelineLayout);
                    var pipeline = device.createComputePipeline(pipelineDesc);
                    assertNotNull(pipeline, "Failed to create compute pipeline");
                    
                    // Create bind group
                    var bindGroupDesc = new Device.BindGroupDescriptor(bindGroupLayout)
                        .withLabel("compute bind group")
                        .withEntry(new Device.BindGroupEntry(0)
                            .withBuffer(inputBuffer, 0, bufferSize))
                        .withEntry(new Device.BindGroupEntry(1)
                            .withBuffer(outputBuffer, 0, bufferSize));
                    var bindGroup = device.createBindGroup(bindGroupDesc);
                    assertNotNull(bindGroup, "Failed to create bind group");
                    
                    // Create command encoder and compute pass
                    var commandEncoder = device.createCommandEncoder("compute encoder");
                    var computePassDesc = new CommandEncoder.ComputePassDescriptor()
                        .withLabel("compute pass");
                    var computePass = commandEncoder.beginComputePass(computePassDesc);
                    
                    // Set pipeline and bind group
                    computePass.setPipeline(pipeline);
                    log.info("Set compute pipeline");
                    
                    computePass.setBindGroup(0, bindGroup);
                    log.info("Set bind group at index 0");
                    
                    // Dispatch just 1 workgroup for our simple test
                    computePass.dispatchWorkgroups(1, 1, 1);
                    log.info("Dispatched 1 workgroup");
                    
                    // End compute pass
                    computePass.end();
                    log.info("Ended compute pass");
                    
                    // Copy output to staging buffer
                    commandEncoder.copyBufferToBuffer(outputBuffer, 0, stagingBuffer, 0, bufferSize);
                    
                    // Finish and submit
                    var commandBuffer = commandEncoder.finish();
                    device.getQueue().submit(commandBuffer);
                    
                    // Wait for completion - poll with wait=true to block until complete
                    log.info("Waiting for GPU work to complete...");
                    device.poll(true);
                    log.info("GPU work completed");
                    
                    // Read back results from staging buffer
                    var mappedSegment = stagingBuffer.mapAsync(Buffer.MapMode.READ, 0, bufferSize).get(5, TimeUnit.SECONDS);
                    ByteBuffer outputData = mappedSegment.asByteBuffer();
                    // WebGPU uses native byte order (little-endian on most systems)
                    outputData.order(ByteOrder.LITTLE_ENDIAN);
                    assertNotNull(outputData, "Failed to map output buffer");
                    
                    // Verify results - we expect specific constant values
                    log.info("Reading back first 3 values from buffer");
                    
                    float val0 = outputData.getFloat();
                    float val1 = outputData.getFloat();
                    float val2 = outputData.getFloat();
                    
                    log.info("Output[0] = {} (expected 42.0)", val0);
                    log.info("Output[1] = {} (expected 2.0)", val1);
                    log.info("Output[2] = {} (expected 4.0)", val2);
                    
                    assertEquals(42.0f, val0, 0.001f, "Output[0] mismatch");
                    assertEquals(2.0f, val1, 0.001f, "Output[1] mismatch");
                    assertEquals(4.0f, val2, 0.001f, "Output[2] mismatch");
                    
                    stagingBuffer.unmap();
                    log.info("Compute shader successfully wrote {} values", dataSize);
                }
            }
        }
    }
    
    @Test 
    @Disabled("Matrix multiplication requires more complex setup")
    void testMatrixMultiplicationCompute() throws Exception {
        // TODO: Implement matrix multiplication compute shader test
        // This will be a more complex test demonstrating real GPU compute
    }
    
    @Test
    @Disabled("Requires indirect dispatch support")
    void testIndirectDispatch() throws Exception {
        // TODO: Test indirect dispatch functionality
    }
}
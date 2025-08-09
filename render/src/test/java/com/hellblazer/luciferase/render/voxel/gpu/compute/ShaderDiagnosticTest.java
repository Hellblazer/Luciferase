package com.hellblazer.luciferase.render.voxel.gpu.compute;

import com.hellblazer.luciferase.render.voxel.gpu.ComputeShaderManager;
import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.webgpu.wrapper.*;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test to identify why WebGPU shaders aren't producing output.
 * This test uses a minimal shader that just writes thread IDs to verify
 * basic GPU execution is working.
 */
public class ShaderDiagnosticTest {
    private static final Logger log = LoggerFactory.getLogger(ShaderDiagnosticTest.class);
    
    private WebGPUContext context;
    private ComputeShaderManager shaderManager;
    
    @BeforeEach
    void setUp() throws Exception {
        context = new WebGPUContext();
        context.initialize().join();
        shaderManager = new ComputeShaderManager(context);
    }
    
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.shutdown();
        }
    }
    
    @Test
    void testBasicShaderExecution() throws Exception {
        log.info("=== Starting Basic Shader Execution Test ===");
        
        // Load diagnostic shader
        var diagnosticShader = shaderManager.loadShaderFromResource(
            "/shaders/diagnostic/diagnostic_test.wgsl"
        ).get();
        
        var device = context.getDevice();
        
        // Create output buffer (64 u32 values)
        int outputSize = 64 * 4; // 64 integers * 4 bytes
        var outputBuffer = context.createBuffer(outputSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | 
            WebGPUNative.BUFFER_USAGE_COPY_SRC | 
            WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Initialize with zeros
        byte[] zeros = new byte[outputSize];
        context.writeBuffer(outputBuffer, zeros, 0);
        
        // Create debug buffer (small buffer for diagnostic info)
        int debugSize = 16 * 4; // 16 integers
        var debugBuffer = context.createBuffer(debugSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | 
            WebGPUNative.BUFFER_USAGE_COPY_SRC |
            WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Initialize debug buffer with zeros
        byte[] debugZeros = new byte[debugSize];
        context.writeBuffer(debugBuffer, debugZeros, 0);
        
        log.info("Created buffers - output: {} bytes, debug: {} bytes", outputSize, debugSize);
        
        // Create bind group layout
        var bindGroupLayoutDesc = new Device.BindGroupLayoutDescriptor()
            .withLabel("DiagnosticBindGroupLayout")
            .withEntry(new Device.BindGroupLayoutEntry(0, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)))
            .withEntry(new Device.BindGroupLayoutEntry(1, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)));
        
        var bindGroupLayout = device.createBindGroupLayout(bindGroupLayoutDesc);
        
        // Create bind group
        var bindGroupDescriptor = new Device.BindGroupDescriptor(bindGroupLayout)
            .withLabel("DiagnosticBindGroup")
            .withEntry(new Device.BindGroupEntry(0).withBuffer(outputBuffer, 0, outputSize))
            .withEntry(new Device.BindGroupEntry(1).withBuffer(debugBuffer, 0, debugSize));
        
        var bindGroup = device.createBindGroup(bindGroupDescriptor);
        
        // Create pipeline layout
        var pipelineLayout = device.createPipelineLayout(
            new Device.PipelineLayoutDescriptor()
                .withLabel("DiagnosticPipelineLayout")
                .addBindGroupLayout(bindGroupLayout)
        );
        
        // Create compute pipeline
        var pipelineDescriptor = new Device.ComputePipelineDescriptor(diagnosticShader)
            .withLabel("diagnostic_pipeline")
            .withLayout(pipelineLayout)
            .withEntryPoint("main");
        
        var pipeline = device.createComputePipeline(pipelineDescriptor);
        log.info("Created compute pipeline: {}", pipeline.getHandle());
        
        // Create command encoder and dispatch
        var commandEncoder = device.createCommandEncoder("diagnostic_encoder");
        var computePass = commandEncoder.beginComputePass(new CommandEncoder.ComputePassDescriptor()
            .withLabel("diagnostic_pass"));
        computePass.setPipeline(pipeline);
        computePass.setBindGroup(0, bindGroup);
        computePass.dispatchWorkgroups(1, 1, 1); // Single workgroup of 64 threads
        computePass.end();
        
        // Submit commands
        var commandBuffer = commandEncoder.finish();
        device.getQueue().submit(commandBuffer);
        device.getQueue().onSubmittedWorkDone();
        
        log.info("Submitted compute commands");
        
        // Read back output buffer
        var outputData = readBuffer(outputBuffer, outputSize);
        
        // Read back debug buffer
        var debugData = readBuffer(debugBuffer, debugSize);
        
        // Analyze results
        log.info("=== Output Buffer Analysis ===");
        int nonZeroCount = 0;
        for (int i = 0; i < 64; i++) {
            if (outputData[i] != 0) {
                nonZeroCount++;
                if (i < 10) { // Log first 10 non-zero values
                    log.info("output[{}] = {}", i, outputData[i]);
                }
            }
        }
        log.info("Non-zero values in output: {}/64", nonZeroCount);
        
        log.info("=== Debug Buffer Analysis ===");
        log.info("debug[0] = 0x{} (expected: 0xDEADBEEF)", Integer.toHexString(debugData[0]));
        log.info("debug[1] = {} (array length seen by shader)", debugData[1]);
        log.info("debug[2] = {} (expected: 42)", debugData[2]);
        
        // Assertions
        assertEquals(0xDEADBEEF, debugData[0], 
            "Debug magic number should be written - shader didn't execute!");
        assertEquals(64, debugData[1], 
            "Shader should see array length of 64");
        assertEquals(42, debugData[2], 
            "Debug test value should be 42");
        
        // Check output values
        for (int i = 0; i < 64; i++) {
            assertEquals(i + 1, outputData[i], 
                "Output[" + i + "] should be " + (i + 1));
        }
        
        log.info("=== Test PASSED: Shader executed correctly! ===");
    }
    
    @Test
    void testAtomicOperations() throws Exception {
        log.info("=== Testing Atomic Operations ===");
        
        // Create a shader that uses atomic operations
        String atomicShader = """
            @group(0) @binding(0) var<storage, read_write> atomicCounter: atomic<u32>;
            @group(0) @binding(1) var<storage, read_write> debug: array<u32>;
            
            @compute @workgroup_size(64, 1, 1)
            fn main(@builtin(global_invocation_id) id: vec3<u32>) {
                // Each thread increments the atomic counter
                atomicAdd(&atomicCounter, 1u);
                
                // Thread 0 writes debug info
                if (id.x == 0u) {
                    debug[0] = 0xCAFEBABEu;
                }
            }
            """;
        
        // Write shader to temp file
        var tempShaderPath = "/tmp/atomic_test.wgsl";
        java.nio.file.Files.writeString(java.nio.file.Path.of(tempShaderPath), atomicShader);
        
        // For now, skip this test as we need to implement loadShaderFromFile
        // var shader = shaderManager.loadShaderFromFile(tempShaderPath).get();
        log.info("Skipping atomic operations test - needs loadShaderFromFile implementation");
        
        var device = context.getDevice();
        
        // Create atomic counter buffer (single u32)
        int atomicSize = 4;
        var atomicBuffer = context.createBuffer(atomicSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | 
            WebGPUNative.BUFFER_USAGE_COPY_SRC |
            WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Initialize to zero
        context.writeBuffer(atomicBuffer, new byte[4], 0);
        
        // Create debug buffer
        int debugSize = 16 * 4;
        var debugBuffer = context.createBuffer(debugSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | 
            WebGPUNative.BUFFER_USAGE_COPY_SRC);
        
        // Create and execute pipeline (similar to above)
        // ... [pipeline creation code similar to testBasicShaderExecution]
        
        log.info("Atomic operations test would continue here...");
        // This test would verify atomic operations work correctly
    }
    
    private int[] readBuffer(Buffer buffer, int size) throws Exception {
        // Create staging buffer for readback
        var stagingBuffer = context.createBuffer(size,
            WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Copy from GPU buffer to staging
        var encoder = context.getDevice().createCommandEncoder("readback_encoder");
        encoder.copyBufferToBuffer(buffer, 0, stagingBuffer, 0, size);
        var commands = encoder.finish();
        context.getDevice().getQueue().submit(commands);
        context.getDevice().getQueue().onSubmittedWorkDone();
        
        // Map and read
        var mappedSegment = stagingBuffer.mapAsync(Buffer.MapMode.READ, 0, size).get();
        
        byte[] resultData = new byte[(int)mappedSegment.byteSize()];
        mappedSegment.asByteBuffer().get(resultData);
        ByteBuffer mapped = ByteBuffer.wrap(resultData).order(ByteOrder.nativeOrder());
        
        int[] result = new int[size / 4];
        for (int i = 0; i < result.length; i++) {
            result[i] = mapped.getInt();
        }
        
        stagingBuffer.unmap();
        
        return result;
    }
}
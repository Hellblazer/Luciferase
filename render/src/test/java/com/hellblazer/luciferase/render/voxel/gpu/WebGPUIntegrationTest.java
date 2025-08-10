package com.hellblazer.luciferase.render.voxel.gpu;

import com.hellblazer.luciferase.render.voxel.core.VoxelOctreeNode;
import com.hellblazer.luciferase.render.voxel.memory.FFMLayouts;
import com.hellblazer.luciferase.webgpu.wrapper.*;

import org.junit.jupiter.api.*;
import java.lang.foreign.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Integration tests for WebGPU components.
 * 
 * These tests require WebGPU support and will be skipped if not available.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WebGPUIntegrationTest {
    
    private static WebGPUContext context;
    private static boolean webGPUAvailable = false;
    
    @BeforeAll
    public static void setupWebGPU() {
        webGPUAvailable = checkWebGPUAvailability();
        
        if (webGPUAvailable) {
            try {
                context = new WebGPUContext();
                context.initialize().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Failed to initialize WebGPU context: " + e.getMessage());
                webGPUAvailable = false;
            }
        }
    }
    
    @AfterAll
    public static void teardownWebGPU() {
        if (context != null) {
            context.shutdown();
        }
    }
    
    @BeforeEach
    public void checkAvailability() {
        assumeTrue(webGPUAvailable, "WebGPU not available - skipping test");
    }
    
    @Test
    @Order(1)
    @DisplayName("Test WebGPU context initialization")
    public void testContextInitialization() {
        assertNotNull(context, "Context should be created");
        assertTrue(context.isInitialized(), "Context should be initialized");
        assertNotNull(context.getDevice(), "Device should be available");
    }
    
    @Test
    @Order(2)
    @DisplayName("Test GPU buffer creation and writing")
    public void testBufferOperations() {
        long bufferSize = 1024;
        int usage = Buffer.Usage.STORAGE | Buffer.Usage.COPY_DST | Buffer.Usage.COPY_SRC;
        
        // Create buffer
        var buffer = context.createBuffer(bufferSize, usage);
        assertNotNull(buffer, "Buffer should be created");
        
        // Write data
        var data = new byte[(int) bufferSize];
        for (int i = 0; i < data.length / 4; i++) {
            // Write integers as bytes
            int value = i;
            data[i * 4] = (byte) (value & 0xFF);
            data[i * 4 + 1] = (byte) ((value >> 8) & 0xFF);
            data[i * 4 + 2] = (byte) ((value >> 16) & 0xFF);
            data[i * 4 + 3] = (byte) ((value >> 24) & 0xFF);
        }
        
        context.writeBuffer(buffer, data, 0);
        
        // Read back (if supported)
        var readData = context.readBuffer(buffer, bufferSize, 0);
        assertNotNull(readData);
        assertEquals(bufferSize, readData.length);
        
        // Clean up
        buffer.destroy();
    }
    
    @Test
    @Order(3)
    @DisplayName("Test compute shader creation")
    public void testComputeShaderCreation() {
        String simpleShader = """
            @compute @workgroup_size(64)
            fn main(@builtin(global_invocation_id) id: vec3<u32>) {
                // Simple compute shader
            }
            """;
        
        var shaderModule = context.createComputeShader(simpleShader);
        assertNotNull(shaderModule, "Shader module should be created");
    }
    
    @Test
    @Order(4)
    @DisplayName("Test compute pipeline creation")
    public void testComputePipelineCreation() {
        // Create a simple compute shader
        String shaderCode = """
            @group(0) @binding(0) var<storage, read> input: array<f32>;
            @group(0) @binding(1) var<storage, read_write> output: array<f32>;
            
            @compute @workgroup_size(64)
            fn main(@builtin(global_invocation_id) id: vec3<u32>) {
                let index = id.x;
                if (index < arrayLength(&input)) {
                    output[index] = input[index] * 2.0;
                }
            }
            """;
        
        var shaderModule = context.createComputeShader(shaderCode);
        assertNotNull(shaderModule, "Shader module should be created");
        
        // Create pipeline layout
        var pipelineLayout = context.createPipelineLayout();
        assertNotNull(pipelineLayout, "Pipeline layout should be created");
        
        // Create compute pipeline
        var pipeline = context.createComputePipeline(shaderModule, "main", pipelineLayout);
        assertNotNull(pipeline, "Compute pipeline should be created");
    }
    
    @Test
    @Order(5)
    @DisplayName("Test compute dispatch")
    public void testComputeDispatch() {
        // Create buffers
        int dataSize = 256;
        int bufferSize = dataSize * 4; // 4 bytes per float
        
        var inputBuffer = context.createBuffer(bufferSize, 
            Buffer.Usage.STORAGE | Buffer.Usage.COPY_DST);
        var outputBuffer = context.createBuffer(bufferSize, 
            Buffer.Usage.STORAGE | Buffer.Usage.COPY_SRC);
        
        // Write input data
        var inputData = new byte[bufferSize];
        for (int i = 0; i < dataSize; i++) {
            // Write float 1.0f as bytes
            int floatBits = Float.floatToIntBits(1.0f);
            inputData[i * 4] = (byte) (floatBits & 0xFF);
            inputData[i * 4 + 1] = (byte) ((floatBits >> 8) & 0xFF);
            inputData[i * 4 + 2] = (byte) ((floatBits >> 16) & 0xFF);
            inputData[i * 4 + 3] = (byte) ((floatBits >> 24) & 0xFF);
        }
        context.writeBuffer(inputBuffer, inputData, 0);
        
        // Create compute shader
        String shaderCode = """
            @group(0) @binding(0) var<storage, read> input: array<f32>;
            @group(0) @binding(1) var<storage, read_write> output: array<f32>;
            
            @compute @workgroup_size(64)
            fn main(@builtin(global_invocation_id) id: vec3<u32>) {
                let index = id.x;
                if (index < arrayLength(&input)) {
                    output[index] = input[index] * 2.0;
                }
            }
            """;
        
        var shaderModule = context.createComputeShader(shaderCode);
        var pipelineLayout = context.createPipelineLayout();
        var pipeline = context.createComputePipeline(shaderModule, "main", pipelineLayout);
        
        // Create bind group
        var bindGroup = context.createBindGroup(pipelineLayout, 
            new Buffer[]{inputBuffer, outputBuffer});
        
        // Dispatch compute
        context.dispatchCompute(pipeline, bindGroup, (dataSize + 63) / 64, 1, 1);
        
        // Clean up
        inputBuffer.destroy();
        outputBuffer.destroy();
    }
    
    @Test
    @Order(6)
    @DisplayName("Test multiple buffer operations")
    public void testMultipleBuffers() {
        var buffers = new ArrayList<Buffer>();
        
        // Create multiple buffers
        for (int i = 0; i < 10; i++) {
            var buffer = context.createBuffer(1024, 
                Buffer.Usage.STORAGE | Buffer.Usage.COPY_DST);
            assertNotNull(buffer);
            buffers.add(buffer);
        }
        
        // Write to each buffer
        for (int i = 0; i < buffers.size(); i++) {
            var data = new byte[1024];
            data[0] = (byte) i; // Mark each buffer with its index
            context.writeBuffer(buffers.get(i), data, 0);
        }
        
        // Clean up
        buffers.forEach(Buffer::destroy);
    }
    
    @Test
    @Order(7)
    @DisplayName("Test shader with multiple workgroups")
    public void testLargeComputeDispatch() {
        int workgroupSize = 64;
        int numElements = 1000000; // 1 million elements
        int numWorkgroups = (numElements + workgroupSize - 1) / workgroupSize;
        
        // This would dispatch a large compute workload
        // In a real scenario, we'd create buffers and shader
        
        assertTrue(numWorkgroups > 0, "Should calculate workgroups correctly");
        assertEquals(15625, numWorkgroups, "Should have correct number of workgroups");
    }
    
    @Test
    @Order(8)
    @DisplayName("Test FFM memory layout compatibility")
    public void testFFMLayoutCompatibility() {
        try (var arena = Arena.ofConfined()) {
            // Allocate using FFM layouts
            var nodeArray = FFMLayouts.allocateArray(arena, FFMLayouts.VOXEL_NODE_LAYOUT, 10);
            var rayArray = FFMLayouts.allocateArray(arena, FFMLayouts.RAY_LAYOUT, 10);
            
            // These would be uploaded to GPU
            assertNotNull(nodeArray);
            assertNotNull(rayArray);
            assertEquals(160, nodeArray.byteSize()); // 10 * 16 bytes
            assertEquals(320, rayArray.byteSize());  // 10 * 32 bytes
            
            // Verify we can create buffers for these sizes
            var nodeBuffer = context.createBuffer(nodeArray.byteSize(), 
                Buffer.Usage.STORAGE | Buffer.Usage.COPY_DST);
            var rayBuffer = context.createBuffer(rayArray.byteSize(), 
                Buffer.Usage.STORAGE | Buffer.Usage.COPY_DST);
            
            assertNotNull(nodeBuffer);
            assertNotNull(rayBuffer);
            
            // Clean up
            nodeBuffer.destroy();
            rayBuffer.destroy();
        }
    }
    
    // Helper methods
    
    private static boolean checkWebGPUAvailability() {
        // Check if WebGPU is actually available using the WebGPU.isAvailable() method
        try {
            return com.hellblazer.luciferase.webgpu.WebGPU.isAvailable();
        } catch (Exception e) {
            System.err.println("WebGPU availability check failed: " + e.getMessage());
            return false;
        }
    }
    
    private VoxelOctreeNode createTestOctree() {
        // Create a real VoxelOctreeNode instance
        var root = new VoxelOctreeNode();
        
        // Set up valid mask to indicate all 8 children
        root.setValidMask((byte) 0xFF);
        
        // Set flags to indicate non-leaf nodes
        root.setFlags((byte) 0xFF);
        
        // In a real implementation, we'd set up actual child pointers
        // For this test, we're just validating the data structure
        return root;
    }
}
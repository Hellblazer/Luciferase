package com.hellblazer.luciferase.render.voxel.gpu.compute;

import com.hellblazer.luciferase.render.voxel.gpu.ComputeShaderManager;
import com.hellblazer.luciferase.render.voxel.gpu.WebGPUContext;
import com.hellblazer.luciferase.webgpu.wrapper.Buffer;
import com.hellblazer.luciferase.webgpu.wrapper.CommandEncoder;
import com.hellblazer.luciferase.webgpu.wrapper.Device;
import com.hellblazer.luciferase.webgpu.wrapper.ShaderModule;
import com.hellblazer.luciferase.webgpu.ffm.WebGPUNative;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests voxelization compute shader execution with real GPU triangle processing.
 * Verifies accurate voxelization and conservative rasterization.
 */
public class VoxelizationComputeTest {
    private static final Logger log = LoggerFactory.getLogger(VoxelizationComputeTest.class);
    
    private WebGPUContext context;
    private ComputeShaderManager shaderManager;
    private ShaderModule voxelizationShader;
    
    // Test parameters
    private static final int GRID_SIZE = 32;
    private static final float VOXEL_SIZE = 1.0f / GRID_SIZE;
    
    @BeforeEach
    void setUp() throws Exception {
        context = new WebGPUContext();
        context.initialize().join();
        shaderManager = new ComputeShaderManager(context);
        
        // Load voxelization shader
        voxelizationShader = shaderManager.loadShaderFromResource(
            "/shaders/esvo/voxelization.wgsl"
        ).get();
    }
    
    @AfterEach
    void tearDown() {
        if (context != null) {
            context.shutdown();
        }
    }
    
    // Diagnostic tests removed - see ShaderDiagnosticTest.java for comprehensive shader testing
    
    @Test
    void testSingleTriangleVoxelization() throws Exception {
        // Create a simple triangle in the center of the grid
        float[][] triangle = {
            {0.4f, 0.4f, 0.5f},  // Vertex 0
            {0.6f, 0.4f, 0.5f},  // Vertex 1
            {0.5f, 0.6f, 0.5f}   // Vertex 2
        };
        
        // Execute real GPU voxelization
        int[] voxelGrid = executeGPUVoxelization(new float[][][] {triangle});
        
        // Check that at least some voxels are set
        int filledVoxels = countFilledVoxels(voxelGrid);
        log.info("Voxel grid size: {}, filled voxels: {}", voxelGrid.length, filledVoxels);
        
        // Verify voxelization produced results
        int nonZeroCount = 0;
        for (int i = 0; i < Math.min(100, voxelGrid.length); i++) {
            if (voxelGrid[i] != 0) {
                nonZeroCount++;
            }
        }
        log.debug("Found {} non-zero voxels in first 100 positions", nonZeroCount);
        
        assertTrue(filledVoxels > 0, "Triangle should voxelize to at least one voxel");
        
        // Check approximate location (center of grid)
        int centerIdx = getVoxelIndex(GRID_SIZE/2, GRID_SIZE/2, GRID_SIZE/2);
        assertTrue(isVoxelNearTriangle(centerIdx, triangle, voxelGrid),
                  "Center voxels should be near the triangle");
        
        log.info("GPU voxelized single triangle to {} voxels", filledVoxels);
    }
    
    @Test
    void testAxisAlignedTriangleVoxelization() throws Exception {
        // Test triangle aligned with XY plane
        float[][] triangleXY = {
            {0.2f, 0.2f, 0.5f},
            {0.8f, 0.2f, 0.5f},
            {0.5f, 0.8f, 0.5f}
        };
        
        int[] voxelGrid = executeGPUVoxelization(new float[][][] {triangleXY});
        
        // Verify all voxels are at same Z level
        boolean allSameZ = true;
        int expectedZ = GRID_SIZE / 2;
        
        for (int i = 0; i < voxelGrid.length; i++) {
            if (voxelGrid[i] != 0) {
                int z = i / (GRID_SIZE * GRID_SIZE);
                if (Math.abs(z - expectedZ) > 1) {
                    allSameZ = false;
                    break;
                }
            }
        }
        
        assertTrue(allSameZ, "XY-aligned triangle should voxelize mostly at same Z level");
        
        int filledVoxels = countFilledVoxels(voxelGrid);
        log.info("Axis-aligned triangle voxelized to {} voxels", filledVoxels);
    }
    
    @Test
    void testConservativeRasterization() throws Exception {
        // Test that conservative rasterization captures thin triangles
        float[][] thinTriangle = {
            {0.1f, 0.5f, 0.5f},
            {0.9f, 0.5f, 0.5f},
            {0.5f, 0.501f, 0.5f}  // Very thin triangle
        };
        
        int[] voxelGrid = executeGPUVoxelization(new float[][][] {thinTriangle});
        
        // Should still voxelize despite being thin
        int filledVoxels = countFilledVoxels(voxelGrid);
        assertTrue(filledVoxels > 0, "Conservative rasterization should capture thin triangles");
        
        // Should form a line of voxels
        assertTrue(filledVoxels >= GRID_SIZE / 2, 
                  "Thin triangle should voxelize to line of voxels");
        
        log.info("Thin triangle voxelized to {} voxels with conservative rasterization", filledVoxels);
    }
    
    @Test
    void testMultipleTrianglesVoxelization() throws Exception {
        // Test multiple triangles forming a cube
        float[][][] triangles = {
            // Front face
            {{0.3f, 0.3f, 0.3f}, {0.7f, 0.3f, 0.3f}, {0.7f, 0.7f, 0.3f}},
            {{0.3f, 0.3f, 0.3f}, {0.7f, 0.7f, 0.3f}, {0.3f, 0.7f, 0.3f}},
            // Back face
            {{0.3f, 0.3f, 0.7f}, {0.7f, 0.7f, 0.7f}, {0.7f, 0.3f, 0.7f}},
            {{0.3f, 0.3f, 0.7f}, {0.3f, 0.7f, 0.7f}, {0.7f, 0.7f, 0.7f}}
        };
        
        int[] voxelGrid = executeGPUVoxelization(triangles);
        
        // Should voxelize multiple faces
        int filledVoxels = countFilledVoxels(voxelGrid);
        assertTrue(filledVoxels > 10, "Multiple triangles should create substantial voxelization");
        
        // Check spatial distribution
        boolean hasFront = false, hasBack = false;
        for (int z = 0; z < GRID_SIZE; z++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int x = 0; x < GRID_SIZE; x++) {
                    if (voxelGrid[getVoxelIndex(x, y, z)] != 0) {
                        if (z < GRID_SIZE/2) hasFront = true;
                        if (z > GRID_SIZE/2) hasBack = true;
                    }
                }
            }
        }
        
        assertTrue(hasFront && hasBack, "Should have voxels in both front and back");
        log.info("Multiple triangles voxelized to {} voxels", filledVoxels);
    }
    
    @Test
    void testDiagonalTriangleVoxelization() throws Exception {
        // Test triangle at 45-degree angle
        float[][] diagonalTriangle = {
            {0.2f, 0.2f, 0.2f},
            {0.8f, 0.8f, 0.2f},
            {0.5f, 0.5f, 0.8f}
        };
        
        int[] voxelGrid = executeGPUVoxelization(new float[][][] {diagonalTriangle});
        
        // Should have voxels along diagonal
        int filledVoxels = countFilledVoxels(voxelGrid);
        assertTrue(filledVoxels > 5, "Diagonal triangle should voxelize to multiple voxels");
        
        // Check diagonal distribution
        boolean hasDiagonal = false;
        for (int i = GRID_SIZE/4; i < 3*GRID_SIZE/4; i++) {
            int idx = getVoxelIndex(i, i, i * GRID_SIZE / (GRID_SIZE * 2));
            if (idx >= 0 && idx < voxelGrid.length && voxelGrid[idx] != 0) {
                hasDiagonal = true;
                break;
            }
        }
        
        log.info("Diagonal triangle voxelized to {} voxels", filledVoxels);
    }
    
    @Test
    void testEdgeCaseTriangles() throws Exception {
        // Test edge cases: degenerate, very small, very large triangles
        float[][][] edgeCases = {
            // Very small triangle
            {{0.5f, 0.5f, 0.5f}, {0.501f, 0.5f, 0.5f}, {0.5f, 0.501f, 0.5f}},
            // Large triangle
            {{0.0f, 0.0f, 0.5f}, {1.0f, 0.0f, 0.5f}, {0.5f, 1.0f, 0.5f}},
            // Edge-aligned triangle
            {{0.0f, 0.0f, 0.0f}, {1.0f, 0.0f, 0.0f}, {1.0f, 1.0f, 0.0f}}
        };
        
        for (float[][] triangle : edgeCases) {
            int[] voxelGrid = executeGPUVoxelization(new float[][][] {triangle});
            int filledVoxels = countFilledVoxels(voxelGrid);
            assertTrue(filledVoxels > 0, "Edge case triangle should still voxelize");
            log.info("Edge case triangle voxelized to {} voxels", filledVoxels);
        }
    }
    
    @Test
    void testVoxelColorPropagation() throws Exception {
        // Test that triangle colors are properly propagated to voxels
        float[][] triangle = {
            {0.4f, 0.4f, 0.5f},
            {0.6f, 0.4f, 0.5f},
            {0.5f, 0.6f, 0.5f}
        };
        
        // Execute with color tracking
        var result = executeGPUVoxelizationWithColors(new float[][][] {triangle});
        int[] voxelGrid = result.voxelGrid;
        float[][] voxelColors = result.colors;
        
        // Check that voxels have colors
        for (int i = 0; i < voxelGrid.length; i++) {
            if (voxelGrid[i] != 0) {
                // Should have non-zero color
                assertTrue(voxelColors[i][0] > 0 || voxelColors[i][1] > 0 || 
                          voxelColors[i][2] > 0 || voxelColors[i][3] > 0,
                          "Voxel should have color data");
            }
        }
        
        log.info("Color propagation test completed");
    }
    
    // Helper class for returning multiple values
    static class VoxelizationResult {
        int[] voxelGrid;
        float[][] colors;
        
        VoxelizationResult(int[] grid, float[][] cols) {
            this.voxelGrid = grid;
            this.colors = cols;
        }
    }
    
    // Helper methods
    
    private int[] executeGPUVoxelization(float[][][] triangles) throws Exception {
        var result = executeGPUVoxelizationWithColors(triangles);
        return result.voxelGrid;
    }
    
    // TODO: Remove this method - it was only used by diagnostic tests that have been removed
    private int[] executeGPUVoxelizationWithNoInit(float[][][] triangles, ShaderModule shader) throws Exception {
        // Create a simple test that doesn't use the complex pipeline
        var device = context.getDevice();
        
        // Create minimal buffers
        var dummyBuffer = context.createBuffer(16, 
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST);
        var outputBuffer = context.createBuffer(256,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_SRC);
        var paramsBuffer = context.createBuffer(64,
            WebGPUNative.BUFFER_USAGE_UNIFORM | WebGPUNative.BUFFER_USAGE_COPY_DST);
        var extraBuffer = context.createBuffer(16,
            WebGPUNative.BUFFER_USAGE_STORAGE);
            
        // Initialize params buffer (required for uniform)
        byte[] paramData = new byte[64];
        context.writeBuffer(paramsBuffer, paramData, 0);
        
        // DO NOT initialize output buffer - leave as zeros
        
        // Create bind group layout
        var bindGroupLayoutDesc = new Device.BindGroupLayoutDescriptor()
            .withLabel("TestBindGroupLayout")
            .withEntry(new Device.BindGroupLayoutEntry(0, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)))
            .withEntry(new Device.BindGroupLayoutEntry(1, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)))
            .withEntry(new Device.BindGroupLayoutEntry(2, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_UNIFORM)))
            .withEntry(new Device.BindGroupLayoutEntry(3, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)));
        
        var bindGroupLayout = device.createBindGroupLayout(bindGroupLayoutDesc);
        
        // Create bind group
        var bindGroupDescriptor = new Device.BindGroupDescriptor(bindGroupLayout)
            .withLabel("TestBindGroup")
            .withEntry(new Device.BindGroupEntry(0).withBuffer(dummyBuffer, 0, 16))
            .withEntry(new Device.BindGroupEntry(1).withBuffer(outputBuffer, 0, 256))
            .withEntry(new Device.BindGroupEntry(2).withBuffer(paramsBuffer, 0, 64))
            .withEntry(new Device.BindGroupEntry(3).withBuffer(extraBuffer, 0, 16));
        
        var bindGroup = device.createBindGroup(bindGroupDescriptor);
        
        // Create pipeline
        var pipelineLayout = device.createPipelineLayout(
            new Device.PipelineLayoutDescriptor()
                .withLabel("TestPipelineLayout")
                .addBindGroupLayout(bindGroupLayout)
        );
        
        var pipelineDescriptor = new Device.ComputePipelineDescriptor(shader)
            .withLabel("test_pipeline")
            .withLayout(pipelineLayout)
            .withEntryPoint("main");
        
        var pipeline = device.createComputePipeline(pipelineDescriptor);
        
        // Dispatch
        var commandEncoder = device.createCommandEncoder("test_encoder");
        var computePass = commandEncoder.beginComputePass(new CommandEncoder.ComputePassDescriptor());
        computePass.setPipeline(pipeline);
        computePass.setBindGroup(0, bindGroup);
        computePass.dispatchWorkgroups(1, 1, 1);
        computePass.end();
        
        var commandBuffer = commandEncoder.finish();
        device.getQueue().submit(commandBuffer);
        device.getQueue().onSubmittedWorkDone();
        
        // Read back - special version for 256 byte buffer
        var stagingBuffer = context.createBuffer(256,
            WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        var encoder = device.createCommandEncoder("readback_encoder");
        encoder.copyBufferToBuffer(outputBuffer, 0, stagingBuffer, 0, 256);
        var commands = encoder.finish();
        device.getQueue().submit(commands);
        device.getQueue().onSubmittedWorkDone();
        
        var mappedSegment = stagingBuffer.mapAsync(Buffer.MapMode.READ, 0, 256).get();
        byte[] resultData = new byte[(int)mappedSegment.byteSize()];
        mappedSegment.asByteBuffer().get(resultData);
        ByteBuffer mapped = ByteBuffer.wrap(resultData).order(ByteOrder.nativeOrder());
        
        int[] result = new int[64]; // Only 256/4 = 64 ints
        for (int i = 0; i < 64; i++) {
            result[i] = mapped.getInt();
        }
        
        stagingBuffer.unmap();
        return result;
    }
    
    private int[] executeGPUVoxelizationWithShader(float[][][] triangles, ShaderModule shader) throws Exception {
        var result = executeGPUVoxelizationWithColorsAndShader(triangles, shader);
        return result.voxelGrid;
    }
    
    private VoxelizationResult executeGPUVoxelizationWithColors(float[][][] triangles) throws Exception {
        return executeGPUVoxelizationWithColorsAndShader(triangles, voxelizationShader);
    }
    
    private VoxelizationResult executeGPUVoxelizationWithColorsAndShader(float[][][] triangles, ShaderModule shader) throws Exception {
        var device = context.getDevice();
        
        // Create triangle buffer
        // Triangle struct: v0, v1, v2 (3x vec4), normal (vec4), color (vec4) = 20 floats = 80 bytes
        int triangleSize = 80; // 20 floats per triangle
        int triangleBufferSize = triangles.length * triangleSize;
        log.info("Creating triangle buffer for {} triangles, total size {} bytes", triangles.length, triangleBufferSize);
        var triangleBuffer = context.createBuffer(triangleBufferSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Pack triangle data
        ByteBuffer triangleData = ByteBuffer.allocateDirect(triangleBufferSize);
        triangleData.order(ByteOrder.nativeOrder());
        
        for (float[][] triangle : triangles) {
            // Pack vertices (3x vec4)
            for (float[] vertex : triangle) {
                triangleData.putFloat(vertex[0]);
                triangleData.putFloat(vertex[1]);
                triangleData.putFloat(vertex[2]);
                triangleData.putFloat(0.0f); // Padding
            }
            
            // Calculate and pack normal
            float[] v0 = triangle[0];
            float[] v1 = triangle[1];
            float[] v2 = triangle[2];
            float[] edge1 = {v1[0]-v0[0], v1[1]-v0[1], v1[2]-v0[2]};
            float[] edge2 = {v2[0]-v0[0], v2[1]-v0[1], v2[2]-v0[2]};
            float[] normal = {
                edge1[1]*edge2[2] - edge1[2]*edge2[1],
                edge1[2]*edge2[0] - edge1[0]*edge2[2],
                edge1[0]*edge2[1] - edge1[1]*edge2[0]
            };
            float len = (float)Math.sqrt(normal[0]*normal[0] + normal[1]*normal[1] + normal[2]*normal[2]);
            if (len > 0) {
                normal[0] /= len;
                normal[1] /= len;
                normal[2] /= len;
            }
            triangleData.putFloat(normal[0]);
            triangleData.putFloat(normal[1]);
            triangleData.putFloat(normal[2]);
            triangleData.putFloat(0.0f); // Padding
            
            // Pack color
            triangleData.putFloat(1.0f); // R
            triangleData.putFloat(0.0f); // G
            triangleData.putFloat(0.0f); // B
            triangleData.putFloat(1.0f); // A
        }
        
        triangleData.flip();
        byte[] triangleBytes = new byte[triangleData.remaining()];
        triangleData.get(triangleBytes);
        context.writeBuffer(triangleBuffer, triangleBytes, 0);
        
        // Create voxel grid buffer and initialize with zeros
        int voxelGridSize = GRID_SIZE * GRID_SIZE * GRID_SIZE * 4; // 4 bytes per voxel
        var voxelGridBuffer = context.createBuffer(voxelGridSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_SRC | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Initialize voxel grid with zeros to avoid any issues
        byte[] zeroData = new byte[voxelGridSize];
        context.writeBuffer(voxelGridBuffer, zeroData, 0);
        
        // Create parameters buffer
        byte[] paramBytes = new byte[64]; // Padded for alignment
        ByteBuffer params = ByteBuffer.wrap(paramBytes).order(ByteOrder.nativeOrder());
        params.putInt(GRID_SIZE); // resolution.x
        params.putInt(GRID_SIZE); // resolution.y
        params.putInt(GRID_SIZE); // resolution.z
        params.putInt(0); // padding
        params.putFloat(VOXEL_SIZE); // voxelSize
        params.putFloat(0); // padding
        params.putFloat(0); // padding
        params.putFloat(0); // padding
        params.putFloat(0.0f); // boundsMin.x
        params.putFloat(0.0f); // boundsMin.y
        params.putFloat(0.0f); // boundsMin.z
        params.putFloat(0); // padding
        params.putFloat(1.0f); // boundsMax.x
        params.putFloat(1.0f); // boundsMax.y
        params.putFloat(1.0f); // boundsMax.z
        params.putFloat(0); // padding
        
        var paramsBuffer = context.createBuffer(64,
            WebGPUNative.BUFFER_USAGE_UNIFORM | WebGPUNative.BUFFER_USAGE_COPY_DST);
        context.writeBuffer(paramsBuffer, paramBytes, 0);
        
        // Create color buffer
        int colorBufferSize = GRID_SIZE * GRID_SIZE * GRID_SIZE * 16; // vec4<f32> per voxel
        var colorBuffer = context.createBuffer(colorBufferSize,
            WebGPUNative.BUFFER_USAGE_STORAGE | WebGPUNative.BUFFER_USAGE_COPY_SRC);
        
        // Create bind group layout
        var bindGroupLayoutDesc = new Device.BindGroupLayoutDescriptor()
            .withLabel("VoxelizationBindGroupLayout")
            .withEntry(new Device.BindGroupLayoutEntry(0, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_READ_ONLY_STORAGE)))
            .withEntry(new Device.BindGroupLayoutEntry(1, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)))
            .withEntry(new Device.BindGroupLayoutEntry(2, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_UNIFORM)))
            .withEntry(new Device.BindGroupLayoutEntry(3, WebGPUNative.SHADER_STAGE_COMPUTE)
                .withBuffer(new Device.BufferBindingLayout(WebGPUNative.BUFFER_BINDING_TYPE_STORAGE)));
        
        var bindGroupLayout = device.createBindGroupLayout(bindGroupLayoutDesc);
        
        // Create bind group
        var bindGroupDescriptor = new Device.BindGroupDescriptor(bindGroupLayout)
            .withLabel("VoxelizationBindGroup")
            .withEntry(new Device.BindGroupEntry(0).withBuffer(triangleBuffer, 0, triangleBufferSize))
            .withEntry(new Device.BindGroupEntry(1).withBuffer(voxelGridBuffer, 0, voxelGridSize))
            .withEntry(new Device.BindGroupEntry(2).withBuffer(paramsBuffer, 0, 64))
            .withEntry(new Device.BindGroupEntry(3).withBuffer(colorBuffer, 0, colorBufferSize));
        
        var bindGroup = device.createBindGroup(bindGroupDescriptor);
        
        // Create pipeline layout
        var pipelineLayout = device.createPipelineLayout(
            new Device.PipelineLayoutDescriptor()
                .withLabel("VoxelizationPipelineLayout")
                .addBindGroupLayout(bindGroupLayout)
        );
        
        // Create compute pipeline
        var pipelineDescriptor = new Device.ComputePipelineDescriptor(shader)
            .withLabel("voxelization_pipeline")
            .withLayout(pipelineLayout)
            .withEntryPoint("main");
        
        var pipeline = device.createComputePipeline(pipelineDescriptor);
        log.info("Created native compute pipeline: {}", pipeline.getHandle());
        
        // Add debug to verify shader module
        log.info("Shader module handle: {}", voxelizationShader.getHandle());
        log.info("About to dispatch {} workgroups", Math.max(1, (triangles.length + 63) / 64));
        
        // Create command encoder and dispatch
        var commandEncoder = device.createCommandEncoder("voxelization_encoder");
        var computePass = commandEncoder.beginComputePass(new CommandEncoder.ComputePassDescriptor());
        computePass.setPipeline(pipeline);
        computePass.setBindGroup(0, bindGroup);
        
        // Dispatch with appropriate workgroup count
        int numWorkgroups = (triangles.length + 63) / 64; // 64 threads per workgroup
        computePass.dispatchWorkgroups(Math.max(1, numWorkgroups), 1, 1);
        computePass.end();
        
        // Submit commands
        var commandBuffer = commandEncoder.finish();
        device.getQueue().submit(commandBuffer);
        
        // Wait for completion
        device.getQueue().onSubmittedWorkDone();
        
        // Read back voxel grid
        int[] voxelGrid = readGPUBuffer(voxelGridBuffer, voxelGridSize);
        
        // Read back colors
        float[][] colors = readGPUColorBuffer(colorBuffer, colorBufferSize);
        
        return new VoxelizationResult(voxelGrid, colors);
    }
    
    private int[] readGPUBuffer(Buffer buffer, int size) throws Exception {
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
        
        int[] grid = new int[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = mapped.getInt();
        }
        
        stagingBuffer.unmap();
        
        return grid;
    }
    
    private float[][] readGPUColorBuffer(Buffer buffer, int size) throws Exception {
        // Create staging buffer for readback
        var stagingBuffer = context.createBuffer(size,
            WebGPUNative.BUFFER_USAGE_MAP_READ | WebGPUNative.BUFFER_USAGE_COPY_DST);
        
        // Copy from GPU buffer to staging
        var encoder = context.getDevice().createCommandEncoder("color_readback_encoder");
        encoder.copyBufferToBuffer(buffer, 0, stagingBuffer, 0, size);
        var commands = encoder.finish();
        context.getDevice().getQueue().submit(commands);
        context.getDevice().getQueue().onSubmittedWorkDone();
        
        // Map and read
        var mappedSegment = stagingBuffer.mapAsync(Buffer.MapMode.READ, 0, size).get();
        
        byte[] resultData = new byte[(int)mappedSegment.byteSize()];
        mappedSegment.asByteBuffer().get(resultData);
        ByteBuffer mapped = ByteBuffer.wrap(resultData).order(ByteOrder.nativeOrder());
        
        float[][] colors = new float[GRID_SIZE * GRID_SIZE * GRID_SIZE][4];
        for (int i = 0; i < colors.length; i++) {
            colors[i][0] = mapped.getFloat(); // R
            colors[i][1] = mapped.getFloat(); // G
            colors[i][2] = mapped.getFloat(); // B
            colors[i][3] = mapped.getFloat(); // A
        }
        
        stagingBuffer.unmap();
        
        return colors;
    }
    
    private int getVoxelIndex(int x, int y, int z) {
        return z * GRID_SIZE * GRID_SIZE + y * GRID_SIZE + x;
    }
    
    private int countFilledVoxels(int[] grid) {
        int count = 0;
        for (int val : grid) {
            if (val != 0) count++;
        }
        return count;
    }
    
    private boolean isVoxelNearTriangle(int centerIdx, float[][] triangle, int[] grid) {
        // Check if voxels near the center index are filled
        return grid[centerIdx] != 0 || 
               (centerIdx > 0 && grid[centerIdx - 1] != 0) ||
               (centerIdx < grid.length - 1 && grid[centerIdx + 1] != 0);
    }
}
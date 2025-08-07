package com.hellblazer.luciferase.render.webgpu;

import com.hellblazer.luciferase.render.voxel.VoxelRenderPipeline;
import org.junit.jupiter.api.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for WebGPU render module with FFM bindings.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RenderIntegrationTest {
    
    private static WebGPURenderBridge bridge;
    private static VoxelRenderPipeline pipeline;
    private static boolean gpuAvailable = false;
    
    @BeforeAll
    static void setup() {
        try {
            // Initialize render bridge
            bridge = new WebGPURenderBridge();
            var initFuture = bridge.initialize();
            gpuAvailable = initFuture.get(5, TimeUnit.SECONDS);
            
            if (gpuAvailable) {
                // Create voxel pipeline
                pipeline = new VoxelRenderPipeline(bridge);
                var pipelineFuture = pipeline.initialize();
                assertTrue(pipelineFuture.get(5, TimeUnit.SECONDS), 
                          "Pipeline initialization should succeed");
                
                System.out.println("Render Integration Tests - WebGPU initialized successfully");
            }
        } catch (Exception e) {
            System.err.println("GPU not available for render integration tests: " + e.getMessage());
            gpuAvailable = false;
        }
    }
    
    @AfterAll
    static void cleanup() {
        if (pipeline != null) {
            pipeline.shutdown();
        }
        if (bridge != null) {
            bridge.shutdown();
        }
    }
    
    @Test
    @Order(1)
    void testBridgeInitialization() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        assertNotNull(bridge, "Bridge should be created");
        assertTrue(bridge.isReady(), "Bridge should be ready");
        assertNotNull(bridge.getDevice(), "Device should be available");
        assertNotNull(bridge.getQueue(), "Queue should be available");
    }
    
    @Test
    @Order(2)
    void testVoxelBufferCreation() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Create a voxel buffer
        int size = 1024 * 1024; // 1MB
        var buffer = bridge.createVoxelBuffer(size, 0x80); // STORAGE usage
        
        assertNotNull(buffer, "Buffer should be created");
        assertEquals(size, buffer.getSize(), "Buffer size should match");
        
        // Clean up
        buffer.close();
    }
    
    @Test
    @Order(3)
    void testShaderCreation() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Create a simple compute shader
        String shaderCode = """
            @compute @workgroup_size(1)
            fn main(@builtin(global_invocation_id) id: vec3<u32>) {
                // Simple shader
            }
            """;
        
        var shader = bridge.createVoxelShader(shaderCode);
        assertNotNull(shader, "Shader should be created");
        
        // Clean up
        shader.close();
    }
    
    @Test
    @Order(4)
    void testVoxelization() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Create test mesh data (triangle)
        FloatBuffer vertices = FloatBuffer.allocate(9);
        vertices.put(0.0f).put(0.0f).put(0.0f);  // Vertex 1
        vertices.put(1.0f).put(0.0f).put(0.0f);  // Vertex 2
        vertices.put(0.5f).put(1.0f).put(0.0f);  // Vertex 3
        vertices.flip();
        
        // Voxelize the mesh
        var voxelFuture = pipeline.voxelizeMesh(vertices);
        
        assertDoesNotThrow(() -> {
            voxelFuture.get(5, TimeUnit.SECONDS);
        }, "Voxelization should complete without errors");
    }
    
    @Test
    @Order(5)
    void testOctreeConstruction() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Build octree from voxel data
        var octreeFuture = pipeline.buildOctree();
        
        assertDoesNotThrow(() -> {
            octreeFuture.get(5, TimeUnit.SECONDS);
        }, "Octree construction should complete without errors");
    }
    
    @Test
    @Order(6)
    void testUniformUpdate() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Create uniform data
        ByteBuffer uniforms = ByteBuffer.allocateDirect(64);
        uniforms.putFloat(256.0f);  // resolution.x
        uniforms.putFloat(256.0f);  // resolution.y
        uniforms.putFloat(256.0f);  // resolution.z
        uniforms.putFloat(0.01f);   // voxel_size
        uniforms.flip();
        
        assertDoesNotThrow(() -> {
            pipeline.updateUniforms(uniforms);
        }, "Uniform update should not throw");
    }
    
    @Test
    @Order(7)
    void testRenderExecution() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Test that render can be called without errors
        assertDoesNotThrow(() -> {
            pipeline.render();
        }, "Render should execute without errors");
    }
    
    @Test
    @Order(8)
    void testResolutionChange() {
        assumeTrue(gpuAvailable, "GPU not available - skipping test");
        
        // Test changing voxel resolution
        assertDoesNotThrow(() -> {
            pipeline.setVoxelResolution(128);
        }, "Should be able to change resolution to 128");
        
        assertDoesNotThrow(() -> {
            pipeline.setVoxelResolution(512);
        }, "Should be able to change resolution to 512");
        
        // Test invalid resolution
        assertThrows(IllegalArgumentException.class, () -> {
            pipeline.setVoxelResolution(100); // Not power of 2
        }, "Should reject non-power-of-2 resolution");
    }
}
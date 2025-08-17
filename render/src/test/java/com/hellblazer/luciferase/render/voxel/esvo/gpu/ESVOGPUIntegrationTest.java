package com.hellblazer.luciferase.render.voxel.esvo.gpu;

import com.hellblazer.luciferase.render.lwjgl.LWJGLTestBase;
import com.hellblazer.luciferase.render.memory.GPUMemoryManager;
import com.hellblazer.luciferase.render.voxel.esvo.voxelization.*;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45.*;

/**
 * Integration tests for ESVO GPU pipeline.
 * These tests require an OpenGL context and GPU access.
 */
@DisplayName("ESVO GPU Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ESVOGPUIntegrationTest extends LWJGLTestBase {
    
    private static GPUMemoryManager memoryManager;
    private static ESVOGPUIntegration gpuIntegration;
    
    @BeforeAll
    void setupGPUResources() {
        // Base class handles GLFW initialization
        // Only initialize if OpenGL is available
        if (isOpenGLAvailable()) {
            // Create GPU resources
            memoryManager = new GPUMemoryManager(64 * 1024 * 1024, 8 * 1024 * 1024);
            gpuIntegration = new ESVOGPUIntegration(memoryManager);
            
            System.out.println("OpenGL Version: " + glGetString(GL_VERSION));
            System.out.println("GLSL Version: " + glGetString(GL_SHADING_LANGUAGE_VERSION));
        }
    }
    
    @BeforeEach
    void checkOpenGLAvailable() {
        Assumptions.assumeTrue(isOpenGLAvailable(), 
            "OpenGL not available - skipping test. On macOS, ensure -XstartOnFirstThread is set.");
    }
    
    @AfterAll
    void cleanupGPUResources() {
        if (gpuIntegration != null) {
            gpuIntegration.release();
            gpuIntegration = null;
        }
        memoryManager = null;
    }
    
    @Test
    @Order(1)
    @DisplayName("Should upload empty octree to GPU")
    void testEmptyOctreeUpload() {
        Octree emptyOctree = new Octree();
        
        boolean success = gpuIntegration.uploadOctree(emptyOctree);
        
        assertTrue(success, "Empty octree upload should succeed");
        assertEquals(0, gpuIntegration.getTotalNodesUploaded());
        assertEquals(0, gpuIntegration.getTotalPagesUploaded());
    }
    
    @Test
    @Order(2)
    @DisplayName("Should upload simple octree to GPU")
    void testSimpleOctreeUpload() {
        // Create a simple octree
        OctreeBuilder builder = new OctreeBuilder();
        List<Voxel> voxels = createTestVoxels(8);
        
        var config = new OctreeConfig()
            .withMaxDepth(3)
            .withMinVoxelsPerNode(2);
        
        Octree octree = builder.buildOctree(voxels, config);
        
        // Upload to GPU
        boolean success = gpuIntegration.uploadOctree(octree);
        
        assertTrue(success, "Octree upload should succeed");
        assertTrue(gpuIntegration.getTotalNodesUploaded() > 0, "Should upload nodes");
        assertTrue(gpuIntegration.isUploaded(), "Should be marked as uploaded");
        
        System.out.println("Uploaded " + gpuIntegration.getTotalNodesUploaded() + 
                          " nodes in " + gpuIntegration.getUploadTimeMs() + "ms");
    }
    
    @Test
    @Order(3)
    @DisplayName("Should upload large octree to GPU")
    void testLargeOctreeUpload() {
        // Create a larger octree
        OctreeBuilder builder = new OctreeBuilder();
        List<Voxel> voxels = createTestVoxels(64);
        
        var config = new OctreeConfig()
            .withMaxDepth(5)
            .withMinVoxelsPerNode(4);
        
        Octree octree = builder.buildOctree(voxels, config);
        
        // Upload to GPU
        long startTime = System.currentTimeMillis();
        boolean success = gpuIntegration.uploadOctree(octree);
        long uploadTime = System.currentTimeMillis() - startTime;
        
        assertTrue(success, "Large octree upload should succeed");
        assertEquals(octree.getNodeCount(), gpuIntegration.getTotalNodesUploaded(),
                    "Should upload all nodes");
        
        System.out.println("Large octree: " + octree.getNodeCount() + " nodes, " +
                          octree.getLeafCount() + " leaves");
        System.out.println("Upload time: " + uploadTime + "ms");
        
        // Performance check - should be reasonably fast
        assertTrue(uploadTime < 1000, "Upload should complete within 1 second");
    }
    
    @Test
    @Order(4)
    @DisplayName("Should bind buffers for rendering")
    void testBufferBinding() {
        // Create and upload octree
        OctreeBuilder builder = new OctreeBuilder();
        List<Voxel> voxels = createTestVoxels(8);
        Octree octree = builder.buildOctree(voxels, new OctreeConfig());
        
        gpuIntegration.uploadOctree(octree);
        
        // Test binding
        assertDoesNotThrow(() -> gpuIntegration.bindForRendering(),
                          "Buffer binding should not throw");
        
        // Can't verify actual binding since glGetIntegeri returns buffer ID, not binding status
        // Just verify that the operation completes without error
        assertTrue(gpuIntegration.isUploaded(), "Should still be uploaded after binding");
    }
    
    @Test
    @Order(5)
    @DisplayName("Should handle octree with pages")
    void testOctreeWithPages() {
        // Create octree with pages
        OctreeBuilder builder = new OctreeBuilder();
        List<Voxel> voxels = createTestVoxels(32);
        
        var config = new OctreeConfig()
            // .withPageSize(ESVOPage.PAGE_BYTES) // TODO: Uncomment when ESVOPage is implemented
            .withMaxDepth(4);
        
        Octree octree = builder.buildOctree(voxels, config);
        
        // TODO: Add test pages when ESVOPage is implemented
        // for (int i = 0; i < 3; i++) {
        //     octree.addPage(new ESVOPage());
        // }
        
        // Upload
        boolean success = gpuIntegration.uploadOctree(octree);
        
        assertTrue(success, "Upload with pages should succeed");
        // TODO: Check page upload when ESVOPage is implemented
        // assertEquals(3, gpuIntegration.getTotalPagesUploaded(), "Should upload pages");
    }
    
    @Test
    @Order(6)
    @DisplayName("Should measure upload performance")
    void testUploadPerformance() {
        // Test with various octree sizes
        int[] sizes = {8, 16, 32, 64, 128};
        
        System.out.println("\nUpload Performance Test:");
        System.out.println("Size\tNodes\tTime(ms)\tThroughput(nodes/ms)");
        
        for (int size : sizes) {
            // Create octree
            OctreeBuilder builder = new OctreeBuilder();
            List<Voxel> voxels = createTestVoxels(size);
            
            var config = new OctreeConfig()
                .withMaxDepth(6)
                .withMinVoxelsPerNode(2);
            
            Octree octree = builder.buildOctree(voxels, config);
            
            // Upload and measure
            long startTime = System.nanoTime();
            gpuIntegration.uploadOctree(octree);
            long endTime = System.nanoTime();
            
            long timeMs = (endTime - startTime) / 1_000_000;
            int nodes = gpuIntegration.getTotalNodesUploaded();
            double throughput = timeMs > 0 ? (double)nodes / timeMs : 0;
            
            System.out.printf("%d\t%d\t%d\t%.2f%n", size, nodes, timeMs, throughput);
        }
    }
    
    @Test
    @Order(7)
    @DisplayName("Should release GPU resources properly")
    void testResourceRelease() {
        // Upload octree
        OctreeBuilder builder = new OctreeBuilder();
        List<Voxel> voxels = createTestVoxels(16);
        Octree octree = builder.buildOctree(voxels, new OctreeConfig());
        
        gpuIntegration.uploadOctree(octree);
        assertTrue(gpuIntegration.isUploaded(), "Should be uploaded");
        
        // Release resources
        gpuIntegration.release();
        
        assertFalse(gpuIntegration.isUploaded(), "Should not be uploaded after release");
        assertEquals(0, gpuIntegration.getTotalNodesUploaded(), "Node count should be reset");
        assertEquals(0, gpuIntegration.getTotalPagesUploaded(), "Page count should be reset");
        
        // Should be able to upload again
        boolean success = gpuIntegration.uploadOctree(octree);
        assertTrue(success, "Should be able to upload after release");
    }
    
    @Test
    @Order(8)
    @DisplayName("Should handle voxelized mesh upload")
    void testVoxelizedMeshUpload() {
        // Create a simple triangle mesh
        float[][] vertices = {
            {0, 0, 0}, {1, 0, 0}, {0, 1, 0},
            {1, 1, 0}, {0, 0, 1}, {1, 0, 1}
        };
        
        int[][] triangles = {
            {0, 1, 2}, {1, 3, 2},
            {0, 4, 1}, {1, 4, 5}
        };
        
        TriangleMesh mesh = new TriangleMesh(vertices, triangles);
        
        // Voxelize
        TriangleVoxelizer voxelizer = new TriangleVoxelizer();
        var config = new VoxelizationConfig()
            .withResolution(16)
            .withBounds(-0.5f, -0.5f, -0.5f, 1.5f, 1.5f, 1.5f)
            .withGenerateOctree(true);
        
        VoxelizationResult result = voxelizer.voxelizeMesh(mesh, config);
        Octree octree = result.getOctree();
        
        // Upload to GPU
        boolean success = gpuIntegration.uploadOctree(octree);
        
        assertTrue(success, "Voxelized mesh upload should succeed");
        assertTrue(gpuIntegration.getTotalNodesUploaded() > 0, "Should have nodes");
        
        System.out.println("Voxelized mesh: " + result.getVoxelCount() + " voxels -> " +
                          octree.getNodeCount() + " octree nodes");
    }
    
    // Helper method to create test voxels
    private List<Voxel> createTestVoxels(int gridSize) {
        List<Voxel> voxels = new ArrayList<>();
        int step = Math.max(1, 64 / gridSize);
        
        for (int x = 0; x < gridSize; x += step) {
            for (int y = 0; y < gridSize; y += step) {
                for (int z = 0; z < gridSize; z += step) {
                    voxels.add(new Voxel(x, y, z));
                }
            }
        }
        
        return voxels;
    }
}
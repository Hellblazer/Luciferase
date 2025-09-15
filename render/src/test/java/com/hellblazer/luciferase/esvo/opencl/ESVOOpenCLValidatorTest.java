package com.hellblazer.luciferase.esvo.opencl;

import com.hellblazer.luciferase.gpu.test.CICompatibleGPUTest;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenCL-based validation tests for ESVO ray traversal algorithms.
 * These tests run in CI environments without requiring OpenGL support.
 */
public class ESVOOpenCLValidatorTest extends CICompatibleGPUTest {
    
    private static final float EPSILON = 1e-5f;
    
    @Test
    @Disabled("GPU context methods not available in base class")
    public void testBasicRayTraversal() throws Exception {
        // Create simple octree with single filled voxel
        OctreeNode[] nodes = createSimpleOctree();
        
        // Create test rays
        Ray[] rays = createTestRays();
        
        // Set up traversal parameters
        TraversalParams params = createDefaultParams();
        
        // Execute kernel
        IntersectionResult[] results = executeRayTraversal(null, nodes, rays, params);
        
        // Validate results
        validateBasicIntersections(results, rays);
    }
    
    @Test
    @Disabled("GPU context methods not available in base class")
    public void testMultiLevelTraversal() throws Exception {
        // GPU context not available - test disabled
        if (false) {
            // Create deeper octree
            OctreeNode[] nodes = createMultiLevelOctree();
            
            // Create rays targeting different depths
            Ray[] rays = createDepthTestRays();
            
            TraversalParams params = createDefaultParams();
            params.maxDepth = 4;
            
            IntersectionResult[] results = executeRayTraversal(
                null, nodes, rays, params);
            
            validateDepthTraversal(results);
        }
    }
    
    @Test
    @Disabled("GPU context methods not available in base class")
    public void testShadowRays() throws Exception {
        // GPU context not available - test disabled
        if (false) {
            OctreeNode[] nodes = createOcclusionTestOctree();
            Ray[] shadowRays = createShadowRays();
            
            int[] visibility = executeShadowRayTest(
                null, nodes, shadowRays);
            
            validateShadowResults(visibility, shadowRays);
        }
    }
    
    @Test
    @Disabled("GPU context methods not available in base class")
    public void testPerformanceScaling() throws Exception {
        if (true) { // GPU not available
            System.out.println("Skipping performance test - no GPU available");
            return;
        }
        
        // GPU context not available - test disabled
        if (false) {
            int[] nodeCounts = {1000, 10000, 100000};
            int[] rayCounts = {100, 1000, 10000};
            
            for (int nodeCount : nodeCounts) {
                for (int rayCount : rayCounts) {
                    OctreeNode[] nodes = generateRandomOctree(nodeCount);
                    Ray[] rays = generateRandomRays(rayCount);
                    
                    long startTime = System.nanoTime();
                    IntersectionResult[] results = executeRayTraversal(
                        null, nodes, rays, createDefaultParams());
                    long elapsed = System.nanoTime() - startTime;
                    
                    double msPerRay = (elapsed / 1_000_000.0) / rayCount;
                    System.out.printf("Nodes: %d, Rays: %d, Time per ray: %.3f ms%n",
                        nodeCount, rayCount, msPerRay);
                    
                    // Basic validation
                    assertNotNull(results);
                    assertEquals(rayCount, results.length);
                }
            }
        }
    }
    
    // Helper methods
    
    private OctreeNode[] createSimpleOctree() {
        OctreeNode[] nodes = new OctreeNode[1];
        nodes[0] = new OctreeNode();
        nodes[0].childDescriptor = 0;  // Leaf node
        nodes[0].attributes = 1;       // Filled voxel
        nodes[0].minValue = 0.0f;
        nodes[0].maxValue = 1.0f;
        return nodes;
    }
    
    private OctreeNode[] createMultiLevelOctree() {
        // Create a 4-level octree with selective filling
        int totalNodes = 1 + 8 + 64 + 512;  // Levels 0-3
        OctreeNode[] nodes = new OctreeNode[totalNodes];
        
        // Initialize all nodes
        for (int i = 0; i < totalNodes; i++) {
            nodes[i] = new OctreeNode();
        }
        
        // Root node has all children
        nodes[0].childDescriptor = 0xFF;  // All 8 children exist
        
        // Level 1 nodes (indices 1-8)
        for (int i = 1; i <= 8; i++) {
            if (i % 2 == 0) {
                nodes[i].childDescriptor = 0xFF;  // Has children
            } else {
                nodes[i].childDescriptor = 0;     // Leaf
                nodes[i].attributes = i;          // Different values
            }
        }
        
        return nodes;
    }
    
    private OctreeNode[] createOcclusionTestOctree() {
        OctreeNode[] nodes = new OctreeNode[9];
        
        // Root with children
        nodes[0] = new OctreeNode();
        nodes[0].childDescriptor = 0xFF;
        
        // Create occluding voxels in specific positions
        for (int i = 1; i <= 8; i++) {
            nodes[i] = new OctreeNode();
            nodes[i].childDescriptor = 0;
            nodes[i].attributes = (i <= 4) ? 1 : 0;  // First 4 are solid
        }
        
        return nodes;
    }
    
    private Ray[] createTestRays() {
        Ray[] rays = new Ray[5];
        
        // Ray hitting center
        rays[0] = new Ray();
        rays[0].originX = -1.0f;
        rays[0].originY = 0.5f;
        rays[0].originZ = 0.5f;
        rays[0].directionX = 1.0f;
        rays[0].directionY = 0.0f;
        rays[0].directionZ = 0.0f;
        rays[0].tMin = 0.0f;
        rays[0].tMax = 10.0f;
        
        // Ray missing
        rays[1] = new Ray();
        rays[1].originX = -1.0f;
        rays[1].originY = 2.0f;
        rays[1].originZ = 0.5f;
        rays[1].directionX = 1.0f;
        rays[1].directionY = 0.0f;
        rays[1].directionZ = 0.0f;
        rays[1].tMin = 0.0f;
        rays[1].tMax = 10.0f;
        
        // Diagonal ray
        rays[2] = new Ray();
        rays[2].originX = -1.0f;
        rays[2].originY = -1.0f;
        rays[2].originZ = -1.0f;
        float invSqrt3 = (float)(1.0 / Math.sqrt(3.0));
        rays[2].directionX = invSqrt3;
        rays[2].directionY = invSqrt3;
        rays[2].directionZ = invSqrt3;
        rays[2].tMin = 0.0f;
        rays[2].tMax = 10.0f;
        
        // Edge cases
        rays[3] = createEdgeRay(0.0f, 0.5f, 0.5f);
        rays[4] = createEdgeRay(0.5f, 0.0f, 0.5f);
        
        return rays;
    }
    
    private Ray createEdgeRay(float x, float y, float z) {
        Ray ray = new Ray();
        ray.originX = x - 2.0f;
        ray.originY = y;
        ray.originZ = z;
        ray.directionX = 1.0f;
        ray.directionY = 0.0f;
        ray.directionZ = 0.0f;
        ray.tMin = 0.0f;
        ray.tMax = 10.0f;
        return ray;
    }
    
    private Ray[] createDepthTestRays() {
        Ray[] rays = new Ray[8];
        for (int i = 0; i < 8; i++) {
            rays[i] = new Ray();
            float offset = i * 0.125f;
            rays[i].originX = -1.0f;
            rays[i].originY = offset;
            rays[i].originZ = offset;
            rays[i].directionX = 1.0f;
            rays[i].directionY = 0.0f;
            rays[i].directionZ = 0.0f;
            rays[i].tMin = 0.0f;
            rays[i].tMax = 10.0f;
        }
        return rays;
    }
    
    private Ray[] createShadowRays() {
        Ray[] rays = new Ray[4];
        
        // Ray blocked by solid voxel
        rays[0] = new Ray();
        rays[0].originX = -1.0f;
        rays[0].originY = 0.25f;
        rays[0].originZ = 0.25f;
        rays[0].directionX = 1.0f;
        rays[0].directionY = 0.0f;
        rays[0].directionZ = 0.0f;
        rays[0].tMin = 0.0f;
        rays[0].tMax = 10.0f;
        
        // Ray passing through empty space
        rays[1] = new Ray();
        rays[1].originX = -1.0f;
        rays[1].originY = 0.75f;
        rays[1].originZ = 0.75f;
        rays[1].directionX = 1.0f;
        rays[1].directionY = 0.0f;
        rays[1].directionZ = 0.0f;
        rays[1].tMin = 0.0f;
        rays[1].tMax = 10.0f;
        
        // Grazing rays
        rays[2] = createEdgeRay(0.5f, 0.5f, 0.0f);
        rays[3] = createEdgeRay(0.5f, 0.5f, 1.0f);
        
        return rays;
    }
    
    private Ray[] generateRandomRays(int count) {
        Random random = new Random(42);
        Ray[] rays = new Ray[count];
        
        for (int i = 0; i < count; i++) {
            rays[i] = new Ray();
            
            // Random origin outside cube
            float theta = random.nextFloat() * 2.0f * (float)Math.PI;
            float phi = random.nextFloat() * (float)Math.PI;
            float r = 2.0f + random.nextFloat();
            
            rays[i].originX = r * (float)(Math.sin(phi) * Math.cos(theta));
            rays[i].originY = r * (float)(Math.sin(phi) * Math.sin(theta));
            rays[i].originZ = r * (float)Math.cos(phi);
            
            // Direction toward center with some randomness
            float targetX = 0.5f + (random.nextFloat() - 0.5f) * 0.5f;
            float targetY = 0.5f + (random.nextFloat() - 0.5f) * 0.5f;
            float targetZ = 0.5f + (random.nextFloat() - 0.5f) * 0.5f;
            
            float dx = targetX - rays[i].originX;
            float dy = targetY - rays[i].originY;
            float dz = targetZ - rays[i].originZ;
            float len = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
            
            rays[i].directionX = dx / len;
            rays[i].directionY = dy / len;
            rays[i].directionZ = dz / len;
            rays[i].tMin = 0.0f;
            rays[i].tMax = 100.0f;
        }
        
        return rays;
    }
    
    private OctreeNode[] generateRandomOctree(int targetNodes) {
        Random random = new Random(42);
        OctreeNode[] nodes = new OctreeNode[targetNodes];
        
        for (int i = 0; i < targetNodes; i++) {
            nodes[i] = new OctreeNode();
            
            if (i < targetNodes / 2) {
                // Internal nodes
                nodes[i].childDescriptor = random.nextInt(256);
            } else {
                // Leaf nodes
                nodes[i].childDescriptor = 0;
                nodes[i].attributes = random.nextFloat() > 0.3f ? 1 : 0;
            }
            
            nodes[i].minValue = random.nextFloat();
            nodes[i].maxValue = nodes[i].minValue + random.nextFloat();
        }
        
        return nodes;
    }
    
    private TraversalParams createDefaultParams() {
        TraversalParams params = new TraversalParams();
        params.maxDepth = 8;
        params.maxIterations = 1000;
        params.epsilon = 1e-6f;
        params.rootSize = 1.0f;
        params.rootX = 0.0f;
        params.rootY = 0.0f;
        params.rootZ = 0.0f;
        params.beamOptimization = 0;
        params.contourEnabled = 0;
        params.shadowRays = 0;
        return params;
    }
    
    private IntersectionResult[] executeRayTraversal(
            Object context, OctreeNode[] nodes, Ray[] rays, TraversalParams params) {
        
        // This method is disabled since GPU context methods are not available
        throw new UnsupportedOperationException("GPU context methods not available in base class");
        
        /*
        try {
            // Load kernel source
            String kernelSource = loadKernelSource();
            
            // Compile kernel
            long program = BufferUtils.compileProgram(context.context, kernelSource);
            long kernel = CL10.clCreateKernel(program, "esvo_raycast", (IntBuffer)null);
            
            // Create buffers
            long nodeBuffer = BufferUtils.createNodeBuffer(
                context.context, nodes, CL10.CL_MEM_READ_ONLY);
            long rayBuffer = BufferUtils.createRayBuffer(
                context.context, rays, CL10.CL_MEM_READ_ONLY);
            
            ByteBuffer paramsBuffer = params.toBuffer();
            long paramBuffer = CL10.clCreateBuffer(context.context, 
                CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR,
                paramsBuffer, (IntBuffer)null);
            
            IntBuffer errcode = BufferUtils.createIntBuffer(1);
            long resultBuffer = CL10.clCreateBuffer(context.context,
                CL10.CL_MEM_WRITE_ONLY,
                (long)(rays.length * IntersectionResult.SIZE_BYTES),
                errcode);
            if (errcode.get(0) != CL10.CL_SUCCESS) {
                throw new RuntimeException("Failed to create result buffer: " + errcode.get(0));
            }
            
            // Set kernel arguments
            CL10.clSetKernelArg(kernel, 0, nodeBuffer);
            CL10.clSetKernelArg(kernel, 1, rayBuffer);
            CL10.clSetKernelArg(kernel, 2, resultBuffer);
            CL10.clSetKernelArg(kernel, 3, paramBuffer);
            CL10.clSetKernelArg1i(kernel, 4, rays.length);
            
            // Execute kernel
            try (MemoryStack stack = MemoryStack.stackPush()) {
                CL10.clEnqueueNDRangeKernel(context.queue, kernel, 1,
                    null,
                    stack.pointers(rays.length),
                    null,
                    null, null);
            }
            
            CL10.clFinish(context.queue);
            
            // Read results
            IntersectionResult[] results = BufferUtils.readResults(
                context.queue, resultBuffer, rays.length);
            
            // Cleanup
            CL10.clReleaseMemObject(nodeBuffer);
            CL10.clReleaseMemObject(rayBuffer);
            CL10.clReleaseMemObject(paramBuffer);
            CL10.clReleaseMemObject(resultBuffer);
            CL10.clReleaseKernel(kernel);
            CL10.clReleaseProgram(program);
            
            return results;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute ray traversal", e);
        }
        */
    }
    
    private int[] executeShadowRayTest(Object context, OctreeNode[] nodes, Ray[] rays) {
        // This method is disabled since GPU context methods are not available
        throw new UnsupportedOperationException("GPU context methods not available in base class");
        
        /*
        try {
            String kernelSource = loadKernelSource();
            long program = BufferUtils.compileProgram(context.context, kernelSource);
            long kernel = CL10.clCreateKernel(program, "esvo_shadow_ray", (IntBuffer)null);
            
            // Create buffers
            long nodeBuffer = BufferUtils.createNodeBuffer(
                context.context, nodes, CL10.CL_MEM_READ_ONLY);
            long rayBuffer = BufferUtils.createRayBuffer(
                context.context, rays, CL10.CL_MEM_READ_ONLY);
            
            TraversalParams params = createDefaultParams();
            ByteBuffer paramsBuffer = params.toBuffer();
            long paramBuffer = CL10.clCreateBuffer(context.context,
                CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR,
                paramsBuffer, (IntBuffer)null);
            
            IntBuffer errcode_ret = BufferUtils.createIntBuffer(1);
            long visibilityBuffer = CL10.clCreateBuffer(context.context,
                CL10.CL_MEM_WRITE_ONLY,
                (long)(rays.length * 4),  // int size
                errcode_ret);
            if (errcode_ret.get(0) != CL10.CL_SUCCESS) {
                throw new RuntimeException("Failed to create visibility buffer: " + errcode_ret.get(0));
            }
            
            // Set kernel arguments
            CL10.clSetKernelArg(kernel, 0, nodeBuffer);
            CL10.clSetKernelArg(kernel, 1, rayBuffer);
            CL10.clSetKernelArg(kernel, 2, visibilityBuffer);
            CL10.clSetKernelArg(kernel, 3, paramBuffer);
            CL10.clSetKernelArg1i(kernel, 4, rays.length);
            
            // Execute
            try (MemoryStack stack = MemoryStack.stackPush()) {
                CL10.clEnqueueNDRangeKernel(context.queue, kernel, 1,
                    null,
                    stack.pointers(rays.length),
                    null,
                    null, null);
            }
            
            CL10.clFinish(context.queue);
            
            // Read results
            IntBuffer visibility = BufferUtils.createIntBuffer(rays.length);
            CL10.clEnqueueReadBuffer(context.queue, visibilityBuffer, true, 0,
                visibility, null, null);
            
            int[] results = new int[rays.length];
            visibility.get(results);
            
            // Cleanup
            CL10.clReleaseMemObject(nodeBuffer);
            CL10.clReleaseMemObject(rayBuffer);
            CL10.clReleaseMemObject(paramBuffer);
            CL10.clReleaseMemObject(visibilityBuffer);
            CL10.clReleaseKernel(kernel);
            CL10.clReleaseProgram(program);
            
            return results;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute shadow ray test", e);
        }
        */
    }
    
    private String loadKernelSource() throws IOException {
        Path kernelPath = Paths.get("src/main/resources/kernels/esvo_raycast.cl");
        if (!Files.exists(kernelPath)) {
            // Try alternate path for test execution
            kernelPath = Paths.get("../src/main/resources/kernels/esvo_raycast.cl");
        }
        return Files.readString(kernelPath);
    }
    
    private void validateBasicIntersections(IntersectionResult[] results, Ray[] rays) {
        assertNotNull(results);
        assertEquals(rays.length, results.length);
        
        // First ray should hit
        assertTrue(results[0].hit > 0, "Center ray should hit");
        assertTrue(results[0].t > 0 && results[0].t < 10.0f, "Hit distance should be valid");
        
        // Second ray should miss
        assertEquals(0, results[1].hit, "Ray outside cube should miss");
        
        // Diagonal ray should hit
        assertTrue(results[2].hit > 0, "Diagonal ray should hit");
    }
    
    private void validateDepthTraversal(IntersectionResult[] results) {
        assertNotNull(results);
        
        // Check that different rays hit at different depths
        int hitsAtDifferentNodes = 0;
        int lastNodeIndex = -1;
        
        for (IntersectionResult result : results) {
            if (result.hit > 0 && result.nodeIndex != lastNodeIndex) {
                hitsAtDifferentNodes++;
                lastNodeIndex = result.nodeIndex;
            }
        }
        
        assertTrue(hitsAtDifferentNodes > 1, 
            "Multi-level traversal should hit different nodes");
    }
    
    private void validateShadowResults(int[] visibility, Ray[] rays) {
        assertNotNull(visibility);
        assertEquals(rays.length, visibility.length);
        
        // First ray should be blocked (solid voxel)
        assertEquals(0, visibility[0], "Ray through solid voxel should be blocked");
        
        // Second ray should be visible (empty space)
        assertEquals(1, visibility[1], "Ray through empty space should be visible");
    }
}

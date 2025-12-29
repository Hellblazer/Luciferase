package com.hellblazer.luciferase.esvo.validation;

import com.hellblazer.luciferase.esvo.cpu.ESVOCPUTraversal;
import com.hellblazer.luciferase.esvo.gpu.ESVOKernels;
import com.hellblazer.luciferase.gpu.test.CICompatibleGPUTest;
import com.hellblazer.luciferase.gpu.test.support.TestSupportMatrix;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;

import java.nio.ByteBuffer;
import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Validates that CPU and GPU ESVO implementations produce identical results.
 * This is critical for ensuring algorithm correctness.
 *
 * NOTE: Disabled pending gpu-framework integration. OpenCL is not functional
 * on Apple Silicon Macs (Apple deprecated/removed it). These tests will be
 * re-enabled when gpu-framework provides proper GPU backend abstraction.
 */
@Disabled("Pending gpu-framework integration - OpenCL not functional on Apple Silicon")
public class ESVOAlgorithmValidationTest extends CICompatibleGPUTest {
    private static final Logger log = LoggerFactory.getLogger(ESVOAlgorithmValidationTest.class);
    private static final float EPSILON = 1e-4f; // Tolerance for floating point comparison
    
    private TestSupportMatrix supportMatrix;
    private boolean gpuAvailable;
    
    @BeforeEach
    void setup() {
        supportMatrix = new TestSupportMatrix();
        gpuAvailable = supportMatrix.getBackendSupport(TestSupportMatrix.Backend.OPENCL) 
            != TestSupportMatrix.SupportLevel.NOT_AVAILABLE;
    }
    
    @Test
    @DisplayName("Validate CPU vs GPU ray traversal results match")
    void testCPUvsGPUTraversal() {
        assumeTrue(gpuAvailable, "GPU not available for cross-validation");
        
        // Create test scene
        int octreeDepth = 4;
        OctreeNode[] octree = createTestOctree(octreeDepth);
        Ray[] rays = createTestRays(100);
        float[] sceneMin = {0, 0, 0};
        float[] sceneMax = {1, 1, 1};
        
        // CPU traversal
        log.info("Running CPU traversal for {} rays", rays.length);
        long cpuStart = System.nanoTime();
        ESVOCPUTraversal.IntersectionResult[] cpuResults = ESVOCPUTraversal.traverseRays(
            convertToCPURays(rays), convertToCPUOctree(octree), sceneMin, sceneMax, octreeDepth
        );
        long cpuTime = System.nanoTime() - cpuStart;
        
        // GPU traversal
        log.info("Running GPU traversal for {} rays", rays.length);
        IntersectionResult[] gpuResults = null;
        long gpuTime = 0;
        
        try {
            long gpuStart = System.nanoTime();
            gpuResults = executeGPUTraversal(rays, octree, sceneMin, sceneMax, octreeDepth);
            gpuTime = System.nanoTime() - gpuStart;
        } catch (Exception e) {
            log.error("GPU traversal failed: {}", e.getMessage());
            fail("GPU traversal failed: " + e.getMessage());
        }
        
        // Validate results match
        assertNotNull(gpuResults);
        assertEquals(cpuResults.length, gpuResults.length);
        
        int matches = 0;
        int mismatches = 0;
        
        for (int i = 0; i < rays.length; i++) {
            ESVOCPUTraversal.IntersectionResult cpu = cpuResults[i];
            IntersectionResult gpu = gpuResults[i];
            
            // Check hit/miss agreement
            if (cpu.hit != gpu.hit) {
                mismatches++;
                log.warn("Ray {} hit mismatch: CPU={}, GPU={}", i, cpu.hit, gpu.hit);
                continue;
            }
            
            // If both hit, check distance and voxel
            if (cpu.hit == 1) {
                float distError = Math.abs(cpu.t - gpu.t);
                if (distError > EPSILON) {
                    mismatches++;
                    log.warn("Ray {} distance mismatch: CPU={}, GPU={}, error={}", 
                        i, cpu.t, gpu.t, distError);
                } else if (cpu.voxelValue != gpu.voxelValue) {
                    mismatches++;
                    log.warn("Ray {} voxel mismatch: CPU={}, GPU={}", 
                        i, cpu.voxelValue, gpu.voxelValue);
                } else {
                    matches++;
                }
            } else {
                matches++;
            }
        }
        
        log.info("Validation results: {} matches, {} mismatches", matches, mismatches);
        log.info("CPU time: {}ms, GPU time: {}ms, Speedup: {:.2f}x", 
            cpuTime / 1_000_000, gpuTime / 1_000_000, 
            (double)cpuTime / gpuTime);
        
        // Assert high match rate
        double matchRate = (double)matches / rays.length;
        assertTrue(matchRate > 0.95, 
            String.format("Match rate too low: %.2f%% (expected > 95%%)", matchRate * 100));
    }
    
    @Test
    @DisplayName("Validate edge cases produce identical results")
    void testEdgeCases() {
        assumeTrue(gpuAvailable, "GPU not available for cross-validation");
        
        // Test edge cases
        OctreeNode[] octree = createSimpleOctree();
        float[] sceneMin = {0, 0, 0};
        float[] sceneMax = {1, 1, 1};
        
        // Edge case rays
        Ray[] edgeRays = new Ray[] {
            createRay(0.5f, 0.5f, -1.0f, 0, 0, 1),    // Straight through center
            createRay(0, 0, 0, 1, 1, 1),              // From corner
            createRay(0.5f, 0.5f, 0.5f, 1, 0, 0),     // From center outward
            createRay(-1, 0.5f, 0.5f, 1, 0, 0),       // From outside
            createRay(0.999f, 0.999f, 0.999f, -1, -1, -1) // Near boundary
        };
        
        // Compare results
        ESVOCPUTraversal.IntersectionResult[] cpuResults = ESVOCPUTraversal.traverseRays(
            convertToCPURays(edgeRays), convertToCPUOctree(octree), sceneMin, sceneMax, 8
        );
        
        IntersectionResult[] gpuResults = executeGPUTraversal(
            edgeRays, octree, sceneMin, sceneMax, 8
        );
        
        for (int i = 0; i < edgeRays.length; i++) {
            assertEquals(cpuResults[i].hit, gpuResults[i].hit,
                "Edge case " + i + " hit mismatch");
            
            if (cpuResults[i].hit == 1) {
                assertEquals(cpuResults[i].t, gpuResults[i].t, EPSILON,
                    "Edge case " + i + " distance mismatch");
            }
        }
    }
    
    @Test
    @DisplayName("Validate performance scales appropriately")
    void testPerformanceScaling() {
        assumeTrue(gpuAvailable, "GPU not available for performance testing");
        
        int[] sizes = {10, 100, 1000, 10000};
        OctreeNode[] octree = createTestOctree(6);
        float[] sceneMin = {0, 0, 0};
        float[] sceneMax = {1, 1, 1};
        
        log.info("Performance scaling test:");
        log.info("Rays\tCPU(ms)\tGPU(ms)\tSpeedup");
        
        for (int size : sizes) {
            Ray[] rays = createTestRays(size);
            
            // CPU timing
            long cpuStart = System.nanoTime();
            ESVOCPUTraversal.traverseRays(convertToCPURays(rays), convertToCPUOctree(octree), sceneMin, sceneMax, 6);
            long cpuTime = System.nanoTime() - cpuStart;
            
            // GPU timing
            long gpuStart = System.nanoTime();
            executeGPUTraversal(rays, octree, sceneMin, sceneMax, 6);
            long gpuTime = System.nanoTime() - gpuStart;
            
            double speedup = (double)cpuTime / gpuTime;
            log.info("{}\t{:.2f}\t{:.2f}\t{:.2f}x", 
                size, 
                cpuTime / 1_000_000.0,
                gpuTime / 1_000_000.0,
                speedup);
            
            // GPU should show better scaling with larger workloads
            if (size >= 1000) {
                assertTrue(speedup > 1.0, 
                    "GPU should be faster for " + size + " rays");
            }
        }
    }
    
    private IntersectionResult[] executeGPUTraversal(Ray[] rays, OctreeNode[] octree,
                                                     float[] sceneMin, float[] sceneMax,
                                                     int maxDepth) {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);
            
            // Initialize OpenCL
            var platforms = stack.mallocPointer(1);
            CL10.clGetPlatformIDs(platforms, (IntBuffer)null);
            long platform = platforms.get(0);
            
            var devices = stack.mallocPointer(1);
            CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, devices, (IntBuffer)null);
            long device = devices.get(0);
            
            var contextProps = stack.mallocPointer(3);
            contextProps.put(CL10.CL_CONTEXT_PLATFORM).put(platform).put(0);
            contextProps.flip();
            
            long context = CL10.clCreateContext(contextProps, device, null, 0, errcode);
            checkError(errcode.get(0), "clCreateContext");
            
            long queue = CL10.clCreateCommandQueue(context, device, 0, errcode);
            checkError(errcode.get(0), "clCreateCommandQueue");
            
            // Create buffers
            var rayData = stack.malloc(rays.length * 32); // 8 floats per ray
            for (Ray ray : rays) {
                rayData.putFloat(ray.originX).putFloat(ray.originY).putFloat(ray.originZ);
                rayData.putFloat(ray.directionX).putFloat(ray.directionY).putFloat(ray.directionZ);
                rayData.putFloat(ray.tMin).putFloat(ray.tMax);
            }
            rayData.flip();
            
            long rayBuffer = CL10.clCreateBuffer(context,
                CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR,
                rayData, errcode);
            checkError(errcode.get(0), "clCreateBuffer rays");
            
            var octreeData = stack.malloc(octree.length * 32); // 32 bytes per node (8 ints)
            for (OctreeNode node : octree) {
                octreeData.putInt(node.childDescriptor);
                octreeData.putInt(node.contourPointer);
                octreeData.putFloat(node.minValue);
                octreeData.putFloat(node.maxValue);
                octreeData.putInt(node.attributes);
                octreeData.putInt(node.padding1);
                octreeData.putInt(node.padding2);
                octreeData.putInt(node.padding3);
            }
            octreeData.flip();
            
            long octreeBuffer = CL10.clCreateBuffer(context,
                CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR,
                octreeData, errcode);
            checkError(errcode.get(0), "clCreateBuffer octree");
            
            long resultBuffer = CL10.clCreateBuffer(context,
                CL10.CL_MEM_WRITE_ONLY,
                rays.length * 16L, errcode);
            checkError(errcode.get(0), "clCreateBuffer results");
            
            long voxelBuffer = CL10.clCreateBuffer(context,
                CL10.CL_MEM_WRITE_ONLY,
                rays.length * 4L, errcode);
            checkError(errcode.get(0), "clCreateBuffer voxels");
            
            // Compile kernel
            String kernelSource = ESVOKernels.getOpenCLKernel();
            long program = CL10.clCreateProgramWithSource(context, kernelSource, errcode);
            checkError(errcode.get(0), "clCreateProgramWithSource");
            
            int buildStatus = CL10.clBuildProgram(program, device, "", null, 0);
            if (buildStatus != CL10.CL_SUCCESS) {
                var logSize = stack.mallocPointer(1);
                CL10.clGetProgramBuildInfo(program, device, CL10.CL_PROGRAM_BUILD_LOG, (ByteBuffer)null, logSize);
                var buildLog = stack.malloc((int)logSize.get(0));
                CL10.clGetProgramBuildInfo(program, device, CL10.CL_PROGRAM_BUILD_LOG, buildLog, null);
                log.error("Kernel compilation failed: {}", org.lwjgl.system.MemoryUtil.memUTF8(buildLog));
                throw new RuntimeException("Kernel compilation failed");
            }
            
            long kernel = CL10.clCreateKernel(program, "traverseOctree", errcode);
            checkError(errcode.get(0), "clCreateKernel");
            
            // Set arguments
            CL10.clSetKernelArg(kernel, 0, rayBuffer);
            CL10.clSetKernelArg(kernel, 1, octreeBuffer);
            CL10.clSetKernelArg(kernel, 2, resultBuffer);
            CL10.clSetKernelArg(kernel, 3, voxelBuffer);
            CL10.clSetKernelArg(kernel, 4, stack.ints(maxDepth));
            CL10.clSetKernelArg(kernel, 5, stack.floats(sceneMin[0], sceneMin[1], sceneMin[2]));
            CL10.clSetKernelArg(kernel, 6, stack.floats(sceneMax[0], sceneMax[1], sceneMax[2]));
            
            // Execute
            CL10.clEnqueueNDRangeKernel(queue, kernel, 1, null,
                stack.pointers(rays.length), null, null, null);
            CL10.clFinish(queue);
            
            // Read results
            FloatBuffer resultData = stack.mallocFloat(rays.length * 4);
            IntBuffer voxelData = stack.mallocInt(rays.length);
            
            CL10.clEnqueueReadBuffer(queue, resultBuffer, true, 0, resultData, null, null);
            CL10.clEnqueueReadBuffer(queue, voxelBuffer, true, 0, voxelData, null, null);
            
            // Convert to IntersectionResult array
            IntersectionResult[] results = new IntersectionResult[rays.length];
            for (int i = 0; i < rays.length; i++) {
                results[i] = new IntersectionResult();
                float distance = resultData.get(i * 4 + 3);
                results[i].hit = distance >= 0 ? 1 : 0;
                results[i].t = distance >= 0 ? distance : Float.MAX_VALUE;
                results[i].voxelValue = voxelData.get(i);
            }
            
            // Cleanup
            CL10.clReleaseKernel(kernel);
            CL10.clReleaseProgram(program);
            CL10.clReleaseMemObject(rayBuffer);
            CL10.clReleaseMemObject(octreeBuffer);
            CL10.clReleaseMemObject(resultBuffer);
            CL10.clReleaseMemObject(voxelBuffer);
            CL10.clReleaseCommandQueue(queue);
            CL10.clReleaseContext(context);
            
            return results;
            
        } catch (Exception e) {
            log.error("GPU traversal failed: {}", e.getMessage());
            throw new RuntimeException("GPU traversal failed", e);
        }
    }
    
    private void checkError(int error, String operation) {
        if (error != CL10.CL_SUCCESS) {
            throw new RuntimeException(operation + " failed with error: " + error);
        }
    }
    
    private OctreeNode[] createTestOctree(int depth) {
        // Create a simple test octree with some filled voxels
        int nodeCount = Math.min(1000, (int)((Math.pow(8, depth + 1) - 1) / 7));
        OctreeNode[] nodes = new OctreeNode[nodeCount];
        Random random = new Random(42);
        
        // Root node
        nodes[0] = new OctreeNode();
        nodes[0].childDescriptor = 0xFF | (1 << 8); // All children, childPtr = 1
        nodes[0].minValue = 0;
        nodes[0].maxValue = 1;
        
        int currentIndex = 1;
        for (int level = 0; level < depth && currentIndex + 8 < nodes.length; level++) {
            int nodesAtLevel = (int)Math.pow(8, level);
            
            for (int i = 0; i < nodesAtLevel && currentIndex + 8 < nodes.length; i++) {
                for (int child = 0; child < 8 && currentIndex < nodes.length; child++) {
                    nodes[currentIndex] = new OctreeNode();
                    
                    boolean isLeaf = level == depth - 1 || random.nextFloat() > 0.7;
                    if (isLeaf) {
                        nodes[currentIndex].childDescriptor = 0; // No children
                        nodes[currentIndex].attributes = random.nextFloat() > 0.5 ? 
                            random.nextInt(255) + 1 : 0;
                    } else {
                        nodes[currentIndex].childDescriptor = 0xFF | ((currentIndex + 1) << 8);
                    }
                    
                    currentIndex++;
                }
            }
        }
        
        return nodes;
    }
    
    private OctreeNode[] createSimpleOctree() {
        // Single filled voxel at root
        OctreeNode[] nodes = new OctreeNode[1];
        nodes[0] = new OctreeNode();
        nodes[0].childDescriptor = 0; // Leaf
        nodes[0].attributes = 1; // Filled
        return nodes;
    }
    
    private Ray[] createTestRays(int count) {
        Ray[] rays = new Ray[count];
        Random random = new Random(42);
        
        for (int i = 0; i < count; i++) {
            rays[i] = new Ray();
            
            // Random origin outside or inside scene
            rays[i].originX = random.nextFloat() * 2 - 0.5f;
            rays[i].originY = random.nextFloat() * 2 - 0.5f;
            rays[i].originZ = random.nextFloat() * 2 - 0.5f;
            
            // Random direction
            rays[i].directionX = random.nextFloat() * 2 - 1;
            rays[i].directionY = random.nextFloat() * 2 - 1;
            rays[i].directionZ = random.nextFloat() * 2 - 1;
            
            // Normalize
            float len = (float)Math.sqrt(
                rays[i].directionX * rays[i].directionX +
                rays[i].directionY * rays[i].directionY +
                rays[i].directionZ * rays[i].directionZ
            );
            rays[i].directionX /= len;
            rays[i].directionY /= len;
            rays[i].directionZ /= len;
            
            rays[i].tMin = 0;
            rays[i].tMax = 10;
        }
        
        return rays;
    }
    
    private Ray createRay(float ox, float oy, float oz, float dx, float dy, float dz) {
        Ray ray = new Ray();
        ray.originX = ox;
        ray.originY = oy;
        ray.originZ = oz;
        
        float len = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
        ray.directionX = dx / len;
        ray.directionY = dy / len;
        ray.directionZ = dz / len;
        
        ray.tMin = 0;
        ray.tMax = 10;
        
        return ray;
    }
    
    // Type conversion methods for ESVODataStructures -> ESVOCPUTraversal
    private ESVOCPUTraversal.Ray[] convertToCPURays(Ray[] esvoRays) {
        ESVOCPUTraversal.Ray[] cpuRays = new ESVOCPUTraversal.Ray[esvoRays.length];
        for (int i = 0; i < esvoRays.length; i++) {
            cpuRays[i] = new ESVOCPUTraversal.Ray(
                esvoRays[i].originX, esvoRays[i].originY, esvoRays[i].originZ,
                esvoRays[i].directionX, esvoRays[i].directionY, esvoRays[i].directionZ,
                esvoRays[i].tMin, esvoRays[i].tMax
            );
        }
        return cpuRays;
    }
    
    private ESVOCPUTraversal.OctreeNode[] convertToCPUOctree(OctreeNode[] esvoNodes) {
        // Count non-null nodes
        int validCount = 0;
        for (OctreeNode node : esvoNodes) {
            if (node != null) validCount++;
        }

        ESVOCPUTraversal.OctreeNode[] cpuNodes = new ESVOCPUTraversal.OctreeNode[validCount];
        int idx = 0;
        for (OctreeNode esvoNode : esvoNodes) {
            if (esvoNode != null) {
                cpuNodes[idx++] = new ESVOCPUTraversal.OctreeNode(
                    esvoNode.childDescriptor,
                    esvoNode.attributes
                );
            }
        }
        return cpuNodes;
    }
}

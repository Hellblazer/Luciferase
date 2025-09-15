package com.hellblazer.luciferase.esvo.performance;

import com.hellblazer.luciferase.esvo.gpu.ESVOKernels;
import com.hellblazer.luciferase.gpu.test.support.TestSupportMatrix;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures.*;
import org.junit.jupiter.api.*;
import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Performance benchmarks comparing CPU vs GPU implementations of ESVO algorithms.
 * 
 * Tests various workload sizes to identify the crossover point where GPU becomes beneficial.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ESVOPerformanceBenchmark {
    private static final Logger log = LoggerFactory.getLogger(ESVOPerformanceBenchmark.class);
    
    // Benchmark parameters
    private static final int[] RAY_COUNTS = {100, 1_000, 10_000, 100_000, 1_000_000};
    private static final int[] OCTREE_DEPTHS = {4, 6, 8, 10};
    private static final int WARMUP_ITERATIONS = 5;
    private static final int BENCHMARK_ITERATIONS = 20;
    
    private TestSupportMatrix supportMatrix;
    private long clContext;
    private long clQueue;
    private long clProgram;
    private long clKernel;
    private boolean gpuAvailable;
    
    @BeforeAll
    void setup() {
        supportMatrix = new TestSupportMatrix();
        gpuAvailable = initializeGPU();
        
        if (!gpuAvailable) {
            log.warn("GPU not available, will only run CPU benchmarks");
        }
    }
    
    @AfterAll
    void cleanup() {
        if (gpuAvailable && clKernel != 0) {
            CL10.clReleaseKernel(clKernel);
            CL10.clReleaseProgram(clProgram);
            CL10.clReleaseCommandQueue(clQueue);
            CL10.clReleaseContext(clContext);
        }
    }
    
    private boolean initializeGPU() {
        try {
            // Check if OpenCL is available
            if (supportMatrix.getBackendSupport(TestSupportMatrix.Backend.OPENCL) 
                == TestSupportMatrix.SupportLevel.NOT_AVAILABLE) {
                return false;
            }
            
            // Initialize OpenCL context
            try (var stack = MemoryStack.stackPush()) {
                IntBuffer errcode = stack.mallocInt(1);
                
                // Get platform and device
                var platforms = stack.mallocPointer(1);
                CL10.clGetPlatformIDs(platforms, (IntBuffer)null);
                long platform = platforms.get(0);
                
                var devices = stack.mallocPointer(1);
                CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, devices, (IntBuffer)null);
                long device = devices.get(0);
                
                // Create context and queue
                var contextProps = stack.mallocPointer(3);
                contextProps.put(CL10.CL_CONTEXT_PLATFORM).put(platform).put(0);
                contextProps.flip();
                
                clContext = CL10.clCreateContext(
                    contextProps, device, null, 0, errcode
                );
                checkError(errcode.get(0), "clCreateContext");
                
                clQueue = CL10.clCreateCommandQueue(
                    clContext, device, 0, errcode
                );
                checkError(errcode.get(0), "clCreateCommandQueue");
                
                // Compile kernel
                String kernelSource = ESVOKernels.getOpenCLKernel();
                clProgram = ESVODataStructures.BufferUtils.compileProgram(clContext, kernelSource);
                
                clKernel = CL10.clCreateKernel(clProgram, "traverseOctree", errcode);
                checkError(errcode.get(0), "clCreateKernel");
                
                return true;
            }
        } catch (Exception e) {
            log.warn("GPU initialization failed: {}", e.getMessage());
            return false;
        }
    }
    
    private void checkError(int error, String operation) {
        if (error != CL10.CL_SUCCESS) {
            throw new RuntimeException(operation + " failed with error: " + error);
        }
    }
    
    @Test
    @DisplayName("Benchmark ray traversal performance")
    void benchmarkRayTraversal() {
        log.info("Starting ESVO performance benchmarks");
        log.info("Ray counts: {}", RAY_COUNTS);
        log.info("Octree depths: {}", OCTREE_DEPTHS);
        
        List<BenchmarkResult> results = new ArrayList<>();
        
        for (int depth : OCTREE_DEPTHS) {
            OctreeNode[] octree = generateOctree(depth);
            log.info("Generated octree with depth {} ({} nodes)", depth, octree.length);
            
            for (int rayCount : RAY_COUNTS) {
                Ray[] rays = generateRays(rayCount);
                
                // CPU benchmark
                long cpuTime = benchmarkCPU(rays, octree);
                
                // GPU benchmark (if available)
                long gpuTime = gpuAvailable ? benchmarkGPU(rays, octree) : -1;
                
                var result = new BenchmarkResult(
                    rayCount, depth, octree.length, cpuTime, gpuTime
                );
                results.add(result);
                
                log.info("Rays: {}, Depth: {}, CPU: {}ms, GPU: {}ms, Speedup: {}x",
                    rayCount, depth, 
                    TimeUnit.NANOSECONDS.toMillis(cpuTime),
                    gpuTime > 0 ? TimeUnit.NANOSECONDS.toMillis(gpuTime) : "N/A",
                    result.getSpeedup()
                );
            }
        }
        
        // Print summary
        printBenchmarkSummary(results);
    }
    
    private long benchmarkCPU(Ray[] rays, OctreeNode[] octree) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            traverseCPU(rays, octree);
        }
        
        // Benchmark
        long totalTime = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long start = System.nanoTime();
            var results = traverseCPU(rays, octree);
            long end = System.nanoTime();
            totalTime += (end - start);
            
            // Verify some results to prevent optimization
            assertTrue(results.length == rays.length);
        }
        
        return totalTime / BENCHMARK_ITERATIONS;
    }
    
    private long benchmarkGPU(Ray[] rays, OctreeNode[] octree) {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);
            
            // Allocate buffers
            long rayBuffer = ESVODataStructures.BufferUtils.createRayBuffer(
                clContext, rays, CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR
            );
            
            long octreeBuffer = ESVODataStructures.BufferUtils.createNodeBuffer(
                clContext, octree, CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR
            );
            
            long resultBuffer = CL10.clCreateBuffer(
                clContext,
                CL10.CL_MEM_WRITE_ONLY,
                rays.length * 16L, // float4 per ray
                errcode
            );
            checkError(errcode.get(0), "clCreateBuffer results");
            
            long voxelBuffer = CL10.clCreateBuffer(
                clContext,
                CL10.CL_MEM_WRITE_ONLY,
                rays.length * 4L, // uint per ray
                errcode
            );
            checkError(errcode.get(0), "clCreateBuffer voxels");
            
            // Set kernel arguments
            CL10.clSetKernelArg(clKernel, 0, rayBuffer);
            CL10.clSetKernelArg(clKernel, 1, octreeBuffer);
            CL10.clSetKernelArg(clKernel, 2, resultBuffer);
            CL10.clSetKernelArg(clKernel, 3, voxelBuffer);
            CL10.clSetKernelArg(clKernel, 4, stack.ints(10)); // max depth
            CL10.clSetKernelArg(clKernel, 5, stack.floats(0, 0, 0)); // scene min
            CL10.clSetKernelArg(clKernel, 6, stack.floats(1, 1, 1)); // scene max
            
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                CL10.clEnqueueNDRangeKernel(
                    clQueue, clKernel, 1, null,
                    stack.pointers(rays.length), null,
                    null, null
                );
                CL10.clFinish(clQueue);
            }
            
            // Benchmark
            long totalTime = 0;
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                long start = System.nanoTime();
                
                CL10.clEnqueueNDRangeKernel(
                    clQueue, clKernel, 1, null,
                    stack.pointers(rays.length), null,
                    null, null
                );
                CL10.clFinish(clQueue);
                
                long end = System.nanoTime();
                totalTime += (end - start);
            }
            
            // Cleanup
            CL10.clReleaseMemObject(rayBuffer);
            CL10.clReleaseMemObject(octreeBuffer);
            CL10.clReleaseMemObject(resultBuffer);
            CL10.clReleaseMemObject(voxelBuffer);
            
            return totalTime / BENCHMARK_ITERATIONS;
            
        } catch (Exception e) {
            log.error("GPU benchmark failed: {}", e.getMessage());
            return -1;
        }
    }
    
    private IntersectionResult[] traverseCPU(Ray[] rays, OctreeNode[] octree) {
        var results = new IntersectionResult[rays.length];
        
        for (int i = 0; i < rays.length; i++) {
            results[i] = traverseSingleRayCPU(rays[i], octree);
        }
        
        return results;
    }
    
    private IntersectionResult traverseSingleRayCPU(Ray ray, OctreeNode[] octree) {
        // Simplified CPU ray traversal implementation
        var result = new IntersectionResult();
        result.hit = 0;
        result.t = Float.MAX_VALUE;
        
        // Basic AABB intersection test with root
        if (!intersectAABB(ray, 0, 0, 0, 1, 1, 1)) {
            return result;
        }
        
        // Traverse octree (simplified)
        int currentNode = 0;
        int maxDepth = 10;
        
        for (int depth = 0; depth < maxDepth; depth++) {
            if (currentNode >= octree.length) break;
            
            var node = octree[currentNode];
            boolean isLeaf = (node.childDescriptor & 0x80000000) != 0;
            
            if (isLeaf) {
                if (node.attributes != 0) {
                    // Found hit
                    result.hit = 1;
                    result.t = ray.tMin;
                    result.normalX = 0;
                    result.normalY = 1;
                    result.normalZ = 0;
                    result.voxelValue = node.attributes;
                    result.nodeIndex = currentNode;
                    result.iterations = depth;
                    return result;
                }
                break;
            }
            
            // Find next child to traverse (simplified)
            currentNode = node.childDescriptor & 0x7FFFFFFF;
        }
        
        return result;
    }
    
    private boolean intersectAABB(Ray ray, float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ) {
        float tmin = (minX - ray.originX) / ray.directionX;
        float tmax = (maxX - ray.originX) / ray.directionX;
        
        if (tmin > tmax) {
            float temp = tmin;
            tmin = tmax;
            tmax = temp;
        }
        
        float tymin = (minY - ray.originY) / ray.directionY;
        float tymax = (maxY - ray.originY) / ray.directionY;
        
        if (tymin > tymax) {
            float temp = tymin;
            tymin = tymax;
            tymax = temp;
        }
        
        if ((tmin > tymax) || (tymin > tmax)) return false;
        
        if (tymin > tmin) tmin = tymin;
        if (tymax < tmax) tmax = tymax;
        
        float tzmin = (minZ - ray.originZ) / ray.directionZ;
        float tzmax = (maxZ - ray.originZ) / ray.directionZ;
        
        if (tzmin > tzmax) {
            float temp = tzmin;
            tzmin = tzmax;
            tzmax = temp;
        }
        
        return !((tmin > tzmax) || (tzmin > tmax));
    }
    
    private Ray[] generateRays(int count) {
        var rays = new Ray[count];
        var random = new Random(42); // Fixed seed for reproducibility
        
        for (int i = 0; i < count; i++) {
            rays[i] = new Ray();
            rays[i].originX = random.nextFloat() * 2 - 1;
            rays[i].originY = random.nextFloat() * 2 - 1;
            rays[i].originZ = random.nextFloat() * 2 - 1;
            
            rays[i].directionX = random.nextFloat() * 2 - 1;
            rays[i].directionY = random.nextFloat() * 2 - 1;
            rays[i].directionZ = random.nextFloat() * 2 - 1;
            
            // Normalize direction
            float len = (float)Math.sqrt(
                rays[i].directionX * rays[i].directionX +
                rays[i].directionY * rays[i].directionY +
                rays[i].directionZ * rays[i].directionZ
            );
            rays[i].directionX /= len;
            rays[i].directionY /= len;
            rays[i].directionZ /= len;
            
            rays[i].tMin = 0;
            rays[i].tMax = Float.MAX_VALUE;
        }
        
        return rays;
    }
    
    private OctreeNode[] generateOctree(int maxDepth) {
        // Generate a balanced octree with some filled voxels
        int nodeCount = (int)((Math.pow(8, maxDepth + 1) - 1) / 7);
        nodeCount = Math.min(nodeCount, 100_000); // Cap for memory
        
        var nodes = new OctreeNode[nodeCount];
        var random = new Random(42);
        
        // Root node
        nodes[0] = new OctreeNode();
        nodes[0].childDescriptor = 1; // Points to first child
        nodes[0].minValue = 0;
        nodes[0].maxValue = 1;
        
        int currentIndex = 1;
        for (int i = 0; i < Math.min(nodeCount / 8, nodes.length); i++) {
            if (nodes[i] != null && (nodes[i].childDescriptor & 0x80000000) == 0 && currentIndex + 8 < nodes.length) {
                nodes[i].childDescriptor = currentIndex;
                
                // Create 8 children
                for (int j = 0; j < 8; j++) {
                    boolean isLeaf = random.nextFloat() > 0.7 || currentIndex + 8 >= nodes.length;
                    nodes[currentIndex] = new OctreeNode();
                    
                    if (isLeaf) {
                        nodes[currentIndex].childDescriptor = 0x80000000; // Mark as leaf
                        nodes[currentIndex].attributes = random.nextFloat() > 0.5 ? random.nextInt(255) + 1 : 0;
                    } else {
                        nodes[currentIndex].childDescriptor = currentIndex + 8;
                    }
                    
                    nodes[currentIndex].minValue = 0;
                    nodes[currentIndex].maxValue = 1;
                    currentIndex++;
                }
            }
        }
        
        return nodes;
    }
    
    private void printBenchmarkSummary(List<BenchmarkResult> results) {
        log.info("\n========== PERFORMANCE BENCHMARK SUMMARY ==========");
        log.info("Platform: {}", supportMatrix.getCurrentPlatform());
        log.info("GPU Available: {}", gpuAvailable);
        
        if (gpuAvailable) {
            // Find crossover point where GPU becomes beneficial
            int crossoverRays = -1;
            for (var result : results) {
                if (result.gpuTime > 0 && result.getSpeedup() > 1.0) {
                    crossoverRays = result.rayCount;
                    break;
                }
            }
            
            if (crossoverRays > 0) {
                log.info("GPU becomes beneficial at {} rays", crossoverRays);
            } else {
                log.info("GPU did not show performance benefit in tested range");
            }
            
            // Show best speedup
            double bestSpeedup = results.stream()
                .mapToDouble(BenchmarkResult::getSpeedup)
                .filter(s -> s > 0)
                .max()
                .orElse(0);
            
            log.info("Best GPU speedup: {}x", String.format("%.2f", bestSpeedup));
        }
        
        log.info("====================================================\n");
    }
    
    private static class BenchmarkResult {
        final int rayCount;
        final int octreeDepth;
        final int nodeCount;
        final long cpuTime;
        final long gpuTime;
        
        BenchmarkResult(int rayCount, int octreeDepth, int nodeCount, 
                       long cpuTime, long gpuTime) {
            this.rayCount = rayCount;
            this.octreeDepth = octreeDepth;
            this.nodeCount = nodeCount;
            this.cpuTime = cpuTime;
            this.gpuTime = gpuTime;
        }
        
        double getSpeedup() {
            if (gpuTime <= 0) return 0;
            return (double)cpuTime / gpuTime;
        }
    }
    
}

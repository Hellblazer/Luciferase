package com.hellblazer.luciferase.gpu.test.performance;

import com.hellblazer.luciferase.gpu.esvo.ESVOKernels;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures.OctreeNode;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures.Ray;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures.IntersectionResult;
import com.hellblazer.luciferase.gpu.test.support.TestSupportMatrix;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks for ESVO ray traversal algorithms.
 * 
 * Provides accurate performance measurements with proper warmup,
 * statistical analysis, and JVM optimization control.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = {"-Xms2G", "-Xmx2G", "--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class ESVOJMHBenchmark {
    private static final Logger log = LoggerFactory.getLogger(ESVOJMHBenchmark.class);
    
    @Param({"100", "1000", "10000"})
    private int rayCount;
    
    @Param({"4", "6", "8"})
    private int octreeDepth;
    
    private Ray[] rays;
    private OctreeNode[] octree;
    
    // GPU resources
    private long clContext;
    private long clQueue;
    private long clProgram;
    private long clKernel;
    private long rayBuffer;
    private long octreeBuffer;
    private long resultBuffer;
    private long voxelBuffer;
    private boolean gpuAvailable;
    
    @Setup(Level.Trial)
    public void setupTrial() {
        // Initialize GPU once per trial
        gpuAvailable = initializeGPU();
        
        if (!gpuAvailable) {
            log.warn("GPU not available for benchmarks");
        }
    }
    
    @Setup(Level.Iteration)
    public void setupIteration() {
        // Generate test data for each iteration
        rays = generateRays(rayCount);
        octree = generateOctree(octreeDepth);
        
        if (gpuAvailable) {
            allocateGPUBuffers();
        }
    }
    
    @TearDown(Level.Iteration)
    public void tearDownIteration() {
        if (gpuAvailable) {
            releaseGPUBuffers();
        }
    }
    
    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (gpuAvailable && clKernel != 0) {
            CL10.clReleaseKernel(clKernel);
            CL10.clReleaseProgram(clProgram);
            CL10.clReleaseCommandQueue(clQueue);
            CL10.clReleaseContext(clContext);
        }
    }
    
    @Benchmark
    public IntersectionResult[] benchmarkCPUTraversal() {
        return traverseCPU(rays, octree);
    }
    
    @Benchmark
    public void benchmarkGPUTraversal() {
        if (!gpuAvailable) {
            return;
        }
        
        try (var stack = MemoryStack.stackPush()) {
            // Execute kernel
            CL10.clEnqueueNDRangeKernel(
                clQueue, clKernel, 1, null,
                stack.pointers(rays.length), null,
                null, null
            );
            CL10.clFinish(clQueue);
        }
    }
    
    @Benchmark
    public IntersectionResult[] benchmarkCPUSIMD() {
        // Simulated SIMD-optimized CPU traversal
        // In real implementation, would use Vector API or JNI to native SIMD
        var results = new IntersectionResult[rays.length];
        
        // Process in batches of 4 (simulating SIMD width)
        for (int i = 0; i < rays.length; i += 4) {
            int batchSize = Math.min(4, rays.length - i);
            
            for (int j = 0; j < batchSize; j++) {
                results[i + j] = traverseSingleRayCPU(rays[i + j], octree);
            }
        }
        
        return results;
    }
    
    private boolean initializeGPU() {
        try {
            var supportMatrix = new TestSupportMatrix();
            if (supportMatrix.getBackendSupport(TestSupportMatrix.Backend.OPENCL) 
                == TestSupportMatrix.SupportLevel.NOT_AVAILABLE) {
                return false;
            }
            
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
                
                clQueue = CL10.clCreateCommandQueue(
                    clContext, device, 0, errcode
                );
                
                // Compile kernel
                String kernelSource = ESVOKernels.getOpenCLKernel();
                clProgram = ESVODataStructures.BufferUtils.compileProgram(clContext, kernelSource);
                
                clKernel = CL10.clCreateKernel(clProgram, "traverseOctree", errcode);
                
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    private void allocateGPUBuffers() {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);
            
            // Allocate GPU buffers
            rayBuffer = ESVODataStructures.BufferUtils.createRayBuffer(
                clContext, rays, CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR
            );
            
            octreeBuffer = ESVODataStructures.BufferUtils.createNodeBuffer(
                clContext, octree, CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR
            );
            
            resultBuffer = CL10.clCreateBuffer(
                clContext,
                CL10.CL_MEM_WRITE_ONLY,
                rays.length * 16L,
                errcode
            );
            
            voxelBuffer = CL10.clCreateBuffer(
                clContext,
                CL10.CL_MEM_WRITE_ONLY,
                rays.length * 4L,
                errcode
            );
            
            // Set kernel arguments
            CL10.clSetKernelArg(clKernel, 0, rayBuffer);
            CL10.clSetKernelArg(clKernel, 1, octreeBuffer);
            CL10.clSetKernelArg(clKernel, 2, resultBuffer);
            CL10.clSetKernelArg(clKernel, 3, voxelBuffer);
            CL10.clSetKernelArg(clKernel, 4, stack.ints(octreeDepth));
            CL10.clSetKernelArg(clKernel, 5, stack.floats(0, 0, 0));
            CL10.clSetKernelArg(clKernel, 6, stack.floats(1, 1, 1));
        }
    }
    
    private void releaseGPUBuffers() {
        if (rayBuffer != 0) CL10.clReleaseMemObject(rayBuffer);
        if (octreeBuffer != 0) CL10.clReleaseMemObject(octreeBuffer);
        if (resultBuffer != 0) CL10.clReleaseMemObject(resultBuffer);
        if (voxelBuffer != 0) CL10.clReleaseMemObject(voxelBuffer);
        
        rayBuffer = 0;
        octreeBuffer = 0;
        resultBuffer = 0;
        voxelBuffer = 0;
    }
    
    private IntersectionResult[] traverseCPU(Ray[] rays, OctreeNode[] octree) {
        var results = new IntersectionResult[rays.length];
        
        for (int i = 0; i < rays.length; i++) {
            results[i] = traverseSingleRayCPU(rays[i], octree);
        }
        
        return results;
    }
    
    private IntersectionResult traverseSingleRayCPU(Ray ray, OctreeNode[] octree) {
        // Simplified traversal for benchmarking
        var result = new IntersectionResult();
        result.hit = 0;
        result.t = Float.MAX_VALUE;
        
        // Basic AABB intersection test
        if (!intersectAABB(ray, 0, 0, 0, 1, 1, 1)) {
            return result;
        }
        
        // Traverse octree
        int currentNode = 0;
        int maxDepth = octreeDepth;
        
        for (int depth = 0; depth < maxDepth && currentNode < octree.length; depth++) {
            var node = octree[currentNode];
            boolean isLeaf = (node.childDescriptor & 0x80000000) != 0;
            
            if (isLeaf) {
                if (node.attributes != 0) {
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
        var random = new Random(42);
        
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
        int nodeCount = Math.min(100_000, (int)((Math.pow(8, maxDepth + 1) - 1) / 7));
        var nodes = new OctreeNode[nodeCount];
        var random = new Random(42);
        
        nodes[0] = new OctreeNode();
        nodes[0].childDescriptor = 1;
        nodes[0].minValue = 0;
        nodes[0].maxValue = 1;
        
        int currentIndex = 1;
        for (int i = 0; i < Math.min(nodeCount / 8, nodes.length); i++) {
            if (nodes[i] != null && (nodes[i].childDescriptor & 0x80000000) == 0 && currentIndex + 8 < nodes.length) {
                nodes[i].childDescriptor = currentIndex;
                
                for (int j = 0; j < 8; j++) {
                    boolean isLeaf = random.nextFloat() > 0.7 || currentIndex + 8 >= nodes.length;
                    nodes[currentIndex] = new OctreeNode();
                    
                    if (isLeaf) {
                        nodes[currentIndex].childDescriptor = 0x80000000;
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
    
    
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(ESVOJMHBenchmark.class.getSimpleName())
            .forks(2)
            .build();
        
        new Runner(opt).run();
    }
}
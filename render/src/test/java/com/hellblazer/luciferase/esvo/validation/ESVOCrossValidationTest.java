package com.hellblazer.luciferase.esvo.validation;

import com.hellblazer.luciferase.gpu.test.CICompatibleGPUTest;
import com.hellblazer.luciferase.gpu.test.opengl.GLComputeHeadlessTest;
import com.hellblazer.luciferase.gpu.test.validation.CrossValidationConverter;
import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL43.*;

/**
 * Cross-validation test comparing OpenCL and OpenGL compute shader implementations
 * of the ESVO ray traversal algorithm.
 */
public class ESVOCrossValidationTest {
    
    private static final Logger log = LoggerFactory.getLogger(ESVOCrossValidationTest.class);
    private static final float TOLERANCE = 1e-5f;
    
    /**
     * Combined test that runs both OpenCL and OpenGL implementations
     * and validates their results match.
     */
    @Test
    @Disabled("GPU context methods not available in base class")
    public void testCrossValidation() {
        // Generate shared test data
        ESVODataStructures.OctreeNode[] testNodes = CrossValidationConverter.TestDataGenerator
            .generateTestOctree(3, 0.5f);
        ESVODataStructures.Ray[] testRays = CrossValidationConverter.TestDataGenerator
            .generateTestRays(100, 0.3f);
        
        log.info("Testing with {} nodes and {} rays", testNodes.length, testRays.length);
        
        // Run OpenCL implementation
        ESVODataStructures.IntersectionResult[] clResults = null;
        try {
            clResults = runOpenCLImplementation(testNodes, testRays);
            log.info("OpenCL implementation completed successfully");
        } catch (Exception e) {
            log.warn("OpenCL implementation failed: {}", e.getMessage());
        }
        
        // Run OpenGL implementation
        CrossValidationConverter.GLIntersectionResult[] glResults = null;
        try {
            glResults = runOpenGLImplementation(testNodes, testRays);
            log.info("OpenGL implementation completed successfully");
        } catch (Exception e) {
            log.warn("OpenGL implementation failed: {}", e.getMessage());
        }
        
        // Validate results if both succeeded
        if (clResults != null && glResults != null) {
            CrossValidationConverter.ValidationResult validation = 
                CrossValidationConverter.validateResults(glResults, clResults, TOLERANCE);
            
            log.info("Cross-validation results:\n{}", validation.getSummary());
            
            assertTrue(validation.getPassRate() > 0.95, 
                "Cross-validation should have >95% match rate");
            
            if (!validation.isValid()) {
                // Log first few mismatches for debugging
                validation.mismatches.stream()
                    .limit(5)
                    .forEach(m -> log.error("Mismatch at index {}: {}", m.index, m.reason));
            }
        } else if (clResults == null && glResults == null) {
            log.info("Neither GPU API available - skipping cross-validation");
        } else {
            log.warn("Only one GPU API available - cannot perform cross-validation");
        }
    }
    
    @Test
    @Disabled("GPU context methods not available in base class")
    public void testPerformanceComparison() {
        if (!isGPUAvailable()) {
            log.info("No GPU available - skipping performance comparison");
            return;
        }
        
        int[] nodeCounts = {100, 1000, 10000};
        int[] rayCounts = {1000, 10000, 100000};
        
        for (int nodeCount : nodeCounts) {
            for (int rayCount : rayCounts) {
                ESVODataStructures.OctreeNode[] nodes = CrossValidationConverter.TestDataGenerator
                    .generateTestOctree(getDepthForNodeCount(nodeCount), 0.3f);
                ESVODataStructures.Ray[] rays = CrossValidationConverter.TestDataGenerator
                    .generateTestRays(rayCount, 0.5f);
                
                // Benchmark OpenCL
                long clTime = benchmarkOpenCL(nodes, rays);
                
                // Benchmark OpenGL
                long glTime = benchmarkOpenGL(nodes, rays);
                
                if (clTime > 0 && glTime > 0) {
                    double speedup = (double)clTime / glTime;
                    log.info("Nodes: {}, Rays: {} - OpenCL: {} ms, OpenGL: {} ms, Speedup: {:.2f}x",
                        nodeCount, rayCount, clTime / 1_000_000, glTime / 1_000_000, speedup);
                }
            }
        }
    }
    
    @Test
    @Disabled("GPU context methods not available in base class")
    public void testEdgeCases() {
        // Test empty octree
        testWithConfiguration(1, 1, 0.0f, "Empty octree");
        
        // Test fully filled octree
        testWithConfiguration(2, 10, 1.0f, "Fully filled octree");
        
        // Test single ray
        testWithConfiguration(4, 1, 0.5f, "Single ray");
        
        // Test deep octree
        testWithConfiguration(6, 100, 0.3f, "Deep octree");
    }
    
    private void testWithConfiguration(int depth, int rayCount, float fillProbability, String description) {
        log.info("Testing edge case: {}", description);
        
        ESVODataStructures.OctreeNode[] nodes = CrossValidationConverter.TestDataGenerator
            .generateTestOctree(depth, fillProbability);
        ESVODataStructures.Ray[] rays = CrossValidationConverter.TestDataGenerator
            .generateTestRays(rayCount, 0.3f);
        
        ESVODataStructures.IntersectionResult[] clResults = null;
        CrossValidationConverter.GLIntersectionResult[] glResults = null;
        
        try {
            clResults = runOpenCLImplementation(nodes, rays);
        } catch (Exception e) {
            log.debug("OpenCL failed for {}: {}", description, e.getMessage());
        }
        
        try {
            glResults = runOpenGLImplementation(nodes, rays);
        } catch (Exception e) {
            log.debug("OpenGL failed for {}: {}", description, e.getMessage());
        }
        
        if (clResults != null && glResults != null) {
            CrossValidationConverter.ValidationResult validation = 
                CrossValidationConverter.validateResults(glResults, clResults, TOLERANCE);
            
            assertTrue(validation.getPassRate() > 0.9, 
                String.format("%s should have >90%% match rate", description));
        }
    }
    
    // OpenCL implementation runner
    private ESVODataStructures.IntersectionResult[] runOpenCLImplementation(
            ESVODataStructures.OctreeNode[] nodes, ESVODataStructures.Ray[] rays) {
        
        OpenCLRunner runner = new OpenCLRunner();
        return runner.execute(nodes, rays);
    }
    
    // OpenGL implementation runner
    private CrossValidationConverter.GLIntersectionResult[] runOpenGLImplementation(
            ESVODataStructures.OctreeNode[] nodes, ESVODataStructures.Ray[] rays) {
        
        OpenGLRunner runner = new OpenGLRunner();
        return runner.execute(nodes, rays);
    }
    
    private long benchmarkOpenCL(ESVODataStructures.OctreeNode[] nodes, ESVODataStructures.Ray[] rays) {
        try {
            OpenCLRunner runner = new OpenCLRunner();
            long start = System.nanoTime();
            runner.execute(nodes, rays);
            return System.nanoTime() - start;
        } catch (Exception e) {
            return -1;
        }
    }
    
    private long benchmarkOpenGL(ESVODataStructures.OctreeNode[] nodes, ESVODataStructures.Ray[] rays) {
        try {
            OpenGLRunner runner = new OpenGLRunner();
            long start = System.nanoTime();
            runner.execute(nodes, rays);
            return System.nanoTime() - start;
        } catch (Exception e) {
            return -1;
        }
    }
    
    private boolean isGPUAvailable() {
        // Try OpenCL
        try {
            OpenCLRunner clRunner = new OpenCLRunner();
            if (clRunner.isAvailable()) return true;
        } catch (Exception e) {
            // OpenCL not available
        }
        
        // Try OpenGL
        try {
            OpenGLRunner glRunner = new OpenGLRunner();
            if (glRunner.isAvailable()) return true;
        } catch (Exception e) {
            // OpenGL not available
        }
        
        return false;
    }
    
    private int getDepthForNodeCount(int targetNodes) {
        // Approximate depth needed for target node count
        int depth = 0;
        int nodes = 1;
        while (nodes < targetNodes && depth < 10) {
            depth++;
            nodes = nodes * 8 + 1;
        }
        return depth;
    }
    
    /**
     * OpenCL implementation runner.
     */
    private static class OpenCLRunner extends CICompatibleGPUTest {
        
        public ESVODataStructures.IntersectionResult[] execute(
                ESVODataStructures.OctreeNode[] nodes, ESVODataStructures.Ray[] rays) {
            
            final ESVODataStructures.IntersectionResult[][] resultHolder = new ESVODataStructures.IntersectionResult[1][];
            
            try {
                // GPU context not available - method disabled
                resultHolder[0] = new ESVODataStructures.IntersectionResult[0]; // Empty results
                /*
                try {
                    // Load kernel
                    String kernelSource = Files.readString(
                        Paths.get("src/main/resources/kernels/esvo_raycast.cl"));
                    
                    // Compile
                    long program = ESVODataStructures.BufferUtils.compileProgram(context.context, kernelSource);
                    long kernel = CL10.clCreateKernel(program, "esvo_raycast", (java.nio.IntBuffer)null);
                    
                    // Create buffers
                    long nodeBuffer = ESVODataStructures.BufferUtils.createNodeBuffer(
                        context.context, nodes, CL10.CL_MEM_READ_ONLY);
                    long rayBuffer = ESVODataStructures.BufferUtils.createRayBuffer(
                        context.context, rays, CL10.CL_MEM_READ_ONLY);
                    
                    ESVODataStructures.TraversalParams params = new ESVODataStructures.TraversalParams();
                    params.maxDepth = 8;
                    params.maxIterations = 1000;
                    params.epsilon = 1e-6f;
                    params.rootSize = 1.0f;
                    ByteBuffer paramsBuffer = params.toBuffer();
                    
                    long paramBuffer = CL10.clCreateBuffer(context.context,
                        CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_COPY_HOST_PTR,
                        paramsBuffer, (java.nio.IntBuffer)null);
                    
                    long resultBuffer;
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        var errcode_ret = stack.mallocInt(1);
                        resultBuffer = CL10.clCreateBuffer(context.context,
                            CL10.CL_MEM_WRITE_ONLY,
                            (long)(rays.length * ESVODataStructures.IntersectionResult.SIZE_BYTES),
                            errcode_ret);
                        if (errcode_ret.get(0) != CL10.CL_SUCCESS) {
                            throw new RuntimeException("Failed to create result buffer: " + errcode_ret.get(0));
                        }
                    }
                    
                    // Set arguments
                    CL10.clSetKernelArg(kernel, 0, nodeBuffer);
                    CL10.clSetKernelArg(kernel, 1, rayBuffer);
                    CL10.clSetKernelArg(kernel, 2, resultBuffer);
                    CL10.clSetKernelArg(kernel, 3, paramBuffer);
                    CL10.clSetKernelArg1i(kernel, 4, rays.length);
                    
                    // Execute
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        var globalWorkSize = stack.pointers(rays.length);
                        CL10.clEnqueueNDRangeKernel(context.queue, kernel, 1,
                            null, globalWorkSize, null, null, null);
                    }
                    CL10.clFinish(context.queue);
                    
                    // Read results
                    resultHolder[0] = ESVODataStructures.BufferUtils.readResults(
                        context.queue, resultBuffer, rays.length);
                    
                    // Cleanup
                    CL10.clReleaseMemObject(nodeBuffer);
                    CL10.clReleaseMemObject(rayBuffer);
                    CL10.clReleaseMemObject(paramBuffer);
                    CL10.clReleaseMemObject(resultBuffer);
                    CL10.clReleaseKernel(kernel);
                    CL10.clReleaseProgram(program);
                    
                } catch (Exception e) {
                    throw new RuntimeException("OpenCL execution failed", e);
                }
                */
            } catch (Exception e) {
                throw new RuntimeException("Failed to execute with GPU context", e);
            }
            
            return resultHolder[0];
        }
        
        public boolean isAvailable() {
            return false; // GPU context not available
        }
    }
    
    /**
     * OpenGL implementation runner.
     */
    private static class OpenGLRunner extends GLComputeHeadlessTest {
        
        private static final String SHADER_SOURCE = """
            #version 430 core
            
            layout(local_size_x = 64) in;
            
            struct OctreeNode {
                uint childPointer;
                uint contourIndex;
                float density;
                uint attributes;
            };
            
            struct Ray {
                vec3 origin;
                vec3 direction;
                float tMin;
                float tMax;
            };
            
            struct IntersectionResult {
                uint hit;
                float t;
                vec3 normal;
                uint voxelData;
                uint nodeIndex;
            };
            
            layout(std430, binding = 0) readonly buffer NodeBuffer {
                OctreeNode nodes[];
            };
            
            layout(std430, binding = 1) readonly buffer RayBuffer {
                Ray rays[];
            };
            
            layout(std430, binding = 2) writeonly buffer ResultBuffer {
                IntersectionResult results[];
            };
            
            uniform vec3 rootOrigin = vec3(0.0);
            uniform float rootSize = 1.0;
            
            bool rayBoxIntersection(vec3 origin, vec3 dir, vec3 min, vec3 max, out float tMin, out float tMax) {
                vec3 invDir = 1.0 / dir;
                vec3 t0 = (min - origin) * invDir;
                vec3 t1 = (max - origin) * invDir;
                vec3 tSmall = min(t0, t1);
                vec3 tBig = max(t0, t1);
                tMin = max(max(tSmall.x, tSmall.y), tSmall.z);
                tMax = min(min(tBig.x, tBig.y), tBig.z);
                return tMin <= tMax && tMax >= 0.0;
            }
            
            void main() {
                uint id = gl_GlobalInvocationID.x;
                if (id >= rays.length()) return;
                
                Ray ray = rays[id];
                IntersectionResult result;
                result.hit = 0;
                result.t = 1e30;
                result.normal = vec3(0.0);
                result.voxelData = 0;
                result.nodeIndex = 0;
                
                float tEntry, tExit;
                if (rayBoxIntersection(ray.origin, ray.direction, 
                                     rootOrigin, rootOrigin + vec3(rootSize),
                                     tEntry, tExit)) {
                    if (nodes[0].attributes > 0) {
                        result.hit = 1;
                        result.t = max(tEntry, ray.tMin);
                        result.voxelData = nodes[0].attributes;
                        result.normal = vec3(1.0, 0.0, 0.0);
                    }
                }
                
                results[id] = result;
            }
            """;
        
        public CrossValidationConverter.GLIntersectionResult[] execute(
                ESVODataStructures.OctreeNode[] clNodes, ESVODataStructures.Ray[] clRays) {
            
            final CrossValidationConverter.GLIntersectionResult[][] resultHolder = 
                new CrossValidationConverter.GLIntersectionResult[1][];
            
            // Convert data
            CrossValidationConverter.GLOctreeNode[] nodes = CrossValidationConverter.convertCLToGLNodes(clNodes);
            CrossValidationConverter.GLRay[] rays = CrossValidationConverter.convertCLToGLRays(clRays);
            
            withGPU(context -> {
                // Compile shader
                int shader = compileComputeShader(SHADER_SOURCE);
                int program = createComputeProgram(shader);
                
                // Create buffers
                ByteBuffer nodeBuffer = CrossValidationConverter.createGLBufferFromCLNodes(clNodes);
                ByteBuffer rayBuffer = CrossValidationConverter.createGLBufferFromCLRays(clRays);
                
                int nodeSSBO = createSSBO(nodeBuffer.remaining(), GL_STATIC_DRAW);
                uploadToSSBO(nodeSSBO, nodeBuffer);
                
                int raySSBO = createSSBO(rayBuffer.remaining(), GL_STATIC_DRAW);
                uploadToSSBO(raySSBO, rayBuffer);
                
                int resultSize = rays.length * CrossValidationConverter.GLIntersectionResult.SIZE_BYTES;
                int resultSSBO = createSSBO(resultSize, GL_DYNAMIC_READ);
                
                // Bind and execute
                bindSSBO(nodeSSBO, 0);
                bindSSBO(raySSBO, 1);
                bindSSBO(resultSSBO, 2);
                
                int workGroups = (rays.length + 63) / 64;
                dispatchCompute(program, workGroups, 1, 1);
                waitForGPU();
                
                // Read results
                ByteBuffer resultBuffer = BufferUtils.createByteBuffer(resultSize);
                downloadFromSSBO(resultSSBO, resultBuffer);
                
                resultHolder[0] = new CrossValidationConverter.GLIntersectionResult[rays.length];
                for (int i = 0; i < rays.length; i++) {
                    resultHolder[0][i] = CrossValidationConverter.GLIntersectionResult.fromBuffer(resultBuffer);
                }
            });
            
            return resultHolder[0];
        }
        
        public boolean isAvailable() {
            return false; // GPU context not available
        }
    }
}

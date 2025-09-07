package com.hellblazer.luciferase.gpu.test.opengl;

import com.hellblazer.luciferase.gpu.test.opencl.ESVODataStructures;
import com.hellblazer.luciferase.gpu.test.validation.CrossValidationConverter;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL compute shader tests for ESVO ray traversal.
 * Validates GPU compute shader implementation.
 */
public class ESVOGLComputeTest extends GLComputeHeadlessTest {
    
    @org.junit.jupiter.api.BeforeAll
    static void checkEnvironment() {
        // Skip all tests in this class if we're in CI or no GPU is available
        String ci = System.getenv("CI");
        if ("true".equals(ci)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, 
                "Skipping OpenGL compute tests - CI environment detected");
        }
    }
    
    @org.junit.jupiter.api.BeforeEach
    void checkGPUAvailable() {
        if (!gpuAvailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, 
                "Skipping OpenGL test - no GPU available in CI environment");
        }
    }
    
    private static final String COMPUTE_SHADER_SOURCE = """
        #version 430 core
        
        layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
        
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
        
        uniform vec3 rootOrigin;
        uniform float rootSize;
        uniform uint maxDepth;
        
        bool rayBoxIntersection(vec3 rayOrigin, vec3 rayDir,
                               vec3 boxMin, vec3 boxMax,
                               out float tEntry, out float tExit) {
            vec3 invDir = 1.0 / rayDir;
            vec3 t0 = (boxMin - rayOrigin) * invDir;
            vec3 t1 = (boxMax - rayOrigin) * invDir;
            
            vec3 tMin = min(t0, t1);
            vec3 tMax = max(t0, t1);
            
            tEntry = max(max(tMin.x, tMin.y), tMin.z);
            tExit = min(min(tMax.x, tMax.y), tMax.z);
            
            return tEntry <= tExit && tExit >= 0.0;
        }
        
        void main() {
            uint rayId = gl_GlobalInvocationID.x;
            if (rayId >= rays.length()) return;
            
            Ray ray = rays[rayId];
            IntersectionResult result;
            result.hit = 0;
            result.t = 1e30;
            result.normal = vec3(0.0);
            result.voxelData = 0;
            result.nodeIndex = 0;
            
            // Simple root node test
            vec3 boxMin = rootOrigin;
            vec3 boxMax = rootOrigin + vec3(rootSize);
            float tEntry, tExit;
            
            if (rayBoxIntersection(ray.origin, ray.direction, boxMin, boxMax, tEntry, tExit)) {
                if (nodes[0].attributes > 0) {
                    result.hit = 1;
                    result.t = max(tEntry, ray.tMin);
                    result.nodeIndex = 0;
                    result.voxelData = nodes[0].attributes;
                    
                    // Simple normal calculation
                    vec3 hitPoint = ray.origin + ray.direction * result.t;
                    vec3 center = rootOrigin + vec3(rootSize * 0.5);
                    vec3 diff = hitPoint - center;
                    vec3 absDiff = abs(diff);
                    
                    if (absDiff.x > absDiff.y && absDiff.x > absDiff.z) {
                        result.normal = vec3(sign(diff.x), 0.0, 0.0);
                    } else if (absDiff.y > absDiff.z) {
                        result.normal = vec3(0.0, sign(diff.y), 0.0);
                    } else {
                        result.normal = vec3(0.0, 0.0, sign(diff.z));
                    }
                }
            }
            
            results[rayId] = result;
        }
        """;
    
    @Test
    public void testBasicRayTraversal() {
        withGPU(context -> {
            // Compile shader
            int shader = compileComputeShader(COMPUTE_SHADER_SOURCE);
            int program = createComputeProgram(shader);
            
            // Create test data
            CrossValidationConverter.GLOctreeNode[] nodes = createSimpleOctree();
            CrossValidationConverter.GLRay[] rays = createTestRays();
            
            // Create buffers
            ByteBuffer nodeBuffer = createNodeBuffer(nodes);
            ByteBuffer rayBuffer = createRayBuffer(rays);
            int resultSize = rays.length * CrossValidationConverter.GLIntersectionResult.SIZE_BYTES;
            
            int nodeSSBO = createSSBO(nodeBuffer.remaining(), GL_STATIC_DRAW);
            uploadToSSBO(nodeSSBO, nodeBuffer);
            
            int raySSBO = createSSBO(rayBuffer.remaining(), GL_STATIC_DRAW);
            uploadToSSBO(raySSBO, rayBuffer);
            
            int resultSSBO = createSSBO(resultSize, GL_DYNAMIC_READ);
            
            // Bind buffers
            bindSSBO(nodeSSBO, 0);
            bindSSBO(raySSBO, 1);
            bindSSBO(resultSSBO, 2);
            
            // Set uniforms
            glUseProgram(program);
            glUniform3f(glGetUniformLocation(program, "rootOrigin"), 0.0f, 0.0f, 0.0f);
            glUniform1f(glGetUniformLocation(program, "rootSize"), 1.0f);
            glUniform1ui(glGetUniformLocation(program, "maxDepth"), 8);
            
            // Dispatch compute
            int workGroups = (rays.length + 63) / 64;
            dispatchCompute(program, workGroups, 1, 1);
            
            // Read results
            ByteBuffer resultBuffer = BufferUtils.createByteBuffer(resultSize);
            downloadFromSSBO(resultSSBO, resultBuffer);
            
            // Parse and validate results
            CrossValidationConverter.GLIntersectionResult[] results = parseResults(resultBuffer, rays.length);
            validateResults(results);
        });
    }
    
    @Test
    public void testPerformanceBenchmark() {
        withGPU(context -> {
            if (!isGPUAvailable()) {
                System.out.println("Skipping performance test - no GPU");
                return;
            }
            
            int[] rayCounts = {1000, 10000, 100000};
            
            for (int rayCount : rayCounts) {
                // Setup
                int shader = compileComputeShader(COMPUTE_SHADER_SOURCE);
                int program = createComputeProgram(shader);
                
                CrossValidationConverter.GLRay[] rays = generateRandomRays(rayCount);
                ByteBuffer rayBuffer = createRayBuffer(rays);
                
                // Simple octree
                CrossValidationConverter.GLOctreeNode[] nodes = createSimpleOctree();
                ByteBuffer nodeBuffer = createNodeBuffer(nodes);
                
                int nodeSSBO = createSSBO(nodeBuffer.remaining(), GL_STATIC_DRAW);
                uploadToSSBO(nodeSSBO, nodeBuffer);
                
                int raySSBO = createSSBO(rayBuffer.remaining(), GL_STATIC_DRAW);
                uploadToSSBO(raySSBO, rayBuffer);
                
                int resultSize = rayCount * CrossValidationConverter.GLIntersectionResult.SIZE_BYTES;
                int resultSSBO = createSSBO(resultSize, GL_DYNAMIC_READ);
                
                bindSSBO(nodeSSBO, 0);
                bindSSBO(raySSBO, 1);
                bindSSBO(resultSSBO, 2);
                
                glUseProgram(program);
                glUniform3f(glGetUniformLocation(program, "rootOrigin"), 0.0f, 0.0f, 0.0f);
                glUniform1f(glGetUniformLocation(program, "rootSize"), 1.0f);
                glUniform1ui(glGetUniformLocation(program, "maxDepth"), 8);
                
                // Warmup
                int workGroups = (rayCount + 63) / 64;
                dispatchCompute(program, workGroups, 1, 1);
                waitForGPU();
                
                // Benchmark
                GPUTimer timer = new GPUTimer();
                timer.start();
                dispatchCompute(program, workGroups, 1, 1);
                waitForGPU();
                timer.stop();
                
                double elapsed = timer.getElapsedMillis();
                double raysPerSecond = (rayCount / elapsed) * 1000;
                
                System.out.printf("GL Compute - Rays: %d, Time: %.3f ms, Rays/sec: %.0f%n",
                    rayCount, elapsed, raysPerSecond);
                
                timer.cleanup();
            }
        });
    }
    
    @Test
    public void testMultipleDispatches() {
        withGPU(context -> {
            int shader = compileComputeShader(COMPUTE_SHADER_SOURCE);
            int program = createComputeProgram(shader);
            
            // Run multiple dispatches to test stability
            for (int i = 0; i < 10; i++) {
                CrossValidationConverter.GLRay[] rays = createTestRays();
                CrossValidationConverter.GLOctreeNode[] nodes = createSimpleOctree();
                
                ByteBuffer nodeBuffer = createNodeBuffer(nodes);
                ByteBuffer rayBuffer = createRayBuffer(rays);
                
                int nodeSSBO = createSSBO(nodeBuffer.remaining(), GL_STATIC_DRAW);
                uploadToSSBO(nodeSSBO, nodeBuffer);
                
                int raySSBO = createSSBO(rayBuffer.remaining(), GL_STATIC_DRAW);
                uploadToSSBO(raySSBO, rayBuffer);
                
                int resultSize = rays.length * CrossValidationConverter.GLIntersectionResult.SIZE_BYTES;
                int resultSSBO = createSSBO(resultSize, GL_DYNAMIC_READ);
                
                bindSSBO(nodeSSBO, 0);
                bindSSBO(raySSBO, 1);
                bindSSBO(resultSSBO, 2);
                
                glUseProgram(program);
                glUniform3f(glGetUniformLocation(program, "rootOrigin"), 0.0f, 0.0f, 0.0f);
                glUniform1f(glGetUniformLocation(program, "rootSize"), 1.0f);
                
                int workGroups = (rays.length + 63) / 64;
                dispatchCompute(program, workGroups, 1, 1);
                
                ByteBuffer resultBuffer = BufferUtils.createByteBuffer(resultSize);
                downloadFromSSBO(resultSSBO, resultBuffer);
                
                CrossValidationConverter.GLIntersectionResult[] results = parseResults(resultBuffer, rays.length);
                
                // Basic validation
                assertNotNull(results);
                assertEquals(rays.length, results.length);
                
                // Cleanup iteration resources
                glDeleteBuffers(nodeSSBO);
                glDeleteBuffers(raySSBO);
                glDeleteBuffers(resultSSBO);
            }
        });
    }
    
    // Helper methods
    
    private CrossValidationConverter.GLOctreeNode[] createSimpleOctree() {
        CrossValidationConverter.GLOctreeNode[] nodes = new CrossValidationConverter.GLOctreeNode[1];
        nodes[0] = new CrossValidationConverter.GLOctreeNode();
        nodes[0].childPointer = 0;
        nodes[0].attributes = 1;  // Filled voxel
        nodes[0].density = 1.0f;
        nodes[0].contourIndex = 0;
        return nodes;
    }
    
    private CrossValidationConverter.GLRay[] createTestRays() {
        CrossValidationConverter.GLRay[] rays = new CrossValidationConverter.GLRay[3];
        
        // Ray hitting center
        rays[0] = new CrossValidationConverter.GLRay();
        rays[0].origin[0] = -1.0f;
        rays[0].origin[1] = 0.5f;
        rays[0].origin[2] = 0.5f;
        rays[0].direction[0] = 1.0f;
        rays[0].direction[1] = 0.0f;
        rays[0].direction[2] = 0.0f;
        rays[0].tMin = 0.0f;
        rays[0].tMax = 10.0f;
        
        // Ray missing
        rays[1] = new CrossValidationConverter.GLRay();
        rays[1].origin[0] = -1.0f;
        rays[1].origin[1] = 2.0f;
        rays[1].origin[2] = 0.5f;
        rays[1].direction[0] = 1.0f;
        rays[1].direction[1] = 0.0f;
        rays[1].direction[2] = 0.0f;
        rays[1].tMin = 0.0f;
        rays[1].tMax = 10.0f;
        
        // Diagonal ray
        rays[2] = new CrossValidationConverter.GLRay();
        rays[2].origin[0] = -1.0f;
        rays[2].origin[1] = -1.0f;
        rays[2].origin[2] = -1.0f;
        float invSqrt3 = (float)(1.0 / Math.sqrt(3.0));
        rays[2].direction[0] = invSqrt3;
        rays[2].direction[1] = invSqrt3;
        rays[2].direction[2] = invSqrt3;
        rays[2].tMin = 0.0f;
        rays[2].tMax = 10.0f;
        
        return rays;
    }
    
    private CrossValidationConverter.GLRay[] generateRandomRays(int count) {
        CrossValidationConverter.GLRay[] rays = new CrossValidationConverter.GLRay[count];
        
        for (int i = 0; i < count; i++) {
            rays[i] = new CrossValidationConverter.GLRay();
            
            double theta = Math.random() * 2 * Math.PI;
            double phi = Math.acos(2 * Math.random() - 1);
            double r = 2.0 + Math.random();
            
            rays[i].origin[0] = (float)(r * Math.sin(phi) * Math.cos(theta));
            rays[i].origin[1] = (float)(r * Math.sin(phi) * Math.sin(theta));
            rays[i].origin[2] = (float)(r * Math.cos(phi));
            
            float targetX = 0.5f + (float)(Math.random() - 0.5) * 0.5f;
            float targetY = 0.5f + (float)(Math.random() - 0.5) * 0.5f;
            float targetZ = 0.5f + (float)(Math.random() - 0.5) * 0.5f;
            
            float dx = targetX - rays[i].origin[0];
            float dy = targetY - rays[i].origin[1];
            float dz = targetZ - rays[i].origin[2];
            float len = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
            
            rays[i].direction[0] = dx / len;
            rays[i].direction[1] = dy / len;
            rays[i].direction[2] = dz / len;
            rays[i].tMin = 0.0f;
            rays[i].tMax = 100.0f;
        }
        
        return rays;
    }
    
    private ByteBuffer createNodeBuffer(CrossValidationConverter.GLOctreeNode[] nodes) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(
            nodes.length * CrossValidationConverter.GLOctreeNode.SIZE_BYTES);
        
        for (CrossValidationConverter.GLOctreeNode node : nodes) {
            buffer.put(node.toBuffer());
        }
        buffer.flip();
        return buffer;
    }
    
    private ByteBuffer createRayBuffer(CrossValidationConverter.GLRay[] rays) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(
            rays.length * CrossValidationConverter.GLRay.SIZE_BYTES);
        
        for (CrossValidationConverter.GLRay ray : rays) {
            buffer.put(ray.toBuffer());
        }
        buffer.flip();
        return buffer;
    }
    
    private CrossValidationConverter.GLIntersectionResult[] parseResults(ByteBuffer buffer, int count) {
        CrossValidationConverter.GLIntersectionResult[] results = 
            new CrossValidationConverter.GLIntersectionResult[count];
        
        for (int i = 0; i < count; i++) {
            results[i] = CrossValidationConverter.GLIntersectionResult.fromBuffer(buffer);
        }
        
        return results;
    }
    
    private void validateResults(CrossValidationConverter.GLIntersectionResult[] results) {
        assertNotNull(results);
        assertTrue(results.length >= 3);
        
        // First ray should hit
        assertTrue(results[0].hit, "Center ray should hit");
        assertTrue(results[0].t > 0 && results[0].t < 10.0f);
        
        // Second ray should miss
        assertFalse(results[1].hit, "Ray outside cube should miss");
        
        // Third diagonal ray should hit
        assertTrue(results[2].hit, "Diagonal ray should hit");
    }
}
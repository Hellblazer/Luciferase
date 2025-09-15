# Dual-API GPU Testing Plan: OpenCL Validation + OpenGL Compute Integration

## Executive Summary

This document outlines a comprehensive strategy to enable GPU testing for the ESVO render module using a dual-API approach:
- **OpenCL** for algorithmic validation in CI environments (using existing `CICompatibleGPUTest`)
- **OpenGL Compute Shaders** for production code testing (new `GLComputeHeadlessTest`)

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Test Infrastructure                       │
├───────────────────────────┬─────────────────────────────────┤
│     OpenCL Testing        │      OpenGL Testing             │
│  (CI/Algorithm Valid)     │   (Production/Performance)      │
├───────────────────────────┼─────────────────────────────────┤
│  CICompatibleGPUTest      │   GLComputeHeadlessTest (NEW)   │
│  ├─ Platform detection    │   ├─ GLFW hidden window         │
│  ├─ Mock fallback         │   ├─ FBO rendering              │
│  └─ OpenCL kernels        │   └─ GLSL compute shaders      │
├───────────────────────────┼─────────────────────────────────┤
│  ESVOOpenCLValidator      │   ESVOGLComputeTest            │
│  └─ ESVO algorithms       │   └─ ComputeShaderRenderer     │
│      ported to OpenCL     │       production code           │
└───────────────────────────┴─────────────────────────────────┘
```

## Phase 1: OpenCL Algorithm Validation Infrastructure

### 1.1 ESVO Data Structure Definitions

Create OpenCL-compatible versions of ESVO data structures:

**Location**: `gpu-test-framework/src/main/java/com/hellblazer/luciferase/gpu/test/esvo/ESVODataStructures.java`

```java
package com.hellblazer.luciferase.gpu.test.esvo;

/**
 * OpenCL-compatible ESVO data structure definitions.
 * These match the GLSL shader structures exactly.
 */
public class ESVODataStructures {
    
    /**
     * Octree node structure matching GLSL layout.
     * 8 bytes per node = 2 x uint32
     */
    public static class OctreeNode {
        public int childDescriptor;   // Bits: [31-17: childPtr][16: far][15-8: validMask][7-0: nonLeafMask]
        public int contourDescriptor; // Bits: [31-8: contourPtr][7-0: contourMask]
        
        public static final int SIZE_BYTES = 8;
        
        public byte getNonLeafMask() {
            return (byte)(childDescriptor & 0xFF);
        }
        
        public byte getValidMask() {
            return (byte)((childDescriptor >> 8) & 0xFF);
        }
        
        public boolean isFar() {
            return (childDescriptor & 0x10000) != 0;
        }
        
        public int getChildPointer() {
            return childDescriptor >>> 17;
        }
    }
    
    /**
     * Ray structure for traversal
     */
    public static class Ray {
        public float[] origin = new float[3];
        public float[] direction = new float[3];
        public float origSz;    // Screen-space size at origin
        public float dirSz;     // Size change per unit distance
        public float tmin;
        public float tmax;
    }
    
    /**
     * Camera matrices for transformation
     */
    public static class CameraData {
        public float[] viewMatrix = new float[16];
        public float[] projMatrix = new float[16];
        public float[] objectToWorld = new float[16];
        public float[] octreeToObject = new float[16];
        public float[] cameraPos = new float[3];
        public float nearPlane;
        public float[] cameraDir = new float[3];
        public float farPlane;
        
        public static final int SIZE_BYTES = 16 * 4 * 4 + 3 * 4 + 4 + 3 * 4 + 4;
    }
}
```

### 1.2 OpenCL Kernel Ports

Port ESVO ray traversal algorithm to OpenCL:

**Location**: `gpu-test-framework/src/main/resources/kernels/esvo_raycast.cl`

```c
// ESVO Ray Casting Kernel - OpenCL Port
// Direct translation from GLSL compute shader

#define CAST_STACK_DEPTH 23
#define MAX_RAYCAST_ITERATIONS 10000
#define EPSILON 1.19209290e-07f  // exp2(-23)

typedef struct {
    uint childDescriptor;
    uint contourDescriptor;
} OctreeNode;

typedef struct {
    float3 origin;
    float3 direction;
    float orig_sz;
    float dir_sz;
    float tmin;
    float tmax;
} Ray;

// Population count for lower 8 bits
uint popc8(uint mask) {
    return popcount(mask & 0xFF);
}

__kernel void esvo_raycast(
    __global const OctreeNode* octree,
    __global float4* output,
    __constant float* viewMatrix,
    __constant float* projMatrix,
    __constant float* octreeToObject,
    __constant float* objectToWorld,
    const int width,
    const int height
) {
    int x = get_global_id(0);
    int y = get_global_id(1);
    
    if (x >= width || y >= height) return;
    
    // Stack allocation in local memory
    __local uint stack_nodes[64 * CAST_STACK_DEPTH];
    __local float stack_tmax[64 * CAST_STACK_DEPTH];
    
    // Generate ray for pixel (simplified for brevity)
    Ray ray = generateRay(x, y, width, height, viewMatrix, projMatrix);
    
    // Transform ray to octree space [1,2]
    ray = transformToOctreeSpace(ray, octreeToObject, objectToWorld);
    
    // Main traversal algorithm (matches GLSL exactly)
    float3 hitPoint, hitNormal;
    castRay(octree, ray, stack_nodes, stack_tmax, &hitPoint, &hitNormal);
    
    // Output result
    output[y * width + x] = (float4)(hitNormal * 0.5f + 0.5f, 1.0f);
}
```

### 1.3 OpenCL Test Base Class Extensions

Extend the existing framework for ESVO testing:

**Location**: `gpu-test-framework/src/main/java/com/hellblazer/luciferase/gpu/test/esvo/ESVOOpenCLValidator.java`

```java
package com.hellblazer.luciferase.gpu.test.esvo;

import com.hellblazer.luciferase.gpu.test.CICompatibleGPUTest;
import org.lwjgl.system.MemoryStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * OpenCL-based validation tests for ESVO algorithms.
 * These tests run in CI environments without real GPUs.
 */
public class ESVOOpenCLValidator extends CICompatibleGPUTest {
    
    private String esvoKernelSource;
    private ESVOTestDataGenerator testDataGen;
    
    @BeforeEach
    void loadKernels() {
        esvoKernelSource = loadKernelSource("/kernels/esvo_raycast.cl");
        testDataGen = new ESVOTestDataGenerator();
    }
    
    @Test
    void testOctreeTraversal() {
        var platforms = discoverPlatforms();
        assumeTrue(!platforms.isEmpty(), "No OpenCL platforms available");
        
        var platform = platforms.get(0);
        if (MockPlatform.isMockPlatform(platform)) {
            log.info("Using mock platform - validating algorithm logic only");
            validateAlgorithmLogic();
            return;
        }
        
        // Get compute device (GPU or CPU)
        var devices = discoverDevices(platform.platformId(), CL_DEVICE_TYPE_ALL);
        assumeTrue(!devices.isEmpty(), "No compute devices available");
        
        var device = devices.get(0);
        
        // Generate test octree
        var testOctree = testDataGen.generateSimpleOctree();
        var expectedOutput = testDataGen.generateExpectedOutput(testOctree);
        
        // Run OpenCL kernel
        var actualOutput = runESVOKernel(platform.platformId(), 
                                         device.deviceId(),
                                         testOctree,
                                         256, 256);
        
        // Validate results
        assertArrayEquals(expectedOutput, actualOutput, 0.001f,
            "Ray traversal results should match expected");
    }
    
    @Test
    void testCoordinateTransformation() {
        // Test [1,2] octree space transformation
        float[] worldSpacePoint = {0, 0, 0};
        float[] octreeSpacePoint = transformToOctreeSpace(worldSpacePoint);
        
        // Should be in [1,2] range
        assertTrue(octreeSpacePoint[0] >= 1.0f && octreeSpacePoint[0] <= 2.0f);
        assertTrue(octreeSpacePoint[1] >= 1.0f && octreeSpacePoint[1] <= 2.0f);
        assertTrue(octreeSpacePoint[2] >= 1.0f && octreeSpacePoint[2] <= 2.0f);
    }
    
    @Test
    void testChildIndexing() {
        // Test popc8 implementation matches GLSL
        assertEquals(0, popc8(0x00));
        assertEquals(1, popc8(0x01));
        assertEquals(8, popc8(0xFF));
        assertEquals(4, popc8(0xF0));
    }
    
    private float[] runESVOKernel(long platformId, long deviceId,
                                  OctreeNode[] octree,
                                  int width, int height) {
        // Implementation using OpenCL API
        // ... (detailed implementation)
        return new float[width * height * 4];
    }
}
```

## Phase 2: OpenGL Compute Shader Testing Infrastructure

### 2.1 GLComputeHeadlessTest Base Class

Create new base class for OpenGL compute shader testing:

**Location**: `gpu-test-framework/src/main/java/com/hellblazer/luciferase/gpu/test/GLComputeHeadlessTest.java`

```java
package com.hellblazer.luciferase.gpu.test;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Base class for OpenGL compute shader testing in headless environments.
 * 
 * CRITICAL: This requires actual OpenGL drivers and won't work in most CI environments.
 * Use @Tag("gpu") and @Tag("integration") to exclude from default test runs.
 */
@Tag("gpu")
@Tag("integration")
public abstract class GLComputeHeadlessTest extends LWJGLHeadlessTest {
    
    private static final Logger log = LoggerFactory.getLogger(GLComputeHeadlessTest.class);
    
    private long window = NULL;
    private boolean glInitialized = false;
    
    @BeforeEach
    protected void initializeGLContext(TestInfo testInfo) {
        super.initializeNativeLibraries(testInfo);
        
        // Platform-specific configuration
        if (Platform.get() == Platform.MACOSX) {
            // Disable thread check for headless operation
            Configuration.GLFW_CHECK_THREAD0.set(false);
            log.warn("macOS detected - disabling GLFW thread check for headless operation");
        }
        
        // Set error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            assumeTrue(false, "GLFW initialization failed - OpenGL tests will be skipped");
            return;
        }
        
        // Configure for headless/offscreen rendering
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);  // Hidden window
        glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);  // Don't steal focus
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        
        if (Platform.get() == Platform.MACOSX) {
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        }
        
        // Create hidden window for OpenGL context
        window = glfwCreateWindow(1, 1, "Headless", NULL, NULL);
        if (window == NULL) {
            glfwTerminate();
            assumeTrue(false, "Failed to create GLFW window - OpenGL tests will be skipped");
            return;
        }
        
        // Make context current
        glfwMakeContextCurrent(window);
        
        // Create capabilities
        GL.createCapabilities();
        glInitialized = true;
        
        // Verify compute shader support
        int maxWorkGroupCount = glGetInteger(GL_MAX_COMPUTE_WORK_GROUP_COUNT);
        if (maxWorkGroupCount == 0) {
            assumeTrue(false, "Compute shaders not supported - tests will be skipped");
        }
        
        // Log GPU information
        String vendor = glGetString(GL_VENDOR);
        String renderer = glGetString(GL_RENDERER);
        String version = glGetString(GL_VERSION);
        
        log.info("OpenGL Context Created:");
        log.info("  Vendor: {}", vendor);
        log.info("  Renderer: {}", renderer);
        log.info("  Version: {}", version);
        
        // Detect software rendering
        if (renderer != null && 
            (renderer.toLowerCase().contains("llvmpipe") ||
             renderer.toLowerCase().contains("software"))) {
            log.warn("Software renderer detected - tests may be slow");
        }
    }
    
    @AfterEach
    protected void cleanupGLContext() {
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }
        
        if (glInitialized) {
            glfwTerminate();
            glInitialized = false;
        }
        
        // Clear error callback
        var callback = glfwSetErrorCallback(null);
        if (callback != null) {
            callback.free();
        }
    }
    
    /**
     * Compile a compute shader from source.
     */
    protected int compileComputeShader(String source) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, source);
        glCompileShader(shader);
        
        // Check compilation status
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Compute shader compilation failed:\n" + log);
        }
        
        return shader;
    }
    
    /**
     * Create and link a compute program.
     */
    protected int createComputeProgram(int computeShader) {
        int program = glCreateProgram();
        glAttachShader(program, computeShader);
        glLinkProgram(program);
        
        // Check link status
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new RuntimeException("Program linking failed:\n" + log);
        }
        
        return program;
    }
    
    /**
     * Create a storage buffer (SSBO).
     */
    protected int createStorageBuffer(ByteBuffer data) {
        int ssbo = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, data, GL_STATIC_DRAW);
        return ssbo;
    }
    
    /**
     * Create a texture for compute shader output.
     */
    protected int createOutputTexture(int width, int height) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        return texture;
    }
    
    /**
     * Read back texture data for validation.
     */
    protected ByteBuffer readbackTexture(int texture, int width, int height) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glBindTexture(GL_TEXTURE_2D, texture);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }
    
    /**
     * Check if hardware GPU is available (not software rendering).
     */
    protected boolean hasHardwareGPU() {
        if (!glInitialized) return false;
        
        String renderer = glGetString(GL_RENDERER);
        if (renderer == null) return false;
        
        String rendererLower = renderer.toLowerCase();
        
        // Check for known hardware vendors
        return rendererLower.contains("nvidia") ||
               rendererLower.contains("amd") ||
               rendererLower.contains("radeon") ||
               rendererLower.contains("intel") ||
               rendererLower.contains("apple") ||
               rendererLower.contains("metal");
    }
}
```

### 2.2 ESVO OpenGL Compute Tests

Test the actual ESVO implementation:

**Location**: `render/src/test/java/com/hellblazer/luciferase/esvo/gpu/ESVOGLComputeTest.java`

```java
package com.hellblazer.luciferase.esvo.gpu;

import com.hellblazer.luciferase.gpu.test.GLComputeHeadlessTest;
import com.hellblazer.luciferase.esvo.gpu.ComputeShaderRenderer;
import com.hellblazer.luciferase.esvo.gpu.OctreeGPUMemory;
import org.junit.jupiter.api.*;
import javax.vecmath.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GPU tests for ESVO compute shader implementation.
 * These tests require actual OpenGL support and won't run in CI.
 */
@Tag("gpu")
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ESVOGLComputeTest extends GLComputeHeadlessTest {
    
    private ComputeShaderRenderer renderer;
    private OctreeGPUMemory octreeMemory;
    
    @BeforeEach
    void setupRenderer() {
        renderer = new ComputeShaderRenderer(256, 256);
        renderer.initialize();
        
        octreeMemory = new OctreeGPUMemory();
        octreeMemory.allocate(1024);
    }
    
    @AfterEach
    void cleanupRenderer() {
        if (renderer != null) {
            renderer.dispose();
        }
        if (octreeMemory != null) {
            octreeMemory.dispose();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Test compute shader compilation")
    void testShaderCompilation() {
        // Shader compilation happens in initialize()
        // If we get here without exception, compilation succeeded
        assertNotNull(renderer);
        assertEquals(256, renderer.getFrameWidth());
        assertEquals(256, renderer.getFrameHeight());
    }
    
    @Test
    @Order(2)
    @DisplayName("Test octree GPU memory upload")
    void testOctreeUpload() {
        OctreeNode[] testNodes = ESVOTestDataGenerator.generateSimpleOctree();
        octreeMemory.upload(testNodes);
        
        // Verify upload succeeded (no exceptions)
        assertTrue(octreeMemory.getNodeCount() > 0);
    }
    
    @Test
    @Order(3)
    @DisplayName("Test ray traversal on GPU")
    void testGPURayTraversal() {
        // Generate test scene
        OctreeNode[] testOctree = ESVOTestDataGenerator.generateCornellBox();
        octreeMemory.upload(testOctree);
        
        // Setup camera
        Matrix4f view = createLookAtMatrix(
            new Vector3f(1.5f, 1.5f, 0.5f),  // eye
            new Vector3f(1.5f, 1.5f, 1.5f),  // center
            new Vector3f(0, 1, 0)            // up
        );
        
        Matrix4f proj = createPerspectiveMatrix(60.0f, 1.0f, 0.1f, 100.0f);
        Matrix4f objectToWorld = new Matrix4f();
        objectToWorld.setIdentity();
        Matrix4f octreeToObject = CoordinateSpace.createOctreeToObjectMatrix(
            new Vector3f(0, 0, 0), 2.0f
        );
        
        // Render frame
        renderer.renderFrame(octreeMemory, view, proj, objectToWorld, octreeToObject);
        
        // Read back and validate
        ByteBuffer pixels = readbackTexture(renderer.getOutputTexture(), 256, 256);
        
        // Check center pixel (should hit something)
        int centerIdx = (128 * 256 + 128) * 4;
        float r = (pixels.get(centerIdx) & 0xFF) / 255.0f;
        float g = (pixels.get(centerIdx + 1) & 0xFF) / 255.0f;
        float b = (pixels.get(centerIdx + 2) & 0xFF) / 255.0f;
        
        // Normal should not be black (0,0,0) if we hit something
        assertTrue(r > 0 || g > 0 || b > 0, "Center pixel should hit octree");
    }
    
    @Test
    @Order(4)
    @DisplayName("Test performance benchmarks")
    @EnabledIf("hasHardwareGPU")
    void testPerformance() {
        OctreeNode[] largeOctree = ESVOTestDataGenerator.generateLargeOctree(10000);
        octreeMemory.upload(largeOctree);
        
        // Warm-up
        for (int i = 0; i < 10; i++) {
            renderer.renderFrame(...);
        }
        
        // Benchmark
        long startTime = System.nanoTime();
        int iterations = 100;
        
        for (int i = 0; i < iterations; i++) {
            renderer.renderFrame(...);
            glFinish(); // Ensure GPU completion
        }
        
        long elapsed = System.nanoTime() - startTime;
        double fps = iterations * 1_000_000_000.0 / elapsed;
        
        log.info("Performance: {:.2f} FPS at 256x256", fps);
        
        // Performance threshold (adjust based on hardware)
        assertTrue(fps > 30, "Performance should exceed 30 FPS");
    }
}
```

## Phase 3: Cross-Validation Framework

### 3.1 Algorithm Equivalence Testing

Ensure OpenCL and OpenGL implementations produce identical results:

**Location**: `gpu-test-framework/src/main/java/com/hellblazer/luciferase/gpu/test/esvo/ESVOCrossValidator.java`

```java
package com.hellblazer.luciferase.gpu.test.esvo;

/**
 * Cross-validation between OpenCL and OpenGL implementations.
 * Ensures algorithmic equivalence between the two APIs.
 */
public class ESVOCrossValidator {
    
    private static final float EPSILON = 0.001f;
    
    /**
     * Validate that OpenCL and OpenGL produce identical results.
     */
    public static void validateEquivalence(
            float[] openclOutput,
            float[] openglOutput,
            String testCase) {
        
        assertEquals(openclOutput.length, openglOutput.length,
            "Output sizes must match");
        
        int differences = 0;
        float maxDiff = 0;
        
        for (int i = 0; i < openclOutput.length; i++) {
            float diff = Math.abs(openclOutput[i] - openglOutput[i]);
            if (diff > EPSILON) {
                differences++;
                maxDiff = Math.max(maxDiff, diff);
            }
        }
        
        if (differences > 0) {
            double errorRate = (double)differences / openclOutput.length * 100;
            fail(String.format(
                "%s: %d differences (%.2f%%), max diff: %.6f",
                testCase, differences, errorRate, maxDiff
            ));
        }
    }
    
    /**
     * Generate detailed difference report for debugging.
     */
    public static String generateDifferenceReport(
            float[] expected,
            float[] actual,
            int width, int height) {
        
        StringBuilder report = new StringBuilder();
        report.append("Difference Report\n");
        report.append("=================\n");
        
        // Find regions with differences
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 4;
                
                float diffR = Math.abs(expected[idx] - actual[idx]);
                float diffG = Math.abs(expected[idx+1] - actual[idx+1]);
                float diffB = Math.abs(expected[idx+2] - actual[idx+2]);
                
                if (diffR > EPSILON || diffG > EPSILON || diffB > EPSILON) {
                    report.append(String.format(
                        "Pixel [%d,%d]: Expected(%.3f,%.3f,%.3f) Actual(%.3f,%.3f,%.3f)\n",
                        x, y,
                        expected[idx], expected[idx+1], expected[idx+2],
                        actual[idx], actual[idx+1], actual[idx+2]
                    ));
                }
            }
        }
        
        return report.toString();
    }
}
```

## Phase 4: Test Data Generation

### 4.1 Standardized Test Scenes

Create reproducible test data:

**Location**: `gpu-test-framework/src/main/java/com/hellblazer/luciferase/gpu/test/esvo/ESVOTestDataGenerator.java`

```java
package com.hellblazer.luciferase.gpu.test.esvo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.*;

/**
 * Generate standardized test data for ESVO validation.
 */
public class ESVOTestDataGenerator {
    
    /**
     * Generate a simple octree with a single voxel.
     */
    public static OctreeNode[] generateSimpleOctree() {
        OctreeNode[] nodes = new OctreeNode[1];
        nodes[0] = new OctreeNode();
        nodes[0].childDescriptor = 0x00FF00FF; // All children valid, all leaves
        nodes[0].contourDescriptor = 0;
        return nodes;
    }
    
    /**
     * Generate Cornell Box scene.
     */
    public static OctreeNode[] generateCornellBox() {
        // Standard Cornell Box with:
        // - White walls
        // - Red left wall
        // - Green right wall
        // - White light on ceiling
        // - Two boxes on floor
        
        // Implementation details...
        return buildCornellBoxOctree();
    }
    
    /**
     * Generate large random octree for performance testing.
     */
    public static OctreeNode[] generateLargeOctree(int nodeCount) {
        OctreeNode[] nodes = new OctreeNode[nodeCount];
        
        // Build balanced octree with specified node count
        // ...
        
        return nodes;
    }
    
    /**
     * Save test data to binary file for regression testing.
     */
    public static void saveTestData(OctreeNode[] nodes, String filename) 
            throws IOException {
        
        try (DataOutputStream dos = new DataOutputStream(
                new FileOutputStream(filename))) {
            
            dos.writeInt(nodes.length);
            
            for (OctreeNode node : nodes) {
                dos.writeInt(node.childDescriptor);
                dos.writeInt(node.contourDescriptor);
            }
        }
    }
    
    /**
     * Load test data from binary file.
     */
    public static OctreeNode[] loadTestData(String filename) 
            throws IOException {
        
        try (DataInputStream dis = new DataInputStream(
                new FileInputStream(filename))) {
            
            int nodeCount = dis.readInt();
            OctreeNode[] nodes = new OctreeNode[nodeCount];
            
            for (int i = 0; i < nodeCount; i++) {
                nodes[i] = new OctreeNode();
                nodes[i].childDescriptor = dis.readInt();
                nodes[i].contourDescriptor = dis.readInt();
            }
            
            return nodes;
        }
    }
    
    /**
     * Generate expected output for validation.
     * This is a CPU reference implementation.
     */
    public static float[] generateExpectedOutput(
            OctreeNode[] octree,
            Matrix4f view,
            Matrix4f proj,
            int width,
            int height) {
        
        float[] output = new float[width * height * 4];
        
        // CPU ray traversal implementation
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Ray ray = generateRay(x, y, width, height, view, proj);
                Vector3f hitNormal = traverseOctreeCPU(octree, ray);
                
                int idx = (y * width + x) * 4;
                output[idx] = hitNormal.x * 0.5f + 0.5f;
                output[idx+1] = hitNormal.y * 0.5f + 0.5f;
                output[idx+2] = hitNormal.z * 0.5f + 0.5f;
                output[idx+3] = 1.0f;
            }
        }
        
        return output;
    }
}
```

## Phase 5: CI/CD Integration

### 5.1 Maven Configuration

Update pom.xml for dual-API testing:

**Location**: `gpu-test-framework/pom.xml`

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
                <systemPropertyVariables>
                    <java.awt.headless>true</java.awt.headless>
                    <lwjgl.opencl.explicitInit>true</lwjgl.opencl.explicitInit>
                </systemPropertyVariables>
            </configuration>
            <executions>
                <!-- Default: OpenCL validation tests (run in CI) -->
                <execution>
                    <id>opencl-tests</id>
                    <goals><goal>test</goal></goals>
                    <configuration>
                        <groups>opencl,validation</groups>
                        <excludedGroups>gpu,integration</excludedGroups>
                    </configuration>
                </execution>
                
                <!-- OpenGL GPU tests (optional, requires real GPU) -->
                <execution>
                    <id>opengl-tests</id>
                    <goals><goal>test</goal></goals>
                    <configuration>
                        <groups>gpu,integration</groups>
                        <skip>${skip.gpu.tests}</skip>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>

<profiles>
    <!-- Profile for running all GPU tests -->
    <profile>
        <id>gpu-all</id>
        <properties>
            <skip.gpu.tests>false</skip.gpu.tests>
        </properties>
    </profile>
    
    <!-- Profile for CI environments -->
    <profile>
        <id>ci</id>
        <activation>
            <property>
                <name>env.CI</name>
                <value>true</value>
            </property>
        </activation>
        <properties>
            <skip.gpu.tests>true</skip.gpu.tests>
        </properties>
    </profile>
</profiles>
```

### 5.2 GitHub Actions Workflow

Configure CI/CD pipeline:

**Location**: `.github/workflows/gpu-tests.yml`

```yaml
name: GPU Testing Pipeline

on: [push, pull_request]

jobs:
  # OpenCL validation tests - run on all platforms
  opencl-validation:
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest, windows-latest]
    
    runs-on: ${{ matrix.os }}
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 24
      uses: actions/setup-java@v3
      with:
        java-version: '24'
        distribution: 'temurin'
    
    - name: Cache Maven dependencies
      uses: actions/cache@v3
      with:
        path: ~/.m2
        key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
    
    - name: Run OpenCL Validation Tests
      run: mvn test -pl gpu-test-framework -Dgroups=opencl,validation
      continue-on-error: true  # Tests may skip if no OpenCL
    
    - name: Upload Test Results
      if: always()
      uses: actions/upload-artifact@v3
      with:
        name: opencl-test-results-${{ matrix.os }}
        path: |
          gpu-test-framework/target/surefire-reports
          render/target/surefire-reports
  
  # OpenGL GPU tests - only on self-hosted runners
  opengl-gpu-tests:
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: [self-hosted, gpu]  # Requires self-hosted runner
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Run OpenGL GPU Tests
      run: |
        export DISPLAY=:0  # May need X display
        mvn test -Pgpu-all -pl gpu-test-framework,render
    
    - name: Performance Report
      run: |
        echo "## GPU Performance Results" >> $GITHUB_STEP_SUMMARY
        cat render/target/performance-report.md >> $GITHUB_STEP_SUMMARY
```

## Phase 6: Documentation and Usage

### 6.1 Test Execution Guide

```bash
# Run OpenCL validation tests (CI-compatible)
mvn test -Dgroups=opencl,validation

# Run OpenGL GPU tests (requires real GPU)
mvn test -Pgpu-all

# Run specific test class
mvn test -Dtest=ESVOOpenCLValidator

# Run with verbose output
mvn test -Dgroups=opencl -Dorg.slf4j.simpleLogger.defaultLogLevel=debug

# Skip GPU tests in CI
mvn test -Dskip.gpu.tests=true
```

### 6.2 Development Workflow

1. **Algorithm Development**:
   - Implement in OpenCL first for easy testing
   - Validate with ESVOOpenCLValidator
   - Port to GLSL compute shader

2. **Cross-Validation**:
   - Run both OpenCL and OpenGL versions
   - Compare outputs with ESVOCrossValidator
   - Debug differences with detailed reports

3. **Performance Tuning**:
   - Benchmark with OpenGL tests
   - Profile with GPU tools
   - Optimize critical paths

## Success Metrics

1. **OpenCL Validation**: 100% algorithm coverage
2. **Cross-API Equivalence**: <0.1% pixel difference
3. **CI Integration**: Tests run on all platforms
4. **Performance**: >60 FPS at 512x512 on hardware GPU
5. **Code Coverage**: >80% for GPU components

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| No GPU in CI | Use OpenCL validation with mock fallback |
| Platform differences | Test on Linux/macOS/Windows |
| Driver bugs | Document known issues, provide workarounds |
| Performance regression | Automated benchmarking with thresholds |

## Conclusion

This dual-API approach provides:
- **Algorithmic validation** via OpenCL in CI environments
- **Production testing** via OpenGL on real GPUs
- **Cross-validation** to ensure correctness
- **Performance benchmarking** for optimization

The framework leverages existing CICompatibleGPUTest for OpenCL while adding new GLComputeHeadlessTest for OpenGL, providing comprehensive GPU testing coverage.
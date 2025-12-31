package com.hellblazer.luciferase.esvo.opencl;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.gpu.ESVTOpenCLRenderer;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenCL validation tests for ESVO using the gpu-support framework.
 * These tests validate that OpenCL works correctly on this platform.
 *
 * <p>Run with: {@code RUN_GPU_TESTS=true ./mvnw test -Dtest=ESVOOpenCLValidatorTest}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
public class ESVOOpenCLValidatorTest {

    private boolean openclAvailable;

    @BeforeAll
    void setup() {
        // Use the same approach as ESVTOpenCLRenderer for safe initialization
        openclAvailable = ESVTOpenCLRenderer.isOpenCLAvailable();
        System.out.println("OpenCL available: " + openclAvailable);
    }

    @Test
    @DisplayName("OpenCL context initializes successfully")
    void testContextInitialization() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        // Use ESVTOpenCLRenderer to test OpenCL context - avoids direct context manipulation
        try (var renderer = new ESVTOpenCLRenderer(64, 64)) {
            assertFalse(renderer.isInitialized(), "Not initialized before initialize() call");
            renderer.initialize();
            assertTrue(renderer.isInitialized(), "Should be initialized after initialize()");
        }
    }

    @Test
    @DisplayName("OpenCL kernel compilation via ESVT")
    void testKernelCompilation() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        // ESVT kernel compiles as part of initialization
        try (var renderer = new ESVTOpenCLRenderer(64, 64)) {
            renderer.initialize();
            // If we get here without exception, kernel compiled successfully
            assertTrue(renderer.isInitialized(), "Kernel compiled and renderer initialized");
        }
    }

    @Test
    @DisplayName("OpenCL kernel execution via ESVT renderer")
    void testKernelExecution() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        try (var renderer = new ESVTOpenCLRenderer(64, 64)) {
            renderer.initialize();

            // Create minimal test data using ESVTBuilder
            var testData = createMinimalTestData();
            renderer.uploadData(testData);

            var cameraPos = new javax.vecmath.Vector3f(2.0f, 2.0f, 2.0f);
            var lookAt = new javax.vecmath.Vector3f(0.5f, 0.5f, 0.5f);

            // This executes the OpenCL kernel
            assertDoesNotThrow(() -> renderer.renderFrame(cameraPos, lookAt, 60.0f));

            // Output buffer should be available
            var output = renderer.getOutputImage();
            assertNotNull(output, "Output image should be available");
            assertEquals(64 * 64 * 4, output.remaining(), "Output should be RGBA for all pixels");
        }
    }

    @Test
    @DisplayName("OpenCL performance - throughput measurement")
    void testPerformance() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        try (var renderer = new ESVTOpenCLRenderer(256, 256)) {
            renderer.initialize();

            // Need data for rendering
            var testData = createMinimalTestData();
            renderer.uploadData(testData);

            var cameraPos = new javax.vecmath.Vector3f(2.0f, 2.0f, 2.0f);
            var lookAt = new javax.vecmath.Vector3f(0.5f, 0.5f, 0.5f);

            // Warmup
            for (int i = 0; i < 5; i++) {
                renderer.renderFrame(cameraPos, lookAt, 60.0f);
            }

            // Benchmark
            int iterations = 20;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                renderer.renderFrame(cameraPos, lookAt, 60.0f);
            }
            long elapsed = System.nanoTime() - start;

            double avgMs = (elapsed / 1_000_000.0) / iterations;
            double fps = 1000.0 / avgMs;

            System.out.println("\n=== OpenCL ESVO Performance ===");
            System.out.printf("Resolution: %dx%d%n", 256, 256);
            System.out.printf("Rays per frame: %d%n", 256 * 256);
            System.out.printf("Average frame time: %.2f ms%n", avgMs);
            System.out.printf("Estimated FPS: %.1f%n", fps);

            // Should achieve at least 10 FPS for 256x256
            assertTrue(fps >= 10.0, "Should achieve at least 10 FPS");
        }
    }

    @Test
    @DisplayName("ESVO kernel resource loading")
    void testESVOKernelResourceLoading() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        // Try to load the ESVO kernel from classpath
        try (var stream = getClass().getResourceAsStream("/kernels/esvo_ray_traversal.cl")) {
            assertNotNull(stream, "ESVO kernel should be loadable from classpath");
            var kernelSource = new String(stream.readAllBytes());
            assertFalse(kernelSource.isEmpty(), "ESVO kernel should not be empty");
            assertTrue(kernelSource.contains("traverseOctree"), "ESVO kernel should contain traverseOctree function");
            System.out.println("ESVO kernel loaded: " + kernelSource.length() + " characters");
        } catch (java.io.IOException e) {
            fail("Failed to read ESVO kernel: " + e.getMessage());
        }
    }

    /**
     * Create minimal test data using ESVTBuilder with a simple sphere pattern.
     */
    private ESVTData createMinimalTestData() {
        var random = new Random(42);
        var tetree = new Tetree<>(new SequentialLongIDGenerator());
        var builder = new ESVTBuilder();

        // Create a sphere pattern centered in the unit cube [0,1]
        float center = 0.5f;
        float radius = 0.3f;
        int samples = 500;

        for (int i = 0; i < samples; i++) {
            float x = random.nextFloat();
            float y = random.nextFloat();
            float z = random.nextFloat();

            float dx = x - center;
            float dy = y - center;
            float dz = z - center;
            float dist = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

            if (dist <= radius) {
                float scale = Constants.lengthAtLevel((byte) 0);
                tetree.insert(new Point3f(x * scale, y * scale, z * scale), (byte) 4, "Entity" + i);
            }
        }

        return builder.build(tetree);
    }
}

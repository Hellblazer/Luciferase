/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.esvt.gpu;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ESVTOpenCLRenderer - OpenCL-based GPU raycast renderer.
 *
 * <p>Run with: {@code RUN_GPU_TESTS=true ./mvnw test -Dtest=ESVTOpenCLRendererTest}
 *
 * <p><b>Platform Notes:</b>
 * <ul>
 *   <li>Apple Silicon Macs (M1/M2/M3/M4): OpenCL is NOT available - Apple removed
 *       OpenCL support in favor of Metal. Tests will be skipped.</li>
 *   <li>Intel Macs: OpenCL may be available but deprecated since macOS 10.14.</li>
 *   <li>Linux/Windows: OpenCL requires appropriate GPU drivers.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
class ESVTOpenCLRendererTest {

    private ESVTOpenCLRenderer renderer;
    private ESVTData testData;
    private boolean openclAvailable;

    @BeforeAll
    void setup() {
        openclAvailable = ESVTOpenCLRenderer.isOpenCLAvailable();
        System.out.println("OpenCL available: " + openclAvailable);

        if (openclAvailable) {
            // Create test ESVT data - simple sphere
            testData = createTestSphere(4);
            System.out.println("Created test data: " + testData);
        }
    }

    @AfterAll
    void cleanup() {
        if (renderer != null && !renderer.isDisposed()) {
            renderer.close();
        }
    }

    @Test
    @DisplayName("OpenCL availability check")
    void testOpenCLAvailability() {
        // This test always runs to report OpenCL status
        System.out.println("=== OpenCL Availability Test ===");
        boolean available = ESVTOpenCLRenderer.isOpenCLAvailable();
        System.out.println("OpenCL GPU available: " + available);

        if (!available) {
            System.out.println("OpenCL is not available on this system.");
            System.out.println("This could be due to:");
            System.out.println("  - No GPU with OpenCL support");
            System.out.println("  - Missing OpenCL drivers");
            System.out.println("  - Sandbox restrictions (try RUN_GPU_TESTS=true)");
        }

        // Don't fail - just report
        assertTrue(true, "OpenCL status reported");
    }

    @Test
    @DisplayName("Initialize OpenCL renderer")
    void testInitialize() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        renderer = new ESVTOpenCLRenderer(256, 256);
        assertFalse(renderer.isInitialized());
        assertFalse(renderer.isDisposed());

        renderer.initialize();

        assertTrue(renderer.isInitialized());
        assertFalse(renderer.isDisposed());
        assertEquals(256, renderer.getFrameWidth());
        assertEquals(256, renderer.getFrameHeight());
    }

    @Test
    @DisplayName("Upload ESVT data to GPU")
    void testUploadData() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        if (renderer == null || renderer.isDisposed()) {
            renderer = new ESVTOpenCLRenderer(256, 256);
            renderer.initialize();
        }

        assertDoesNotThrow(() -> renderer.uploadData(testData));
    }

    @Test
    @DisplayName("Render frame produces output")
    void testRenderFrame() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        if (renderer == null || renderer.isDisposed()) {
            renderer = new ESVTOpenCLRenderer(256, 256);
            renderer.initialize();
            renderer.uploadData(testData);
        }

        // Render a frame
        var cameraPos = new Vector3f(2.0f, 2.0f, 2.0f);
        var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);
        renderer.renderFrame(cameraPos, lookAt, 60.0f);

        // Get output image
        ByteBuffer output = renderer.getOutputImage();
        assertNotNull(output);
        assertEquals(256 * 256 * 4, output.remaining());

        // Check that we have some non-background pixels (hits)
        int hitCount = 0;
        output.rewind();
        for (int i = 0; i < 256 * 256; i++) {
            byte r = output.get();
            byte g = output.get();
            byte b = output.get();
            byte a = output.get();

            // Background is (20, 20, 30, 255)
            if (r != 20 || g != 20 || b != 30) {
                hitCount++;
            }
        }

        System.out.println("Pixels with hits: " + hitCount + " / " + (256 * 256));
        assertTrue(hitCount > 0, "Should have some ray hits");
    }

    @Test
    @DisplayName("Dispose releases resources")
    void testDispose() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        var localRenderer = new ESVTOpenCLRenderer(128, 128);
        localRenderer.initialize();
        assertTrue(localRenderer.isInitialized());
        assertFalse(localRenderer.isDisposed());

        localRenderer.close();

        assertTrue(localRenderer.isDisposed());
        assertFalse(localRenderer.isInitialized());
    }

    @Test
    @DisplayName("Render multiple frames")
    void testMultipleFrames() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        if (renderer == null || renderer.isDisposed()) {
            renderer = new ESVTOpenCLRenderer(256, 256);
            renderer.initialize();
            renderer.uploadData(testData);
        }

        // Render multiple frames with different camera positions
        var positions = new Vector3f[] {
            new Vector3f(2, 2, 2),
            new Vector3f(-2, 2, 2),
            new Vector3f(2, -2, 2),
            new Vector3f(2, 2, -2),
            new Vector3f(0, 3, 0)
        };

        var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);

        for (int i = 0; i < positions.length; i++) {
            long start = System.nanoTime();
            renderer.renderFrame(positions[i], lookAt, 60.0f);
            long elapsed = System.nanoTime() - start;

            ByteBuffer output = renderer.getOutputImage();
            assertNotNull(output);

            System.out.printf("Frame %d: %.2fms%n", i, elapsed / 1_000_000.0);
        }
    }

    @Test
    @DisplayName("Performance benchmark")
    void testPerformance() {
        Assumptions.assumeTrue(openclAvailable, "OpenCL not available");

        // Use larger resolution for meaningful benchmark
        var benchRenderer = new ESVTOpenCLRenderer(512, 512);
        benchRenderer.initialize();
        benchRenderer.uploadData(testData);

        var cameraPos = new Vector3f(2, 2, 2);
        var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);

        // Warmup
        for (int i = 0; i < 5; i++) {
            benchRenderer.renderFrame(cameraPos, lookAt, 60.0f);
        }

        // Benchmark
        int iterations = 20;
        long totalTime = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            benchRenderer.renderFrame(cameraPos, lookAt, 60.0f);
            totalTime += System.nanoTime() - start;
        }

        double avgMs = totalTime / (iterations * 1_000_000.0);
        double fps = 1000.0 / avgMs;

        System.out.println("\n=== OpenCL Renderer Performance ===");
        System.out.printf("Resolution: %dx%d%n", 512, 512);
        System.out.printf("Rays per frame: %d%n", 512 * 512);
        System.out.printf("Average frame time: %.2fms%n", avgMs);
        System.out.printf("Estimated FPS: %.1f%n", fps);

        benchRenderer.close();

        // Performance target: at least 10 FPS for 512x512
        assertTrue(fps >= 10.0, "Should achieve at least 10 FPS");
    }

    /**
     * Create a simple sphere voxel pattern for testing.
     */
    private ESVTData createTestSphere(int depth) {
        var random = new Random(42);
        var tetree = new Tetree<>(new SequentialLongIDGenerator());
        var builder = new ESVTBuilder();

        // Create a sphere pattern by inserting entities
        float maxCoord = Constants.lengthAtLevel((byte) 0) * 0.8f;
        float center = maxCoord / 2.0f;
        float radius = maxCoord / 3.0f;

        int inserted = 0;
        int samples = 500; // Number of samples to try

        for (int i = 0; i < samples; i++) {
            // Random point in cube
            float x = random.nextFloat() * maxCoord + 10;
            float y = random.nextFloat() * maxCoord + 10;
            float z = random.nextFloat() * maxCoord + 10;

            // Check if inside sphere
            float dx = x - center;
            float dy = y - center;
            float dz = z - center;
            float dist = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

            if (dist <= radius) {
                tetree.insert(new Point3f(x, y, z), (byte) depth, "Entity" + i);
                inserted++;
            }
        }

        System.out.println("Inserted " + inserted + " entities for sphere at depth " + depth);

        return builder.build(tetree);
    }
}

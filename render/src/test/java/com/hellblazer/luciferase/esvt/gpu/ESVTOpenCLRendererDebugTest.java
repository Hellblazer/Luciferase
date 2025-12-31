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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to examine GPU rendering pipeline output directly without UI.
 * Helps diagnose the black screen / peachy color issues.
 *
 * Run with: RUN_GPU_TESTS=true mvn test -Dtest=ESVTOpenCLRendererDebugTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_GPU_TESTS", matches = "true")
public class ESVTOpenCLRendererDebugTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;

    private ESVTOpenCLRenderer renderer;
    private ESVTData testData;

    @BeforeAll
    void setup() {
        Assumptions.assumeTrue(ESVTOpenCLRenderer.isOpenCLAvailable(), "OpenCL not available");

        // Build simple ESVT data - single point at center of S0 tetrahedron
        testData = buildSimpleESVT();

        renderer = new ESVTOpenCLRenderer(WIDTH, HEIGHT);
        renderer.initialize();
        renderer.uploadData(testData);

        System.out.println("=== ESVT Data Info ===");
        System.out.println("Nodes: " + testData.nodeCount());
        System.out.println("Leaves: " + testData.leafCount());
        System.out.println("Max depth: " + testData.maxDepth());

        // Print root node info
        var nodes = testData.nodesToByteBuffer();
        int childDescriptor = nodes.getInt(0);
        int contourDescriptor = nodes.getInt(4);
        int childMask = (childDescriptor >> 8) & 0xFF;
        int leafMask = childDescriptor & 0xFF;
        int tetType = (contourDescriptor >> 1) & 0x7;
        boolean valid = (childDescriptor & 0x80000000) != 0;

        System.out.println("Root node:");
        System.out.println("  Valid: " + valid);
        System.out.println("  TetType: " + tetType);
        System.out.println("  ChildMask: 0x" + Integer.toHexString(childMask) + " (" + Integer.toBinaryString(childMask) + ")");
        System.out.println("  LeafMask: 0x" + Integer.toHexString(leafMask) + " (" + Integer.toBinaryString(leafMask) + ")");

        // Print S0 tetrahedron vertices for reference
        System.out.println("\n=== S0 Tetrahedron Type " + tetType + " ===");
        float[][] s0Vertices = {
            {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {1, 1, 1}  // Type 0
        };
        for (int i = 0; i < 4; i++) {
            System.out.printf("  v%d: (%.2f, %.2f, %.2f)%n", i, s0Vertices[i][0], s0Vertices[i][1], s0Vertices[i][2]);
        }
        float centroidX = (0 + 1 + 1 + 1) / 4.0f;
        float centroidY = (0 + 0 + 0 + 1) / 4.0f;
        float centroidZ = (0 + 0 + 1 + 1) / 4.0f;
        System.out.printf("  Centroid: (%.2f, %.2f, %.2f)%n", centroidX, centroidY, centroidZ);
    }

    @AfterAll
    void teardown() {
        if (renderer != null) {
            renderer.close();
        }
    }

    @Test
    @DisplayName("Trace single ray through intersection logic")
    void testSingleRayTrace() {
        // Test ray-tetrahedron intersection on CPU to verify the algorithm
        // Type 0 tetrahedron vertices
        float[][] v = {
            {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {1, 1, 1}
        };

        // Camera at (2.25, 1.0, 0.5) looking at centroid (0.75, 0.25, 0.5)
        float[] rayOrigin = {2.25f, 1.0f, 0.5f};
        float[] rayDir = {0.75f - 2.25f, 0.25f - 1.0f, 0.5f - 0.5f};
        // Normalize direction
        float len = (float) Math.sqrt(rayDir[0]*rayDir[0] + rayDir[1]*rayDir[1] + rayDir[2]*rayDir[2]);
        rayDir[0] /= len;
        rayDir[1] /= len;
        rayDir[2] /= len;

        System.out.println("\n=== Single Ray Trace ===");
        System.out.printf("Ray origin: (%.3f, %.3f, %.3f)%n", rayOrigin[0], rayOrigin[1], rayOrigin[2]);
        System.out.printf("Ray direction: (%.3f, %.3f, %.3f)%n", rayDir[0], rayDir[1], rayDir[2]);
        System.out.println("\nTetrahedron vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.printf("  v%d: (%.2f, %.2f, %.2f)%n", i, v[i][0], v[i][1], v[i][2]);
        }

        // Test each face intersection (Möller-Trumbore)
        int[][] faces = {
            {1, 2, 3},  // Face 0: opposite v0
            {0, 2, 3},  // Face 1: opposite v1
            {0, 1, 3},  // Face 2: opposite v2
            {0, 1, 2}   // Face 3: opposite v3
        };

        System.out.println("\nFace intersections:");
        float tEntry = Float.MAX_VALUE;
        float tExit = Float.MIN_VALUE;
        int entryFace = -1;
        int exitFace = -1;

        for (int f = 0; f < 4; f++) {
            float[] fv0 = v[faces[f][0]];
            float[] fv1 = v[faces[f][1]];
            float[] fv2 = v[faces[f][2]];

            float[] result = intersectTriangleMT(rayOrigin, rayDir, fv0, fv1, fv2);
            boolean hit = result != null;

            System.out.printf("  Face %d (v%d,v%d,v%d): %s%n",
                f, faces[f][0], faces[f][1], faces[f][2],
                hit ? String.format("HIT at t=%.3f", result[0]) : "miss");

            if (hit) {
                if (result[0] < tEntry) {
                    tEntry = result[0];
                    entryFace = f;
                }
                if (result[0] > tExit) {
                    tExit = result[0];
                    exitFace = f;
                }
            }
        }

        System.out.println("\nTetrahedron intersection result:");
        if (entryFace >= 0 && exitFace >= 0) {
            System.out.printf("  Entry: face %d at t=%.3f%n", entryFace, tEntry);
            System.out.printf("  Exit: face %d at t=%.3f%n", exitFace, tExit);
            float[] hitPoint = {
                rayOrigin[0] + rayDir[0] * tEntry,
                rayOrigin[1] + rayDir[1] * tEntry,
                rayOrigin[2] + rayDir[2] * tEntry
            };
            System.out.printf("  Hit point: (%.3f, %.3f, %.3f)%n", hitPoint[0], hitPoint[1], hitPoint[2]);
        } else {
            System.out.println("  NO INTERSECTION - RAY MISSES TETRAHEDRON");
        }
    }

    // CPU implementation of Möller-Trumbore intersection (same as kernel)
    private float[] intersectTriangleMT(float[] rayOrigin, float[] rayDir,
                                        float[] tv0, float[] tv1, float[] tv2) {
        float EPSILON = 1e-7f;

        float[] edge1 = {tv1[0] - tv0[0], tv1[1] - tv0[1], tv1[2] - tv0[2]};
        float[] edge2 = {tv2[0] - tv0[0], tv2[1] - tv0[1], tv2[2] - tv0[2]};

        float[] h = cross(rayDir, edge2);
        float a = dot(edge1, h);

        if (Math.abs(a) < EPSILON) {
            return null;  // Parallel
        }

        float f = 1.0f / a;
        float[] s = {rayOrigin[0] - tv0[0], rayOrigin[1] - tv0[1], rayOrigin[2] - tv0[2]};
        float u = f * dot(s, h);

        if (u < 0.0f || u > 1.0f) {
            return null;
        }

        float[] q = cross(s, edge1);
        float v = f * dot(rayDir, q);

        if (v < 0.0f || u + v > 1.0f) {
            return null;
        }

        float t = f * dot(edge2, q);

        if (t > EPSILON) {
            return new float[]{t, u, v};
        }

        return null;
    }

    private float[] cross(float[] a, float[] b) {
        return new float[]{
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }

    private float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    @Test
    @DisplayName("Render from diagnostic camera and analyze output")
    void testDiagnosticCameraRender() {
        // Use the same camera setup as the diagnostic mode in ESVTInspectorApp
        float centerX = 0.75f;
        float centerY = 0.25f;
        float centerZ = 0.5f;

        float radius = 1.5f;
        float angle = 0; // First frame angle

        float camX = centerX + radius * (float) Math.cos(angle);
        float camY = centerY + radius * 0.5f;
        float camZ = centerZ + radius * (float) Math.sin(angle);

        var cameraPos = new Vector3f(camX, camY, camZ);
        var lookAt = new Vector3f(centerX, centerY, centerZ);

        System.out.println("\n=== Camera Setup ===");
        System.out.printf("Camera position: (%.3f, %.3f, %.3f)%n", camX, camY, camZ);
        System.out.printf("Look at: (%.3f, %.3f, %.3f)%n", centerX, centerY, centerZ);

        // Calculate expected ray direction at center of screen
        float dx = centerX - camX;
        float dy = centerY - camY;
        float dz = centerZ - camZ;
        float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        System.out.printf("Expected center ray direction: (%.3f, %.3f, %.3f)%n", dx/len, dy/len, dz/len);

        // Render frame
        renderer.renderFrame(cameraPos, lookAt, 60.0f);

        // Analyze output buffer
        ByteBuffer output = renderer.getOutputImage();
        analyzeOutputBuffer(output, WIDTH, HEIGHT);
    }

    @Test
    @DisplayName("Render from inside tetrahedron")
    void testRenderFromInside() {
        // Camera inside the tetrahedron should definitely hit something
        var cameraPos = new Vector3f(0.75f, 0.25f, 0.5f);  // At centroid
        var lookAt = new Vector3f(1.0f, 0.5f, 0.5f);  // Looking toward v3 direction

        System.out.println("\n=== Camera INSIDE Tetrahedron ===");
        System.out.printf("Camera position: (%.3f, %.3f, %.3f)%n", cameraPos.x, cameraPos.y, cameraPos.z);
        System.out.printf("Look at: (%.3f, %.3f, %.3f)%n", lookAt.x, lookAt.y, lookAt.z);

        // Also trace center ray on CPU to verify
        float[] rayOrigin = {cameraPos.x, cameraPos.y, cameraPos.z};
        float[] rawDir = {lookAt.x - cameraPos.x, lookAt.y - cameraPos.y, lookAt.z - cameraPos.z};
        float len = (float) Math.sqrt(rawDir[0]*rawDir[0] + rawDir[1]*rawDir[1] + rawDir[2]*rawDir[2]);
        float[] rayDir = {rawDir[0]/len, rawDir[1]/len, rawDir[2]/len};
        System.out.printf("Center ray dir: (%.3f, %.3f, %.3f)%n", rayDir[0], rayDir[1], rayDir[2]);

        // CPU intersection test for this ray
        float[][] v = {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {1, 1, 1}};
        int[][] faces = {{1, 2, 3}, {0, 2, 3}, {0, 1, 3}, {0, 1, 2}};
        for (int f = 0; f < 4; f++) {
            float[] result = intersectTriangleMT(rayOrigin, rayDir, v[faces[f][0]], v[faces[f][1]], v[faces[f][2]]);
            System.out.printf("  CPU Face %d: %s%n", f, result != null ? String.format("t=%.3f", result[0]) : "miss");
        }

        renderer.renderFrame(cameraPos, lookAt, 60.0f);

        ByteBuffer output = renderer.getOutputImage();
        analyzeOutputBuffer(output, WIDTH, HEIGHT);
    }

    @Test
    @DisplayName("Render from multiple angles")
    void testMultipleAngles() {
        float centerX = 0.75f;
        float centerY = 0.25f;
        float centerZ = 0.5f;
        float radius = 1.5f;

        System.out.println("\n=== Multiple Angle Test ===");

        for (int i = 0; i < 8; i++) {
            float angle = (float) (2 * Math.PI * i / 8);
            float camX = centerX + radius * (float) Math.cos(angle);
            float camY = centerY + radius * 0.5f;
            float camZ = centerZ + radius * (float) Math.sin(angle);

            var cameraPos = new Vector3f(camX, camY, camZ);
            var lookAt = new Vector3f(centerX, centerY, centerZ);

            renderer.renderFrame(cameraPos, lookAt, 60.0f);

            ByteBuffer output = renderer.getOutputImage();
            var stats = computeStats(output, WIDTH, HEIGHT);

            System.out.printf("Angle %d (%.0f°): cam(%.2f, %.2f, %.2f) -> hits=%d, misses=%d, distinct=%d%n",
                i, Math.toDegrees(angle), camX, camY, camZ,
                stats.get("hits"), stats.get("misses"), stats.get("distinctColors"));
        }
    }

    private void analyzeOutputBuffer(ByteBuffer output, int width, int height) {
        output.rewind();

        Map<Integer, Integer> colorCounts = new HashMap<>();
        int hits = 0;
        int misses = 0;
        int totalPixels = width * height;

        // Expected miss color (dark blue background)
        byte missR = 20, missG = 20, missB = 30;

        // Diagnostic color detection
        // "Ray missed root" diagnostic: hitNormal=(1,0,0,1), distance=0.1 -> RGBA(251, 201, 155, 255)
        // "Ray hit root" diagnostic: hitNormal=(0,1,0,1), varying distance -> greenish colors
        int rayMissedRoot = 0;
        int rayHitRoot = 0;

        System.out.println("\n=== Output Buffer Analysis ===");
        System.out.println("Resolution: " + width + "x" + height + " = " + totalPixels + " pixels");

        // Sample specific pixels
        System.out.println("\nSample pixels:");
        int[] sampleIndices = {
            0,  // top-left
            width/2,  // top-center
            width * (height/2) + width/2,  // center
            width * (height/2),  // left-center
            width * (height/2) + width - 1,  // right-center
            totalPixels - 1  // bottom-right
        };
        String[] sampleNames = {"top-left", "top-center", "center", "left-center", "right-center", "bottom-right"};

        for (int i = 0; i < sampleIndices.length; i++) {
            int idx = sampleIndices[i] * 4;
            if (idx + 3 < output.capacity()) {
                int r = output.get(idx) & 0xFF;
                int g = output.get(idx + 1) & 0xFF;
                int b = output.get(idx + 2) & 0xFF;
                int a = output.get(idx + 3) & 0xFF;
                System.out.printf("  %s: RGBA(%d, %d, %d, %d)%n", sampleNames[i], r, g, b, a);
            }
        }

        // Count all pixels
        output.rewind();
        for (int i = 0; i < totalPixels; i++) {
            int r = output.get() & 0xFF;
            int g = output.get() & 0xFF;
            int b = output.get() & 0xFF;
            int a = output.get() & 0xFF;

            int color = (r << 24) | (g << 16) | (b << 8) | a;
            colorCounts.merge(color, 1, Integer::sum);

            // Check if it's a miss (background) or hit
            if (r == missR && g == missG && b == missB) {
                misses++;
            } else {
                hits++;
            }
        }

        System.out.println("\nPixel classification:");
        System.out.printf("  Hits: %d (%.1f%%)%n", hits, 100.0 * hits / totalPixels);
        System.out.printf("  Misses: %d (%.1f%%)%n", misses, 100.0 * misses / totalPixels);
        System.out.printf("  Distinct colors: %d%n", colorCounts.size());

        // Show top 10 most common colors
        System.out.println("\nMost common colors:");
        colorCounts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10)
            .forEach(e -> {
                int c = e.getKey();
                int r = (c >> 24) & 0xFF;
                int g = (c >> 16) & 0xFF;
                int b = (c >> 8) & 0xFF;
                int a = c & 0xFF;
                System.out.printf("  RGBA(%3d, %3d, %3d, %3d): %d pixels (%.1f%%)%n",
                    r, g, b, a, e.getValue(), 100.0 * e.getValue() / totalPixels);
            });

        // Interpret the results
        System.out.println("\n=== Interpretation ===");
        if (hits == 0) {
            System.out.println("ALL RAYS MISSED! Possible causes:");
            System.out.println("  - Camera too far from geometry");
            System.out.println("  - Ray generation incorrect");
            System.out.println("  - Intersection test failing");
            System.out.println("  - Node data not uploaded correctly");
        } else if (misses == 0) {
            System.out.println("ALL RAYS HIT! This suggests geometry covers entire view.");
        } else {
            System.out.println("Mixed hits/misses - geometry partially visible.");
        }

        // Check for the diagnostic colors
        boolean foundBlack = colorCounts.containsKey(0x000000FF);  // black with alpha
        boolean foundMagenta = false;
        boolean foundGreen = false;

        for (int color : colorCounts.keySet()) {
            int r = (color >> 24) & 0xFF;
            int g = (color >> 16) & 0xFF;
            int b = (color >> 8) & 0xFF;

            if (r > 200 && g < 50 && b > 200) foundMagenta = true;  // Invalid root
            if (r < 50 && g > 100 && b < 50) foundGreen = true;  // Hit root
        }

        if (foundMagenta) {
            System.out.println("MAGENTA pixels found - root node is INVALID!");
        }
        if (foundGreen) {
            System.out.println("GREEN pixels found - rays ARE hitting root tetrahedron.");
        }
    }

    private Map<String, Integer> computeStats(ByteBuffer output, int width, int height) {
        output.rewind();
        Map<Integer, Integer> colorCounts = new HashMap<>();
        int hits = 0;
        int misses = 0;
        int totalPixels = width * height;

        byte missR = 20, missG = 20, missB = 30;

        for (int i = 0; i < totalPixels; i++) {
            int r = output.get() & 0xFF;
            int g = output.get() & 0xFF;
            int b = output.get() & 0xFF;
            int a = output.get() & 0xFF;

            int color = (r << 24) | (g << 16) | (b << 8) | a;
            colorCounts.merge(color, 1, Integer::sum);

            if (r == missR && g == missG && b == missB) {
                misses++;
            } else {
                hits++;
            }
        }

        var result = new HashMap<String, Integer>();
        result.put("hits", hits);
        result.put("misses", misses);
        result.put("distinctColors", colorCounts.size());
        return result;
    }

    private ESVTData buildSimpleESVT() {
        var tetree = new Tetree<>(new SequentialLongIDGenerator());
        var builder = new ESVTBuilder();

        // Insert a few points to create a simple tree
        // Using positions that should be inside the S0 tetrahedron
        float scale = Constants.lengthAtLevel((byte) 0);

        // Insert at centroid-ish positions at low depth
        tetree.insert(new Point3f(0.5f * scale, 0.2f * scale, 0.3f * scale), (byte) 2, "p1");
        tetree.insert(new Point3f(0.6f * scale, 0.1f * scale, 0.4f * scale), (byte) 2, "p2");
        tetree.insert(new Point3f(0.7f * scale, 0.15f * scale, 0.35f * scale), (byte) 2, "p3");

        return builder.build(tetree);
    }
}

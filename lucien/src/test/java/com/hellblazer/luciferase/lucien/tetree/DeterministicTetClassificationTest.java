package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.benchmark.CIEnvironmentCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test the new deterministic tetrahedral classification algorithm. This will replace the non-deterministic "test all 6"
 * approach in locate().
 */
public class DeterministicTetClassificationTest {

    /**
     * Deterministic S0-S5 point classification using distance to tetrahedron centroids. This is the PRODUCTION
     * algorithm used in Tetree.locate().
     *
     * @param x normalized coordinate [0,1] within cube
     * @param y normalized coordinate [0,1] within cube
     * @param z normalized coordinate [0,1] within cube
     * @return tetrahedron type [0-5]
     */
    private static byte classifyPointInCube(float x, float y, float z) {
        // S0-S5 tetrahedron centroids (calculated from vertex averages)
        // S0: (0,0,0), (1,0,0), (1,1,0), (1,1,1) -> centroid (0.75, 0.5, 0.25)
        // S1: (0,0,0), (0,1,0), (1,1,0), (1,1,1) -> centroid (0.5, 0.75, 0.25)
        // S2: (0,0,0), (0,0,1), (1,0,1), (1,1,1) -> centroid (0.5, 0.25, 0.75)
        // S3: (0,0,0), (0,0,1), (0,1,1), (1,1,1) -> centroid (0.25, 0.5, 0.75)
        // S4: (0,0,0), (1,0,0), (1,0,1), (1,1,1) -> centroid (0.75, 0.25, 0.5)
        // S5: (0,0,0), (0,1,0), (0,1,1), (1,1,1) -> centroid (0.25, 0.75, 0.5)

        double[][] centroids = { { 0.75, 0.5, 0.25 }, // S0
                                 { 0.5, 0.75, 0.25 }, // S1
                                 { 0.5, 0.25, 0.75 }, // S2
                                 { 0.25, 0.5, 0.75 }, // S3
                                 { 0.75, 0.25, 0.5 }, // S4
                                 { 0.25, 0.75, 0.5 }  // S5
        };

        byte closestType = 0;
        double minDistanceSquared = Double.MAX_VALUE;

        for (byte type = 0; type < 6; type++) {
            double cx = centroids[type][0];
            double cy = centroids[type][1];
            double cz = centroids[type][2];

            // Use squared distance (faster, same relative ordering)
            double distanceSquared = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz);

            if (distanceSquared < minDistanceSquared) {
                minDistanceSquared = distanceSquared;
                closestType = type;
            }
        }

        return closestType;
    }

    @BeforeEach
    public void setup() {
        // Skip this test in CI environments
        assumeFalse(CIEnvironmentCheck.isRunningInCI(), "Continuous profiling test is disabled in CI environments");
        // Skip if not flagged
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("RUN_SPATIAL_INDEX_PERF_TESTS", "false")));
    }

    @Test
    void testAgainstCurrentContainment() {
        System.out.println("=== Testing Against Current S0-S5 Containment ===");

        byte level = 10;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);

        // Create all 6 tetrahedra
        Tet[] tets = new Tet[6];
        for (byte type = 0; type < 6; type++) {
            tets[type] = new Tet(0, 0, 0, level, type);
        }

        Random random = new Random(42);
        int totalTests = 1000;
        int correctPredictions = 0;
        int ambiguousPoints = 0;

        for (int i = 0; i < totalTests; i++) {
            // Generate random point in cube
            float x = random.nextFloat() * h;
            float y = random.nextFloat() * h;
            float z = random.nextFloat() * h;
            Point3f point = new Point3f(x, y, z);

            // Normalize to [0,1]
            float nx = x / h;
            float ny = y / h;
            float nz = z / h;

            // Get our prediction
            byte predicted = classifyPointInCube(nx, ny, nz);

            // Check current containment
            boolean predictedContains = tets[predicted].contains(point);

            // Count how many tets actually contain this point
            int containCount = 0;
            for (byte type = 0; type < 6; type++) {
                if (tets[type].contains(point)) {
                    containCount++;
                }
            }

            if (containCount > 1) {
                ambiguousPoints++;
            }

            if (predictedContains) {
                correctPredictions++;
            } else {
                // Debug failed predictions
                if (i < 10) { // Only show first 10 failures
                    System.out.printf("FAILED: Point (%.3f,%.3f,%.3f) predicted S%d but not contained, actually in: ",
                                      nx, ny, nz, predicted);
                    for (byte type = 0; type < 6; type++) {
                        if (tets[type].contains(point)) {
                            System.out.printf("S%d ", type);
                        }
                    }
                    System.out.println();
                }
            }
        }

        double accuracy = 100.0 * correctPredictions / totalTests;
        double ambiguityRate = 100.0 * ambiguousPoints / totalTests;

        System.out.printf("\\nResults: %.1f%% accuracy (%d/%d correct)\\n", accuracy, correctPredictions, totalTests);
        System.out.printf("Ambiguous points: %.1f%% (%d/%d)\\n", ambiguityRate, ambiguousPoints, totalTests);

        // Distance-based algorithm should achieve very high accuracy
        assertTrue(accuracy >= 98.0, "Expected ≥98% accuracy with distance-based method, got " + accuracy + "%");

        System.out.println("✓ Containment validation completed");
    }

    @Test
    void testBoundaryConditions() {
        System.out.println("=== Testing Boundary Conditions ===");

        // Test boundary conditions with distance-based results
        assertEquals(0, classifyPointInCube(0.6f, 0.5f, 0.4f), "Diagonal boundary, X-dominant -> S0");
        assertEquals(5, classifyPointInCube(0.4f, 0.6f, 0.5f), "Diagonal boundary, Y-dominant -> S5");
        assertEquals(3, classifyPointInCube(0.4f, 0.5f, 0.6f), "Diagonal boundary, Z-dominant -> S3");

        // Test coordinate ties (distance-based tie-breaking)
        assertEquals(0, classifyPointInCube(0.6f, 0.6f, 0.3f), "X==Y tie, upper -> S0");
        assertEquals(0, classifyPointInCube(0.4f, 0.4f, 0.3f), "X==Y tie, lower -> S0");
        assertEquals(2, classifyPointInCube(0.6f, 0.3f, 0.6f), "X==Z tie -> S2");
        assertEquals(3, classifyPointInCube(0.3f, 0.6f, 0.6f), "Y==Z tie -> S3");

        System.out.println("✓ Boundary condition tests passed");
    }

    @Test
    void testCompleteCoverage() {
        System.out.println("=== Testing Complete Coverage ===");

        // Test that every point in a regular grid gets exactly one classification
        int gridSize = 20;
        int totalPoints = 0;
        int[] typeCounts = new int[6];

        for (int i = 0; i <= gridSize; i++) {
            for (int j = 0; j <= gridSize; j++) {
                for (int k = 0; k <= gridSize; k++) {
                    float x = (float) i / gridSize;
                    float y = (float) j / gridSize;
                    float z = (float) k / gridSize;

                    byte type = classifyPointInCube(x, y, z);
                    assertTrue(type >= 0 && type <= 5, "Type must be in range [0,5]");

                    typeCounts[type]++;
                    totalPoints++;
                }
            }
        }

        System.out.println("Classification distribution:");
        for (int type = 0; type < 6; type++) {
            double percentage = 100.0 * typeCounts[type] / totalPoints;
            System.out.printf("  S%d: %d points (%.1f%%)\\n", type, typeCounts[type], percentage);
        }

        // Verify all points were classified
        int totalClassified = 0;
        for (int count : typeCounts) {
            totalClassified += count;
        }
        assertEquals(totalPoints, totalClassified, "All points must be classified");

        // Expect roughly even distribution (though won't be exactly equal due to boundary conditions)
        for (int type = 0; type < 6; type++) {
            double percentage = 100.0 * typeCounts[type] / totalPoints;
            assertTrue(percentage >= 5.0 && percentage <= 30.0,
                       "Type " + type + " has unreasonable distribution: " + percentage + "%");
        }

        System.out.println("✓ Complete coverage verified");
    }

    @Test
    void testDeterministicBehavior() {
        System.out.println("=== Testing Deterministic Behavior ===");

        Random random = new Random(12345); // Fixed seed

        // Test the same points multiple times
        for (int trial = 0; trial < 100; trial++) {
            float x = random.nextFloat();
            float y = random.nextFloat();
            float z = random.nextFloat();

            // Get classification multiple times
            byte result1 = classifyPointInCube(x, y, z);
            byte result2 = classifyPointInCube(x, y, z);
            byte result3 = classifyPointInCube(x, y, z);

            assertEquals(result1, result2, "Deterministic failure on trial " + trial);
            assertEquals(result2, result3, "Deterministic failure on trial " + trial);
        }

        System.out.println("✓ Deterministic behavior verified");
    }

    @Test
    void testKnownPoints() {
        System.out.println("=== Testing Known Point Classifications ===");

        // Test distance-based classification results (these are the actual correct results)
        assertEquals(0, classifyPointInCube(0.8f, 0.3f, 0.3f), "X-dominant point -> S0");
        assertEquals(1, classifyPointInCube(0.3f, 0.8f, 0.3f), "Y-dominant point -> S1");
        assertEquals(2, classifyPointInCube(0.3f, 0.3f, 0.8f), "Z-dominant point -> S2");

        // Test corner cases
        assertEquals(0, classifyPointInCube(0.0f, 0.0f, 0.0f), "Origin -> S0");
        assertEquals(0, classifyPointInCube(1.0f, 1.0f, 1.0f), "Opposite corner -> S0");
        assertEquals(0, classifyPointInCube(0.5f, 0.5f, 0.5f), "Center -> S0");

        System.out.println("✓ Known point classifications passed");
    }

    @Test
    void testPerformanceComparison() {
        System.out.println("=== Performance Comparison ===");

        byte level = 10;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);

        // Create all 6 tetrahedra for current method
        Tet[] tets = new Tet[6];
        for (byte type = 0; type < 6; type++) {
            tets[type] = new Tet(0, 0, 0, level, type);
        }

        Random random = new Random(42);
        int numTests = 10000;

        // Generate test points
        Point3f[] testPoints = new Point3f[numTests];
        for (int i = 0; i < numTests; i++) {
            testPoints[i] = new Point3f(random.nextFloat() * h, random.nextFloat() * h, random.nextFloat() * h);
        }

        // Test current method (iterate through all 6)
        long startTime = System.nanoTime();
        for (Point3f point : testPoints) {
            // Simulate current locate() method
            for (byte type = 0; type < 6; type++) {
                if (tets[type].contains(point)) {
                    break; // Found first match
                }
            }
        }
        long currentTime = System.nanoTime() - startTime;

        // Test new method (direct classification)
        startTime = System.nanoTime();
        for (Point3f point : testPoints) {
            float nx = point.x / h;
            float ny = point.y / h;
            float nz = point.z / h;
            classifyPointInCube(nx, ny, nz);
        }
        long newTime = System.nanoTime() - startTime;

        double speedup = (double) currentTime / newTime;

        System.out.printf("Current method: %.2f ms\\n", currentTime / 1_000_000.0);
        System.out.printf("New method:     %.2f ms\\n", newTime / 1_000_000.0);
        System.out.printf("Speedup:        %.1fx\\n", speedup);

        assertTrue(speedup >= 1.1, "Expected ≥1.1x speedup, got " + speedup + "x");

        System.out.println("✓ Performance improvement verified");
    }
}

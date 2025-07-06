package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for the new Tetree.locate() method with distance-based S0-S5 classification.
 * 
 * This validates the fix for the tetrahedral visualization containment issue by ensuring
 * the locate() method now provides deterministic, accurate point-to-tetrahedron assignment.
 */
public class TetreeLocateMethodTest {
    
    @Test
    void testLocateMethodAccuracy() {
        System.out.println("=== Testing Locate Method Accuracy ===");
        
        Tetree<LongEntityID, String> tetree = 
            new Tetree<>(new SequentialLongIDGenerator());
        
        // Test at multiple levels to ensure consistency
        byte[] testLevels = {5, 10, 15, 20};
        
        for (byte level : testLevels) {
            testLocateAtLevel(tetree, level);
        }
        
        System.out.println("✓ Locate method accuracy validated across all levels");
    }
    
    private void testLocateAtLevel(Tetree<?, ?> tetree, byte level) {
        System.out.printf("\nTesting level %d:\n", level);
        
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        Random random = new Random(42 + level); // Different seed per level
        
        int totalTests = 500;
        int successfulTests = 0;
        int multipleContainmentCases = 0;
        
        for (int i = 0; i < totalTests; i++) {
            // Generate random point within a cell
            float x = random.nextFloat() * cellSize;
            float y = random.nextFloat() * cellSize; 
            float z = random.nextFloat() * cellSize;
            Point3f point = new Point3f(x, y, z);
            
            // Get the locate() result
            Tet locatedTet = tetree.locate(point, level);
            assertNotNull(locatedTet, "locate() should never return null");
            assertEquals(level, locatedTet.l, "Returned tet should be at requested level");
            
            // Verify the located tet actually contains the point
            boolean contained = locatedTet.contains(point);
            if (contained) {
                successfulTests++;
            } else {
                // Debug failed case
                System.out.printf("FAILED: Point (%.3f,%.3f,%.3f) not contained by located tet type %d\n", 
                    x/cellSize, y/cellSize, z/cellSize, locatedTet.type);
                
                // Check which tetrahedra actually contain it
                System.out.print("Actually contained by: ");
                for (byte type = 0; type < 6; type++) {
                    Tet testTet = new Tet(locatedTet.x, locatedTet.y, locatedTet.z, level, type);
                    if (testTet.contains(point)) {
                        System.out.printf("S%d ", type);
                    }
                }
                System.out.println();
            }
            
            // Check for multiple containment (boundary cases)
            int containmentCount = 0;
            for (byte type = 0; type < 6; type++) {
                Tet testTet = new Tet(locatedTet.x, locatedTet.y, locatedTet.z, level, type);
                if (testTet.contains(point)) {
                    containmentCount++;
                }
            }
            if (containmentCount > 1) {
                multipleContainmentCases++;
            }
        }
        
        double accuracy = 100.0 * successfulTests / totalTests;
        double boundaryRate = 100.0 * multipleContainmentCases / totalTests;
        
        System.out.printf("  Accuracy: %.1f%% (%d/%d)\n", accuracy, successfulTests, totalTests);
        System.out.printf("  Boundary cases: %.1f%% (%d points on multiple tet boundaries)\n", 
            boundaryRate, multipleContainmentCases);
        
        // The new algorithm should achieve 100% accuracy
        assertTrue(accuracy >= 99.0, String.format("Expected ≥99%% accuracy at level %d, got %.1f%%", level, accuracy));
    }
    
    @Test
    void testDeterministicBehavior() {
        System.out.println("\n=== Testing Deterministic Behavior ===");
        
        Tetree<LongEntityID, String> tetree = 
            new Tetree<>(new SequentialLongIDGenerator());
        
        Random random = new Random(12345);
        byte level = 10;
        
        // Test that the same point always returns the same result
        for (int trial = 0; trial < 100; trial++) {
            Point3f point = new Point3f(
                random.nextFloat() * 1000,
                random.nextFloat() * 1000,
                random.nextFloat() * 1000
            );
            
            // Call locate multiple times
            Tet result1 = tetree.locate(point, level);
            Tet result2 = tetree.locate(point, level);
            Tet result3 = tetree.locate(point, level);
            
            // All results should be identical
            assertEquals(result1.x, result2.x, "X coordinate should be deterministic");
            assertEquals(result1.y, result2.y, "Y coordinate should be deterministic");
            assertEquals(result1.z, result3.z, "Z coordinate should be deterministic");
            assertEquals(result1.l, result2.l, "Level should be deterministic");
            assertEquals(result1.type, result2.type, "Type should be deterministic");
            assertEquals(result2.type, result3.type, "Type should be deterministic");
        }
        
        System.out.println("✓ Deterministic behavior verified");
    }
    
    @Test
    void testBoundaryConditions() {
        System.out.println("\n=== Testing Boundary Conditions ===");
        
        Tetree<LongEntityID, String> tetree = 
            new Tetree<>(new SequentialLongIDGenerator());
        
        byte level = 10;
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        // Test cube corners
        Point3f[] corners = {
            new Point3f(0, 0, 0),                           // Origin
            new Point3f(cellSize, 0, 0),                    // X-axis
            new Point3f(0, cellSize, 0),                    // Y-axis
            new Point3f(0, 0, cellSize),                    // Z-axis
            new Point3f(cellSize, cellSize, 0),             // XY-plane
            new Point3f(cellSize, 0, cellSize),             // XZ-plane
            new Point3f(0, cellSize, cellSize),             // YZ-plane
            new Point3f(cellSize, cellSize, cellSize),      // Opposite corner
            new Point3f(cellSize/2f, cellSize/2f, cellSize/2f) // Center
        };
        
        for (Point3f corner : corners) {
            Tet result = tetree.locate(corner, level);
            assertNotNull(result, "Corner points should be locatable");
            assertTrue(result.contains(corner), "Located tet should contain the corner point");
        }
        
        // Test edge cases - points very close to boundaries
        float epsilon = 0.001f;
        Point3f[] edgeCases = {
            new Point3f(epsilon, epsilon, epsilon),
            new Point3f(cellSize - epsilon, epsilon, epsilon),
            new Point3f(epsilon, cellSize - epsilon, epsilon),
            new Point3f(epsilon, epsilon, cellSize - epsilon),
            new Point3f(cellSize - epsilon, cellSize - epsilon, cellSize - epsilon)
        };
        
        for (Point3f edgeCase : edgeCases) {
            Tet result = tetree.locate(edgeCase, level);
            assertNotNull(result, "Edge case points should be locatable");
            assertTrue(result.contains(edgeCase), "Located tet should contain the edge case point");
        }
        
        System.out.println("✓ Boundary conditions passed");
    }
    
    @Test
    void testCoverageAcrossAllTypes() {
        System.out.println("\n=== Testing Coverage Across All S0-S5 Types ===");
        
        Tetree<LongEntityID, String> tetree = 
            new Tetree<>(new SequentialLongIDGenerator());
        
        byte level = 8; // Smaller level for faster testing
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        Random random = new Random(789);
        
        int[] typeCounts = new int[6];
        int totalTests = 3000;
        
        for (int i = 0; i < totalTests; i++) {
            Point3f point = new Point3f(
                random.nextFloat() * cellSize,
                random.nextFloat() * cellSize,
                random.nextFloat() * cellSize
            );
            
            Tet result = tetree.locate(point, level);
            byte type = result.type;
            
            assertTrue(type >= 0 && type <= 5, "Type must be in range [0,5]");
            typeCounts[type]++;
        }
        
        System.out.println("Type distribution:");
        for (int type = 0; type < 6; type++) {
            double percentage = 100.0 * typeCounts[type] / totalTests;
            System.out.printf("  S%d: %d points (%.1f%%)\n", type, typeCounts[type], percentage);
            
            // Each type should get a reasonable share (not exactly 1/6 due to geometric differences)
            assertTrue(percentage >= 5.0, String.format("Type %d has too few points: %.1f%%", type, percentage));
            assertTrue(percentage <= 35.0, String.format("Type %d has too many points: %.1f%%", type, percentage));
        }
        
        // Verify all types are used
        for (int type = 0; type < 6; type++) {
            assertTrue(typeCounts[type] > 0, String.format("Type %d should be used", type));
        }
        
        System.out.println("✓ All S0-S5 types are properly used");
    }
    
    @Test
    void testPerformanceImprovement() {
        System.out.println("\n=== Testing Performance Improvement ===");
        
        Tetree<LongEntityID, String> tetree = 
            new Tetree<>(new SequentialLongIDGenerator());
        
        byte level = 10;
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        Random random = new Random(456);
        
        // Generate test points
        int numTests = 5000;
        Point3f[] testPoints = new Point3f[numTests];
        for (int i = 0; i < numTests; i++) {
            testPoints[i] = new Point3f(
                random.nextFloat() * cellSize,
                random.nextFloat() * cellSize,
                random.nextFloat() * cellSize
            );
        }
        
        // Time the new locate() method
        long startTime = System.nanoTime();
        for (Point3f point : testPoints) {
            tetree.locate(point, level);
        }
        long newMethodTime = System.nanoTime() - startTime;
        
        // Simulate old method (test all 6 tetrahedra until first match)
        Tet[] testTets = new Tet[6];
        for (byte type = 0; type < 6; type++) {
            testTets[type] = new Tet(0, 0, 0, level, type);
        }
        
        startTime = System.nanoTime();
        for (Point3f point : testPoints) {
            // Simulate old approach: test all until first match
            for (byte type = 0; type < 6; type++) {
                Tet tet = new Tet(
                    (int)(Math.floor(point.x / cellSize) * cellSize),
                    (int)(Math.floor(point.y / cellSize) * cellSize), 
                    (int)(Math.floor(point.z / cellSize) * cellSize),
                    level, type
                );
                if (tet.contains(point)) {
                    break; // Found first match
                }
            }
        }
        long oldMethodTime = System.nanoTime() - startTime;
        
        double speedup = (double) oldMethodTime / newMethodTime;
        
        System.out.printf("New method: %.2f ms\n", newMethodTime / 1_000_000.0);
        System.out.printf("Old method: %.2f ms\n", oldMethodTime / 1_000_000.0);
        System.out.printf("Speedup: %.1fx\n", speedup);
        
        assertTrue(speedup >= 1.5, String.format("Expected ≥1.5x speedup, got %.1fx", speedup));
        
        System.out.println("✓ Performance improvement confirmed");
    }
    
    @Test
    void testEdgeCaseGeometry() {
        System.out.println("\n=== Testing Edge Case Geometry ===");
        
        Tetree<LongEntityID, String> tetree = 
            new Tetree<>(new SequentialLongIDGenerator());
        
        byte level = 12;
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        // Test points that historically caused issues in coordinate dominance approach
        float[][] problematicPoints = {
            {0.277f, 0.708f, 0.666f}, // From research - was misclassified
            {0.369f, 0.382f, 0.276f}, // From research - was misclassified
            {0.783f, 0.998f, 0.919f}, // From research - was misclassified
            {0.798f, 0.177f, 0.151f}, // From research - was misclassified
            {0.5f, 0.5f, 0.5f},       // Exact center - all coordinates equal
            {0.333f, 0.333f, 0.334f}, // Near-equal coordinates
            {0.6f, 0.5f, 0.4f},       // Diagonal boundary case
            {0.4f, 0.6f, 0.5f},       // Another diagonal boundary case
        };
        
        for (float[] coords : problematicPoints) {
            Point3f point = new Point3f(
                coords[0] * cellSize,
                coords[1] * cellSize, 
                coords[2] * cellSize
            );
            
            Tet result = tetree.locate(point, level);
            assertTrue(result.contains(point), 
                String.format("Problematic point (%.3f,%.3f,%.3f) should be contained by located tet S%d",
                    coords[0], coords[1], coords[2], result.type));
        }
        
        System.out.println("✓ Edge case geometry handled correctly");
    }
}
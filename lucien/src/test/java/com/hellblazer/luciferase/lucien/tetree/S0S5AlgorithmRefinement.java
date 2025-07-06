package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.Arrays;

/**
 * Refine the S0-S5 classification algorithm based on actual containment data.
 * The initial coordinate dominance approach had only 50% accuracy.
 */
public class S0S5AlgorithmRefinement {
    
    @Test
    void analyzeFailurePatterns() {
        System.out.println("=== Analyzing Classification Failures ===");
        
        byte level = 10;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        // Create all 6 tetrahedra
        Tet[] tets = new Tet[6];
        for (byte type = 0; type < 6; type++) {
            tets[type] = new Tet(0, 0, 0, level, type);
        }
        
        // Test specific failed points from the previous test
        float[][] failedPoints = {
            {0.277f, 0.708f, 0.666f}, // Predicted S1, actually S5
            {0.369f, 0.382f, 0.276f}, // Predicted S5, actually S1  
            {0.783f, 0.998f, 0.919f}, // Predicted S1, actually S5
            {0.798f, 0.177f, 0.151f}  // Predicted S4, actually S0
        };
        
        for (float[] coords : failedPoints) {
            analyzeSpecificPoint(coords[0], coords[1], coords[2], tets, h);
        }
    }
    
    private void analyzeSpecificPoint(float nx, float ny, float nz, Tet[] tets, int h) {
        Point3f point = new Point3f(nx * h, ny * h, nz * h);
        
        System.out.printf("\\nPoint (%.3f, %.3f, %.3f):\\n", nx, ny, nz);
        System.out.printf("  Sum: %.3f, X-dom: %s, Y-dom: %s, Z-dom: %s\\n", 
            nx + ny + nz,
            (nx >= ny && nx >= nz) ? "yes" : "no",
            (ny >= nx && ny >= nz) ? "yes" : "no", 
            (nz >= nx && nz >= ny) ? "yes" : "no");
        
        // Check which tetrahedra actually contain it
        System.out.print("  Actually contained by: ");
        for (byte type = 0; type < 6; type++) {
            if (tets[type].contains(point)) {
                System.out.printf("S%d ", type);
            }
        }
        System.out.println();
        
        // My algorithm prediction
        byte predicted = classifyPointInCube(nx, ny, nz);
        System.out.printf("  My algorithm predicts: S%d\\n", predicted);
    }
    
    private static byte classifyPointInCube(float x, float y, float z) {
        boolean xDominant = (x >= y && x >= z);
        boolean yDominant = (y >= x && y >= z);
        boolean upperDiagonal = (x + y + z >= 1.5f);
        
        if (xDominant) {
            return upperDiagonal ? (byte)0 : (byte)4;
        } else if (yDominant) {
            return upperDiagonal ? (byte)1 : (byte)5;
        } else {
            return upperDiagonal ? (byte)2 : (byte)3;
        }
    }
    
    @Test
    void exploreAlternativeStrategies() {
        System.out.println("\\n=== Exploring Alternative Strategies ===");
        
        byte level = 10;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        Tet[] tets = new Tet[6];
        for (byte type = 0; type < 6; type++) {
            tets[type] = new Tet(0, 0, 0, level, type);
        }
        
        // Strategy 1: Reverse the diagonal classification
        System.out.println("\\nStrategy 1: Reverse diagonal split");
        testStrategy(tets, h, this::strategy1_ReverseDiagonal);
        
        // Strategy 2: Different diagonal threshold
        System.out.println("\\nStrategy 2: Diagonal threshold = 1.0");
        testStrategy(tets, h, this::strategy2_DifferentThreshold);
        
        // Strategy 3: Coordinate ordering approach
        System.out.println("\\nStrategy 3: Coordinate ordering");
        testStrategy(tets, h, this::strategy3_CoordinateOrdering);
        
        // Strategy 4: Distance-based approach
        System.out.println("\\nStrategy 4: Distance to tetrahedron centroids");
        testStrategy(tets, h, this::strategy4_DistanceBased);
    }
    
    private byte strategy1_ReverseDiagonal(float x, float y, float z) {
        boolean xDominant = (x >= y && x >= z);
        boolean yDominant = (y >= x && y >= z);
        boolean lowerDiagonal = (x + y + z < 1.5f); // REVERSED
        
        if (xDominant) {
            return lowerDiagonal ? (byte)0 : (byte)4; // REVERSED
        } else if (yDominant) {
            return lowerDiagonal ? (byte)1 : (byte)5; // REVERSED
        } else {
            return lowerDiagonal ? (byte)2 : (byte)3; // REVERSED
        }
    }
    
    private byte strategy2_DifferentThreshold(float x, float y, float z) {
        boolean xDominant = (x >= y && x >= z);
        boolean yDominant = (y >= x && y >= z);
        boolean upperDiagonal = (x + y + z >= 1.0f); // Different threshold
        
        if (xDominant) {
            return upperDiagonal ? (byte)0 : (byte)4;
        } else if (yDominant) {
            return upperDiagonal ? (byte)1 : (byte)5;
        } else {
            return upperDiagonal ? (byte)2 : (byte)3;
        }
    }
    
    private byte strategy3_CoordinateOrdering(float x, float y, float z) {
        // Sort coordinates to determine type
        float[] coords = {x, y, z};
        Integer[] indices = {0, 1, 2}; // X, Y, Z
        
        // Sort by coordinate value
        Arrays.sort(indices, (a, b) -> Float.compare(coords[b], coords[a])); // Descending
        
        // Determine type based on ordering
        if (indices[0] == 0) { // X is largest
            return (byte)(coords[0] + coords[1] + coords[2] >= 1.5f ? 0 : 4);
        } else if (indices[0] == 1) { // Y is largest
            return (byte)(coords[0] + coords[1] + coords[2] >= 1.5f ? 1 : 5);
        } else { // Z is largest
            return (byte)(coords[0] + coords[1] + coords[2] >= 1.5f ? 2 : 3);
        }
    }
    
    private byte strategy4_DistanceBased(float x, float y, float z) {
        // Calculate distance to each tetrahedron's centroid
        float[][][] centroids = {
            // S0: (0,0,0), (1,0,0), (1,1,0), (1,1,1) -> centroid (0.75, 0.5, 0.25)
            {{0.75f, 0.5f, 0.25f}},
            // S1: (0,0,0), (0,1,0), (1,1,0), (1,1,1) -> centroid (0.5, 0.75, 0.25)  
            {{0.5f, 0.75f, 0.25f}},
            // S2: (0,0,0), (0,0,1), (1,0,1), (1,1,1) -> centroid (0.5, 0.25, 0.75)
            {{0.5f, 0.25f, 0.75f}},
            // S3: (0,0,0), (0,0,1), (0,1,1), (1,1,1) -> centroid (0.25, 0.5, 0.75)
            {{0.25f, 0.5f, 0.75f}},
            // S4: (0,0,0), (1,0,0), (1,0,1), (1,1,1) -> centroid (0.75, 0.25, 0.5)
            {{0.75f, 0.25f, 0.5f}},
            // S5: (0,0,0), (0,1,0), (0,1,1), (1,1,1) -> centroid (0.25, 0.75, 0.5)
            {{0.25f, 0.75f, 0.5f}}
        };
        
        byte closestType = 0;
        float minDistance = Float.MAX_VALUE;
        
        for (byte type = 0; type < 6; type++) {
            float cx = centroids[type][0][0];
            float cy = centroids[type][0][1];
            float cz = centroids[type][0][2];
            
            float dist = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz);
            if (dist < minDistance) {
                minDistance = dist;
                closestType = type;
            }
        }
        
        return closestType;
    }
    
    @FunctionalInterface
    interface ClassificationStrategy {
        byte classify(float x, float y, float z);
    }
    
    private void testStrategy(Tet[] tets, int h, ClassificationStrategy strategy) {
        int totalTests = 1000;
        int correct = 0;
        
        java.util.Random random = new java.util.Random(42);
        
        for (int i = 0; i < totalTests; i++) {
            float x = random.nextFloat();
            float y = random.nextFloat();
            float z = random.nextFloat();
            Point3f point = new Point3f(x * h, y * h, z * h);
            
            byte predicted = strategy.classify(x, y, z);
            
            if (tets[predicted].contains(point)) {
                correct++;
            }
        }
        
        double accuracy = 100.0 * correct / totalTests;
        System.out.printf("  Accuracy: %.1f%% (%d/%d)\\n", accuracy, correct, totalTests);
    }
    
    @Test
    void analyzeActualS0S5Patterns() {
        System.out.println("\\n=== Analyzing Actual S0-S5 Patterns ===");
        
        byte level = 10;
        int h = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - level);
        
        Tet[] tets = new Tet[6];
        for (byte type = 0; type < 6; type++) {
            tets[type] = new Tet(0, 0, 0, level, type);
        }
        
        // Sample many points and see which ones each tet actually contains
        java.util.Random random = new java.util.Random(42);
        int samples = 2000;
        
        for (byte type = 0; type < 6; type++) {
            System.out.printf("\\nAnalyzing S%d containment patterns:\\n", type);
            
            int contained = 0;
            float sumX = 0, sumY = 0, sumZ = 0;
            float sumXYZ = 0;
            
            for (int i = 0; i < samples; i++) {
                float x = random.nextFloat();
                float y = random.nextFloat();
                float z = random.nextFloat();
                Point3f point = new Point3f(x * h, y * h, z * h);
                
                if (tets[type].contains(point)) {
                    contained++;
                    sumX += x;
                    sumY += y;
                    sumZ += z;
                    sumXYZ += (x + y + z);
                }
            }
            
            if (contained > 0) {
                System.out.printf("  Points contained: %d/%d (%.1f%%)\\n", contained, samples, 100.0 * contained / samples);
                System.out.printf("  Average coords: (%.3f, %.3f, %.3f)\\n", sumX/contained, sumY/contained, sumZ/contained);
                System.out.printf("  Average sum: %.3f\\n", sumXYZ/contained);
            }
        }
    }
}
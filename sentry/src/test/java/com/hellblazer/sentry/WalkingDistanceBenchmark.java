package com.hellblazer.sentry;

import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.*;

/**
 * Benchmark to measure walking distance reduction with landmark index
 */
public class WalkingDistanceBenchmark {
    
    // Modified Grid class to track walking steps
    static class InstrumentedGrid extends MutableGrid {
        private long totalWalkSteps = 0;
        private int totalQueries = 0;
        
        @Override
        public Tetrahedron locate(Tuple3f p, Random entropy) {
            totalQueries++;
            int steps = 0;
            
            // Override to count steps
            Tetrahedron start = last;
            if (landmarkIndex != null) {
                // Let landmark index handle it
                Tetrahedron result = super.locate(p, entropy);
                // Get steps from landmark index
                String stats = landmarkIndex.getStatistics();
                // Extract average walk steps from stats
                if (stats.contains("Avg walk steps:")) {
                    String avgPart = stats.substring(stats.lastIndexOf("Avg walk steps:") + 15).trim();
                    try {
                        double avg = Double.parseDouble(avgPart);
                        totalWalkSteps = (long)(avg * totalQueries);
                    } catch (Exception e) {
                        // Ignore parse errors
                    }
                }
                return result;
            }
            
            // Manual walking with step counting
            Tetrahedron current = start;
            while (current != null && steps < 10000) {
                steps++;
                
                V outsideFace = null;
                for (V face : Grid.VERTICES) {
                    if (current.orientationWrt(face, p) < 0.0d) {
                        outsideFace = face;
                        break;
                    }
                }
                
                if (outsideFace == null) {
                    totalWalkSteps += steps;
                    last = current;
                    return current;
                }
                
                current = current.getNeighbor(outsideFace);
            }
            
            totalWalkSteps += steps;
            return null;
        }
        
        public double getAverageWalkSteps() {
            return totalQueries > 0 ? (double) totalWalkSteps / totalQueries : 0;
        }
        
        public void resetStats() {
            totalWalkSteps = 0;
            totalQueries = 0;
        }
    }
    
    public static void main(String[] args) {
        System.out.println("Walking Distance Benchmark - Landmark Index Impact");
        System.out.println("=================================================\n");
        
        Random random = new Random(42);
        int[] sizes = {100, 200, 500, 1000, 2000, 5000};
        
        System.out.println("Mesh Size | Avg Walk (No Index) | Avg Walk (Landmark) | Reduction");
        System.out.println("----------|---------------------|---------------------|----------");
        
        for (int size : sizes) {
            benchmarkSize(size, random);
        }
        
        System.out.println("\nTheoretical expectations:");
        System.out.println("- Without index: O(n^(1/3)) walking distance");
        System.out.println("- With landmarks: O(n^(1/6)) walking distance");
        System.out.println("- Reduction should increase with mesh size");
    }
    
    private static void benchmarkSize(int size, Random random) {
        // Test without landmarks
        System.setProperty("sentry.useLandmarkIndex", "false");
        double avgWalkNoIndex = measureWalkingDistance(size, random);
        
        // Test with landmarks
        System.setProperty("sentry.useLandmarkIndex", "true");
        double avgWalkLandmark = measureWalkingDistance(size, random);
        
        double reduction = ((avgWalkNoIndex - avgWalkLandmark) / avgWalkNoIndex) * 100;
        
        System.out.printf("%9d | %19.1f | %19.1f | %7.1f%%\n",
            size, avgWalkNoIndex, avgWalkLandmark, reduction);
    }
    
    private static double measureWalkingDistance(int size, Random random) {
        InstrumentedGrid grid = new InstrumentedGrid();
        
        // Build mesh
        for (int i = 0; i < size; i++) {
            float x = random.nextFloat() * 100;
            float y = random.nextFloat() * 100;
            float z = random.nextFloat() * 100;
            grid.track(new Point3f(x, y, z), random);
        }
        
        // Reset stats after building
        grid.resetStats();
        if (grid.landmarkIndex != null) {
            grid.landmarkIndex.clear();
            // Re-add some landmarks after building
            for (int i = 0; i < size / 20; i++) {
                grid.landmarkIndex.addTetrahedron(grid.last, size * 4);
            }
        }
        
        // Get all tetrahedra for random selection
        List<Tetrahedron> tetrahedra = new ArrayList<>(grid.tetrahedrons());
        if (tetrahedra.isEmpty()) {
            return 0.0;
        }
        
        // Perform queries from random starting points
        int queries = Math.min(100, size / 2);
        for (int i = 0; i < queries; i++) {
            // Random query point
            Point3f query = new Point3f(
                random.nextFloat() * 100,
                random.nextFloat() * 100,
                random.nextFloat() * 100
            );
            
            // Force starting from a random tetrahedron (not last)
            grid.last = tetrahedra.get(random.nextInt(tetrahedra.size()));
            grid.locate(query, random);
        }
        
        // Print landmark statistics for debugging
        if (grid.landmarkIndex != null) {
            System.out.println("  " + grid.getLandmarkStatistics());
        }
        
        return grid.getAverageWalkSteps();
    }
}

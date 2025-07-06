package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

/**
 * Simple performance test comparing index() vs tmIndex() methods
 */
public class TetIndexPerformanceTest {

    @Test
    public void compareIndexMethods() {
        System.out.println("\n=== Tet index() vs tmIndex() Performance Comparison ===\n");

        // Test at different levels
        int[] levels = { 1, 5, 10, 15, 20 };
        int iterations = 10000;

        for (int level : levels) {
            System.out.printf("Level %d:\n", level);

            // Create test Tet at this level with grid-aligned coordinates
            int cellSize = Constants.lengthAtLevel((byte) level);
            int coord = cellSize; // Use cellSize as a valid grid-aligned coordinate
            Tet tet = new Tet(coord, coord, coord, (byte) level, (byte) 0);

            // Warm up
            for (int i = 0; i < 1000; i++) {
                tet.consecutiveIndex();
                tet.tmIndex();
            }

            // Test index()
            long startTime = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                tet.consecutiveIndex();
            }
            long indexTime = System.nanoTime() - startTime;

            // Test tmIndex()
            startTime = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                tet.tmIndex();
            }
            long tmIndexTime = System.nanoTime() - startTime;

            // Calculate averages
            double indexAvg = indexTime / (double) iterations;
            double tmIndexAvg = tmIndexTime / (double) iterations;
            double ratio = tmIndexAvg / indexAvg;

            System.out.printf("  index():   %.2f ns/op\n", indexAvg);
            System.out.printf("  tmIndex(): %.2f ns/op\n", tmIndexAvg);
            System.out.printf("  Ratio:     %.1fx slower\n\n", ratio);
        }
    }
}

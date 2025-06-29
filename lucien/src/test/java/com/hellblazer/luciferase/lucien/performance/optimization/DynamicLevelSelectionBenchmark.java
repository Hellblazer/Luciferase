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
package com.hellblazer.luciferase.lucien.performance.optimization;

import com.hellblazer.luciferase.lucien.BulkOperationConfig;
import com.hellblazer.luciferase.lucien.LevelSelector;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Benchmarks dynamic level selection vs fixed level bulk operations
 *
 * @author hal.hildebrand
 */
@EnabledIfEnvironmentVariable(named = "RUN_SPATIAL_INDEX_PERF_TESTS", matches = "true")
public class DynamicLevelSelectionBenchmark {

    private static final int[]  TEST_SIZES   = { 10_000, 50_000, 100_000, 500_000 };
    private static final byte[] FIXED_LEVELS = { 8, 10, 12, 14 };

    @Test
    public void benchmarkAdaptiveSubdivision() {
        System.out.println("\n--- Adaptive Subdivision Effectiveness ---");
        System.out.println("\nTesting with deep tree structures to show adaptive benefits");

        int size = 100_000;
        List<Point3f> positions = generateData(DataDistribution.CLUSTERED, size);

        System.out.println(
        "\nIndex Type,With Adaptive,Without Adaptive,Node Count Adaptive,Node Count Fixed,Reduction");

        // Test Octree
        {
            Octree<LongEntityID, String> octreeAdaptive = new Octree<>(new SequentialLongIDGenerator(), 100, (byte) 20);
            BulkOperationConfig adaptiveConfig = BulkOperationConfig.highPerformance().withAdaptiveSubdivision(true);

            long adaptiveStart = System.nanoTime();
            octreeAdaptive.insertBatch(positions, generateContents(size), (byte) 12);
            long adaptiveTime = (System.nanoTime() - adaptiveStart) / 1_000_000;
            int adaptiveNodes = octreeAdaptive.nodeCount();

            Octree<LongEntityID, String> octreeFixed = new Octree<>(new SequentialLongIDGenerator(), 100, (byte) 20);
            BulkOperationConfig fixedConfig = BulkOperationConfig.highPerformance().withAdaptiveSubdivision(false);

            long fixedStart = System.nanoTime();
            octreeFixed.insertBatch(positions, generateContents(size), (byte) 12);
            long fixedTime = (System.nanoTime() - fixedStart) / 1_000_000;
            int fixedNodes = octreeFixed.nodeCount();

            double reduction = (1.0 - (double) adaptiveNodes / fixedNodes) * 100;
            System.out.printf("Octree,%dms,%dms,%d,%d,%.1f%%%n", adaptiveTime, fixedTime, adaptiveNodes, fixedNodes,
                              reduction);
        }

        // Test Tetree
        {
            Tetree<LongEntityID, String> tetreeAdaptive = new Tetree<>(new SequentialLongIDGenerator(), 100, (byte) 20);
            BulkOperationConfig adaptiveConfig = BulkOperationConfig.highPerformance().withAdaptiveSubdivision(true);

            long adaptiveStart = System.nanoTime();
            tetreeAdaptive.insertBatch(positions, generateContents(size), (byte) 12);
            long adaptiveTime = (System.nanoTime() - adaptiveStart) / 1_000_000;
            int adaptiveNodes = tetreeAdaptive.nodeCount();

            Tetree<LongEntityID, String> tetreeFixed = new Tetree<>(new SequentialLongIDGenerator(), 100, (byte) 20);
            BulkOperationConfig fixedConfig = BulkOperationConfig.highPerformance().withAdaptiveSubdivision(false);

            long fixedStart = System.nanoTime();
            tetreeFixed.insertBatch(positions, generateContents(size), (byte) 12);
            long fixedTime = (System.nanoTime() - fixedStart) / 1_000_000;
            int fixedNodes = tetreeFixed.nodeCount();

            double reduction = (1.0 - (double) adaptiveNodes / fixedNodes) * 100;
            System.out.printf("Tetree,%dms,%dms,%d,%d,%.1f%%%n", adaptiveTime, fixedTime, adaptiveNodes, fixedNodes,
                              reduction);
        }
    }

    @Test
    public void benchmarkOctreeDynamicLevelSelection() {
        System.out.println("\n--- Octree Dynamic Level Selection ---");

        for (DataDistribution distribution : DataDistribution.values()) {
            System.out.println("\nDistribution: " + distribution);
            System.out.println(
            "Size,DynamicLevel,DynamicTime(ms),BestFixedLevel,BestFixedTime(ms),Speedup,SelectedLevel");

            for (int size : TEST_SIZES) {
                List<Point3f> positions = generateData(distribution, size);

                // Test dynamic level selection
                long dynamicTime = 0;
                byte selectedLevel = 0;
                {
                    Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator(), 100, (byte) 20);
                    BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                                    .withDynamicLevelSelection(true)
                                                                    .withAdaptiveSubdivision(true);

                    long start = System.nanoTime();
                    selectedLevel = LevelSelector.selectOptimalLevel(positions, 100);
                    octree.insertBatch(positions, generateContents(size), selectedLevel);
                    dynamicTime = (System.nanoTime() - start) / 1_000_000;
                }

                // Test fixed levels
                long bestFixedTime = Long.MAX_VALUE;
                byte bestFixedLevel = 0;

                for (byte fixedLevel : FIXED_LEVELS) {
                    Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator(), 100, (byte) 20);
                    BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                                    .withDynamicLevelSelection(false)
                                                                    .withAdaptiveSubdivision(false);

                    long start = System.nanoTime();
                    octree.insertBatch(positions, generateContents(size), fixedLevel);
                    long fixedTime = (System.nanoTime() - start) / 1_000_000;

                    if (fixedTime < bestFixedTime) {
                        bestFixedTime = fixedTime;
                        bestFixedLevel = fixedLevel;
                    }
                }

                double speedup = (double) bestFixedTime / dynamicTime;
                System.out.printf("%d,%d,%d,%d,%d,%.2fx,L%d%n", size, dynamicTime, dynamicTime, bestFixedLevel,
                                  bestFixedTime, speedup, selectedLevel);
            }
        }
    }

    @Test
    public void benchmarkTetreeDynamicLevelSelection() {
        System.out.println("\n--- Tetree Dynamic Level Selection ---");

        for (DataDistribution distribution : DataDistribution.values()) {
            System.out.println("\nDistribution: " + distribution);
            System.out.println(
            "Size,DynamicLevel,DynamicTime(ms),BestFixedLevel,BestFixedTime(ms),Speedup,SelectedLevel");

            for (int size : TEST_SIZES) {
                List<Point3f> positions = generateData(distribution, size);

                // Test dynamic level selection
                long dynamicTime = 0;
                byte selectedLevel = 0;
                {
                    Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator(), 100, (byte) 20);
                    BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                                    .withDynamicLevelSelection(true)
                                                                    .withAdaptiveSubdivision(true);

                    long start = System.nanoTime();
                    selectedLevel = LevelSelector.selectOptimalLevel(positions, 100);
                    tetree.insertBatch(positions, generateContents(size), selectedLevel);
                    dynamicTime = (System.nanoTime() - start) / 1_000_000;
                }

                // Test fixed levels
                long bestFixedTime = Long.MAX_VALUE;
                byte bestFixedLevel = 0;

                for (byte fixedLevel : FIXED_LEVELS) {
                    Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator(), 100, (byte) 20);
                    BulkOperationConfig config = BulkOperationConfig.highPerformance()
                                                                    .withDynamicLevelSelection(false)
                                                                    .withAdaptiveSubdivision(false);

                    long start = System.nanoTime();
                    tetree.insertBatch(positions, generateContents(size), fixedLevel);
                    long fixedTime = (System.nanoTime() - start) / 1_000_000;

                    if (fixedTime < bestFixedTime) {
                        bestFixedTime = fixedTime;
                        bestFixedLevel = fixedLevel;
                    }
                }

                double speedup = (double) bestFixedTime / dynamicTime;
                System.out.printf("%d,%d,%d,%d,%d,%.2fx,L%d%n", size, dynamicTime, dynamicTime, bestFixedLevel,
                                  bestFixedTime, speedup, selectedLevel);
            }
        }
    }

    @BeforeEach
    public void setup() {
        System.out.println("\n=== Dynamic Level Selection Performance Benchmark ===");
        System.out.println("Comparing dynamic level selection vs fixed levels");
        System.out.println("Entity counts: " + java.util.Arrays.toString(TEST_SIZES));
        System.out.println("Fixed levels to test: " + java.util.Arrays.toString(FIXED_LEVELS));
    }

    private List<String> generateContents(int count) {
        List<String> contents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            contents.add("Entity" + i);
        }
        return contents;
    }

    private List<Point3f> generateData(DataDistribution distribution, int count) {
        List<Point3f> positions = new ArrayList<>(count);
        Random rand = new Random(42); // Fixed seed for reproducibility

        switch (distribution) {
            case UNIFORM_RANDOM:
                for (int i = 0; i < count; i++) {
                    positions.add(
                    new Point3f(rand.nextFloat() * 1000, rand.nextFloat() * 1000, rand.nextFloat() * 1000));
                }
                break;

            case CLUSTERED:
                // Single tight cluster
                float centerX = 500, centerY = 500, centerZ = 500;
                for (int i = 0; i < count; i++) {
                    positions.add(
                    new Point3f(centerX + (rand.nextFloat() - 0.5f) * 100, centerY + (rand.nextFloat() - 0.5f) * 100,
                                centerZ + (rand.nextFloat() - 0.5f) * 100));
                }
                break;

            case SURFACE_ALIGNED:
                // Points on a plane
                for (int i = 0; i < count; i++) {
                    positions.add(new Point3f(rand.nextFloat() * 1000, 500, // Fixed Y
                                              rand.nextFloat() * 1000));
                }
                break;

            case DIAGONAL_LINE:
                // Points along diagonal
                for (int i = 0; i < count; i++) {
                    float t = (float) i / count * 1000;
                    float noise = 10;
                    positions.add(
                    new Point3f(t + (rand.nextFloat() - 0.5f) * noise, t + (rand.nextFloat() - 0.5f) * noise,
                                t + (rand.nextFloat() - 0.5f) * noise));
                }
                break;

            case MULTI_CLUSTER:
                // Multiple clusters
                int clustersCount = 10;
                int pointsPerCluster = count / clustersCount;
                for (int c = 0; c < clustersCount; c++) {
                    float cx = rand.nextFloat() * 900 + 50;
                    float cy = rand.nextFloat() * 900 + 50;
                    float cz = rand.nextFloat() * 900 + 50;
                    for (int i = 0; i < pointsPerCluster; i++) {
                        positions.add(
                        new Point3f(cx + (rand.nextFloat() - 0.5f) * 50, cy + (rand.nextFloat() - 0.5f) * 50,
                                    cz + (rand.nextFloat() - 0.5f) * 50));
                    }
                }
                break;
        }

        return positions;
    }

    private enum DataDistribution {
        UNIFORM_RANDOM("Uniform Random"), CLUSTERED("Clustered"), SURFACE_ALIGNED("Surface Aligned"), DIAGONAL_LINE(
        "Diagonal Line"), MULTI_CLUSTER("Multi-Cluster");

        private final String displayName;

        DataDistribution(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}

/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

/**
 * Benchmark for Tetree.locate() performance.
 * Compares S0-S5 cube partition (old) vs S0 Bey tree traversal (new).
 */
class LocateBenchmark {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int BENCHMARK_ITERATIONS = 100_000;
    private static final byte TARGET_LEVEL = 10;

    @Test
    void benchmarkLocate() {
        var random = new Random(42);
        var tetree = new Tetree<>(new SequentialLongIDGenerator(), 100, TARGET_LEVEL);

        // Generate random points in valid coordinate space
        int maxCoord = Constants.lengthAtLevel((byte) 0);
        var points = new Point3f[BENCHMARK_ITERATIONS];
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            points[i] = new Point3f(
                random.nextFloat() * maxCoord * 0.9f + maxCoord * 0.05f,
                random.nextFloat() * maxCoord * 0.9f + maxCoord * 0.05f,
                random.nextFloat() * maxCoord * 0.9f + maxCoord * 0.05f
            );
        }

        // Warmup
        System.out.println("Warming up with " + WARMUP_ITERATIONS + " iterations...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            tetree.locateTetrahedron(points[i % points.length], TARGET_LEVEL);
        }

        // Benchmark new S0 Bey tree traversal (via Tetree.locate)
        System.out.println("Benchmarking S0 Bey tree traversal (NEW) with " + BENCHMARK_ITERATIONS + " iterations...");
        long startTime = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            var tet = tetree.locateTetrahedron(points[i], TARGET_LEVEL);
            if (tet == null) {
                throw new AssertionError("locate returned null");
            }
        }

        long endTime = System.nanoTime();
        double totalMs = (endTime - startTime) / 1_000_000.0;
        double avgNs = (endTime - startTime) / (double) BENCHMARK_ITERATIONS;

        System.out.println("\n=== S0 BEY TREE TRAVERSAL (NEW) ===");
        System.out.printf("Total time: %.2f ms%n", totalMs);
        System.out.printf("Average time per locate: %.2f ns%n", avgNs);
        System.out.printf("Throughput: %.0f locates/sec%n", BENCHMARK_ITERATIONS / (totalMs / 1000.0));
        System.out.println("===================================\n");

        // Verify type consistency for S0 tree
        System.out.println("Verifying S0 tree type consistency...");
        int typeErrors = verifyTypeConsistency(tetree, points, 1000);
        System.out.printf("Type verification: %d errors in 1000 samples%n\n", typeErrors);

        // Additional verification: check that all children have valid parent types
        System.out.println("Verifying parent-child type relationships...");
        int parentChildErrors = 0;
        for (int i = 0; i < 100; i++) {
            var tet = tetree.locateTetrahedron(points[i], TARGET_LEVEL);
            var current = tet;
            while (current.l() > 0) {
                var parent = current.parent();
                // Check if current's type is a valid child type for parent's type
                byte parentType = parent.type();
                byte childType = current.type();
                boolean validChild = false;
                for (int beyIdx = 0; beyIdx < 8; beyIdx++) {
                    if (TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[parentType][beyIdx] == childType) {
                        validChild = true;
                        break;
                    }
                }
                if (!validChild) {
                    if (parentChildErrors < 5) {
                        System.out.printf("Invalid parent-child: parent type %d cannot have child type %d%n",
                            parentType, childType);
                    }
                    parentChildErrors++;
                }
                current = parent;
            }
        }
        System.out.printf("Parent-child validation: %d errors in 100 samples%n", parentChildErrors);
    }

    private int verifyTypeConsistency(Tetree<?, ?> tetree, Point3f[] points, int sampleCount) {
        int typeErrors = 0;
        for (int i = 0; i < sampleCount; i++) {
            var tet = tetree.locateTetrahedron(points[i], TARGET_LEVEL);
            // Trace up and verify parent types are consistent with Bey refinement
            var current = tet;
            while (current.l() > 0) {
                var parent = current.parent();
                byte parentType = parent.type();

                // Compute cube ID of current within parent
                int parentH = Constants.lengthAtLevel(parent.l());
                int halfH = parentH / 2;
                int cubeId = 0;
                if (current.x >= parent.x + halfH) cubeId |= 1;
                if (current.y >= parent.y + halfH) cubeId |= 2;
                if (current.z >= parent.z + halfH) cubeId |= 4;

                // Expected child type from Bey tables
                byte beyId = TetreeConnectivity.TYPE_CID_TO_BEYID[parentType][cubeId];
                byte expectedType = TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[parentType][beyId];

                if (expectedType != current.type()) {
                    if (typeErrors < 5) {
                        System.out.printf("Type error at level %d: parent type %d, cubeId %d, beyId %d -> expected %d, got %d%n",
                            current.l(), parentType, cubeId, beyId, expectedType, current.type());
                    }
                    typeErrors++;
                }
                current = parent;
            }
        }
        return typeErrors;
    }
}

/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.esvt.traversal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark comparing scalar vs SIMD Möller-Trumbore intersection.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class SIMDBenchmarkTest {

    private MollerTrumboreIntersection scalar;
    private MollerTrumboreSIMD simd;
    private Random random;

    // Test tetrahedron (unit tet at origin)
    private final float[] tetVerts = {
        0, 0, 0,     // v0
        1, 0, 0,     // v1
        0.5f, 1, 0,  // v2
        0.5f, 0.5f, 1 // v3
    };

    @BeforeEach
    void setUp() {
        scalar = MollerTrumboreIntersection.create();
        simd = MollerTrumboreSIMD.create();
        random = new Random(42);
    }

    @Test
    void testSIMDAvailability() {
        boolean available = MollerTrumboreSIMD.isAvailable();
        System.out.println("SIMD Available: " + available);
        // Test should still run even if SIMD not available
    }

    @Test
    void testCorrectness() {
        // Verify SIMD produces same results as scalar

        Point3f v0 = new Point3f(tetVerts[0], tetVerts[1], tetVerts[2]);
        Point3f v1 = new Point3f(tetVerts[3], tetVerts[4], tetVerts[5]);
        Point3f v2 = new Point3f(tetVerts[6], tetVerts[7], tetVerts[8]);
        Point3f v3 = new Point3f(tetVerts[9], tetVerts[10], tetVerts[11]);

        int matchCount = 0;
        int testCount = 1000;

        for (int i = 0; i < testCount; i++) {
            // Random ray
            Point3f origin = new Point3f(
                random.nextFloat() * 2 - 0.5f,
                random.nextFloat() * 2 - 0.5f,
                random.nextFloat() * 2 - 0.5f
            );
            Vector3f dir = new Vector3f(
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1
            );
            dir.normalize();

            // Scalar result
            var scalarResult = new MollerTrumboreIntersection.TetrahedronResult();
            boolean scalarHit = scalar.intersectTetrahedron(origin, dir, v0, v1, v2, v3, scalarResult);

            // SIMD result
            var simdResult = new MollerTrumboreSIMD.TetrahedronResult();
            boolean simdHit = simd.intersectTetrahedron(
                origin.x, origin.y, origin.z,
                dir.x, dir.y, dir.z,
                v0.x, v0.y, v0.z,
                v1.x, v1.y, v1.z,
                v2.x, v2.y, v2.z,
                v3.x, v3.y, v3.z,
                simdResult);

            if (scalarHit == simdHit) {
                if (!scalarHit || (Math.abs(scalarResult.tEntry - simdResult.tEntry) < 0.001f &&
                                   scalarResult.entryFace == simdResult.entryFace)) {
                    matchCount++;
                }
            }
        }

        double accuracy = (double) matchCount / testCount * 100;
        System.out.printf("SIMD Correctness: %.1f%% (%d/%d matches)%n", accuracy, matchCount, testCount);
        assertTrue(accuracy > 95, "SIMD should match scalar results in >95% of cases");
    }

    @Test
    void benchmarkScalarVsSIMD() {
        Point3f v0 = new Point3f(tetVerts[0], tetVerts[1], tetVerts[2]);
        Point3f v1 = new Point3f(tetVerts[3], tetVerts[4], tetVerts[5]);
        Point3f v2 = new Point3f(tetVerts[6], tetVerts[7], tetVerts[8]);
        Point3f v3 = new Point3f(tetVerts[9], tetVerts[10], tetVerts[11]);

        int numRays = 100_000;
        int warmup = 10_000;

        // Generate random rays
        Point3f[] origins = new Point3f[numRays];
        Vector3f[] dirs = new Vector3f[numRays];
        for (int i = 0; i < numRays; i++) {
            origins[i] = new Point3f(
                random.nextFloat() * 2 - 0.5f,
                random.nextFloat() * 2 - 0.5f,
                -1 + random.nextFloat() * 0.1f // Rays coming from -Z
            );
            dirs[i] = new Vector3f(
                random.nextFloat() * 0.2f - 0.1f,
                random.nextFloat() * 0.2f - 0.1f,
                1
            );
            dirs[i].normalize();
        }

        // Warmup scalar
        var scalarResult = new MollerTrumboreIntersection.TetrahedronResult();
        for (int i = 0; i < warmup; i++) {
            scalar.intersectTetrahedron(origins[i % numRays], dirs[i % numRays], v0, v1, v2, v3, scalarResult);
        }

        // Benchmark scalar
        int scalarHits = 0;
        long scalarStart = System.nanoTime();
        for (int i = 0; i < numRays; i++) {
            if (scalar.intersectTetrahedron(origins[i], dirs[i], v0, v1, v2, v3, scalarResult)) {
                scalarHits++;
            }
        }
        long scalarTime = System.nanoTime() - scalarStart;

        // Warmup SIMD
        var simdResult = new MollerTrumboreSIMD.TetrahedronResult();
        for (int i = 0; i < warmup; i++) {
            Point3f o = origins[i % numRays];
            Vector3f d = dirs[i % numRays];
            simd.intersectTetrahedron(
                o.x, o.y, o.z, d.x, d.y, d.z,
                v0.x, v0.y, v0.z, v1.x, v1.y, v1.z,
                v2.x, v2.y, v2.z, v3.x, v3.y, v3.z, simdResult);
        }

        // Benchmark SIMD
        int simdHits = 0;
        long simdStart = System.nanoTime();
        for (int i = 0; i < numRays; i++) {
            Point3f o = origins[i];
            Vector3f d = dirs[i];
            if (simd.intersectTetrahedron(
                    o.x, o.y, o.z, d.x, d.y, d.z,
                    v0.x, v0.y, v0.z, v1.x, v1.y, v1.z,
                    v2.x, v2.y, v2.z, v3.x, v3.y, v3.z, simdResult)) {
                simdHits++;
            }
        }
        long simdTime = System.nanoTime() - simdStart;

        // Results
        double scalarMs = scalarTime / 1_000_000.0;
        double simdMs = simdTime / 1_000_000.0;
        double speedup = scalarTime / (double) simdTime;
        double scalarRaysPerMs = numRays / scalarMs;
        double simdRaysPerMs = numRays / simdMs;

        System.out.println("\n=== SIMD Ray-Tetrahedron Intersection Benchmark ===");
        System.out.printf("Rays tested: %,d%n", numRays);
        System.out.printf("Scalar: %.2f ms (%.0f rays/ms), hits: %d%n", scalarMs, scalarRaysPerMs, scalarHits);
        System.out.printf("SIMD:   %.2f ms (%.0f rays/ms), hits: %d%n", simdMs, simdRaysPerMs, simdHits);
        System.out.printf("Speedup: %.2fx%n", speedup);
        System.out.println("======================================\n");

        // Note: On ARM (Apple M-series), SIMD overhead often exceeds gains for small ops
        // The JIT already vectorizes scalar loops effectively
        System.out.println("Note: Speedup < 1.0 is expected on ARM due to Vector API overhead");
    }

    @Test
    void benchmark4RayBatched() {
        // Test the 4-ray batched SIMD version
        float v0x = tetVerts[0], v0y = tetVerts[1], v0z = tetVerts[2];
        float v1x = tetVerts[3], v1y = tetVerts[4], v1z = tetVerts[5];
        float v2x = tetVerts[6], v2y = tetVerts[7], v2z = tetVerts[8];
        float v3x = tetVerts[9], v3y = tetVerts[10], v3z = tetVerts[11];

        Point3f v0 = new Point3f(v0x, v0y, v0z);
        Point3f v1 = new Point3f(v1x, v1y, v1z);
        Point3f v2 = new Point3f(v2x, v2y, v2z);
        Point3f v3 = new Point3f(v3x, v3y, v3z);

        int numBatches = 25_000;  // 100k rays / 4
        int numRays = numBatches * 4;
        int warmup = 2500;

        // Generate random rays in batches of 4
        float[][] rayOx = new float[numBatches][4];
        float[][] rayOy = new float[numBatches][4];
        float[][] rayOz = new float[numBatches][4];
        float[][] rayDx = new float[numBatches][4];
        float[][] rayDy = new float[numBatches][4];
        float[][] rayDz = new float[numBatches][4];
        Point3f[][] origins = new Point3f[numBatches][4];
        Vector3f[][] dirs = new Vector3f[numBatches][4];

        for (int b = 0; b < numBatches; b++) {
            for (int i = 0; i < 4; i++) {
                float ox = random.nextFloat() * 2 - 0.5f;
                float oy = random.nextFloat() * 2 - 0.5f;
                float oz = -1 + random.nextFloat() * 0.1f;
                float dx = random.nextFloat() * 0.2f - 0.1f;
                float dy = random.nextFloat() * 0.2f - 0.1f;
                float dz = 1;
                float len = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
                dx /= len; dy /= len; dz /= len;

                rayOx[b][i] = ox; rayOy[b][i] = oy; rayOz[b][i] = oz;
                rayDx[b][i] = dx; rayDy[b][i] = dy; rayDz[b][i] = dz;
                origins[b][i] = new Point3f(ox, oy, oz);
                dirs[b][i] = new Vector3f(dx, dy, dz);
            }
        }

        // Warmup scalar
        var scalarResult = new MollerTrumboreIntersection.TetrahedronResult();
        for (int b = 0; b < warmup; b++) {
            for (int i = 0; i < 4; i++) {
                scalar.intersectTetrahedron(origins[b % numBatches][i], dirs[b % numBatches][i], v0, v1, v2, v3, scalarResult);
            }
        }

        // Benchmark scalar (4 rays at a time)
        int scalarHits = 0;
        long scalarStart = System.nanoTime();
        for (int b = 0; b < numBatches; b++) {
            for (int i = 0; i < 4; i++) {
                if (scalar.intersectTetrahedron(origins[b][i], dirs[b][i], v0, v1, v2, v3, scalarResult)) {
                    scalarHits++;
                }
            }
        }
        long scalarTime = System.nanoTime() - scalarStart;

        // Warmup SIMD batched
        var simdResults = new MollerTrumboreSIMD.TetrahedronResult[4];
        for (int i = 0; i < 4; i++) simdResults[i] = new MollerTrumboreSIMD.TetrahedronResult();
        for (int b = 0; b < warmup; b++) {
            simd.intersect4RaysTet(
                rayOx[b % numBatches], rayOy[b % numBatches], rayOz[b % numBatches],
                rayDx[b % numBatches], rayDy[b % numBatches], rayDz[b % numBatches],
                v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, v3x, v3y, v3z,
                simdResults);
        }

        // Benchmark SIMD batched
        int simdHits = 0;
        long simdStart = System.nanoTime();
        for (int b = 0; b < numBatches; b++) {
            simdHits += simd.intersect4RaysTet(
                rayOx[b], rayOy[b], rayOz[b],
                rayDx[b], rayDy[b], rayDz[b],
                v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, v3x, v3y, v3z,
                simdResults);
        }
        long simdTime = System.nanoTime() - simdStart;

        // Results
        double scalarMs = scalarTime / 1_000_000.0;
        double simdMs = simdTime / 1_000_000.0;
        double speedup = scalarTime / (double) simdTime;
        double scalarRaysPerMs = numRays / scalarMs;
        double simdRaysPerMs = numRays / simdMs;

        System.out.println("\n=== 4-Ray Batched SIMD Benchmark ===");
        System.out.printf("Rays tested: %,d (in %,d batches of 4)%n", numRays, numBatches);
        System.out.printf("Scalar: %.2f ms (%.0f rays/ms), hits: %d%n", scalarMs, scalarRaysPerMs, scalarHits);
        System.out.printf("SIMD 4x: %.2f ms (%.0f rays/ms), hits: %d%n", simdMs, simdRaysPerMs, simdHits);
        System.out.printf("Speedup: %.2fx%n", speedup);
        System.out.println("=====================================\n");

        // Note: On ARM, Java Vector API overhead often exceeds SIMD gains
        // GPU compute (raycast_esvt.comp) is the recommended path for performance
        if (speedup < 1.0) {
            System.out.println("Finding: SIMD vectorization NOT beneficial on this platform");
            System.out.println("Recommendation: Use GPU compute shader for performance");
        }
    }
}

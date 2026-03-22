// SPDX-License-Identifier: AGPL-3.0
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;

/**
 * Comparative benchmark: 12-DOP vs determinant-based containment, and 12-DOP intersection throughput.
 * <p>
 * Run: {@code mvn test -pl lucien -Dtest=Tet12DOPBenchmark -Dsurefire.rerunFailingTestsCount=0}
 */
@Tag("performance")
@SuppressWarnings("deprecation")
class Tet12DOPBenchmark {

    private static final int WARMUP = 200;
    private static final int MEASURE = 2_000;
    private static final int POINTS_PER_ITER = 1_000;
    private static final int AABBS_PER_ITER = 500;
    private static final int TETS_PER_ITER = 500;
    private static final byte LEVEL = 10;
    private static final long SEED = 42L;

    @Test
    void benchmarkAll() {
        int h = Constants.lengthAtLevel(LEVEL);
        var tets = new Tet[6];
        for (byte t = 0; t < 6; t++) {
            tets[t] = new Tet(0, 0, 0, LEVEL, t);
        }

        System.out.println();
        System.out.println("=== 12-DOP Benchmark (level=" + LEVEL + ", h=" + h + ") ===");
        System.out.println();

        benchmarkPointContainment(tets, h);
        benchmarkAABBIntersection(tets, h);
        benchmarkTetVsTetIntersection(h);
    }

    private void benchmarkPointContainment(Tet[] tets, int h) {
        var rng = new Random(SEED);
        float[][] points = new float[POINTS_PER_ITER][3];
        for (int i = 0; i < POINTS_PER_ITER; i++) {
            points[i][0] = rng.nextFloat() * h;
            points[i][1] = rng.nextFloat() * h;
            points[i][2] = rng.nextFloat() * h;
        }

        // Warmup + measure contains12DOP
        for (int w = 0; w < WARMUP; w++) {
            for (var tet : tets) for (var p : points) tet.contains12DOP(p[0], p[1], p[2]);
        }
        long start = System.nanoTime();
        int hits12 = 0;
        for (int m = 0; m < MEASURE; m++) {
            for (var tet : tets) for (var p : points) if (tet.contains12DOP(p[0], p[1], p[2])) hits12++;
        }
        long dop12Ns = System.nanoTime() - start;

        // Warmup + measure containsUltraFast
        for (int w = 0; w < WARMUP; w++) {
            for (var tet : tets) for (var p : points) tet.containsUltraFast(p[0], p[1], p[2]);
        }
        start = System.nanoTime();
        int hitsDet = 0;
        for (int m = 0; m < MEASURE; m++) {
            for (var tet : tets) for (var p : points) if (tet.containsUltraFast(p[0], p[1], p[2])) hitsDet++;
        }
        long detNs = System.nanoTime() - start;

        long totalOps = (long) MEASURE * tets.length * POINTS_PER_ITER;
        double dopNs = (double) dop12Ns / totalOps;
        double detNsOp = (double) detNs / totalOps;

        System.out.println("--- Point Containment (11 ops vs 84 ops) ---");
        System.out.printf("  contains12DOP:     %5.1f ns/op  (hits=%d)%n", dopNs, hits12);
        System.out.printf("  containsUltraFast: %5.1f ns/op  (hits=%d)%n", detNsOp, hitsDet);
        System.out.printf("  Speedup: %.1fx%n%n", detNsOp / dopNs);
    }

    private void benchmarkAABBIntersection(Tet[] tets, int h) {
        var rng = new Random(SEED + 1);
        float[][] aabbs = new float[AABBS_PER_ITER][6];
        for (int i = 0; i < AABBS_PER_ITER; i++) {
            float cx = rng.nextFloat() * h, cy = rng.nextFloat() * h, cz = rng.nextFloat() * h;
            float sz = (0.1f + rng.nextFloat() * 0.4f) * h;
            aabbs[i] = new float[] { cx - sz, cy - sz, cz - sz, cx + sz, cy + sz, cz + sz };
        }

        // Warmup + measure intersects12DOP
        for (int w = 0; w < WARMUP; w++) {
            for (var tet : tets) for (var a : aabbs) tet.intersects12DOP(a[0], a[1], a[2], a[3], a[4], a[5]);
        }
        long start = System.nanoTime();
        int hits = 0;
        for (int m = 0; m < MEASURE; m++) {
            for (var tet : tets) for (var a : aabbs) if (tet.intersects12DOP(a[0], a[1], a[2], a[3], a[4], a[5])) hits++;
        }
        long ns = System.nanoTime() - start;

        long totalOps = (long) MEASURE * tets.length * AABBS_PER_ITER;
        double nsPerOp = (double) ns / totalOps;

        System.out.println("--- AABB-vs-Tet Intersection (18 ops) ---");
        System.out.printf("  intersects12DOP: %5.1f ns/op  (hits=%d / %d)%n", nsPerOp, hits, totalOps);
        System.out.println();
    }

    private void benchmarkTetVsTetIntersection(int h) {
        var rng = new Random(SEED + 2);
        Tet[] tetsA = new Tet[TETS_PER_ITER];
        Tet[] tetsB = new Tet[TETS_PER_ITER];
        for (int i = 0; i < TETS_PER_ITER; i++) {
            tetsA[i] = new Tet(rng.nextInt(4) * h, rng.nextInt(4) * h, rng.nextInt(4) * h, LEVEL, (byte) rng.nextInt(6));
            tetsB[i] = new Tet(rng.nextInt(4) * h, rng.nextInt(4) * h, rng.nextInt(4) * h, LEVEL, (byte) rng.nextInt(6));
        }

        // Build vertex arrays for SAT comparison
        Point3f[][] vertsA = new Point3f[TETS_PER_ITER][];
        Point3f[][] vertsB = new Point3f[TETS_PER_ITER][];
        for (int i = 0; i < TETS_PER_ITER; i++) {
            vertsA[i] = toPoint3f(tetsA[i].coordinates());
            vertsB[i] = toPoint3f(tetsB[i].coordinates());
        }

        // Warmup + measure intersectsTet12DOP
        for (int w = 0; w < WARMUP; w++) {
            for (int i = 0; i < TETS_PER_ITER; i++) tetsA[i].intersectsTet12DOP(tetsB[i]);
        }
        long start = System.nanoTime();
        int hits12 = 0;
        for (int m = 0; m < MEASURE; m++) {
            for (int i = 0; i < TETS_PER_ITER; i++) if (tetsA[i].intersectsTet12DOP(tetsB[i])) hits12++;
        }
        long dop12Ns = System.nanoTime() - start;

        // Warmup + measure SAT tet-vs-tet
        for (int w = 0; w < WARMUP; w++) {
            for (int i = 0; i < TETS_PER_ITER; i++) TetrahedralGeometry.tetrahedraIntersect(vertsA[i], vertsB[i]);
        }
        start = System.nanoTime();
        int hitsSAT = 0;
        for (int m = 0; m < MEASURE; m++) {
            for (int i = 0; i < TETS_PER_ITER; i++) if (TetrahedralGeometry.tetrahedraIntersect(vertsA[i], vertsB[i])) hitsSAT++;
        }
        long satNs = System.nanoTime() - start;

        long totalOps = (long) MEASURE * TETS_PER_ITER;
        double dopNsOp = (double) dop12Ns / totalOps;
        double satNsOp = (double) satNs / totalOps;

        System.out.println("--- Tet-vs-Tet Intersection (18 ops vs SAT) ---");
        System.out.printf("  intersectsTet12DOP: %5.1f ns/op  (hits=%d)%n", dopNsOp, hits12);
        System.out.printf("  SAT tetrahedra:     %5.1f ns/op  (hits=%d)%n", satNsOp, hitsSAT);
        System.out.printf("  Speedup: %.1fx%n%n", satNsOp / dopNsOp);
    }

    private static Point3f[] toPoint3f(javax.vecmath.Point3i[] coords) {
        var result = new Point3f[coords.length];
        for (int i = 0; i < coords.length; i++) {
            result[i] = new Point3f(coords[i].x, coords[i].y, coords[i].z);
        }
        return result;
    }
}

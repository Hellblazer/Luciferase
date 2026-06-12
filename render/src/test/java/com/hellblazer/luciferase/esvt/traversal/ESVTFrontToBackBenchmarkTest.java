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
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.vecmath.Matrix4f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmark test for the ESVT front-to-back traversal arc (2s3jc).
 *
 * <h2>Two test methods with distinct roles</h2>
 *
 * <h3>testBaselineIterations — depth-6 regression guard (1-hit scene)</h3>
 * Runs on the seeded depth-6 sphere scene (Random(42), 4096 rays, camera (2,2,2)).
 * This scene produces only 1 hitting ray; pruning never fires because bestT does not
 * shrink before the single leaf is reached. {@code PRUNE_MARGIN = 1.01} is a
 * non-regression bound (at most 1% overhead vs exhaustive), NOT a perf-evidence claim.
 * {@code FRONTTOBACK_MEAN_ITERATIONS = 13.0} pins the deterministic result; any
 * algorithm change that visits more nodes shows up as a test failure.
 *
 * <h3>testDenseScenePruning — depth-3 perf gate (multi-hit scene)</h3>
 * Runs on the same scene at depth 3 (larger leaf tets → many more hits per 64×64 grid).
 * This is the actual perf-evidence test: with many hits, bestT shrinks early and pruning
 * fires for later siblings. {@code DENSE_PRUNE_MARGIN = 0.70} is the required reduction;
 * observed ratio is pinned as {@code DENSE_FRONTTOBACK_MEAN_ITERATIONS}.
 * {@code DENSE_HIT_COUNT_FLOOR >= 40} guards against scene degeneration (observed: 44 hits).
 *
 * <p>Both baselines were captured using {@code git stash} to revert to the exhaustive
 * code, running the tests to observe the exhaustive numbers, then {@code git stash pop}
 * to restore the P2 rewrite and observe the front-to-back numbers.
 *
 * @author hal.hildebrand
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ESVTFrontToBackBenchmarkTest {

    /** Frame size (64×64 = 4096 rays), matching parity test. */
    private static final int FRAME_SIZE = 64;

    // -------------------------------------------------------------------------
    // P1 baseline constant: exhaustive all-8 Morton child scan.
    // Set by running the test once on unmodified exhaustive code; do NOT change
    // this constant without a documented reason — it is the regression anchor.
    // Value will be filled in after first run (see test output).
    // -------------------------------------------------------------------------
    /**
     * Mean iterations per hitting ray with the exhaustive (pre-P2) traversal.
     * Captured 2026-06-11: hitCount=1, totalIterations=13, mean=13.000000.
     * Scene: Random(42), depth 6, 2000 sphere samples, radius 0.3.
     * Rays: 64×64 grid, camera (2,2,2) lookAt (0.5,0.5,0.5) fov 60.
     */
    static final double BASELINE_MEAN_ITERATIONS_EXHAUSTIVE = 13.0;

    // -------------------------------------------------------------------------
    // P2 constants (captured 2026-06-11 after CPU rewrite):
    //   FRONTTOBACK_MEAN_ITERATIONS: exact mean after P2 rewrite (pin, ±epsilon)
    //   PRUNE_MARGIN: max fraction of baseline allowed
    //
    // NOTE on pruning benefit with this specific scene:
    //   The sphere at depth 6 with camera (2,2,2) produces only 1 hitting ray from
    //   the 64×64 grid. With a single hit, bestT never shrinks from a prior hit to
    //   allow pruning of siblings — the prune-break fires AFTER the single leaf is
    //   reached, at which point traversal is essentially complete. So the iteration
    //   count is identical to the exhaustive baseline (13) for this scene.
    //
    //   The pruning benefit is real and would be visible on a denser scene where
    //   many rays hit and bestT shrinks from earlier hits. This scene serves as a
    //   regression guard: if the rewrite introduces extra work (iterations > baseline),
    //   the PRUNE_MARGIN assertion catches it. PRUNE_MARGIN = 1.01 means "at most
    //   1% overhead allowed; must not be worse than exhaustive". The exact-pin on
    //   FRONTTOBACK_MEAN_ITERATIONS pins the deterministic result.
    // -------------------------------------------------------------------------
    /**
     * Regression guard for the 1-hit depth-6 scene: at most 1% overhead vs exhaustive.
     * Pruning never fires on this scene (single hit). The real perf gate is
     * {@code DENSE_PRUNE_MARGIN} in {@code testDenseScenePruning}.
     */
    static final double PRUNE_MARGIN = 1.01;

    /**
     * Exact mean iterations per hit after P2 rewrite.
     * Captured 2026-06-11: hitCount=1, totalIterations=13, mean=13.000000.
     * Same as baseline — pruning does not trigger on a 1-hit scene.
     */
    static final double FRONTTOBACK_MEAN_ITERATIONS = 13.0;

    // -------------------------------------------------------------------------
    // Dense scene constants (depth-3 sphere, same Random(42), same camera).
    // Captured via git stash (exhaustive) → run → record → git stash pop → run → record.
    // -------------------------------------------------------------------------

    /**
     * Minimum number of hitting rays in the depth-3 scene.
     * Guards against silent scene degeneration to a near-zero-hit case.
     * Observed (2026-06-11, exhaustive): hitCount=44 from a depth-3 sphere with 64×64 rays.
     * Floor set to 40 (10% below observed) to catch scene degeneration while tolerating
     * minor floating-point-path variation across JVMs.
     */
    static final int DENSE_HIT_COUNT_FLOOR = 40;

    /**
     * Mean iterations per hit in the depth-3 scene with the exhaustive traversal.
     * Captured 2026-06-11 via git stash procedure (exhaustive code):
     * hitCount=44, totalIterations=488, mean=11.090909.
     * Scene: Random(42), depth 3, 2000 sphere samples, radius 0.3.
     * Rays: 64×64 grid, camera (2,2,2) lookAt (0.5,0.5,0.5) fov 60.
     */
    static final double DENSE_BASELINE_MEAN_ITERATIONS_EXHAUSTIVE = 11.090909;

    /**
     * Required prune fraction for the dense scene. Front-to-back must visit at most
     * 70% of the iterations the exhaustive scan does. If observed ratio is above 0.70,
     * implementation must be re-examined before loosening this constant.
     */
    static final double DENSE_PRUNE_MARGIN = 0.70;

    /**
     * Exact mean iterations per hit after P2 rewrite on the dense (depth-3) scene.
     * Captured 2026-06-11 via git stash pop procedure (front-to-back code):
     * hitCount=44, totalIterations=296, mean=6.727273.
     * Ratio vs exhaustive: 6.727273 / 11.090909 = 0.606 (beats DENSE_PRUNE_MARGIN=0.70).
     */
    static final double DENSE_FRONTTOBACK_MEAN_ITERATIONS = 6.727273;

    /** Tolerance for double mean comparison (means of integers; effectively exact). */
    private static final double MEAN_EPSILON = 1e-3;

    private ESVTData testData;
    private List<ESVTRay> rays;

    @BeforeAll
    void setup() {
        testData = createTestSphere(6);
        System.out.println("[ESVTFrontToBackBenchmarkTest] nodes=" + testData.nodes().length);

        var cameraPos = new Vector3f(2.0f, 2.0f, 2.0f);
        var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);
        rays = generateRays(cameraPos, lookAt, 60.0f, FRAME_SIZE, FRAME_SIZE);
        System.out.println("[ESVTFrontToBackBenchmarkTest] rays=" + rays.size());
    }

    /**
     * P1: Run the traversal on the seeded scene and print the deterministic baseline.
     *
     * <p>On current exhaustive code this test captures the mean and prints it. Once
     * the constant {@code BASELINE_MEAN_ITERATIONS_EXHAUSTIVE} is filled in, the
     * equality assertion activates and pins the baseline.
     */
    @Test
    void testBaselineIterations() {
        var traversal = new ESVTTraversal();
        var nodes = testData.nodes();
        var farPointers = testData.farPointers();

        long totalIterations = 0;
        int hitCount = 0;

        for (var ray : rays) {
            var result = traversal.castRay(ray, nodes, null, farPointers, 0);
            if (result.hit) {
                totalIterations += result.iterations;
                hitCount++;
            }
        }

        assertTrue(hitCount > 0, "Scene must produce at least one hit (non-vacuous)");

        double mean = (double) totalIterations / hitCount;
        System.out.printf("[P1-baseline] hitCount=%d, totalIterations=%d, meanIterations=%.6f%n",
                          hitCount, totalIterations, mean);
        System.out.printf("[P1-baseline] *** BASELINE_MEAN_ITERATIONS_EXHAUSTIVE = %.6f ***%n", mean);

        if (BASELINE_MEAN_ITERATIONS_EXHAUSTIVE >= 0.0) {
            // Baseline pinned: assert exact equality (deterministic CPU-side)
            assertTrue(Math.abs(mean - BASELINE_MEAN_ITERATIONS_EXHAUSTIVE) < MEAN_EPSILON,
                       String.format("Baseline changed! expected=%.6f actual=%.6f delta=%.6f",
                                     BASELINE_MEAN_ITERATIONS_EXHAUSTIVE, mean,
                                     Math.abs(mean - BASELINE_MEAN_ITERATIONS_EXHAUSTIVE)));
        }
        // else: first run — just print; caller reads console and sets the constant.

        // P2 ratio assertion (active only after BASELINE is set and P2 rewrite is done)
        if (BASELINE_MEAN_ITERATIONS_EXHAUSTIVE >= 0.0 && FRONTTOBACK_MEAN_ITERATIONS >= 0.0) {
            // After P2: assert mean beat the baseline by the required margin
            assertTrue(mean < BASELINE_MEAN_ITERATIONS_EXHAUSTIVE * PRUNE_MARGIN,
                       String.format("Front-to-back did NOT beat baseline: mean=%.6f baseline=%.6f margin=%.2f required<%.6f",
                                     mean, BASELINE_MEAN_ITERATIONS_EXHAUSTIVE, PRUNE_MARGIN,
                                     BASELINE_MEAN_ITERATIONS_EXHAUSTIVE * PRUNE_MARGIN));

            // And pin the new exact mean
            assertTrue(Math.abs(mean - FRONTTOBACK_MEAN_ITERATIONS) < MEAN_EPSILON,
                       String.format("Front-to-back mean changed! expected=%.6f actual=%.6f",
                                     FRONTTOBACK_MEAN_ITERATIONS, mean));
        }
    }

    /**
     * Depth-3 dense scene perf gate (2s3jc P2).
     *
     * <p>At depth 3, leaf tetrahedra are 8× larger per linear dimension than depth 6.
     * The 64×64 ray grid hits many more cells, bestT shrinks early from front-facing hits,
     * and the prune-break fires for back-facing siblings — demonstrating the actual
     * algorithmic win.
     *
     * <p>Assertions:
     * <ul>
     *   <li>Dense hitCount >= {@code DENSE_HIT_COUNT_FLOOR} (scene is not degenerate)</li>
     *   <li>mean < {@code DENSE_BASELINE_MEAN_ITERATIONS_EXHAUSTIVE * DENSE_PRUNE_MARGIN}
     *       (front-to-back beats exhaustive by ≥ 30%)</li>
     *   <li>mean == {@code DENSE_FRONTTOBACK_MEAN_ITERATIONS} ± epsilon (exact pin)</li>
     * </ul>
     *
     * <p>Constants captured via stash procedure: exhaustive baseline measured on
     * unmodified code, then stash popped to measure front-to-back numbers.
     */
    @Test
    void testDenseScenePruning() {
        var denseData = createTestSphere(3);
        System.out.printf("[dense] nodes=%d%n", denseData.nodes().length);

        var cameraPos = new Vector3f(2.0f, 2.0f, 2.0f);
        var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);
        var denseRays = generateRays(cameraPos, lookAt, 60.0f, FRAME_SIZE, FRAME_SIZE);

        var traversal = new ESVTTraversal();
        var nodes = denseData.nodes();
        var farPointers = denseData.farPointers();

        long totalIterations = 0;
        int hitCount = 0;

        for (var ray : denseRays) {
            var result = traversal.castRay(ray, nodes, null, farPointers, 0);
            if (result.hit) {
                totalIterations += result.iterations;
                hitCount++;
            }
        }

        System.out.printf("[dense] hitCount=%d, totalIterations=%d%n", hitCount, totalIterations);
        assertTrue(hitCount > 0, "Dense scene must produce at least one hit");

        double mean = (double) totalIterations / hitCount;
        System.out.printf("[dense] meanIterations=%.6f%n", mean);
        System.out.printf("[dense] *** DENSE current mean = %.6f ***%n", mean);

        // Non-vacuous scene guard
        assertTrue(hitCount >= DENSE_HIT_COUNT_FLOOR,
                   String.format("Dense scene hitCount=%d below floor=%d — scene may have degenerated",
                                 hitCount, DENSE_HIT_COUNT_FLOOR));

        // Both constants must be set (i.e., P2 rewrite active) before the perf gate fires.
        // While DENSE_FRONTTOBACK_MEAN_ITERATIONS is -1 (sentinel), we are still capturing
        // baselines; assertions are skipped so exhaustive code passes this test.
        if (DENSE_BASELINE_MEAN_ITERATIONS_EXHAUSTIVE >= 0.0 && DENSE_FRONTTOBACK_MEAN_ITERATIONS >= 0.0) {
            // Perf gate: front-to-back must beat exhaustive by DENSE_PRUNE_MARGIN
            double required = DENSE_BASELINE_MEAN_ITERATIONS_EXHAUSTIVE * DENSE_PRUNE_MARGIN;
            assertTrue(mean < required,
                       String.format("Front-to-back did NOT beat dense baseline: mean=%.6f baseline=%.6f margin=%.2f required<%.6f (ratio=%.3f)",
                                     mean, DENSE_BASELINE_MEAN_ITERATIONS_EXHAUSTIVE, DENSE_PRUNE_MARGIN, required,
                                     mean / DENSE_BASELINE_MEAN_ITERATIONS_EXHAUSTIVE));

            // Exact regression pin: any future change to traversal order shows up here
            assertTrue(Math.abs(mean - DENSE_FRONTTOBACK_MEAN_ITERATIONS) < MEAN_EPSILON,
                       String.format("Dense front-to-back mean changed! expected=%.6f actual=%.6f",
                                     DENSE_FRONTTOBACK_MEAN_ITERATIONS, mean));

            System.out.printf("[dense] *** ratio = %.3f (baseline=%.6f f2b=%.6f) ***%n",
                              mean / DENSE_BASELINE_MEAN_ITERATIONS_EXHAUSTIVE,
                              DENSE_BASELINE_MEAN_ITERATIONS_EXHAUSTIVE, mean);
        } else {
            // Baseline captured; front-to-back constant not yet filled — print for recording.
            System.out.printf("[dense] *** DENSE_FRONTTOBACK_MEAN_ITERATIONS candidate = %.6f (fill this in after git stash pop) ***%n", mean);
        }
    }

    // -------------------------------------------------------------------------
    // Scene construction — verbatim from ESVTGPUCPUParityTest.createTestSphere
    // (method is private there; duplicated here with Random(42) seed intact).
    // Any drift from the original invalidates the baseline.
    // -------------------------------------------------------------------------
    private ESVTData createTestSphere(int depth) {
        var random = new Random(42);
        var tetree = new Tetree<>(new SequentialLongIDGenerator());
        var builder = new ESVTBuilder();

        float center = 0.5f;
        float radius = 0.3f;
        int samples = 2000;

        for (int i = 0; i < samples; i++) {
            float x = random.nextFloat();
            float y = random.nextFloat();
            float z = random.nextFloat();

            float dx = x - center;
            float dy = y - center;
            float dz = z - center;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist <= radius) {
                float scale = Constants.lengthAtLevel((byte) 0);
                tetree.insert(new Point3f(x * scale, y * scale, z * scale), (byte) depth, "Entity" + i);
            }
        }

        return builder.build(tetree);
    }

    // -------------------------------------------------------------------------
    // Ray generation — verbatim from ESVTGPUCPUParityTest.generateRays
    // -------------------------------------------------------------------------
    private List<ESVTRay> generateRays(Vector3f cameraPos, Vector3f lookAt, float fov,
                                      int width, int height) {
        var rays = new ArrayList<ESVTRay>(width * height);

        var viewMatrix = createViewMatrix(cameraPos, lookAt, new Vector3f(0, 1, 0));
        float aspect = (float) width / height;
        var projMatrix = createPerspectiveMatrix(fov, aspect, 0.1f, 100.0f);

        var invView = new Matrix4f();
        invView.invert(viewMatrix);
        var invProj = new Matrix4f();
        invProj.invert(projMatrix);

        var cameraPosWorld = new Vector3f(invView.m03, invView.m13, invView.m23);
        var cameraPosDataSpace = new Vector3f(cameraPosWorld);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float ndcX = (2.0f * x / width) - 1.0f;
                float ndcY = 1.0f - (2.0f * y / height);

                var clipPos = new Vector4f(ndcX, ndcY, -1.0f, 1.0f);
                var viewPos = new Vector4f();
                invProj.transform(clipPos, viewPos);
                viewPos.scale(1.0f / viewPos.w);

                var worldPos = new Vector4f();
                invView.transform(viewPos, worldPos);

                var rayDir = new Vector3f(
                    worldPos.x - cameraPosDataSpace.x,
                    worldPos.y - cameraPosDataSpace.y,
                    worldPos.z - cameraPosDataSpace.z
                );
                rayDir.normalize();

                var ray = new ESVTRay(
                    cameraPosDataSpace.x, cameraPosDataSpace.y, cameraPosDataSpace.z,
                    rayDir.x, rayDir.y, rayDir.z
                );
                ray.tMin = 0.001f;
                ray.tMax = 1000.0f;

                rays.add(ray);
            }
        }
        return rays;
    }

    private Matrix4f createViewMatrix(Vector3f eye, Vector3f center, Vector3f up) {
        var f = new Vector3f();
        f.sub(center, eye);
        f.normalize();

        var actualUp = new Vector3f(up);
        var s = new Vector3f();
        s.cross(f, actualUp);
        if (s.lengthSquared() < 1e-6f) {
            actualUp.set(0, 0, 1);
            s.cross(f, actualUp);
            if (s.lengthSquared() < 1e-6f) {
                actualUp.set(1, 0, 0);
                s.cross(f, actualUp);
            }
        }
        s.normalize();

        var u = new Vector3f();
        u.cross(s, f);

        var view = new Matrix4f();
        view.m00 = s.x;  view.m01 = s.y;  view.m02 = s.z;  view.m03 = -s.dot(eye);
        view.m10 = u.x;  view.m11 = u.y;  view.m12 = u.z;  view.m13 = -u.dot(eye);
        view.m20 = -f.x; view.m21 = -f.y; view.m22 = -f.z; view.m23 = f.dot(eye);
        view.m30 = 0;    view.m31 = 0;    view.m32 = 0;    view.m33 = 1;
        return view;
    }

    private Matrix4f createPerspectiveMatrix(float fovDegrees, float aspect, float near, float far) {
        float fovRad = (float) Math.toRadians(fovDegrees);
        float f = 1.0f / (float) Math.tan(fovRad / 2.0f);

        var proj = new Matrix4f();
        proj.m00 = f / aspect;
        proj.m11 = f;
        proj.m22 = (far + near) / (near - far);
        proj.m23 = (2 * far * near) / (near - far);
        proj.m32 = -1;
        proj.m33 = 0;
        return proj;
    }
}

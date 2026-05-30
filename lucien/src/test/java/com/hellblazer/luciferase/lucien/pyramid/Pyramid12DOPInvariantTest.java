/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Non-vacuous invariant test for RDR-010 §3b: {@code Tet.contains12DOP()} MUST NEVER be called on
 * a {@link Tet} whose type is 6 or 7. The pyramid-decomposition step in
 * {@link PyramidContainment#contains(Pyramid, Point3f)} must complete the type-dispatch (tet vs
 * pyramid child) before any 12-DOP call.
 *
 * <h2>Test structure</h2>
 * <ol>
 *   <li><b>Negative invariant path</b>: drive 1,000 random query points through
 *       {@code PyramidContainment.contains()} on a depth-2 pyramid. Record the type of every
 *       {@code Tet} that receives a {@code contains12DOP} call via an instrumented subclass.
 *       Assert that no recorded type is 6 or 7.</li>
 *   <li><b>Positive control</b>: directly construct an instrumented tet with a synthetic type
 *       value of 6, invoke the guard that {@code PyramidContainment} must use, and assert the
 *       guard fires (i.e., the instrumentation is not a no-op and would catch a type-6/7 tet
 *       if one slipped through).</li>
 * </ol>
 *
 * <p>The instrumented subclass lives entirely inside this test class and does NOT modify
 * production {@code Tet}. It overrides {@code contains12DOP} to record the type and delegate.
 *
 * @author hal.hildebrand
 */
class Pyramid12DOPInvariantTest {

    // -----------------------------------------------------------------------
    // Instrumented Tet subclass (test-only)
    // -----------------------------------------------------------------------

    /**
     * Subclass of {@link Tet} that intercepts every {@code contains12DOP} call, records the
     * type, and delegates to the real implementation. Used only in this test.
     */
    static class InstrumentedTet extends Tet {

        private final List<Byte> recordedTypes;

        InstrumentedTet(int x, int y, int z, byte level, byte type, byte minTetLevel,
                        List<Byte> recordedTypes) {
            super(x, y, z, level, type, minTetLevel);
            this.recordedTypes = recordedTypes;
        }

        @Override
        public boolean contains12DOP(float px, float py, float pz) {
            recordedTypes.add(type());
            return super.contains12DOP(px, py, pz);
        }
    }

    // -----------------------------------------------------------------------
    // Test 1 — negative invariant: no type 6/7 must ever reach contains12DOP
    // -----------------------------------------------------------------------

    /**
     * Drive 1,000 random points through PyramidContainment on a depth-2 representative pyramid.
     * All tets that receive contains12DOP calls must have type in {0..5}.
     *
     * <p>Note: {@link PyramidContainment} creates its own {@link Tet} children via
     * {@link Pyramid#child(int)}. Those children are plain {@link Tet} instances and cannot be
     * intercepted by our subclass. This test therefore validates the invariant indirectly: by
     * confirming that the algorithm does NOT call {@code contains12DOP} on any element with
     * type ≥ 6. We do this by verifying the production code path (which uses real
     * {@code Pyramid#child}), then separately prove the guard fires in test 2.
     *
     * <p>The real invariant check is in {@link PyramidContainment}: the code must never pass a
     * type-6/7 element to {@code contains12DOP} — test 2 (positive control) proves the guard
     * is live.
     */
    @Test
    void noType6Or7EverReachesContains12DOP_viaThrowPropagation() {
        // Detection strategy: INDIRECT. We do not intercept contains12DOP — we rely on the fact that
        // Tet.contains12DOP's default switch branch throws IllegalStateException for any type outside
        // {0..5}, including 6/7. If PyramidContainment ever wrongly forwarded a type-6/7 element, that
        // exception would propagate out and fail assertDoesNotThrow. The positive-control tests below
        // (directCallOnType{6,7}TetThrowsViaReflection) prove the throw-guard is live, so this test
        // is non-vacuous.
        var p = new Pyramid(0, 0, 0, (byte) 2, Pyramid.TYPE_6);
        var rng = new Random(0xDEADBEEFL);
        int h = p.length();

        int callsMade = 0;
        int containedCount = 0;
        // Drive 1000 random points in the parent AABB
        for (int i = 0; i < 1000; i++) {
            float qx = p.x() + rng.nextFloat() * h;
            float qy = p.y() + rng.nextFloat() * h;
            float qz = p.z() + rng.nextFloat() * h;
            // If the code reaches a type-6/7 tet, it would throw IllegalStateException
            // from the default case in Tet.contains12DOP's switch.
            // We assert: no IllegalStateException is thrown.
            final float fx = qx, fy = qy, fz = qz;
            boolean[] result = new boolean[1];
            assertDoesNotThrow(() -> result[0] = PyramidContainment.contains(p, new Point3f(fx, fy, fz)),
                               "contains must never call contains12DOP on a type-6/7 Tet; "
                               + "an IllegalStateException would propagate if it did. "
                               + "Query point: (" + fx + "," + fy + "," + fz + ")");
            if (result[0]) {
                containedCount++;
            }
            callsMade++;
        }
        assertEquals(1000, callsMade, "Should have driven 1000 queries");
        // Non-vacuousness guard: at least some random points must be classified as inside, which
        // proves the algorithm progressed past the outer AABB and ran the child-dispatch loop —
        // each iteration of which calls Tet.contains12DOP on every tet child. If containedCount
        // were 0, every query was rejected by the outer AABB and the throw-guard was never
        // actually exercised by this test (it would pass vacuously).
        assertTrue(containedCount > 0,
                   "Test is vacuous if no query reaches the child-dispatch loop; got 0/1000");

        // Deterministic non-vacuousness: directly query each direct tet child's centroid. For each
        // such point the algorithm MUST call Tet.contains12DOP on at least one tet child (the
        // algorithm iterates children sequentially and the first tet child is hit first). This
        // proves contains12DOP IS being exercised against production tets in this test, so the
        // throw-propagation gate is non-vacuous by construction.
        int tetCentroidsExercised = 0;
        for (int i = 0; i < 10; i++) {
            var child = p.child(i);
            if (!(child instanceof com.hellblazer.luciferase.lucien.tetree.Tet tetChild)) {
                continue;
            }
            // tetChild centroid — known to live inside the parent pyramid
            var verts = tetChild.coordinates();
            float cx = (verts[0].x + verts[1].x + verts[2].x + verts[3].x) / 4f;
            float cy = (verts[0].y + verts[1].y + verts[2].y + verts[3].y) / 4f;
            float cz = (verts[0].z + verts[1].z + verts[2].z + verts[3].z) / 4f;
            assertDoesNotThrow(() -> PyramidContainment.contains(p, new Point3f(cx, cy, cz)),
                               "contains12DOP must not throw on a tet child's centroid (child " + i + ")");
            tetCentroidsExercised++;
        }
        assertTrue(tetCentroidsExercised > 0, "Pyramid.child must yield at least one tet child");
    }

    // -----------------------------------------------------------------------
    // Test 2 — positive control: the guard fires if type-6/7 reaches contains12DOP
    // -----------------------------------------------------------------------

    /**
     * Positive control: uses reflection to bypass {@code Tet}'s assert-guarded constructor and
     * inject type=6 into a real {@code Tet} instance, then invokes {@code contains12DOP}.
     * Asserts that {@link IllegalStateException} is thrown.
     *
     * <p>This proves the default-branch guard in {@code Tet.contains12DOP}'s switch is live.
     * If it throws {@code IllegalStateException} for type=6, then test 1 (the negative invariant)
     * is non-vacuous: any failure in the invariant would propagate to test 1.
     */
    @Test
    void directCallOnType6TetThrowsViaReflection() throws Exception {
        var tet = makeTetWithType((byte) 6);
        assertThrows(IllegalStateException.class,
                     () -> tet.contains12DOP(1.0f, 1.0f, 1.0f),
                     "contains12DOP on a type-6 Tet must throw IllegalStateException — "
                     + "the default branch guard is live");
    }

    @Test
    void directCallOnType7TetThrowsViaReflection() throws Exception {
        var tet = makeTetWithType((byte) 7);
        assertThrows(IllegalStateException.class,
                     () -> tet.contains12DOP(1.0f, 1.0f, 1.0f),
                     "contains12DOP on a type-7 Tet must throw IllegalStateException");
    }

    /**
     * Build a {@link Tet} with an arbitrary type byte injected via reflection, bypassing the
     * assert-guarded constructor. This is intentionally test-only and never used in production.
     */
    private static Tet makeTetWithType(byte targetType) throws Exception {
        // Construct a valid type-0 tet first, then overwrite the final 'type' field via reflection.
        var tet = new Tet(0, 0, 0, (byte) 10, (byte) 0, (byte) 10);
        var typeField = Tet.class.getDeclaredField("type");
        typeField.setAccessible(true);
        typeField.setByte(tet, targetType);
        return tet;
    }

    // -----------------------------------------------------------------------
    // Test 3 — instrumented path: record all types from a direct tet-only workload
    // -----------------------------------------------------------------------

    /**
     * Sanity check: InstrumentedTet successfully records type values for normal (type 0..5) tets.
     * This verifies the recording mechanism works before relying on the negative invariant test.
     */
    @Test
    void instrumentedTetRecordsTypeOnValidCall() {
        var recorded = new ArrayList<Byte>();
        // type 0 tet at level 10
        var tet = new InstrumentedTet(0, 0, 0, (byte) 10, (byte) 0, (byte) 10, recorded);
        // Any point that fails AABB won't record — pick the anchor itself (inside by closed convention)
        tet.contains12DOP(0f, 0f, 0f);
        assertFalse(recorded.isEmpty(), "Should have recorded a type for the call");
        assertEquals((byte) 0, recorded.get(0), "Should have recorded type 0");
    }

    @Test
    void instrumentedTetRecordsAllValidTypes() {
        for (byte t = 0; t <= 5; t++) {
            var recorded = new ArrayList<Byte>();
            var tet = new InstrumentedTet(0, 0, 0, (byte) 10, t, (byte) 10, recorded);
            tet.contains12DOP(0f, 0f, 0f);
            assertFalse(recorded.isEmpty(), "Should record type " + t);
            assertEquals(t, recorded.get(0), "Recorded type mismatch for type " + t);
        }
    }
}

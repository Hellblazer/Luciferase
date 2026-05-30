/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.tetree.Tet;

import javax.vecmath.Point3f;

/**
 * Standalone containment test for {@link Pyramid} elements using the decompose-and-reuse strategy
 * (RDR-010 §3b).
 *
 * <h2>Algorithm</h2>
 * A pyramid refines into ten children: six sub-pyramids (types 6/7) and four tetrahedra (types
 * 0–5). To test whether a query point Q is inside pyramid P:
 * <ol>
 *   <li>Iterate the 10 children via {@link Pyramid#child(int)}.</li>
 *   <li>For each <b>tet</b> child: call {@link Tet#contains12DOP(float, float, float)} — O(11)
 *       using the existing exact containment primitive. This is only valid for tet types 0–5;
 *       types 6/7 must never reach this call.</li>
 *   <li>For each <b>pyramid</b> child: recurse with a decremented depth budget.</li>
 *   <li>Return {@code true} as soon as any child reports containment; return {@code false} if all
 *       10 children report no containment.</li>
 * </ol>
 *
 * <h2>Termination rule</h2>
 * Recursion is bounded by {@link #MAX_RECURSION_DEPTH} (default 4). When the depth budget
 * reaches zero on a pyramid sub-child, the surrounding cube AABB is used as a conservative
 * fallback (which over-reports "inside" at cell boundaries but never misses a true interior
 * point). This guarantees O(10^MAX_RECURSION_DEPTH) total leaf tests in the worst case and
 * keeps the algorithm independent of the absolute refinement level.
 *
 * <p>Empirically (see {@code DecompositionCoverageTest}), MAX_RECURSION_DEPTH = 4 achieves
 * ≥99.9% coverage on uniform random samples across both pyramid types at representative levels.
 *
 * <h2>Critical invariant (RDR-010 §3b)</h2>
 * {@code Tet.contains12DOP()} MUST NEVER be called on a {@link Tet} whose type is 6 or 7. The
 * type-dispatch ({@code instanceof Tet} vs {@code instanceof Pyramid}) occurs BEFORE any 12-DOP
 * call. Because {@link Pyramid#child(int)} always produces tets with type ∈ {0..5} and pyramids
 * with type ∈ {6,7}, and this class branches on element type before dispatch, the invariant is
 * structurally enforced by the switch on dynamic type.
 *
 * <h2>Standalone contract</h2>
 * This class has no callers in the production index yet (Phase C/D/E wire it into
 * {@code PyramidIndex}). It is deliberately standalone — no changes to {@code Tet},
 * {@code Pyramid}, {@code PyramidIndex}, or any cluster interface.
 *
 * @author hal.hildebrand
 */
public final class PyramidContainment {

    /**
     * Maximum recursion depth into pyramid sub-children. At this depth, an AABB fallback is
     * used for any remaining pyramid child. A value of 4 gives worst-case 10^4 = 10,000 leaf
     * tests and ≥99.9% coverage per {@code DecompositionCoverageTest}.
     */
    public static final int MAX_RECURSION_DEPTH = 4;

    // Non-instantiable utility class
    private PyramidContainment() {
    }

    /**
     * Tests whether the query point {@code q} is inside pyramid {@code p}.
     *
     * <p>Uses the decompose-and-reuse strategy: decomposes {@code p} into its 10 children
     * ({@link Pyramid#child(int)}), tests tet children with {@link Tet#contains12DOP}, and
     * recurses into pyramid children up to {@link #MAX_RECURSION_DEPTH}.
     *
     * <p><b>Invariant</b>: {@code Tet.contains12DOP} is called only on elements whose type is
     * in {0..5}. Pyramid children (type 6/7) are handled by recursion, never by 12-DOP.
     *
     * @param p the pyramid to test containment in (must not be null)
     * @param q the query point (must not be null)
     * @return {@code true} if {@code q} is inside {@code p}
     */
    public static boolean contains(Pyramid p, Point3f q) {
        return containsRecursive(p, q.x, q.y, q.z, MAX_RECURSION_DEPTH);
    }

    /**
     * Recursive implementation with depth budget.
     *
     * @param p     the pyramid to test
     * @param qx    query point x
     * @param qy    query point y
     * @param qz    query point z
     * @param depth remaining recursion budget (≥ 0); when 0, pyramid children are tested via AABB
     * @return {@code true} if the query point is inside the pyramid
     */
    static boolean containsRecursive(Pyramid p, float qx, float qy, float qz, int depth) {
        // AABB early-out on the surrounding cube of this pyramid
        float minX = p.x(), minY = p.y(), minZ = p.z();
        float h = p.length();
        if (qx < minX || qx > minX + h || qy < minY || qy > minY + h || qz < minZ
        || qz > minZ + h) {
            return false;
        }

        // Decompose into 10 children and test each
        for (int i = 0; i < 10; i++) {
            var child = p.child(i);
            // CRITICAL INVARIANT: type-dispatch BEFORE any contains12DOP call.
            // Tet.contains12DOP is only called on Tet (type 0..5); Pyramid (type 6/7) never reaches it.
            if (child instanceof Tet t) {
                // Type is 0..5 by construction from Pyramid.child() — safe to call contains12DOP.
                if (t.contains12DOP(qx, qy, qz)) {
                    return true;
                }
            } else if (child instanceof Pyramid sub) {
                // Pyramid child: recurse if depth budget allows; otherwise use AABB fallback.
                if (depth > 0) {
                    if (containsRecursive(sub, qx, qy, qz, depth - 1)) {
                        return true;
                    }
                } else {
                    // Depth-limit fallback: conservative AABB test on the sub-pyramid's cube.
                    // This over-reports at cell boundaries but never under-reports true interior.
                    if (pointInCubeAABB(sub, qx, qy, qz)) {
                        return true;
                    }
                }
            }
            // Note: HybridElement is non-sealed; if some future subtype appears, it is ignored
            // (conservative: returns false for unrecognized types). This is safe.
        }
        return false;
    }

    /**
     * Conservative AABB containment test for the surrounding cube of a pyramid element.
     * Uses closed-interval convention (points on the boundary are considered inside).
     */
    private static boolean pointInCubeAABB(Pyramid e, float qx, float qy, float qz) {
        float minX = e.x(), minY = e.y(), minZ = e.z();
        float h = e.length();
        return qx >= minX && qx <= minX + h && qy >= minY && qy <= minY + h && qz >= minZ
        && qz <= minZ + h;
    }
}

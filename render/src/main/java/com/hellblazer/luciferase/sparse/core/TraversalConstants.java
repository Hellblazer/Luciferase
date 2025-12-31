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
package com.hellblazer.luciferase.sparse.core;

/**
 * Common constants for sparse voxel traversal.
 *
 * <p>These constants are shared between ESVO and ESVT ray traversal algorithms.
 * While the coordinate spaces differ ([1,2] for ESVO, [0,1] for ESVT), the
 * traversal mechanics share common parameters.
 *
 * <p><b>Usage:</b> Implementations may use these defaults or override with
 * their own values for specific optimizations.
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.esvo.core.ESVOTraversal
 * @see com.hellblazer.luciferase.esvt.traversal.ESVTTraversal
 */
public final class TraversalConstants {

    private TraversalConstants() {
        // Prevent instantiation
    }

    // === Traversal Limits ===

    /**
     * Default maximum ray traversal iterations.
     *
     * <p>Prevents infinite loops in edge cases. Both ESVO and ESVT use 10,000
     * as a reasonable upper bound that handles complex scenes while protecting
     * against runaway traversals.
     */
    public static final int MAX_ITERATIONS = 10_000;

    /**
     * Default maximum tree depth for ESVO octrees.
     *
     * <p>Depth 23 allows approximately 8 million (2^23) levels of subdivision,
     * which matches the mantissa precision of single-precision floats.
     */
    public static final int ESVO_MAX_DEPTH = 23;

    /**
     * Default maximum tree depth for ESVT tetrahedra.
     *
     * <p>Depth 21-22 is optimal for tetrahedral subdivision due to the
     * 3-bit encoding per level in the SFC index.
     */
    public static final int ESVT_MAX_DEPTH = 22;

    /**
     * Default traversal stack size.
     *
     * <p>Must be at least MAX_DEPTH + 1 to handle the deepest traversal path.
     * Using 24 provides margin for both ESVO (23) and ESVT (22) depths.
     */
    public static final int DEFAULT_STACK_SIZE = 24;

    // === Epsilon Values ===

    /**
     * Float epsilon for ray-node intersection tests.
     *
     * <p>Used to handle floating-point precision issues at node boundaries.
     * Value of 2^-23 matches single-precision float epsilon.
     */
    public static final float RAY_EPSILON = (float) Math.pow(2, -23);

    /**
     * General-purpose epsilon for geometric comparisons.
     */
    public static final float GEOMETRY_EPSILON = 1e-6f;

    /**
     * Epsilon for Moller-Trumbore ray-triangle intersection.
     *
     * <p>Smaller epsilon for high-precision triangle tests.
     */
    public static final float MOLLER_TRUMBORE_EPSILON = 1e-7f;

    // === Ray Buffer Sizes (for GPU/batch operations) ===

    /**
     * Size of a ray in floats: origin(3) + direction(3) + tMin(1) + tMax(1).
     */
    public static final int RAY_SIZE_FLOATS = 8;

    /**
     * Size of a ray in bytes (8 floats * 4 bytes).
     */
    public static final int RAY_SIZE_BYTES = RAY_SIZE_FLOATS * Float.BYTES;

    /**
     * Size of a basic result in floats: hit(1) + t(1) + x(1) + y(1) + z(1).
     *
     * <p>Note: Full TraversalResult contains more fields, but GPU kernels
     * often use a compact 4-5 float representation.
     */
    public static final int RESULT_SIZE_FLOATS = 4;

    /**
     * Size of a normal vector in floats: nx(1) + ny(1) + nz(1) + pad(1).
     */
    public static final int NORMAL_SIZE_FLOATS = 4;
}

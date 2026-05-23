/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.geometry.MortonCurve;

/**
 * Default-level heuristic for VoN spatial indexing (RDR-003 Phase 0 Step 0).
 * <p>
 * The Tetree subdivides the absolute integer-coordinate domain {@code [0, 2^21)}. At
 * refinement level {@code L} the cell-edge in those units is {@code 2^(21 - L)}. VoN entity
 * positions are placed directly into that coordinate system without rescaling, so AoI
 * radii are comparable to cell-edges 1:1.
 * <p>
 * The "sweet spot" for spatial-index pruning is {@code r &approx; 8&middot;cell-edge}
 * (RDR-003 &sect;Performance Expectations). Solving for {@code L} gives:
 * <pre>
 *   2^(21 - L) = r / 8
 *   21 - L     = log2(r) - 3
 *   L          = 24 - ceil(log2(r))
 * </pre>
 * which is then clamped to the safe range
 * {@code [MIN_USEFUL_LEVEL, MortonCurve.MAX_REFINEMENT_LEVEL]}.
 * <p>
 * For the default VoN configuration ({@code aoiRadius = 50}) this produces
 * {@link #DEFAULT_SPATIAL_LEVEL} {@code = 18} (cell-edge 8 units), giving {@code &asymp; 25}
 * cells across the 200-unit default world.
 *
 * @author hal.hildebrand
 */
public final class SpatialLevelHeuristic {

    /** Maximum refinement level supported by the Tetree (= {@link MortonCurve#MAX_REFINEMENT_LEVEL}). */
    public static final byte MAX_LEVEL = MortonCurve.MAX_REFINEMENT_LEVEL;

    /**
     * Minimum level the heuristic will return.
     * <p>
     * At level 8 the cell-edge is {@code 2^13 = 8192} units. Below this level cells span
     * the entire default-VoN world (200 units) and the spatial index degenerates to a
     * linear scan.
     */
    public static final byte MIN_USEFUL_LEVEL = 8;

    /**
     * Default-AoI radius assumed by the no-arg paths in {@code Manager} and
     * {@link BubbleBounds#fromEntityPositions(java.util.List)}.
     * <p>
     * Matches {@code Manager} default-constructor's historical {@code aoiRadius = 50.0f}.
     */
    public static final float DEFAULT_AOI_RADIUS = 50.0f;

    /**
     * Spatial level used by the no-arg default paths.
     * <p>
     * Computed at class-load time from {@link #DEFAULT_AOI_RADIUS}. With
     * {@code aoiRadius = 50} this evaluates to {@code 18}.
     */
    public static final byte DEFAULT_SPATIAL_LEVEL = computeDefault(DEFAULT_AOI_RADIUS);

    private SpatialLevelHeuristic() {
    }

    /**
     * Compute a sensible default refinement level for a given AoI radius.
     * <p>
     * Targets {@code r &approx; 8&middot;cell-edge} via
     * {@code L = clamp(24 &minus; ceil(log2(r)), MIN_USEFUL_LEVEL, MAX_LEVEL)}.
     *
     * @param aoiRadius positive, finite AoI radius in absolute Tetree coordinate units
     * @return a refinement level in {@code [MIN_USEFUL_LEVEL, MAX_LEVEL]}
     * @throws IllegalArgumentException if {@code aoiRadius} is not positive and finite
     */
    public static byte computeDefault(float aoiRadius) {
        if (!(aoiRadius > 0f) || !Float.isFinite(aoiRadius)) {
            throw new IllegalArgumentException(
                "aoiRadius must be positive and finite, got " + aoiRadius);
        }
        int level = 24 - (int) Math.ceil(Math.log(aoiRadius) / Math.log(2.0));
        if (level > MAX_LEVEL) {
            return MAX_LEVEL;
        }
        if (level < MIN_USEFUL_LEVEL) {
            return MIN_USEFUL_LEVEL;
        }
        return (byte) level;
    }
}

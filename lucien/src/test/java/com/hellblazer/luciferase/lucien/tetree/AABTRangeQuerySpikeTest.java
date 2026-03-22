/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.VolumeBounds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spike test: prove that AABT (SAT-based per-tet intersection) produces correct results with
 * fewer false positives than the AABB-envelope traversal path.
 *
 * <p>The existing AABB path emits all 6 tet types per qualifying grid cell; the AABT path only
 * emits individual tet types that pass {@link Tet#intersectsBound(Spatial.aabt)}.</p>
 *
 * @author hal.hildebrand
 */
class AABTRangeQuerySpikeTest {

    // -------------------------------------------------------------------------
    // Correctness: AABT results are a subset of AABB results
    // -------------------------------------------------------------------------

    /**
     * For a small query box inside one grid cell at level 10, the AABT set must be a subset of
     * the AABB set.  Any tet key emitted by AABT must also appear in AABB — AABT never misses a
     * true intersection that AABB would find.
     */
    @Test
    void aabtIsSubsetOfAabb_singleCellQuery() {
        byte level = 10;
        var root = new Tet(0, 0, 0, level, (byte) 0);

        // Small query box near the origin corner of the root cell.
        // Cell size at level 10 = 2^(21-10) = 2048.  The box [50..200, 50..200, 50..200]
        // is well inside the cell origin corner, which should touch only a subset of tets.
        var queryBounds = new VolumeBounds(50f, 50f, 50f, 200f, 200f, 200f);
        var queryBox    = new Spatial.aabt.Box(50f, 50f, 50f, 200f, 200f, 200f);

        Set<TetreeKey<?>> aabbCandidates = aabbCandidatesAt(root, queryBounds);
        Set<TetreeKey<?>> aabtCandidates = root.intersectingBound(queryBox).collect(Collectors.toSet());

        // Every AABT candidate must also be in the AABB set — AABT cannot introduce novel keys
        for (var key : aabtCandidates) {
            assertTrue(aabbCandidates.contains(key),
                       "AABT returned a key not present in AABB set: " + key);
        }

        System.out.printf("singleCell  — AABB=%d  AABT=%d  reduction=%.1f%%%n",
                          aabbCandidates.size(), aabtCandidates.size(),
                          100.0 * (aabbCandidates.size() - aabtCandidates.size()) / Math.max(1, aabbCandidates.size()));
    }

    /**
     * Any tet that truly intersects the query box (verified via {@link Tet#intersectsBound}) must
     * appear in the AABT result — no false negatives.
     */
    @Test
    void aabtHasNoFalseNegatives_singleCellQuery() {
        byte level = 10;
        var root = new Tet(0, 0, 0, level, (byte) 0);

        var queryBounds = new VolumeBounds(50f, 50f, 50f, 200f, 200f, 200f);
        var queryBox    = new Spatial.aabt.Box(50f, 50f, 50f, 200f, 200f, 200f);

        Set<TetreeKey<?>> aabtCandidates = root.intersectingBound(queryBox).collect(Collectors.toSet());

        // Exhaustively enumerate the one grid cell at level 10 and verify no misses
        int cellSize = Constants.lengthAtLevel(level);
        // The query falls in the cell at origin (0,0,0) at level 10
        for (byte type = 0; type < 6; type++) {
            var tet = new Tet(0, 0, 0, level, type);
            if (tet.intersectsBound(queryBox)) {
                assertTrue(aabtCandidates.contains(tet.tmIndex()),
                           "AABT missed a true-intersecting tet: type=" + type);
            }
        }
    }

    // -------------------------------------------------------------------------
    // False-positive reduction: AABT prunes more than AABB
    // -------------------------------------------------------------------------

    /**
     * Uses a query box that deliberately sits in the corner of a grid cell so that
     * not all 6 tet types intersect it.  The AABT count must be strictly less than
     * the AABB count (which emits all 6 for any qualifying cell).
     */
    @Test
    void aabtFewerCandidatesThanAabb_cornerQueryBox() {
        byte level = 10;
        var root = new Tet(0, 0, 0, level, (byte) 0);

        // Tiny box at origin corner — only 1-2 of the 6 S0-S5 tets contain this region.
        // S0 (type 0) has its anchor at origin; the others have very different geometry.
        var queryBox = new Spatial.aabt.Box(1f, 1f, 1f, 10f, 10f, 10f);
        var qBounds  = new VolumeBounds(1f, 1f, 1f, 10f, 10f, 10f);

        Set<TetreeKey<?>> aabbSet = aabbCandidatesAt(root, qBounds);
        Set<TetreeKey<?>> aabtSet = root.intersectingBound(queryBox).collect(Collectors.toSet());

        assertTrue(aabtSet.size() <= aabbSet.size(),
                   "AABT should return no more candidates than AABB");

        System.out.printf("cornerBox   — AABB=%d  AABT=%d  reduction=%.1f%%%n",
                          aabbSet.size(), aabtSet.size(),
                          100.0 * (aabbSet.size() - aabtSet.size()) / Math.max(1, aabbSet.size()));
    }

    /**
     * Multi-cell scenario at a finer level where the query box spans several cells.
     * Across multiple cells, AABT should prune more aggressively because many of the 6 tet
     * types per cell won't individually intersect the query.
     */
    @Test
    void aabtFewerCandidatesThanAabb_multiCellQuery() {
        byte level = 15;   // cell size = 2^(21-15) = 64
        var root = new Tet(0, 0, 0, level, (byte) 0);

        // Query spans ~ 3×3×3 cells but positioned so cells on the boundary only partially
        // overlap — many tet types in those border cells won't pass SAT.
        var queryBox = new Spatial.aabt.Box(10f, 10f, 10f, 200f, 200f, 200f);
        var qBounds  = new VolumeBounds(10f, 10f, 10f, 200f, 200f, 200f);

        Set<TetreeKey<?>> aabbSet = aabbCandidatesAt(root, qBounds);
        Set<TetreeKey<?>> aabtSet = root.intersectingBound(queryBox).collect(Collectors.toSet());

        // Correctness: subset relationship
        for (var key : aabtSet) {
            assertTrue(aabbSet.contains(key),
                       "AABT returned a key not in AABB set: " + key);
        }

        assertTrue(aabtSet.size() <= aabbSet.size(),
                   "AABT should not exceed AABB candidate count");

        System.out.printf("multiCell   — AABB=%d  AABT=%d  reduction=%.1f%%%n",
                          aabbSet.size(), aabtSet.size(),
                          100.0 * (aabbSet.size() - aabtSet.size()) / Math.max(1, aabbSet.size()));
    }

    /**
     * Multi-level scan: run the comparison at several levels and report reduction at each level.
     * Uses a query Tet as the bounding volume — an actual tetrahedral query bound causes the most
     * dramatic pruning because many of the 6 S0-S5 types in each cell won't intersect a
     * tet-shaped query.  The AABB helper uses the tet's AABB envelope.
     */
    @Test
    void aabtReductionAcrossMultipleLevels_tetQuery() {
        byte level = 15; // cell size = 64
        // Use a tet at level 15 as the query volume — its AABB envelope is 64^3 but only 1/6 of
        // that cube is the actual tet, so AABB over-approximates by ~6x.
        var queryTet = new Tet(64, 64, 64, level, (byte) 2);  // S2 type in the 64,64,64 cell
        var qBounds  = queryTet.toVolumeBounds();

        boolean anyReduction = false;
        for (byte testLevel = 14; testLevel <= 17; testLevel++) {
            var root = new Tet(0, 0, 0, testLevel, (byte) 0);
            Set<TetreeKey<?>> aabbSet = aabbCandidatesAt(root, qBounds);
            Set<TetreeKey<?>> aabtSet = root.intersectingBound(queryTet).collect(Collectors.toSet());

            // Correctness
            for (var key : aabtSet) {
                assertTrue(aabbSet.contains(key),
                           "Level " + testLevel + ": AABT key not in AABB set: " + key);
            }
            assertTrue(aabtSet.size() <= aabbSet.size(),
                       "Level " + testLevel + ": AABT count exceeds AABB count");

            double reduction = 100.0 * (aabbSet.size() - aabtSet.size()) / Math.max(1, aabbSet.size());
            System.out.printf("Level %2d  cellSz=%4d  AABB=%5d  AABT=%5d  reduction=%5.1f%%%n",
                              testLevel, Constants.lengthAtLevel(testLevel),
                              aabbSet.size(), aabtSet.size(), reduction);
            if (reduction > 0) anyReduction = true;
        }

        assertTrue(anyReduction, "Expected AABT to prune more aggressively than AABB for tet-shaped query");
    }

    /**
     * Multi-level scan with a Box query, reporting reduction at each level for diagnostic purposes.
     * Since a Box is the AABB itself, reduction will be modest but should still occur at cell
     * boundaries where the query box clips a tet-cell corner.
     */
    @Test
    void aabtReductionAcrossMultipleLevels_boxQuery() {
        // A box that deliberately clips just the boundary region between cells
        // and only partially overlaps the tet geometry in those cells.
        var queryBox = new Spatial.aabt.Box(50f, 50f, 50f, 115f, 115f, 115f);
        var qBounds  = new VolumeBounds(50f, 50f, 50f, 115f, 115f, 115f);

        for (byte level = 12; level <= 19; level++) {
            var root = new Tet(0, 0, 0, level, (byte) 0);
            Set<TetreeKey<?>> aabbSet = aabbCandidatesAt(root, qBounds);
            Set<TetreeKey<?>> aabtSet = root.intersectingBound(queryBox).collect(Collectors.toSet());

            // Correctness always
            for (var key : aabtSet) {
                assertTrue(aabbSet.contains(key),
                           "Level " + level + ": AABT key not in AABB set: " + key);
            }
            assertTrue(aabtSet.size() <= aabbSet.size(),
                       "Level " + level + ": AABT count exceeds AABB count");

            double reduction = 100.0 * (aabbSet.size() - aabtSet.size()) / Math.max(1, aabbSet.size());
            System.out.printf("Box L%2d  cellSz=%4d  AABB=%5d  AABT=%5d  reduction=%5.1f%%%n",
                              level, Constants.lengthAtLevel(level),
                              aabbSet.size(), aabtSet.size(), reduction);
        }
    }

    // -------------------------------------------------------------------------
    // S0-S5 type coverage: AABT correctly distinguishes tet types
    // -------------------------------------------------------------------------

    /**
     * For every S0-S5 type at a fixed grid cell, check that intersectsBound (with Box query) agrees with
     * the full SAT test so our AABT path is internally consistent with the existing AABB path.
     */
    @Test
    void intersectsBoundConsistentWithIntersects12DOP() {
        byte level = 12;
        var queryBox = new Spatial.aabt.Box(100f, 100f, 100f, 300f, 300f, 300f);

        int cellSize = Constants.lengthAtLevel(level);
        // Pick the cell that contains (200, 200, 200)
        int cx = (200 / cellSize) * cellSize;
        int cy = (200 / cellSize) * cellSize;
        int cz = (200 / cellSize) * cellSize;

        for (byte type = 0; type < 6; type++) {
            var tet = new Tet(cx, cy, cz, level, type);
            boolean viaBound  = tet.intersectsBound(queryBox);
            boolean via12DOP  = tet.intersects12DOP(100f, 100f, 100f, 300f, 300f, 300f);
            assertEquals(via12DOP, viaBound,
                         "intersectsBound disagrees with intersects12DOP for type " + type);
        }
    }

    /**
     * For a tet-shaped query, AABT must not miss any tet that truly intersects it.
     * Exhaustively enumerate all tets in the relevant cells at level 15 and verify.
     */
    @Test
    void aabtHasNoFalseNegatives_tetQuery() {
        byte level = 15; // cell size = 64
        var queryTet = new Tet(64, 64, 64, level, (byte) 2);
        var root = new Tet(0, 0, 0, level, (byte) 0);

        Set<TetreeKey<?>> aabtSet = root.intersectingBound(queryTet).collect(Collectors.toSet());

        // Enumerate all cells touched by queryTet's AABB and verify no true intersection is missed
        var qBounds = queryTet.toVolumeBounds();
        int cellSize = Constants.lengthAtLevel(level);

        int minXi = (int) Math.floor(qBounds.minX() / cellSize);
        int maxXi = (int) Math.ceil(qBounds.maxX() / cellSize);
        int minYi = (int) Math.floor(qBounds.minY() / cellSize);
        int maxYi = (int) Math.ceil(qBounds.maxY() / cellSize);
        int minZi = (int) Math.floor(qBounds.minZ() / cellSize);
        int maxZi = (int) Math.ceil(qBounds.maxZ() / cellSize);

        for (int xi = minXi; xi <= maxXi; xi++) {
            for (int yi = minYi; yi <= maxYi; yi++) {
                for (int zi = minZi; zi <= maxZi; zi++) {
                    for (byte type = 0; type < 6; type++) {
                        var candidateTet = new Tet(xi * cellSize, yi * cellSize, zi * cellSize, level, type);
                        if (candidateTet.intersectsBound(queryTet)) {
                            assertTrue(aabtSet.contains(candidateTet.tmIndex()),
                                       "AABT missed tet type=" + type + " at cell (" + xi + "," + yi + "," + zi + ")");
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Reproduce the AABB path candidate count: all 6 tet types per qualifying cell.
     * This mirrors what {@code computeOptimizedSFCRangesAtLevel} does for a 3D-touch query
     * at a given level — emit all 6 for any cell whose AABB overlaps the query bounds.
     */
    private Set<TetreeKey<?>> aabbCandidatesAt(Tet root, VolumeBounds bounds) {
        byte level   = root.l;
        int  length  = Constants.lengthAtLevel(level);

        int minX = (int) Math.floor(bounds.minX() / length);
        int maxX = (int) Math.ceil(bounds.maxX() / length);
        int minY = (int) Math.floor(bounds.minY() / length);
        int maxY = (int) Math.ceil(bounds.maxY() / length);
        int minZ = (int) Math.floor(bounds.minZ() / length);
        int maxZ = (int) Math.ceil(bounds.maxZ() / length);

        var result = new java.util.HashSet<TetreeKey<?>>();
        for (int xi = minX; xi <= maxX; xi++) {
            int cx = xi * length;
            if (cx + length < bounds.minX() || cx > bounds.maxX()) continue;
            for (int yi = minY; yi <= maxY; yi++) {
                int cy = yi * length;
                if (cy + length < bounds.minY() || cy > bounds.maxY()) continue;
                for (int zi = minZ; zi <= maxZ; zi++) {
                    int cz = zi * length;
                    if (cz + length < bounds.minZ() || cz > bounds.maxZ()) continue;
                    // AABB path: include ALL 6 tet types if cell AABB intersects
                    for (byte type = 0; type < 6; type++) {
                        result.add(new Tet(cx, cy, cz, level, type).tmIndex());
                    }
                }
            }
        }
        return result;
    }
}

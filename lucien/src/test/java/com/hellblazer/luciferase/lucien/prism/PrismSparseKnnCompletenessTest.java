/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.prism;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-h65: kNN completeness over a SPARSE Prism index. The pre-existing gap (found during
 * RDR-009 P6) was that Prism uses normalized [0,1) world coordinates, but the {@code AbstractSpatialIndex}
 * kNN machinery assumed integer coordinate space: {@code getCellSizeAtLevel} returned {@code int} and
 * truncated the fractional cell size to 0, so the expanding-radius fallback's search radius started at
 * 0 and never grew; and {@code Prism.findNodesIntersectingBounds} was a stub. Together they made kNN
 * (and bounded/region search) under-return whenever the nearest entities were several empty cells away
 * — regardless of the S0/S1 diagonal. This pins the fix: widened {@code getCellSizeAtLevel} to float +
 * a real {@code findNodesIntersectingBounds}.
 *
 * @author hal.hildebrand
 */
class PrismSparseKnnCompletenessTest {

    private Prism<LongEntityID, String> prism;

    @BeforeEach
    void setUp() {
        prism = new Prism<>(new SequentialLongIDGenerator(), 1.0f, 21);
    }

    @Test
    @DisplayName("same-half sparse kNN returns k (regression: the gap is orthogonal to the diagonal)")
    void sameHalfSparseKnnReturnsK() {
        // All four entities in S0 (y<x), scattered so their cells are several empty cells apart at
        // level 10. Before the fix this returned 1 (the BFS could not bridge the empty cells and the
        // expanding-radius fallback was stalled at radius 0).
        var near1 = prism.insert(new Point3f(0.52f, 0.49f, 0.50f), (byte) 10, "near1");
        var near2 = prism.insert(new Point3f(0.55f, 0.48f, 0.50f), (byte) 10, "near2");
        prism.insert(new Point3f(0.90f, 0.10f, 0.50f), (byte) 10, "far1");
        prism.insert(new Point3f(0.95f, 0.05f, 0.50f), (byte) 10, "far2");

        var nearest = prism.kNearestNeighbors(new Point3f(0.51f, 0.49f, 0.50f), 2, 1.0f);
        var nearestSet = new HashSet<>(nearest);
        assertEquals(2, nearest.size(), "k=2 neighbors expected over a sparse index");
        assertTrue(nearestSet.contains(near1) && nearestSet.contains(near2),
            "the two closest S0 entities must be returned, not the far ones");
    }

    @Test
    @DisplayName("sparse cross-diagonal kNN: an S0 query returns its true nearest neighbors across the diagonal in S1")
    void sparseCrossDiagonalKnnReturnsK() {
        // The acceptance proper: a sparse index where the S0 query's true nearest neighbors are two
        // entities just across the diagonal in S1, several empty cells from the query, and the only
        // S0 entities are far. Exercises the expanding-radius fallback scanning both families.
        var s1Near1 = prism.insert(new Point3f(0.49f, 0.52f, 0.50f), (byte) 10, "S1-near1"); // y>x, closest
        var s1Near2 = prism.insert(new Point3f(0.48f, 0.55f, 0.50f), (byte) 10, "S1-near2"); // y>x, close
        prism.insert(new Point3f(0.90f, 0.10f, 0.50f), (byte) 10, "S0-far1"); // y<x, far
        prism.insert(new Point3f(0.95f, 0.05f, 0.50f), (byte) 10, "S0-far2"); // y<x, far

        var nearest = prism.kNearestNeighbors(new Point3f(0.51f, 0.49f, 0.50f), 2, 1.0f); // query y<x -> S0
        var nearestSet = new HashSet<>(nearest);
        assertEquals(2, nearest.size(), "k=2 neighbors expected");
        assertTrue(nearestSet.contains(s1Near1) && nearestSet.contains(s1Near2),
            "the two nearest neighbors of the S0 query are the close S1 entities across the diagonal");
    }

    @Test
    @DisplayName("far-corner sparse kNN: a neighbor farther than the cube edge (up to the diagonal) is still returned")
    void farCornerSparseKnnReturnsK() {
        // Query near one corner; its two nearest are a mid entity and one near the OPPOSITE corner,
        // ~1.66 apart — farther than the unit-cube edge (1.0), within the diagonal (sqrt(3)=1.73)
        // and within maxDistance (2.0). The BFS finds only the mid one; the far-corner one is reached
        // only by the final safety sweep. The sweep's box (query +/- edge) already encloses the whole
        // domain, so the far-corner entity IS a candidate; what matters is that acceptance is governed
        // by maxDistance (2.0), NOT by the box/edge radius — so a neighbor beyond the edge but within
        // maxDistance is not wrongly rejected. (Without the sweep, Prism sparse kNN returns just the
        // mid entity.)
        var mid = prism.insert(new Point3f(0.50f, 0.30f, 0.50f), (byte) 10, "mid");          // 0.74 away
        var farCorner = prism.insert(new Point3f(0.98f, 0.97f, 0.98f), (byte) 10, "corner"); // 1.66 away

        var nearest = prism.kNearestNeighbors(new Point3f(0.02f, 0.01f, 0.02f), 2, 2.0f); // query near origin, S0
        var nearestSet = new HashSet<>(nearest);
        assertEquals(2, nearest.size(), "k=2 neighbors expected");
        assertTrue(nearestSet.contains(mid) && nearestSet.contains(farCorner),
            "both nearest must be returned; the sweep accepts up to maxDistance, so the far-corner "
            + "neighbor beyond the cube edge is not rejected");
    }

    @Test
    @DisplayName("getCellSizeAtLevel returns the true fractional world cell size (not truncated to 0)")
    void cellSizeIsFractional() {
        assertEquals(1.0f, prism.getCellSizeAtLevel((byte) 0), 0f, "level 0 cell size is the world size");
        assertEquals(0.5f, prism.getCellSizeAtLevel((byte) 1), 0f, "level 1 cell size is half");
        assertEquals(1.0f / 1024.0f, prism.getCellSizeAtLevel((byte) 10), 1e-9f, "level 10 cell size");
    }
}

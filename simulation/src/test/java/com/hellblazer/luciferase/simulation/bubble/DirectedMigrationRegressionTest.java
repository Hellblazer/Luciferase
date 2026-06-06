/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Directed-migration regression for RDR-015 (AC5) — <b>non-vacuous</b>.
 * <p>
 * Places a probe at a position strictly inside a KNOWN same-level face-neighbor bubble {@code B}
 * of a source bubble {@code S} and asserts the router resolves that position to <b>{@code B}
 * specifically</b> ({@code destinationBubbleKey == expectedNeighborKey}), NOT merely
 * {@code getTotalMigrations() > 0} — a catch-all router (the current level-0-first scan that
 * resolves almost everything to the all-containing L0 root) would satisfy the weak form while
 * routing to the wrong bubble.
 * <p>
 * <b>TDD status (RDR-015 P0): RED against current code, intentionally.</b> Two ways it fails today:
 * <ol>
 *   <li>{@link TetreeBubbleGrid#createBubbles} builds a mixed-level (non-partition) grid, so a
 *       same-level in-grid face-neighbor pair may not exist — the setup {@code assertNotNull}
 *       fails meaningfully (grid is not a connected same-level partition).</li>
 *   <li>Even when a neighbor pair exists, {@link TetrahedralContainmentChecker#locateDestinationBubble}
 *       scans levels 0..10 and returns the first match — the L0 root catch-all — instead of the
 *       partition-level neighbor {@code B}.</li>
 * </ol>
 * Goes green once P1 reworks {@code createBubbles} into a single-level partition (AC2) and P2
 * replaces the router's level-0-first scan with a direct lookup at the partition level
 * {@code L} (AC4).
 *
 * @author hal.hildebrand
 */
class DirectedMigrationRegressionTest {

    private static final int  BUBBLE_COUNT = 8;
    private static final byte MAX_LEVEL    = (byte) 3;
    private static final long TARGET_FRAME = 10L;

    @Test
    void escapedEntityRoutesToTheSpecificAdjacentNeighbor() {
        var grid = new TetreeBubbleGrid(MAX_LEVEL);
        grid.createBubbles(BUBBLE_COUNT, MAX_LEVEL, TARGET_FRAME);

        var keySet = new HashSet<>(grid.getBubblesWithKeys().keySet());

        // Find a source bubble S with a same-level, in-grid, involution-reciprocal face neighbor B.
        TetreeKey<?> expectedNeighborKey = null;
        Tet neighborTet = null;
        for (var key : keySet) {
            var s = key.toTet();
            var b = firstSameLevelNeighborInGrid(s, keySet);
            if (b != null) {
                neighborTet = b;
                expectedNeighborKey = b.tmIndex();
                break;
            }
        }

        assertNotNull(expectedNeighborKey,
                      "RDR-015 AC5 setup: the grid must expose a same-level in-grid face-neighbor pair "
                      + "(a connected partition). If null, createBubbles is not a single-level partition (P1).");

        // Probe: a point strictly inside neighbor B (its tetrahedral centroid). An entity that has
        // crossed the shared S->B face and now sits here MUST route to B specifically.
        var probe = centroid(neighborTet);

        var checker = new TetrahedralContainmentChecker(grid.getSpatialIndex(), grid);
        var destinationBubbleKey = checker.locateDestinationBubble(probe);

        assertEquals(expectedNeighborKey, destinationBubbleKey,
                     "router must route an escaped entity to the specific adjacent neighbor bubble B, "
                     + "not a level-0 catch-all (RDR-015 AC4/AC5)");
    }

    /**
     * First face neighbor of {@code s} that is (a) present in the partition and (b) involution-reciprocal
     * ({@code faceNeighbor(faceNeighbor(s,f).face()).tet() == s}). The Bey-SFC face neighbor is
     * non-conforming (shares 0–3 vertices), so reciprocity — not shared-vertex count — is the correct
     * adjacency test (CLAUDE.md "Face-neighbor testing caveat").
     */
    private static Tet firstSameLevelNeighborInGrid(Tet s, Set<TetreeKey<?>> partition) {
        for (int face = 0; face < 4; face++) {
            var fn = s.faceNeighbor(face);
            if (fn == null) {
                continue;
            }
            var back = fn.tet().faceNeighbor(fn.face());
            if (back == null || !s.equals(back.tet())) {
                continue;
            }
            if (partition.contains(fn.tet().tmIndex())) {
                return fn.tet();
            }
        }
        return null;
    }

    private static Point3f centroid(Tet tet) {
        Point3i[] c = tet.coordinates();
        float cx = (c[0].x + c[1].x + c[2].x + c[3].x) / 4.0f;
        float cy = (c[0].y + c[1].y + c[2].y + c[3].y) / 4.0f;
        float cz = (c[0].z + c[1].z + c[2].z + c[3].z) / 4.0f;
        return new Point3f(cx, cy, cz);
    }
}

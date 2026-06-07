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
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Well-formed-partition structural test for {@link TetreeBubbleGrid} (RDR-015 AC3).
 * <p>
 * Asserts that the bubble grid is a <b>single-level adjacent spatial partition</b>:
 * <ul>
 *   <li>every bubble key is at the same level {@code L};</li>
 *   <li>{@code L > 0} — no bubble is the L0 root catch-all;</li>
 *   <li>no duplicate keys (every requested bubble materializes at a distinct key);</li>
 *   <li>the same-level face-neighbor graph is connected (BFS from any key reaches all);</li>
 *   <li>no bubble's bounds nest/contain another's.</li>
 * </ul>
 * <p>
 * <b>Adjacency is validated by INVOLUTION reciprocity</b>
 * ({@code faceNeighbor(faceNeighbor(t,f).face()).tet() == t}), NEVER by a shared-vertex
 * count: the Bey-SFC face neighbor is non-conforming and shares 0–3 vertices (see the
 * project CLAUDE.md "Face-neighbor testing caveat").
 * <p>
 * <b>TDD status (RDR-015 P0): RED against current code.</b> The current
 * {@link TetreeBubbleGrid#createBubbles} distributes bubbles across MIXED levels
 * (including the L0 root), so the same-level / no-L0-root / connected-partition
 * assertions fail. This test goes green when P1 reworks {@code createBubbles} into a
 * single-level partition tiling the WorldBounds domain (AC2).
 *
 * @author hal.hildebrand
 */
class TetreeBubbleGridPartitionTest {

    private static final int        BUBBLE_COUNT = 8;
    private static final long       TARGET_FRAME = 10L;
    private static final WorldBounds WORLD       = new WorldBounds(0.0f, 100.0f);

    private static TetreeBubbleGrid partitionGrid() {
        var grid = new TetreeBubbleGrid((byte) 21);
        grid.createBubbles(BUBBLE_COUNT, WORLD, TARGET_FRAME);
        return grid;
    }

    @Test
    void everyBubbleIsAtTheSameLevelAboveRoot() {
        var grid = partitionGrid();
        var keys = new ArrayList<>(grid.getBubblesWithKeys().keySet());

        assertTrue(keys.size() > 1,
                   "a world-tiling partition must produce more than one bubble; got " + keys.size());

        byte expectedLevel = keys.get(0).toTet().l();
        assertTrue(expectedLevel > 0, "partition level L must be > 0 (no L0 root catch-all bubble)");

        for (var key : keys) {
            assertEquals(expectedLevel, key.toTet().l(),
                         "all bubbles must share a single partition level L; offending key: " + key);
        }
    }

    @Test
    void noBubbleIsTheRootTetreeKey() {
        var grid = partitionGrid();
        var rootKey = new Tet(0, 0, 0, (byte) 0, (byte) 0).tmIndex();

        for (var key : grid.getBubblesWithKeys().keySet()) {
            assertFalse(key.equals(rootKey), "no bubble may be the all-containing L0 root tet");
        }
    }

    @Test
    void noDuplicateKeys() {
        var grid = partitionGrid();
        var keys = new ArrayList<>(grid.getBubblesWithKeys().keySet());
        assertEquals(keys.size(), new HashSet<>(keys).size(), "bubble keys must be unique");
    }

    @Test
    void faceNeighborGraphIsConnected() {
        var grid = partitionGrid();
        var keySet = new HashSet<>(grid.getBubblesWithKeys().keySet());
        assertTrue(keySet.size() > 1, "partition must contain more than one bubble to be meaningfully connected");

        // BFS over same-level face adjacency, validated by involution reciprocity.
        var start = keySet.iterator().next();
        var visited = new HashSet<TetreeKey<?>>();
        var queue = new ArrayDeque<TetreeKey<?>>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var neighborKey : sameLevelFaceNeighbors(current, keySet)) {
                if (visited.add(neighborKey)) {
                    queue.add(neighborKey);
                }
            }
        }

        assertEquals(keySet.size(), visited.size(),
                     "the same-level face-neighbor graph must be connected (BFS must reach every bubble)");
    }

    @Test
    void noBubbleBoundsNestAnother() {
        // Same-level tets have equal-sized RDG AABBs, so one can never strictly contain another; this guards
        // against any mixed-level regression (a coarser cell whose AABB swallows a finer one — the legacy bug).
        var grid = partitionGrid();
        var bounds = new ArrayList<BubbleBounds>();
        for (var key : grid.getBubblesWithKeys().keySet()) {
            bounds.add(BubbleBounds.fromTetreeKey(key));
        }

        for (int i = 0; i < bounds.size(); i++) {
            for (int j = 0; j < bounds.size(); j++) {
                if (i == j) {
                    continue;
                }
                assertFalse(strictlyContains(bounds.get(i), bounds.get(j)),
                            "no bubble's bounds may nest/contain another's (mixed-level nesting)");
            }
        }
    }

    @Test
    void partitionCoversTheWorldDomainIncludingBoundary() {
        var grid = partitionGrid();
        byte level = grid.getPartitionLevel();
        var spatial = grid.getSpatialIndex();

        // Sample a dense grid of in-bounds points (interior, faces, edges, corners). Every entity position
        // (clamped to the world domain by physics) must fall in a partition cell, else migration routes to
        // null and the entity is silently stuck. Boundary points are the at-risk case (RDR-015 review).
        float lo = WORLD.min();
        float hi = WORLD.max();
        int steps = 6;
        for (int i = 0; i <= steps; i++) {
            for (int j = 0; j <= steps; j++) {
                for (int k = 0; k <= steps; k++) {
                    float x = lo + (hi - lo) * i / steps;
                    float y = lo + (hi - lo) * j / steps;
                    float z = lo + (hi - lo) * k / steps;
                    var tet = spatial.locateTetrahedron(new javax.vecmath.Point3f(x, y, z), level);
                    assertTrue(tet != null && grid.containsBubble(tet.tmIndex()),
                               "world point (" + x + "," + y + "," + z + ") must fall in a partition bubble");
                }
            }
        }
    }

    /**
     * Face neighbors of {@code key} that (a) are present in the partition and (b) pass the involution
     * reciprocity check — {@code faceNeighbor(faceNeighbor(t,f).face()).tet() == t}. The returned
     * {@link Tet.FaceNeighbor#face()} is the dual face on the neighbor that points back to {@code t}.
     */
    private static List<TetreeKey<?>> sameLevelFaceNeighbors(TetreeKey<?> key, Set<TetreeKey<?>> partition) {
        var t = key.toTet();
        var result = new ArrayList<TetreeKey<?>>();
        for (int face = 0; face < 4; face++) {
            var fn = t.faceNeighbor(face);
            if (fn == null) {
                continue;
            }
            var back = fn.tet().faceNeighbor(fn.face());
            if (back == null || !t.equals(back.tet())) {
                continue; // adjacency not reciprocal — not a true shared-face neighbor
            }
            var neighborKey = fn.tet().tmIndex();
            if (partition.contains(neighborKey)) {
                result.add(neighborKey);
            }
        }
        return result;
    }

    /** True iff {@code a}'s RDG AABB strictly contains {@code b}'s (b nested inside a). */
    private static boolean strictlyContains(BubbleBounds a, BubbleBounds b) {
        var aMin = a.rdgMin();
        var aMax = a.rdgMax();
        var bMin = b.rdgMin();
        var bMax = b.rdgMax();
        boolean encloses = aMin.x <= bMin.x && aMin.y <= bMin.y && aMin.z <= bMin.z
                           && aMax.x >= bMax.x && aMax.y >= bMax.y && aMax.z >= bMax.z;
        boolean strictlyLarger = aMin.x < bMin.x || aMin.y < bMin.y || aMin.z < bMin.z
                                 || aMax.x > bMax.x || aMax.y > bMax.y || aMax.z > bMax.z;
        return encloses && strictlyLarger;
    }
}

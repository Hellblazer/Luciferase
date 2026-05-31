/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.neighbor;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostAlgorithm;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostBoundaryDetector;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the live consumer ({@link GhostBoundaryDetector}) for {@code GhostType.EDGES} and
 * {@code GhostType.VERTICES} through {@link TetreeNeighborDetector} (Luciferase-v9vm follow-up to a
 * substantive-critic gap: before this bead the cross-parent edge/vertex walk was a stub, so this consumer path
 * never produced cross-parent ghost requests and was never exercised end-to-end).
 *
 * <p>The base {@code createGhostElement} is a placeholder (real ghost data arrives via gRPC), so we record its
 * invocations with a subclass — the same pattern as {@code PyramidCrossShapeGhostTest}. Asserts the
 * now-populated, strictly larger edge/vertex neighbor sets flow through ghost-layer creation: the run completes
 * in bounded time (no runaway descent — guarded by {@code @Timeout}), fires ghost requests for remote-owned
 * absent neighbors, and never requests a ghost for a key that is present locally (no self/local ghost).
 */
class TetreeGhostEdgeVertexIntegrationTest {

    /** Records the (key, rank) of every ghost request the detector fires (base impl is a gRPC placeholder). */
    private static final class RecordingGhostBoundaryDetector<Key extends SpatialKey<Key>>
        extends GhostBoundaryDetector<Key, LongEntityID, String> {

        final Map<Key, Integer> requested = new LinkedHashMap<>();

        RecordingGhostBoundaryDetector(SpatialIndex<Key, LongEntityID, String> index,
                                       NeighborDetector<Key> detector, GhostType type, GhostAlgorithm algo) {
            super(index, detector, type, algo);
        }

        @Override
        protected void createGhostElement(Key neighborKey, int ownerRank) {
            requested.put(neighborKey, ownerRank);
        }
    }

    private Tetree<LongEntityID, String> populatedTreeNearOriginCorner() {
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        // Cluster near the NEGATIVE domain corner so boundary elements are guaranteed and their edge/vertex
        // neighbors reach unoccupied (absent) in-domain cells we can mark remote.
        for (int i = 1; i <= 12; i++) {
            tetree.insert(new Point3f(i * 8, i * 8, i * 8), (byte) 10, "E" + i);
        }
        return tetree;
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    void edgeGhostRequestsAreBoundedFiredAndSelfFree() {
        runGhostScenario(GhostType.EDGES);
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    void vertexGhostRequestsAreBoundedFiredAndSelfFree() {
        runGhostScenario(GhostType.VERTICES);
    }

    private void runGhostScenario(GhostType ghostType) {
        var tetree = populatedTreeNearOriginCorner();
        var detector = new TetreeNeighborDetector(tetree);
        var ghostDetector = new RecordingGhostBoundaryDetector<TetreeKey<? extends TetreeKey<?>>>(
            tetree, detector, ghostType, GhostAlgorithm.CONSERVATIVE);

        // Mark every absent neighbor (edge or vertex, per ghostType) of every local key as remotely owned, so
        // ghost creation has remote targets to fire on.
        int marked = 0;
        for (var key : tetree.getSortedSpatialIndices()) {
            var neighbors = ghostType == GhostType.EDGES ? detector.findEdgeNeighbors(key)
                                                         : detector.findVertexNeighbors(key);
            for (var n : neighbors) {
                if (!tetree.containsSpatialKey(n)) {
                    ghostDetector.setElementOwner(n, 1); // rank 1 = remote
                    marked++;
                }
            }
        }
        assertTrue(marked > 0, "scenario must mark some absent remote " + ghostType + " neighbors to be meaningful");

        ghostDetector.createGhostLayer(); // bounded by @Timeout; must not run away

        assertFalse(ghostDetector.requested.isEmpty(),
                    "remote-owned absent " + ghostType + " neighbors must fire ghost requests");
        assertFalse(ghostDetector.getBoundaryElements().isEmpty(), "expected boundary elements near the corner");

        // No ghost request may target a locally-present key (requests are only ever for absent/remote keys).
        for (var requestedKey : ghostDetector.requested.keySet()) {
            assertFalse(tetree.containsSpatialKey(requestedKey),
                        "ghost request must not target a locally-present key: " + requestedKey);
        }
    }
}

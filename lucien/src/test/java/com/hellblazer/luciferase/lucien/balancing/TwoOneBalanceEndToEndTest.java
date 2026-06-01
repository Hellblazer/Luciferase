/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end 2:1-balance integration test (Luciferase-m27q, B10c).
 *
 * <p>Acceptance criterion: a boundary with a level-diff &gt; 1 violation where the LOCAL element is the coarser side
 * is driven to true 2:1 balance by {@link CrossPartitionBalancePhase#execute} — the phase consumes the local
 * refinement queue and subdivides the coarse node via the on-demand {@link
 * com.hellblazer.luciferase.lucien.SpatialIndex#subdivide} API until the boundary is balanced (level diff ≤ 1).
 *
 * <p>This drives the REAL {@code execute()} Allreduce-LAND loop, REAL {@code createRefinementRequests}/local-queue
 * drain/Phase-5 subdivide on a REAL Octree forest, and verifies the ghost re-sync hook fires. Only violation
 * <em>detection</em> is supplied via a checker seam: constructing a Morton-detectable local-coarser violation
 * geometrically (SFC-code alignment between a coarse local node and a fine ghost neighbour) is intricate and
 * orthogonal to the local-refinement path under test, so the seam returns the canonical violation until the coarse
 * node is genuinely subdivided away — making convergence a real consequence of the subdivision, not a stub.
 *
 * @author hal.hildebrand
 */
class TwoOneBalanceEndToEndTest {

    private static final RefinementExchange<MortonKey, LongEntityID, String> EMPTY_EXCHANGE =
        (targetRank, treeId, roundNumber, treeLevel, boundaryKeys) ->
            CompletableFuture.completedFuture(RefinementResponse.empty());

    private static final class SinglePartitionRegistry implements ParallelBalancer.PartitionRegistry {
        @Override public int getCurrentPartitionId() { return 0; }
        @Override public int getPartitionCount() { return 2; }
        @Override public void barrier(int round) { }
        @Override public void requestRefinement(Object elementKey) { }
        @Override public int getPendingRefinements() { return 0; }
    }

    /**
     * Checker seam: reports a single local-coarser violation against {@code coarseKey} (vs a fine ghost two levels
     * deeper) for as long as {@code stillViolated} holds. The test wires {@code stillViolated} to "the coarse node has
     * not yet produced finer children", so once Phase 5 actually subdivides it (children appear) the seam returns
     * empty — true 2:1 balance, reached as a real consequence of the subdivision.
     */
    private static final class SeamChecker extends TwoOneBalanceChecker<MortonKey, LongEntityID, String> {
        private final MortonKey coarseKey;
        private final MortonKey ghostKey;
        private final java.util.function.BooleanSupplier stillViolated;

        SeamChecker(MortonKey coarseKey, MortonKey ghostKey, java.util.function.BooleanSupplier stillViolated) {
            this.coarseKey = coarseKey;
            this.ghostKey = ghostKey;
            this.stillViolated = stillViolated;
        }

        @Override
        public List<BalanceViolation<MortonKey>> findViolations(GhostLayer<MortonKey, LongEntityID, String> ghostLayer,
                                                                Forest<MortonKey, LongEntityID, String> forest) {
            if (!stillViolated.getAsBoolean()) {
                return List.of(); // coarse node has been refined into children -> boundary balanced
            }
            int local = coarseKey.getLevel();
            int ghost = ghostKey.getLevel();
            return List.of(new BalanceViolation<>(coarseKey, ghostKey, local, ghost, Math.abs(local - ghost), 1));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void localCoarserBoundary_reachesTrueTwoOneBalance() {
        var forest = new Forest<MortonKey, LongEntityID, String>(ForestConfig.defaultConfig());
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        forest.addTree(octree);

        byte localLevel = 10; // coarse local node
        int cell = Constants.lengthAtLevel(localLevel);                 // 2048
        int childSplit = Constants.lengthAtLevel((byte) (localLevel + 1)); // 1024
        int base = cell; // coarse cell index (1,1,1)

        // Two entities in one level-10 cell spanning two level-11 child octants, so the coarse node can subdivide.
        octree.insert(new Point3f(base + 100, base + 100, base + 100), localLevel, "localA");
        octree.insert(new Point3f(base + childSplit + 100, base + 100, base + 100), localLevel, "localB");
        assertEquals(1, octree.getNodeCount(), "one coarse local node before balancing");

        var coarseKey = octree.getSpatialKeys().iterator().next();
        assertEquals(localLevel, coarseKey.getLevel(), "the local node is at the coarse level");

        // Fine ghost two levels deeper -> level diff 2 (a 2:1 violation; local is the coarser side).
        var ghostKey = new MortonKey(coarseKey.getMortonCode(), (byte) (localLevel + 2));
        var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);

        // The boundary is balanced once the coarse node has produced finer children (Octree retains the parent as an
        // internal node and adds child leaves, so node count grows past the initial 1).
        java.util.function.BooleanSupplier stillViolated = () -> octree.getNodeCount() == 1;

        var config = BalanceConfiguration.defaultConfig();
        var phase = new CrossPartitionBalancePhase<>(EMPTY_EXCHANGE, new SinglePartitionRegistry(), config);
        phase.setForestContext(forest, ghostLayer);
        phase.setBalanceChecker(new SeamChecker(coarseKey, ghostKey, stillViolated));
        var ghostResyncs = new AtomicInteger(0);
        phase.setGhostResync(ghostResyncs::incrementAndGet);

        var result = phase.execute(forest, 0, 2);

        assertTrue(result.successful(), "balance must complete successfully");
        assertTrue(octree.getNodeCount() > 1, "the coarse local node must have been subdivided into finer children");
        assertTrue(ghostResyncs.get() >= 1, "ghost re-sync must run after the local adapt (S9 precondition)");

        // True 2:1 balance: a fresh scan finds no remaining violation now that the boundary element is refined.
        assertFalse(stillViolated.getAsBoolean(), "boundary reaches true 2:1 balance after refinement");
        assertTrue(result.finalMetrics().roundCount() <= config.maxRounds(),
                   "converged within the maxRounds safety cap");
    }

    /** Ghost-coarser one-shot seam: emits a single !localNeedsRefinement() violation, then reports balanced. */
    private static final class GhostCoarserSeam extends TwoOneBalanceChecker<MortonKey, LongEntityID, String> {
        private final MortonKey localKey;
        private final MortonKey ghostKey;
        private final int ownerRank;
        private boolean emitted = false;

        GhostCoarserSeam(MortonKey localKey, MortonKey ghostKey, int ownerRank) {
            this.localKey = localKey;
            this.ghostKey = ghostKey;
            this.ownerRank = ownerRank;
        }

        @Override
        public List<BalanceViolation<MortonKey>> findViolations(GhostLayer<MortonKey, LongEntityID, String> ghostLayer,
                                                                Forest<MortonKey, LongEntityID, String> forest) {
            if (emitted) {
                return List.of();
            }
            emitted = true;
            int local = localKey.getLevel();   // fine
            int ghost = ghostKey.getLevel();   // coarse: local > ghost -> !localNeedsRefinement -> remote request
            return List.of(new BalanceViolation<>(localKey, ghostKey, local, ghost, Math.abs(local - ghost), ownerRank));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void ghostCoarserBoundary_propagatesRefinementRequestToOwner() {
        var forest = new Forest<MortonKey, LongEntityID, String>(ForestConfig.defaultConfig());
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        forest.addTree(octree);

        byte localLevel = 6; // fine local element
        int cell = Constants.lengthAtLevel(localLevel);
        octree.insert(new Point3f(cell + 50, cell + 50, cell + 50), localLevel, "localFine");
        var localKey = octree.getSpatialKeys().iterator().next();
        var ghostKey = new MortonKey(localKey.getMortonCode(), (byte) (localLevel - 2)); // coarser ghost, diff 2
        int ownerRank = 1;

        // Recording exchange: captures the target rank and boundary keys that reach the wire.
        var recordedTargets = new java.util.ArrayList<Integer>();
        var recordedKeyCounts = new java.util.ArrayList<Integer>();
        RefinementExchange<MortonKey, LongEntityID, String> recording =
            (targetRank, treeId, roundNumber, treeLevel, boundaryKeys) -> {
                recordedTargets.add(targetRank);
                recordedKeyCounts.add(boundaryKeys.size());
                return CompletableFuture.completedFuture(RefinementResponse.empty());
            };

        var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        var config = BalanceConfiguration.defaultConfig();
        var phase = new CrossPartitionBalancePhase<>(recording, new SinglePartitionRegistry(), config);
        phase.setForestContext(forest, ghostLayer);
        phase.setBalanceChecker(new GhostCoarserSeam(localKey, ghostKey, ownerRank));

        var result = phase.execute(forest, 0, 2);

        assertTrue(result.successful(), "balance completes");
        assertTrue(recordedTargets.contains(ownerRank),
                   "a refinement request must be propagated to the ghost owner's rank");
        assertTrue(recordedKeyCounts.stream().anyMatch(n -> n > 0),
                   "the propagated request must carry real boundary keys (the ghost-coarser violation's keys)");
    }
}

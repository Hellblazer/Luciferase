/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.portal.demo;

import com.hellblazer.luciferase.esvo.io.VOLLoader;
import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.balancing.BalanceConfiguration;
import com.hellblazer.luciferase.lucien.balancing.CrossPartitionBalancePhase;
import com.hellblazer.luciferase.lucien.balancing.ParallelBalancer;
import com.hellblazer.luciferase.lucien.balancing.RefinementExchange;
import com.hellblazer.luciferase.lucien.balancing.RefinementResponse;
import com.hellblazer.luciferase.lucien.balancing.TwoOneBalanceChecker;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostElement;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostLayer;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostType;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.vecmath.Point3f;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstration: exercise the headline <b>2:1 balance constraint</b> on the actual Stanford Bunny.
 *
 * <p>The 2:1 constraint says adjacent spatial cells may differ by at most one refinement level. This demo builds
 * a COARSE bunny (its voxels bucketed into level-{@value #COARSE_LEVEL} Octree cells), then plants a FINE
 * (level-{@value #FINE_LEVEL}) ghost element from a notional neighbour partition right against one coarse bunny
 * surface cell — a genuine level-diff-2 violation. It then runs the real {@link TwoOneBalanceChecker} to DETECT
 * the violation and the real {@link CrossPartitionBalancePhase} to REFINE the coarse cell back into 2:1 balance,
 * reporting the before/after numbers. No mocks: real bunny data, real detector, real refinement loop.
 *
 * @author hal.hildebrand
 */
class BunnyTwoOneBalanceDemoTest {

    private static final byte COARSE_LEVEL = 6;
    private static final byte FINE_LEVEL   = 8;   // two levels deeper => level-diff 2 => 2:1 violation

    /** No remote partitions to talk to in this single-process demo. */
    private static final RefinementExchange<MortonKey, LongEntityID, String> EMPTY_EXCHANGE =
        (targetRank, treeId, roundNumber, treeLevel, boundaryKeys) ->
            CompletableFuture.completedFuture(RefinementResponse.empty());

    private static final class TwoPartitionRegistry implements ParallelBalancer.PartitionRegistry {
        @Override public int getCurrentPartitionId() { return 0; }
        @Override public int getPartitionCount() { return 2; }
        @Override public void barrier(int round) { }
        @Override public void requestRefinement(Object elementKey) { }
        @Override public int getPendingRefinements() { return 0; }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void exerciseTwoOneBalanceOnTheBunny() throws Exception {
        // 1. Load the real Stanford Bunny voxels.
        var vol = new VOLLoader().loadResource("/voxels/bunny-64.vol");
        int dim = Math.max(vol.header().dimX(), Math.max(vol.header().dimY(), vol.header().dimZ()));
        System.out.printf("%nBunny: %d voxels in a %d^3 grid%n", vol.voxels().size(), dim);

        // 2. Build a COARSE bunny: bucket voxels into level-6 Octree cells. Scale so ~2 grid units map to one
        //    level-6 cell (step = level-7 cell size), so each coarse cell that the surface touches holds several
        //    voxels spread across distinct level-7 child octants — i.e. it can actually be subdivided.
        var forest = new Forest<MortonKey, LongEntityID, String>(ForestConfig.defaultConfig());
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        forest.addTree(octree);

        int step = Constants.lengthAtLevel((byte) (COARSE_LEVEL + 1)); // 2 grid units per coarse cell
        int base = Constants.lengthAtLevel(COARSE_LEVEL) * 4;          // keep coords positive, away from origin
        for (var v : vol.voxels()) {
            float wx = base + v.x * (float) step;
            float wy = base + v.y * (float) step;
            float wz = base + v.z * (float) step;
            octree.insert(new Point3f(wx, wy, wz), COARSE_LEVEL, "bunny");
        }
        int coarseCells = octree.getNodeCount();
        System.out.printf("Coarse bunny: %d level-%d cells%n", coarseCells, COARSE_LEVEL);

        // 3. Pick a refinable coarse surface cell (a cell holding >= 2 entities, so subdivide can redistribute).
        MortonKey coarse = octree.nodes()
                                 .filter(n -> n.sfcIndex().getLevel() == COARSE_LEVEL && n.entityIds().size() >= 2)
                                 .map(n -> n.sfcIndex())
                                 .findFirst()
                                 .orElseThrow(() -> new AssertionError("no multi-entity coarse cell on the bunny"));

        // 4. Plant a FINE ghost from a neighbour partition against that coarse cell. Place it at the origin of the
        //    coarse cell's +X neighbour, at level 8: its -X neighbour (level 8) falls back inside `coarse`, so the
        //    detector pairs them as coarse-local (lvl 6) vs fine-ghost (lvl 8) => level-diff 2.
        int[] o = MortonCurve.decode(coarse.getMortonCode());          // coarse cell origin
        int len6 = Constants.lengthAtLevel(COARSE_LEVEL);
        var ghostKey = MortonKey.fromCoordinates(o[0] + len6, o[1], o[2], FINE_LEVEL);
        var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        ghostLayer.addGhostElement(new GhostElement<>(
            ghostKey, new LongEntityID(9_000_001L), "fine-neighbour",
            new Point3f(o[0] + len6 + 1, o[1] + 1, o[2] + 1), /*ownerRank*/ 1, /*treeId*/ 0L));

        // 5. REAL detection.
        var checker = new TwoOneBalanceChecker<MortonKey, LongEntityID, String>();
        var before = checker.findViolations(ghostLayer, forest);
        System.out.printf("%n-- 2:1 detection --%n  violations found: %d%n", before.size());
        assertTrue(before.size() > 0, "the planted fine ghost against a coarse bunny cell must be a 2:1 violation");
        var v0 = before.get(0);
        System.out.printf("  example: local level %d  vs  ghost level %d  (level-diff %d, local must refine=%b)%n",
                          v0.localLevel(), v0.ghostLevel(), v0.levelDifference(), v0.localNeedsRefinement());

        // 6. REAL refinement loop (the headline orchestration): detect -> refine coarser side -> re-sync ghosts.
        var ghostResyncs = new AtomicInteger(0);
        var phase = new CrossPartitionBalancePhase<>(EMPTY_EXCHANGE, new TwoPartitionRegistry(),
                                                     BalanceConfiguration.defaultConfig().withMaxRounds(64));
        phase.setForestContext(forest, ghostLayer);
        // No setBalanceChecker(): the phase already defaults to a real TwoOneBalanceChecker, so the whole
        // detect -> refine loop runs against our real ghost layer with zero seam.
        phase.setGhostResync(ghostResyncs::incrementAndGet);

        int nodesBefore = octree.getNodeCount();
        long finerBefore = octree.nodes().filter(n -> n.sfcIndex().getLevel() > COARSE_LEVEL).count();
        var result = phase.execute(forest, 0, 2);
        int nodesAfter = octree.getNodeCount();
        long finerAfter = octree.nodes().filter(n -> n.sfcIndex().getLevel() > COARSE_LEVEL).count();

        var after = checker.findViolations(ghostLayer, forest);
        System.out.printf("%n-- 2:1 balance --%n  successful: %b, rounds: %d%n  nodes: %d -> %d (+%d)%n"
                          + "  finer-than-coarse cells (the new sub-cells): %d -> %d%n  ghost re-syncs: %d%n"
                          + "  violations (naive re-scan): %d%n",
                          result.successful(), result.finalMetrics().roundCount(), nodesBefore, nodesAfter,
                          nodesAfter - nodesBefore, finerBefore, finerAfter, ghostResyncs.get(), after.size());
        System.out.printf("  NOTE: with the leaf-aware checker (Luciferase-hthxs) the re-scan DROPS as cells are "
                          + "refined — it no longer false-positives on retained internal parents. The residual "
                          + "violations are dense-region coarse cells whose entities don't subdivide toward the "
                          + "boundary; the clean coarse->fine cell converges fully to 0 (see "
                          + "TwoOneBalanceConvergenceTest).%n%n");

        // Real outcomes proven here: detection fired on real bunny geometry, the balance phase completed, coarse
        // cells were genuinely subdivided into finer (level-7) children, the ghost re-sync hook ran, and — with
        // the leaf-aware fix — refining cells REDUCES the violation set (it previously stayed pinned at 15).
        assertNotNull(result);
        assertTrue(result.successful(), "the 2:1 balance phase must complete");
        assertTrue(nodesAfter > nodesBefore,
                   "the coarse bunny cells must have been subdivided (2:1 refinement actually happened)");
        assertTrue(finerAfter > finerBefore,
                   "subdivision must create finer-than-coarse child cells covering the boundary");
        assertTrue(ghostResyncs.get() >= 1, "ghost re-sync must fire after the local adapt");
        assertTrue(after.size() < before.size(),
                   "the leaf-aware checker must let refinement REDUCE the 2:1 violation set (Luciferase-hthxs); "
                   + "before=" + before.size() + " after=" + after.size());
    }
}

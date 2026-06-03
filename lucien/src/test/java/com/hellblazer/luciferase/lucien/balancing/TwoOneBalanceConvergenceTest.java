/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.balancing;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-hthxs: 2:1 balance must converge to ZERO violations on a single Octree, verified by a REAL re-scan
 * of {@link TwoOneBalanceChecker#findViolations} (no convergence seam).
 *
 * <p>Before the fix, {@code Octree.subdivide} retained the coarse cell as an internal parent and the checker's
 * coarse-band probe matched that retained parent key via {@code containsSpatialKey} — so the boundary kept
 * reporting a violation even after its finer children covered it, and the balance loop burned maxRounds without
 * converging. The fix makes the coarse-band probe leaf-aware (a subdivided parent with a stored child at the next
 * level is not a 2:1 element). This test plants a genuine coarse-local vs fine-ghost violation against a
 * definitely-refinable coarse cell, runs the real {@link CrossPartitionBalancePhase}, and asserts a fresh real
 * re-scan finds no remaining violation.
 *
 * @author hal.hildebrand
 */
class TwoOneBalanceConvergenceTest {

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
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void coarseLocalBoundaryConvergesToZeroViolationsViaRealRescan() {
        var forest = new Forest<MortonKey, LongEntityID, String>(ForestConfig.defaultConfig());
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        forest.addTree(octree);

        byte coarseLevel = 10;
        int len = Constants.lengthAtLevel(coarseLevel);                  // coarse cell size
        int childSplit = Constants.lengthAtLevel((byte) (coarseLevel + 1));
        int base = len * 3;                                              // a coarse cell well away from the origin

        // One coarse (level-10) cell holding two entities in distinct level-11 octants -> genuinely subdividable.
        octree.insert(new Point3f(base + 100, base + 100, base + 100), coarseLevel, "a");
        octree.insert(new Point3f(base + childSplit + 100, base + 100, base + 100), coarseLevel, "b");
        var coarseKey = octree.getSpatialKeys().iterator().next();
        assertEquals(coarseLevel, coarseKey.getLevel());

        // Fine ghost (level 12) at the +X neighbour origin of the coarse cell -> its -X neighbour falls back inside
        // the coarse cell, so the real detector pairs them: coarse-local (10) vs fine-ghost (12), level-diff 2.
        int[] o = MortonCurve.decode(coarseKey.getMortonCode());
        var ghostKey = MortonKey.fromCoordinates(o[0] + len, o[1], o[2], (byte) (coarseLevel + 2));
        var ghostLayer = new GhostLayer<MortonKey, LongEntityID, String>(GhostType.FACES);
        ghostLayer.addGhostElement(new GhostElement<>(ghostKey, new LongEntityID(7_000_001L), "fineGhost",
                                                      new Point3f(o[0] + len + 1, o[1] + 1, o[2] + 1), 1, 0L));

        var checker = new TwoOneBalanceChecker<MortonKey, LongEntityID, String>();
        var before = checker.findViolations(ghostLayer, forest);
        assertTrue(before.size() > 0, "precondition: a real 2:1 violation must be detected before balancing");

        var ghostResyncs = new AtomicInteger(0);
        var phase = new CrossPartitionBalancePhase<>(EMPTY_EXCHANGE, new TwoPartitionRegistry(),
                                                     BalanceConfiguration.defaultConfig());
        phase.setForestContext(forest, ghostLayer);   // default real TwoOneBalanceChecker drives the loop
        phase.setGhostResync(ghostResyncs::incrementAndGet);

        int nodesBefore = octree.getNodeCount();
        var result = phase.execute(forest, 0, 2);

        assertTrue(result.successful(), "balance phase completes");
        assertTrue(octree.getNodeCount() > nodesBefore, "the coarse cell must be subdivided into finer children");
        assertTrue(ghostResyncs.get() >= 1, "ghost re-sync fires after the local adapt");

        // The point of hthxs: a REAL re-scan now returns zero — the retained internal parent is no longer a
        // false-positive violation, and the level-11 children vs the level-12 ghost differ by only one level.
        var after = checker.findViolations(ghostLayer, forest);
        assertEquals(0, after.size(),
                     "2:1 balance must converge to zero violations on a real re-scan (Luciferase-hthxs); got "
                     + after.size());
        assertTrue(result.finalMetrics().roundCount() < BalanceConfiguration.DEFAULT_MAX_ROUNDS,
                   "convergence must happen before the maxRounds safety cap (not spin to the cap)");
    }

    /**
     * The leaf guard in BOTH balance-checker paths (Morton coarse-band and the Tetree/Pyramid detector path) keys
     * off {@link com.hellblazer.luciferase.lucien.SpatialIndex#hasChildren}. This pins the discriminator it relies
     * on: for Octree AND Tetree, a freshly inserted cell is a leaf (hasChildren==false), and after subdivision the
     * retained parent reports hasChildren==true while its children are leaves. Without this, the detector-path fix
     * for Tetree/Pyramid would silently no-op (Luciferase-hthxs).
     */
    @Test
    void hasChildrenDistinguishesLeafFromSubdividedParent() {
        // Octree
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        byte level = 10;
        int len = Constants.lengthAtLevel(level);
        int split = Constants.lengthAtLevel((byte) (level + 1));
        int b = len * 5;
        octree.insert(new Point3f(b + 50, b + 50, b + 50), level, "a");
        octree.insert(new Point3f(b + split + 50, b + 50, b + 50), level, "b");
        var oKey = octree.getSpatialKeys().iterator().next();
        assertEquals(false, octree.hasChildren(oKey), "a fresh Octree cell is a leaf");
        assertTrue(octree.subdivide(oKey), "the multi-octant cell must subdivide");
        assertTrue(octree.hasChildren(oKey), "the retained Octree parent must report hasChildren after subdivide");

        // Tetree (the detector-path index type)
        var tetree = new com.hellblazer.luciferase.lucien.tetree.Tetree<LongEntityID, String>(
            new SequentialLongIDGenerator());
        int tb = len * 5;
        tetree.insert(new Point3f(tb + 50, tb + 50, tb + 50), level, "a");
        tetree.insert(new Point3f(tb + split + 50, tb + 50, tb + 50), level, "b");
        var tKey = tetree.getSpatialKeys().iterator().next();
        assertEquals(false, tetree.hasChildren(tKey), "a fresh Tetree cell is a leaf");
        if (tetree.subdivide(tKey)) {
            assertTrue(tetree.hasChildren(tKey),
                       "the retained Tetree parent must report hasChildren after subdivide (detector-path leaf guard)");
        }
    }
}

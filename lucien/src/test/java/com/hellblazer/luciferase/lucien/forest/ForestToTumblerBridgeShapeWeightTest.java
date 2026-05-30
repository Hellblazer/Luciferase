/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.pyramid.PyramidIndex;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 §4c (bead Luciferase-d3z3): shape-weighted server assignment in
 * {@link ForestToTumblerBridge} — the live consumer of {@link
 * com.hellblazer.luciferase.lucien.balancing.ShapeWeightProvider#elementCount(int)}.
 *
 * <p>The default (no weigher) path stays exact round-robin (covered by {@code ForestTumblerBridgeTest}).
 * The shape-weighted path assigns each root tree to the least-loaded server by accumulated shape weight,
 * so a forest of pyramid-heavy trees (N_pyramid = 2·8^ℓ − 6^ℓ &gt; N_hex = 8^ℓ) balances server <em>load</em>
 * rather than tree <em>count</em>.
 */
class ForestToTumblerBridgeShapeWeightTest {

    private static ForestEvent.TreeAdded rootAdded(String treeId, RegionShape shape) {
        return new ForestEvent.TreeAdded(1L, "forest", treeId, null, shape, null);
    }

    @Test
    void shapeWeightedGreedyBalancesServerLoadNotTreeCount() {
        // Weigher: pyramid trees weigh 92 (N_pyramid(2)), hex trees 64 (N_hex(2)).
        java.util.function.ToLongFunction<String> weigher = id -> id.startsWith("pyr") ? 92 : 64;

        // Sequence: one heavy pyramid root, then four hex roots. The heavy pyramid on server-0 makes the
        // 5th tree (hex3) go to a LIGHTER server under greedy, where naive count round-robin would cycle
        // it back to the already-heavy server-0.
        String[] ids = { "pyr0", "hex0", "hex1", "hex2", "hex3" };

        var bridge = new ForestToTumblerBridge(weigher);
        for (var id : ids) {
            bridge.onEvent(rootAdded(id, id.startsWith("pyr") ? RegionShape.TETRAHEDRAL : RegionShape.CUBIC));
        }
        var greedy = bridge.getAllAssignments();

        // Reference: naive count round-robin (index % 4).
        var roundRobin = new HashMap<String, String>();
        for (int i = 0; i < ids.length; i++) {
            roundRobin.put(ids[i], "server-" + (i % 4));
        }

        // The two assignments diverge (hex3: greedy→least-loaded, round-robin→back to heavy server-0).
        assertNotEquals(roundRobin, greedy,
                        "shape-weighted greedy must diverge from count round-robin when weights are uneven");

        // And the greedy assignment yields a strictly tighter server-load spread — the §4c payoff.
        assertTrue(spread(greedy, weigher) < spread(roundRobin, weigher),
                   "greedy load spread " + spread(greedy, weigher) + " must beat round-robin "
                   + spread(roundRobin, weigher));
    }

    private static long spread(Map<String, String> assignment, java.util.function.ToLongFunction<String> w) {
        var serverWeight = new HashMap<String, Long>();
        for (var e : assignment.entrySet()) {
            serverWeight.merge(e.getValue(), w.applyAsLong(e.getKey()), Long::sum);
        }
        long max = serverWeight.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long min = serverWeight.values().stream().mapToLong(Long::longValue).min().orElse(0);
        return max - min;
    }

    @Test
    void defaultConstructorPreservesRoundRobin() {
        // Backward-compat: no weigher → exact round-robin server-0..3 (as ForestTumblerBridgeTest expects).
        var bridge = new ForestToTumblerBridge();
        for (int i = 0; i < 5; i++) {
            bridge.onEvent(rootAdded("t" + i, RegionShape.CUBIC));
        }
        var a = bridge.getAllAssignments();
        assertEquals("server-0", a.get("t0"));
        assertEquals("server-1", a.get("t1"));
        assertEquals("server-2", a.get("t2"));
        assertEquals("server-3", a.get("t3"));
        assertEquals("server-0", a.get("t4"));
    }

    @Test
    void factoryConsumesShapeWeightProviderFromForest() {
        // The genuine wiring: the weigher resolves a tree's weight from its index's elementCount(level)
        // (ShapeWeightProvider). A pyramid tree must weigh more than a hex tree at the same level.
        var forest = new Forest<com.hellblazer.luciferase.lucien.octree.MortonKey, LongEntityID, String>();
        var hex = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var hexId = forest.addTree(hex);

        var pyrForest = new Forest<com.hellblazer.luciferase.lucien.pyramid.PyramidKey, LongEntityID, String>();
        var pyr = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        var pyrId = pyrForest.addTree(pyr);

        var hexBridge = ForestToTumblerBridge.forShapeWeightedAssignment(forest, 2);
        var pyrBridge = ForestToTumblerBridge.forShapeWeightedAssignment(pyrForest, 2);

        assertEquals(64L, hexBridge.treeWeight(hexId), "hex weight = N_hex(2) = 64");
        assertEquals(92L, pyrBridge.treeWeight(pyrId), "pyramid weight = N_pyramid(2) = 92");
        assertTrue(pyrBridge.treeWeight(pyrId) > hexBridge.treeWeight(hexId),
                   "a pyramid tree must carry more shape weight than a hex tree (the §4c point)");
    }

    @Test
    void weightedRemovalAndRehomeKeepServerLoadConsistent() {
        // Exercises the recordAssignment(prior!=null) re-home path + removeAssignment decrement, so the
        // greedy keeps balancing correctly across the full tree lifecycle (not just initial adds).
        java.util.function.ToLongFunction<String> weigher = id -> id.startsWith("pyr") ? 92 : 64;
        var bridge = new ForestToTumblerBridge(weigher);

        // Two roots: pyr0→server-0 (92), hex0→server-1 (64).
        bridge.onEvent(rootAdded("pyr0", RegionShape.TETRAHEDRAL));
        bridge.onEvent(rootAdded("hex0", RegionShape.CUBIC));
        assertEquals("server-0", bridge.getServerAssignment("pyr0"));
        assertEquals("server-1", bridge.getServerAssignment("hex0"));

        // Child TreeAdded inherits the parent's server (server-0); the subsequent TreeSubdivided
        // re-records it onto the parent's server, exercising recordAssignment's prior!=null re-home path
        // (remove old credit, add new) — the bookkeeping must net to no double-count.
        bridge.onEvent(new ForestEvent.TreeAdded(2L, "f", "pyr0-c0", null, RegionShape.TETRAHEDRAL, "pyr0"));
        bridge.onEvent(new ForestEvent.TreeSubdivided(3L, "f", "pyr0", java.util.List.of("pyr0-c0"),
                                                      AdaptiveForest.AdaptationConfig.SubdivisionStrategy.OCTANT,
                                                      RegionShape.TETRAHEDRAL));
        assertEquals("server-0", bridge.getServerAssignment("pyr0-c0"),
                     "child must end up on the parent's server after subdivision");

        // Remove the child: server-0's load must drop back. The next NEW heavy root should then prefer a
        // genuinely least-loaded server, proving serverLoad was decremented (no leaked weight).
        bridge.onEvent(new ForestEvent.TreeRemoved(4L, "f", "pyr0-c0"));

        // Add a fresh root: with pyr0(92) on s0, hex0(64) on s1, s2=s3=0 → least-loaded is server-2.
        bridge.onEvent(rootAdded("pyr1", RegionShape.TETRAHEDRAL));
        assertEquals("server-2", bridge.getServerAssignment("pyr1"),
                     "after the child's weight was removed, a fresh root lands on a truly least-loaded server");
    }

    @Test
    void unknownTreeWeighsZeroWithoutThrowing() {
        var forest = new Forest<com.hellblazer.luciferase.lucien.octree.MortonKey, LongEntityID, String>();
        var bridge = ForestToTumblerBridge.forShapeWeightedAssignment(forest, 2);
        assertEquals(0L, bridge.treeWeight("no-such-tree"), "absent tree → weight 0, no throw");
    }
}

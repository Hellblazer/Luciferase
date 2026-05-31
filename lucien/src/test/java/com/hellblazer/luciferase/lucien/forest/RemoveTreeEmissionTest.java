/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.forest.ForestConfig;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 (bead Luciferase-juts): {@link AdaptiveForest#removeTree} must emit
 * {@link ForestEvent.TreeRemoved}. Before this fix a standalone {@code removeTree} on an assigned tree
 * left {@link ForestToTumblerBridge#handleTreeRemoved} as dead code and leaked the tree's entry in
 * {@code treeToServerAssignments} (the symmetric gap to the l4p0 merge fix, surfaced by l4p0 code-review
 * MED-1).
 *
 * <p>Design (option a): {@code mergeTrees} removes its sources via a non-emitting {@code removeTreeInternal}
 * so a merge announces source removal exactly once (via {@code TreesMerged}), not three times
 * ({@code TreesMerged} + 2x {@code TreeRemoved}). Each structural change is announced by exactly one event.
 */
class RemoveTreeEmissionTest {

    @Test
    void standaloneRemoveEmitsTreeRemovedAndBridgeClearsAssignment() {
        var gen = new SequentialLongIDGenerator();
        var forest = new AdaptiveForest<MortonKey, LongEntityID, String>(gen);

        var bridge = new ForestToTumblerBridge();
        forest.addEventListener(bridge);
        var removed = new ArrayList<ForestEvent.TreeRemoved>();
        forest.addEventListener(e -> {
            if (e instanceof ForestEvent.TreeRemoved tr) {
                removed.add(tr);
            }
        });

        var id = forest.addTree(new Octree<>(gen), null);
        assertNotNull(bridge.getServerAssignment(id), "tree assigned a server on add");

        var result = forest.removeTree(id);
        assertTrue(result, "removeTree returns true for an existing tree");

        assertEquals(1, removed.size(), "standalone removeTree must emit exactly one TreeRemoved");
        assertEquals(id, removed.get(0).treeId(), "TreeRemoved names the removed tree");
        assertNull(bridge.getServerAssignment(id), "server assignment cleared on removal (no leak)");
    }

    @Test
    void removeMissingTreeEmitsNothing() {
        var gen = new SequentialLongIDGenerator();
        var forest = new AdaptiveForest<MortonKey, LongEntityID, String>(gen);
        var removed = new ArrayList<ForestEvent.TreeRemoved>();
        forest.addEventListener(e -> {
            if (e instanceof ForestEvent.TreeRemoved tr) {
                removed.add(tr);
            }
        });

        assertFalse(forest.removeTree("does-not-exist"), "removeTree returns false for a missing tree");
        assertTrue(removed.isEmpty(), "no TreeRemoved emitted when nothing was removed");
    }

    @Test
    void subdivisionDoesNotEmitTreeRemovedForParent() throws Exception {
        // Single-announcement contract pin (substantive-critic SIG-3): subdivision preserves the parent in
        // the tree map and announces via TreeSubdivided only. Assert that across a real subdivision NO
        // TreeRemoved fires for the parent — so a future phase that removes the parent post-subdivision
        // (introducing a TreeRemoved + TreeSubdivided double-announce) breaks visibly here.
        var adaptationConfig = AdaptiveForest.AdaptationConfig.builder()
            .maxEntitiesPerTree(10)
            .subdivisionStrategy(AdaptiveForest.AdaptationConfig.SubdivisionStrategy.OCTANT)
            .build();
        var gen = new SequentialLongIDGenerator();
        var forest = new AdaptiveForest<MortonKey, LongEntityID, String>(
            ForestConfig.defaultConfig(), adaptationConfig, gen);
        try {
            var removed = new ArrayList<ForestEvent.TreeRemoved>();
            var subdivided = new ArrayList<ForestEvent.TreeSubdivided>();
            forest.addEventListener(e -> {
                if (e instanceof ForestEvent.TreeRemoved tr) {
                    removed.add(tr);
                } else if (e instanceof ForestEvent.TreeSubdivided ts) {
                    subdivided.add(ts);
                }
            });

            var tree = new Octree<LongEntityID, String>(gen);
            var treeId = forest.addTree(tree);
            for (int i = 0; i < 120; i++) {
                var id = gen.generateID();
                // Deterministic clustered positions — dense enough to exceed maxEntitiesPerTree(10).
                var pos = new Point3f(10f + (i % 5), 10f + ((i / 5) % 5), 10f + ((i / 25) % 5));
                tree.insert(id, pos, (byte) 0, "e" + i);
                forest.trackEntityInsertion(treeId, id, pos);
            }
            forest.checkAndAdapt();

            // Subdivision adaptation is async; poll until it lands (bounded), then assert the invariant.
            for (int i = 0; i < 40 && subdivided.isEmpty() && forest.getSubdivisionCount() == 0; i++) {
                Thread.sleep(50);
            }
            assertFalse(subdivided.isEmpty() && forest.getSubdivisionCount() == 0,
                        "subdivision must have been triggered for a non-vacuous invariant check");
            assertTrue(removed.isEmpty(),
                       "subdivision must NOT emit TreeRemoved for the parent (single-announcement), got "
                       + removed.size());
        } finally {
            forest.shutdown();
        }
    }

    @Test
    void mergeDoesNotDoubleAnnounceSourceRemoval() throws Exception {
        var gen = new SequentialLongIDGenerator();
        var forest = new AdaptiveForest<MortonKey, LongEntityID, String>(gen);
        var removed = new ArrayList<ForestEvent.TreeRemoved>();
        var merges = new ArrayList<ForestEvent.TreesMerged>();
        forest.addEventListener(e -> {
            if (e instanceof ForestEvent.TreeRemoved tr) {
                removed.add(tr);
            } else if (e instanceof ForestEvent.TreesMerged tm) {
                merges.add(tm);
            }
        });

        var id1 = forest.addTree(new Octree<>(gen), null);
        var id2 = forest.addTree(new Octree<>(gen), null);

        var m = AdaptiveForest.class.getDeclaredMethod("mergeTrees", TreeNode.class, TreeNode.class);
        m.setAccessible(true);
        m.invoke(forest, forest.getTree(id1), forest.getTree(id2));

        // Merge announces source removal exactly once (via TreesMerged), NOT via TreeRemoved.
        assertEquals(1, merges.size(), "merge emits exactly one TreesMerged");
        assertTrue(removed.isEmpty(),
                   "merge must NOT emit TreeRemoved for its sources (single-announcement contract), got "
                   + removed.size());
    }
}

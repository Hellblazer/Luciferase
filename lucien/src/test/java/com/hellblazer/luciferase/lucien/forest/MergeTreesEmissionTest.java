/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 (bead Luciferase-l4p0): {@link AdaptiveForest#mergeTrees} must emit
 * {@link ForestEvent.TreesMerged}. Before this fix the merged tree was created via {@code addTreeInternal}
 * (no {@code TreeAdded}) and no {@code TreesMerged} was emitted, so {@link ForestToTumblerBridge#getServerAssignment}
 * leaked the source trees' server assignments and the merged tree was never assigned —
 * {@code handleTreesMerged} was dead code.
 */
class MergeTreesEmissionTest {

    @Test
    void mergeEmitsTreesMergedAndBridgeReconcilesAssignments() throws Exception {
        var gen = new SequentialLongIDGenerator();
        var forest = new AdaptiveForest<MortonKey, LongEntityID, String>(gen);

        var bridge = new ForestToTumblerBridge();
        forest.addEventListener(bridge);
        var merges = new ArrayList<ForestEvent.TreesMerged>();
        forest.addEventListener(e -> {
            if (e instanceof ForestEvent.TreesMerged tm) {
                merges.add(tm);
            }
        });

        // Two root trees — each assigned a server on add (RDR-010 7poh root TreeAdded emission).
        var id1 = forest.addTree(new Octree<>(gen), null);
        var id2 = forest.addTree(new Octree<>(gen), null);
        assertNotNull(bridge.getServerAssignment(id1), "source tree 1 assigned on add");
        assertNotNull(bridge.getServerAssignment(id2), "source tree 2 assigned on add");

        // Drive the (private) merge directly — the merge-consideration loop only fires for low-density
        // adjacent trees; reflection gives a deterministic exercise of the event path.
        var m = AdaptiveForest.class.getDeclaredMethod("mergeTrees", TreeNode.class, TreeNode.class);
        m.setAccessible(true);
        m.invoke(forest, forest.getTree(id1), forest.getTree(id2));

        // Exactly one TreesMerged naming both sources and the merged tree.
        assertEquals(1, merges.size(), "merge must emit exactly one TreesMerged");
        var event = merges.get(0);
        assertEquals(List.of(id1, id2), event.sourceIds(), "TreesMerged must name both source trees");
        assertNotNull(event.mergedId());
        assertNotEquals(id1, event.mergedId());
        assertNotEquals(id2, event.mergedId());

        // The bridge reconciled: source assignments cleared, merged tree assigned (no leak, no dead code).
        assertNull(bridge.getServerAssignment(id1), "source 1 server assignment cleared on merge");
        assertNull(bridge.getServerAssignment(id2), "source 2 server assignment cleared on merge");
        assertNotNull(bridge.getServerAssignment(event.mergedId()), "merged tree assigned a server");
    }
}

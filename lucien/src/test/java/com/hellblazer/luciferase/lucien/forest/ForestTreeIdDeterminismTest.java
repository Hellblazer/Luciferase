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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-7wzml.100: generateTreeId must not use System.nanoTime().
 * Two forests built from the same sequence of metadata must produce identical
 * tree-id sequences (deterministic), and IDs within a single forest must be unique.
 */
class ForestTreeIdDeterminismTest {

    private static final Instant FIXED_TS = Instant.ofEpochMilli(0);

    private static Octree<LongEntityID, String> newOctree() {
        return new Octree<>(new SequentialLongIDGenerator(), 10, (byte) 5);
    }

    private static TreeMetadata namedMeta(String name) {
        return TreeMetadata.builder()
                           .name(name)
                           .treeType(TreeMetadata.TreeType.OCTREE)
                           .creationTimestamp(FIXED_TS)
                           .build();
    }

    /**
     * Documents the Forest-scoped (NOT global) id space.
     *
     * <p>Two independently constructed Forest instances each start their counter at zero,
     * so the same metadata sequence produces the same ID strings in both.  This is a
     * <em>known scoping constraint</em>, not global uniqueness — callers that cross Forest
     * boundaries (distributed ghost layers, shared registries, gRPC balance clients) must
     * namespace the ID with a Forest-level identifier before using it as a global key
     * (Luciferase-7wzml.100).
     */
    @Test
    void forestScopedIdSpace_sameSequenceYieldsSameIds() {
        // Build forest A
        var forestA = new Forest<MortonKey, LongEntityID, String>();
        var idA1 = forestA.addTree(newOctree(), namedMeta("alpha"));
        var idA2 = forestA.addTree(newOctree(), namedMeta("beta"));
        var idA3 = forestA.addTree(newOctree(), null);

        // Build forest B — same metadata sequence, fresh counter starts at 0.
        // Identical IDs are expected: each Forest has its own counter, both start at 0.
        // This is NOT global uniqueness — cross-Forest callers must add their own namespace.
        var forestB = new Forest<MortonKey, LongEntityID, String>();
        var idB1 = forestB.addTree(newOctree(), namedMeta("alpha"));
        var idB2 = forestB.addTree(newOctree(), namedMeta("beta"));
        var idB3 = forestB.addTree(newOctree(), null);

        assertEquals(idA1, idB1, "Forest-scoped IDs: same metadata sequence => same id (known scoping constraint, not global uniqueness)");
        assertEquals(idA2, idB2, "Forest-scoped IDs: same metadata sequence => same id (known scoping constraint, not global uniqueness)");
        assertEquals(idA3, idB3, "Forest-scoped IDs: same metadata sequence => same id (known scoping constraint, not global uniqueness)");
    }

    @Test
    void withinForestIdsAreSequentialAndDeterministic() {
        var forest = new Forest<MortonKey, LongEntityID, String>();
        var id0 = forest.addTree(newOctree(), namedMeta("alpha"));
        var id1 = forest.addTree(newOctree(), namedMeta("beta"));
        var id2 = forest.addTree(newOctree(), null);

        // IDs must be distinct within a single Forest
        assertNotEquals(id0, id1, "id0 != id1 within one forest");
        assertNotEquals(id1, id2, "id1 != id2 within one forest");
        assertNotEquals(id0, id2, "id0 != id2 within one forest");

        // IDs must embed the name prefix for named trees (sequential determinism)
        assertTrue(id0.startsWith("alpha_"), "named tree id must start with name prefix: " + id0);
        assertTrue(id1.startsWith("beta_"), "named tree id must start with name prefix: " + id1);
        assertTrue(id2.startsWith("tree_"), "unnamed tree id must start with 'tree_': " + id2);
    }

    @Test
    void idsAreUniqueWithinForest() {
        var forest = new Forest<MortonKey, LongEntityID, String>();
        var ids = new ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            ids.add(forest.addTree(newOctree(), namedMeta("tree" + i)));
        }
        var distinct = new HashSet<>(ids);
        assertEquals(ids.size(), distinct.size(), "All tree IDs within a forest must be unique");
    }

    @Test
    void noSystemNanoTimeInGeneratedIds() {
        // Structural test: if System.nanoTime() is absent, two forests
        // with identical metadata produce identical IDs regardless of when called.
        var meta = namedMeta("stable");

        var f1 = new Forest<MortonKey, LongEntityID, String>();
        var id1 = f1.addTree(newOctree(), meta);

        var f2 = new Forest<MortonKey, LongEntityID, String>();
        var id2 = f2.addTree(newOctree(), meta);

        assertEquals(id1, id2,
                     "IDs from two fresh forests with identical metadata must match; System.nanoTime() causes divergence");
    }
}

/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.migration;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-2qpd2: {@code findEntityLevel} for the Tetree path used a reflective {@code getMethod("getLevel")}
 * with {@code catch(Exception){return 0;}} — a rename would have silently migrated every entity to level 0. The fix
 * calls {@link com.hellblazer.luciferase.lucien.SpatialKey#getLevel()} directly. This test migrates a Tetree (whose
 * keys went through the reflective path) to an Octree and verifies the entity lands at its real, non-zero level.
 *
 * @author hal.hildebrand
 */
class SpatialIndexConverterLevelTest {

    @Test
    void tetreeToOctreeMigrationPreservesNonZeroLevel() {
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        byte level = 10;
        var id = new LongEntityID(1);
        tetree.insert(id, new Point3f(300, 300, 300), level, "e");

        var octree = SpatialIndexConverter.tetreeToOctree(tetree, new SequentialLongIDGenerator());

        // The migrated entity must NOT be at level 0 (which the broken reflection would have produced). Find the
        // deepest node holding any entity in the target and assert it is a real, non-zero level.
        int migratedLevel = octree.nodes()
                                  .filter(node -> !node.entityIds().isEmpty())
                                  .mapToInt(node -> node.sfcIndex().getLevel())
                                  .max()
                                  .orElse(-1);

        assertTrue(migratedLevel > 0,
                   "migrated entity must keep a real non-zero level, not the reflection-broken 0 (Luciferase-2qpd2); "
                   + "got " + migratedLevel);
    }
}

/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for Luciferase-0frcy.24: DuplicateEntityDetector.reconcile() must re-query the
 * MigrationLog at reconcile time and verify the authoritative source still holds the entity, rather
 * than acting on the stale scan snapshot.
 * <p>
 * Scenario: scan() builds a DuplicateEntity at T1 with source = B (the target of the then-latest
 * migration A→B). Between T1 and reconcile (T2), the entity migrates again B→C and physically moves
 * to C. Acting on the stale snapshot (source=B) would delete the entity from C — its live location —
 * and keep the stale copy in B, LOSING the entity's live position. The fix re-resolves source = C
 * and prunes B instead.
 */
class DuplicateEntityReconcileStaleSnapshotTest {

    @Test
    void reconcileUsesFreshMigrationNotStaleSnapshot() {
        var grid = new TetreeBubbleGrid((byte) 3);
        grid.createBubbles(3, (byte) 2, 100);
        var bubbles = new ArrayList<>(grid.getAllBubbles());
        org.junit.jupiter.api.Assertions.assertTrue(bubbles.size() >= 3, "need 3 bubbles");
        var migrationLog = new MigrationLog();
        var detector = new DuplicateEntityDetector(grid, migrationLog, DuplicateDetectionConfig.defaultConfig());

        var bubbleA = bubbles.get(0);
        var bubbleB = bubbles.get(1);
        var bubbleC = bubbles.get(2);
        var entityId = "wandering-entity";
        var key = new StringEntityID(entityId);

        // T1 state: migration A -> B already recorded; entity present in B (and a stale copy in C
        // from an out-of-order ghost). The scan snapshot will see source = B.
        migrationLog.recordMigration(key, UUID.randomUUID(), bubbleA.id(), bubbleB.id(), 1L);
        var staleSnapshot = new DuplicateEntity(
            entityId,
            Set.of(bubbleB.id(), bubbleC.id()),
            migrationLog.getLatestMigration(key));  // sourceBubble == B

        // Between scan and reconcile: a newer migration B -> C happens and the entity is now LIVE in C.
        migrationLog.recordMigration(key, UUID.randomUUID(), bubbleB.id(), bubbleC.id(), 2L);
        bubbleB.addEntity(entityId, new Point3f(0.1f, 0.1f, 0.1f), null);  // stale copy left behind in B
        bubbleC.addEntity(entityId, new Point3f(0.2f, 0.2f, 0.2f), null);  // live copy in C

        // Reconcile the STALE snapshot. Pre-fix: deletes from C (stale source=B kept) -> entity lost.
        // Post-fix: re-resolves source=C, verifies C holds it, prunes B.
        detector.reconcile(staleSnapshot);

        assertTrue(bubbleC.getEntities().contains(entityId),
                   "Live entity in C (latest migration target) must NOT be deleted by a stale snapshot");
    }
}

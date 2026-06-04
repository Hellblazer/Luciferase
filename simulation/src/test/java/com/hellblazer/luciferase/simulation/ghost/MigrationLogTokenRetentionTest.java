/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * Licensed under AGPL v3.0. See LICENSE.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.104: idempotency tokens must survive history cleanup. The wave-2
 * fix (Luciferase-0frcy.26) co-located token removal with history removal in {@code cleanupBefore}, so a
 * cleanup that purged an entity's old history records also dropped its idempotency tokens — letting a
 * later re-delivery of the same migration token be (incorrectly) accepted as new. The fix decouples
 * token cleanup from history cleanup: tokens outlive the records they guard.
 *
 * @author hal.hildebrand
 */
class MigrationLogTokenRetentionTest {

    @Test
    void recordedTokenRemainsDuplicateAfterHistoryCleanup() {
        var log = new MigrationLog();
        var entity = new StringEntityID("e1");
        var token = UUID.randomUUID();
        var source = UUID.randomUUID();
        var target = UUID.randomUUID();

        // Record a migration at bucket 1.
        assertTrue(log.recordMigration(entity, token, source, target, 1L), "first record must succeed");
        assertTrue(log.isDuplicate(entity, token), "token must be known immediately after recording");

        // Cleanup everything before bucket 5 — purges the bucket-1 history record (and empties history).
        log.cleanupBefore(5L);

        // The idempotency guard must still hold: a re-delivery of the SAME token must be rejected.
        assertTrue(log.isDuplicate(entity, token),
                   "token must survive history cleanup (Luciferase-0frcy.104)");
        assertFalse(log.recordMigration(entity, token, source, target, 1L),
                    "re-delivery of an already-recorded token must be rejected even after cleanup "
                    + "(Luciferase-0frcy.104)");
    }

    @Test
    void distinctTokenAfterCleanupIsStillAccepted() {
        var log = new MigrationLog();
        var entity = new StringEntityID("e1");
        var source = UUID.randomUUID();
        var target = UUID.randomUUID();

        assertTrue(log.recordMigration(entity, UUID.randomUUID(), source, target, 1L));
        log.cleanupBefore(5L);

        // A genuinely new migration (different token) must still be accepted after cleanup.
        assertTrue(log.recordMigration(entity, UUID.randomUUID(), source, target, 6L),
                   "a new token must still be accepted after cleanup");
    }
}

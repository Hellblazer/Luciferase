/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-017 P3 (Luciferase-skaui, gate O1) — WAL clean-shutdown lifecycle policy: {@code closeClean()}
 * checkpoints then truncates the log segments (compaction), and a later restart continues the monotonic
 * sequence past the checkpoint rather than colliding below it.
 */
class WalCleanShutdownPolicyTest {

    private static int logLineCount(Path walDir, UUID nodeId) throws IOException {
        var logFile = walDir.resolve("node-" + nodeId + ".log");
        return Files.exists(logFile) ? Files.readAllLines(logFile, StandardCharsets.UTF_8).size() : 0;
    }

    @Test
    void cleanCloseCheckpointsAndTruncates_restartReplaysNothing(@TempDir Path walDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var pm = new PersistenceManager(nodeId, walDir);
        // MIGRATION_COMMIT fsyncs (so the segment has flushed content on disk); the departures are
        // buffered. Either way closeClean must leave an empty segment afterward.
        pm.logEntityDeparture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        pm.logMigrationCommit(UUID.randomUUID());
        pm.logMigrationCommit(UUID.randomUUID());
        assertTrue(logLineCount(walDir, nodeId) > 0, "fsynced commits must be on disk before shutdown");

        pm.closeClean();   // checkpoint + truncate

        assertTrue(Files.exists(walDir.resolve("node-" + nodeId + ".meta")),
                   "clean shutdown must leave a checkpoint");
        assertEquals(0, logLineCount(walDir, nodeId),
                     "clean shutdown must truncate the log segments (compaction) — checkpoint-only would "
                     + "leave the flushed lines on disk");

        // Reopen: recovery replays nothing (the head checkpoint supersedes the truncated prefix).
        try (var pm2 = new PersistenceManager(nodeId, walDir)) {
            var recovered = pm2.recover();
            assertEquals(0, recovered.events().size(),
                         "a clean-shutdown restart must replay no events");
        }
    }

    @Test
    void postTruncateAppendsContinueSequencePastCheckpoint_noCollision(@TempDir Path walDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var pm = new PersistenceManager(nodeId, walDir);
        pm.logEntityDeparture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        pm.logEntityDeparture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        pm.logEntityDeparture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        pm.closeClean();   // checkpoint at seq=3, logs truncated

        // A fresh manager appends a NEW event, then "crashes" (crash-safe close retains the WAL).
        var newEntity = UUID.randomUUID();
        var pm2 = new PersistenceManager(nodeId, walDir);
        pm2.logEntityDeparture(newEntity, UUID.randomUUID(), UUID.randomUUID());
        pm2.close();       // crash: flush + retain, no new checkpoint

        // Recovery filters events with sequence > checkpoint(3). The new event must have sequence 4
        // (continued past the checkpoint), NOT 1 — otherwise readEventsSince(3) would silently drop it.
        try (var pm3 = new PersistenceManager(nodeId, walDir)) {
            var recovered = pm3.recover();
            assertEquals(1, recovered.events().size(),
                         "the post-truncate append must survive recovery (sequence continued past checkpoint)");
            assertEquals(newEntity.toString(), recovered.events().get(0).get("entityId"),
                         "the recovered event must be the post-truncate departure");
        }
    }
}

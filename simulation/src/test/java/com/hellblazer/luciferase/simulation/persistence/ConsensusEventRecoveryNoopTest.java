/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RDR-017 P3 (Luciferase-skaui, §Approach.5a) — the four consensus event types are recognized as
 * deliberate no-ops in {@link EventRecovery#replayEvents}, so a WAL containing only consensus events does
 * NOT emit a spurious {@code "Unknown event type"} WARN on every restart. This is WARN-silencing only:
 * consensus-event recovery (a real FSM sink) remains out of scope.
 */
class ConsensusEventRecoveryNoopTest {

    private Logger recoveryLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        var ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        recoveryLogger = ctx.getLogger(EventRecovery.class);
        appender = new ListAppender<>();
        appender.start();
        recoveryLogger.addAppender(appender);
        recoveryLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        recoveryLogger.detachAppender(appender);
    }

    private boolean warnedUnknownType() {
        return appender.list.stream()
                            .anyMatch(e -> e.getLevel() == Level.WARN
                                           && e.getFormattedMessage().contains("Unknown event type"));
    }

    @Test
    void consensusOnlyWalReplaysWithoutUnknownTypeWarn(@TempDir Path walDir) throws IOException {
        var nodeId = UUID.randomUUID();
        try (var pm = new PersistenceManager(nodeId, walDir)) {
            pm.logElectionStart(UUID.randomUUID(), 1L);
            pm.logVoteCast(UUID.randomUUID(), UUID.randomUUID(), 1L, true);
            pm.logLeaderElected(UUID.randomUUID(), 1L);
            pm.logTermIncrement(2L);
        }

        try (var pm2 = new PersistenceManager(nodeId, walDir)) {
            pm2.recover();
        }

        assertFalse(warnedUnknownType(),
                    "consensus events must be recognized no-ops — no 'Unknown event type' WARN on restart");
    }

    @Test
    void trulyUnknownTypeStillWarns(@TempDir Path walDir) throws IOException {
        // Control: proves the appender + assertion actually observe the WARN arm (non-vacuous).
        var nodeId = UUID.randomUUID();
        try (var wal = new WriteAheadLog(nodeId, walDir)) {
            var event = new java.util.HashMap<String, Object>();
            event.put("version", 1);
            event.put("type", "DEFINITELY_NOT_A_REAL_TYPE");
            event.put("timestamp", java.time.Instant.EPOCH.toString());
            wal.append(event);
            wal.flush();
        }

        new EventRecovery(walDir).recover(nodeId);

        assertTrue(warnedUnknownType(),
                   "a genuinely unknown event type must still emit the 'Unknown event type' WARN");
    }
}

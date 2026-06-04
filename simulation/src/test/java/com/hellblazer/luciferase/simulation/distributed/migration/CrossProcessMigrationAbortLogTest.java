/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.distributed.migration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3d;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-0frcy.109: abort() must not emit the DEBUG "Restored entity" success message when the
 * rollback re-add to the source actually FAILED (restored == false). Logging it unconditionally
 * produces a false success trace that masks rollback-failure data loss.
 */
class CrossProcessMigrationAbortLogTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    /** Source whose addEntity (rollback re-add) FAILS, simulating a rollback that could not restore. */
    private static final class RollbackFailingSource implements BubbleReference, TestableEntityStore {
        private final UUID bubbleId;
        RollbackFailingSource(UUID bubbleId) { this.bubbleId = bubbleId; }
        @Override public boolean isLocal() { return true; }
        @Override public LocalBubbleReference asLocal() { return null; }
        @Override public RemoteBubbleProxy asRemote() { throw new IllegalStateException(); }
        @Override public UUID getBubbleId() { return bubbleId; }
        @Override public Point3d getPosition() { return new Point3d(0, 0, 0); }
        @Override public Set<UUID> getNeighbors() { return new HashSet<>(); }
        @Override public boolean isReachable() { return true; }
        @Override public boolean removeEntity(String entityId) { return true; }
        @Override public EntitySnapshot getEntitySnapshot(String entityId) { return null; }
        @Override public boolean addEntity(EntitySnapshot snapshot) { return false; } // rollback fails
    }

    /** Destination that fails COMMIT, forcing an ABORT/rollback. */
    private static final class FailingCommitDest implements BubbleReference, TestableEntityStore {
        private final UUID bubbleId;
        FailingCommitDest(UUID bubbleId) { this.bubbleId = bubbleId; }
        @Override public boolean isLocal() { return true; }
        @Override public LocalBubbleReference asLocal() { return null; }
        @Override public RemoteBubbleProxy asRemote() { throw new IllegalStateException(); }
        @Override public UUID getBubbleId() { return bubbleId; }
        @Override public Point3d getPosition() { return new Point3d(0, 0, 0); }
        @Override public Set<UUID> getNeighbors() { return new HashSet<>(); }
        @Override public boolean isReachable() { return true; }
        @Override public boolean removeEntity(String entityId) { return true; }
        @Override public EntitySnapshot getEntitySnapshot(String entityId) { return null; }
        @Override public boolean addEntity(EntitySnapshot snapshot) { return false; } // COMMIT fails
    }

    @BeforeEach
    void setUp() {
        var ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = ctx.getLogger(CrossProcessMigration.class);
        appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
        }
    }

    @Test
    void noRestoredEntityDebugWhenRollbackFails() throws Exception {
        var entityId = UUID.randomUUID().toString();
        var source = new RollbackFailingSource(UUID.randomUUID());
        var dest = new FailingCommitDest(UUID.randomUUID());

        var metrics = new MigrationMetrics();
        var migration = new CrossProcessMigration(new IdempotencyStore(), metrics);
        var result = migration.migrate(entityId, source, dest).get(5, TimeUnit.SECONDS);

        assertFalse(result.success(), "Migration aborts when COMMIT is rejected");
        assertEquals(1, metrics.getRollbackFailures(), "rollback failure must be recorded when re-add fails");

        var restoredMessages = appender.list.stream()
            .filter(e -> e.getFormattedMessage().contains("ABORT: Restored entity"))
            .count();
        assertEquals(0, restoredMessages,
            "Must NOT log 'ABORT: Restored entity' when restoration (source.addEntity) failed");

        // The error path must still be present (rollback failure logged at ERROR).
        var rollbackFailedErrors = appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains("ABORT/Rollback FAILED"))
            .count();
        assertTrue(rollbackFailedErrors >= 1, "rollback failure must be logged at ERROR");
    }
}

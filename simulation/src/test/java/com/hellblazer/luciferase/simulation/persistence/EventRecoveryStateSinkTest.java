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

package com.hellblazer.luciferase.simulation.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-vacuous state-reconstruction test for {@link EventRecovery} (Luciferase-7wzml.5).
 *
 * <p>Pre-fix, all four replay handlers were log.debug-only hollow stubs — a crash lost ALL
 * in-flight migration/physics state while the API reported successful recovery. This test
 * writes a WAL containing all four event types, simulates a crash (no wal.close()), then
 * drives recovery with a tracking {@link RecoveryStateSink} and asserts that each event
 * type caused a real mutation in the sink — not just a log line and a non-zero event count.
 *
 * @author hal.hildebrand
 */
class EventRecoveryStateSinkTest {

    /** Tracking sink that records every call for assertion. */
    static class TrackingSink implements RecoveryStateSink {
        final List<String> departedEntities     = new CopyOnWriteArrayList<>();
        final List<String> viewSyncAckEntities  = new CopyOnWriteArrayList<>();
        final List<float[]> deferredPositions   = new CopyOnWriteArrayList<>();
        final List<String> committedEntities    = new CopyOnWriteArrayList<>();

        @Override
        public void onEntityDeparture(String entityId, String sourceBubble, String targetBubble) {
            departedEntities.add(entityId);
        }

        @Override
        public void onViewSynchronyAck(String entityId, boolean success) {
            viewSyncAckEntities.add(entityId);
        }

        @Override
        public void onDeferredUpdate(String entityId, float[] position, float[] velocity) {
            if (position != null) {
                deferredPositions.add(position.clone());
            }
        }

        @Override
        public void onMigrationCommit(String entityId) {
            committedEntities.add(entityId);
        }
    }

    private static Map<String, Object> departureEvent(String entityId, String src, String tgt) {
        var e = new HashMap<String, Object>();
        e.put("version", 1);
        e.put("type", "ENTITY_DEPARTURE");
        e.put("entityId", entityId);
        e.put("sourceBubble", src);
        e.put("targetBubble", tgt);
        e.put("timestamp", System.currentTimeMillis());
        return e;
    }

    private static Map<String, Object> viewSyncAckEvent(String entityId, boolean success) {
        var e = new HashMap<String, Object>();
        e.put("version", 1);
        e.put("type", "VIEW_SYNC_ACK");
        e.put("entityId", entityId);
        e.put("success", success);
        e.put("timestamp", System.currentTimeMillis());
        return e;
    }

    private static Map<String, Object> deferredUpdateEvent(String entityId, List<Number> pos, List<Number> vel) {
        var e = new HashMap<String, Object>();
        e.put("version", 1);
        e.put("type", "DEFERRED_UPDATE");
        e.put("entityId", entityId);
        e.put("position", pos);
        e.put("velocity", vel);
        e.put("timestamp", System.currentTimeMillis());
        return e;
    }

    private static Map<String, Object> migrationCommitEvent(String entityId) {
        var e = new HashMap<String, Object>();
        e.put("version", 1);
        e.put("type", "MIGRATION_COMMIT");
        e.put("entityId", entityId);
        e.put("timestamp", System.currentTimeMillis());
        return e;
    }

    @Test
    void crashRecoveryReconstructsStateViaInjectedSink(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        var entityA = "entity-alpha";
        var entityB = "entity-beta";
        var srcBubble = UUID.randomUUID().toString();
        var tgtBubble = UUID.randomUUID().toString();
        List<Number> expectedPos = List.of(1.0, 2.5, 3.0);
        List<Number> expectedVel = List.of(0.1, 0.2, 0.3);

        // Write WAL; simulate crash by not calling close()
        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            wal.append(departureEvent(entityA, srcBubble, tgtBubble));
            wal.append(viewSyncAckEvent(entityA, true));
            wal.append(deferredUpdateEvent(entityB, expectedPos, expectedVel));
            wal.append(migrationCommitEvent(entityA));
            wal.flush();
        }

        var sink = new TrackingSink();
        var recovery = new EventRecovery(logDir, sink);
        var state = recovery.recover(nodeId);

        // ---- WAL was read (non-vacuous event count) ----
        assertThat(state.totalEventsReplayed())
            .as("Recovery must replay all 4 events from the WAL")
            .isEqualTo(4);

        // ---- ENTITY_DEPARTURE applied to real state ----
        assertThat(sink.departedEntities)
            .as("ENTITY_DEPARTURE must be applied to the sink, not merely logged")
            .containsExactly(entityA);

        // ---- VIEW_SYNC_ACK applied to real state ----
        assertThat(sink.viewSyncAckEntities)
            .as("VIEW_SYNC_ACK must be applied to the sink, not merely logged")
            .containsExactly(entityA);

        // ---- DEFERRED_UPDATE position applied to real state ----
        assertThat(sink.deferredPositions)
            .as("DEFERRED_UPDATE must apply position to the sink")
            .hasSize(1);
        assertThat(sink.deferredPositions.get(0))
            .as("Recovered position must match the written position")
            .containsExactly(1.0f, 2.5f, 3.0f);

        // ---- MIGRATION_COMMIT applied to real state ----
        assertThat(sink.committedEntities)
            .as("MIGRATION_COMMIT must be applied to the sink, not merely logged")
            .containsExactly(entityA);
    }

    @Test
    void noSinkConstructorRemainsBackwardCompatible(@TempDir Path logDir) throws IOException {
        // The 1-arg constructor must still work (no NPE, no crash).
        var nodeId = UUID.randomUUID();
        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            wal.append(departureEvent("e1", "s", "t"));
            wal.flush();
        }
        var recovery = new EventRecovery(logDir);  // no sink injected
        var state = recovery.recover(nodeId);
        assertThat(state.totalEventsReplayed()).isEqualTo(1);
    }
}

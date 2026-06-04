/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checkpoint-filtering regression for {@link EventRecovery} (Luciferase-0frcy.37).
 *
 * <p>Pre-fix, recover() loaded the checkpoint (and its sequenceNumber) but then called
 * {@code wal.readAllEvents()} unconditionally — replaying the full event history regardless of the
 * checkpoint, making the checkpoint a no-op for recovery. This test writes events, checkpoints, then
 * writes more, and asserts recovery replays only the post-checkpoint events.
 *
 * @author hal.hildebrand
 */
class EventRecoveryCheckpointTest {

    private static HashMap<String, Object> departureEvent(String entityId) {
        var event = new HashMap<String, Object>();
        event.put("version", 1);
        event.put("type", "ENTITY_DEPARTURE");
        event.put("entityId", entityId);
        event.put("timestamp", System.currentTimeMillis());
        return event;
    }

    @Test
    void recoveryReplaysOnlyEventsAfterTheCheckpoint(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();

        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            // 10 pre-checkpoint events (sequences 1..10).
            for (int i = 0; i < 10; i++) {
                wal.append(departureEvent("pre-" + i));
            }
            // Checkpoint at sequence 10.
            wal.checkpoint(10, Instant.now());
            // 5 post-checkpoint events (sequences 11..15).
            for (int i = 0; i < 5; i++) {
                wal.append(departureEvent("post-" + i));
            }
        }

        var recovery = new EventRecovery(logDir);
        var state = recovery.recover(nodeId);

        assertThat(state.events())
            .as("Recovery must replay only the 5 events AFTER the checkpoint at seq=10, "
                + "not all 15 — pre-fix readAllEvents() replayed the full history")
            .hasSize(5);
        assertThat(state.events())
            .allSatisfy(e -> assertThat((String) e.get("entityId")).startsWith("post-"));
    }

    @Test
    void noCheckpointReplaysFullLog(@TempDir Path logDir) throws IOException {
        // With no checkpoint metadata, getLastCheckpoint returns seq=0, so all events replay.
        var nodeId = UUID.randomUUID();
        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            for (int i = 0; i < 3; i++) {
                wal.append(departureEvent("e-" + i));
            }
        }

        var recovery = new EventRecovery(logDir);
        var state = recovery.recover(nodeId);
        assertThat(state.events())
            .as("Without a checkpoint, recovery replays the full log (seq>0)")
            .hasSize(3);
    }

    // ---- validateRecoveryIntegrity(RecoveredState): real, falsifiable integrity gate ----
    // (Luciferase-5yh9h — the old no-arg gate was a vacuous `return true`.)

    private static HashMap<String, Object> seqEvent(String entityId, long seq) {
        var event = departureEvent(entityId);
        event.put("sequenceNumber", seq);
        return event;
    }

    @Test
    void integrityPassesForMonotonicPostCheckpointTail(@TempDir Path logDir) {
        var recovery = new EventRecovery(logDir);
        var checkpoint = new CheckpointMetadata(10, Instant.now());
        // Sequences strictly increasing and all > checkpoint(10).
        var events = java.util.List.<java.util.Map<String, Object>>of(
            seqEvent("a", 11), seqEvent("b", 12), seqEvent("c", 13));
        var state = new RecoveredState(checkpoint, events, 13, 0);
        assertThat(recovery.validateRecoveryIntegrity(state))
            .as("Monotonic, post-checkpoint tail with valid types must pass")
            .isTrue();
    }

    @Test
    void integrityFailsOnNonMonotonicSequence(@TempDir Path logDir) {
        var recovery = new EventRecovery(logDir);
        var checkpoint = new CheckpointMetadata(0, Instant.now());
        // 12 then 11 — a reordering/corruption that the vacuous gate never caught.
        var events = java.util.List.<java.util.Map<String, Object>>of(
            seqEvent("a", 11), seqEvent("b", 12), seqEvent("c", 11));
        var state = new RecoveredState(checkpoint, events, 11, 0);
        assertThat(recovery.validateRecoveryIntegrity(state))
            .as("A non-monotonic replayed sequence indicates corruption and must fail")
            .isFalse();
    }

    @Test
    void integrityFailsWhenTailReplaysCheckpointedEvents(@TempDir Path logDir) {
        var recovery = new EventRecovery(logDir);
        var checkpoint = new CheckpointMetadata(10, Instant.now());
        // First replayed seq (10) is NOT > checkpoint(10) — recovery re-reading a captured prefix.
        var events = java.util.List.<java.util.Map<String, Object>>of(
            seqEvent("a", 10), seqEvent("b", 11));
        var state = new RecoveredState(checkpoint, events, 11, 0);
        assertThat(recovery.validateRecoveryIntegrity(state))
            .as("Replaying an event at/below the checkpoint sequence must fail integrity")
            .isFalse();
    }

    @Test
    void integrityFailsOnEventMissingType(@TempDir Path logDir) {
        var recovery = new EventRecovery(logDir);
        var bad = new HashMap<String, Object>();
        bad.put("entityId", "x");  // no "type"
        bad.put("sequenceNumber", 11L);
        var state = new RecoveredState(new CheckpointMetadata(0, Instant.now()),
                                       java.util.List.of(bad), 1, 0);
        assertThat(recovery.validateRecoveryIntegrity(state))
            .as("An event with no type is malformed and must fail integrity")
            .isFalse();
    }
}

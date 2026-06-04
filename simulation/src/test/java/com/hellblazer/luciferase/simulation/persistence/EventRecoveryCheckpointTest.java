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
}

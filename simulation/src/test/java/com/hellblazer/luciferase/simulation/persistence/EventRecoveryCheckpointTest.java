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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        // WAL stores per-event ordinals under "sequence" (matching WriteAheadLog.append :140).
        // Bead Luciferase-7wzml.209 fix: validateRecoveryIntegrity reads "sequence", not "sequenceNumber".
        event.put("sequence", seq);
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
        bad.put("sequence", 11L);
        var state = new RecoveredState(new CheckpointMetadata(0, Instant.now()),
                                       java.util.List.of(bad), 1, 0);
        assertThat(recovery.validateRecoveryIntegrity(state))
            .as("An event with no type is malformed and must fail integrity")
            .isFalse();
    }

    // ---- C1/H1/M1 gate tests (Luciferase-7wzml.209/.211) ----

    /**
     * TDD test 1: mid-file corrupt line must cause recover() to throw, not silently proceed.
     * C1 — RDR-004-class 'detect-but-proceed' pattern eliminated.
     */
    @Test
    void midFileCorruptCausesRecoverToThrow(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            wal.append(departureEvent("e1"));
            wal.append(departureEvent("e2"));
            wal.append(departureEvent("e3"));
            wal.flush();
        }
        // Inject a corrupt line in the middle (not the last line).
        var logFile = logDir.resolve("node-" + nodeId + ".log");
        var lines = Files.readAllLines(logFile);
        lines.set(1, "NOT_VALID_JSON{{");
        // Add a valid trailing line so the corrupt line is clearly mid-file.
        lines.add("{\"version\":1,\"type\":\"ENTITY_DEPARTURE\",\"entityId\":\"e4\",\"sequence\":4}");
        Files.write(logFile, lines, StandardOpenOption.TRUNCATE_EXISTING);

        var recovery = new EventRecovery(logDir);
        var ex = assertThrows(IOException.class, () -> recovery.recover(nodeId),
            "Mid-file corrupt line must cause recover() to throw (C1 fail-loud)");
        assertThat(ex.getMessage())
            .contains("mid-file corrupt")
            .contains("manual inspection required");
    }

    /**
     * TDD test 2: torn final line in the ACTIVE file recovers cleanly (no throw).
     */
    @Test
    void tornTailInActiveFileRecoversCleanlly(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            wal.append(departureEvent("e1"));
            wal.append(departureEvent("e2"));
            wal.flush();
        }
        // Append a truncated (torn) final line to simulate crash-flush.
        var logFile = logDir.resolve("node-" + nodeId + ".log");
        Files.writeString(logFile, "{\"version\":1,\"type\":\"TORN", StandardOpenOption.APPEND);

        var recovery = new EventRecovery(logDir);
        var state = recovery.recover(nodeId);

        assertThat(state.skippedCorrupt())
            .as("Torn tail on the active file must NOT be counted as corrupt")
            .isEqualTo(0);
        assertThat(state.events()).hasSize(2);
    }

    /**
     * TDD test 4: non-monotonic WAL sequence must cause recover() to throw via validateRecoveryIntegrity.
     * M1 — integrity gate wired.
     */
    @Test
    void nonMonotonicWalCausesRecoverToThrowViaIntegrityGate(@TempDir Path logDir) throws IOException {
        // Write events with a manually injected non-monotonic sequence into the log file.
        var nodeId = UUID.randomUUID();
        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            wal.append(departureEvent("e1"));
            wal.flush();
        }
        var logFile = logDir.resolve("node-" + nodeId + ".log");
        // Append an event with sequence=1 after one that has sequence=2 — non-monotonic.
        // We write raw JSONL to avoid going through WAL which enforces monotonicity.
        Files.writeString(logFile,
            "{\"version\":1,\"type\":\"ENTITY_DEPARTURE\",\"entityId\":\"e2\",\"sequence\":5}\n"
            + "{\"version\":1,\"type\":\"ENTITY_DEPARTURE\",\"entityId\":\"e3\",\"sequence\":3}\n",
            StandardOpenOption.APPEND);

        var recovery = new EventRecovery(logDir);
        var ex = assertThrows(IOException.class, () -> recovery.recover(nodeId),
            "Non-monotonic WAL sequence must cause recover() to throw via validateRecoveryIntegrity (M1)");
        assertThat(ex.getMessage()).contains("integrity failure");
    }

    /**
     * TDD test 5: clean log recovers without throwing.
     */
    @Test
    void cleanLogRecoverWithoutThrow(@TempDir Path logDir) throws IOException {
        var nodeId = UUID.randomUUID();
        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            wal.append(departureEvent("e1"));
            wal.append(departureEvent("e2"));
            wal.append(departureEvent("e3"));
            wal.flush();
        }

        var recovery = new EventRecovery(logDir);
        var state = recovery.recover(nodeId);

        assertThat(state.skippedCorrupt()).isEqualTo(0);
        assertThat(state.events()).hasSize(3);
    }

    // ---- Bead Luciferase-7wzml.209: validateRecoveryIntegrity reads "sequence" key ----
    // Pre-fix: the method read "sequenceNumber" but WAL writes "sequence"; all sequence-based
    // checks were dead (seqRaw always null) so non-monotonic/overlapping streams silently passed.

    @Test
    void integrityFailsOnNonMonotonicSequenceEndToEnd(@TempDir Path logDir) throws IOException {
        // Write two events, then manually inject a third with a lower sequence by replaying
        // events with wrong ordering into validateRecoveryIntegrity.
        var recovery = new EventRecovery(logDir);
        var checkpoint = new CheckpointMetadata(0, Instant.now());
        // seq 5, then 4 — non-monotonic, must be detected.
        var events = java.util.List.<java.util.Map<String, Object>>of(
            seqEvent("a", 5), seqEvent("b", 4));
        var state = new RecoveredState(checkpoint, events, 5, 0);
        assertThat(recovery.validateRecoveryIntegrity(state))
            .as("Non-monotonic sequence (5 then 4) must fail — pre-fix this passed vacuously "
                + "because 'sequenceNumber' key was checked but WAL uses 'sequence'")
            .isFalse();
    }

    @Test
    void integrityFailsWhenFirstSeqAtOrBelowCheckpointEndToEnd(@TempDir Path logDir) throws IOException {
        // checkpoint seq=10, first replayed seq=10 — must fail (not strictly greater).
        var recovery = new EventRecovery(logDir);
        var checkpoint = new CheckpointMetadata(10, Instant.now());
        var events = java.util.List.<java.util.Map<String, Object>>of(
            seqEvent("a", 10), seqEvent("b", 11));
        var state = new RecoveredState(checkpoint, events, 11, 0);
        assertThat(recovery.validateRecoveryIntegrity(state))
            .as("First replayed seq == checkpoint seq must fail — replay must start strictly after checkpoint")
            .isFalse();
    }

    @Test
    void integrityPassesForValidMonotonicStreamViaWAL(@TempDir Path logDir) throws IOException {
        // End-to-end: write events via WAL, recover, validate integrity.
        // This is the falsifiable positive case: a correct stream must pass.
        var nodeId = UUID.randomUUID();
        try (var wal = new WriteAheadLog(nodeId, logDir)) {
            for (int i = 0; i < 5; i++) {
                wal.append(departureEvent("e-" + i));
            }
            wal.flush();
        }

        var recovery = new EventRecovery(logDir);
        var state = recovery.recover(nodeId);

        assertThat(recovery.validateRecoveryIntegrity(state))
            .as("A cleanly written and recovered WAL with monotonic sequences must pass integrity")
            .isTrue();
        // Confirm sequences were actually found (not silently skipped due to wrong key)
        assertThat(state.events())
            .allSatisfy(e -> assertThat(e).containsKey("sequence"))
            .as("Each WAL event must carry the 'sequence' key (not 'sequenceNumber')");
    }
}

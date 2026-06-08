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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hellblazer.luciferase.common.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * EventRecovery - Log replay mechanism for crash recovery (Phase 7G Day 2)
 *
 * Recovers state from write-ahead log by replaying events in order.
 * Handles corrupted events, duplicate migrations, and validates recovery integrity.
 *
 * RECOVERY PROCESS:
 * 1. Find last complete checkpoint
 * 2. Load checkpoint metadata
 * 3. Read all events from log files
 * 4. Replay events (skip duplicates/malformed)
 * 5. Verify integrity
 *
 * IDEMPOTENCY:
 * Recovery can be replayed multiple times without side effects.
 * Duplicate migration events are detected and skipped.
 *
 * @author hal.hildebrand
 */
public class EventRecovery {

    private static final Logger log = LoggerFactory.getLogger(EventRecovery.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path logDirectory;
    private final RecoveryStateSink stateSink;

    private volatile Clock clock = Clock.system();

    /**
     * Create EventRecovery for log directory with a no-op state sink.
     * Equivalent to {@code new EventRecovery(logDirectory, RecoveryStateSink.NOOP)}.
     *
     * @param logDirectory Directory containing log files
     */
    public EventRecovery(Path logDirectory) {
        this(logDirectory, RecoveryStateSink.NOOP);
    }

    /**
     * Create EventRecovery for log directory with an injected state sink.
     *
     * <p>The {@code stateSink} receives every replayed event so that real state
     * (migration FSM, entity positions) can be reconstructed on crash recovery.
     * Pass {@link RecoveryStateSink#NOOP} to retain the previous log-only behaviour.
     *
     * @param logDirectory Directory containing log files
     * @param stateSink    sink that receives replayed events (must not be null)
     */
    public EventRecovery(Path logDirectory, RecoveryStateSink stateSink) {
        this.logDirectory = Objects.requireNonNull(logDirectory, "logDirectory must not be null");
        this.stateSink = Objects.requireNonNull(stateSink, "stateSink must not be null");
    }

    /**
     * Inject a clock for fallback checkpoint timestamps. Production uses {@link Clock#system()};
     * tests inject a deterministic clock to correlate recovery metadata with simulated time.
     *
     * @param clock the clock to use (must not be null)
     */
    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Recover state for specific bubble/node.
     *
     * @param nodeId Node UUID to recover
     * @return Recovered state with events and metadata
     * @throws IOException if recovery fails
     */
    public RecoveredState recover(UUID nodeId) throws IOException {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        // Load last checkpoint (retained for reporting in RecoveredState only — NOT used to bound replay).
        var checkpoint = getLastCheckpoint(nodeId);

        // RDR-019 (Luciferase-punhr, gate O1): FULL replay of the retained log. The checkpoint NO LONGER
        // bounds replay.
        //
        // History: Luciferase-0frcy.37 made recovery replay only events AFTER the last checkpoint, to
        // avoid re-replaying a long history. But with no durable snapshot behind the checkpoint, that
        // bound silently DROPPED durably-logged in-flight migrations: a periodic checkpoint at the live
        // sequence meant recover() skipped events at/below it, reconstructing null instead of
        // MIGRATING_OUT (the RDR-019 data-loss regression, driving bug Luciferase-n6jrh.3).
        //
        // RDR-019 removes the periodic checkpoint (PersistenceManager): the ONLY checkpoint is
        // closeClean()'s, which is structurally always followed by WriteAheadLog.truncate(). So after a
        // clean shutdown the log segments are gone and a full replay reads nothing; after a crash the
        // full log is retained and a full replay reconstructs every in-flight migration. Either way,
        // full replay is correct — the checkpoint is "truncation-backed" by construction, so there is
        // never a need to bound. Bounded-WAL size is handled by Phase-2 compaction, not by skipping
        // durably-logged events on replay.
        //
        // Luciferase-sc6pl: use a READ-ONLY reader, never a full WriteAheadLog. Constructing a
        // WriteAheadLog here opens the same JSONL log file with a second writable append-mode
        // FileOutputStream while the owning WAL is still being written by the batch-flush scheduler,
        // interleaving partial JSON lines and corrupting the log. WalLogReader opens files for
        // reading only.
        var reader = new WalLogReader(nodeId, logDirectory);
        WalReadResult readResult = reader.readAllEventsResult();
        List<Map<String, Object>> allEvents = readResult.events();
        int skippedCorrupt = readResult.skippedCorrupt();
        if (skippedCorrupt > 0) {
            // C1 — RDR-004-class silent-data-loss: detect-but-proceed is NOT acceptable.
            // Proceeding with partial state as if it were complete can replay a corrupted view,
            // causing split-brain, ghost entities, or lost migrations.  Fail loud so an operator
            // can inspect and explicitly intervene before the node re-joins the cluster.
            // (Luciferase-7wzml.209)
            throw new IOException(
                "WAL recovery for node " + nodeId + ": " + skippedCorrupt
                + " mid-file corrupt line(s) detected — manual inspection required before "
                + "recovery can proceed. Inspect the log files in " + logDirectory);
        }

        // Replay events with validation
        var validEvents = new ArrayList<Map<String, Object>>();
        var skippedCount = 0;
        var seenMigrations = new HashSet<String>();

        for (var event : allEvents) {
            if (isValidEvent(event)) {
                // Check for duplicate migrations
                var migrationKey = extractMigrationKey(event);
                if (migrationKey != null && seenMigrations.contains(migrationKey)) {
                    log.debug("Skipping duplicate migration: {}", migrationKey);
                    skippedCount++;
                    continue;
                }

                validEvents.add(event);
                if (migrationKey != null) {
                    seenMigrations.add(migrationKey);
                }
            } else {
                log.warn("Skipping invalid event: {}", event);
                skippedCount++;
            }
        }

        // Replay valid events — capture per-event exception-skip count (M3)
        skippedCount += replayEvents(validEvents);

        // RDR-019 (Luciferase-punhr): replay is now FULL (no checkpoint bound), so every replayed
        // event is counted directly. The previous "checkpointedPrefix + tail" arithmetic existed only
        // because recovery skipped the checkpointed prefix; with full replay there is no skipped prefix
        // to add back. Kept as a long for headroom on long-lived logs.
        long totalReplayed = (long) validEvents.size();

        var recovered = new RecoveredState(checkpoint, validEvents, totalReplayed, skippedCount, skippedCorrupt);

        // M1 — wire the integrity gate: a non-monotonic replayed sequence indicates a corrupt or
        // double-opened WAL. Fail loud; same rationale as C1 (Luciferase-7wzml.209).
        //
        // RDR-019: replay is full, so the "first replayed seq must be > checkpoint seq" overlap check
        // (meaningful only for bounded/post-checkpoint replay) no longer applies — replaying from seq 1
        // past a checkpoint is now CORRECT, not corruption. Validate with a zero checkpoint so only the
        // monotonicity invariant is enforced here; the returned RecoveredState still carries the real
        // checkpoint metadata for reporting. The checkpoint-overlap branch of
        // validateRecoveryIntegrity(RecoveredState) remains exercised by its direct unit tests.
        var monotonicityState = new RecoveredState(
            CheckpointMetadata.now(0, clock), validEvents, totalReplayed, skippedCount, skippedCorrupt);
        if (!validateRecoveryIntegrity(monotonicityState)) {
            throw new IOException(
                "WAL recovery integrity failure for node " + nodeId
                + ": non-monotonic sequence detected — "
                + "manual inspection required before recovery can proceed");
        }

        return recovered;
    }

    /**
     * Replay events to restore state.
     *
     * <p>Per-event errors are isolated: a malformed or otherwise unprocessable WAL entry logs a
     * warning and increments the returned skip count rather than aborting the entire recovery
     * run (M3 — Luciferase-7wzml.5).
     *
     * @param events Events to replay
     * @return number of events skipped due to per-event dispatch exceptions
     */
    public int replayEvents(List<Map<String, Object>> events) {
        var exceptionSkipped = 0;
        for (var event : events) {
            try {
                var type = (String) event.get("type");

                switch (type) {
                    case "ENTITY_DEPARTURE" -> replayEntityDeparture(event);
                    case "VIEW_SYNC_ACK" -> replayViewSynchronyAck(event);
                    case "DEFERRED_UPDATE" -> replayDeferredUpdate(event);
                    case "MIGRATION_COMMIT" -> replayMigrationCommit(event);
                    // RDR-017 P3 (§Approach.5a): the four consensus event types are WRITTEN by
                    // PersistenceManager but no FSM consumes them on recovery — consensus-event RECOVERY
                    // is explicitly out of scope (not a data-loss risk; no state depends on it). These
                    // are recognized as deliberate no-ops so a WAL containing them does not emit a
                    // spurious "Unknown event type" WARN on every restart. Productionizing a consensus
                    // recovery sink would replace these no-ops.
                    case "ELECTION_START", "VOTE_CAST", "LEADER_ELECTED", "TERM_INCREMENT" -> {
                        // no-op: consensus event recovery deliberately not implemented (RDR-017 §5a)
                    }
                    default -> log.warn("Unknown event type: {}", type);
                }
            } catch (Exception e) {
                log.warn("Skipping malformed WAL entry during recovery: {}", event, e);
                exceptionSkipped++;
            }
        }
        return exceptionSkipped;
    }

    /**
     * Validate recovery integrity (no-arg legacy shim).
     * <p>
     * Retained for backward compatibility. Without a {@link RecoveredState} there is no
     * material to validate, so this trivially returns {@code true}. Callers that want a
     * real integrity gate must use {@link #validateRecoveryIntegrity(RecoveredState)}.
     *
     * @return always {@code true}
     * @deprecated use {@link #validateRecoveryIntegrity(RecoveredState)} which performs
     *             real sequence-ordering and checkpoint-consistency checks.
     */
    @Deprecated
    public boolean validateRecoveryIntegrity() {
        return true;
    }

    /**
     * Validate the integrity of a recovered state.
     * <p>
     * Performs real, falsifiable checks against the replayed log so that corrupt or
     * out-of-order recovery is detected rather than silently accepted
     * (Luciferase-5yh9h — replaces the vacuous {@code return true} gate):
     * <ul>
     *   <li>every replayed event carries a non-null {@code type};</li>
     *   <li>any present {@code sequenceNumber} fields are strictly monotonically
     *       increasing across the replayed tail (a regression / reordering indicates
     *       a corrupt or double-opened WAL);</li>
     *   <li>the first replayed sequence number, when present, is strictly greater than
     *       the checkpoint sequence (recovery must not re-replay checkpointed events).</li>
     * </ul>
     *
     * @param state the recovered state to validate (must not be null)
     * @return {@code true} if the recovered state passes all integrity checks
     */
    public boolean validateRecoveryIntegrity(RecoveredState state) {
        java.util.Objects.requireNonNull(state, "state must not be null");

        var checkpointSeq = state.checkpoint() != null ? state.checkpoint().sequenceNumber() : -1L;
        Long previousSeq = null;
        boolean first = true;

        for (var event : state.events()) {
            if (event == null || event.get("type") == null) {
                log.warn("Recovery integrity failure: event missing type: {}", event);
                return false;
            }
            var seqRaw = event.get("sequence");
            if (seqRaw instanceof Number num) {
                long seq = num.longValue();
                if (first && checkpointSeq >= 0 && seq <= checkpointSeq) {
                    log.warn("Recovery integrity failure: first replayed seq {} <= checkpoint seq {}",
                             seq, checkpointSeq);
                    return false;
                }
                if (previousSeq != null && seq <= previousSeq) {
                    log.warn("Recovery integrity failure: non-monotonic sequence {} after {}",
                             seq, previousSeq);
                    return false;
                }
                previousSeq = seq;
            }
            first = false;
        }
        return true;
    }

    /**
     * Get last checkpoint metadata.
     *
     * @return Checkpoint metadata or default if not found
     */
    public CheckpointMetadata getLastCheckpoint() {
        try {
            return getLastCheckpoint(null);
        } catch (IOException e) {
            return CheckpointMetadata.now(0, clock);
        }
    }

    // ========== Private Helper Methods ==========

    private CheckpointMetadata getLastCheckpoint(UUID nodeId) throws IOException {
        var metadataFile = nodeId != null
            ? logDirectory.resolve("node-" + nodeId + ".meta")
            : logDirectory.resolve("checkpoint.meta");

        if (!Files.exists(metadataFile)) {
            log.debug("No checkpoint metadata found, using default");
            return CheckpointMetadata.now(0, clock);
        }

        try {
            var json = Files.readString(metadataFile);
            @SuppressWarnings("unchecked")
            var metadata = MAPPER.readValue(json, Map.class);

            var seqNum = ((Number) metadata.get("sequenceNumber")).longValue();
            var timestamp = Instant.parse((String) metadata.get("timestamp"));

            return new CheckpointMetadata(seqNum, timestamp);
        } catch (Exception e) {
            log.warn("Failed to parse checkpoint metadata: {}", e.getMessage());
            return CheckpointMetadata.now(0, clock);
        }
    }

    private boolean isValidEvent(Map<String, Object> event) {
        // Check required fields
        if (event == null || event.isEmpty()) {
            return false;
        }

        // Must have version, type, and timestamp
        if (!event.containsKey("version") || !event.containsKey("type")) {
            return false;
        }

        // Type-specific validation
        var type = (String) event.get("type");
        return switch (type) {
            case "ENTITY_DEPARTURE", "VIEW_SYNC_ACK", "MIGRATION_COMMIT" ->
                event.containsKey("entityId");
            case "DEFERRED_UPDATE" ->
                event.containsKey("entityId") && event.containsKey("position");
            default -> true; // Unknown types are valid but logged
        };
    }

    private String extractMigrationKey(Map<String, Object> event) {
        var type = (String) event.get("type");
        if ("ENTITY_DEPARTURE".equals(type) || "MIGRATION_COMMIT".equals(type)) {
            var entityId = event.get("entityId");
            return entityId != null ? type + ":" + entityId : null;
        }
        return null;
    }

    private void replayEntityDeparture(Map<String, Object> event) {
        var entityId = (String) event.get("entityId");
        var sourceBubble = (String) event.get("sourceBubble");
        var targetBubble = (String) event.get("targetBubble");

        log.debug("Replaying ENTITY_DEPARTURE: entity={}, source={}, target={}",
                 entityId, sourceBubble, targetBubble);
        stateSink.onEntityDeparture(entityId, sourceBubble, targetBubble);
    }

    private void replayViewSynchronyAck(Map<String, Object> event) {
        var entityId = (String) event.get("entityId");
        var successRaw = event.get("success");
        boolean success = successRaw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(successRaw));

        log.debug("Replaying VIEW_SYNC_ACK: entity={}, success={}", entityId, success);
        stateSink.onViewSynchronyAck(entityId, success);
    }

    private void replayDeferredUpdate(Map<String, Object> event) {
        var entityId = (String) event.get("entityId");
        var posRaw = event.get("position");
        var velRaw = event.get("velocity");

        log.debug("Replaying DEFERRED_UPDATE: entity={}, pos={}, vel={}",
                 entityId, posRaw, velRaw);
        stateSink.onDeferredUpdate(entityId, toFloatArray(posRaw), toFloatArray(velRaw));
    }

    private void replayMigrationCommit(Map<String, Object> event) {
        var entityId = (String) event.get("entityId");

        log.debug("Replaying MIGRATION_COMMIT: entity={}", entityId);
        stateSink.onMigrationCommit(entityId);
    }

    /**
     * Convert a WAL-encoded position/velocity value to a float array.
     * The WAL stores float arrays as JSON arrays, which Jackson deserialises as
     * {@code List<Number>} (or occasionally {@code double[]}).
     *
     * @param raw the raw value from the event map, may be null
     * @return float[3] or null if absent/unparseable
     */
    @SuppressWarnings("unchecked")
    private static float[] toFloatArray(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            if (raw instanceof List<?> list) {
                var arr = new float[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    arr[i] = ((Number) list.get(i)).floatValue();
                }
                return arr;
            }
            if (raw instanceof double[] da) {
                var arr = new float[da.length];
                for (int i = 0; i < da.length; i++) {
                    arr[i] = (float) da[i];
                }
                return arr;
            }
            if (raw instanceof float[] fa) {
                return fa.clone();
            }
        } catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
            log.warn("Could not parse float array from WAL value: {}", raw, e);
        }
        return null;
    }
}

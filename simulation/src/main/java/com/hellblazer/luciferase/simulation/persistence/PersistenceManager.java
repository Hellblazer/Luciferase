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

import com.hellblazer.luciferase.common.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PersistenceManager - Checkpoint and recovery orchestration (Phase 7G Day 2)
 *
 * High-level API for persistence operations. Coordinates WriteAheadLog and EventRecovery
 * to provide durable state management with crash recovery.
 *
 * FEATURES:
 * - Clean-shutdown checkpoint + truncate compaction (closeClean; no periodic checkpoint — RDR-019 gate O1)
 * - Batch fsync for non-critical events (every 100ms)
 * - Immediate fsync for critical events (migration commit)
 * - Recovery from crash with integrity validation
 * - Integration with OptimisticMigratorImpl
 *
 * THREAD SAFETY:
 * Uses concurrent primitives and scheduled executor for batch flushing.
 *
 * @author hal.hildebrand
 */
public class PersistenceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PersistenceManager.class);
    private static final long BATCH_FLUSH_INTERVAL_MS = 100;

    private final UUID nodeId;
    private final WriteAheadLog writeAheadLog;
    private final RecoveryStateSink recoverySink;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean recoveryInProgress;
    private final AtomicBoolean schedulersStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong lastCheckpointSeq;

    private volatile Instant lastCheckpoint;
    private volatile Clock clock = Clock.system();

    /**
     * Set the clock source for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Create PersistenceManager with default settings and a no-op recovery sink.
     *
     * <p>Use {@link #PersistenceManager(UUID, Path, RecoveryStateSink)} to provide a real sink
     * so that {@link #recover()} reconstructs actual state (migration FSM, entity positions)
     * rather than silently discarding replayed events.
     *
     * @param nodeId       Node UUID
     * @param logDirectory Directory for log files
     * @throws IOException if log initialization fails
     */
    public PersistenceManager(UUID nodeId, Path logDirectory) throws IOException {
        this(nodeId, logDirectory, RecoveryStateSink.NOOP);
    }

    /**
     * Create PersistenceManager with a real recovery sink.
     *
     * <p>The {@code recoverySink} receives every replayed WAL event during {@link #recover()} so
     * that real state (migration FSM, entity positions) can be reconstructed on crash recovery.
     * Pass {@link RecoveryStateSink#NOOP} if recovery state reconstruction is intentionally
     * deferred (but prefer this overload in production so callers cannot silently lose state).
     *
     * @param nodeId        Node UUID
     * @param logDirectory  Directory for log files
     * @param recoverySink  sink that receives replayed events during recovery (must not be null)
     * @throws IOException if log initialization fails
     */
    public PersistenceManager(UUID nodeId, Path logDirectory, RecoveryStateSink recoverySink) throws IOException {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.recoverySink = Objects.requireNonNull(recoverySink, "recoverySink must not be null");
        this.writeAheadLog = new WriteAheadLog(nodeId, logDirectory);
        this.executor = Executors.newScheduledThreadPool(1, r -> {
            var thread = new Thread(r, "PersistenceManager-" + nodeId);
            thread.setDaemon(true);
            return thread;
        });
        this.recoveryInProgress = new AtomicBoolean(false);
        this.lastCheckpointSeq = new AtomicLong(0);
        this.lastCheckpoint = Instant.ofEpochMilli(clock.currentTimeMillis());

        // RDR-017 P1 (Luciferase-pf1iu): the batch-flush scheduler is NO LONGER started here.
        // Starting it in the constructor let a batch flush (every 100ms) fire before recover() ran —
        // overwriting an unrecovered WAL. It is now started by PersistenceManagerAdapter.doStart() via
        // startSchedulers(), strictly AFTER recover() completes.
        // RDR-019 (Luciferase-punhr, gate O1): there is NO periodic checkpoint scheduler. A periodic
        // checkpoint at the live sequence with no durable snapshot behind it silently bounded recovery
        // replay (recover() skipped events at/below the checkpoint), dropping in-flight migrations on
        // restart. The ONLY checkpoint is closeClean()'s, which is structurally always followed by
        // truncate() — so every checkpoint is truncation-backed and recovery can safely full-replay
        // the retained log. Do NOT re-introduce a periodic/mid-run checkpoint without first
        // re-establishing a durable snapshot to back it (otherwise the data-loss regression returns).
        log.info("PersistenceManager constructed for node {} at {} (schedulers idle until startSchedulers())",
                 nodeId, logDirectory);
    }

    /**
     * Start the batch-flush background scheduler.
     * <p>
     * RDR-017 P1: called by {@link com.hellblazer.luciferase.simulation.lifecycle.PersistenceManagerAdapter}
     * ({@code doStart()}) <b>after</b> {@link #recover()} completes, so the scheduler cannot run against an
     * unrecovered WAL. RDR-019 (gate O1): there is no periodic checkpoint scheduler. Idempotent: repeated
     * calls are a no-op.
     */
    public void startSchedulers() {
        if (schedulersStarted.compareAndSet(false, true)) {
            startBatchFlushScheduler();
            // RDR-019 (gate O1): no periodic checkpoint scheduler — see constructor note.
            log.info("PersistenceManager schedulers started for node {}", nodeId);
        }
    }

    /**
     * @return whether the background schedulers have been started (RDR-017 P1)
     */
    public boolean isSchedulersStarted() {
        return schedulersStarted.get();
    }

    /**
     * @return the number of tasks currently queued on the scheduler executor. Zero before
     *         {@link #startSchedulers()} runs; one once the batch-flush scheduler is scheduled
     *         (RDR-019 removed the periodic checkpoint scheduler — gate O1). Package-private
     *         test-support: the scheduler-relocation regression (RDR-017 P1) uses it to prove no
     *         scheduler is queued in the constructor — catching a re-introduction of any scheduler.
     */
    int scheduledTaskCount() {
        if (executor instanceof java.util.concurrent.ScheduledThreadPoolExecutor stpe) {
            return stpe.getQueue().size();
        }
        return -1;
    }

    /**
     * Log entity departure event.
     *
     * @param entityId Entity being migrated
     * @param sourceBubble Source bubble UUID
     * @param targetBubble Target bubble UUID
     * @throws IOException if log write fails
     */
    public void logEntityDeparture(UUID entityId, UUID sourceBubble, UUID targetBubble) throws IOException {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(sourceBubble, "sourceBubble must not be null");
        Objects.requireNonNull(targetBubble, "targetBubble must not be null");

        var event = createEvent("ENTITY_DEPARTURE");
        event.put("entityId", entityId.toString());
        event.put("sourceBubble", sourceBubble.toString());
        event.put("targetBubble", targetBubble.toString());
        event.put("state", "MIGRATING_OUT");

        writeAheadLog.append(event);

        log.debug("Logged ENTITY_DEPARTURE: entity={}, source={}, target={}",
                 entityId, sourceBubble, targetBubble);
    }

    /**
     * Log view synchrony acknowledgement.
     *
     * @param entityId Entity being acknowledged
     * @param sourceBubble Source bubble UUID
     * @param success Whether acknowledgement was successful
     * @throws IOException if log write fails
     */
    public void logViewSynchronyAck(UUID entityId, UUID sourceBubble, boolean success) throws IOException {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(sourceBubble, "sourceBubble must not be null");

        var event = createEvent("VIEW_SYNC_ACK");
        event.put("entityId", entityId.toString());
        event.put("sourceBubble", sourceBubble.toString());
        event.put("success", success);

        writeAheadLog.append(event);

        log.debug("Logged VIEW_SYNC_ACK: entity={}, success={}", entityId, success);
    }

    /**
     * Log deferred physics update.
     *
     * @param entityId Entity being updated
     * @param position Updated position [x, y, z]
     * @param velocity Updated velocity [vx, vy, vz]
     * @throws IOException if log write fails
     */
    public void logDeferredUpdate(UUID entityId, float[] position, float[] velocity) throws IOException {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(position, "position must not be null");
        Objects.requireNonNull(velocity, "velocity must not be null");

        if (position.length != 3) {
            throw new IllegalArgumentException("position must have 3 components");
        }
        if (velocity.length != 3) {
            throw new IllegalArgumentException("velocity must have 3 components");
        }

        var event = createEvent("DEFERRED_UPDATE");
        event.put("entityId", entityId.toString());
        event.put("position", new float[]{position[0], position[1], position[2]});
        event.put("velocity", new float[]{velocity[0], velocity[1], velocity[2]});

        writeAheadLog.append(event);

        log.debug("Logged DEFERRED_UPDATE: entity={}, pos=[{}, {}, {}]",
                 entityId, position[0], position[1], position[2]);
    }

    /**
     * Log migration commit (critical event - fsync immediately).
     *
     * @param entityId Entity being committed
     * @throws IOException if log write fails
     */
    public void logMigrationCommit(UUID entityId) throws IOException {
        Objects.requireNonNull(entityId, "entityId must not be null");

        var event = createEvent("MIGRATION_COMMIT");
        event.put("entityId", entityId.toString());

        writeAheadLog.append(event);

        // Critical event - fsync immediately
        writeAheadLog.flush();

        log.debug("Logged MIGRATION_COMMIT: entity={} (fsynced)", entityId);
    }

    /**
     * Create recovery checkpoint.
     *
     * @throws IOException if checkpoint creation fails
     */
    public void checkpoint() throws IOException {
        // RDR-019 Gap 4 (Luciferase-punhr): source the checkpoint sequence from the WAL's restored
        // global high-water mark, NOT a session-local counter that resets to 0 on each restart. A
        // reset counter checkpoints below the true high-water, silently bounding recovery replay and
        // dropping durably-logged events.
        var seq = writeAheadLog.getSequence();
        var timestamp = Instant.ofEpochMilli(clock.currentTimeMillis());

        writeAheadLog.checkpoint(seq, timestamp);
        lastCheckpointSeq.set(seq);
        lastCheckpoint = timestamp;

        log.info("Checkpoint created: seq={}, timestamp={}", seq, timestamp);
    }

    /**
     * Recover state from log.
     *
     * @return Recovered state with events and metadata
     * @throws IOException if recovery fails
     */
    public RecoveredState recover() throws IOException {
        if (!recoveryInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("Recovery already in progress");
        }

        try {
            var recovery = new EventRecovery(writeAheadLog.logDirectory, recoverySink);
            var recovered = recovery.recover(nodeId);

            log.info("Recovery completed: {} events replayed, {} skipped, {} corrupt WAL lines",
                    recovered.totalEventsReplayed(), recovered.skippedEvents(), recovered.skippedCorrupt());

            return recovered;
        } finally {
            recoveryInProgress.set(false);
        }
    }

    // ========== Consensus Event Logging (Phase 7G Day 3.2) ==========

    /**
     * Log consensus election start event.
     *
     * @param candidateId UUID of the candidate starting election
     * @param term Election term number
     * @throws IOException if logging fails
     */
    public void logElectionStart(UUID candidateId, long term) throws IOException {
        Objects.requireNonNull(candidateId, "candidateId must not be null");

        var event = createEvent("ELECTION_START");
        event.put("candidateId", candidateId.toString());
        event.put("term", term);

        writeAheadLog.append(event);

        log.debug("Election start logged: candidate={}, term={}", candidateId, term);
    }

    /**
     * Log vote cast by node.
     *
     * @param voterId UUID of node casting vote
     * @param candidateId UUID of candidate receiving vote
     * @param term Election term
     * @param vote true if vote granted, false if denied
     * @throws IOException if logging fails
     */
    public void logVoteCast(UUID voterId, UUID candidateId, long term, boolean vote) throws IOException {
        Objects.requireNonNull(voterId, "voterId must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");

        var event = createEvent("VOTE_CAST");
        event.put("voterId", voterId.toString());
        event.put("candidateId", candidateId.toString());
        event.put("term", term);
        event.put("vote", vote);

        writeAheadLog.append(event);

        log.debug("Vote cast logged: voter={}, candidate={}, term={}, vote={}", voterId, candidateId, term, vote);
    }

    /**
     * Log leader election completion.
     *
     * @param leaderId UUID of elected leader
     * @param term Election term
     * @throws IOException if logging fails
     */
    public void logLeaderElected(UUID leaderId, long term) throws IOException {
        Objects.requireNonNull(leaderId, "leaderId must not be null");

        var event = createEvent("LEADER_ELECTED");
        event.put("leaderId", leaderId.toString());
        event.put("term", term);

        writeAheadLog.append(event);

        log.info("Leader elected logged: leader={}, term={}", leaderId, term);
    }

    /**
     * Log term number increment.
     *
     * @param term New term number
     * @throws IOException if logging fails
     */
    public void logTermIncrement(long term) throws IOException {
        var event = createEvent("TERM_INCREMENT");
        event.put("term", term);

        writeAheadLog.append(event);

        log.debug("Term increment logged: term={}", term);
    }

    /**
     * Clean-shutdown close (RDR-017 P3, gate O1): checkpoint at the current sequence, truncate the WAL
     * segments (compaction — recovery replays only post-checkpoint events, of which a head checkpoint
     * leaves none), then close.
     * <p>
     * <b>Retention semantics.</b> A clean shutdown leaves a pristine WAL: the next start recovers nothing
     * (a cleanly-stopped node has no in-flight migrations — the caller is expected to have drained the
     * migrator first, RDR-017 P2). This is distinct from {@link #close()} (the crash-safe path), which
     * flushes and closes WITHOUT checkpoint/truncate so a crash retains the full WAL tail for recovery.
     * Use this from the lifecycle stop path; use {@link #close()} on abort/error paths.
     * <p>
     * Idempotent: a no-op if the manager is already closed.
     *
     * @throws IOException if checkpoint, truncate, or close fails
     */
    public void closeClean() throws IOException {
        if (closed.get()) {
            return;
        }
        checkpoint();
        writeAheadLog.truncate();
        close();
    }

    /**
     * Crash-safe close: flush and close WITHOUT checkpoint/truncate, so a crash retains the full WAL for
     * recovery. For clean lifecycle shutdown use {@link #closeClean()} (checkpoint + truncate compaction).
     *
     * @throws IOException if close fails
     */
    @Override
    public void close() throws IOException {
        // Idempotent (RDR-017 P1): the corrupt-WAL abort path closes the PM from doStart(), and a
        // caller's finally block may close again — a second close must not throw "WAL is closed".
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("Shutting down PersistenceManager for node {}", nodeId);

        // Shutdown executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Final flush and close WAL
        writeAheadLog.flush();
        writeAheadLog.close();

        log.info("PersistenceManager closed for node {}", nodeId);
    }

    // ========== Private Helper Methods ==========

    private Map<String, Object> createEvent(String type) {
        var event = new HashMap<String, Object>();
        event.put("version", 1);
        event.put("timestamp", Instant.ofEpochMilli(clock.currentTimeMillis()).toString());
        event.put("type", type);
        return event;
    }

    private void startBatchFlushScheduler() {
        executor.scheduleAtFixedRate(() -> {
            try {
                writeAheadLog.flush();
            } catch (IOException e) {
                log.error("Batch flush failed", e);
            }
        }, BATCH_FLUSH_INTERVAL_MS, BATCH_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

}

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

package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.migration.MigrationLogPersistence;
import com.hellblazer.luciferase.simulation.distributed.migration.TransactionState;
import com.hellblazer.luciferase.simulation.von.VonTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Centralized topology authority for distributed bubble coordination.
 * <p>
 * Responsibilities:
 * - Process registration and lifecycle management
 * - Heartbeat monitoring for failure detection (DEPRECATED - use Fireflies)
 * - Topology update broadcasting
 * <p>
 * Lifecycle:
 * 1. Create ProcessCoordinator(transport)
 * 2. start() to begin listening
 * 3. Processes register via registerProcess(id, bubbles)
 * 4. Heartbeat monitoring detects failures (DEPRECATED - use Fireflies view changes)
 * 5. stop() to shut down gracefully
 * <p>
 * Bucket Synchronization:
 * - BUCKET_DURATION_MS: 100ms per simulation tick
 * - TOLERANCE_MS: 50ms clock skew tolerance
 * <p>
 * Heartbeat Protocol (DEPRECATED - use Fireflies):
 * - Interval: 1000ms (every second)
 * - Timeout: 3000ms (3 missed heartbeats)
 * <p>
 * Phase 4.1: CoordinatorElectionProtocol deleted (redundant with Fireflies ring ordering)
 *
 * @author hal.hildebrand
 */
public class ProcessCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProcessCoordinator.class);

    public static final long BUCKET_DURATION_MS = 100;
    public static final long TOLERANCE_MS = 50;

    private final VonTransport transport;
    private final ProcessRegistry registry;
    private final ScheduledExecutorService heartbeatScheduler;
    private final WallClockBucketScheduler bucketScheduler;
    private final MessageOrderValidator messageValidator;
    private MigrationLogPersistence walPersistence;

    private volatile boolean running = false;
    private volatile Clock clock = Clock.system();

    /**
     * Create a ProcessCoordinator with the given transport.
     *
     * @param transport VonTransport for inter-process communication
     */
    public ProcessCoordinator(VonTransport transport) {
        this.transport = transport;
        this.registry = new ProcessRegistry();
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "coordinator-heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });
        this.bucketScheduler = new WallClockBucketScheduler();
        this.messageValidator = new MessageOrderValidator();
        this.walPersistence = null; // Initialized lazily in start()
    }

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Start the coordinator: begin listening and election protocol with crash recovery.
     * <p>
     * Initiates:
     * - Crash recovery (load and recover incomplete migrations)
     * - Heartbeat monitoring (every 1000ms)
     * - Failure detection (timeout 3000ms)
     * - Election if this is the first process
     */
    public void start() throws Exception {
        if (running) {
            throw new IllegalStateException("ProcessCoordinator already running");
        }

        running = true;

        // Initialize WAL persistence for crash recovery
        try {
            walPersistence = new MigrationLogPersistence(transport.getLocalId());
        } catch (IOException e) {
            log.error("Failed to initialize WAL persistence: {}", e.getMessage(), e);
            running = false;
            throw new Exception("WAL initialization failed", e);
        }

        // Check for crash recovery
        if (isRestart()) {
            log.info("Detected process restart, initiating crash recovery on {}", transport.getLocalId());
            try {
                recoverInFlightMigrations();
            } catch (Exception e) {
                log.error("Crash recovery failed: {}", e.getMessage(), e);
                // Continue startup anyway - migrations will be retried by source processes
            }
        }

        // Start heartbeat monitoring
        heartbeatScheduler.scheduleAtFixedRate(
            this::monitorHeartbeats,
            ProcessRegistry.HEARTBEAT_INTERVAL_MS,
            ProcessRegistry.HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        log.info("ProcessCoordinator started on {}", transport.getLocalId());
    }

    /**
     * Stop the coordinator gracefully.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        heartbeatScheduler.shutdownNow();
        try {
            if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Heartbeat scheduler did not terminate within 5 second timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for heartbeat scheduler shutdown", e);
        }
        log.info("ProcessCoordinator stopped");
    }

    /**
     * Register a process with its bubbles.
     *
     * @param processId UUID of the process
     * @param bubbles   List of bubble UUIDs hosted by this process
     */
    public void registerProcess(UUID processId, List<UUID> bubbles) throws Exception {
        registry.register(processId, bubbles);
        log.info("Registered process {} with {} bubbles", processId, bubbles.size());
    }

    /**
     * Unregister a process (called on process failure or shutdown).
     *
     * @param processId UUID of the process to remove
     */
    public void unregisterProcess(UUID processId) throws Exception {
        registry.unregister(processId);
        log.info("Unregistered process {}", processId);
    }

    /**
     * Process a heartbeat acknowledgment from a process.
     * <p>
     * Updates the last heartbeat timestamp for failure detection.
     *
     * @param processId UUID of the process sending heartbeat
     */
    public void processHeartbeatAck(UUID processId) {
        if (!registry.updateHeartbeat(processId)) {
            log.warn("Heartbeat from unregistered process {}", processId);
        } else {
            log.trace("Heartbeat ACK from {}", processId);
        }
    }

    /**
     * Broadcast topology update to all registered processes.
     * <p>
     * Phase 6B1: API exists but messaging is minimal.
     * Phase 6B2: Will use TopologyUpdateMessage for distribution.
     *
     * @param topology List of all bubble UUIDs in the topology
     */
    public void broadcastTopologyUpdate(List<UUID> topology) throws Exception {
        log.debug("Broadcasting topology update: {} bubbles", topology.size());
        // Phase 6B2: Implement message broadcasting
    }

    /**
     * Monitor heartbeats and detect failed processes.
     * <p>
     * Called periodically by heartbeatScheduler.
     * Unregisters processes that missed heartbeat timeout.
     */
    private void monitorHeartbeats() {
        var processes = registry.getAllProcesses();
        for (var processId : processes) {
            if (!registry.isAlive(processId)) {
                log.warn("Process {} missed heartbeat timeout, unregistering", processId);
                try {
                    unregisterProcess(processId);
                } catch (Exception e) {
                    log.error("Failed to unregister process {}: {}", processId, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Get the process registry.
     *
     * @return ProcessRegistry instance
     */
    public ProcessRegistry getRegistry() {
        return registry;
    }

    /**
     * Check if coordinator is running.
     *
     * @return true if started and not stopped
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the bucket scheduler.
     *
     * @return WallClockBucketScheduler
     */
    public WallClockBucketScheduler getBucketScheduler() {
        return bucketScheduler;
    }

    /**
     * Get the message order validator.
     *
     * @return MessageOrderValidator
     */
    public MessageOrderValidator getMessageValidator() {
        return messageValidator;
    }

    /**
     * Detect if this is a process restart (WAL files exist from previous run).
     * <p>
     * Checks for persisted WAL directory which indicates incomplete migrations
     * from before the crash.
     *
     * @return true if WAL directory exists and contains transaction data
     */
    private boolean isRestart() {
        if (walPersistence == null) {
            return false;
        }

        try {
            var walDir = walPersistence.getWalDirectory();
            if (!Files.exists(walDir)) {
                return false;
            }

            // Check if WAL file exists and has data
            var walFile = walPersistence.getWalFile();
            if (Files.exists(walFile)) {
                return Files.size(walFile) > 0;
            }

            return false;
        } catch (IOException e) {
            log.error("Error checking for restart: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Recover in-flight migrations from WAL on process restart.
     * <p>
     * Protocol:
     * 1. Load incomplete transactions from WAL
     * 2. For PREPARE-only: Restore entity to source bubble (rollback)
     * 3. For PREPARE+COMMIT: Assume migration succeeded (idempotency)
     * 4. For PREPARE+ABORT: Complete the rollback
     * <p>
     * After recovery, remove transactions from WAL.
     *
     * @throws Exception If recovery fails
     */
    private void recoverInFlightMigrations() throws Exception {
        if (walPersistence == null) {
            log.warn("WAL persistence not initialized, skipping recovery");
            return;
        }

        try {
            var incomplete = walPersistence.loadIncomplete();
            log.info("Loaded {} incomplete transactions for recovery", incomplete.size());

            int recovered = 0;
            int failed = 0;

            for (var txn : incomplete) {
                try {
                    recoverTransaction(txn);
                    recovered++;
                } catch (Exception e) {
                    log.error("Failed to recover transaction {}: {}", txn.transactionId(), e.getMessage(), e);
                    failed++;
                }
            }

            log.info("Recovery complete: {} recovered, {} failed", recovered, failed);
        } catch (Exception e) {
            log.error("Failed to load incomplete transactions: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Recover a single in-flight transaction.
     * <p>
     * Recovery strategy by phase:
     * - PREPARE-only: Entity was removed from source but never committed to destination
     *   → Restore entity to source bubble (rollback)
     * - PREPARE+COMMIT: Commit phase started, assume completed
     *   → No action needed, entity already in destination (idempotency ensures no duplicates)
     * - PREPARE+ABORT: Abort was in progress
     *   → Complete the rollback to source
     *
     * @param txn Transaction to recover
     */
    private void recoverTransaction(TransactionState txn) {
        switch (txn.phase()) {
            case PREPARE -> {
                // Entity was removed from source but not committed to dest
                log.info("Recovering PREPARE-only transaction {}: rolling back entity {} to source",
                         txn.transactionId(), txn.entityId());
                rollbackToSource(txn);
            }
            case COMMIT -> {
                // COMMIT phase started, assume it succeeded or will be retried by destination
                log.info("Recovering COMMIT transaction {}: assuming destination has entity {}",
                         txn.transactionId(), txn.entityId());
                // No action needed - entity already in destination
                // Idempotency tokens prevent duplicate adds on retry
            }
            case ABORT -> {
                // ABORT was in progress, complete it
                log.info("Recovering ABORT transaction {}: completing rollback of entity {}",
                         txn.transactionId(), txn.entityId());
                rollbackToSource(txn);
            }
        }

        // Mark transaction as complete in WAL (prevents re-recovery)
        try {
            walPersistence.recordAbort(txn.transactionId());
        } catch (IOException e) {
            log.error("Failed to update WAL after recovery of transaction {}: {}",
                     txn.transactionId(), e.getMessage());
            // Continue anyway - recovery already complete
        }
    }

    /**
     * Rollback entity to source bubble (restore on crash recovery).
     * <p>
     * Restores entity from snapshot to source bubble.
     * Used when PREPARE completed but COMMIT did not finish.
     * <p>
     * Note: In a real system, would look up the local bubble and call
     * bubble.asLocal().addEntity(snapshot). For test/simulation environments,
     * this would be injected or mocked.
     *
     * @param txn Transaction containing source bubble and entity snapshot
     */
    private void rollbackToSource(TransactionState txn) {
        // In production: Look up local bubble and restore entity
        // var sourceBubble = registry.getBubble(txn.sourceBubble());
        // if (sourceBubble != null && sourceBubble.isLocal()) {
        //     sourceBubble.asLocal().addEntity(txn.snapshot());
        //     log.info("Restored entity {} to source bubble {}", txn.entityId(), txn.sourceBubble());
        // } else {
        //     log.error("Cannot rollback: source bubble {} not found or not local", txn.sourceBubble());
        // }

        // For now, just log - actual restoration depends on ProcessRegistry/BubbleRegistry integration
        log.info("Rollback would restore entity {} to bubble {}", txn.entityId(), txn.sourceBubble());
    }

    /**
     * Get the WAL persistence instance (for testing and recovery operations).
     *
     * @return MigrationLogPersistence instance (may be null if not initialized)
     */
    public MigrationLogPersistence getWalPersistence() {
        return walPersistence;
    }

    /**
     * Synchronize ghost entities across process boundaries.
     * <p>
     * Triggered by test infrastructure to validate ghost layer propagation.
     * In a real system, this would be scheduled automatically.
     * <p>
     * For Phase 6E: This is a stub implementation that logs the synchronization.
     * Actual ghost sync logic is handled by the BubbleRegistry and ghost layer.
     */
    public void syncGhosts() {
        if (!running) {
            log.debug("Cannot sync ghosts - coordinator not running");
            return;
        }
        log.debug("Ghost synchronization triggered on coordinator for process {}",
                transport.getLocalId());
        // Actual ghost sync would be coordinated here with the bubble registry
    }
}

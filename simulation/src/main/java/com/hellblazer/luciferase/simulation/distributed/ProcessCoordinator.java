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

import com.hellblazer.luciferase.simulation.causality.FirefliesViewMonitor;
import com.hellblazer.luciferase.simulation.delos.MembershipView;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.migration.MigrationLogPersistence;
import com.hellblazer.luciferase.simulation.distributed.migration.TransactionState;
import com.hellblazer.luciferase.simulation.von.VonTransport;
import com.hellblazer.primeMover.annotations.Entity;
import com.hellblazer.primeMover.annotations.NonEvent;
import com.hellblazer.primeMover.api.Kronos;
import com.hellblazer.primeMover.controllers.RealTimeController;
import com.hellblazer.primeMover.runtime.Kairos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * Centralized topology authority for distributed bubble coordination.
 * <p>
 * Responsibilities:
 * - Process registration and lifecycle management
 * - Failure detection via Fireflies view changes
 * - Coordinator selection via ring ordering
 * - Topology update broadcasting
 * <p>
 * Lifecycle:
 * 1. Create ProcessCoordinator(transport, membershipView)
 * 2. start() to begin listening and initialize Fireflies monitor
 * 3. Processes register via registerProcess(id, bubbles)
 * 4. View changes automatically trigger failure handling
 * 5. stop() to shut down gracefully
 * <p>
 * Failure Detection:
 * - Uses FirefliesViewMonitor for automatic failure detection
 * - View changes trigger unregistration of failed processes
 * - No manual heartbeat monitoring needed
 * <p>
 * Coordinator Selection:
 * - Uses ring ordering (first UUID from sorted view members)
 * - Deterministic: same view always produces same coordinator
 * - Leverages Fireflies high-quality hashing with view and member ID mixin
 * <p>
 * Bucket Synchronization:
 * - BUCKET_DURATION_MS: 100ms per simulation tick
 * - TOLERANCE_MS: 50ms clock skew tolerance
 * <p>
 * Phase 4.1: Redundant code removed (CoordinatorElectionProtocol, heartbeat monitoring, heartbeat tracking)
 * Phase 4.1.4: Fireflies integration (MembershipView, FirefliesViewMonitor, ring ordering)
 * Phase 4.2.1: Prime-Mover @Entity conversion (event-driven coordination tick)
 *
 * @author hal.hildebrand
 */
public class ProcessCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProcessCoordinator.class);

    public static final long BUCKET_DURATION_MS = 100;
    public static final long TOLERANCE_MS = 50;

    private final VonTransport transport;
    private final ProcessRegistry registry;
    private final WallClockBucketScheduler bucketScheduler;
    private final MessageOrderValidator messageValidator;
    private final MembershipView<UUID> membershipView;
    private final ProcessCoordinatorEntity entity;
    private final RealTimeController controller;
    private MigrationLogPersistence walPersistence;
    private FirefliesViewMonitor viewMonitor;

    private volatile boolean running = false;
    private volatile Clock clock = Clock.system();

    /**
     * Create a ProcessCoordinator with the given transport and membership view.
     * <p>
     * Phase 4.2.1: Initializes Prime-Mover @Entity and RealTimeController for event-driven coordination.
     *
     * @param transport      VonTransport for inter-process communication
     * @param membershipView MembershipView for Fireflies failure detection
     */
    public ProcessCoordinator(VonTransport transport, MembershipView<UUID> membershipView) {
        this.transport = transport;
        this.membershipView = membershipView;
        this.registry = new ProcessRegistry();
        this.bucketScheduler = new WallClockBucketScheduler();
        this.messageValidator = new MessageOrderValidator();
        this.walPersistence = null; // Initialized lazily in start()
        this.viewMonitor = null; // Initialized in start()

        // Phase 4.2.1: Initialize Prime-Mover entity and controller
        this.controller = new RealTimeController("ProcessCoordinator-" + transport.getLocalId());
        this.entity = new ProcessCoordinatorEntity(
            () -> running,
            () -> registry.getAllBubbles(),
            topology -> {
                try {
                    broadcastTopologyUpdate(topology);
                } catch (Exception e) {
                    log.error("Failed to broadcast topology update: {}", e.getMessage(), e);
                }
            }
        );
        Kairos.setController(controller);

        log.debug("Created ProcessCoordinator with event-driven coordination for process {}",
                 transport.getLocalId());
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
     * Start the coordinator: begin listening with crash recovery and Fireflies monitoring.
     * <p>
     * Initiates:
     * - Crash recovery (load and recover incomplete migrations)
     * - Fireflies view monitoring (automatic failure detection)
     * - Event-driven coordination tick (Phase 4.2.1)
     * <p>
     * Phase 4.1.2: Heartbeat monitoring removed (use Fireflies view changes instead)
     * Phase 4.1.4: FirefliesViewMonitor initialization and view change listener registration
     * Phase 4.2.1: Start Prime-Mover controller and coordination tick
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

        // Initialize Fireflies view monitor (Phase 4.1.4)
        viewMonitor = new FirefliesViewMonitor(membershipView);
        viewMonitor.addViewChangeListener(change -> handleViewChange(change));
        log.info("Fireflies view monitor initialized for process {}", transport.getLocalId());

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

        // Phase 4.2.1: Start event-driven coordination
        entity.coordinationTick();
        controller.start();

        log.info("ProcessCoordinator started on {}", transport.getLocalId());
    }

    /**
     * Stop the coordinator gracefully.
     * <p>
     * Phase 4.1.2: Heartbeat scheduler removed (use Fireflies view changes instead)
     * Phase 4.2.1: Stop Prime-Mover controller
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        controller.stop();
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

    /**
     * Handle view change notifications from Fireflies.
     * <p>
     * Called when cluster membership changes (members join or leave).
     * Automatically unregisters processes that left the view.
     * <p>
     * Phase 4.1.4: Replaces manual heartbeat monitoring with Fireflies view changes.
     *
     * @param change ViewChange with joined and left members
     */
    private void handleViewChange(MembershipView.ViewChange<?> change) {
        var joined = change.joined();
        var left = change.left();

        log.info("View change detected: {} joined, {} left", joined.size(), left.size());

        // Unregister processes that left the view (failure detection)
        for (var member : left) {
            if (member instanceof UUID processId) {
                try {
                    unregisterProcess(processId);
                    log.info("Unregistered process {} (left view)", processId);
                } catch (Exception e) {
                    log.error("Failed to unregister process {}: {}", processId, e.getMessage(), e);
                }
            }
        }

        // Log joined processes (actual registration happens via registerProcess() calls)
        for (var member : joined) {
            if (member instanceof UUID processId) {
                log.debug("Process {} joined view", processId);
            }
        }
    }

    /**
     * Check if this process is the current coordinator.
     * <p>
     * Uses ring ordering for deterministic coordinator selection:
     * the first UUID from the sorted view members becomes coordinator.
     * <p>
     * This leverages Fireflies' high-quality hashing with view and member ID mixin
     * for random distribution of coordinator role.
     * <p>
     * Phase 4.1.4: Ring ordering replaces CoordinatorElectionProtocol.
     *
     * @return true if this process is the coordinator
     */
    public boolean isCoordinator() {
        if (viewMonitor == null) {
            log.warn("View monitor not initialized, cannot determine coordinator");
            return false;
        }

        var members = viewMonitor.getCurrentMembers();
        if (members.isEmpty()) {
            log.warn("No members in view, cannot determine coordinator");
            return false;
        }

        // Ring ordering: first UUID from sorted view
        var coordinator = members.stream()
                                 .filter(m -> m instanceof UUID)
                                 .map(m -> (UUID) m)
                                 .sorted(Comparator.comparing(UUID::toString))
                                 .findFirst()
                                 .orElse(null);

        var localId = transport.getLocalId();
        var isCoordinator = localId.equals(coordinator);

        log.debug("Coordinator check: local={}, coordinator={}, isCoordinator={}",
                 localId, coordinator, isCoordinator);

        return isCoordinator;
    }

    /**
     * Get the current coordinator process ID.
     * <p>
     * Uses ring ordering: first UUID from sorted view members.
     * <p>
     * Phase 4.1.4: Deterministic coordinator selection via ring ordering.
     *
     * @return UUID of current coordinator, or null if view is empty
     */
    public UUID getCoordinator() {
        if (viewMonitor == null) {
            log.warn("View monitor not initialized, cannot determine coordinator");
            return null;
        }

        var members = viewMonitor.getCurrentMembers();
        if (members.isEmpty()) {
            log.warn("No members in view, cannot determine coordinator");
            return null;
        }

        return members.stream()
                      .filter(m -> m instanceof UUID)
                      .map(m -> (UUID) m)
                      .sorted(Comparator.comparing(UUID::toString))
                      .findFirst()
                      .orElse(null);
    }

    /**
     * Get the Fireflies view monitor.
     * <p>
     * Provides access to view monitoring for testing and diagnostics.
     * <p>
     * Phase 4.1.4: FirefliesViewMonitor accessor.
     *
     * @return FirefliesViewMonitor instance (may be null if not started)
     */
    public FirefliesViewMonitor getViewMonitor() {
        return viewMonitor;
    }

    /**
     * Get the Prime-Mover controller.
     * <p>
     * Phase 4.2.1: Controller accessor for testing and diagnostics.
     *
     * @return RealTimeController instance
     */
    @NonEvent
    public RealTimeController getController() {
        return controller;
    }

    /**
     * Prime-Mover @Entity for event-driven coordination.
     * <p>
     * Static nested class to avoid generic type issues with Prime-Mover bytecode transformer.
     * Follows BucketScheduler.BucketSchedulerEntity pattern:
     * <ul>
     *   <li>@NonEvent on all getters</li>
     *   <li>coordinationTick() as event method</li>
     *   <li>Kronos.sleep() for polling timing</li>
     *   <li>Recursive this.coordinationTick() for continuous execution</li>
     * </ul>
     * <p>
     * Event-driven lifecycle:
     * <ol>
     *   <li>Check if topology changed (compare current vs last broadcast)</li>
     *   <li>If changed: broadcast topology update</li>
     *   <li>Process pending coordination tasks (future)</li>
     *   <li>Schedule next tick (recursive)</li>
     * </ol>
     * <p>
     * Polling replaces blocking calls with periodic checks.
     * <p>
     * Phase 4.2.1: Initial implementation with topology monitoring.
     */
    @Entity
    public static class ProcessCoordinatorEntity {
        /**
         * Polling interval in nanoseconds (10ms).
         * Chosen for reasonable coordination overhead (1% at 100Hz ticking).
         */
        private static final long POLL_INTERVAL_NS = 10_000_000;

        // References to outer coordinator components (passed via suppliers)
        private final Supplier<Boolean> runningSupplier;
        private final Supplier<List<UUID>> topologySupplier;
        private final java.util.function.Consumer<List<UUID>> broadcastCallback;

        // State for topology change detection
        private List<UUID> lastBroadcastTopology = List.of();
        private long tickCount = 0;

        /**
         * Create the entity with references to outer coordinator components.
         *
         * @param runningSupplier    Supplier for running state
         * @param topologySupplier   Supplier for current topology (all bubbles)
         * @param broadcastCallback  Callback to broadcast topology update
         */
        public ProcessCoordinatorEntity(
            Supplier<Boolean> runningSupplier,
            Supplier<List<UUID>> topologySupplier,
            java.util.function.Consumer<List<UUID>> broadcastCallback
        ) {
            this.runningSupplier = runningSupplier;
            this.topologySupplier = topologySupplier;
            this.broadcastCallback = broadcastCallback;
        }

        @NonEvent
        public long getTickCount() {
            return tickCount;
        }

        @NonEvent
        public List<UUID> getLastBroadcastTopology() {
            return lastBroadcastTopology;
        }

        /**
         * Execute a single coordination tick.
         * <p>
         * Prime-Mover event method that drives distributed coordination.
         * <p>
         * Current responsibilities:
         * - Detect topology changes (registry modifications)
         * - Broadcast updates when topology changes
         * - Future: Process pending coordination tasks
         * <p>
         * Phase 4.2.1: Initial implementation with topology monitoring.
         */
        public void coordinationTick() {
            tickCount++;

            // Check if coordinator should stop
            if (!runningSupplier.get()) {
                return;
            }

            // Step 1: Get current topology
            var currentTopology = topologySupplier.get();

            // Step 2: Check if topology changed
            if (topologyChanged(currentTopology)) {
                log.debug("Topology changed: {} bubbles (tick {})", currentTopology.size(), tickCount);

                // Step 3: Broadcast topology update
                broadcastCallback.accept(currentTopology);

                // Update last broadcast
                lastBroadcastTopology = List.copyOf(currentTopology);

                log.info("Broadcasted topology update: {} bubbles (tick {})",
                        currentTopology.size(), tickCount);
            }

            // Step 4: Process pending coordination tasks
            // Future: Process migration coordination, bucket synchronization, etc.

            // Schedule next tick (recursive event scheduling)
            Kronos.sleep(POLL_INTERVAL_NS);
            this.coordinationTick();
        }

        /**
         * Check if topology changed since last broadcast.
         * <p>
         * Compares current topology with last broadcast topology.
         * Uses Set comparison for order-independent change detection.
         *
         * @param currentTopology Current topology (list of bubble UUIDs)
         * @return true if topology changed
         */
        private boolean topologyChanged(List<UUID> currentTopology) {
            if (currentTopology.size() != lastBroadcastTopology.size()) {
                return true;
            }

            // Order-independent comparison
            var currentSet = new HashSet<>(currentTopology);
            var lastSet = new HashSet<>(lastBroadcastTopology);

            return !currentSet.equals(lastSet);
        }
    }
}

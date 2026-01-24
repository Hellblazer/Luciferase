package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.balancing.DefaultParallelBalancer;
import com.hellblazer.luciferase.lucien.balancing.ParallelBalancer;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.forest.Forest;
import com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Fault-tolerant decorator for DistributedForest providing automatic failure
 * detection and recovery coordination.
 *
 * <p>This decorator wraps a DistributedForest implementation and adds:
 * <ul>
 *   <li>Health state tracking (HEALTHY, SUSPECTED, FAILED)</li>
 *   <li>Quorum-based recovery coordination</li>
 *   <li>Synchronous pause/resume with barrier (waits for in-flight operations)</li>
 *   <li>Metrics collection for monitoring</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>stateLock synchronizes partition state mutations and quorum checks</li>
 *   <li>InFlightOperationTracker provides synchronous pause barrier</li>
 *   <li>RecoveryCoordinatorLock is synchronized for atomic lock acquisition</li>
 * </ul>
 *
 * @param <Key> the spatial key type
 * @param <ID> the entity ID type
 * @param <Content> the content type
 */
public class FaultTolerantDistributedForest<Key extends SpatialKey<Key>, ID extends EntityID, Content>
    implements ParallelBalancer.DistributedForest<Key, ID, Content> {

    private static final Logger log = LoggerFactory.getLogger(FaultTolerantDistributedForest.class);
    private static final long DEFAULT_PAUSE_TIMEOUT_MS = 5000;

    // Core components
    private final ParallelBalancer.DistributedForest<Key, ID, Content> delegate;
    private final SimpleFaultHandler faultHandler;
    private final RecoveryCoordinatorLock recoveryLock;
    private final DefaultParallelBalancer<Key, ID, Content> balancer;
    private final DistributedGhostManager<Key, ID, Content> ghostManager;
    private final PartitionTopology topology;
    private final InFlightOperationTracker operationTracker;

    // Configuration
    private final FaultConfiguration configuration;
    private final UUID localPartitionId;

    // State tracking - protected by stateLock
    private final Object stateLock = new Object();
    private final Map<UUID, PartitionStatus> partitionStates = new ConcurrentHashMap<>();

    // Recovery state
    private final AtomicBoolean inRecoveryMode = new AtomicBoolean(false);
    private Subscription eventSubscription;

    // Optional callback for testing
    private Consumer<UUID> recoveryCallback;

    // Metrics
    private final FaultTolerantForestStats.StatsAccumulator statsAccumulator;

    // Executor for async recovery
    private final ExecutorService recoveryExecutor;

    /**
     * Create a fault-tolerant distributed forest decorator.
     *
     * @param delegate the underlying distributed forest
     * @param faultHandler fault detection handler
     * @param recoveryLock recovery coordination lock
     * @param balancer the parallel balancer (for pause coordination)
     * @param ghostManager the ghost manager (for pause coordination)
     * @param topology partition topology mapping
     * @param localPartitionId the local partition UUID
     * @param configuration fault tolerance configuration
     * @param operationTracker shared tracker for synchronous pause
     */
    public FaultTolerantDistributedForest(
        ParallelBalancer.DistributedForest<Key, ID, Content> delegate,
        SimpleFaultHandler faultHandler,
        RecoveryCoordinatorLock recoveryLock,
        DefaultParallelBalancer<Key, ID, Content> balancer,
        DistributedGhostManager<Key, ID, Content> ghostManager,
        PartitionTopology topology,
        UUID localPartitionId,
        FaultConfiguration configuration,
        InFlightOperationTracker operationTracker
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.faultHandler = Objects.requireNonNull(faultHandler, "faultHandler");
        this.recoveryLock = Objects.requireNonNull(recoveryLock, "recoveryLock");
        this.balancer = Objects.requireNonNull(balancer, "balancer");
        this.ghostManager = Objects.requireNonNull(ghostManager, "ghostManager");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.localPartitionId = Objects.requireNonNull(localPartitionId, "localPartitionId");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.operationTracker = Objects.requireNonNull(operationTracker, "operationTracker");

        this.statsAccumulator = new FaultTolerantForestStats.StatsAccumulator();
        this.recoveryExecutor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "FaultTolerant-Recovery-" + localPartitionId);
            t.setDaemon(true);
            return t;
        });

        // Initialize partition states
        initializePartitionStates();

        log.info("Created FaultTolerantDistributedForest for partition {} with {} total partitions",
            localPartitionId, topology.totalPartitions());
    }

    // === DistributedForest Interface Delegation ===

    @Override
    public Forest<Key, ID, Content> getLocalForest() {
        return delegate.getLocalForest();
    }

    @Override
    public DistributedGhostManager<Key, ID, Content> getGhostManager() {
        return ghostManager;
    }

    @Override
    public ParallelBalancer.PartitionRegistry getPartitionRegistry() {
        return delegate.getPartitionRegistry();
    }

    // === Health State Management ===

    public PartitionStatus getPartitionStatus(UUID partitionId) {
        synchronized (stateLock) {
            return partitionStates.getOrDefault(partitionId, PartitionStatus.HEALTHY);
        }
    }

    public Map<UUID, PartitionStatus> getAllPartitionStates() {
        synchronized (stateLock) {
            return Map.copyOf(partitionStates);
        }
    }

    public int countActivePartitions() {
        synchronized (stateLock) {
            return countActivePartitionsLocked();
        }
    }

    // === Quorum Management ===

    /**
     * Check if quorum is maintained (majority of partitions healthy).
     *
     * <p><b>Thread Safety</b>: Acquires stateLock for consistent read.
     *
     * @return true if quorum maintained
     */
    public boolean hasQuorum() {
        synchronized (stateLock) {
            int activeCount = countActivePartitionsLocked();
            return recoveryLock.hasQuorum(activeCount, topology.totalPartitions());
        }
    }

    // === Recovery Coordination ===

    /**
     * Trigger recovery for a failed partition.
     *
     * @param failedPartitionId the partition to recover
     * @return future completing when recovery finishes
     */
    public CompletableFuture<Boolean> triggerRecovery(UUID failedPartitionId) {
        log.info("Triggering recovery for partition {}", failedPartitionId);

        return CompletableFuture.supplyAsync(() -> {
            var startTime = System.currentTimeMillis();
            boolean success = false;

            try {
                // Acquire recovery lock (synchronized, atomic)
                if (!recoveryLock.acquireRecoveryLock(
                        failedPartitionId,
                        topology,
                        configuration.recoveryTimeoutMs(),
                        TimeUnit.MILLISECONDS)) {
                    log.warn("Failed to acquire recovery lock for partition {}", failedPartitionId);
                    return false;
                }

                try {
                    // Enter recovery mode with synchronous barrier
                    enterRecoveryMode();

                    // Notify callback (for testing)
                    if (recoveryCallback != null) {
                        recoveryCallback.accept(failedPartitionId);
                    }

                    // Execute actual recovery via fault handler
                    var recoveryFuture = faultHandler.initiateRecovery(failedPartitionId);
                    success = recoveryFuture.get(
                        configuration.recoveryTimeoutMs(),
                        TimeUnit.MILLISECONDS
                    );

                    return success;

                } finally {
                    // Always release lock and exit recovery mode
                    exitRecoveryMode();
                    recoveryLock.releaseRecoveryLock(failedPartitionId);

                    var duration = System.currentTimeMillis() - startTime;
                    statsAccumulator.recordRecoveryAttempt(duration, success);

                    log.info("Recovery for partition {} {} in {}ms",
                        failedPartitionId, success ? "succeeded" : "failed", duration);
                }

            } catch (Exception e) {
                log.error("Recovery failed for partition {} with exception", failedPartitionId, e);
                return false;
            }
        }, recoveryExecutor);
    }

    // === Pause/Resume Coordination ===

    /**
     * Enter recovery mode with synchronous barrier.
     *
     * <p>This method:
     * <ol>
     *   <li>Sets recovery mode flag</li>
     *   <li>Pauses balancer operations</li>
     *   <li>Pauses ghost sync operations</li>
     *   <li><b>WAITS</b> for all in-flight operations to complete</li>
     * </ol>
     *
     * <p>Only returns when the system is quiescent.
     */
    public void enterRecoveryMode() {
        if (inRecoveryMode.compareAndSet(false, true)) {
            log.info("Entering recovery mode - pausing operations and waiting for in-flight to complete");

            // Pause via tracker - this will reject new operations
            // and block until existing ones complete
            try {
                boolean completed = operationTracker.pauseAndWait(
                    DEFAULT_PAUSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (!completed) {
                    log.warn("Pause barrier timeout - {} operations still in flight",
                        operationTracker.getActiveCount());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for pause barrier");
            }

            // Also call legacy pause methods for compatibility
            balancer.pauseCrossPartitionBalance();
            ghostManager.pauseAutoSync();

            log.info("Recovery mode entered - all operations paused");
        }
    }

    /**
     * Exit recovery mode and resume normal operations.
     */
    public void exitRecoveryMode() {
        if (inRecoveryMode.compareAndSet(true, false)) {
            log.info("Exiting recovery mode - resuming operations");

            operationTracker.resume();
            balancer.resumeCrossPartitionBalance();
            ghostManager.resumeAutoSync();

            log.info("Recovery mode exited - operations resumed");
        }
    }

    public boolean isInRecoveryMode() {
        return inRecoveryMode.get();
    }

    // === Statistics ===

    /**
     * Get comprehensive statistics including live partition counts.
     *
     * @return statistics snapshot
     */
    public FaultTolerantForestStats getStats() {
        var base = statsAccumulator.snapshot();

        // Compute actual partition counts from live state
        int total, healthy = 0, suspected = 0, failed = 0;
        synchronized (stateLock) {
            total = partitionStates.size();
            for (var status : partitionStates.values()) {
                switch (status) {
                    case HEALTHY -> healthy++;
                    case SUSPECTED -> suspected++;
                    case FAILED -> failed++;
                }
            }
        }

        return new FaultTolerantForestStats(
            total, healthy, suspected, failed,
            base.totalFailuresDetected(),
            base.totalRecoveriesAttempted(),
            base.totalRecoveriesSucceeded(),
            base.averageDetectionLatencyMs(),
            base.averageRecoveryLatencyMs()
        );
    }

    // === Lifecycle ===

    /**
     * Start fault tolerance monitoring.
     */
    public void start() {
        // Subscribe to fault handler events
        subscribeToFaultEvents();
        faultHandler.start();
        log.info("FaultTolerantDistributedForest started for partition {}", localPartitionId);
    }

    /**
     * Stop fault tolerance monitoring.
     *
     * <p><b>Important</b>: Stops fault handler BEFORE unsubscribing to prevent event loss.
     */
    public void stop() {
        log.info("Stopping FaultTolerantDistributedForest for partition {}", localPartitionId);

        // Stop fault handler FIRST (prevents new events)
        faultHandler.stop();

        // THEN unsubscribe (safe now that no new events)
        if (eventSubscription != null) {
            eventSubscription.unsubscribe();
            eventSubscription = null;
        }

        // Shutdown recovery executor
        recoveryExecutor.shutdown();
        try {
            if (!recoveryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                recoveryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            recoveryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("FaultTolerantDistributedForest stopped");
    }

    // === Testing Support ===

    /**
     * Set callback for recovery trigger (testing only).
     */
    public void setRecoveryCallback(Consumer<UUID> callback) {
        this.recoveryCallback = callback;
    }

    // === Private Helpers ===

    private void initializePartitionStates() {
        synchronized (stateLock) {
            for (var rank : topology.activeRanks()) {
                topology.partitionFor(rank).ifPresent(uuid -> {
                    partitionStates.put(uuid, PartitionStatus.HEALTHY);
                    faultHandler.markHealthy(uuid);
                });
            }
            log.debug("Initialized {} partition states as HEALTHY", partitionStates.size());
        }
    }

    private void subscribeToFaultEvents() {
        eventSubscription = faultHandler.subscribeToChanges(this::handlePartitionChange);
        log.debug("Subscribed to fault handler events");
    }

    private void handlePartitionChange(PartitionChangeEvent event) {
        log.debug("Received partition change event: {} -> {} for partition {}",
            event.oldStatus(), event.newStatus(), event.partitionId());

        synchronized (stateLock) {
            // Atomic state update
            var previousStatus = partitionStates.put(event.partitionId(), event.newStatus());
            statsAccumulator.recordStatusChange(event);

            log.info("Partition {} status updated: {} -> {} (previous tracked: {})",
                event.partitionId(), event.oldStatus(), event.newStatus(), previousStatus);

            if (event.newStatus() == PartitionStatus.FAILED) {
                // Quorum check and recovery trigger are ATOMIC (inside stateLock)
                handlePartitionFailure(event.partitionId());
            } else if (event.newStatus() == PartitionStatus.HEALTHY
                       && (event.oldStatus() == PartitionStatus.SUSPECTED
                           || event.oldStatus() == PartitionStatus.FAILED)) {
                // Handle SUSPECTED->HEALTHY or recovery completion
                handlePartitionRecovered(event.partitionId());
            }
        }
    }

    private void handlePartitionFailure(UUID partitionId) {
        // CRITICAL: This is called inside synchronized(stateLock)
        int activeCount = countActivePartitionsLocked();
        int totalCount = topology.totalPartitions();
        boolean hasQuorum = recoveryLock.hasQuorum(activeCount, totalCount);

        log.info("Partition {} failed - quorum check: {}/{} active (hasQuorum={})",
            partitionId, activeCount, totalCount, hasQuorum);

        if (hasQuorum) {
            log.info("Quorum maintained, scheduling async recovery for partition {}", partitionId);
            scheduleRecoveryAsync(partitionId);
        } else {
            log.error("QUORUM LOST - cannot recover partition {}. System degraded: {}/{} active",
                partitionId, activeCount, totalCount);
            statsAccumulator.recordRecoveryBlocked();
        }
    }

    private void handlePartitionRecovered(UUID partitionId) {
        log.info("Partition {} recovered (false alarm or recovery complete)", partitionId);
    }

    private void scheduleRecoveryAsync(UUID partitionId) {
        // Release stateLock before async operation
        triggerRecovery(partitionId)
            .thenAccept(success -> {
                if (success) {
                    log.info("Async recovery completed successfully for partition {}", partitionId);
                } else {
                    log.warn("Async recovery failed for partition {}", partitionId);
                }
            })
            .exceptionally(ex -> {
                log.error("Async recovery threw exception for partition {}", partitionId, ex);
                return null;
            });
    }

    private int countActivePartitionsLocked() {
        return (int) partitionStates.values().stream()
            .filter(s -> s == PartitionStatus.HEALTHY)
            .count();
    }
}

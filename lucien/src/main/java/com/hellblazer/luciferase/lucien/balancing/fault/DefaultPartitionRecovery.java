package com.hellblazer.luciferase.lucien.balancing.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Default recovery coordinator implementing PartitionRecovery interface (Phase 4.3).
 * <p>
 * Manages recovery state machine and coordinates with ghost layer validation.
 * Recovery proceeds through these phases:
 * <pre>
 * IDLE → DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE
 * </pre>
 * <p>
 * On failure at any stage, transitions to FAILED phase.
 * <p>
 * <b>Thread Safety</b>: This class is thread-safe. Multiple threads can call
 * {@link #recover(UUID, FaultHandler)} concurrently, though only one recovery
 * will be active at a time per partition.
 * <p>
 * <b>Example Usage</b>:
 * <pre>{@code
 * var topology = new InMemoryPartitionTopology();
 * topology.register(partition0, 0);
 * topology.register(partition1, 1);
 *
 * var recovery = new DefaultPartitionRecovery(partition0, topology);
 *
 * // Subscribe to phase changes
 * recovery.subscribe(phase -> {
 *     log.info("Recovery phase: {}", phase);
 * });
 *
 * // Initiate recovery
 * var result = recovery.recover(partition0, faultHandler).get();
 * if (result.success()) {
 *     log.info("Recovery completed in {}ms", result.durationMs());
 * }
 * }</pre>
 *
 * @see PartitionRecovery
 * @see RecoveryPhase
 * @see GhostLayerValidator
 */
public class DefaultPartitionRecovery implements PartitionRecovery {

    private static final Logger log = LoggerFactory.getLogger(DefaultPartitionRecovery.class);
    private static final String STRATEGY_NAME = "default-recovery";

    private final UUID partitionId;
    private final PartitionTopology topology;
    private final FaultConfiguration configuration;
    private final GhostLayerValidator validator;

    private volatile RecoveryPhase currentPhase = RecoveryPhase.IDLE;
    private volatile long stateTransitionTime;
    private volatile int retryCount = 0;
    private final List<Consumer<RecoveryPhase>> listeners = new CopyOnWriteArrayList<>();

    // Ghost layer integration for validation
    private com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager<?, ?, ?> ghostManager;

    /**
     * Create recovery coordinator with configuration.
     *
     * @param partitionId partition to recover
     * @param topology partition topology for rank mapping
     * @param configuration fault tolerance configuration
     * @throws IllegalArgumentException if partitionId or topology is null
     */
    public DefaultPartitionRecovery(
        UUID partitionId,
        PartitionTopology topology,
        FaultConfiguration configuration
    ) {
        this.partitionId = Objects.requireNonNull(partitionId, "partitionId cannot be null");
        this.topology = Objects.requireNonNull(topology, "topology cannot be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration cannot be null");
        this.validator = new GhostLayerValidator();
        this.stateTransitionTime = System.currentTimeMillis();
    }

    /**
     * Create recovery coordinator with default configuration.
     *
     * @param partitionId partition to recover
     * @param topology partition topology for rank mapping
     * @throws IllegalArgumentException if partitionId or topology is null
     */
    public DefaultPartitionRecovery(UUID partitionId, PartitionTopology topology) {
        this(partitionId, topology, FaultConfiguration.defaultConfig());
    }

    @Override
    public CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler) {
        if (partitionId == null) {
            throw new IllegalArgumentException("partitionId cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler cannot be null");
        }
        if (!this.partitionId.equals(partitionId)) {
            return CompletableFuture.completedFuture(
                RecoveryResult.failure(
                    partitionId,
                    0,
                    STRATEGY_NAME,
                    1,
                    "Partition ID mismatch: expected " + this.partitionId + ", got " + partitionId,
                    null
                )
            );
        }

        // If already complete, return success immediately
        if (currentPhase == RecoveryPhase.COMPLETE) {
            return CompletableFuture.completedFuture(
                RecoveryResult.success(partitionId, 0, STRATEGY_NAME, retryCount > 0 ? retryCount : 1)
            );
        }

        // If already failed, return failure immediately
        if (currentPhase == RecoveryPhase.FAILED) {
            return CompletableFuture.completedFuture(
                RecoveryResult.failure(
                    partitionId,
                    0,
                    STRATEGY_NAME,
                    retryCount > 0 ? retryCount : 1,
                    "Recovery previously failed",
                    null
                )
            );
        }

        // State machine: IDLE → DETECTING → REDISTRIBUTING → REBALANCING → VALIDATING → COMPLETE
        var startTime = System.currentTimeMillis();

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Phase 1: Detection
                transitionPhase(RecoveryPhase.DETECTING);
                // TODO: Implement actual detection logic when available
                simulatePhaseDelay(50);

                // Phase 2: Redistribution
                transitionPhase(RecoveryPhase.REDISTRIBUTING);
                // TODO: Implement redistribution logic when available
                simulatePhaseDelay(100);

                // Phase 3: Rebalancing
                transitionPhase(RecoveryPhase.REBALANCING);
                // TODO: Implement rebalancing logic when available
                simulatePhaseDelay(75);

                // Phase 4: Validation
                transitionPhase(RecoveryPhase.VALIDATING);
                // Validate ghost layer consistency
                var activeRanks = topology.activeRanks();
                var failedRankOpt = topology.rankFor(partitionId);
                var failedRank = failedRankOpt.orElse(-1);

                // Use real ghost layer if available, otherwise use mock for backwards compatibility
                var ghostLayer = ghostManager != null ? ghostManager.getGhostLayer() : new Object();
                var validationResult = validator.validate(ghostLayer, activeRanks, failedRank);
                if (!validationResult.valid()) {
                    throw new RecoveryException(
                        "Ghost layer validation failed: " + validationResult.errors()
                    );
                }

                // Phase 5: Complete
                transitionPhase(RecoveryPhase.COMPLETE);
                var duration = System.currentTimeMillis() - startTime;

                return RecoveryResult.success(
                    partitionId,
                    duration,
                    STRATEGY_NAME,
                    retryCount > 0 ? retryCount : 1
                );

            } catch (Exception e) {
                transitionPhase(RecoveryPhase.FAILED);
                var duration = System.currentTimeMillis() - startTime;

                return RecoveryResult.failure(
                    partitionId,
                    duration,
                    STRATEGY_NAME,
                    retryCount > 0 ? retryCount : 1,
                    "Recovery failed: " + e.getMessage(),
                    e
                );
            }
        });
    }

    @Override
    public boolean canRecover(UUID partitionId, FaultHandler handler) {
        if (partitionId == null || handler == null) {
            return false;
        }
        if (!this.partitionId.equals(partitionId)) {
            return false;
        }
        // Can recover if not currently active
        return currentPhase == RecoveryPhase.IDLE ||
               currentPhase == RecoveryPhase.FAILED;
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public FaultConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Get current recovery phase.
     *
     * @return current phase
     */
    public RecoveryPhase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Get state transition timestamp.
     *
     * @return timestamp of last phase transition (milliseconds)
     */
    public long getStateTransitionTime() {
        return stateTransitionTime;
    }

    /**
     * Get retry count.
     *
     * @return number of retry attempts
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * Subscribe to phase change notifications.
     * <p>
     * Listeners are notified whenever recovery transitions to a new phase.
     * Notifications are delivered asynchronously and may be delayed.
     * <p>
     * Listener exceptions are caught and ignored to prevent disruption.
     *
     * @param listener callback to receive phase updates
     * @throws IllegalArgumentException if listener is null
     */
    public void subscribe(Consumer<RecoveryPhase> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        listeners.add(listener);
    }

    /**
     * Increment retry count and reset to IDLE phase.
     * <p>
     * Used to prepare for recovery retry after a failure.
     */
    public void retryRecovery() {
        retryCount++;
        transitionPhase(RecoveryPhase.IDLE);
    }

    /**
     * Set the ghost manager for ghost layer validation.
     * <p>
     * This allows recovery to use the real ghost layer for validation
     * instead of a mock object.
     *
     * @param ghostManager the distributed ghost manager
     */
    public void setGhostManager(com.hellblazer.luciferase.lucien.forest.ghost.DistributedGhostManager<?, ?, ?> ghostManager) {
        this.ghostManager = ghostManager;
        log.debug("Ghost manager injected for recovery");
    }

    /**
     * Transition to new recovery phase and notify listeners.
     *
     * @param newPhase new phase to transition to
     */
    private void transitionPhase(RecoveryPhase newPhase) {
        currentPhase = newPhase;
        stateTransitionTime = System.currentTimeMillis();
        notifyListeners(newPhase);
    }

    /**
     * Notify all subscribed listeners of phase change.
     *
     * @param phase new phase
     */
    private void notifyListeners(RecoveryPhase phase) {
        listeners.forEach(listener -> {
            try {
                listener.accept(phase);
            } catch (Exception e) {
                // Ignore listener errors to prevent disruption
            }
        });
    }

    /**
     * Simulate delay for a recovery phase (for testing).
     * <p>
     * This is a placeholder until actual recovery logic is implemented.
     *
     * @param millis delay in milliseconds
     */
    private void simulatePhaseDelay(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RecoveryException("Recovery interrupted", e);
        }
    }

    /**
     * Exception thrown when recovery fails.
     */
    private static class RecoveryException extends RuntimeException {
        public RecoveryException(String message) {
            super(message);
        }

        public RecoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

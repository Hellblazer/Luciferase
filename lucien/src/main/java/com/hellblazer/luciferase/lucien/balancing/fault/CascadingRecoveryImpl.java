package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.common.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Multi-level recovery with fallback strategies.
 * <p>
 * <b>SIMULATION ONLY (Luciferase-yogvu):</b> this strategy does not perform real
 * partition-state restoration — redistribution/state-transfer/rebalancing are stubs.
 * {@link #recover(UUID, FaultHandler)} is an explicit no-op (returns failure) unless
 * {@link #enableSimulatedRecovery()} is called. For production, implement real recovery
 * or use NoOpRecoveryImpl.
 * <p>
 * This strategy attempts recovery using progressively more aggressive approaches:
 * <ol>
 *   <li><b>Level 1: Barrier Sync</b> - Try barrier synchronization first (lightweight)</li>
 *   <li><b>Level 2: State Transfer</b> - If barrier fails, attempt state synchronization</li>
 *   <li><b>Level 3: Full Rebuild</b> - If state transfer fails, trigger full partition rebuild</li>
 * </ol>
 * <p>
 * Each level is attempted up to {@link FaultConfiguration#maxRecoveryRetries()} times
 * before escalating to the next level. This provides robust recovery with
 * graceful degradation.
 * <p>
 * <b>Use Cases</b>:
 * <ul>
 *   <li>Production systems requiring high availability</li>
 *   <li>Partitions with critical data that must be recovered</li>
 *   <li>Environments with intermittent network issues (retry first, rebuild last)</li>
 * </ul>
 * <p>
 * <b>Thread Safety</b>: This class is thread-safe. Multiple concurrent
 * recovery attempts for different partitions are supported.
 *
 * @see BarrierRecoveryImpl
 * @see NoOpRecoveryImpl
 */
public final class CascadingRecoveryImpl implements PartitionRecovery, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CascadingRecoveryImpl.class);
    private static final String STRATEGY_NAME = "cascading-recovery";

    private volatile boolean simulatedRecoveryEnabled = false;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Clock clock = Clock.system(); // Clock injection (Luciferase-mt7hi)
    /**
     * Per-level outcome predicate (Luciferase-7wzml.11).
     * Default: every level succeeds. Inject via setLevelOutcome to drive escalation/retry in tests.
     * Evaluated BEFORE markHealthy — a false return skips markHealthy, eliminating the tautology.
     */
    private volatile Function<RecoveryLevel, Boolean> levelOutcome = level -> true;
    private final FaultConfiguration config;
    private final ExecutorService executor;
    private final CopyOnWriteArrayList<RecoveryProgressObserver> observers;
    private final boolean shutdownExecutorOnClose;

    /**
     * Recovery levels for cascading recovery.
     * Package-private so tests can inject per-level outcomes via setLevelOutcome (Luciferase-7wzml.11).
     */
    enum RecoveryLevel {
        BARRIER("Barrier Synchronization"),
        STATE_TRANSFER("State Transfer"),
        FULL_REBUILD("Full Rebuild");

        private final String description;

        RecoveryLevel(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public CascadingRecoveryImpl() {
        this(FaultConfiguration.defaultConfig());
    }

    public CascadingRecoveryImpl(FaultConfiguration config) {
        this(config, Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("cascading-recovery-", 0).factory()),
             true);
    }

    public CascadingRecoveryImpl(FaultConfiguration config, ExecutorService executor, boolean shutdownExecutorOnClose) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        this.shutdownExecutorOnClose = shutdownExecutorOnClose;
        this.observers = new CopyOnWriteArrayList<>();
    }

    /** Package-private for tests (Luciferase-h08sd). */
    ExecutorService executor() {
        return executor;
    }

    public CascadingRecoveryImpl enableSimulatedRecovery() {
        this.simulatedRecoveryEnabled = true;
        return this;
    }

    /** Inject a deterministic clock for tests (Luciferase-mt7hi). */
    public CascadingRecoveryImpl setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Inject per-level outcome predicate (Luciferase-7wzml.11).
     * false = that level's verifyRecovery fails without calling markHealthy.
     */
    public CascadingRecoveryImpl setLevelOutcome(Function<RecoveryLevel, Boolean> levelOutcome) {
        this.levelOutcome = Objects.requireNonNull(levelOutcome, "levelOutcome");
        return this;
    }

    public void addObserver(RecoveryProgressObserver observer) {
        Objects.requireNonNull(observer, "observer cannot be null");
        observers.add(observer);
    }

    public void removeObserver(RecoveryProgressObserver observer) {
        observers.remove(observer);
    }

    @Override
    public CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler) {
        Objects.requireNonNull(partitionId, "partitionId cannot be null");
        Objects.requireNonNull(handler, "handler cannot be null");
        if (!simulatedRecoveryEnabled) {
            return CompletableFuture.completedFuture(RecoveryResult.failure(
                partitionId, 0L, STRATEGY_NAME, 1,
                "Real partition recovery is not implemented. Call enableSimulatedRecovery() to run scaffolding.",
                null));
        }

        log.info("Initiating cascading recovery for partition {}", partitionId);
        notifyEvent(partitionId, RecoveryEventType.RECOVERY_STARTED, "Cascading recovery initiated");

        var startTime = clock.currentTimeMillis();

        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeCascadingRecovery(partitionId, handler, startTime);
            } catch (Exception e) {
                log.error("Cascading recovery failed for partition {}: {}", partitionId, e.getMessage(), e);
                var duration = clock.currentTimeMillis() - startTime;
                notifyEvent(partitionId, RecoveryEventType.RECOVERY_FAILED, "Recovery failed: " + e.getMessage());
                return RecoveryResult.failure(partitionId, duration, STRATEGY_NAME, 1,
                    "Recovery failed with exception: " + e.getMessage(), e);
            }
        }, executor);
    }

    private RecoveryResult executeCascadingRecovery(UUID partitionId, FaultHandler handler, long startTime) {
        var totalAttempts = 0;

        for (var level : RecoveryLevel.values()) {
            log.info("Attempting recovery level: {} for partition {}", level.getDescription(), partitionId);

            var result = attemptRecoveryLevel(partitionId, handler, level, startTime, totalAttempts);
            totalAttempts += result.attemptsNeeded();

            if (result.success()) {
                log.info("Cascading recovery succeeded at level {} for partition {} (total attempts: {})",
                    level.getDescription(), partitionId, totalAttempts);
                return result;
            }

            log.warn("Recovery level {} failed for partition {}, escalating to next level",
                level.getDescription(), partitionId);
        }

        var duration = clock.currentTimeMillis() - startTime;
        log.error("Cascading recovery exhausted all levels for partition {} (total attempts: {})",
            partitionId, totalAttempts);
        notifyEvent(partitionId, RecoveryEventType.RECOVERY_FAILED,
            "All recovery levels exhausted after " + totalAttempts + " attempts");

        return RecoveryResult.failure(partitionId, duration, STRATEGY_NAME, totalAttempts,
            "All recovery levels exhausted (barrier, state transfer, rebuild)", null);
    }

    private RecoveryResult attemptRecoveryLevel(UUID partitionId, FaultHandler handler, RecoveryLevel level,
                                                 long startTime, int previousAttempts) {
        var maxRetries = config.maxRecoveryRetries();

        for (var attempt = 1; attempt <= maxRetries; attempt++) {
            log.debug("Recovery level {} attempt {}/{} for partition {}",
                level.getDescription(), attempt, maxRetries, partitionId);

            var result = switch (level) {
                case BARRIER -> attemptBarrierSync(partitionId, handler, startTime, previousAttempts + attempt);
                case STATE_TRANSFER -> attemptStateTransfer(partitionId, handler, startTime, previousAttempts + attempt);
                case FULL_REBUILD -> attemptFullRebuild(partitionId, handler, startTime, previousAttempts + attempt);
            };

            if (result.success()) {
                return result;
            }

            if (attempt < maxRetries) {
                var backoffMs = (long) (100 * Math.pow(2, attempt - 1));
                log.debug("Level {} failed, retrying after {}ms", level.getDescription(), backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    var duration = clock.currentTimeMillis() - startTime;
                    return RecoveryResult.failure(partitionId, duration, STRATEGY_NAME, previousAttempts + attempt,
                        "Recovery interrupted during retry backoff at level " + level.getDescription(), e);
                }
            }
        }

        var duration = clock.currentTimeMillis() - startTime;
        return RecoveryResult.failure(partitionId, duration, STRATEGY_NAME, previousAttempts + maxRetries,
            "Recovery level " + level.getDescription() + " failed after " + maxRetries + " attempts", null);
    }

    private RecoveryResult attemptBarrierSync(UUID partitionId, FaultHandler handler, long startTime, int attemptNumber) {
        notifyProgress(partitionId, "barrier-sync", 30, startTime, "Attempting barrier synchronization (Level 1)");
        notifyEvent(partitionId, RecoveryEventType.RECOVERY_BARRIER, "Barrier synchronization at level 1");

        if (!validatePartitionState(partitionId, handler)) {
            var duration = clock.currentTimeMillis() - startTime;
            return RecoveryResult.failure(partitionId, duration, STRATEGY_NAME, attemptNumber,
                "Partition validation failed at barrier sync level", null);
        }

        log.debug("Performing barrier sync for partition {}", partitionId);

        if (verifyRecovery(partitionId, handler, RecoveryLevel.BARRIER)) {
            var duration = clock.currentTimeMillis() - startTime;
            notifyProgress(partitionId, "complete", 100, startTime, "Recovery completed via barrier sync");
            notifyEvent(partitionId, RecoveryEventType.RECOVERY_COMPLETED, "Recovery completed at barrier sync level");
            return RecoveryResult.success(partitionId, duration, STRATEGY_NAME, attemptNumber,
                "Recovery succeeded via barrier synchronization (Level 1)");
        }

        var duration = clock.currentTimeMillis() - startTime;
        return RecoveryResult.failure(partitionId, duration, STRATEGY_NAME, attemptNumber,
            "Barrier synchronization failed", null);
    }

    private RecoveryResult attemptStateTransfer(UUID partitionId, FaultHandler handler, long startTime, int attemptNumber) {
        notifyProgress(partitionId, "state-transfer", 60, startTime, "Attempting state transfer (Level 2)");
        notifyEvent(partitionId, RecoveryEventType.RECOVERY_STATE_SYNC, "State transfer at level 2");

        log.debug("Performing state transfer for partition {}", partitionId);

        if (verifyRecovery(partitionId, handler, RecoveryLevel.STATE_TRANSFER)) {
            var duration = clock.currentTimeMillis() - startTime;
            notifyProgress(partitionId, "complete", 100, startTime, "Recovery completed via state transfer");
            notifyEvent(partitionId, RecoveryEventType.RECOVERY_COMPLETED, "Recovery completed at state transfer level");
            return RecoveryResult.success(partitionId, duration, STRATEGY_NAME, attemptNumber,
                "Recovery succeeded via state transfer (Level 2)");
        }

        var duration = clock.currentTimeMillis() - startTime;
        return RecoveryResult.failure(partitionId, duration, STRATEGY_NAME, attemptNumber,
            "State transfer failed", null);
    }

    private RecoveryResult attemptFullRebuild(UUID partitionId, FaultHandler handler, long startTime, int attemptNumber) {
        notifyProgress(partitionId, "full-rebuild", 90, startTime, "Attempting full rebuild (Level 3)");
        notifyEvent(partitionId, RecoveryEventType.RECOVERY_STATE_SYNC, "Full rebuild at level 3");

        log.debug("Performing full rebuild for partition {}", partitionId);

        if (verifyRecovery(partitionId, handler, RecoveryLevel.FULL_REBUILD)) {
            var duration = clock.currentTimeMillis() - startTime;
            notifyProgress(partitionId, "complete", 100, startTime, "Recovery completed via full rebuild");
            notifyEvent(partitionId, RecoveryEventType.RECOVERY_COMPLETED, "Recovery completed at full rebuild level");
            return RecoveryResult.success(partitionId, duration, STRATEGY_NAME, attemptNumber,
                "Recovery succeeded via full rebuild (Level 3)");
        }

        var duration = clock.currentTimeMillis() - startTime;
        return RecoveryResult.failure(partitionId, duration, STRATEGY_NAME, attemptNumber,
            "Full rebuild failed", null);
    }

    private boolean validatePartitionState(UUID partitionId, FaultHandler handler) {
        var status = handler.checkHealth(partitionId);
        if (status == null) {
            log.warn("Partition {} is unknown to fault handler", partitionId);
            return false;
        }
        return status == PartitionStatus.SUSPECTED || status == PartitionStatus.FAILED;
    }

    /**
     * Verify simulated recovery outcome (Luciferase-7wzml.11).
     * Consults injected levelOutcome predicate BEFORE calling markHealthy.
     * A false result does NOT call markHealthy — no tautological self-confirmation.
     */
    private boolean verifyRecovery(UUID partitionId, FaultHandler handler, RecoveryLevel level) {
        log.debug("Verifying recovery for partition {} at level {}", partitionId, level.getDescription());

        if (!levelOutcome.apply(level)) {
            log.debug("Injected outcome says level {} failed for partition {}", level.getDescription(), partitionId);
            return false;
        }

        handler.markHealthy(partitionId);
        var status = handler.checkHealth(partitionId);
        return status == PartitionStatus.HEALTHY;
    }

    @Override
    public boolean canRecover(UUID partitionId, FaultHandler handler) {
        Objects.requireNonNull(partitionId, "partitionId cannot be null");
        Objects.requireNonNull(handler, "handler cannot be null");

        var status = handler.checkHealth(partitionId);
        if (status == null) {
            log.debug("Partition {} unknown, cannot recover", partitionId);
            return false;
        }

        var canRecover = status == PartitionStatus.SUSPECTED || status == PartitionStatus.FAILED;
        log.debug("Partition {} status: {}, canRecover: {}", partitionId, status, canRecover);
        return canRecover;
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public FaultConfiguration getConfiguration() {
        return config;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && shutdownExecutorOnClose) {
            executor.shutdown();
        }
    }

    private void notifyProgress(UUID partitionId, String phase, int percent, long startTime, String message) {
        if (observers.isEmpty()) {
            return;
        }
        var progress = new RecoveryProgress(partitionId, phase, percent, clock.currentTimeMillis() - startTime, message);
        for (var observer : observers) {
            try {
                observer.onProgress(progress);
            } catch (Exception e) {
                log.warn("Observer threw exception on progress: {}", e.getMessage());
            }
        }
    }

    private void notifyEvent(UUID partitionId, RecoveryEventType eventType, String details) {
        if (observers.isEmpty()) {
            return;
        }
        var event = RecoveryEvent.at(partitionId, eventType, details, clock.currentTimeMillis());
        for (var observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Exception e) {
                log.warn("Observer threw exception on event: {}", e.getMessage());
            }
        }
    }
}

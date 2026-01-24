package com.hellblazer.luciferase.lucien.balancing.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * No-operation recovery strategy for testing.
 * <p>
 * This implementation immediately succeeds without performing any actual recovery
 * actions. Useful for:
 * <ul>
 *   <li>Unit testing where actual recovery is not needed</li>
 *   <li>Integration testing focused on fault detection (not recovery)</li>
 *   <li>Scaffolding during development</li>
 *   <li>Dry-run mode for validation</li>
 * </ul>
 * <p>
 * <b>Warning</b>: Do NOT use in production. This strategy does not perform
 * data redistribution, state synchronization, or any actual recovery work.
 *
 * @see BarrierRecoveryImpl
 * @see CascadingRecoveryImpl
 */
public final class NoOpRecoveryImpl implements PartitionRecovery {

    private static final Logger log = LoggerFactory.getLogger(NoOpRecoveryImpl.class);
    private static final String STRATEGY_NAME = "noop-recovery";

    private final FaultConfiguration config;
    private final long simulatedDelayMs;

    /**
     * Create NoOp recovery with default configuration.
     */
    public NoOpRecoveryImpl() {
        this(FaultConfiguration.defaultConfig(), 0);
    }

    /**
     * Create NoOp recovery with custom configuration.
     *
     * @param config fault configuration (for consistency with other strategies)
     */
    public NoOpRecoveryImpl(FaultConfiguration config) {
        this(config, 0);
    }

    /**
     * Create NoOp recovery with simulated delay.
     * <p>
     * Useful for testing timing-dependent behavior without actual recovery work.
     *
     * @param config fault configuration
     * @param simulatedDelayMs delay before completing (milliseconds)
     */
    public NoOpRecoveryImpl(FaultConfiguration config, long simulatedDelayMs) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        if (simulatedDelayMs < 0) {
            throw new IllegalArgumentException("simulatedDelayMs must be non-negative, got: " + simulatedDelayMs);
        }
        this.simulatedDelayMs = simulatedDelayMs;
    }

    @Override
    public CompletableFuture<RecoveryResult> recover(UUID partitionId, FaultHandler handler) {
        Objects.requireNonNull(partitionId, "partitionId cannot be null");
        Objects.requireNonNull(handler, "handler cannot be null");

        log.debug("NoOp recovery initiated for partition {} (simulated delay: {}ms)",
            partitionId, simulatedDelayMs);

        var startTime = System.currentTimeMillis();

        // Simulate delay if configured
        if (simulatedDelayMs > 0) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(simulatedDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    var duration = System.currentTimeMillis() - startTime;
                    return RecoveryResult.failure(
                        partitionId,
                        duration,
                        STRATEGY_NAME,
                        1,
                        "Recovery interrupted during simulated delay",
                        e
                    );
                }

                var duration = System.currentTimeMillis() - startTime;
                log.debug("NoOp recovery completed for partition {} in {}ms",
                    partitionId, duration);

                return RecoveryResult.success(
                    partitionId,
                    duration,
                    STRATEGY_NAME,
                    1,
                    "NoOp recovery completed (no actual work performed)"
                );
            });
        }

        // Immediate success
        var duration = System.currentTimeMillis() - startTime;
        log.debug("NoOp recovery completed immediately for partition {}", partitionId);

        return CompletableFuture.completedFuture(
            RecoveryResult.success(
                partitionId,
                duration,
                STRATEGY_NAME,
                1,
                "NoOp recovery completed (no actual work performed)"
            )
        );
    }

    @Override
    public boolean canRecover(UUID partitionId, FaultHandler handler) {
        Objects.requireNonNull(partitionId, "partitionId cannot be null");
        Objects.requireNonNull(handler, "handler cannot be null");

        // NoOp recovery succeeds if partition is known and in recoverable state
        var status = handler.checkHealth(partitionId);
        if (status == null) {
            return false; // Unknown partition
        }

        // Can recover if partition is SUSPECTED or FAILED
        return status == PartitionStatus.SUSPECTED || status == PartitionStatus.FAILED;
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public FaultConfiguration getConfiguration() {
        return config;
    }
}

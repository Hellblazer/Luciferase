package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import java.util.*;

/**
 * Reusable failure patterns for scenario composition.
 * <p>
 * Provides pre-defined failure templates that can be applied to
 * {@link FaultScenarioBuilder} to quickly construct common test scenarios.
 * <p>
 * <b>Available Patterns</b>:
 * <ul>
 *   <li>{@link #singlePartitionFailure} - One partition fails and recovers</li>
 *   <li>{@link #cascadingFailures} - Chain reaction of sequential failures</li>
 *   <li>{@link #burstFailures} - Multiple concurrent failures</li>
 *   <li>{@link #networkDegradation} - Packet loss during recovery</li>
 *   <li>{@link #clockSkew} - Time anomalies during critical phases</li>
 *   <li>{@link #resourceConstraint} - Memory/thread pressure during recovery</li>
 * </ul>
 */
public interface FailurePattern {

    /**
     * Apply this pattern to the scenario builder.
     *
     * @param builder scenario builder to configure
     */
    void apply(FaultScenarioBuilder builder);

    /**
     * Single partition failure pattern.
     * <p>
     * One partition fails immediately, recovers after delay.
     * Suitable for basic recovery testing.
     *
     * @param partitionId partition to fail
     * @param recoveryDelayMs delay before recovery completes
     * @return pattern instance
     */
    static FailurePattern singlePartitionFailure(UUID partitionId, long recoveryDelayMs) {
        return builder -> {
            builder.failPartitionAt(0, partitionId);
            builder.validateAt(recoveryDelayMs, () -> {
                // Validation hook - user can add custom validation
            });
        };
    }

    /**
     * Cascading failures pattern.
     * <p>
     * Multiple partitions fail in sequence with staggered delays.
     * Simulates chain reaction scenarios.
     *
     * @param partitions list of partitions to fail
     * @param delayBetweenMs delay between successive failures
     * @return pattern instance
     */
    static FailurePattern cascadingFailures(List<UUID> partitions, long delayBetweenMs) {
        return builder -> {
            for (var i = 0; i < partitions.size(); i++) {
                var delay = i * delayBetweenMs;
                builder.failPartitionAt(delay, partitions.get(i));
            }
        };
    }

    /**
     * Burst failures pattern.
     * <p>
     * Multiple partitions fail simultaneously at specified time.
     * Tests concurrent recovery handling.
     *
     * @param partitions set of partitions to fail
     * @param failureTimeMs when burst occurs
     * @return pattern instance
     */
    static FailurePattern burstFailures(Set<UUID> partitions, long failureTimeMs) {
        return builder -> {
            for (var partition : partitions) {
                builder.failPartitionAt(failureTimeMs, partition);
            }
        };
    }

    /**
     * Network degradation pattern.
     * <p>
     * Injects packet loss during recovery phase.
     * Tests recovery robustness under poor network conditions.
     *
     * @param lossRate packet loss rate [0.0, 1.0]
     * @param startTimeMs when degradation starts
     * @param durationMs degradation duration
     * @return pattern instance
     */
    static FailurePattern networkDegradation(double lossRate, long startTimeMs, long durationMs) {
        return builder -> {
            // Start packet loss
            builder.networkFaultAt(startTimeMs, lossRate);

            // Stop packet loss (reset to 0%)
            builder.networkFaultAt(startTimeMs + durationMs, 0.0);
        };
    }

    /**
     * Clock skew pattern.
     * <p>
     * Injects time anomalies during critical recovery phases.
     * Tests time-dependent logic robustness.
     *
     * @param skewMs clock offset in milliseconds
     * @param injectionTimeMs when skew occurs
     * @return pattern instance
     */
    static FailurePattern clockSkew(long skewMs, long injectionTimeMs) {
        return builder -> {
            builder.clockSkewAt(injectionTimeMs, skewMs);
        };
    }

    /**
     * Clock oscillation pattern.
     * <p>
     * Clock jumps back and forth to simulate unstable time source.
     *
     * @param skewMs maximum skew amplitude
     * @param periodMs oscillation period
     * @param cycleCount number of oscillation cycles
     * @return pattern instance
     */
    static FailurePattern clockOscillation(long skewMs, long periodMs, int cycleCount) {
        return builder -> {
            for (var i = 0; i < cycleCount; i++) {
                var phase = i * periodMs;
                // Alternate between positive and negative skew
                var currentSkew = (i % 2 == 0) ? skewMs : -skewMs;
                builder.clockSkewAt(phase, currentSkew);
            }
        };
    }

    /**
     * Resource constraint pattern.
     * <p>
     * Injects memory pressure during recovery.
     * Tests graceful degradation under resource scarcity.
     *
     * @param pressureMB memory pressure in megabytes
     * @param startTimeMs when constraint starts
     * @return pattern instance
     */
    static FailurePattern memoryPressure(long pressureMB, long startTimeMs) {
        return builder -> {
            builder.atTime(startTimeMs, () -> {
                builder.getInjector().injectMemoryPressure(pressureMB);
            });
        };
    }

    /**
     * Thread starvation pattern.
     * <p>
     * Limits available thread pool during recovery.
     * Tests recovery with constrained concurrency.
     *
     * @param maxThreads maximum thread pool size
     * @param startTimeMs when constraint starts
     * @return pattern instance
     */
    static FailurePattern threadStarvation(int maxThreads, long startTimeMs) {
        return builder -> {
            builder.atTime(startTimeMs, () -> {
                builder.getInjector().injectThreadStarvation(maxThreads);
            });
        };
    }

    /**
     * Adversarial pattern (kitchen sink).
     * <p>
     * Combines multiple fault types for stress testing.
     * Injects partition failures, network faults, and clock skew concurrently.
     *
     * @param failedPartitions partitions to fail
     * @param packetLossRate network packet loss rate
     * @param clockSkewMs clock offset
     * @return pattern instance
     */
    static FailurePattern adversarial(Set<UUID> failedPartitions, double packetLossRate, long clockSkewMs) {
        return builder -> {
            // Simultaneous partition failures
            for (var partition : failedPartitions) {
                builder.failPartitionAt(0, partition);
            }

            // Network degradation starts slightly after
            builder.networkFaultAt(50, packetLossRate);

            // Clock skew during recovery
            builder.clockSkewAt(100, clockSkewMs);
        };
    }

    /**
     * Recovery validation pattern.
     * <p>
     * Adds validation checkpoints at key recovery phases.
     * Combines with other patterns to verify recovery correctness.
     *
     * @param detectTimeMs expected detection time
     * @param redistributeTimeMs expected redistribution time
     * @param completeTimeMs expected completion time
     * @return pattern instance
     */
    static FailurePattern recoveryValidation(long detectTimeMs, long redistributeTimeMs, long completeTimeMs) {
        return builder -> {
            builder.validateAt(detectTimeMs, () -> {
                // User adds: verify DETECTING phase reached
            });

            builder.validateAt(redistributeTimeMs, () -> {
                // User adds: verify REDISTRIBUTING phase reached
            });

            builder.validateAt(completeTimeMs, () -> {
                // User adds: verify COMPLETE phase reached
            });
        };
    }
}

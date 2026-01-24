package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fluent API for building fault injection scenarios.
 * <p>
 * Provides a declarative way to define complex failure scenarios with
 * time-based action scheduling and validation checkpoints.
 * <p>
 * <b>Usage Pattern</b>:
 * <pre>{@code
 * var scenario = new FaultScenarioBuilder(injector, clock)
 *     .setup(5) // 5-partition forest
 *     .atTime(0, () -> injectFailure(P0))
 *     .atTime(100, () -> validateRecoveryStarted(P0))
 *     .atTime(500, () -> injectFailure(P1))
 *     .atTime(1000, () -> validateAllRecovered())
 *     .build();
 *
 * scenario.execute();
 * }</pre>
 */
public class FaultScenarioBuilder {

    private final FaultInjector injector;
    private final TestClock clock;
    private final Map<Long, List<Runnable>> scheduledActions;
    private int partitionCount = 0;
    private String scenarioName = "UnnamedScenario";

    /**
     * Create scenario builder with injector and clock.
     *
     * @param injector fault injector for injection
     * @param clock test clock for time control
     */
    public FaultScenarioBuilder(FaultInjector injector, TestClock clock) {
        this.injector = Objects.requireNonNull(injector, "injector cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.scheduledActions = new TreeMap<>();
    }

    /**
     * Set scenario name for reporting.
     *
     * @param name scenario name
     * @return this builder for chaining
     */
    public FaultScenarioBuilder named(String name) {
        this.scenarioName = Objects.requireNonNull(name, "name cannot be null");
        return this;
    }

    /**
     * Setup distributed forest with specified partition count.
     * <p>
     * This is typically the first method called in a scenario definition.
     *
     * @param count number of partitions
     * @return this builder for chaining
     */
    public FaultScenarioBuilder setup(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("partition count must be positive, got: " + count);
        }
        this.partitionCount = count;
        return this;
    }

    /**
     * Schedule action at specific relative time.
     * <p>
     * Time is relative to scenario start (t=0).
     *
     * @param timeMs relative time in milliseconds
     * @param action action to execute
     * @return this builder for chaining
     */
    public FaultScenarioBuilder atTime(long timeMs, Runnable action) {
        if (timeMs < 0) {
            throw new IllegalArgumentException("time must be non-negative, got: " + timeMs);
        }
        Objects.requireNonNull(action, "action cannot be null");

        scheduledActions.computeIfAbsent(timeMs, k -> new ArrayList<>())
            .add(action);
        return this;
    }

    /**
     * Schedule partition failure at specific time.
     *
     * @param timeMs relative time in milliseconds
     * @param partitionId partition to fail
     * @return this builder for chaining
     */
    public FaultScenarioBuilder failPartitionAt(long timeMs, UUID partitionId) {
        return atTime(timeMs, () -> {
            injector.injectPartitionFailure(partitionId, 0);
        });
    }

    /**
     * Schedule network fault at specific time.
     *
     * @param timeMs relative time in milliseconds
     * @param packetLossRate packet loss rate [0.0, 1.0]
     * @return this builder for chaining
     */
    public FaultScenarioBuilder networkFaultAt(long timeMs, double packetLossRate) {
        return atTime(timeMs, () -> {
            injector.injectPacketLoss(packetLossRate);
        });
    }

    /**
     * Schedule clock skew at specific time.
     *
     * @param timeMs relative time in milliseconds
     * @param skewMs clock skew in milliseconds
     * @return this builder for chaining
     */
    public FaultScenarioBuilder clockSkewAt(long timeMs, long skewMs) {
        return atTime(timeMs, () -> {
            injector.injectClockSkew(skewMs);
        });
    }

    /**
     * Schedule validation checkpoint at specific time.
     *
     * @param timeMs relative time in milliseconds
     * @param validator validation action
     * @return this builder for chaining
     */
    public FaultScenarioBuilder validateAt(long timeMs, Runnable validator) {
        return atTime(timeMs, validator);
    }

    /**
     * Apply a failure pattern template.
     *
     * @param pattern failure pattern to apply
     * @return this builder for chaining
     */
    public FaultScenarioBuilder applyPattern(FailurePattern pattern) {
        Objects.requireNonNull(pattern, "pattern cannot be null");
        pattern.apply(this);
        return this;
    }

    /**
     * Build executable fault scenario.
     *
     * @return FaultScenario ready for execution
     */
    public FaultScenario build() {
        if (partitionCount == 0) {
            throw new IllegalStateException("Partition count not set - call setup() first");
        }

        return new FaultScenario(
            scenarioName,
            partitionCount,
            scheduledActions,
            injector,
            clock
        );
    }

    /**
     * Get fault injector.
     *
     * @return injector instance
     */
    public FaultInjector getInjector() {
        return injector;
    }

    /**
     * Get test clock.
     *
     * @return clock instance
     */
    public TestClock getClock() {
        return clock;
    }

    /**
     * Get configured partition count.
     *
     * @return partition count (0 if not set)
     */
    public int getPartitionCount() {
        return partitionCount;
    }
}

/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared test utilities for integration tests.
 *
 * @author hal.hildebrand
 */
public class TestUtils {

    /**
     * Poll until a condition becomes true or timeout occurs.
     * <p>
     * Provides deterministic async coordination for integration tests,
     * replacing brittle Thread.sleep() patterns with timeout-based polling.
     *
     * @param condition Supplier that returns true when condition is met
     * @param description Human-readable description of what we're waiting for
     * @param timeout Maximum time to wait
     * @throws InterruptedException if interrupted while polling
     */
    public static void awaitCondition(Supplier<Boolean> condition,
                                      String description,
                                      Duration timeout) throws InterruptedException {
        long startMs = System.currentTimeMillis();
        long pollIntervalMs = 50;  // 50ms poll interval

        while (!condition.get()) {
            Thread.sleep(pollIntervalMs);
            long elapsedMs = System.currentTimeMillis() - startMs;
            if (elapsedMs > timeout.toMillis()) {
                fail(String.format("Timeout after %dms waiting for %s",
                    elapsedMs, description));
            }
        }
    }

    /**
     * Poll until expected build count is reached or timeout occurs.
     * <p>
     * Convenience wrapper for RegionBuilder build completion.
     *
     * @param builder RegionBuilder to poll
     * @param expectedCount Minimum number of builds expected to complete
     * @param timeout Maximum time to wait
     * @throws InterruptedException if interrupted while polling
     */
    public static void awaitBuilds(RegionBuilder builder,
                                   int expectedCount,
                                   Duration timeout) throws InterruptedException {
        awaitBuilds(builder, expectedCount, timeout, 50);
    }

    /**
     * Poll until expected build count is reached or timeout occurs.
     * <p>
     * Convenience wrapper for RegionBuilder build completion with custom poll interval.
     *
     * @param builder RegionBuilder to poll
     * @param expectedCount Minimum number of builds expected to complete
     * @param timeout Maximum time to wait
     * @param pollIntervalMs Poll interval in milliseconds
     * @throws InterruptedException if interrupted while polling
     */
    public static void awaitBuilds(RegionBuilder builder,
                                   int expectedCount,
                                   Duration timeout,
                                   long pollIntervalMs) throws InterruptedException {
        long startMs = System.currentTimeMillis();

        while (builder.getTotalBuilds() < expectedCount) {
            Thread.sleep(pollIntervalMs);
            long elapsedMs = System.currentTimeMillis() - startMs;
            if (elapsedMs > timeout.toMillis()) {
                fail(String.format(
                    "Timeout after %dms waiting for %d builds (actual: %d, queue depth: %d)",
                    elapsedMs, expectedCount, builder.getTotalBuilds(), builder.getQueueDepth()
                ));
            }
        }
    }

    private TestUtils() {
        // Utility class - prevent instantiation
    }
}

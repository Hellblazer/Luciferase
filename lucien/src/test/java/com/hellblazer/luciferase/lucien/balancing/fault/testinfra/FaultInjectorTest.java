/**
 * Copyright (C) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FaultInjector#awaitCompletion} deterministic delayed-injection completion (Luciferase-uockh).
 *
 * <p>A delayed partition failure runs its handler invocation on a background executor; before this fix the
 * {@code executor.submit(...)} future was discarded, so a caller could only race a fixed sleep over the
 * delay (the background-race class fixed for IntegrationTestFixture in 8brw9). These tests pin that the
 * completion is now awaitable.
 *
 * @author hal.hildebrand
 */
class FaultInjectorTest {

    @Test
    void delayedInjectionCompletionIsAwaitable() throws Exception {
        var injector = new FaultInjector(new TestClock(1_000L));
        var failed = new AtomicReference<UUID>();
        injector.registerPartitionFailureHandler("recorder", failed::set);

        var partitionId = UUID.randomUUID();
        var fault = injector.injectPartitionFailure(partitionId, 50);

        // Await the actual (background) injection rather than sleeping a margin over the 50ms delay.
        injector.awaitCompletion(fault).get(5, TimeUnit.SECONDS);

        assertEquals(partitionId, failed.get(),
                     "after awaitCompletion, the delayed partition-failure handler must have run");
    }

    @Test
    void immediateInjectionCompletesSynchronously() throws Exception {
        var injector = new FaultInjector(new TestClock(1_000L));
        var failed = new AtomicReference<UUID>();
        injector.registerPartitionFailureHandler("recorder", failed::set);

        var partitionId = UUID.randomUUID();
        var fault = injector.injectPartitionFailure(partitionId, 0);

        // Immediate failure ran synchronously on this thread; its completion is already done.
        assertTrue(injector.awaitCompletion(fault).isDone(),
                   "an immediate (delay=0) injection's completion must already be complete");
        assertEquals(partitionId, failed.get(),
                     "an immediate partition-failure handler must run synchronously");
        injector.awaitCompletion(fault).get(1, TimeUnit.SECONDS); // never blocks
    }
}

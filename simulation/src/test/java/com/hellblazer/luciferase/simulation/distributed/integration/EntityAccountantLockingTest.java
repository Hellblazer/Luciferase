/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression test for Luciferase-0frcy.22: EntityAccountant.register()/unregister() must hold the
 * ReentrantLock so a concurrent validate() never observes the bidirectional-invariant violated
 * (entity in entityToBubble but not yet in bubbleToEntities, or vice versa). The class javadoc
 * claims "All operations are atomic"; pre-fix register/unregister bypassed the lock entirely.
 */
class EntityAccountantLockingTest {

    @Test
    void validateNeverSeesPartialRegistration() throws InterruptedException {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            var accountant = new EntityAccountant();
            var bubbleId = UUID.randomUUID();
            var spuriousErrors = new AtomicInteger(0);
            int iterations = 2_000;

            var pool = Executors.newFixedThreadPool(2);
            try {
                var start = new CountDownLatch(1);

                var registrar = pool.submit(() -> {
                    awaitQuietly(start);
                    for (int i = 0; i < iterations; i++) {
                        var entityId = UUID.randomUUID();
                        accountant.register(bubbleId, entityId);
                        accountant.unregister(bubbleId, entityId);
                    }
                });

                var validator = pool.submit(() -> {
                    awaitQuietly(start);
                    for (int i = 0; i < iterations; i++) {
                        var result = accountant.validate();
                        if (!result.success()) {
                            // A transient bidirectional-invariant breakage is exactly the bug.
                            spuriousErrors.incrementAndGet();
                        }
                    }
                });

                start.countDown();
                registrar.get(15, TimeUnit.SECONDS);
                validator.get(15, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertEquals(0, spuriousErrors.get(),
                         "validate() observed a partial register/unregister — lock not held");
        });
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

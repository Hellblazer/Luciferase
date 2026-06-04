/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.von;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Regression test for Luciferase-0frcy.50: Bubble.close() idempotency must be an atomic
 * test-and-set. The prior plain {@code volatile boolean} check-then-act let two concurrent
 * close() callers both pass the guard and both broadcast Leave — duplicate departure.
 */
class BubbleCloseRaceTest {

    @Test
    void concurrentCloseBroadcastsLeaveExactlyOnce() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            // Run many trials to make the race extremely likely to manifest pre-fix.
            for (int trial = 0; trial < 200; trial++) {
                var transport = mock(Transport.class);
                var neighborId = UUID.randomUUID();
                var leaveSends = new AtomicInteger(0);

                // Count Leave sends to the neighbor.
                doAnswer(inv -> {
                    Object msg = inv.getArgument(1);
                    if (msg instanceof Message.Leave) {
                        leaveSends.incrementAndGet();
                    }
                    return null;
                }).when(transport).sendToNeighbor(eq(neighborId), any(Message.class));

                var bubble = new Bubble(UUID.randomUUID(), (byte) 10, 16L, transport);
                bubble.addNeighbor(neighborId);

                var start = new CountDownLatch(1);
                Runnable closer = () -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    bubble.close();
                };
                var t1 = new Thread(closer);
                var t2 = new Thread(closer);
                t1.start();
                t2.start();
                start.countDown();  // release both simultaneously
                t1.join(5_000);
                t2.join(5_000);

                assertEquals(1, leaveSends.get(),
                             "Leave must be broadcast exactly once even under concurrent close (trial " + trial + ")");
            }
        });
    }
}

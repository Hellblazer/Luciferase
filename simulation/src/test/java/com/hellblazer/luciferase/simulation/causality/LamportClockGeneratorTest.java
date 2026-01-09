/**
 * Copyright (C) 2024 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase Simulation Framework.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.causality;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for LamportClockGenerator (Phase 7C.1)
 *
 * Tests Lamport clock semantics, vector timestamp tracking,
 * causality detection, and concurrent access patterns.
 *
 * @author hal.hildebrand
 */
class LamportClockGeneratorTest {

    /**
     * Test: LamportClockGenerator initializes to 0.
     */
    @Test
    void testInitialization() {
        var bubbleId = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        assertEquals(0L, clock.getLamportClock(), "Clock should start at 0");
        assertEquals(0L, clock.getVectorTimestamp(bubbleId), "Vector timestamp should start at 0");
        assertTrue(clock.getKnownSources().contains(bubbleId), "Should know self as source");
    }

    /**
     * Test: Local tick increments Lamport clock.
     */
    @Test
    void testLocalTickIncrement() {
        var bubbleId = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        for (int i = 1; i <= 10; i++) {
            var timestamp = clock.tick();
            assertEquals(i, timestamp, "Clock should increment by 1 each tick");
            assertEquals(i, clock.getLamportClock(), "getLamportClock() should match tick result");
        }
    }

    /**
     * Test: Vector timestamp tracks local ticks.
     */
    @Test
    void testVectorTimestampLocalUpdate() {
        var bubbleId = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        for (int i = 1; i <= 5; i++) {
            clock.tick();
            assertEquals(i, clock.getVectorTimestamp(bubbleId),
                        "Vector timestamp should match local clock");
        }
    }

    /**
     * Test: Remote event with lower clock doesn't affect local clock much.
     */
    @Test
    void testRemoteEventLowerClock() {
        var bubbleId = UUID.randomUUID();
        var remoteBubbleId = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        // Advance local clock
        clock.tick(); // 1
        clock.tick(); // 2
        clock.tick(); // 3

        var localClock = clock.getLamportClock();

        // Remote event with lower clock
        var updated = clock.onRemoteEvent(1L, remoteBubbleId);

        // Should be max(3, 1) + 1 = 4
        assertEquals(localClock + 1, updated, "Clock should be max(local, remote) + 1");
    }

    /**
     * Test: Remote event with higher clock jumps ahead.
     */
    @Test
    void testRemoteEventHigherClock() {
        var bubbleId = UUID.randomUUID();
        var remoteBubbleId = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        clock.tick(); // 1
        var localClock = clock.getLamportClock();

        // Remote event with much higher clock
        var updated = clock.onRemoteEvent(100L, remoteBubbleId);

        // Should be max(1, 100) + 1 = 101
        assertEquals(101L, updated, "Clock should jump to max(local, remote) + 1");
    }

    /**
     * Test: Vector timestamp tracks multiple sources.
     */
    @Test
    void testVectorTimestampMultipleSources() {
        var bubbleId = UUID.randomUUID();
        var source1 = UUID.randomUUID();
        var source2 = UUID.randomUUID();
        var source3 = UUID.randomUUID();

        var clock = new LamportClockGenerator(bubbleId);

        clock.onRemoteEvent(10L, source1);
        clock.onRemoteEvent(20L, source2);
        clock.onRemoteEvent(5L, source3);

        var vectorTimestamp = clock.getVectorTimestamp();

        assertEquals(10L, clock.getVectorTimestamp(source1), "Should track source1");
        assertEquals(20L, clock.getVectorTimestamp(source2), "Should track source2");
        assertEquals(5L, clock.getVectorTimestamp(source3), "Should track source3");
        assertEquals(4, vectorTimestamp.size(), "Should have 4 sources (self + 3 remote)");
    }

    /**
     * Test: Vector timestamp updates to higher value.
     */
    @Test
    void testVectorTimestampUpdate() {
        var bubbleId = UUID.randomUUID();
        var source1 = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        clock.onRemoteEvent(10L, source1);
        assertEquals(10L, clock.getVectorTimestamp(source1), "Should record first update");

        clock.onRemoteEvent(20L, source1);
        assertEquals(20L, clock.getVectorTimestamp(source1), "Should update to higher value");

        // Lower value should not downgrade
        clock.onRemoteEvent(5L, source1);
        assertEquals(20L, clock.getVectorTimestamp(source1), "Should not downgrade");
    }

    /**
     * Test: canProcess() returns true for processable events.
     */
    @Test
    void testCanProcessSequential() {
        var bubbleId = UUID.randomUUID();
        var source = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        // First event from source
        assertTrue(clock.canProcess(1L, source), "Should process first event");

        clock.onRemoteEvent(1L, source);

        // Next sequential event
        assertTrue(clock.canProcess(2L, source), "Should process sequential event");
    }

    /**
     * Test: canProcess() handles out-of-order events.
     */
    @Test
    void testCanProcessOutOfOrder() {
        var bubbleId = UUID.randomUUID();
        var source = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        // High event arrives first
        clock.onRemoteEvent(100L, source);

        // Lower event should still be processable (idempotency)
        assertTrue(clock.canProcess(50L, source), "Should process lower event (idempotency)");
    }

    /**
     * Test: Concurrent ticks maintain monotonicity.
     */
    @Test
    void testConcurrentTicks() throws InterruptedException {
        var bubbleId = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        int threadCount = 4;
        int ticksPerThread = 25;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var timestamps = new ArrayList<Long>();

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < ticksPerThread; i++) {
                        synchronized (timestamps) {
                            timestamps.add(clock.tick());
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Should have all ticks (order may vary but should be unique)
        assertEquals(threadCount * ticksPerThread, timestamps.size(), "Should have all ticks");
        var finalClock = clock.getLamportClock();
        assertEquals(threadCount * ticksPerThread, finalClock, "Final clock should be threadCount * ticksPerThread");
    }

    /**
     * Test: Concurrent remote events with same source.
     */
    @Test
    void testConcurrentRemoteEvents() throws InterruptedException {
        var bubbleId = UUID.randomUUID();
        var source = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        int threadCount = 4;
        int eventsPerThread = 25;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < eventsPerThread; i++) {
                        long remoteClock = threadId * 100 + i;
                        clock.onRemoteEvent(remoteClock, source);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Vector timestamp should be updated to highest value seen
        long expectedHighest = (threadCount - 1) * 100 + (eventsPerThread - 1);
        assertEquals(expectedHighest, clock.getVectorTimestamp(source),
                    "Should track highest clock from source");
    }

    /**
     * Test: Known sources set reflects all seen bubbles.
     */
    @Test
    void testKnownSources() {
        var bubbleId = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        var source1 = UUID.randomUUID();
        var source2 = UUID.randomUUID();
        var source3 = UUID.randomUUID();

        clock.onRemoteEvent(10L, source1);
        clock.onRemoteEvent(20L, source2);
        clock.onRemoteEvent(30L, source3);

        var knownSources = clock.getKnownSources();
        assertEquals(4, knownSources.size(), "Should know self + 3 sources");
        assertTrue(knownSources.contains(bubbleId), "Should contain self");
        assertTrue(knownSources.contains(source1), "Should contain source1");
        assertTrue(knownSources.contains(source2), "Should contain source2");
        assertTrue(knownSources.contains(source3), "Should contain source3");
    }

    /**
     * Test: Clock near overflow detection.
     */
    @Test
    void testClockNearOverflow() {
        var bubbleId = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        assertFalse(clock.isClockNearOverflow(), "New clock should not be near overflow");

        // Would take too long to test actual overflow, but we can verify the method exists
        // and works correctly for normal values
    }

    /**
     * Test: toString() method.
     */
    @Test
    void testToString() {
        var bubbleId = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        var str = clock.toString();
        assertNotNull(str);
        assertTrue(str.contains("LamportClockGenerator"), "Should contain class name");
        assertTrue(str.contains(bubbleId.toString()), "Should contain bubble ID");
        assertTrue(str.contains("0"), "Should show clock value");
    }

    /**
     * Test: Vector timestamp immutability.
     */
    @Test
    void testVectorTimestampImmutable() {
        var bubbleId = UUID.randomUUID();
        var source = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        clock.onRemoteEvent(10L, source);

        var vectorTimestamp1 = clock.getVectorTimestamp();
        var vectorTimestamp2 = clock.getVectorTimestamp();

        assertEquals(vectorTimestamp1, vectorTimestamp2, "Should return equal snapshots");

        // Verify returned map is unmodifiable
        assertThrows(UnsupportedOperationException.class,
                    () -> vectorTimestamp1.put(UUID.randomUUID(), 999L),
                    "Returned map should be unmodifiable");
    }

    /**
     * Test: Multiple sequential events from same source.
     */
    @Test
    void testMultipleSequentialEvents() {
        var bubbleId = UUID.randomUUID();
        var source = UUID.randomUUID();
        var clock = new LamportClockGenerator(bubbleId);

        // Simulate receiving events 1, 2, 3 from remote source
        for (int i = 1; i <= 5; i++) {
            var updated = clock.onRemoteEvent(i, source);
            // Each update should be max(local, remote) + 1
            assertTrue(updated > i, "Clock should advance beyond remote event");
        }

        assertEquals(5, clock.getVectorTimestamp(source), "Should track highest from source");
    }
}

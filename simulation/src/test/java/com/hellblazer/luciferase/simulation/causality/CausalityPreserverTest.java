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

import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for CausalityPreserver (Phase 7C.3)
 *
 * Tests causality enforcement, event ordering, metrics,
 * and idempotent replay with concurrent access.
 *
 * @author hal.hildebrand
 */
class CausalityPreserverTest {

    /**
     * Helper: Create an EntityUpdateEvent for testing.
     */
    private EntityUpdateEvent createEvent(long lamportClock, long timestamp, String entityId) {
        return new EntityUpdateEvent(
            new StringEntityID(entityId),
            new Point3f(0, 0, 0),
            new Point3f(1, 0, 0),
            timestamp,
            lamportClock
        );
    }

    /**
     * Test: CausalityPreserver initializes with empty history.
     */
    @Test
    void testInitialization() {
        var preserver = new CausalityPreserver();

        assertEquals(0, preserver.getSourceCount(), "Should have no sources");
        assertEquals(0L, preserver.getTotalProcessed(), "Should have no processed");
        assertEquals(0L, preserver.getTotalRejected(), "Should have no rejected");
        assertEquals(0L, preserver.getTotalIdempotent(), "Should have no idempotent");
        assertTrue(preserver.isConsistent(), "Should be consistent");
    }

    /**
     * Test: First event from new source can be processed.
     */
    @Test
    void testCanProcessNewSource() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var event = createEvent(1L, System.currentTimeMillis(), "entity1");

        assertTrue(preserver.canProcess(event, source), "Should allow first event from source");
    }

    /**
     * Test: Sequential events from same source can be processed.
     */
    @Test
    void testCanProcessSequential() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        for (int clock = 1; clock <= 5; clock++) {
            var event = createEvent(clock, currentTime, "entity" + clock);
            assertTrue(preserver.canProcess(event, source),
                      "Should process sequential event clock=" + clock);
            preserver.markProcessed(event, source);
        }

        assertEquals(5L, preserver.getTotalProcessed(), "Should have 5 processed");
    }

    /**
     * Test: Out-of-order event is rejected (causality violation).
     */
    @Test
    void testRejectOutOfOrder() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Process event 1
        var event1 = createEvent(1L, currentTime, "e1");
        assertTrue(preserver.canProcess(event1, source));
        preserver.markProcessed(event1, source);

        // Try to process event 2 (OK, sequential)
        var event2 = createEvent(2L, currentTime, "e2");
        assertTrue(preserver.canProcess(event2, source));
        preserver.markProcessed(event2, source);

        // Try to process event 1 again (fails, goes backward)
        var event1Again = createEvent(1L, currentTime, "e1");
        assertFalse(preserver.canProcess(event1Again, source),
                   "Should reject event with lower clock");

        assertEquals(1L, preserver.getTotalRejected(), "Should have 1 rejection");
    }

    /**
     * Test: Idempotent replay (same clock reprocessed) is allowed.
     */
    @Test
    void testIdempotentReplay() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Process event with clock 5
        var event = createEvent(5L, currentTime, "entity1");
        assertTrue(preserver.canProcess(event, source));
        preserver.markProcessed(event, source);

        assertEquals(1L, preserver.getTotalProcessed(), "Should have 1 processed");

        // Reprocess same event (idempotent)
        var eventAgain = createEvent(5L, currentTime, "entity1");
        assertTrue(preserver.canProcess(eventAgain, source),
                  "Should allow idempotent replay of same clock");
        preserver.markProcessed(eventAgain, source);

        assertEquals(1L, preserver.getTotalProcessed(), "Processed should not increase");
        assertEquals(1L, preserver.getTotalIdempotent(), "Should have 1 idempotent");
    }

    /**
     * Test: Higher clock after lower clock is OK (monotonic increase).
     */
    @Test
    void testMonotonicIncrease() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Process clock 1
        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source);

        // Process clock 5 (skip ahead, still OK)
        var event5 = createEvent(5L, currentTime, "e5");
        assertTrue(preserver.canProcess(event5, source),
                  "Should allow clock jump forward");
        preserver.markProcessed(event5, source);

        assertEquals(2L, preserver.getTotalProcessed(), "Should have 2 processed");
        assertEquals(0L, preserver.getTotalRejected(), "Should have no rejections");
    }

    /**
     * Test: Multiple sources tracked independently.
     */
    @Test
    void testMultipleSources() {
        var preserver = new CausalityPreserver();
        var source1 = UUID.randomUUID();
        var source2 = UUID.randomUUID();
        var source3 = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Source1: clocks 1, 2, 3
        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source1);
        preserver.markProcessed(createEvent(2L, currentTime, "e2"), source1);
        preserver.markProcessed(createEvent(3L, currentTime, "e3"), source1);

        // Source2: clocks 5, 10
        preserver.markProcessed(createEvent(5L, currentTime, "e5"), source2);
        preserver.markProcessed(createEvent(10L, currentTime, "e10"), source2);

        // Source3: clock 1
        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source3);

        assertEquals(3, preserver.getSourceCount(), "Should have 3 sources");
        assertEquals(3L, preserver.getProcessedClock(source1), "Source1 should be at clock 3");
        assertEquals(10L, preserver.getProcessedClock(source2), "Source2 should be at clock 10");
        assertEquals(1L, preserver.getProcessedClock(source3), "Source3 should be at clock 1");
    }

    /**
     * Test: Unknown source returns -1 for processed clock.
     */
    @Test
    void testUnknownSource() {
        var preserver = new CausalityPreserver();
        var unknownSource = UUID.randomUUID();

        assertEquals(-1L, preserver.getProcessedClock(unknownSource),
                    "Unknown source should return -1");
        assertFalse(preserver.hasSeenSource(unknownSource), "Should not have seen unknown source");
    }

    /**
     * Test: Consistency flag reflects rejection state.
     */
    @Test
    void testConsistency() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Start consistent
        assertTrue(preserver.isConsistent(), "Should be consistent initially");

        // Process some events
        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source);
        preserver.markProcessed(createEvent(2L, currentTime, "e2"), source);
        assertTrue(preserver.isConsistent(), "Should stay consistent with sequential events");

        // Attempt out-of-order (causes rejection)
        var oldEvent = createEvent(1L, currentTime, "e1");
        preserver.canProcess(oldEvent, source);  // Returns false, increments rejection

        assertFalse(preserver.isConsistent(), "Should not be consistent after rejection");
    }

    /**
     * Test: Reset clears all state.
     */
    @Test
    void testReset() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Add history
        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source);
        preserver.markProcessed(createEvent(2L, currentTime, "e2"), source);
        assertEquals(1, preserver.getSourceCount(), "Should have 1 source");

        // Reset
        preserver.reset();

        assertEquals(0, preserver.getSourceCount(), "Should have no sources after reset");
        assertEquals(0L, preserver.getTotalProcessed(), "Processed should be 0");
        assertTrue(preserver.isConsistent(), "Should be consistent after reset");
    }

    /**
     * Test: Clear specific source.
     */
    @Test
    void testClearSource() {
        var preserver = new CausalityPreserver();
        var source1 = UUID.randomUUID();
        var source2 = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source1);
        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source2);

        assertEquals(2, preserver.getSourceCount());

        preserver.clearSource(source1);

        assertEquals(1, preserver.getSourceCount(), "Should have 1 source after clear");
        assertEquals(-1L, preserver.getProcessedClock(source1), "Cleared source should be -1");
        assertEquals(1L, preserver.getProcessedClock(source2), "Other source should remain");
    }

    /**
     * Test: Initialize with prior history.
     */
    @Test
    void testInitializeWithHistory() {
        var preserver = new CausalityPreserver();
        var source1 = UUID.randomUUID();
        var source2 = UUID.randomUUID();

        var history = java.util.Map.of(
            source1, 5L,
            source2, 10L
        );

        preserver.initializeWithHistory(history);

        assertEquals(2, preserver.getSourceCount(), "Should have 2 sources");
        assertEquals(5L, preserver.getProcessedClock(source1));
        assertEquals(10L, preserver.getProcessedClock(source2));
    }

    /**
     * Test: Get known sources.
     */
    @Test
    void testGetKnownSources() {
        var preserver = new CausalityPreserver();
        var source1 = UUID.randomUUID();
        var source2 = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source1);
        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source2);

        var sources = preserver.getKnownSources();
        assertEquals(2, sources.size());
        assertTrue(sources.contains(source1));
        assertTrue(sources.contains(source2));
    }

    /**
     * Test: Get processed clocks snapshot.
     */
    @Test
    void testGetProcessedClocks() {
        var preserver = new CausalityPreserver();
        var source1 = UUID.randomUUID();
        var source2 = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        preserver.markProcessed(createEvent(5L, currentTime, "e5"), source1);
        preserver.markProcessed(createEvent(10L, currentTime, "e10"), source2);

        var clocks = preserver.getProcessedClocks();
        assertEquals(2, clocks.size());
        assertEquals(5L, clocks.get(source1));
        assertEquals(10L, clocks.get(source2));

        // Verify immutability
        assertThrows(UnsupportedOperationException.class,
                    () -> clocks.put(UUID.randomUUID(), 999L),
                    "Should return unmodifiable map");
    }

    /**
     * Test: Has seen source query.
     */
    @Test
    void testHasSeenSource() {
        var preserver = new CausalityPreserver();
        var source1 = UUID.randomUUID();
        var source2 = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        assertFalse(preserver.hasSeenSource(source1), "Should not have seen source1");

        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source1);

        assertTrue(preserver.hasSeenSource(source1), "Should have seen source1");
        assertFalse(preserver.hasSeenSource(source2), "Should not have seen source2");
    }

    /**
     * Test: Concurrent processing from multiple sources.
     */
    @Test
    void testConcurrentProcessing() throws InterruptedException {
        var preserver = new CausalityPreserver();
        var sourceCount = 4;
        var eventsPerSource = 25;
        var executor = Executors.newFixedThreadPool(sourceCount);
        var latch = new CountDownLatch(sourceCount);

        for (int s = 0; s < sourceCount; s++) {
            final int sourceIndex = s;
            executor.submit(() -> {
                try {
                    var source = UUID.randomUUID();
                    var currentTime = System.currentTimeMillis();

                    for (int e = 1; e <= eventsPerSource; e++) {
                        var event = createEvent(e, currentTime, "e" + e);
                        if (preserver.canProcess(event, source)) {
                            preserver.markProcessed(event, source);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(sourceCount, preserver.getSourceCount(), "Should have all sources");
        assertEquals(sourceCount * eventsPerSource, preserver.getTotalProcessed(),
                    "Should process all events");
    }

    /**
     * Test: Metrics accumulation.
     */
    @Test
    void testMetrics() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Process 5 sequential events
        for (int i = 1; i <= 5; i++) {
            var event = createEvent(i, currentTime, "e" + i);
            preserver.canProcess(event, source);
            preserver.markProcessed(event, source);
        }

        // Replay 2 of them
        preserver.markProcessed(createEvent(2L, currentTime, "e2"), source);
        preserver.markProcessed(createEvent(3L, currentTime, "e3"), source);

        // Reject 1 out-of-order
        preserver.canProcess(createEvent(1L, currentTime, "e1"), source);

        assertEquals(5L, preserver.getTotalProcessed(), "Should have 5 first-time processed");
        assertEquals(2L, preserver.getTotalIdempotent(), "Should have 2 idempotent");
        assertEquals(1L, preserver.getTotalRejected(), "Should have 1 rejected");
    }

    /**
     * Test: toString includes state.
     */
    @Test
    void testToString() {
        var preserver = new CausalityPreserver();

        var str = preserver.toString();
        assertNotNull(str);
        assertTrue(str.contains("CausalityPreserver"), "Should contain class name");
        assertTrue(str.contains("sources="), "Should show source count");
        assertTrue(str.contains("consistent"), "Should show consistency");
    }

    /**
     * Test: Processed clocks are unmodifiable.
     */
    @Test
    void testProcessedClocksUnmodifiable() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source);

        var clocks = preserver.getProcessedClocks();

        assertThrows(UnsupportedOperationException.class,
                    () -> clocks.put(UUID.randomUUID(), 1L),
                    "Should be unmodifiable");
    }

    /**
     * Test: Null source throws exception.
     */
    @Test
    void testNullSourceThrows() {
        var preserver = new CausalityPreserver();
        var event = createEvent(1L, System.currentTimeMillis(), "e1");

        assertThrows(NullPointerException.class,
                    () -> preserver.canProcess(event, null),
                    "Should throw on null source");
    }

    /**
     * Test: Null event throws exception.
     */
    @Test
    void testNullEventThrows() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();

        assertThrows(NullPointerException.class,
                    () -> preserver.canProcess(null, source),
                    "Should throw on null event");
    }

    /**
     * Test: Large clock values handled correctly.
     */
    @Test
    void testLargeClockValues() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        long largeClockValue = Long.MAX_VALUE - 1000;

        var event = createEvent(largeClockValue, currentTime, "e1");
        assertTrue(preserver.canProcess(event, source), "Should handle large clock values");

        preserver.markProcessed(event, source);

        assertEquals(largeClockValue, preserver.getProcessedClock(source),
                    "Should track large clock values");
    }

    /**
     * Test: Skip to maximum clock value.
     */
    @Test
    void testSkipToMaxClock() {
        var preserver = new CausalityPreserver();
        var source = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Start at clock 1
        preserver.markProcessed(createEvent(1L, currentTime, "e1"), source);

        // Jump to max (monotonic, allowed)
        var maxEvent = createEvent(Long.MAX_VALUE, currentTime, "emax");
        assertTrue(preserver.canProcess(maxEvent, source), "Should allow monotonic jump to max");

        preserver.markProcessed(maxEvent, source);

        assertEquals(Long.MAX_VALUE, preserver.getProcessedClock(source));
    }
}

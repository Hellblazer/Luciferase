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
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for EventReprocessor (Phase 7C.2)
 *
 * Tests queue behavior, lookahead window logic, metrics,
 * and event ordering with bounded reprocessing.
 *
 * @author hal.hildebrand
 */
class EventReprocessorTest {

    private static final long MIN_LOOKAHEAD_MS = 100;
    private static final long MAX_LOOKAHEAD_MS = 500;

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
     * Test: Reprocessor initializes with empty queue.
     */
    @Test
    void testInitialization() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);

        assertEquals(0, reprocessor.getQueueDepth(), "Queue should be empty");
        assertEquals(0L, reprocessor.getTotalDropped(), "Should have no drops");
        assertEquals(0L, reprocessor.getTotalReprocessed(), "Should have no reprocessed");
        assertTrue(reprocessor.isHealthy(), "Should be healthy");
    }

    /**
     * Test: Queue event and verify depth increases.
     */
    @Test
    void testQueueEvent() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var sourceBubble = UUID.randomUUID();

        var event = createEvent(1L, System.currentTimeMillis(), "entity1");
        boolean queued = reprocessor.queueEvent(event);

        assertTrue(queued, "Event should queue successfully");
        assertEquals(1, reprocessor.getQueueDepth(), "Queue depth should be 1");
    }

    /**
     * Test: Queue multiple events and verify ordering by Lamport clock.
     */
    @Test
    void testQueueOrdering() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Queue events out of order
        reprocessor.queueEvent(createEvent(3L, currentTime, "e1"));
        reprocessor.queueEvent(createEvent(1L, currentTime, "e2"));
        reprocessor.queueEvent(createEvent(2L, currentTime, "e3"));

        assertEquals(3, reprocessor.getQueueDepth(), "Should have 3 events");

        // Get pending events (should be sorted)
        var pending = reprocessor.getPendingEvents();
        assertEquals(1L, pending.get(0).lamportClock(), "First should have clock=1");
        assertEquals(2L, pending.get(1).lamportClock(), "Second should have clock=2");
        assertEquals(3L, pending.get(2).lamportClock(), "Third should have clock=3");
    }

    /**
     * Test: Process event before lookahead window expires (not ready).
     */
    @Test
    void testProcessBeforeLookahead() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        var event = createEvent(1L, currentTime, "entity1");
        reprocessor.queueEvent(event);

        // Try to process immediately (too early)
        var count = reprocessor.processReady(currentTime, e -> {});

        assertEquals(0, count, "Should not process before min lookahead");
        assertEquals(1, reprocessor.getQueueDepth(), "Event should remain in queue");
    }

    /**
     * Test: Process event after lookahead window (ready).
     */
    @Test
    void testProcessAfterLookahead() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        var event = createEvent(1L, currentTime, "entity1");
        reprocessor.queueEvent(event);

        // Process after min lookahead (add 150ms, so 50ms past min)
        var count = reprocessor.processReady(currentTime + 150, e -> {});

        assertEquals(1, count, "Should process after min lookahead");
        assertEquals(0, reprocessor.getQueueDepth(), "Queue should be empty");
        assertEquals(1L, reprocessor.getTotalReprocessed(), "Should have 1 reprocessed");
    }

    /**
     * Test: Process with callback tracks processed events.
     */
    @Test
    void testProcessWithCallback() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();
        var processedEvents = new ArrayList<EntityUpdateEvent>();

        reprocessor.queueEvent(createEvent(1L, currentTime, "e1"));
        reprocessor.queueEvent(createEvent(2L, currentTime, "e2"));

        reprocessor.processReady(currentTime + 150, e -> processedEvents.add(e));

        assertEquals(2, processedEvents.size(), "Should process both events");
        assertEquals("e1", processedEvents.get(0).entityId().toString(), "First should be e1");
        assertEquals("e2", processedEvents.get(1).entityId().toString(), "Second should be e2");
    }

    /**
     * Test: Queue overflow drops events beyond max size.
     */
    @Test
    void testQueueOverflow() {
        var config = new EventReprocessor.Configuration(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS, 3);
        var reprocessor = new EventReprocessor(config);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Fill queue to max
        assertTrue(reprocessor.queueEvent(createEvent(1L, currentTime, "e1")));
        assertTrue(reprocessor.queueEvent(createEvent(2L, currentTime, "e2")));
        assertTrue(reprocessor.queueEvent(createEvent(3L, currentTime, "e3")));

        // Next event should be dropped
        assertFalse(reprocessor.queueEvent(createEvent(4L, currentTime, "e4")),
                   "Event should be dropped on overflow");

        assertEquals(3, reprocessor.getQueueDepth(), "Queue should remain at max");
        assertEquals(1L, reprocessor.getTotalDropped(), "Should have 1 drop");
        assertFalse(reprocessor.isHealthy(), "Should not be healthy with drops");
    }

    /**
     * Test: Force-process events after max lookahead window.
     */
    @Test
    void testForceProcessMaxWindow() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        var event = createEvent(1L, currentTime, "entity1");
        reprocessor.queueEvent(event);

        // Process after max lookahead (add 600ms, so 100ms past max)
        var count = reprocessor.processReady(currentTime + 600, e -> {});

        assertEquals(1, count, "Should force-process after max lookahead");
        assertEquals(0, reprocessor.getQueueDepth(), "Queue should be empty");
    }

    /**
     * Test: Clear queue removes all events.
     */
    @Test
    void testClear() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        reprocessor.queueEvent(createEvent(1L, currentTime, "e1"));
        reprocessor.queueEvent(createEvent(2L, currentTime, "e2"));

        assertEquals(2, reprocessor.getQueueDepth(), "Should have 2 events");

        reprocessor.clear();

        assertEquals(0, reprocessor.getQueueDepth(), "Queue should be empty after clear");
    }

    /**
     * Test: Multiple events from same entity tracked correctly.
     */
    @Test
    void testMultipleEventsPerEntity() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Same entity, different clocks
        reprocessor.queueEvent(createEvent(1L, currentTime, "e1"));
        reprocessor.queueEvent(createEvent(2L, currentTime, "e1"));

        assertEquals(2, reprocessor.getQueueDepth(), "Should queue both");

        var pending = reprocessor.getPendingEvents();
        assertEquals("e1", pending.get(0).entityId().toString());
        assertEquals("e1", pending.get(1).entityId().toString());
    }

    /**
     * Test: Process events from multiple sources (all included in ordering).
     */
    @Test
    void testMultipleSources() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var source1 = UUID.randomUUID();
        var source2 = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        reprocessor.queueEvent(createEvent(3L, currentTime, "e1"));
        reprocessor.queueEvent(createEvent(1L, currentTime, "e2"));
        reprocessor.queueEvent(createEvent(2L, currentTime, "e3"));

        var pending = reprocessor.getPendingEvents();
        assertEquals(1L, pending.get(0).lamportClock(), "Should sort by clock regardless of source");
        assertEquals(2L, pending.get(1).lamportClock());
        assertEquals(3L, pending.get(2).lamportClock());
    }

    /**
     * Test: Window calculation for time-based lookahead.
     */
    @Test
    void testWindowCalculation() {
        var reprocessor = new EventReprocessor(100, 500);
        var sourceBubble = UUID.randomUUID();
        var baseTime = System.currentTimeMillis();

        // Event arrives at time T
        reprocessor.queueEvent(createEvent(1L, baseTime, "e1"));

        // At T+50ms, not ready (before min window)
        var count1 = reprocessor.processReady(baseTime + 50, e -> {});
        assertEquals(0, count1, "Not ready before min window");

        // At T+150ms, ready (after min window)
        var count2 = reprocessor.processReady(baseTime + 150, e -> {});
        assertEquals(1, count2, "Ready after min window");
    }

    /**
     * Test: Health status reflects drops and queue state.
     */
    @Test
    void testHealthStatus() {
        var config = new EventReprocessor.Configuration(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS, 2);
        var reprocessor = new EventReprocessor(config);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        // Start healthy
        assertTrue(reprocessor.isHealthy(), "Should be healthy initially");

        // Fill queue to exactly half (still healthy at 50% capacity)
        reprocessor.queueEvent(createEvent(1L, currentTime, "e1"));
        assertTrue(reprocessor.isHealthy(), "Should be healthy at 50% capacity");

        // Exceed half (not healthy)
        reprocessor.queueEvent(createEvent(2L, currentTime, "e2"));
        assertFalse(reprocessor.isHealthy(), "Should not be healthy when exceeding 50% capacity");

        // Try to add another (will drop)
        assertFalse(reprocessor.queueEvent(createEvent(3L, currentTime, "e3")),
                   "Should drop event on queue overflow");
        assertFalse(reprocessor.isHealthy(), "Should not be healthy after drop");
    }

    /**
     * Test: toString includes state information.
     */
    @Test
    void testToString() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);

        var str = reprocessor.toString();
        assertNotNull(str);
        assertTrue(str.contains("EventReprocessor"), "Should contain class name");
        assertTrue(str.contains("queue="), "Should show queue info");
    }

    /**
     * Test: Process respects Lamport clock ordering strictly.
     */
    @Test
    void testLamportOrdering() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();
        var processedClocks = new ArrayList<Long>();

        // Queue in random order
        for (long clock : new long[]{5, 2, 8, 1, 3}) {
            reprocessor.queueEvent(createEvent(clock, currentTime, "e" + clock));
        }

        // Process after lookahead
        reprocessor.processReady(currentTime + 150, e -> processedClocks.add(e.lamportClock()));

        // Should be strictly sorted
        assertEquals(5, processedClocks.size());
        assertEquals(1L, processedClocks.get(0));
        assertEquals(2L, processedClocks.get(1));
        assertEquals(3L, processedClocks.get(2));
        assertEquals(5L, processedClocks.get(3));
        assertEquals(8L, processedClocks.get(4));
    }

    /**
     * Test: Configuration object stores settings.
     */
    @Test
    void testConfiguration() {
        var config = new EventReprocessor.Configuration(100, 500, 2000);

        assertEquals(100, config.minLookaheadMs);
        assertEquals(500, config.maxLookaheadMs);
        assertEquals(2000, config.maxQueueSize);
    }

    /**
     * Test: Process with null processor throws NullPointerException.
     */
    @Test
    void testNullProcessorThrows() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);

        assertThrows(NullPointerException.class, () -> reprocessor.processReady(System.currentTimeMillis(), null),
                    "Should throw on null processor");
    }

    /**
     * Test: Queue with null event throws NullPointerException.
     */
    @Test
    void testQueueNullEventThrows() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);

        assertThrows(NullPointerException.class, () -> reprocessor.queueEvent(null),
                    "Should throw on null event");
    }

    /**
     * Test: Pending events returns snapshot (not live queue).
     */
    @Test
    void testPendingEventsSnapshot() {
        var reprocessor = new EventReprocessor(MIN_LOOKAHEAD_MS, MAX_LOOKAHEAD_MS);
        var sourceBubble = UUID.randomUUID();
        var currentTime = System.currentTimeMillis();

        reprocessor.queueEvent(createEvent(1L, currentTime, "e1"));

        var snapshot = reprocessor.getPendingEvents();
        assertEquals(1, snapshot.size(), "Snapshot should have 1 event");

        // Queue more
        reprocessor.queueEvent(createEvent(2L, currentTime, "e2"));

        // Snapshot should still have 1 (it's a snapshot)
        assertEquals(1, snapshot.size(), "Snapshot should not change");

        // But reprocessor should have 2
        assertEquals(2, reprocessor.getQueueDepth(), "Reprocessor should have 2");
    }
}

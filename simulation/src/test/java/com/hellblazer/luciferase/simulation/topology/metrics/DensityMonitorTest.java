/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.topology.metrics;

import com.hellblazer.luciferase.simulation.distributed.integration.SeededUuidSupplier;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import com.hellblazer.luciferase.simulation.topology.events.DensityStateChangeEvent;
import com.hellblazer.luciferase.simulation.topology.events.TopologyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DensityMonitor including deterministic time handling.
 *
 * @author hal.hildebrand
 */
class DensityMonitorTest {

    private static final int SPLIT_THRESHOLD = 5000;
    private static final int MERGE_THRESHOLD = 500;

    private DensityMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new DensityMonitor(SPLIT_THRESHOLD, MERGE_THRESHOLD);
    }

    @Test
    void testBasicStateTransitions() {
        var bubbleId = UUID.randomUUID();

        // Start below merge threshold - should transition to NEEDS_MERGE
        monitor.update(Map.of(bubbleId, 100));
        assertEquals(DensityState.NEEDS_MERGE, monitor.getState(bubbleId));

        // Above merge threshold but below approaching merge - should stay NEEDS_MERGE (hysteresis)
        monitor.update(Map.of(bubbleId, 520));
        assertEquals(DensityState.NEEDS_MERGE, monitor.getState(bubbleId));

        // Above approaching merge threshold - should transition to APPROACHING_MERGE
        monitor.update(Map.of(bubbleId, 600));
        assertEquals(DensityState.APPROACHING_MERGE, monitor.getState(bubbleId));

        // Above split threshold - should transition to NEEDS_SPLIT
        monitor.update(Map.of(bubbleId, 5500));
        assertEquals(DensityState.NEEDS_SPLIT, monitor.getState(bubbleId));
    }

    @Test
    void testDeterministicEventTimestamps() {
        // Test that DensityMonitor produces deterministic event timestamps when using TestClock
        var testClock = new TestClock(1000L);
        monitor.setClock(testClock);

        var bubbleId = UUID.randomUUID();
        List<TopologyEvent> events = new ArrayList<>();
        monitor.addListener(events::add);

        // Trigger a state change by going from NORMAL to NEEDS_MERGE
        monitor.update(Map.of(bubbleId, 100));

        // Verify we got an event
        assertEquals(1, events.size(), "Should have captured one event");

        // Verify event has deterministic timestamp from TestClock
        var event = events.get(0);
        assertTrue(event instanceof DensityStateChangeEvent, "Event should be DensityStateChangeEvent");
        var densityEvent = (DensityStateChangeEvent) event;
        assertEquals(1000L, densityEvent.timestamp(), "Event timestamp should match TestClock time");
    }

    @Test
    void testDeterministicEventTimestampsReproducible() {
        // Run same scenario twice, verify identical timestamps
        long fixedTime = 5000L;

        // First run
        var monitor1 = new DensityMonitor(SPLIT_THRESHOLD, MERGE_THRESHOLD);
        var testClock1 = new TestClock(fixedTime);
        monitor1.setClock(testClock1);

        var bubbleId1 = UUID.randomUUID();
        List<TopologyEvent> events1 = new ArrayList<>();
        monitor1.addListener(events1::add);
        monitor1.update(Map.of(bubbleId1, 100));

        // Second run with same clock time
        var monitor2 = new DensityMonitor(SPLIT_THRESHOLD, MERGE_THRESHOLD);
        var testClock2 = new TestClock(fixedTime);
        monitor2.setClock(testClock2);

        var bubbleId2 = UUID.randomUUID();
        List<TopologyEvent> events2 = new ArrayList<>();
        monitor2.addListener(events2::add);
        monitor2.update(Map.of(bubbleId2, 100));

        // Verify both runs produced same timestamps
        assertEquals(1, events1.size(), "Run 1 should have one event");
        assertEquals(1, events2.size(), "Run 2 should have one event");

        var timestamp1 = ((DensityStateChangeEvent) events1.get(0)).timestamp();
        var timestamp2 = ((DensityStateChangeEvent) events2.get(0)).timestamp();

        assertEquals(timestamp1, timestamp2, "Timestamps should be identical across runs with same clock time");
        assertEquals(fixedTime, timestamp1, "Timestamp should match fixed time");
    }

    @Test
    void testClockAdvanceAffectsTimestamps() {
        var testClock = new TestClock(1000L);
        monitor.setClock(testClock);

        var bubbleId = UUID.randomUUID();
        List<TopologyEvent> events = new ArrayList<>();
        monitor.addListener(events::add);

        // First state change
        monitor.update(Map.of(bubbleId, 100)); // NORMAL -> NEEDS_MERGE
        assertEquals(1, events.size());
        assertEquals(1000L, ((DensityStateChangeEvent) events.get(0)).timestamp());

        // Advance clock
        testClock.advance(500);

        // Trigger another state change
        monitor.update(Map.of(bubbleId, 600)); // NEEDS_MERGE -> APPROACHING_MERGE
        assertEquals(2, events.size());
        assertEquals(1500L, ((DensityStateChangeEvent) events.get(1)).timestamp());
    }

    @Test
    void testSetClockNullThrows() {
        assertThrows(NullPointerException.class, () -> {
            monitor.setClock(null);
        }, "Should reject null clock");
    }

    @Test
    void testDeterministicEventIds() {
        // Test that DensityMonitor produces deterministic event IDs when using SeededUuidSupplier
        long seed = 12345L;
        var uuidSupplier = new SeededUuidSupplier(seed);
        monitor.setUuidSupplier(uuidSupplier);

        var bubbleId = UUID.randomUUID();
        List<TopologyEvent> events = new ArrayList<>();
        monitor.addListener(events::add);

        // Trigger a state change
        monitor.update(Map.of(bubbleId, 100)); // NORMAL -> NEEDS_MERGE

        // Verify we got an event
        assertEquals(1, events.size(), "Should have captured one event");

        // Capture the event ID
        var eventId = events.get(0).eventId();
        assertNotNull(eventId, "Event ID should not be null");

        // Create a new supplier with same seed and verify same ID is generated
        var uuidSupplier2 = new SeededUuidSupplier(seed);
        assertEquals(uuidSupplier2.get(), eventId, "Event ID should match seeded UUID supplier output");
    }

    @Test
    void testDeterministicEventIdsReproducible() {
        // Run same scenario twice, verify identical event IDs
        long seed = 99999L;

        // First run
        var monitor1 = new DensityMonitor(SPLIT_THRESHOLD, MERGE_THRESHOLD);
        var uuidSupplier1 = new SeededUuidSupplier(seed);
        monitor1.setUuidSupplier(uuidSupplier1);

        var bubbleId1 = UUID.randomUUID();
        List<TopologyEvent> events1 = new ArrayList<>();
        monitor1.addListener(events1::add);
        monitor1.update(Map.of(bubbleId1, 100));

        // Second run with same seed
        var monitor2 = new DensityMonitor(SPLIT_THRESHOLD, MERGE_THRESHOLD);
        var uuidSupplier2 = new SeededUuidSupplier(seed);
        monitor2.setUuidSupplier(uuidSupplier2);

        var bubbleId2 = UUID.randomUUID();
        List<TopologyEvent> events2 = new ArrayList<>();
        monitor2.addListener(events2::add);
        monitor2.update(Map.of(bubbleId2, 100));

        // Verify both runs produced same event IDs
        assertEquals(1, events1.size(), "Run 1 should have one event");
        assertEquals(1, events2.size(), "Run 2 should have one event");

        var eventId1 = events1.get(0).eventId();
        var eventId2 = events2.get(0).eventId();

        assertEquals(eventId1, eventId2, "Event IDs should be identical across runs with same seed");
    }

    @Test
    void testSetUuidSupplierNullThrows() {
        assertThrows(NullPointerException.class, () -> {
            monitor.setUuidSupplier(null);
        }, "Should reject null UUID supplier");
    }
}

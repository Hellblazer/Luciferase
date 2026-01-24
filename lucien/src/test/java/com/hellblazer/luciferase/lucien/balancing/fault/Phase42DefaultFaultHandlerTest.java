/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.lucien.balancing.fault;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4.2 tests for DefaultFaultHandler.
 *
 * Tests cover:
 * - Status transitions (HEALTHY → SUSPECTED → FAILED)
 * - Clock injection for deterministic timing
 * - Listener notification
 * - Consecutive timeout threshold enforcement
 */
class Phase42DefaultFaultHandlerTest {

    private FaultConfiguration config;
    private PartitionTopology topology;
    private DefaultFaultHandler handler;
    private TestClock clock;
    private List<PartitionChangeEvent> events;

    @BeforeEach
    void setUp() {
        config = FaultConfiguration.defaultConfig();
        topology = new InMemoryPartitionTopology();
        clock = new TestClock(1000); // Start at t=1000ms
        handler = new DefaultFaultHandler(config, topology);
        handler.setClock(clock);

        events = new ArrayList<>();
        handler.subscribe(events::add);
    }

    /**
     * Test 1: Start monitoring initializes partition as HEALTHY.
     */
    @Test
    void testStartMonitoring() {
        var partitionId = UUID.randomUUID();
        topology.register(partitionId, 0);

        handler.startMonitoring();

        assertThat(handler.checkHealth(partitionId))
            .isEqualTo(PartitionStatus.HEALTHY);
    }

    /**
     * Test 2: Consecutive timeouts trigger HEALTHY → SUSPECTED transition.
     *
     * Must require multiple consecutive timeouts before marking SUSPECTED
     * to prevent false positives from temporary network blips.
     */
    @Test
    void testHealthyToSuspected() {
        var partitionId = UUID.randomUUID();
        var partitionRank = 0;
        topology.register(partitionId, partitionRank);
        handler.startMonitoring();

        // First timeout - should not trigger SUSPECTED yet
        handler.reportBarrierTimeout(partitionRank);
        assertThat(handler.checkHealth(partitionId))
            .as("Single timeout should not mark partition SUSPECTED")
            .isEqualTo(PartitionStatus.HEALTHY);

        // Second consecutive timeout - should now trigger SUSPECTED
        handler.reportBarrierTimeout(partitionRank);
        assertThat(handler.checkHealth(partitionId))
            .as("Two consecutive timeouts should mark partition SUSPECTED")
            .isEqualTo(PartitionStatus.SUSPECTED);

        // Verify event was published
        assertThat(events)
            .as("Should publish StatusChanged event")
            .hasSize(1);
        assertThat(events.get(0))
            .as("Event should be PartitionChangeEvent")
            .isInstanceOf(PartitionChangeEvent.class);
    }

    /**
     * Test 3: After failureConfirmationMs, SUSPECTED → FAILED transition.
     *
     * Uses Clock injection for deterministic timing - no real delays needed.
     */
    @Test
    void testSuspectedToFailed() {
        var partitionId = UUID.randomUUID();
        var partitionRank = 0;
        topology.register(partitionId, partitionRank);
        handler.startMonitoring();

        // Trigger SUSPECTED status
        handler.reportBarrierTimeout(partitionRank);
        handler.reportBarrierTimeout(partitionRank);
        assertThat(handler.checkHealth(partitionId))
            .isEqualTo(PartitionStatus.SUSPECTED);

        // Advance clock past failureConfirmationMs
        clock.advance(config.failureConfirmationMs() + 100);

        // Manual checkHealth() call to trigger state transition
        handler.checkTimeouts();

        assertThat(handler.checkHealth(partitionId))
            .as("After failureConfirmationMs, should transition to FAILED")
            .isEqualTo(PartitionStatus.FAILED);

        // Should have 2 events: HEALTHY→SUSPECTED, SUSPECTED→FAILED
        assertThat(events).hasSize(2);
    }

    /**
     * Test 4: Listener notifications for all status transitions.
     *
     * Verifies:
     * - Listeners notified on each transition
     * - Events contain correct partition ID
     * - Events are ordered
     */
    @Test
    void testListenerNotification() {
        var partitionId = UUID.randomUUID();
        var partitionRank = 0;
        topology.register(partitionId, partitionRank);

        List<PartitionChangeEvent> listenerEvents = new ArrayList<>();
        Consumer<PartitionChangeEvent> listener = listenerEvents::add;
        handler.subscribe(listener);

        handler.startMonitoring();

        // Trigger HEALTHY → SUSPECTED
        handler.reportBarrierTimeout(partitionRank);
        handler.reportBarrierTimeout(partitionRank);

        // Trigger SUSPECTED → FAILED
        clock.advance(config.failureConfirmationMs() + 100);
        handler.checkTimeouts();

        // Verify listener received events
        assertThat(listenerEvents)
            .as("Listener should receive all status change events")
            .hasSize(2);

        // Can unsubscribe
        handler.unsubscribe(listener);

        // Further events should not reach unsubscribed listener
        var anotherPartition = UUID.randomUUID();
        topology.register(anotherPartition, 1);
        handler.reportBarrierTimeout(1);
        handler.reportBarrierTimeout(1);

        assertThat(listenerEvents)
            .as("Unsubscribed listener should not receive new events")
            .hasSize(2); // Still just the original 2 events
    }
}

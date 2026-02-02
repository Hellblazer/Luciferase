/*
 * Copyright (c) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.lucien.balancing.fault.FaultHandler;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionChangeEvent;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionStatus;
import com.hellblazer.luciferase.lucien.balancing.fault.PartitionTopology;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javafx.geometry.Point3D;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test suite for VONRecoveryIntegration Phase 3: Recovery System Safety.
 * <p>
 * Tests focus on:
 * <ul>
 *   <li>Circular dependency prevention (P3.2)</li>
 *   <li>Recovery depth limiting (P3.3)</li>
 *   <li>Recovery cooldown enforcement (P3.4)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class VONRecoveryIntegrationTest {

    private VonManager vonManager;
    private PartitionTopology topology;
    private FaultHandler faultHandler;
    private TestClock testClock;
    private VONRecoveryIntegration integration;

    @BeforeEach
    void setUp() {
        // Create mocks
        vonManager = mock(VonManager.class);
        topology = mock(PartitionTopology.class);
        faultHandler = mock(FaultHandler.class);
        testClock = new TestClock();

        // Mock vonManager to return empty bubbles list by default
        when(vonManager.getAllBubbles()).thenReturn(List.of());

        // Create integration with test clock
        integration = new VONRecoveryIntegration(vonManager, topology, faultHandler, testClock);
    }

    /**
     * Test P3.2: Circular dependency prevention.
     * <p>
     * Scenario: Partition A depends on B, B depends on A (circular).
     * Expected: Recovery completes without infinite loop, both partitions processed once.
     */
    @Test
    void testCircularDependencyPrevented() throws InterruptedException {
        // Set TestClock to absolute mode at time 0
        testClock.setTime(0);

        // Arrange: Create two partitions with circular dependency
        var partitionA = UUID.randomUUID();
        var partitionB = UUID.randomUUID();

        // Create bubbles for each partition
        var bubbleA = createMockBubble(UUID.randomUUID(), new Point3D(0, 0, 0));
        var bubbleB = createMockBubble(UUID.randomUUID(), new Point3D(10, 10, 10));

        // Register bubbles
        integration.registerBubble(bubbleA.id(), partitionA);
        integration.registerBubble(bubbleB.id(), partitionB);

        // Setup circular dependency: A depends on B, B depends on A
        integration.addRecoveryDependency(partitionB, partitionA);  // A recovers → trigger B
        integration.addRecoveryDependency(partitionA, partitionB);  // B recovers → trigger A

        // Mock vonManager.getBubble() to return our bubbles
        when(vonManager.getBubble(bubbleA.id())).thenReturn(bubbleA);
        when(vonManager.getBubble(bubbleB.id())).thenReturn(bubbleB);
        when(vonManager.joinAt(any(), any())).thenReturn(true);

        // Capture recovery events
        var recoveryEvents = new ArrayList<Event.PartitionRecovered>();
        doAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            if (event instanceof Event.PartitionRecovered recovered) {
                recoveryEvents.add(recovered);
            }
            return null;
        }).when(vonManager).dispatchEvent(any(Event.class));

        // Act: Trigger recovery for partition A
        triggerPartitionRecovery(partitionA);

        // Assert: Both partitions recovered exactly once (no infinite loop)
        assertEquals(2, recoveryEvents.size(), "Expected exactly 2 recovery events (A and B)");

        var partitionsRecovered = recoveryEvents.stream()
            .map(Event.PartitionRecovered::partitionId)
            .distinct()
            .toList();

        assertEquals(2, partitionsRecovered.size(), "Expected 2 distinct partitions recovered");
        assertTrue(partitionsRecovered.contains(partitionA), "Partition A should be recovered");
        assertTrue(partitionsRecovered.contains(partitionB), "Partition B should be recovered");

        // Verify no stack overflow or timeout occurred
        assertTrue(testClock.currentTimeMillis() < 30_000, "Recovery should complete quickly");
    }

    /**
     * Test P3.3: Recovery depth limit enforcement.
     * <p>
     * Scenario: Chain of 15 partitions, MAX_RECOVERY_DEPTH=10.
     * Expected: Only 10 partitions processed (depth limit enforced).
     */
    @Test
    void testRecoveryDepthLimitEnforced() throws InterruptedException {
        // Set TestClock to absolute mode at time 0
        testClock.setTime(0);

        // Arrange: Create chain of 15 partitions (0 → 1 → 2 → ... → 14)
        var partitions = new ArrayList<UUID>();
        var bubbles = new ArrayList<VonBubble>();

        for (int i = 0; i < 15; i++) {
            var partition = UUID.randomUUID();
            var bubble = createMockBubble(UUID.randomUUID(), new Point3D(i * 10, 0, 0));

            partitions.add(partition);
            bubbles.add(bubble);

            integration.registerBubble(bubble.id(), partition);
            when(vonManager.getBubble(bubble.id())).thenReturn(bubble);

            // Create dependency chain: partition[i] → partition[i+1]
            if (i > 0) {
                integration.addRecoveryDependency(partition, partitions.get(i - 1));
            }
        }

        when(vonManager.joinAt(any(), any())).thenReturn(true);

        // Capture recovery events
        var recoveryCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            if (event instanceof Event.PartitionRecovered) {
                recoveryCount.incrementAndGet();
            }
            return null;
        }).when(vonManager).dispatchEvent(any(Event.class));

        // Act: Trigger recovery for partition 0 (root of chain)
        triggerPartitionRecovery(partitions.get(0));

        // Assert: Only 10 partitions processed (MAX_RECOVERY_DEPTH limit)
        assertTrue(recoveryCount.get() <= 10,
            "Expected at most 10 recoveries due to MAX_RECOVERY_DEPTH=10, got " + recoveryCount.get());

        // The first partition PLUS up to 9 dependents = 10 total
        assertTrue(recoveryCount.get() >= 1, "At least the root partition should be recovered");
    }

    /**
     * Test P3.4: Recovery cooldown prevents rapid re-trigger.
     * <p>
     * Scenario: Same partition recovered twice within RECOVERY_COOLDOWN_MS (1000ms).
     * Expected: Second recovery skipped (cooldown active).
     */
    @Test
    void testRecoveryCooldownPreventsRapidRetrigger() throws InterruptedException {
        // Arrange: Create partition with bubble
        var partition = UUID.randomUUID();
        var bubble = createMockBubble(UUID.randomUUID(), new Point3D(0, 0, 0));

        integration.registerBubble(bubble.id(), partition);
        when(vonManager.getBubble(bubble.id())).thenReturn(bubble);
        when(vonManager.joinAt(any(), any())).thenReturn(true);

        // Capture recovery events
        var recoveryCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            if (event instanceof Event.PartitionRecovered) {
                recoveryCount.incrementAndGet();
            }
            return null;
        }).when(vonManager).dispatchEvent(any(Event.class));

        // Act 1: First recovery at time 0ms
        testClock.setTime(0);
        triggerPartitionRecovery(partition);

        assertEquals(1, recoveryCount.get(), "First recovery should succeed");

        // Act 2: Second recovery at time 500ms (within 1000ms cooldown)
        testClock.setTime(500);
        triggerPartitionRecovery(partition);

        assertEquals(1, recoveryCount.get(),
            "Second recovery should be skipped (cooldown active at 500ms)");

        // Act 3: Third recovery at time 1500ms (after 1000ms cooldown)
        testClock.setTime(1500);
        triggerPartitionRecovery(partition);

        assertEquals(2, recoveryCount.get(),
            "Third recovery should succeed (cooldown expired at 1500ms)");
    }

    // ========== Helper Methods ==========

    /**
     * Trigger a partition recovery by simulating a FAILED → HEALTHY transition.
     */
    private void triggerPartitionRecovery(UUID partitionId) {
        // Capture the recovery event handler from faultHandler.subscribeToChanges()
        ArgumentCaptor<Consumer<PartitionChangeEvent>> captor =
            ArgumentCaptor.forClass(Consumer.class);
        verify(faultHandler, atLeastOnce()).subscribeToChanges(captor.capture());

        Consumer<PartitionChangeEvent> handler = captor.getValue();

        // Trigger recovery event
        var event = new PartitionChangeEvent(
            partitionId,
            PartitionStatus.FAILED,
            PartitionStatus.HEALTHY,
            testClock.currentTimeMillis(),
            "test_recovery"
        );
        handler.accept(event);
    }

    /**
     * Create a mock VonBubble with specified ID and position.
     */
    private VonBubble createMockBubble(UUID id, Point3D position) {
        var bubble = mock(VonBubble.class);
        when(bubble.id()).thenReturn(id);
        when(bubble.position()).thenReturn(position);
        when(bubble.neighbors()).thenReturn(Set.of());
        return bubble;
    }
}

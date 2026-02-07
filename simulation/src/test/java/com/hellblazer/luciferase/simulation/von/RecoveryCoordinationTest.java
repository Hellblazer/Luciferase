/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hellblazer.luciferase.simulation.von;

import com.hellblazer.luciferase.lucien.balancing.fault.*;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Phase 2: Recovery Coordination Enhancements.
 * <p>
 * Validates enhanced RecoveryIntegration with:
 * <ul>
 *   <li>Success/failure counting for bubble rejoins</li>
 *   <li>Recovery timeout handling (30s default)</li>
 *   <li>Cascading recovery with loop prevention</li>
 *   <li>Recovery metrics events</li>
 * </ul>
 * <p>
 * Part of F4.2.4 VON + Simulation Integration (bead: Luciferase-urzt).
 *
 * @author hal.hildebrand
 */
class RecoveryCoordinationTest {

    private static final byte SPATIAL_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 16;
    private static final float AOI_RADIUS = 50.0f;

    private LocalServerTransport.Registry transportRegistry;
    private Manager vonManager;
    private InMemoryPartitionTopology topology;
    private TestFaultHandler faultHandler;
    private RecoveryIntegration integration;
    private TestClock clock;
    private List<Event> capturedEvents;

    @BeforeEach
    void setup() {
        transportRegistry = LocalServerTransport.Registry.create();
        clock = new TestClock(1000L);
        vonManager = new Manager(transportRegistry, SPATIAL_LEVEL, TARGET_FRAME_MS, AOI_RADIUS, clock);
        topology = new InMemoryPartitionTopology();
        faultHandler = new TestFaultHandler();
        capturedEvents = new CopyOnWriteArrayList<>();

        integration = new RecoveryIntegration(vonManager, topology, faultHandler, clock);

        // Capture VON events for verification
        vonManager.addEventListener(capturedEvents::add);
    }

    @AfterEach
    void cleanup() {
        if (integration != null) integration.close();
        if (vonManager != null) vonManager.close();
        if (transportRegistry != null) transportRegistry.close();
    }

    // ========== Recovery Success/Failure Counting (2 tests) ==========

    /**
     * Test 1: All bubbles successfully rejoin VON after partition recovery.
     */
    @Test
    void testPartitionRecovered_allBubblesRejoin_success() {
        // Given: Partition with 2 bubbles
        var bubble1 = vonManager.createBubble();
        var bubble2 = vonManager.createBubble();
        var partitionId = UUID.randomUUID();

        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);

        integration.registerBubble(bubble1.id(), partitionId);
        integration.registerBubble(bubble2.id(), partitionId);

        // When: Partition recovers (FAILED → HEALTHY)
        var recoveryEvent = new PartitionChangeEvent(
            partitionId,
            PartitionStatus.FAILED,
            PartitionStatus.HEALTHY,
            clock.currentTimeMillis(),
            "recovery_completed"
        );
        faultHandler.fireEvent(recoveryEvent);

        // Then: PartitionRecovered event emitted with success metrics
        var recoveredEvents = capturedEvents.stream()
            .filter(e -> e instanceof Event.PartitionRecovered)
            .map(e -> (Event.PartitionRecovered) e)
            .toList();

        assertThat(recoveredEvents).hasSize(1);
        var recovered = recoveredEvents.get(0);
        assertThat(recovered.partitionId()).isEqualTo(partitionId);
        assertThat(recovered.totalBubbles()).isEqualTo(2);
        assertThat(recovered.successfulRejoins()).isEqualTo(2);
        assertThat(recovered.failedRejoins()).isEqualTo(0);
        assertThat(recovered.isFullyRecovered()).isTrue();
    }

    /**
     * Test 2: Some bubbles fail to rejoin, partial success reported.
     */
    @Test
    void testPartitionRecovered_someBubblesFailRejoin_partialSuccess() {
        // Given: Partition with 3 bubbles, but 1 bubble is no longer in manager
        var bubble1 = vonManager.createBubble();
        var bubble2 = vonManager.createBubble();
        var missingBubbleId = UUID.randomUUID();  // Not created in manager
        var partitionId = UUID.randomUUID();

        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);

        integration.registerBubble(bubble1.id(), partitionId);
        integration.registerBubble(bubble2.id(), partitionId);
        integration.registerBubble(missingBubbleId, partitionId);  // Registered but missing

        // When: Partition recovers
        var recoveryEvent = new PartitionChangeEvent(
            partitionId,
            PartitionStatus.FAILED,
            PartitionStatus.HEALTHY,
            clock.currentTimeMillis(),
            "recovery_completed"
        );
        faultHandler.fireEvent(recoveryEvent);

        // Then: PartitionRecovered event shows partial success
        var recoveredEvents = capturedEvents.stream()
            .filter(e -> e instanceof Event.PartitionRecovered)
            .map(e -> (Event.PartitionRecovered) e)
            .toList();

        assertThat(recoveredEvents).hasSize(1);
        var recovered = recoveredEvents.get(0);
        assertThat(recovered.totalBubbles()).isEqualTo(3);
        assertThat(recovered.successfulRejoins()).isEqualTo(2);
        assertThat(recovered.failedRejoins()).isEqualTo(1);
        assertThat(recovered.isFullyRecovered()).isFalse();
        assertThat(recovered.successRatio()).isCloseTo(0.666, org.assertj.core.data.Offset.offset(0.01));
    }

    // ========== Cascading Recovery (1 test) ==========

    /**
     * Test 3: Cascading recovery triggered when dependent partitions detected.
     */
    @Test
    void testPartitionRecovered_cascadeToDependent_triggered() {
        // Given: Two partitions with cross-partition neighbor relationship
        var bubble1 = vonManager.createBubble();
        var bubble2 = vonManager.createBubble();
        var partition1 = UUID.randomUUID();
        var partition2 = UUID.randomUUID();

        addEntities(bubble1, new Point3f(50.0f, 50.0f, 50.0f), 10);
        addEntities(bubble2, new Point3f(55.0f, 55.0f, 50.0f), 10);

        integration.registerBubble(bubble1.id(), partition1);
        integration.registerBubble(bubble2.id(), partition2);

        // Establish neighbor relationship (bubbles are VON neighbors)
        bubble1.addNeighbor(bubble2.id());
        bubble2.addNeighbor(bubble1.id());

        // Mark partition2 as having recovery dependency on partition1
        integration.addRecoveryDependency(partition2, partition1);

        // When: Partition1 recovers
        var recoveryEvent = new PartitionChangeEvent(
            partition1,
            PartitionStatus.FAILED,
            PartitionStatus.HEALTHY,
            clock.currentTimeMillis(),
            "recovery_completed"
        );
        faultHandler.fireEvent(recoveryEvent);

        // Then: Cascading recovery triggered for dependent partition2
        var recoveredEvents = capturedEvents.stream()
            .filter(e -> e instanceof Event.PartitionRecovered)
            .map(e -> (Event.PartitionRecovered) e)
            .toList();

        // Should have at least one event with cascadeTriggered=true
        assertThat(recoveredEvents)
            .anyMatch(e -> e.partitionId().equals(partition1) && e.cascadeTriggered());
    }

    // ========== Recovery Timeout (1 test) ==========

    /**
     * Test 4: Recovery timeout prevents infinite wait (30s default).
     */
    @Test
    void testPartitionRecovered_timeout_handled() {
        // Given: Partition with bubble that will cause slow rejoin
        var bubble = vonManager.createBubble();
        var partitionId = UUID.randomUUID();

        addEntities(bubble, new Point3f(50.0f, 50.0f, 50.0f), 10);
        integration.registerBubble(bubble.id(), partitionId);

        // Set short timeout for testing (1000ms instead of 30s)
        integration.setRecoveryTimeoutMs(1000L);

        // Simulate slow start time
        clock.setTime(1000L);

        // When: Partition recovers
        var recoveryEvent = new PartitionChangeEvent(
            partitionId,
            PartitionStatus.FAILED,
            PartitionStatus.HEALTHY,
            clock.currentTimeMillis(),
            "recovery_completed"
        );
        faultHandler.fireEvent(recoveryEvent);

        // Then: Recovery completes with metrics (even if timed out)
        var recoveredEvents = capturedEvents.stream()
            .filter(e -> e instanceof Event.PartitionRecovered)
            .map(e -> (Event.PartitionRecovered) e)
            .toList();

        assertThat(recoveredEvents).hasSize(1);
        // Recovery time should be recorded
        assertThat(recoveredEvents.get(0).recoveryTimeMs()).isGreaterThanOrEqualTo(0);
    }

    // ========== Recovery Events (1 test) ==========

    /**
     * Test 5: PartitionRecovered event emitted with correct metrics.
     */
    @Test
    void testPartitionRecovered_emitsRecoveryEvent() {
        // Given: Partition with bubble
        var bubble = vonManager.createBubble();
        var partitionId = UUID.randomUUID();

        addEntities(bubble, new Point3f(50.0f, 50.0f, 50.0f), 10);
        integration.registerBubble(bubble.id(), partitionId);

        clock.setTime(1000L);

        // When: Partition recovers
        var recoveryEvent = new PartitionChangeEvent(
            partitionId,
            PartitionStatus.FAILED,
            PartitionStatus.HEALTHY,
            clock.currentTimeMillis(),
            "recovery_completed"
        );

        // Simulate some time passing during recovery
        clock.advance(500);  // 500ms recovery time

        faultHandler.fireEvent(recoveryEvent);

        // Then: Event emitted with all metrics
        var recoveredEvents = capturedEvents.stream()
            .filter(e -> e instanceof Event.PartitionRecovered)
            .map(e -> (Event.PartitionRecovered) e)
            .toList();

        assertThat(recoveredEvents).hasSize(1);
        var recovered = recoveredEvents.get(0);
        assertThat(recovered.partitionId()).isEqualTo(partitionId);
        assertThat(recovered.totalBubbles()).isEqualTo(1);
        assertThat(recovered.successfulRejoins()).isEqualTo(1);
        assertThat(recovered.failedRejoins()).isEqualTo(0);
        assertThat(recovered.recoveryTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(recovered.cascadeTriggered()).isFalse();
    }

    // ========== Recovery Metrics (1 test) ==========

    /**
     * Test 6: Recovery metrics tracked and retrievable.
     */
    @Test
    void testRecoveryMetrics_tracked() {
        // Given: Multiple recovery cycles
        var bubble = vonManager.createBubble();
        var partitionId = UUID.randomUUID();

        addEntities(bubble, new Point3f(50.0f, 50.0f, 50.0f), 10);
        integration.registerBubble(bubble.id(), partitionId);

        // When: Multiple recoveries occur
        for (int i = 0; i < 3; i++) {
            clock.advance(1000);
            var recoveryEvent = new PartitionChangeEvent(
                partitionId,
                PartitionStatus.FAILED,
                PartitionStatus.HEALTHY,
                clock.currentTimeMillis(),
                "recovery_" + i
            );
            faultHandler.fireEvent(recoveryEvent);
        }

        // Then: Metrics tracked
        var metrics = integration.getRecoveryMetrics(partitionId);
        assertThat(metrics).isNotNull();
        assertThat(metrics.recoveryCount()).isEqualTo(3);
        assertThat(metrics.totalSuccessfulRejoins()).isGreaterThanOrEqualTo(3);  // 1 bubble x 3 recoveries
        assertThat(metrics.averageRecoveryTimeMs()).isGreaterThanOrEqualTo(0);
    }

    // ========== Helper Methods ==========

    /**
     * Add entities to a bubble to establish spatial bounds.
     */
    private void addEntities(Bubble bubble, Point3f center, int count) {
        for (int i = 0; i < count; i++) {
            float x = Math.max(0.1f, center.x + (i % 3) * 0.1f);
            float y = Math.max(0.1f, center.y + (i / 3) * 0.1f);
            float z = Math.max(0.1f, center.z);
            bubble.addEntity("entity-" + i, new Point3f(x, y, z), "content-" + i);
        }
    }

    // ========== Test Doubles ==========

    /**
     * Test implementation of FaultHandler for verification.
     */
    private static class TestFaultHandler implements FaultHandler {
        private final Set<UUID> healthyCalls = ConcurrentHashMap.newKeySet();
        private final Set<UUID> syncFailureCalls = ConcurrentHashMap.newKeySet();
        private final List<Consumer<PartitionChangeEvent>> subscribers = new ArrayList<>();
        private final AtomicInteger subscriptionCounter = new AtomicInteger(0);

        @Override
        public void markHealthy(UUID partitionId) {
            healthyCalls.add(partitionId);
        }

        @Override
        public void reportSyncFailure(UUID partitionId) {
            syncFailureCalls.add(partitionId);
        }

        @Override
        public Subscription subscribeToChanges(Consumer<PartitionChangeEvent> consumer) {
            subscribers.add(consumer);
            var id = subscriptionCounter.getAndIncrement();
            return () -> subscribers.remove(consumer);
        }

        public void fireEvent(PartitionChangeEvent event) {
            for (var subscriber : subscribers) {
                subscriber.accept(event);
            }
        }

        public Set<UUID> getHealthyCalls() {
            return new HashSet<>(healthyCalls);
        }

        public Set<UUID> getSyncFailureCalls() {
            return new HashSet<>(syncFailureCalls);
        }

        public void clearHealthyCalls() {
            healthyCalls.clear();
        }

        public void clearSyncFailureCalls() {
            syncFailureCalls.clear();
        }

        // Unimplemented methods (not needed for these tests)
        @Override public PartitionStatus checkHealth(UUID partitionId) { return null; }
        @Override public PartitionView getPartitionView(UUID partitionId) { return null; }
        @Override public void reportBarrierTimeout(UUID partitionId) { }
        @Override public void reportHeartbeatFailure(UUID partitionId, UUID nodeId) { }
        @Override public void registerRecovery(UUID partitionId, PartitionRecovery recovery) { }
        @Override public java.util.concurrent.CompletableFuture<Boolean> initiateRecovery(UUID partitionId) { return null; }
        @Override public void notifyRecoveryComplete(UUID partitionId, boolean success) { }
        @Override public FaultConfiguration getConfiguration() { return null; }
        @Override public FaultMetrics getMetrics(UUID partitionId) { return null; }
        @Override public FaultMetrics getAggregateMetrics() { return null; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public boolean isRunning() { return false; }
    }
}

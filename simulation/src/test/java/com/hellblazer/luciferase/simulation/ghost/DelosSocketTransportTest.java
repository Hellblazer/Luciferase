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

package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.lucien.forest.ghost.GhostZoneManager;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.events.EntityUpdateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Tests for DelosSocketTransport - Phase 7B.2 Network Transport
 *
 * Tests the Delos-based network transport for cross-bubble ghost delivery.
 * DelosSocketTransport implements GhostChannel interface and uses EntityUpdateEvent
 * serialization for wire protocol.
 *
 * TEST STRATEGY:
 * 1. Write comprehensive tests FIRST (this file)
 * 2. Implement DelosSocketTransport to pass tests
 * 3. Validate round-trip serialization and transmission
 *
 * COVERAGE:
 * - Creation and lifecycle
 * - Ghost queuing and batching
 * - Serialization/deserialization via EntityUpdateEvent
 * - Local transmission (simulated network)
 * - Remote reception and handler invocation
 * - Error handling and connection management
 * - Performance bounds (latency < 100ms)
 *
 * @author hal.hildebrand
 */
class DelosSocketTransportTest {

    private DelosSocketTransport transport1;
    private DelosSocketTransport transport2;
    private UUID bubbleId1;
    private UUID bubbleId2;

    @BeforeEach
    void setUp() {
        // Use fixed seed for reproducibility
        var random = new java.util.Random(42L);
        bubbleId1 = new UUID(random.nextLong(), random.nextLong());
        bubbleId2 = new UUID(random.nextLong(), random.nextLong());

        transport1 = new DelosSocketTransport(bubbleId1);
        transport2 = new DelosSocketTransport(bubbleId2);
    }

    @AfterEach
    void tearDown() {
        if (transport1 != null) {
            transport1.close();
        }
        if (transport2 != null) {
            transport2.close();
        }
    }

    /**
     * Test 1: Transport Creation
     * Verify that DelosSocketTransport initializes correctly with bubble ID.
     */
    @Test
    void testCreation() {
        assertNotNull(transport1);
        assertNotNull(transport2);

        // Transport should be ready for use immediately
        assertTrue(transport1.isConnected(bubbleId2), "Transport should report connected");

        // No pending ghosts initially
        assertEquals(0, transport1.getPendingCount(bubbleId2));
    }

    /**
     * Test 2: Queue Ghost
     * Verify that ghosts can be queued without exception.
     */
    @Test
    void testQueueGhost() {
        var ghost = createTestGhost("entity-1", 100f, 200f, 50f, 1000L, 1L, 1L);

        assertDoesNotThrow(() -> transport1.queueGhost(bubbleId2, ghost));

        // Ghost should be pending
        assertEquals(1, transport1.getPendingCount(bubbleId2));

        // Queue multiple ghosts
        transport1.queueGhost(bubbleId2, createTestGhost("entity-2", 150f, 250f, 75f, 1001L, 2L, 2L));
        transport1.queueGhost(bubbleId2, createTestGhost("entity-3", 200f, 300f, 100f, 1002L, 3L, 3L));

        assertEquals(3, transport1.getPendingCount(bubbleId2));
    }

    /**
     * Test 3: Serialization on Queue
     * Verify that EntityUpdateEvent is properly serialized when ghost is queued.
     * This test validates the conversion from SimulationGhostEntity to EntityUpdateEvent.
     */
    @Test
    void testSerializationOnQueue() {
        var ghost = createTestGhost("entity-serialize", 42.5f, 84.5f, 21.25f, 5000L, 10L, 5L);

        // Queue the ghost - this should trigger serialization internally
        assertDoesNotThrow(() -> transport1.queueGhost(bubbleId2, ghost));

        // Verify ghost is queued (serialization didn't fail)
        assertEquals(1, transport1.getPendingCount(bubbleId2));

        // Flush will trigger actual serialization and transmission
        assertDoesNotThrow(() -> transport1.flush(1L));
    }

    /**
     * Test 4: Local Transmission
     * Verify that events can be sent to remote transport (simulated network).
     */
    @Test
    void testLocalTransmission() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var received = new ArrayList<SimulationGhostEntity<StringEntityID, EntityData>>();

        // Register handler on receiving transport
        transport2.onReceive((sourceBubbleId, ghosts) -> {
            assertEquals(bubbleId1, sourceBubbleId, "Source bubble ID should match sender");
            received.addAll(ghosts);
            latch.countDown();
        });

        // Connect transports (simulated network link)
        transport1.connectTo(transport2);

        // Send ghost from transport1 to transport2
        var ghost = createTestGhost("entity-tx", 100f, 200f, 50f, 1000L, 1L, 1L);
        transport1.queueGhost(bubbleId2, ghost);
        transport1.flush(1L);

        // Wait for reception
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive ghost within 5 seconds");
        assertEquals(1, received.size());

        // Verify received ghost matches sent ghost
        var receivedGhost = received.get(0);
        assertEquals("entity-tx", receivedGhost.entityId().getValue());
        assertPointEquals(new Point3f(100f, 200f, 50f), receivedGhost.position());
    }

    /**
     * Test 5: Remote Reception
     * Verify that remote transport receives deserialized EntityUpdateEvent correctly.
     */
    @Test
    void testRemoteReception() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var receivedIds = new CopyOnWriteArrayList<String>();
        var receivedPositions = new CopyOnWriteArrayList<Point3f>();

        transport2.onReceive((sourceBubbleId, ghosts) -> {
            for (var ghost : ghosts) {
                receivedIds.add(ghost.entityId().getValue());
                receivedPositions.add(new Point3f(ghost.position()));
            }
            latch.countDown();
        });

        transport1.connectTo(transport2);

        // Send multiple ghosts in batch
        transport1.queueGhost(bubbleId2, createTestGhost("entity-A", 10f, 20f, 30f, 1000L, 1L, 1L));
        transport1.queueGhost(bubbleId2, createTestGhost("entity-B", 40f, 50f, 60f, 1001L, 2L, 2L));
        transport1.queueGhost(bubbleId2, createTestGhost("entity-C", 70f, 80f, 90f, 1002L, 3L, 3L));
        transport1.flush(1L);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Verify all ghosts received
        assertEquals(3, receivedIds.size());
        assertEquals("entity-A", receivedIds.get(0));
        assertEquals("entity-B", receivedIds.get(1));
        assertEquals("entity-C", receivedIds.get(2));

        assertPointEquals(new Point3f(10f, 20f, 30f), receivedPositions.get(0));
        assertPointEquals(new Point3f(40f, 50f, 60f), receivedPositions.get(1));
        assertPointEquals(new Point3f(70f, 80f, 90f), receivedPositions.get(2));
    }

    /**
     * Test 6: Round Trip
     * Verify full serialize → send → deserialize cycle preserves data.
     */
    @Test
    void testRoundTrip() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var receivedGhost = new ArrayList<SimulationGhostEntity<StringEntityID, EntityData>>(1);

        transport2.onReceive((sourceBubbleId, ghosts) -> {
            receivedGhost.addAll(ghosts);
            latch.countDown();
        });

        transport1.connectTo(transport2);

        // Create ghost with precise values for round-trip validation
        var originalGhost = createTestGhost("roundtrip-test", 123.456f, 234.567f, 345.678f, 9999L, 42L, 17L);
        transport1.queueGhost(bubbleId2, originalGhost);
        transport1.flush(1L);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, receivedGhost.size());

        var received = receivedGhost.get(0);

        // Verify entity ID preserved
        assertEquals("roundtrip-test", received.entityId().getValue());

        // Verify position preserved (with float precision tolerance)
        assertPointEquals(new Point3f(123.456f, 234.567f, 345.678f), received.position());

        // Verify bucket (simulation time) and epoch preserved
        // Note: timestamp() is GhostEntity's creation time (set to System.currentTimeMillis() on receiver),
        // not the original simulation timestamp. The simulation time is in the bucket field.
        assertEquals(42L, received.bucket(), "Bucket (simulation time) should be preserved");

        // Epoch is now derived from bucket: epoch = bucket / 100
        // Bucket 42 -> epoch 0 (42 / 100 = 0)
        assertEquals(0L, received.epoch(), "Bucket 42 should derive epoch 0 (42 / 100 = 0)");

        // Version should be positive (monotonic counter)
        assertTrue(received.version() > 0, "Version should be positive (from AtomicLong counter)");
    }

    /**
     * Test 11: Epoch Derivation from Bucket
     * Verify that epoch is correctly derived from bucket: epoch = bucket / EPOCH_SIZE (100).
     */
    @Test
    void testEpochDerivationFromBucket() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var receivedGhosts = new ArrayList<SimulationGhostEntity<StringEntityID, EntityData>>();

        transport2.onReceive((sourceBubbleId, ghosts) -> {
            receivedGhosts.addAll(ghosts);
            latch.countDown();
        });

        transport1.connectTo(transport2);

        // Send ghost with bucket 250 -> epoch should be 2 (250 / 100 = 2)
        var ghost = createTestGhost("epoch-test", 10f, 20f, 30f, 1000L, 250L, 0L);
        transport1.queueGhost(bubbleId2, ghost);
        transport1.flush(1L);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, receivedGhosts.size());

        var received = receivedGhosts.get(0);
        assertEquals(2L, received.epoch(),
                    "Bucket 250 should derive epoch 2 (250 / 100 = 2)");
    }

    /**
     * Test 12: Version Counter Monotonically Increases
     * Verify that version numbers increase across multiple transmissions.
     */
    @Test
    void testVersionCounterMonotonicallyIncreases() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var receivedGhosts = new CopyOnWriteArrayList<SimulationGhostEntity<StringEntityID, EntityData>>();

        transport2.onReceive((sourceBubbleId, ghosts) -> {
            receivedGhosts.addAll(ghosts);
            if (receivedGhosts.size() >= 3) {
                latch.countDown();
            }
        });

        transport1.connectTo(transport2);

        // Send 3 ghosts - versions should increase
        for (int i = 0; i < 3; i++) {
            var ghost = createTestGhost("version-test-" + i, i * 10f, 0f, 0f, 1000L, 100L, 0L);
            transport1.queueGhost(bubbleId2, ghost);
        }
        transport1.flush(1L);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(3, receivedGhosts.size());

        // Extract versions
        var versions = receivedGhosts.stream()
            .map(SimulationGhostEntity::version)
            .toList();

        // Verify monotonically increasing
        for (int i = 1; i < versions.size(); i++) {
            assertTrue(versions.get(i) > versions.get(i - 1),
                      "Version " + versions.get(i) + " should be > " + versions.get(i - 1));
        }
    }

    /**
     * Test 7: Batch Flushing
     * Verify that multiple ghosts are batched and flushed correctly at bucket boundaries.
     */
    @Test
    void testBatchFlushing() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var batchCount = new AtomicInteger(0);
        var totalReceived = new AtomicInteger(0);

        transport2.onReceive((sourceBubbleId, ghosts) -> {
            batchCount.incrementAndGet();
            totalReceived.addAndGet(ghosts.size());
            latch.countDown();
        });

        transport1.connectTo(transport2);

        // Queue 10 ghosts for same target
        for (int i = 0; i < 10; i++) {
            var ghost = createTestGhost("batch-" + i, i * 10f, i * 20f, i * 5f, 1000L + i, i, i);
            transport1.queueGhost(bubbleId2, ghost);
        }

        // Flush should send all as single batch
        transport1.flush(1L);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // All 10 ghosts should arrive in single batch
        assertEquals(1, batchCount.get(), "Should receive exactly 1 batch");
        assertEquals(10, totalReceived.get(), "Should receive all 10 ghosts");

        // Pending count should be 0 after flush
        assertEquals(0, transport1.getPendingCount(bubbleId2));
    }

    /**
     * Test 8: Latency Bounds
     * Verify that transmission latency is under 100ms for local network.
     */
    @Test
    void testLatencyBounds() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var startTime = new long[1];
        var endTime = new long[1];

        transport2.onReceive((sourceBubbleId, ghosts) -> {
            endTime[0] = System.nanoTime();
            latch.countDown();
        });

        transport1.connectTo(transport2);

        var ghost = createTestGhost("latency-test", 100f, 200f, 50f, 1000L, 1L, 1L);

        startTime[0] = System.nanoTime();
        transport1.queueGhost(bubbleId2, ghost);
        transport1.flush(1L);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        var latencyMs = (endTime[0] - startTime[0]) / 1_000_000;
        assertTrue(latencyMs < 100, "Latency should be < 100ms, was: " + latencyMs + "ms");
    }

    /**
     * Test 9: Error Handling
     * Verify that connection failures are handled gracefully.
     */
    @Test
    void testErrorHandling() {
        // Test null target
        var ghost = createTestGhost("error-test", 100f, 200f, 50f, 1000L, 1L, 1L);
        assertThrows(NullPointerException.class, () -> transport1.queueGhost(null, ghost));

        // Test null ghost
        assertThrows(NullPointerException.class, () -> transport1.queueGhost(bubbleId2, null));

        // Test flush with no pending ghosts (should not throw)
        assertDoesNotThrow(() -> transport1.flush(1L));

        // Test close idempotence
        transport1.close();
        assertDoesNotThrow(() -> transport1.close());
    }

    /**
     * Test 10: Lifecycle
     * Verify that open() / close() work correctly.
     */
    @Test
    void testLifecycle() {
        var transport = new DelosSocketTransport(UUID.randomUUID());

        // Transport should be usable immediately after construction
        assertTrue(transport.isConnected(bubbleId2));

        // Queue a ghost
        var ghost = createTestGhost("lifecycle-test", 100f, 200f, 50f, 1000L, 1L, 1L);
        assertDoesNotThrow(() -> transport.queueGhost(bubbleId2, ghost));

        // Close should clear pending ghosts
        transport.close();
        assertEquals(0, transport.getPendingCount(bubbleId2));

        // Transport should still report connected (no network resources to close yet)
        assertTrue(transport.isConnected(bubbleId2));
    }

    // ========== Helper Methods ==========

    /**
     * Create a test ghost with specified parameters.
     */
    @SuppressWarnings("rawtypes") // EntityData used as raw type to match EnhancedBubble pattern
    private SimulationGhostEntity<StringEntityID, EntityData> createTestGhost(
        String entityIdValue,
        float x, float y, float z,
        long timestamp,
        long bucket,
        long epoch
    ) {
        var entityId = new StringEntityID(entityIdValue);
        var position = new Point3f(x, y, z);
        EntityData content = new EntityData<>(entityId, position, (byte) 10, null); // raw type
        var bounds = new EntityBounds(position, 0.1f); // small radius for point-like entity

        GhostZoneManager.GhostEntity<StringEntityID, EntityData> ghostEntity =
            new GhostZoneManager.GhostEntity<>(
                entityId,
                content,
                position,
                bounds,
                "source-tree-" + entityIdValue
            );

        return new SimulationGhostEntity<>(
            ghostEntity,
            bubbleId1,
            bucket,
            epoch,
            1L // version
        );
    }

    /**
     * Assert that two Point3f instances are equal within float precision tolerance.
     */
    private void assertPointEquals(Point3f expected, Point3f actual) {
        var epsilon = 0.0001f;
        assertEquals(expected.x, actual.x, epsilon, "X coordinate mismatch");
        assertEquals(expected.y, actual.y, epsilon, "Y coordinate mismatch");
        assertEquals(expected.z, actual.z, epsilon, "Z coordinate mismatch");
    }
}

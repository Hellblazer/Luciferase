package com.hellblazer.luciferase.simulation.bubble;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hellblazer.luciferase.simulation.delos.mock.MockGossipAdapter;

import javax.vecmath.Point3f;

/**
 * TDD tests for ReplicatedForest.
 * Tests written BEFORE implementation to drive design.
 * <p>
 * Total: 10 tests covering all requirements:
 * - Basic CRUD operations
 * - Concurrent access (thread-safety)
 * - Conflict resolution (last-write-wins)
 * - Query operations (by server, by bounds)
 * - Gossip integration
 */
class ReplicatedForestTest {

    private ReplicatedForest forest;
    private MockGossipAdapter gossip;

    @BeforeEach
    void setUp() {
        gossip = new MockGossipAdapter();
        forest = new ReplicatedForest(gossip);
    }

    @Test
    void testEmpty() {
        // When - newly created forest
        // Then - should be empty
        assertEquals(0, forest.size());
        assertNull(forest.get(UUID.randomUUID()));
    }

    @Test
    void testPut() {
        // Given
        var bubbleId = UUID.randomUUID();
        var serverId = UUID.randomUUID();
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(0, 0, 0)));
        var timestamp = System.currentTimeMillis();
        var entry = new BubbleEntry(bubbleId, serverId, bounds, timestamp);

        // When
        forest.put(entry);

        // Then
        assertEquals(1, forest.size());
        assertEquals(entry, forest.get(bubbleId));
    }

    @Test
    void testGet() {
        // Given
        var bubbleId = UUID.randomUUID();
        var serverId = UUID.randomUUID();
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(0, 0, 0)));
        var entry = new BubbleEntry(bubbleId, serverId, bounds, System.currentTimeMillis());
        forest.put(entry);

        // When
        var retrieved = forest.get(bubbleId);

        // Then
        assertNotNull(retrieved);
        assertEquals(bubbleId, retrieved.bubbleId());
        assertEquals(serverId, retrieved.serverId());
    }

    @Test
    void testUpdate() {
        // Given - initial entry
        var bubbleId = UUID.randomUUID();
        var serverId1 = UUID.randomUUID();
        var bounds1 = BubbleBounds.fromEntityPositions(List.of(new Point3f(0, 0, 0)));
        var timestamp1 = 1000L;
        var entry1 = new BubbleEntry(bubbleId, serverId1, bounds1, timestamp1);
        forest.put(entry1);

        // When - update with newer timestamp
        var serverId2 = UUID.randomUUID();
        var bounds2 = BubbleBounds.fromEntityPositions(List.of(new Point3f(10, 10, 10)));
        var timestamp2 = 2000L;
        var entry2 = new BubbleEntry(bubbleId, serverId2, bounds2, timestamp2);
        forest.put(entry2);

        // Then - should have newer entry
        assertEquals(1, forest.size()); // Still only 1 entry
        var retrieved = forest.get(bubbleId);
        assertEquals(serverId2, retrieved.serverId());
        assertEquals(timestamp2, retrieved.timestamp());
    }

    @Test
    void testRemove() {
        // Given
        var bubbleId = UUID.randomUUID();
        var entry = new BubbleEntry(
            bubbleId,
            UUID.randomUUID(),
            BubbleBounds.fromEntityPositions(List.of(new Point3f(0, 0, 0))),
            System.currentTimeMillis()
        );
        forest.put(entry);
        assertEquals(1, forest.size());

        // When
        forest.remove(bubbleId);

        // Then
        assertEquals(0, forest.size());
        assertNull(forest.get(bubbleId));
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Given - 100 threads
        var numThreads = 100;
        var latch = new CountDownLatch(numThreads);
        var errors = new ConcurrentHashMap<Integer, Exception>();

        // When - all threads concurrently put entries
        var threads = IntStream.range(0, numThreads)
            .mapToObj(i -> new Thread(() -> {
                try {
                    var bubbleId = UUID.randomUUID();
                    var entry = new BubbleEntry(
                        bubbleId,
                        UUID.randomUUID(),
                        BubbleBounds.fromEntityPositions(List.of(new Point3f(i, i, i))),
                        System.currentTimeMillis()
                    );
                    forest.put(entry);

                    // Verify can retrieve immediately
                    assertNotNull(forest.get(bubbleId));
                } catch (Exception e) {
                    errors.put(i, e);
                } finally {
                    latch.countDown();
                }
            }))
            .toList();

        threads.forEach(Thread::start);

        // Then - all threads complete successfully
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads did not complete in time");
        assertTrue(errors.isEmpty(), "Concurrent access errors: " + errors);
        assertEquals(numThreads, forest.size());
    }

    @Test
    void testConflictResolution() {
        // Given - same bubbleId, different timestamps
        var bubbleId = UUID.randomUUID();
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(0, 0, 0)));

        var oldEntry = new BubbleEntry(bubbleId, UUID.randomUUID(), bounds, 1000L);
        var newEntry = new BubbleEntry(bubbleId, UUID.randomUUID(), bounds, 2000L);

        // When - put new first, then old
        forest.put(newEntry);
        forest.put(oldEntry); // Should be rejected (older timestamp)

        // Then - should keep newer entry (last-write-wins with timestamp check)
        var retrieved = forest.get(bubbleId);
        assertEquals(2000L, retrieved.timestamp());
        assertEquals(newEntry.serverId(), retrieved.serverId());
    }

    @Test
    void testQueryByServer() {
        // Given - multiple bubbles on different servers
        var serverId1 = UUID.randomUUID();
        var serverId2 = UUID.randomUUID();
        var bounds = BubbleBounds.fromEntityPositions(List.of(new Point3f(0, 0, 0)));

        forest.put(new BubbleEntry(UUID.randomUUID(), serverId1, bounds, 1000L));
        forest.put(new BubbleEntry(UUID.randomUUID(), serverId1, bounds, 1001L));
        forest.put(new BubbleEntry(UUID.randomUUID(), serverId2, bounds, 1002L));

        // When - query by server
        var server1Bubbles = forest.getByServer(serverId1);
        var server2Bubbles = forest.getByServer(serverId2);

        // Then
        assertEquals(2, server1Bubbles.size());
        assertEquals(1, server2Bubbles.size());
        assertTrue(server1Bubbles.stream().allMatch(e -> e.serverId().equals(serverId1)));
        assertTrue(server2Bubbles.stream().allMatch(e -> e.serverId().equals(serverId2)));
    }

    @Test
    void testQueryByBounds() {
        // Given - bubbles at different positions
        var serverId = UUID.randomUUID();

        // Bubble 1: near origin
        var bounds1 = BubbleBounds.fromEntityPositions(List.of(
            new Point3f(0, 0, 0),
            new Point3f(10, 10, 10)
        ));
        forest.put(new BubbleEntry(UUID.randomUUID(), serverId, bounds1, 1000L));

        // Bubble 2: far from origin
        var bounds2 = BubbleBounds.fromEntityPositions(List.of(
            new Point3f(1000, 1000, 1000),
            new Point3f(1010, 1010, 1010)
        ));
        forest.put(new BubbleEntry(UUID.randomUUID(), serverId, bounds2, 1001L));

        // When - query with bounds near origin
        var queryBounds = BubbleBounds.fromEntityPositions(List.of(
            new Point3f(5, 5, 5)
        ));
        var nearOrigin = forest.getByBounds(queryBounds);

        // Then - should only return bubble 1
        assertEquals(1, nearOrigin.size());
        assertEquals(bounds1.rdgMin(), nearOrigin.get(0).bounds().rdgMin());
    }

    @Test
    void testGossipIntegration() {
        // Given
        var bubbleId = UUID.randomUUID();
        var entry = new BubbleEntry(
            bubbleId,
            UUID.randomUUID(),
            BubbleBounds.fromEntityPositions(List.of(new Point3f(0, 0, 0))),
            System.currentTimeMillis()
        );

        // When
        forest.put(entry);

        // Then - should have broadcasted via gossip
        var messages = gossip.getMessages();
        assertEquals(1, messages.size());

        var msg = messages.get(0);
        assertEquals(bubbleId, msg.senderId()); // senderId is the bubbleId
        assertNotNull(msg.payload());
        assertTrue(msg.payload().length > 0);
    }
}

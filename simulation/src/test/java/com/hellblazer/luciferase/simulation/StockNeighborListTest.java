package com.hellblazer.luciferase.simulation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StockNeighborList - backup neighbor list for partition recovery.
 * <p>
 * StockNeighborList implements VON "stock neighbors" pattern:
 * - Keep historical list of previously discovered neighbors
 * - Use for partition recovery when current discovery fails
 * - Prioritize recent contacts (more likely still reachable)
 * - Limit list size to prevent unbounded growth
 * <p>
 * Partition recovery workflow:
 * 1. GhostLayerHealth detects partition (NC < 0.5)
 * 2. Current neighbor discovery failing
 * 3. Contact stock neighbors from historical list
 * 4. Stock neighbors provide fresh neighbor introductions
 * 5. NC metric recovers as new neighbors discovered
 * <p>
 * VON paper reference:
 * "Nodes keep a stock neighbor list of previously seen neighbors.
 * When partitioned, nodes contact stock neighbors for reintroduction
 * to the overlay."
 *
 * @author hal.hildebrand
 */
class StockNeighborListTest {

    private StockNeighborList stockList;

    @BeforeEach
    void setUp() {
        stockList = new StockNeighborList(10);  // Max 10 stock neighbors
    }

    @Test
    void testInitialState() {
        assertEquals(0, stockList.getStockNeighborCount(),
                    "Initially no stock neighbors");
        assertTrue(stockList.getStockNeighbors().isEmpty());
    }

    @Test
    void testAddStockNeighbor() {
        var neighborId = UUID.randomUUID();

        stockList.addStockNeighbor(neighborId, 100L);

        assertEquals(1, stockList.getStockNeighborCount());
        assertTrue(stockList.contains(neighborId));
    }

    @Test
    void testAddMultipleStockNeighbors() {
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();
        var neighbor3 = UUID.randomUUID();

        stockList.addStockNeighbor(neighbor1, 100L);
        stockList.addStockNeighbor(neighbor2, 200L);
        stockList.addStockNeighbor(neighbor3, 300L);

        assertEquals(3, stockList.getStockNeighborCount());
        assertTrue(stockList.contains(neighbor1));
        assertTrue(stockList.contains(neighbor2));
        assertTrue(stockList.contains(neighbor3));
    }

    @Test
    void testDuplicateNeighborUpdatesTimestamp() {
        var neighborId = UUID.randomUUID();

        stockList.addStockNeighbor(neighborId, 100L);
        stockList.addStockNeighbor(neighborId, 200L);  // Update

        assertEquals(1, stockList.getStockNeighborCount(),
                    "Duplicate should update, not add new entry");

        var neighbors = stockList.getStockNeighbors();
        assertEquals(200L, neighbors.get(0).lastSeen(),
                    "Last seen should be updated to 200");
    }

    @Test
    void testMaxCapacityLimit() {
        // Add 15 neighbors to list with max capacity 10
        for (int i = 0; i < 15; i++) {
            stockList.addStockNeighbor(UUID.randomUUID(), 100L + i);
        }

        assertEquals(10, stockList.getStockNeighborCount(),
                    "Should enforce max capacity of 10");
    }

    @Test
    void testOldestEvictedWhenFull() {
        var oldestNeighbor = UUID.randomUUID();
        stockList.addStockNeighbor(oldestNeighbor, 100L);

        // Fill to capacity with newer neighbors
        for (int i = 0; i < 10; i++) {
            stockList.addStockNeighbor(UUID.randomUUID(), 200L + i);
        }

        assertFalse(stockList.contains(oldestNeighbor),
                   "Oldest neighbor should be evicted");
        assertEquals(10, stockList.getStockNeighborCount());

        // Verify all remaining neighbors are newer
        var neighbors = stockList.getStockNeighbors();
        for (var entry : neighbors) {
            assertTrue(entry.lastSeen() >= 200L,
                      "All remaining neighbors should be recent");
        }
    }

    @Test
    void testGetStockNeighborsSortedByRecency() {
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();
        var neighbor3 = UUID.randomUUID();

        stockList.addStockNeighbor(neighbor1, 100L);
        stockList.addStockNeighbor(neighbor2, 300L);  // Most recent
        stockList.addStockNeighbor(neighbor3, 200L);

        var neighbors = stockList.getStockNeighbors();

        // Should be sorted by recency (most recent first)
        assertEquals(neighbor2, neighbors.get(0).neighborId(),
                    "Most recent neighbor should be first");
        assertEquals(neighbor3, neighbors.get(1).neighborId());
        assertEquals(neighbor1, neighbors.get(2).neighborId());
    }

    @Test
    void testRemoveStockNeighbor() {
        var neighborId = UUID.randomUUID();

        stockList.addStockNeighbor(neighborId, 100L);
        assertTrue(stockList.contains(neighborId));

        stockList.removeStockNeighbor(neighborId);

        assertFalse(stockList.contains(neighborId));
        assertEquals(0, stockList.getStockNeighborCount());
    }

    @Test
    void testRemoveNonexistentNeighbor() {
        var neighborId = UUID.randomUUID();

        assertDoesNotThrow(() -> stockList.removeStockNeighbor(neighborId),
                          "Removing nonexistent neighbor should not throw");
    }

    @Test
    void testGetRandomStockNeighbor() {
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();
        var neighbor3 = UUID.randomUUID();

        stockList.addStockNeighbor(neighbor1, 100L);
        stockList.addStockNeighbor(neighbor2, 200L);
        stockList.addStockNeighbor(neighbor3, 300L);

        var random = stockList.getRandomStockNeighbor();

        assertTrue(random.isPresent(), "Should return random neighbor");
        assertTrue(
            random.get().equals(neighbor1) ||
            random.get().equals(neighbor2) ||
            random.get().equals(neighbor3),
            "Random neighbor should be one of the three"
        );
    }

    @Test
    void testGetRandomStockNeighborEmpty() {
        var random = stockList.getRandomStockNeighbor();

        assertTrue(random.isEmpty(),
                  "Empty list should return empty Optional");
    }

    @Test
    void testGetTopNStockNeighbors() {
        for (int i = 0; i < 10; i++) {
            stockList.addStockNeighbor(UUID.randomUUID(), 100L + i);
        }

        var top5 = stockList.getTopNStockNeighbors(5);

        assertEquals(5, top5.size(), "Should return top 5 neighbors");

        // Verify sorted by recency (most recent first)
        for (int i = 0; i < 4; i++) {
            assertTrue(top5.get(i).lastSeen() >= top5.get(i + 1).lastSeen(),
                      "Should be sorted by recency");
        }
    }

    @Test
    void testGetTopNMoreThanAvailable() {
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();

        stockList.addStockNeighbor(neighbor1, 100L);
        stockList.addStockNeighbor(neighbor2, 200L);

        var top10 = stockList.getTopNStockNeighbors(10);

        assertEquals(2, top10.size(),
                    "Should return only available neighbors");
    }

    @Test
    void testClearStockNeighbors() {
        stockList.addStockNeighbor(UUID.randomUUID(), 100L);
        stockList.addStockNeighbor(UUID.randomUUID(), 200L);

        assertEquals(2, stockList.getStockNeighborCount());

        stockList.clear();

        assertEquals(0, stockList.getStockNeighborCount());
        assertTrue(stockList.getStockNeighbors().isEmpty());
    }

    @Test
    void testStockEntryImmutability() {
        var neighborId = UUID.randomUUID();
        stockList.addStockNeighbor(neighborId, 100L);

        var neighbors = stockList.getStockNeighbors();
        var entry = neighbors.get(0);

        // Record fields are final
        assertEquals(neighborId, entry.neighborId());
        assertEquals(100L, entry.lastSeen());
    }

    @Test
    void testGetNeighborIds() {
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();
        var neighbor3 = UUID.randomUUID();

        stockList.addStockNeighbor(neighbor1, 100L);
        stockList.addStockNeighbor(neighbor2, 200L);
        stockList.addStockNeighbor(neighbor3, 300L);

        var ids = stockList.getNeighborIds();

        assertEquals(3, ids.size());
        assertTrue(ids.contains(neighbor1));
        assertTrue(ids.contains(neighbor2));
        assertTrue(ids.contains(neighbor3));
    }

    @Test
    void testPartitionRecoveryScenario() {
        // Scenario: Ghost layer detects partition, contacts stock neighbors

        // Phase 1: Normal operation - discover neighbors
        var neighborA = UUID.randomUUID();
        var neighborB = UUID.randomUUID();
        var neighborC = UUID.randomUUID();

        stockList.addStockNeighbor(neighborA, 100L);
        stockList.addStockNeighbor(neighborB, 200L);
        stockList.addStockNeighbor(neighborC, 300L);

        assertEquals(3, stockList.getStockNeighborCount());

        // Phase 2: Partition detected (NC < 0.5)
        // Current neighbor discovery failing

        // Phase 3: Recovery - contact top 2 stock neighbors
        var recoveryContacts = stockList.getTopNStockNeighbors(2);

        assertEquals(2, recoveryContacts.size());
        assertEquals(neighborC, recoveryContacts.get(0).neighborId(),
                    "Most recent neighbor first");
        assertEquals(neighborB, recoveryContacts.get(1).neighborId());

        // Phase 4: Successfully contacted neighborC, update timestamp
        stockList.addStockNeighbor(neighborC, 400L);

        var updated = stockList.getStockNeighbors();
        assertEquals(neighborC, updated.get(0).neighborId());
        assertEquals(400L, updated.get(0).lastSeen(),
                    "Contact success updates timestamp");
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        int neighborsPerThread = 10;

        var threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < neighborsPerThread; i++) {
                    stockList.addStockNeighbor(
                        UUID.randomUUID(),
                        threadId * 1000L + i
                    );
                }
            });
            threads[t].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // Max capacity is 10, so only 10 most recent should remain
        assertEquals(10, stockList.getStockNeighborCount(),
                    "Should respect max capacity under concurrency");
    }

    @Test
    void testRemoveOlderThan() {
        stockList.addStockNeighbor(UUID.randomUUID(), 100L);
        stockList.addStockNeighbor(UUID.randomUUID(), 200L);
        stockList.addStockNeighbor(UUID.randomUUID(), 300L);
        stockList.addStockNeighbor(UUID.randomUUID(), 400L);

        int removed = stockList.removeOlderThan(250L);

        assertEquals(2, removed, "Should remove 2 old neighbors");
        assertEquals(2, stockList.getStockNeighborCount());

        // Verify only recent neighbors remain
        var neighbors = stockList.getStockNeighbors();
        for (var entry : neighbors) {
            assertTrue(entry.lastSeen() >= 250L,
                      "Only neighbors >= 250 should remain");
        }
    }

    @Test
    void testBulkAddStockNeighbors() {
        var neighbors = List.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID()
        );

        stockList.addStockNeighbors(neighbors, 100L);

        assertEquals(3, stockList.getStockNeighborCount());
        for (var neighbor : neighbors) {
            assertTrue(stockList.contains(neighbor));
        }
    }

    @Test
    void testGetStockNeighborsUnmodifiable() {
        stockList.addStockNeighbor(UUID.randomUUID(), 100L);

        var neighbors = stockList.getStockNeighbors();

        assertThrows(UnsupportedOperationException.class,
                    () -> neighbors.add(new StockNeighborList.StockEntry(
                        UUID.randomUUID(), 200L
                    )),
                    "Returned list should be unmodifiable");
    }
}

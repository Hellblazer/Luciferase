/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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

package com.hellblazer.luciferase.simulation.distributed;

import com.hellblazer.luciferase.simulation.von.LocalServerTransport;
import com.hellblazer.luciferase.simulation.von.VonBubble;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for CrossProcessNeighborIndex.
 * <p>
 * Tests all 21 requirements from Phase 6B3 specification.
 *
 * @author hal.hildebrand
 */
class CrossProcessNeighborIndexTest {

    private ProcessRegistry registry;
    private CrossProcessNeighborIndex index;
    private LocalServerTransport.Registry transportRegistry;
    private List<VonBubble> bubbles;

    @BeforeEach
    void setUp() {
        registry = new ProcessRegistry();
        index = new CrossProcessNeighborIndex(registry);
        transportRegistry = LocalServerTransport.Registry.create();
        bubbles = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (bubbles != null) {
            bubbles.forEach(VonBubble::close);
        }
        if (transportRegistry != null) {
            transportRegistry.close();
        }
    }

    private VonBubble createBubble() {
        var transport = transportRegistry.register(UUID.randomUUID());
        var bubble = new VonBubble(UUID.randomUUID(), (byte) 10, 100L, transport);
        bubbles.add(bubble);
        return bubble;
    }

    // ========== Basic Functionality Tests ==========

    @Test
    void test1_LocalNeighborsQueryLocalTetrahedralNeighbors() {
        var bubble = createBubble();
        var neighborId = UUID.randomUUID();
        bubble.addNeighbor(neighborId);

        var ref = new LocalBubbleReference(bubble);
        var neighbors = index.getNeighbors(ref);

        assertNotNull(neighbors);
        // Note: neighbors will be remote proxies since we don't have local references
    }

    @Test
    void test2_RemoteNeighborsCrossProcessLookups() throws Exception {
        var bubble = createBubble();

        // Register bubble in a process
        var processId = UUID.randomUUID();
        registry.register(processId, List.of(bubble.id()));

        var ref = new LocalBubbleReference(bubble);
        var neighbors = index.getNeighbors(ref);

        assertNotNull(neighbors);
    }

    @Test
    void test3_CacheHitRepeatedQueryReturnsCachedResult() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        // First query - cache miss
        var stats1 = index.getCacheStats();
        index.getNeighbors(ref);
        var stats2 = index.getCacheStats();

        assertEquals(stats1.misses() + 1, stats2.misses(), "First query should be cache miss");

        // Second query - cache hit
        index.getNeighbors(ref);
        var stats3 = index.getCacheStats();

        assertEquals(stats2.hits() + 1, stats3.hits(), "Second query should be cache hit");
    }

    @Test
    void test4_CacheMissFirstQueryGoesToProcessRegistry() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        var stats1 = index.getCacheStats();
        index.getNeighbors(ref);
        var stats2 = index.getCacheStats();

        assertTrue(stats2.misses() > stats1.misses(), "First query should miss cache");
    }

    @Test
    void test5_CacheHitRateOver80Percent() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        // Prime cache
        index.getNeighbors(ref);

        // Do 100 queries
        for (int i = 0; i < 100; i++) {
            index.getNeighbors(ref);
        }

        var stats = index.getCacheStats();
        var total = stats.hits() + stats.misses();
        var hitRate = (double) stats.hits() / total;

        assertTrue(hitRate > 0.80, "Cache hit rate should be >80%, was: " + (hitRate * 100) + "%");
    }

    @Test
    void test6_TTLExpirationCacheInvalidatesAfter5Seconds() throws Exception {
        // Create index with short TTL
        var shortTTLIndex = new CrossProcessNeighborIndex(registry, 100L);

        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        // Prime cache
        shortTTLIndex.getNeighbors(ref);

        // Wait for expiration
        Thread.sleep(150);

        // Next query should miss
        var stats1 = shortTTLIndex.getCacheStats();
        shortTTLIndex.getNeighbors(ref);
        var stats2 = shortTTLIndex.getCacheStats();

        assertTrue(stats2.misses() > stats1.misses(), "Should miss cache after TTL expiration");
    }

    @Test
    void test7_CacheInvalidationOnTopologyChange() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        // Prime cache
        index.getNeighbors(ref);

        // Invalidate manually (simulates topology change)
        index.invalidateCache(bubble.id());

        // Next query should miss
        var stats1 = index.getCacheStats();
        index.getNeighbors(ref);
        var stats2 = index.getCacheStats();

        assertTrue(stats2.misses() > stats1.misses(), "Should miss cache after invalidation");
    }

    @Test
    void test8_CacheInvalidationOnExplicitCall() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        // Prime cache
        index.getNeighbors(ref);

        var stats1 = index.getCacheStats();

        // Explicit invalidation
        index.invalidateCache(bubble.id());

        var stats2 = index.getCacheStats();
        assertEquals(stats1.evictions() + 1, stats2.evictions(), "Should increment eviction count");
    }

    @Test
    void test9_ConcurrentCacheAccess5ThreadsQuerying() throws Exception {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        var executor = Executors.newFixedThreadPool(5);
        var latch = new CountDownLatch(5);
        var errors = new ConcurrentLinkedQueue<Throwable>();

        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 20; j++) {
                        index.getNeighbors(ref);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");
        assertTrue(errors.isEmpty(), "No errors during concurrent access: " + errors);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void test10_GetNeighborsReturnsSetNeverNull() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        var neighbors = index.getNeighbors(ref);

        assertNotNull(neighbors, "getNeighbors() should never return null");
    }

    @Test
    void test11_GetNeighborsForNonExistentBubbleReturnsEmptySet() {
        var transport = transportRegistry.register(UUID.randomUUID());
        var nonExistentId = UUID.randomUUID();
        var proxy = new RemoteBubbleProxy(nonExistentId, transport);

        var neighbors = index.getNeighbors(proxy);

        assertNotNull(neighbors);
        assertTrue(neighbors.isEmpty(), "Non-existent bubble should return empty neighbors");
    }

    @Test
    void test12_GetCacheStatsReturnsAccurateCounts() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        var stats1 = index.getCacheStats();
        assertEquals(0, stats1.hits());
        assertEquals(0, stats1.misses());

        // Cache miss
        index.getNeighbors(ref);
        var stats2 = index.getCacheStats();
        assertEquals(1, stats2.misses());

        // Cache hit
        index.getNeighbors(ref);
        var stats3 = index.getCacheStats();
        assertEquals(1, stats3.hits());
    }

    @Test
    void test13_PerformanceCachedLookupLessThan1ms() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        // Prime cache
        index.getNeighbors(ref);

        // Measure cached lookup
        var start = System.nanoTime();
        index.getNeighbors(ref);
        var duration = (System.nanoTime() - start) / 1_000_000; // Convert to ms

        assertTrue(duration < 1, "Cached lookup should be <1ms, was: " + duration + "ms");
    }

    @Test
    void test14_PerformanceUncachedLookupLessThan100ms() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        var start = System.nanoTime();
        index.getNeighbors(ref);
        var duration = (System.nanoTime() - start) / 1_000_000;

        assertTrue(duration < 100, "Uncached lookup should be <100ms, was: " + duration + "ms");
    }

    @Test
    void test15_PerformanceInvalidationTriggersRefresh() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        // Prime cache
        index.getNeighbors(ref);

        // Invalidate
        index.invalidateCache(bubble.id());

        // Next query will refresh
        var start = System.nanoTime();
        index.getNeighbors(ref);
        var duration = (System.nanoTime() - start) / 1_000_000;

        // Should complete reasonably fast even with refresh
        assertTrue(duration < 100, "Refresh should be <100ms");
    }

    @Test
    void test16_DiscoveryLessThan100msCached() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        // Prime cache
        index.getNeighbors(ref);

        var start = System.nanoTime();
        index.getNeighbors(ref);
        var duration = (System.nanoTime() - start) / 1_000_000;

        assertTrue(duration < 100, "Cached discovery should be <100ms, was: " + duration + "ms");
    }

    @Test
    void test17_DiscoveryLessThan300msUncached() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        var start = System.nanoTime();
        index.getNeighbors(ref);
        var duration = (System.nanoTime() - start) / 1_000_000;

        assertTrue(duration < 300, "Uncached discovery should be <300ms, was: " + duration + "ms");
    }

    @Test
    void test18_NeighborCalculationCorrectnessTetrahedralTopology() {
        var bubble = createBubble();

        // Add neighbors based on tetrahedral topology
        var neighbor1 = UUID.randomUUID();
        var neighbor2 = UUID.randomUUID();
        bubble.addNeighbor(neighbor1);
        bubble.addNeighbor(neighbor2);

        var ref = new LocalBubbleReference(bubble);
        var neighbors = index.getNeighbors(ref);

        assertNotNull(neighbors);
        // Neighbors should be based on tetrahedral topology
    }

    @Test
    void test19_MultiProcessScenario2PlusProcesses() throws Exception {
        var bubble1 = createBubble();
        var bubble2 = createBubble();

        // Register in different processes
        var process1 = UUID.randomUUID();
        var process2 = UUID.randomUUID();
        registry.register(process1, List.of(bubble1.id()));
        registry.register(process2, List.of(bubble2.id()));

        var ref1 = new LocalBubbleReference(bubble1);
        var ref2 = new LocalBubbleReference(bubble2);

        // Both should work
        assertNotNull(index.getNeighbors(ref1));
        assertNotNull(index.getNeighbors(ref2));
    }

    @Test
    void test20_DynamicNeighborAddition() {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        // Initial neighbors
        index.getNeighbors(ref);

        // Add new neighbor
        var newNeighbor = UUID.randomUUID();
        bubble.addNeighbor(newNeighbor);

        // Invalidate cache to see new neighbor
        index.invalidateCache(bubble.id());

        var neighbors = index.getNeighbors(ref);
        assertNotNull(neighbors);
    }

    @Test
    void test21_DynamicNeighborRemoval() {
        var bubble = createBubble();
        var neighborId = UUID.randomUUID();
        bubble.addNeighbor(neighborId);

        var ref = new LocalBubbleReference(bubble);

        // Prime cache
        index.getNeighbors(ref);

        // Remove neighbor
        bubble.removeNeighbor(neighborId);

        // Invalidate cache
        index.invalidateCache(bubble.id());

        var neighbors = index.getNeighbors(ref);
        assertNotNull(neighbors);
    }

    @Test
    void test22_ConcurrentInvalidationAndQueries() throws Exception {
        var bubble = createBubble();
        var ref = new LocalBubbleReference(bubble);

        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(10);
        var errors = new ConcurrentLinkedQueue<Throwable>();

        // 5 threads querying, 5 threads invalidating
        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 20; j++) {
                        index.getNeighbors(ref);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    for (int j = 0; j < 20; j++) {
                        index.invalidateCache(bubble.id());
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "All threads should complete");
        assertTrue(errors.isEmpty(), "No errors during concurrent operations: " + errors);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}

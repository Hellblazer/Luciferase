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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for CrossProcessNeighborIndex.
 *
 * @author hal.hildebrand
 */
class SimpleCrossProcessNeighborIndexTest {

    private ProcessRegistry registry;
    private CrossProcessNeighborIndex index;
    private LocalServerTransport.Registry transportRegistry;
    private VonBubble bubble;

    @BeforeEach
    void setUp() {
        registry = new ProcessRegistry();
        index = new CrossProcessNeighborIndex(registry);
        transportRegistry = LocalServerTransport.Registry.create();
        var transport = transportRegistry.register(UUID.randomUUID());
        bubble = new VonBubble(UUID.randomUUID(), (byte) 10, 100L, transport);
    }

    @AfterEach
    void tearDown() {
        if (bubble != null) {
            bubble.close();
        }
        if (transportRegistry != null) {
            transportRegistry.close();
        }
    }

    @Test
    void testGetNeighborsNeverNull() {
        var ref = new LocalBubbleReference(bubble);
        var neighbors = index.getNeighbors(ref);
        assertNotNull(neighbors);
    }

    @Test
    void testCacheHitImprovesDuration() throws Exception {
        var ref = new LocalBubbleReference(bubble);

        // First call - cache miss
        index.getNeighbors(ref);

        // Second call - cache hit
        var start = System.nanoTime();
        index.getNeighbors(ref);
        var duration = (System.nanoTime() - start) / 1_000_000; // ms

        assertTrue(duration < 10, "Cached lookup should be fast");
    }

    @Test
    void testCacheInvalidation() {
        var ref = new LocalBubbleReference(bubble);

        // Prime cache
        index.getNeighbors(ref);
        var stats1 = index.getCacheStats();

        // Invalidate
        index.invalidateCache(bubble.id());

        // Verify eviction count increased
        var stats2 = index.getCacheStats();
        assertEquals(stats1.evictions() + 1, stats2.evictions());
    }

    @Test
    void testCacheStatsAccurate() {
        var ref = new LocalBubbleReference(bubble);

        var stats1 = index.getCacheStats();

        // Cache miss
        index.getNeighbors(ref);
        var stats2 = index.getCacheStats();
        assertEquals(stats1.misses() + 1, stats2.misses());

        // Cache hit
        index.getNeighbors(ref);
        var stats3 = index.getCacheStats();
        assertEquals(stats2.hits() + 1, stats3.hits());
    }

    @Test
    void testMultiProcessScenario() throws Exception {
        // Create second bubble
        var transport2 = transportRegistry.register(UUID.randomUUID());
        var bubble2 = new VonBubble(UUID.randomUUID(), (byte) 10, 100L, transport2);

        // Register in different processes
        registry.register(UUID.randomUUID(), List.of(bubble.id()));
        registry.register(UUID.randomUUID(), List.of(bubble2.id()));

        var ref1 = new LocalBubbleReference(bubble);
        var ref2 = new LocalBubbleReference(bubble2);

        // Both should work
        assertNotNull(index.getNeighbors(ref1));
        assertNotNull(index.getNeighbors(ref2));

        bubble2.close();
    }

    @Test
    void testTTLExpiration() throws Exception {
        // Create index with short TTL
        var shortTTLIndex = new CrossProcessNeighborIndex(registry, 100L);

        var ref = new LocalBubbleReference(bubble);

        // Prime cache
        shortTTLIndex.getNeighbors(ref);

        // Wait for expiration
        Thread.sleep(150);

        // Next query should be cache miss
        var stats1 = shortTTLIndex.getCacheStats();
        shortTTLIndex.getNeighbors(ref);
        var stats2 = shortTTLIndex.getCacheStats();

        assertTrue(stats2.misses() > stats1.misses());
    }
}

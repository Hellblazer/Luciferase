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
package com.hellblazer.luciferase.esvo.dag.cache;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.dag.*;
import com.hellblazer.luciferase.sparse.core.CoordinateSpace;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for DAGCache.
 *
 * @author hal.hildebrand
 */
class DAGCacheTest {

    private DAGCache cache;

    @BeforeEach
    void setUp() {
        cache = new DAGCache(3, CacheEvictionPolicy.LRU);
    }

    @Test
    void testPutAndGet() {
        var data = mockDAGData("key1", 100);
        cache.put("key1", data);

        var retrieved = cache.get("key1");
        assertNotNull(retrieved);
        assertEquals(data, retrieved);
    }

    @Test
    void testGetNonExistent() {
        var retrieved = cache.get("nonexistent");
        assertNull(retrieved);
    }

    @Test
    void testLRUEviction() {
        var data1 = mockDAGData("key1", 100);
        var data2 = mockDAGData("key2", 200);
        var data3 = mockDAGData("key3", 300);
        var data4 = mockDAGData("key4", 400);

        cache.put("key1", data1);
        cache.put("key2", data2);
        cache.put("key3", data3);

        assertEquals(3, cache.size());

        // Adding 4th entry should evict key1 (least recently used)
        cache.put("key4", data4);

        assertEquals(3, cache.size());
        assertNull(cache.get("key1"), "key1 should have been evicted");
        assertNotNull(cache.get("key2"));
        assertNotNull(cache.get("key3"));
        assertNotNull(cache.get("key4"));
    }

    @Test
    void testLRUWithRecentAccess() {
        var data1 = mockDAGData("key1", 100);
        var data2 = mockDAGData("key2", 200);
        var data3 = mockDAGData("key3", 300);
        var data4 = mockDAGData("key4", 400);

        cache.put("key1", data1);
        cache.put("key2", data2);
        cache.put("key3", data3);

        // Access key1 to make it recently used
        cache.get("key1");

        // Adding 4th entry should evict key2 (now least recently used)
        cache.put("key4", data4);

        assertNotNull(cache.get("key1"), "key1 should still be in cache");
        assertNull(cache.get("key2"), "key2 should have been evicted");
        assertNotNull(cache.get("key3"));
        assertNotNull(cache.get("key4"));
    }

    @Test
    void testCacheStats() {
        var data1 = mockDAGData("key1", 100);
        cache.put("key1", data1);

        // Hit
        cache.get("key1");

        // Miss
        cache.get("nonexistent");

        var stats = cache.getStats();
        assertEquals(1, stats.hitCount());
        assertEquals(1, stats.missCount());
        assertEquals(0, stats.evictionCount());
    }

    @Test
    void testEvictionStats() {
        var data1 = mockDAGData("key1", 100);
        var data2 = mockDAGData("key2", 200);
        var data3 = mockDAGData("key3", 300);
        var data4 = mockDAGData("key4", 400);

        cache.put("key1", data1);
        cache.put("key2", data2);
        cache.put("key3", data3);
        cache.put("key4", data4); // Triggers eviction

        var stats = cache.getStats();
        assertEquals(1, stats.evictionCount());
    }

    @Test
    void testClear() {
        var data1 = mockDAGData("key1", 100);
        var data2 = mockDAGData("key2", 200);

        cache.put("key1", data1);
        cache.put("key2", data2);

        assertEquals(2, cache.size());

        cache.clear();

        assertEquals(0, cache.size());
        assertNull(cache.get("key1"));
        assertNull(cache.get("key2"));
    }

    @Test
    void testEstimatedMemoryBytes() {
        var data1 = mockDAGData("key1", 100);
        var data2 = mockDAGData("key2", 200);

        cache.put("key1", data1);
        cache.put("key2", data2);

        var estimatedMemory = cache.estimatedMemoryBytes();
        // Should be sum of node counts * 8 bytes per node
        assertEquals(300 * 8, estimatedMemory);
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        var executor = Executors.newFixedThreadPool(10);
        var futures = new ArrayList<Future<?>>();

        // Concurrent puts
        for (int i = 0; i < 100; i++) {
            var key = "key" + i;
            var data = mockDAGData(key, i * 100);
            futures.add(executor.submit(() -> cache.put(key, data)));
        }

        // Concurrent gets
        for (int i = 0; i < 100; i++) {
            var key = "key" + i;
            futures.add(executor.submit(() -> cache.get(key)));
        }

        // Wait for completion
        for (var future : futures) {
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                fail("Concurrent operation failed: " + e.getMessage());
            }
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Cache should maintain its size limit
        assertTrue(cache.size() <= 3);
    }

    @Test
    void testPutNullKey() {
        var data = mockDAGData("key", 100);
        assertThrows(NullPointerException.class, () -> cache.put(null, data));
    }

    @Test
    void testPutNullValue() {
        assertThrows(NullPointerException.class, () -> cache.put("key", null));
    }

    @Test
    void testGetNullKey() {
        assertThrows(NullPointerException.class, () -> cache.get(null));
    }

    @Test
    void testPutUpdateValue() {
        var data1 = mockDAGData("key1", 100);
        var data2 = mockDAGData("key1", 200);

        cache.put("key1", data1);
        cache.put("key1", data2);

        assertEquals(1, cache.size());
        assertEquals(data2, cache.get("key1"));
    }

    @Test
    void testEvictManually() {
        var data1 = mockDAGData("key1", 100);
        var data2 = mockDAGData("key2", 200);

        cache.put("key1", data1);
        cache.put("key2", data2);

        assertEquals(2, cache.size());

        cache.evict();

        assertEquals(1, cache.size());
    }

    /**
     * Create stub DAGOctreeData for testing.
     */
    private DAGOctreeData mockDAGData(String key, int nodeCount) {
        return new StubDAGData(nodeCount);
    }

    /**
     * Minimal stub implementation for testing.
     */
    private static class StubDAGData implements DAGOctreeData {
        private final int nodeCount;

        StubDAGData(int nodeCount) {
            this.nodeCount = nodeCount;
        }

        @Override
        public int nodeCount() {
            return nodeCount;
        }

        @Override
        public ESVONodeUnified[] nodes() {
            return new ESVONodeUnified[nodeCount];
        }

        @Override
        public PointerAddressingMode getAddressingMode() {
            return PointerAddressingMode.ABSOLUTE;
        }

        @Override
        public DAGMetadata getMetadata() {
            return new DAGMetadata(nodeCount, nodeCount, 8, 0,
                                   Map.of(), Duration.ZERO,
                                   HashAlgorithm.SHA256, CompressionStrategy.BALANCED, 0L);
        }

        @Override
        public float getCompressionRatio() {
            return 1.0f;
        }

        @Override
        public ByteBuffer nodesToByteBuffer() {
            return ByteBuffer.allocate(nodeCount * 8);
        }

        @Override
        public int[] getFarPointers() {
            return new int[0];
        }

        @Override
        public CoordinateSpace getCoordinateSpace() {
            return CoordinateSpace.UNIT_CUBE;
        }

        @Override
        public int sizeInBytes() {
            return nodeCount * 8;
        }

        @Override
        public int maxDepth() {
            return 1;
        }

        @Override
        public int leafCount() {
            return 0;
        }

        @Override
        public int internalCount() {
            return nodeCount;
        }
    }
}

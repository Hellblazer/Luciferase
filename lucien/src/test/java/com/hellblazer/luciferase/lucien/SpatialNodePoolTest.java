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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpatialNodePool focusing on the stale-childrenMask recycle bug
 * (Luciferase-7wzml.15): a pooled node released then re-acquired must have childrenMask == 0
 * so that hasChildren()/hasChild() return correct results on the recycled node.
 *
 * @author hal.hildebrand
 */
public class SpatialNodePoolTest {

    private SpatialNodePool<LongEntityID> pool;

    @BeforeEach
    void setUp() {
        pool = new SpatialNodePool<>(
            () -> new SpatialNodeImpl<>(10),
            new SpatialNodePool.PoolConfig()
                .withInitialSize(0)
                .withMaxSize(100)
                .withPreAllocation(false)
                .withStatistics(true)
        );
    }

    /**
     * Core regression test: a node with childrenMask != 0 that is released and
     * re-acquired must report no children and childrenMask == 0.
     */
    @Test
    void acquiredNodeAfterRelease_hasChildrenMaskReset() {
        // Acquire and dirty the node
        SpatialNodeImpl<LongEntityID> node = pool.acquire();
        node.setChildBit(3);
        node.setChildBit(5);
        assertTrue(node.hasChildren(), "precondition: node should have children after setChildBit");
        assertEquals((byte) ((1 << 3) | (1 << 5)), node.getChildrenMask(),
                     "precondition: childrenMask bits 3 and 5 should be set");

        // Also add an entity so clearEntities() is exercised
        node.addEntity(new LongEntityID(42L));
        assertFalse(node.isEmpty(), "precondition: node should have entity");

        // Release back to pool
        pool.release(node);

        // Acquire the same node (pool has exactly one node at this point)
        SpatialNodeImpl<LongEntityID> recycled = pool.acquire();

        // Both childrenMask and entity list must be clean
        assertEquals(0, recycled.getChildrenMask(), "recycled node must have childrenMask == 0");
        assertFalse(recycled.hasChildren(), "recycled node must report hasChildren() == false");
        assertFalse(recycled.hasChild(3), "recycled node must report hasChild(3) == false");
        assertFalse(recycled.hasChild(5), "recycled node must report hasChild(5) == false");
        assertTrue(recycled.isEmpty(), "recycled node must have no entities");
    }

    /**
     * Verify that cleanupEmptyNode-style reuse works correctly: a node whose bits were
     * all cleared via clearChildBit paths still round-trips cleanly through the pool.
     */
    @Test
    void acquiredNodeAfterRelease_allChildBitsCleared_stillSane() {
        SpatialNodeImpl<LongEntityID> node = pool.acquire();
        // Set all 8 bits then clear them individually (simulates cleanupEmptyNode pattern)
        for (int i = 0; i < 8; i++) {
            node.setChildBit(i);
        }
        assertEquals((byte) 0xFF, node.getChildrenMask(), "precondition: all bits set");
        for (int i = 0; i < 8; i++) {
            node.clearChildBit(i);
        }
        assertEquals(0, node.getChildrenMask(), "after clearChildBit all: mask must be 0");

        // Set one bit again to leave dirty state, then pool-cycle
        node.setChildBit(2);
        pool.release(node);

        SpatialNodeImpl<LongEntityID> recycled = pool.acquire();
        assertEquals(0, recycled.getChildrenMask(), "recycled node must have childrenMask == 0 even after partial dirty");
        assertFalse(recycled.hasChildren());
    }

    /**
     * Bounded-pool invariant: concurrent releases from N threads must never push the
     * pool past maxSize, and currentSize must match the actual queue depth after
     * all threads complete (no counter drift).
     *
     * Regression for Luciferase-7wzml.124: the old get-then-act TOCTOU allowed
     * concurrent releasers each observing size < maxSize and all offering, inflating
     * both pool and currentSize past the cap.
     */
    @Test
    void concurrentRelease_neverExceedsMaxSize() throws InterruptedException {
        int maxSize = 20;
        int nThreads = 16;
        int nodesPerThread = 50;

        SpatialNodePool<LongEntityID> cappedPool = new SpatialNodePool<>(
            () -> new SpatialNodeImpl<>(10),
            new SpatialNodePool.PoolConfig()
                .withInitialSize(0)
                .withMaxSize(maxSize)
                .withPreAllocation(false)
                .withStatistics(false)
        );

        // Pre-create nodes outside the pool (simulates nodes acquired and now being returned)
        List<List<SpatialNodeImpl<LongEntityID>>> threadNodes = new ArrayList<>();
        for (int t = 0; t < nThreads; t++) {
            List<SpatialNodeImpl<LongEntityID>> nodes = new ArrayList<>();
            for (int i = 0; i < nodesPerThread; i++) {
                nodes.add(new SpatialNodeImpl<>(10));
            }
            threadNodes.add(nodes);
        }

        CountDownLatch ready = new CountDownLatch(nThreads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(nThreads);

        for (int t = 0; t < nThreads; t++) {
            List<SpatialNodeImpl<LongEntityID>> nodes = threadNodes.get(t);
            exec.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (SpatialNodeImpl<LongEntityID> node : nodes) {
                    cappedPool.release(node);
                }
            });
        }

        ready.await();
        go.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "threads did not finish in time");

        int reportedSize = cappedPool.size();
        int queueDepth = cappedPool.getQueueDepth();

        assertTrue(reportedSize <= maxSize,
                   "currentSize " + reportedSize + " exceeded maxSize " + maxSize);
        assertEquals(reportedSize, queueDepth,
                     "currentSize " + reportedSize + " drifted from actual queue depth " + queueDepth);
    }

    /**
     * Serial over-release: releasing more nodes than maxSize must never grow the pool
     * beyond the configured cap (basic single-threaded bound check).
     */
    @Test
    void release_beyondMaxSize_doesNotGrowPool() {
        int maxSize = 5;
        SpatialNodePool<LongEntityID> cappedPool = new SpatialNodePool<>(
            () -> new SpatialNodeImpl<>(10),
            new SpatialNodePool.PoolConfig()
                .withInitialSize(0)
                .withMaxSize(maxSize)
                .withPreAllocation(false)
                .withStatistics(false)
        );

        // Release 3x more nodes than the cap
        for (int i = 0; i < maxSize * 3; i++) {
            cappedPool.release(new SpatialNodeImpl<>(10));
        }

        assertTrue(cappedPool.size() <= maxSize,
                   "pool size " + cappedPool.size() + " must not exceed maxSize " + maxSize);
        assertEquals(cappedPool.size(), cappedPool.getQueueDepth(),
                     "currentSize must equal actual queue depth after serial over-release");
    }

    /**
     * Concurrent shrink/acquire must not desync currentSize from actual queue depth.
     * Regression for Luciferase-7wzml.126: shrink() polled and decremented non-atomically,
     * allowing concurrent acquire() to race with the poll, leaving currentSize negative
     * or mismatched vs the actual queue contents.
     */
    @Test
    void concurrentShrinkAndAcquire_counterStaysConsistent() throws InterruptedException {
        int maxSize = 50;
        int initialFill = maxSize;
        int targetShrink = 10;
        int nAcquireThreads = 8;
        int nShrinkThreads = 4;

        SpatialNodePool<LongEntityID> stressPool = new SpatialNodePool<>(
            () -> new SpatialNodeImpl<>(10),
            new SpatialNodePool.PoolConfig()
                .withInitialSize(0)
                .withMaxSize(maxSize)
                .withPreAllocation(false)
                .withStatistics(false)
        );

        // Pre-fill pool
        for (int i = 0; i < initialFill; i++) {
            stressPool.release(new SpatialNodeImpl<>(10));
        }
        assertEquals(initialFill, stressPool.size(), "precondition: pool filled to " + initialFill);

        CountDownLatch ready = new CountDownLatch(nAcquireThreads + nShrinkThreads);
        CountDownLatch go    = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(nAcquireThreads + nShrinkThreads);

        // Threads that concurrently acquire from the pool
        for (int t = 0; t < nAcquireThreads; t++) {
            exec.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                for (int i = 0; i < 5; i++) {
                    stressPool.acquire(); // intentionally not re-releasing
                }
            });
        }

        // Threads that concurrently shrink the pool
        for (int t = 0; t < nShrinkThreads; t++) {
            exec.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                stressPool.shrink(targetShrink);
            });
        }

        ready.await();
        go.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "threads did not finish in time");

        int reportedSize = stressPool.size();
        int queueDepth   = stressPool.getQueueDepth();

        assertTrue(reportedSize >= 0,
                   "currentSize " + reportedSize + " must not be negative");
        assertEquals(reportedSize, queueDepth,
                     "currentSize " + reportedSize + " drifted from actual queue depth " + queueDepth);
    }

    /**
     * Pool hit counter sanity: verify the pool correctly records a hit on re-acquire
     * after release, confirming node identity (same object) is returned from the pool.
     */
    @Test
    void poolHitCountIncrements_onRecycledAcquire() {
        SpatialNodeImpl<LongEntityID> node = pool.acquire();
        node.setChildBit(0);
        pool.release(node);

        long hitsBefore = pool.getStats().getHits();
        SpatialNodeImpl<LongEntityID> recycled = pool.acquire();
        assertEquals(hitsBefore + 1, pool.getStats().getHits(), "pool should record one hit on recycled acquire");
        assertSame(node, recycled, "pool should return the same node instance");
        assertEquals(0, recycled.getChildrenMask(), "recycled node childrenMask must be 0");
    }
}

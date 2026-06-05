/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.internal;

import com.hellblazer.luciferase.lucien.entity.EntityDistance;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luciferase-up7uz: {@code borrowWithComparator} always allocated a new PriorityQueue (zero pooling on the hot k-NN
 * path), and the single mixed pool could hand a comparator-bearing queue to a plain {@code borrow()}. The fix caches
 * the comparator singleton and pools by comparator identity, with separate plain/comparator deques.
 *
 * <p>Luciferase-7wzml.129: borrowArrayList/returnArrayList must enforce the same-thread contract (ThreadLocal pools
 * silently corrupt when a list is returned on a different thread); plus capacity-correctness invariant.
 *
 * @author hal.hildebrand
 */
class ObjectPoolsKnnPoolingTest {

    /**
     * Drain this thread's pools before each test. The pools are per-thread {@link ThreadLocal} state
     * shared across the whole surefire fork, so a sibling test that borrows+returns a comparator-bearing
     * queue leaves residue at the head of the comparator deque, which defeats the reuse-identity assertions
     * below (the mismatch path allocates fresh on every borrow). Draining makes these tests deterministic.
     */
    @BeforeEach
    void drainPools() {
        ObjectPools.clearThreadLocalPoolsForTesting();
    }

    @Test
    void maxHeapComparatorIsACachedSingleton() {
        assertSame(EntityDistance.<LongEntityID>maxHeapComparator(), EntityDistance.<LongEntityID>maxHeapComparator(),
                   "maxHeapComparator must return a cached singleton (Luciferase-up7uz)");
    }

    @Test
    void comparatorPathGenuinelyPools() {
        var cmp = EntityDistance.<LongEntityID>maxHeapComparator();
        PriorityQueue<EntityDistance<LongEntityID>> q1 = ObjectPools.borrowPriorityQueue(cmp);
        ObjectPools.returnPriorityQueue(q1);
        PriorityQueue<EntityDistance<LongEntityID>> q2 = ObjectPools.borrowPriorityQueue(cmp);

        assertSame(q1, q2, "the comparator-path k-NN queue must be reused from the pool, not freshly allocated "
                           + "(Luciferase-up7uz)");
    }

    @Test
    void plainAndComparatorQueuesDoNotCrossContaminate() {
        // Return a comparator queue, then a plain borrow must NOT receive it (no stray comparator).
        var cmp = EntityDistance.<LongEntityID>maxHeapComparator();
        ObjectPools.returnPriorityQueue(ObjectPools.borrowPriorityQueue(cmp));

        PriorityQueue<EntityDistance<LongEntityID>> plain = ObjectPools.borrowPriorityQueue();
        assertNull(plain.comparator(), "a plain borrow must never receive a comparator-bearing queue (Luciferase-up7uz)");
    }

    @Test
    void mismatchedComparatorDoesNotBleedThePool() {
        var a = EntityDistance.<LongEntityID>maxHeapComparator();
        PriorityQueue<EntityDistance<LongEntityID>> q = ObjectPools.borrowPriorityQueue(a);
        ObjectPools.returnPriorityQueue(q);

        // Borrow with a DIFFERENT comparator instance (a fresh lambda): must not consume the pooled 'a' queue.
        java.util.Comparator<EntityDistance<LongEntityID>> b = (x, y) -> Float.compare(x.distance(), y.distance());
        PriorityQueue<EntityDistance<LongEntityID>> fresh = ObjectPools.borrowPriorityQueue(b);
        assertNotSame(q, fresh, "a mismatched-comparator borrow must not reuse the pooled queue");

        // The original pooled queue must still be available — the mismatch path must put it back, not drop it.
        PriorityQueue<EntityDistance<LongEntityID>> again = ObjectPools.borrowPriorityQueue(a);
        assertSame(q, again, "the comparator pool must not bleed capacity on a comparator mismatch (Luciferase-up7uz)");
    }

    // ---- Luciferase-7wzml.129: same-thread contract + capacity-correctness ----

    /**
     * borrowArrayList / returnArrayList must enforce the ThreadLocal same-thread contract: a list borrowed on thread A
     * and returned on thread B must throw AssertionError when assertions are enabled (-ea).
     */
    @Test
    void crossThreadReturnThrowsAssertionError() throws InterruptedException {
        ArrayList<Integer> list = ObjectPools.borrowArrayList();
        var errorRef  = new AtomicReference<Throwable>();
        var completed = new AtomicBoolean(false);

        Thread other = new Thread(() -> {
            try {
                ObjectPools.returnArrayList(list);
                completed.set(true);
            } catch (AssertionError ae) {
                errorRef.set(ae);
            }
        });
        other.start();
        other.join(2000);

        boolean assertionsEnabled = ObjectPools.class.desiredAssertionStatus();
        if (assertionsEnabled) {
            assertNotNull(errorRef.get(),
                          "returnArrayList on a foreign thread must throw AssertionError when -ea is active"
                          + " (Luciferase-7wzml.129)");
        } else {
            // Without -ea the assertion is a no-op: the call must complete without error.
            assertTrue(completed.get() || errorRef.get() == null,
                       "returnArrayList on a foreign thread must not throw when -ea is inactive");
        }
    }

    /**
     * A list borrowed from the pool must be empty so callers receive a clean slate (returnArrayList clears first,
     * then re-pools — verifies the invariant holds across a round-trip).
     */
    @Test
    void borrowedArrayListIsAlwaysEmpty() {
        ArrayList<String> a = ObjectPools.borrowArrayList();
        a.add("stale");
        ObjectPools.returnArrayList(a);

        ArrayList<String> b = ObjectPools.borrowArrayList();
        assertEquals(0, b.size(),
                     "borrowed ArrayList must be empty — returnArrayList must clear before re-pooling"
                     + " (Luciferase-7wzml.129)");
        ObjectPools.returnArrayList(b);
    }

    /**
     * borrowArrayList(int) must return a non-null, empty list — same-thread contract applies and the capacity hint
     * must not break the empty-on-borrow invariant.
     */
    @Test
    void borrowArrayListWithCapacityReturnsList() {
        ArrayList<Long> list = ObjectPools.borrowArrayList(64);
        assertNotNull(list, "borrowArrayList(int) must return a non-null list (Luciferase-7wzml.129)");
        assertEquals(0, list.size(), "borrowed list must be empty (Luciferase-7wzml.129)");
        ObjectPools.returnArrayList(list);
    }
}

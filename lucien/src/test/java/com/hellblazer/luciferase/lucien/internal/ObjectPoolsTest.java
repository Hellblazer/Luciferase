/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the type-safety / borrow-return contract of {@link ObjectPools} (Luciferase-7wzml.131).
 *
 * <p>Because the pool is type-erased, the key safety invariant is: pooled collections are always
 * empty when returned to a caller. This suite exercises the scoped wrappers that guarantee the
 * invariant, and validates that {@code withPriorityQueue} was added for API parity.
 *
 * @author hal.hildebrand
 */
class ObjectPoolsTest {

    // ---- ArrayList -------------------------------------------------------

    @Test
    void borrowedArrayListIsEmpty() {
        ArrayList<String> list = ObjectPools.borrowArrayList();
        try {
            assertTrue(list.isEmpty(), "borrowed list must be empty");
        } finally {
            ObjectPools.returnArrayList(list);
        }
    }

    @Test
    void returnedArrayListIsClearedBeforePooling() {
        // borrow, populate, return — next borrow on same thread must be empty
        ArrayList<Integer> list = ObjectPools.borrowArrayList();
        list.add(42);
        ObjectPools.returnArrayList(list);

        ArrayList<Integer> list2 = ObjectPools.borrowArrayList();
        try {
            assertTrue(list2.isEmpty(), "list must be cleared on return before re-pooling");
        } finally {
            ObjectPools.returnArrayList(list2);
        }
    }

    @Test
    void withArrayListGuaranteesReturn() {
        // withArrayList must return-in-finally even if the function throws
        ArrayList<?>[] captured = { null };
        assertThrows(RuntimeException.class, () ->
            ObjectPools.<String, Void>withArrayList(list -> {
                captured[0] = list;
                throw new RuntimeException("intentional");
            })
        );
        // The captured list should have been returned; it must now be empty
        assertNotNull(captured[0]);
        assertTrue(captured[0].isEmpty());
    }

    @Test
    void withArrayListReturnsResult() {
        int result = ObjectPools.<String, Integer>withArrayList(list -> {
            list.add("a");
            list.add("b");
            return list.size();
        });
        assertEquals(2, result);
    }

    // ---- HashSet --------------------------------------------------------

    @Test
    void borrowedHashSetIsEmpty() {
        HashSet<String> set = ObjectPools.borrowHashSet();
        try {
            assertTrue(set.isEmpty(), "borrowed set must be empty");
        } finally {
            ObjectPools.returnHashSet(set);
        }
    }

    @Test
    void withHashSetGuaranteesReturn() {
        HashSet<?>[] captured = { null };
        assertThrows(RuntimeException.class, () ->
            ObjectPools.<String, Void>withHashSet(set -> {
                captured[0] = set;
                throw new RuntimeException("intentional");
            })
        );
        assertNotNull(captured[0]);
        assertTrue(captured[0].isEmpty());
    }

    // ---- PriorityQueue --------------------------------------------------

    @Test
    void borrowedPriorityQueueIsEmpty() {
        PriorityQueue<Integer> pq = ObjectPools.borrowPriorityQueue();
        try {
            assertTrue(pq.isEmpty(), "borrowed PriorityQueue must be empty");
        } finally {
            ObjectPools.returnPriorityQueue(pq);
        }
    }

    @Test
    void withPriorityQueueNaturalOrderReturnsResult() {
        // Verifies the scoped natural-order withPriorityQueue wrapper added for API parity
        int result = ObjectPools.<Integer, Integer>withPriorityQueue(pq -> {
            pq.add(3);
            pq.add(1);
            pq.add(2);
            return pq.poll(); // natural order: smallest first
        });
        assertEquals(1, result);
    }

    @Test
    void withPriorityQueueComparatorReturnsResult() {
        // Verifies the scoped comparator withPriorityQueue wrapper added for API parity
        Comparator<Integer> reverseOrder = Comparator.reverseOrder();
        int result = ObjectPools.<Integer, Integer>withPriorityQueue(reverseOrder, pq -> {
            pq.add(3);
            pq.add(1);
            pq.add(2);
            return pq.poll(); // reverse: largest first
        });
        assertEquals(3, result);
    }

    @Test
    void withPriorityQueueGuaranteesReturnOnThrow() {
        PriorityQueue<?>[] captured = { null };
        assertThrows(RuntimeException.class, () ->
            ObjectPools.<Integer, Void>withPriorityQueue(pq -> {
                captured[0] = pq;
                throw new RuntimeException("intentional");
            })
        );
        assertNotNull(captured[0]);
        assertTrue(captured[0].isEmpty());
    }

    @Test
    void returnNullIsNoOp() {
        // Returning null must not throw
        assertDoesNotThrow(() -> ObjectPools.returnArrayList(null));
        assertDoesNotThrow(() -> ObjectPools.returnHashSet(null));
        assertDoesNotThrow(() -> ObjectPools.returnPriorityQueue(null));
    }
}

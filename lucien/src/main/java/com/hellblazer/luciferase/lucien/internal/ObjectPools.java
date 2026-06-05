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
package com.hellblazer.luciferase.lucien.internal;

import java.util.*;

/**
 * Object pools for frequently allocated objects to reduce GC pressure.
 *
 * <p><strong>Thread-ownership contract (Luciferase-7wzml.129):</strong> All pool operations
 * ({@code borrow*} / {@code return*}) are backed by {@link ThreadLocal} and must be called on the
 * <em>same thread</em>. Returning a list on a different thread silently deposits it into the wrong
 * thread's pool and leaks the borrowing thread's pool slot. When Java assertions are enabled
 * ({@code -ea}), {@code returnArrayList} verifies ownership and throws {@link AssertionError} on a
 * violation. Callers that cross thread boundaries (e.g. inside a {@code CompletableFuture} continuation)
 * must use the try-finally wrappers {@link #withArrayList} / {@link #withHashSet} and ensure the
 * finally block runs on the borrowing thread, or accept a fresh allocation instead of pooling.
 *
 * @author hal.hildebrand
 */
public class ObjectPools {

    /**
     * Registry used (only when assertions are enabled) to tag each borrowed ArrayList with its
     * owning thread so that {@link #returnArrayList} can detect cross-thread returns.
     *
     * <p><strong>Identity semantics (Luciferase-7wzml.129 fix):</strong> Uses
     * {@link IdentityHashMap} wrapped in {@code Collections.synchronizedMap} so that map keys are
     * compared by object identity ({@code ==} / {@link System#identityHashCode}), not by
     * {@link ArrayList#equals}/{@link ArrayList#hashCode} (which are content-based via
     * {@link AbstractList}). The content-based path caused two problems:
     * <ol>
     *   <li>Two threads borrowing empty lists produced equal keys (all empty ArrayLists have
     *       {@code hashCode=1}), so thread B's {@code put} silently overwrote thread A's owner
     *       entry, causing spurious {@link AssertionError} on thread A's return.</li>
     *   <li>{@code ConcurrentHashMap.put} traversed the ArrayList's content via
     *       {@code hashCode()} while another thread was concurrently mutating the list, causing
     *       {@link java.util.ConcurrentModificationException} inside the map.</li>
     * </ol>
     * With identity semantics, distinct list instances never collide and no content traversal
     * occurs.
     */
    private static final Map<ArrayList<?>, Thread> BORROW_OWNER =
            Collections.synchronizedMap(new IdentityHashMap<>());

    // Thread-local pools for single-threaded access patterns
    private static final ThreadLocal<ArrayListPool>      ARRAY_LIST_POOL    = ThreadLocal.withInitial(ArrayListPool::new);
    private static final ThreadLocal<HashSetPool>        HASH_SET_POOL      = ThreadLocal.withInitial(HashSetPool::new);
    private static final ThreadLocal<PriorityQueuePool>  PRIORITY_QUEUE_POOL = ThreadLocal.withInitial(PriorityQueuePool::new);

    /**
     * Borrow an ArrayList from the thread-local pool.
     *
     * <p><strong>Same-thread contract:</strong> the returned list must be returned via
     * {@link #returnArrayList} on the <em>same thread</em> that called this method.
     * Cross-thread returns silently corrupt the pool; use {@link #withArrayList} for
     * call-sites that span thread boundaries.
     */
    @SuppressWarnings("unchecked")
    public static <T> ArrayList<T> borrowArrayList() {
        var list = (ArrayList<T>) ARRAY_LIST_POOL.get().borrow();
        assert registerBorrow(list);
        return list;
    }

    /**
     * Borrow an ArrayList with an initial capacity hint from the thread-local pool.
     *
     * <p><strong>Same-thread contract:</strong> see {@link #borrowArrayList()}.
     */
    @SuppressWarnings("unchecked")
    public static <T> ArrayList<T> borrowArrayList(int initialCapacity) {
        var list = (ArrayList<T>) ARRAY_LIST_POOL.get().borrow();
        list.ensureCapacity(initialCapacity);
        assert registerBorrow(list);
        return list;
    }

    /**
     * Return an ArrayList to the thread-local pool.
     *
     * <p><strong>Same-thread contract:</strong> this method must be called on the same thread
     * that originally called {@link #borrowArrayList()}. When Java assertions are enabled
     * ({@code -ea}), a cross-thread return throws {@link AssertionError}.
     */
    public static <T> void returnArrayList(ArrayList<T> list) {
        if (list != null) {
            assert checkReturnThread(list) : "returnArrayList called on a thread that did not borrow this list"
                    + " — ThreadLocal pool corruption (Luciferase-7wzml.129)";
            list.clear();
            ARRAY_LIST_POOL.get().returnToPool(list);
        }
    }

    // ---- assertion helpers (compiled away when -ea is absent) ----

    /** Registers the borrow; always returns {@code true} so it is usable inside {@code assert}. */
    private static boolean registerBorrow(ArrayList<?> list) {
        BORROW_OWNER.put(list, Thread.currentThread());
        return true;
    }

    /**
     * Checks that the returning thread matches the borrowing thread; removes the registry entry.
     * Returns {@code true} on success so it is usable inside {@code assert expr : message}.
     * Returns {@code false} (triggering the AssertionError) on mismatch.
     */
    private static boolean checkReturnThread(ArrayList<?> list) {
        Thread owner = BORROW_OWNER.remove(list);
        // If no owner is registered (list was not borrowed via this API, or assertions were off
        // at borrow time), we allow the return silently to avoid false positives.
        return owner == null || owner == Thread.currentThread();
    }
    
    /**
     * Borrow a HashSet from the thread-local pool
     */
    @SuppressWarnings("unchecked")
    public static <T> HashSet<T> borrowHashSet() {
        return (HashSet<T>) HASH_SET_POOL.get().borrow();
    }
    
    /**
     * Return a HashSet to the pool
     */
    public static <T> void returnHashSet(HashSet<T> set) {
        if (set != null) {
            set.clear();
            HASH_SET_POOL.get().returnToPool(set);
        }
    }
    
    /**
     * Execute a function with a borrowed ArrayList
     */
    public static <T, R> R withArrayList(java.util.function.Function<ArrayList<T>, R> function) {
        ArrayList<T> list = borrowArrayList();
        try {
            return function.apply(list);
        } finally {
            returnArrayList(list);
        }
    }
    
    /**
     * Execute a function with a borrowed HashSet
     */
    public static <T, R> R withHashSet(java.util.function.Function<HashSet<T>, R> function) {
        HashSet<T> set = borrowHashSet();
        try {
            return function.apply(set);
        } finally {
            returnHashSet(set);
        }
    }
    
    /**
     * Borrow a PriorityQueue from the thread-local pool
     */
    @SuppressWarnings("unchecked")
    public static <T> PriorityQueue<T> borrowPriorityQueue() {
        return (PriorityQueue<T>) PRIORITY_QUEUE_POOL.get().borrow();
    }
    
    /**
     * Borrow a PriorityQueue with a specific comparator
     */
    @SuppressWarnings("unchecked")
    public static <T> PriorityQueue<T> borrowPriorityQueue(Comparator<? super T> comparator) {
        return (PriorityQueue<T>) PRIORITY_QUEUE_POOL.get().borrowWithComparator(comparator);
    }
    
    /**
     * Return a PriorityQueue to the pool
     */
    public static <T> void returnPriorityQueue(PriorityQueue<T> queue) {
        if (queue != null) {
            queue.clear();
            PRIORITY_QUEUE_POOL.get().returnToPool(queue);
        }
    }
    
    /**
     * Thread-local pool for ArrayLists
     */
    private static class ArrayListPool {
        private final Deque<ArrayList<?>> pool = new ArrayDeque<>(10);
        private static final int MAX_POOL_SIZE = 10;
        
        @SuppressWarnings("unchecked")
        public <T> ArrayList<T> borrow() {
            var list = pool.pollFirst();
            return list != null ? (ArrayList<T>) list : new ArrayList<T>();
        }
        
        public void returnToPool(ArrayList<?> list) {
            if (pool.size() < MAX_POOL_SIZE && list.size() == 0) {
                pool.offerLast(list);
            }
        }
    }
    
    /**
     * Thread-local pool for HashSets
     */
    private static class HashSetPool {
        private final Deque<HashSet<?>> pool = new ArrayDeque<>(10);
        private static final int MAX_POOL_SIZE = 10;
        
        @SuppressWarnings("unchecked")
        public <T> HashSet<T> borrow() {
            var set = pool.pollFirst();
            return set != null ? (HashSet<T>) set : new HashSet<T>();
        }
        
        public void returnToPool(HashSet<?> set) {
            if (pool.size() < MAX_POOL_SIZE && set.size() == 0) {
                pool.offerLast(set);
            }
        }
    }
    
    /**
     * Thread-local pool for PriorityQueues (Luciferase-up7uz). Separate deques for plain (no-comparator) and
     * fixed-comparator queues: a PriorityQueue's comparator is immutable after construction, so the comparator path
     * can only reuse a queue built with the SAME comparator instance — which the k-NN hot path provides via the
     * cached {@code EntityDistance.maxHeapComparator()} singleton. This replaces the previous
     * {@code borrowWithComparator} that always allocated a new queue (zero pooling on the highest-frequency caller)
     * and the single mixed deque that could hand a comparator-bearing queue to a plain {@code borrow()}.
     */
    private static class PriorityQueuePool {
        private final Deque<PriorityQueue<?>> plainPool      = new ArrayDeque<>(10);
        private final Deque<PriorityQueue<?>> comparatorPool = new ArrayDeque<>(10);
        private static final int MAX_POOL_SIZE = 10;

        @SuppressWarnings("unchecked")
        public <T> PriorityQueue<T> borrow() {
            var queue = plainPool.pollFirst();
            return queue != null ? (PriorityQueue<T>) queue : new PriorityQueue<T>();
        }

        @SuppressWarnings("unchecked")
        public <T> PriorityQueue<T> borrowWithComparator(Comparator<? super T> comparator) {
            var queue = comparatorPool.pollFirst();
            if (queue != null) {
                if (queue.comparator() == comparator) {
                    return (PriorityQueue<T>) queue; // same comparator instance, already empty — genuine reuse
                }
                // Different comparator instance: put the polled queue back so the pool does not bleed capacity
                // (Luciferase-up7uz review), then build a fresh queue for this comparator.
                comparatorPool.offerFirst(queue);
            }
            return new PriorityQueue<T>(comparator);
        }

        public void returnToPool(PriorityQueue<?> queue) {
            if (queue.size() != 0) {
                return;
            }
            if (queue.comparator() == null) {
                if (plainPool.size() < MAX_POOL_SIZE) {
                    plainPool.offerLast(queue);
                }
            } else if (comparatorPool.size() < MAX_POOL_SIZE) {
                comparatorPool.offerLast(queue);
            }
        }
    }
}
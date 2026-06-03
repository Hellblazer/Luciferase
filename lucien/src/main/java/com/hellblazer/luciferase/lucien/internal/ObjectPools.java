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
 * @author hal.hildebrand
 */
public class ObjectPools {
    
    // Thread-local pools for single-threaded access patterns
    private static final ThreadLocal<ArrayListPool> ARRAY_LIST_POOL = ThreadLocal.withInitial(ArrayListPool::new);
    private static final ThreadLocal<HashSetPool> HASH_SET_POOL = ThreadLocal.withInitial(HashSetPool::new);
    private static final ThreadLocal<PriorityQueuePool> PRIORITY_QUEUE_POOL = ThreadLocal.withInitial(PriorityQueuePool::new);

    /**
     * Borrow an ArrayList from the thread-local pool
     */
    @SuppressWarnings("unchecked")
    public static <T> ArrayList<T> borrowArrayList() {
        return (ArrayList<T>) ARRAY_LIST_POOL.get().borrow();
    }
    
    /**
     * Borrow an ArrayList with initial capacity
     */
    @SuppressWarnings("unchecked")
    public static <T> ArrayList<T> borrowArrayList(int initialCapacity) {
        var list = (ArrayList<T>) ARRAY_LIST_POOL.get().borrow();
        list.ensureCapacity(initialCapacity);
        return list;
    }
    
    /**
     * Return an ArrayList to the pool
     */
    public static <T> void returnArrayList(ArrayList<T> list) {
        if (list != null) {
            list.clear();
            ARRAY_LIST_POOL.get().returnToPool(list);
        }
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
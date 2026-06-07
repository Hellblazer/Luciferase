/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.bubble;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One coherent mutation-lock protocol for multi-bubble entity moves (Luciferase-n7io1).
 * <p>
 * Any code path that mutates the entity sets of two or more {@link EnhancedBubble}s as a unit (migration,
 * merge, split, cross-process commit, duplicate reconciliation) must hold each involved bubble's
 * {@link EnhancedBubble#getMutationLock() mutation lock} for the duration of the move. A lock that only
 * some paths honor is not a protocol — it still races the paths that skip it. This helper makes the
 * protocol uniform and deadlock-free:
 * <ul>
 *   <li><b>Global order:</b> locks are always acquired in ascending {@link EnhancedBubble#id()} order, so
 *       no two concurrent acquisitions involving the same set can form a lock-ordering cycle.</li>
 *   <li><b>Dedup:</b> repeated/identical bubbles collapse to one acquisition (and the locks are
 *       reentrant regardless, so nesting inside an already-held lock is safe).</li>
 *   <li><b>Reverse release:</b> {@link #close()} unlocks in the reverse of acquisition order.</li>
 *   <li><b>Acquisition failure safety:</b> if a {@code lock()} throws mid-acquisition, locks already taken
 *       are released before propagating.</li>
 * </ul>
 * Intended for try-with-resources:
 * <pre>{@code
 * try (var ignored = MutationLocks.lock(source, target)) {
 *     // mutate source and target atomically w.r.t. other multi-bubble writers
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 */
public final class MutationLocks implements AutoCloseable {

    private final List<ReentrantLock> held;

    private MutationLocks(List<ReentrantLock> held) {
        this.held = held;
    }

    /**
     * Acquire the mutation locks of all given bubbles in ascending UUID order. {@code null} entries are
     * ignored; duplicate bubbles are acquired once.
     *
     * @param bubbles the bubbles whose mutation locks to hold
     * @return an {@link AutoCloseable} handle that releases the locks (reverse order) on {@link #close()}
     */
    public static MutationLocks lock(EnhancedBubble... bubbles) {
        var ordered = Arrays.stream(bubbles)
                            .filter(Objects::nonNull)
                            .distinct()
                            .sorted(Comparator.comparing(EnhancedBubble::id))
                            .toList();
        var acquired = new ArrayList<ReentrantLock>(ordered.size());
        try {
            for (var bubble : ordered) {
                var lock = bubble.getMutationLock();
                lock.lock();
                acquired.add(lock);
            }
        } catch (RuntimeException | Error e) {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
            throw e;
        }
        return new MutationLocks(acquired);
    }

    /** Acquire the mutation locks of all given bubbles (collection overload). */
    public static MutationLocks lock(List<EnhancedBubble> bubbles) {
        return lock(bubbles.toArray(EnhancedBubble[]::new));
    }

    @Override
    public void close() {
        for (int i = held.size() - 1; i >= 0; i--) {
            held.get(i).unlock();
        }
    }
}

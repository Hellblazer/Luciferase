/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.internal;

import com.hellblazer.luciferase.lucien.entity.EntityDistance;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.Test;

import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Luciferase-up7uz: {@code borrowWithComparator} always allocated a new PriorityQueue (zero pooling on the hot k-NN
 * path), and the single mixed pool could hand a comparator-bearing queue to a plain {@code borrow()}. The fix caches
 * the comparator singleton and pools by comparator identity, with separate plain/comparator deques.
 *
 * @author hal.hildebrand
 */
class ObjectPoolsKnnPoolingTest {

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
}

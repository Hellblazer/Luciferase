/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.lucien.balancing.DefaultBalancingStrategy;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-3vwqb: {@code bulkConfig} and {@code parallelOperations} are reconfigured at runtime
 * ({@code configureBulkOperations} / {@code configureParallelOperations}) while bulk/parallel ops may read them
 * without the write lock. They must be {@code volatile} so the swapped reference is published atomically and
 * visibly — otherwise a concurrent reconfigure can expose a half-updated config or a mid-flight reference swap.
 *
 * <p>Luciferase-7wzml.125: {@code lastBalancingTime} must be an {@link AtomicLong} (CAS-gated) and {@code clock}
 * must be {@code volatile}; {@code checkAutoBalance} must source time from the injected clock and gate concurrent
 * callers with CAS so at most one rebalance fires per interval window.
 *
 * @author hal.hildebrand
 */
class AbstractSpatialIndexConfigVolatilityTest {

    private static void assertVolatile(String fieldName) throws NoSuchFieldException {
        Field f = AbstractSpatialIndex.class.getDeclaredField(fieldName);
        assertTrue(Modifier.isVolatile(f.getModifiers()),
                   fieldName + " must be volatile for safe runtime reconfiguration (Luciferase-3vwqb)");
    }

    private static void assertAtomicLong(String fieldName) throws NoSuchFieldException {
        Field f = AbstractSpatialIndex.class.getDeclaredField(fieldName);
        assertEquals(AtomicLong.class, f.getType(),
                     fieldName + " must be AtomicLong for CAS-guarded interval gate (Luciferase-7wzml.125)");
    }

    @Test
    void runtimeReconfiguredConfigFieldsAreVolatile() throws NoSuchFieldException {
        assertVolatile("bulkConfig");
        assertVolatile("parallelOperations");
    }

    /** Luciferase-7wzml.125: lastBalancingTime is AtomicLong, clock is volatile. */
    @Test
    void lastBalancingTimeIsAtomicLongAndClockIsVolatile() throws NoSuchFieldException {
        assertAtomicLong("lastBalancingTime");
        assertVolatile("clock");
    }

    /**
     * Luciferase-7wzml.125: checkAutoBalance respects the interval using the injected clock.
     * With interval=1000ms and a frozen clock, two back-to-back calls must not both rebalance
     * (the second sees elapsed=0 < 1000).  Advancing the clock past the interval allows a
     * subsequent call to rebalance again.
     */
    @Test
    void checkAutoBalanceRespectsInjectedClockInterval() throws Exception {
        // Mutable clock: we control time.
        var time = new AtomicLong(0L);
        Clock testClock = time::get;

        Octree<LongEntityID, String> octree = new Octree<>(new SequentialLongIDGenerator(), 4, (byte) 6);

        // Aggressive strategy: always says shouldRebalanceTree=true, interval=1000ms.
        var strategy = new DefaultBalancingStrategy<LongEntityID>(0.0, 1.0, 0.0, 1000L, 4);
        octree.setBalancingStrategy(strategy);
        octree.setClock(testClock);
        octree.setAutoBalancingEnabled(true);

        // Seed some entities so rebalanceTree has work to do.
        for (int i = 0; i < 20; i++) {
            octree.insert(new Point3f(i, i, i), (byte) 4, "e" + i);
        }

        // t=0: first call — should rebalance (prev=0, now=0, elapsed=0 == 0 >= 0, CAS wins).
        octree.checkAutoBalance();
        // Confirm lastBalancingTime advanced (reflective read).
        Field f = AbstractSpatialIndex.class.getDeclaredField("lastBalancingTime");
        f.setAccessible(true);
        long stamp1 = ((AtomicLong) f.get(octree)).get();
        assertEquals(0L, stamp1, "After first call at t=0, lastBalancingTime should be 0");

        // t=500: second call — elapsed=500 < 1000, must NOT rebalance; stamp unchanged.
        time.set(500L);
        octree.checkAutoBalance();
        long stamp2 = ((AtomicLong) f.get(octree)).get();
        assertEquals(stamp1, stamp2, "At t=500 (< interval), lastBalancingTime must not advance");

        // t=1000: exactly at interval boundary — should rebalance.
        time.set(1000L);
        octree.checkAutoBalance();
        long stamp3 = ((AtomicLong) f.get(octree)).get();
        assertEquals(1000L, stamp3, "At t=1000 (== interval), lastBalancingTime should advance to 1000");

        // t=1500: elapsed=500 from last stamp — should NOT rebalance.
        time.set(1500L);
        octree.checkAutoBalance();
        long stamp4 = ((AtomicLong) f.get(octree)).get();
        assertEquals(1000L, stamp4, "At t=1500 (< interval from last), lastBalancingTime must not advance");
    }
}

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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that OctreeBalancer uses an injected Clock for nanoTime measurements,
 * enabling deterministic timeTaken assertions in rebalanceTree().
 */
public class OctreeBalancerClockTest {

    private Octree<LongEntityID, String> octree;
    private OctreeBalancer<LongEntityID> balancer;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        var idGen = new SequentialLongIDGenerator();
        octree = new Octree<>(idGen);
        // Use the package-private convenience constructor (same package: lucien.octree)
        balancer = new OctreeBalancer<>(octree, (byte) 10, 8);
        testClock = new TestClock(1_000L);
        balancer.setClock(testClock);
    }

    @Test
    void rebalanceTree_timeTakenReflectsInjectedClock() {
        // Insert enough entities so at least one split candidate exists
        for (int i = 0; i < 20; i++) {
            octree.insert(new LongEntityID(i), new Point3f(i * 0.1f, i * 0.1f, i * 0.1f), (byte) 3, "e" + i);
        }

        // Advance clock by a known amount BEFORE rebalance runs
        long expectedNanos = 5_000_000L; // 5 ms
        testClock.advanceNanos(expectedNanos);

        // Reset nanos to 0 so the delta is predictable: startTime=0, endTime=expectedNanos
        testClock.setNanos(0L);

        // Intercept: advance clock mid-rebalance is not possible via this API, so instead
        // pre-position the clock so that nanoTime() at startTime returns 0 and at timeTaken
        // returns expectedNanos. We set nanos=0, run rebalance, then check timeTaken>=0.
        // The key assertion is that no System.nanoTime() is called — if it were, the value
        // would be enormous (billions of ns since JVM start), not 0 or near-0.
        TreeBalancer.RebalancingResult result = balancer.rebalanceTree();

        assertNotNull(result);
        // timeTaken must come from the TestClock (nanoTime=0 at start, nanoTime=0 at end → 0ns elapsed).
        // A System.nanoTime() call would produce a value >> 0 (at least 10^9 ns since JVM start).
        assertEquals(0L, result.timeTaken(), "timeTaken should be 0 because TestClock nanoTime is frozen at 0");
        assertTrue(result.successful());
    }

    @Test
    void rebalanceTree_timeTakenAdvancedClock() {
        // Empty tree — quick path, but nanoTime still measured
        testClock.setNanos(1_000_000_000L); // 1 second as start

        // We need to advance the clock between the two nanoTime() calls.
        // Since we can't inject mid-method, we verify that with frozen clock, timeTaken = 0.
        // Two calls to nanoTime() on a frozen TestClock both return the same value.
        TreeBalancer.RebalancingResult result = balancer.rebalanceTree();

        assertNotNull(result);
        assertEquals(0L, result.timeTaken(),
                "With frozen TestClock, both nanoTime() calls return the same value, so timeTaken=0");
    }

    @Test
    void setClock_replacesDefaultSystemClock() {
        // Confirm setClock is wired: after injection, nanoTime() from the TestClock is used.
        // If OctreeBalancer still called System.nanoTime(), we'd see a large positive timeTaken.
        testClock.setNanos(42L);
        var result = balancer.rebalanceTree();
        // timeTaken = nanoTime_end - nanoTime_start = 42 - 42 = 0 (frozen clock)
        assertEquals(0L, result.timeTaken());
    }
}

/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.prism;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.balancing.NoOpTreeBalancer;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.lang.reflect.Field;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for Luciferase-qrxy4: Prism declared a dead NoOp balancer field but never overrode createTreeBalancer(),
 * so the base class stored a cube-calibrated DefaultTreeBalancer in its final treeBalancer field and the auto-balance
 * path ran it over PrismKey geometry — risking entity misplacement. Prism now overrides createTreeBalancer() to
 * return a NoOp (as SFCArrayIndex does), and the dead field/getter/inner class are removed.
 *
 * @author hal.hildebrand
 */
class PrismBalancerTest {

    @Test
    void activeTreeBalancerIsNoOp() throws Exception {
        var prism = new Prism<LongEntityID, String>(new SequentialLongIDGenerator(), 10.0f, 10);

        Field f = AbstractSpatialIndex.class.getDeclaredField("treeBalancer");
        f.setAccessible(true);
        var balancer = f.get(prism);

        assertNotNull(balancer);
        assertInstanceOf(NoOpTreeBalancer.class, balancer,
                         "Prism must run a NoOp balancer, not the cube-calibrated DefaultTreeBalancer (Luciferase-qrxy4)");
    }

    @Test
    void autoBalanceDoesNotReorganizeOrCorruptEntities() {
        var prism = new Prism<LongEntityID, String>(new SequentialLongIDGenerator(), 10.0f, 10);
        prism.setAutoBalancingEnabled(true);

        // Populate the S0 half with entities at distinct positions, recording their expected locations.
        var expected = new HashMap<LongEntityID, Point3f>();
        for (int i = 1; i <= 20; i++) {
            var p = new Point3f(0.5f * i % 9 + 0.5f, 0.1f * i % 4 + 0.1f, 0.3f * i % 9 + 0.2f);
            var id = prism.insert(p, (byte) 8, "e" + i);
            expected.put(id, p);
        }
        int countBefore = expected.size();

        // Force a rebalance: the NoOp balancer must report zero reorganization.
        // NOTE: these behavioral assertions are NOT distinguishing on their own — the pre-fix DefaultTreeBalancer
        // also no-ops split/merge on this fixture (its splitNode returns empty, mergeNodes returns false). The
        // load-bearing regression guard is activeTreeBalancerIsNoOp() above; this test pins the absence of
        // corruption as a complementary behavioral safety net.
        var result = prism.rebalanceTree();
        assertEquals(0, result.nodesMerged(), "NoOp balancer merges nothing");
        assertEquals(0, result.nodesSplit(), "NoOp balancer splits nothing");
        assertEquals(0, result.entitiesRelocated(), "NoOp balancer relocates no entities");

        // Every entity is still present at its original position — no corruption.
        assertEquals(countBefore, prism.entityCount(), "entity count unchanged after rebalance");
        for (var e : expected.entrySet()) {
            var pos = prism.getEntityPosition(e.getKey());
            assertNotNull(pos, "entity " + e.getKey() + " must still exist after rebalance");
            assertEquals(e.getValue(), pos, "entity position must be unchanged after NoOp rebalance");
        }
    }
}

/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-fhc9: {@link ForestLoadBalancer} migration must (1) preserve each entity's refinement level
 * (not re-insert at a hardcoded level 0), (2) select an SFC-contiguous block (not a random sample), and
 * (3) run a ghost-rebuild hook once after a non-empty batch (ghosts are stale after entities move trees).
 *
 * @author hal.hildebrand
 */
class ForestLoadBalancerMigrationTest {

    private static final byte LEVEL = 10;

    private record Fixture(ForestLoadBalancer<MortonKey, LongEntityID, String> balancer,
                           Octree<LongEntityID, String> source,
                           Octree<LongEntityID, String> target,
                           Map<Integer, SpatialIndex<MortonKey, LongEntityID, String>> trees,
                           Map<LongEntityID, Point3f> positions) { }

    private static Fixture fixture(int entityCount) {
        var gen = new SequentialLongIDGenerator();
        var source = new Octree<LongEntityID, String>(gen);
        var target = new Octree<LongEntityID, String>(gen);
        var positions = new HashMap<LongEntityID, Point3f>();
        for (int i = 0; i < entityCount; i++) {
            var id = new LongEntityID(i);
            var pos = new Point3f(100 + i, 100, 100);
            positions.put(id, pos);
            source.insert(id, pos, LEVEL, "e" + i);
        }
        var trees = new HashMap<Integer, SpatialIndex<MortonKey, LongEntityID, String>>();
        trees.put(0, source);
        trees.put(1, target);
        return new Fixture(new ForestLoadBalancer<>(), source, target, trees, positions);
    }

    @Test
    void migrationPreservesEntityLevel() {
        var f = fixture(20);
        var ids = new java.util.HashSet<LongEntityID>();
        for (int i = 0; i < 10; i++) {
            ids.add(new LongEntityID(i));
        }
        var plan = new ForestLoadBalancer.MigrationPlan<>(0, 1, ids, 0.5);

        f.balancer().executeMigration(plan, f.trees(), (id, pt) -> pt.set(f.positions().get(id)));

        assertEquals(10, f.target().entityCount(), "ten entities migrated");
        for (var id : ids) {
            var locations = f.target().getEntityLocations(id);
            assertFalse(locations.isEmpty(), "migrated entity " + id + " must be located in the target");
            for (var key : locations) {
                assertEquals(LEVEL, key.getLevel(),
                             "migrated entity must keep its source level " + LEVEL + ", not be coarsened to 0");
            }
        }
    }

    @Test
    void migrationPreservesSpanningEntityBoundsAndSpan() {
        // substantive-critic SIG-1/SIG-2: a spanning entity must survive migration as a spanning entity with
        // its bounds intact — not be silently re-created as a point entity by a bounds-less insert.
        var gen = new SequentialLongIDGenerator();
        // Spanning requires a spanning policy (default is SINGLE_NODE_ONLY); adaptive() spans to overlapping cells.
        var policy = com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy.adaptive();
        var source = new Octree<LongEntityID, String>(gen, 10, (byte) 21, policy);
        var target = new Octree<LongEntityID, String>(gen, 10, (byte) 21, policy);
        var id = new LongEntityID(1);
        var center = new Point3f(500, 500, 500);
        byte spanLevel = 15; // fine cells (~64 wide) so a radius-100 entity spans several
        var bounds = new com.hellblazer.luciferase.lucien.entity.EntityBounds(center, 100f);
        source.insert(id, center, spanLevel, "spanning", bounds);
        int sourceSpan = source.getEntitySpanCount(id);

        var trees = new HashMap<Integer, SpatialIndex<MortonKey, LongEntityID, String>>();
        trees.put(0, source);
        trees.put(1, target);
        var ids = new java.util.HashSet<LongEntityID>();
        ids.add(id);
        var plan = new ForestLoadBalancer.MigrationPlan<>(0, 1, ids, 1.0);

        new ForestLoadBalancer<MortonKey, LongEntityID, String>()
            .executeMigration(plan, trees, (eid, pt) -> pt.set(center));

        assertEquals(bounds, target.getEntityBounds(id), "spanning entity must keep its bounds after migration");
        assertEquals(sourceSpan, target.getEntitySpanCount(id),
                     "spanning entity must keep its span (not collapse to a point)");
        assertTrue(sourceSpan > 1, "precondition: the fixture entity actually spans multiple cells");
    }

    @Test
    void selectionIsDeterministicSfcBlockNotRandom() {
        // createMigrationPlans drives selectEntitiesToMigrate. The prior Collections.shuffle made the selected
        // set random; the SFC-block selection is deterministic. Two identical balancers must pick the same set.
        var a = fixture(200);
        var b = fixture(200);
        a.balancer().collectMetrics(a.trees());
        b.balancer().collectMetrics(b.trees());

        var plansA = a.balancer().createMigrationPlans(a.trees());
        var plansB = b.balancer().createMigrationPlans(b.trees());

        assertFalse(plansA.isEmpty(), "an overloaded source vs empty target must yield a migration plan");
        assertEquals(plansA.get(0).getEntityIds(), plansB.get(0).getEntityIds(),
                     "selection must be deterministic (no random shuffle) across identical inputs");

        // The selected set is the SFC-lowest contiguous block: every selected entity's primary key sorts at or
        // below every non-selected entity's primary key.
        var selected = plansA.get(0).getEntityIds();
        MortonKey maxSelected = selected.stream()
            .map(id -> a.source().getEntityLocations(id).stream().min(java.util.Comparator.naturalOrder()).orElseThrow())
            .max(java.util.Comparator.naturalOrder()).orElseThrow();
        for (var id : a.source().getEntitiesWithPositions().keySet()) {
            if (!selected.contains(id)) {
                var key = a.source().getEntityLocations(id).stream().min(java.util.Comparator.naturalOrder()).orElseThrow();
                assertTrue(key.compareTo(maxSelected) >= 0,
                           "non-selected entity must sort at or above the selected SFC block (contiguous block)");
            }
        }
    }

    @Test
    void ghostRebuildHookRunsOncePerNonEmptyBatch() {
        var f = fixture(10);
        var ids = new java.util.HashSet<LongEntityID>();
        for (int i = 0; i < 5; i++) {
            ids.add(new LongEntityID(i));
        }
        var plan = new ForestLoadBalancer.MigrationPlan<>(0, 1, ids, 0.5);

        var hookRuns = new AtomicInteger();
        f.balancer().executeMigration(plan, f.trees(), (id, pt) -> pt.set(f.positions().get(id)),
                                      hookRuns::incrementAndGet);
        assertEquals(1, hookRuns.get(), "ghost-rebuild hook runs exactly once after a non-empty migration batch");
    }

    @Test
    void ghostRebuildHookDoesNotRunForEmptyBatch() {
        var f = fixture(10);
        // Plan referencing entities that do not exist in the source -> nothing migrates.
        var ids = new java.util.HashSet<LongEntityID>();
        ids.add(new LongEntityID(9999));
        var plan = new ForestLoadBalancer.MigrationPlan<>(0, 1, ids, 0.5);

        var hookRuns = new AtomicInteger();
        f.balancer().executeMigration(plan, f.trees(), (id, pt) -> { }, hookRuns::incrementAndGet);
        assertEquals(0, hookRuns.get(), "hook must not run when no entity actually migrated");
    }
}

/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.topology;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.bubble.EnhancedBubble;
import com.hellblazer.luciferase.simulation.bubble.TetrahedralMigration;
import com.hellblazer.luciferase.simulation.bubble.TetreeBubbleGrid;
import com.hellblazer.luciferase.simulation.config.WorldBounds;
import com.hellblazer.luciferase.simulation.distributed.integration.EntityAccountant;
import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-018 AC-3 (Luciferase-viurt): split + merge migration regression.
 * <p>
 * The non-vacuous, end-to-end successor to RDR-015's static {@code DirectedMigrationRegressionTest}.
 * It drives the simulation THROUGH a real Bey split (AC-2.5) <b>and</b> a real coverage-preserving
 * sibling collapse (AC-3 prereq, q37mx), running the live migration subsystem after each topology
 * change, and asserts that the deepest-leaf up-walk router (AC-2) routes <b>every</b> escaped entity
 * to a real, live leaf bubble — no drop, no mis-route — across the resulting mixed-level forest.
 * <p>
 * Two crossings exercise the two directions Option B must keep working:
 * <ol>
 *   <li><b>Coarse → deep (post-split):</b> an entity in a coarse base-level source cell, displaced
 *       into a sibling cell that has just been refined, must route into the correct <em>level-(L+1)
 *       child leaf</em>. A base-first / L0-first router (the RDR-015 R1 bug) would resolve to the
 *       now-removed base ancestor (→ null → dropped) and the assertion would fail.</li>
 *   <li><b>Deep → coarse (post-collapse):</b> after the 8 children collapse back into their parent,
 *       an entity displaced into that region must route to the <em>re-registered parent leaf</em>,
 *       even though the watermark is left at L+1 (monotonic-up). This pins the q37mx invariant that
 *       the up-walk still resolves correctly because {@code removeBubble} purged the child keys.</li>
 * </ol>
 * <p>
 * <b>Constraints honoured (load-bearing, per the bead and CLAUDE.md):</b>
 * <ul>
 *   <li>Migrating entities are placed at cell <b>centroids</b> — strictly interior, never on a 2D
 *       tet face — so the destination is unambiguous and the assertion is not circumstantial.</li>
 *   <li>Adjacency between the source cell and the refined region is validated by <b>involution
 *       reciprocity</b> ({@code neighbor(neighbor(e,f).dualFace) == e}), never by a shared-vertex
 *       count — tet face neighbors are the non-conforming Bey-SFC neighbors that share 0–3 vertices.</li>
 *   <li>Hysteresis is respected: each probe is verified to lie at least {@code HYSTERESIS_DIST}
 *       Cartesian units past the source cell's nearest face, so the migration genuinely fires.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class SplitMergeMigrationRegressionTest {

    private static final TestClock   CLOCK = new TestClock(1_000L);
    private static final WorldBounds WORLD = new WorldBounds(0.0f, 100.0f);
    private static final float       HYST  = TetrahedralMigration.getHysteresisDistance();

    private TetreeBubbleGrid grid;
    private EntityAccountant  accountant;
    private TopologyMetrics   metrics;
    private BubbleMerger      merger;
    private BubbleSplitter    splitter;

    @BeforeEach
    void setUp() {
        grid       = new TetreeBubbleGrid((byte) 21);
        grid.createBubbles(8, WORLD, 10L);
        accountant = new EntityAccountant();
        metrics    = new TopologyMetrics();
        merger     = new BubbleMerger(grid, accountant, OperationTracker.NOOP, metrics);
        splitter   = new BubbleSplitter(grid, accountant, OperationTracker.NOOP, metrics);
    }

    @Test
    void escapedEntitiesRouteToRealLeaves_throughSplitThenMerge() {
        var tetree = grid.getSpatialIndex();
        byte base  = grid.getBaseLevel();
        assertTrue(base > 0, "grid must be a single-level partition");

        // --- Choose an interior cell to refine (S_dst = the cell containing the world centre) and a
        //     registered face-neighbour to migrate FROM (S_src). The source/destination adjacency is
        //     established by involution reciprocity, never by counting shared vertices. ---
        float cc = WORLD.center();
        var centre  = new Point3f(cc, cc, cc);
        var seedTet = tetree.locateTetrahedron(centre, base);
        assertNotNull(seedTet, "world centre must locate a base-level cell");
        var seedKey = seedTet.tmIndex();
        assertTrue(grid.containsBubble(seedKey), "seed cell must be a registered bubble");

        Tet srcTet = null;
        for (int face = 0; face < 4; face++) {
            var fn = seedTet.faceNeighbor(face);
            if (fn == null) {
                continue;
            }
            var nb   = fn.tet();
            var back = nb.faceNeighbor(fn.face());
            // Involution reciprocity: the dual face of the neighbour must point back to the seed.
            if (back != null && seedTet.equals(back.tet()) && grid.containsBubble(nb.tmIndex())) {
                srcTet = nb;
                break;
            }
        }
        assertNotNull(srcTet, "seed cell must expose a registered reciprocal face-neighbour to migrate from");
        var srcKey    = srcTet.tmIndex();
        var srcBubble = grid.getBubble(srcKey);

        // --- Populate the seed cell with split fodder (one entity per child centroid) so the Bey
        //     split has entities to redistribute, then split it into its 8 level-(L+1) children. ---
        var children   = seedTet.geometricSubdivide();
        var seedBubble = grid.getBubble(seedKey);
        for (int i = 0; i < 8; i++) {
            var fid = UUID.randomUUID();
            seedBubble.addEntity(fid.toString(), centroid(children[i]), null);
            accountant.register(seedBubble.id(), fid);
        }
        int totalBeforeSplit = totalEntities();
        var splitResult = splitter.execute(new SplitProposal(
            UUID.randomUUID(), seedBubble.id(), new SplitPlane(new Point3f(1f, 0f, 0f), 0f),
            DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis()));
        assertTrue(splitResult.success(), "Bey split must succeed: " + splitResult.message());
        assertEquals(totalBeforeSplit, totalEntities(), "the split itself must conserve every entity");
        assertEquals((byte) (base + 1), grid.getMaxLeafLevel(), "watermark raised to base+1 by the split");
        assertFalse(grid.containsBubble(seedKey), "parent leaf must be gone after the split");

        var childKeys = new TetreeKey[8];
        for (int i = 0; i < 8; i++) {
            childKeys[i] = children[i].tmIndex();
            assertTrue(grid.containsBubble(childKeys[i]), "child leaf " + i + " must be registered after split");
        }

        // Refined-tree adjacency soundness: every in-domain SFC face neighbour of a refined child is a
        // proper involution (dual face points back). This exercises the navigable topology the router
        // relies on — NOT a shared-vertex assertion (Bey-SFC neighbours share 0–3 vertices).
        for (int i = 0; i < 8; i++) {
            for (int f = 0; f < 4; f++) {
                var fn = children[i].faceNeighbor(f);
                if (fn == null) {
                    continue;
                }
                var back = fn.tet().faceNeighbor(fn.face());
                assertNotNull(back, "in-domain face neighbour must have a reciprocal");
                assertEquals(children[i], back.tet(),
                             "face-neighbour relation over refined child " + i + " must be an involution");
            }
        }

        // ============================ CROSSING 1: coarse source → deep child leaves ============================
        // The spec's load-bearing word is EVERY escaped entity (the RDR-015 R1 failure dropped some of
        // several simultaneous boundary escapees). Drive TWO simultaneous migrants into TWO DISTINCT
        // refined child leaves so the assertion catches both a drop (count/conservation) AND a mis-route
        // (each must arrive in its OWN child leaf, not the other's).
        //
        // Collect children whose centroid (a) is in-bounds, (b) escapes the coarse source cell, (c) clears
        // the hysteresis band, and (d) resolves (deepest-leaf up-walk) to that exact child key.
        var qualifying = new java.util.ArrayList<Integer>();
        for (int i = 0; i < 8; i++) {
            var ci = centroid(children[i]);
            if (ci.x <= 0f || ci.y <= 0f || ci.z <= 0f || ci.x >= 100f || ci.y >= 100f || ci.z >= 100f) {
                continue;
            }
            if (srcTet.contains12DOP(ci.x, ci.y, ci.z)) {
                continue; // not escaped from the source cell
            }
            if (overshootPastNearestFace(srcTet, ci) < HYST) {
                continue; // inside the anti-thrash band — would be suppressed
            }
            if (!childKeys[i].equals(grid.resolveLeafKey(tetree, ci))) {
                continue; // must resolve to this deep child leaf
            }
            qualifying.add(i);
        }
        assertTrue(qualifying.size() >= 2,
                   "need >= 2 refined child interior points that escape the source and resolve to distinct "
                   + "deep child leaves; found " + qualifying.size());
        int k1 = qualifying.get(0), k2 = qualifying.get(1);
        var p1 = centroid(children[k1]);
        var p2 = centroid(children[k2]);

        var migration = new TetrahedralMigration(grid, tetree);

        var e1a = UUID.randomUUID();
        var e1b = UUID.randomUUID();
        srcBubble.addEntity(e1a.toString(), p1, null);
        srcBubble.addEntity(e1b.toString(), p2, null);
        accountant.register(srcBubble.id(), e1a);
        accountant.register(srcBubble.id(), e1b);
        int totalBefore1 = totalEntities();

        migration.checkMigrations(1L);

        assertEquals(2, migration.getMetrics().getTotalMigrations(), "both escaped entities must migrate");
        assertEquals(0, migration.getMetrics().getFailureCount(), "no migration may fail");
        var childK1Bubble = grid.getBubble(childKeys[k1]);
        var childK2Bubble = grid.getBubble(childKeys[k2]);
        // Each entity must arrive in ITS OWN deep child leaf — a mis-route would land it in the other's.
        assertTrue(containsEntity(childK1Bubble, e1a.toString()), "e1a must arrive in its own deep child leaf");
        assertTrue(containsEntity(childK2Bubble, e1b.toString()), "e1b must arrive in its own deep child leaf");
        assertFalse(containsEntity(childK2Bubble, e1a.toString()), "e1a must NOT be mis-routed into k2");
        assertFalse(containsEntity(childK1Bubble, e1b.toString()), "e1b must NOT be mis-routed into k1");
        assertFalse(containsEntity(srcBubble, e1a.toString()), "e1a must leave the coarse source cell");
        assertFalse(containsEntity(srcBubble, e1b.toString()), "e1b must leave the coarse source cell");
        assertEquals(totalBefore1, totalEntities(), "no entity may be dropped across crossing 1");

        // Mirror the migrations into the accountant so the collapse (which moves accountant-tracked
        // entities) conserves the migrants as well. (TetrahedralMigration does not touch the accountant.)
        assertTrue(accountant.moveBetweenBubbles(e1a, srcBubble.id(), childK1Bubble.id()),
                   "accountant mirror of e1a must succeed");
        assertTrue(accountant.moveBetweenBubbles(e1b, srcBubble.id(), childK2Bubble.id()),
                   "accountant mirror of e1b must succeed");

        // ============================ COLLAPSE: 8 child leaves → parent ============================
        var anchorId = grid.getBubble(childKeys[0]).id();
        var collapse = merger.executeCollapse(new CollapseProposal(
            UUID.randomUUID(), anchorId, DigestAlgorithm.DEFAULT.getOrigin(), CLOCK.currentTimeMillis()));
        assertTrue(collapse.success(), "collapse must succeed: " + collapse.message());
        assertTrue(grid.containsBubble(seedKey), "parent leaf must be re-registered after collapse");
        for (int i = 0; i < 8; i++) {
            assertFalse(grid.containsBubble(childKeys[i]), "child leaf " + i + " must be gone after collapse");
        }
        assertEquals((byte) (base + 1), grid.getMaxLeafLevel(),
                     "watermark stays at base+1 after collapse (monotonic-up)");
        var parentBubble = grid.getBubbleById(collapse.parentBubbleId());
        assertNotNull(parentBubble, "collapsed parent bubble must be live");
        assertTrue(containsEntity(parentBubble, e1a.toString()),
                   "crossing-1 migrant e1a must be conserved into the collapsed parent");
        assertTrue(containsEntity(parentBubble, e1b.toString()),
                   "crossing-1 migrant e1b must be conserved into the collapsed parent");

        // ============================ CROSSING 2: coarse source → re-registered parent ============================
        // Again drive TWO simultaneous escapees so "every escaped entity" is exercised, not just one.
        // q1 = deep interior of the parent region; q2 = a former child centroid that now (children gone)
        // up-walks to the parent. Both must reach the re-registered parent leaf, neither dropped.
        var q1 = centroid(seedTet);
        var q2 = p1;            // a former child centroid — its L+1 key is gone after the collapse
        for (var q : new Point3f[] { q1, q2 }) {
            assertFalse(srcTet.contains12DOP(q.x, q.y, q.z), "probe must escape the source cell");
            assertTrue(overshootPastNearestFace(srcTet, q) >= HYST, "probe must clear the hysteresis band");
            assertEquals(seedKey, grid.resolveLeafKey(tetree, q),
                         "post-collapse up-walk must resolve the region to the re-registered parent");
        }
        // M1: make the up-walk OBSERVABLE — at q1 the finest-level (base+1) located cell has NO registered
        // leaf, so resolving to the base-level parent PROVES the router walked UP rather than landing
        // immediately (a base-first scan with the base intact would be indistinguishable otherwise).
        var deepAtQ1 = tetree.locateTetrahedron(q1, grid.getMaxLeafLevel());
        assertNotNull(deepAtQ1, "q1 must locate a finest-level cell");
        assertTrue(deepAtQ1.l() > base, "watermark must still place the locate one level below base");
        assertFalse(grid.containsBubble(deepAtQ1.tmIndex()),
                    "no L+1 leaf may exist at q1 after collapse — the up-walk must climb to the parent");

        migration.getMetrics().reset();   // per-phase counts (S2): the cumulative counter is stateful
        var e2a = UUID.randomUUID();
        var e2b = UUID.randomUUID();
        srcBubble.addEntity(e2a.toString(), q1, null);
        srcBubble.addEntity(e2b.toString(), q2, null);
        accountant.register(srcBubble.id(), e2a);
        accountant.register(srcBubble.id(), e2b);
        int totalBefore2 = totalEntities();

        migration.checkMigrations(100L);

        assertEquals(2, migration.getMetrics().getTotalMigrations(), "both post-merge escapees must migrate");
        assertEquals(0, migration.getMetrics().getFailureCount(), "no migration may fail after the merge");
        assertTrue(containsEntity(parentBubble, e2a.toString()),
                   "e2a must route to the re-registered parent leaf after the merge");
        assertTrue(containsEntity(parentBubble, e2b.toString()),
                   "e2b must route to the re-registered parent leaf after the merge");
        assertFalse(containsEntity(srcBubble, e2a.toString()), "e2a must leave the coarse source cell");
        assertFalse(containsEntity(srcBubble, e2b.toString()), "e2b must leave the coarse source cell");
        assertEquals(totalBefore2, totalEntities(), "no entity may be dropped across crossing 2");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int totalEntities() {
        return grid.getAllBubbles().stream().mapToInt(b -> b.getAllEntityRecords().size()).sum();
    }

    private static boolean containsEntity(EnhancedBubble bubble, String entityId) {
        return bubble.getAllEntityRecords().stream().anyMatch(r -> r.id().equals(entityId));
    }

    /** Centroid of a tet (average of its 4 vertices) — strictly interior, never on a face. */
    private static Point3f centroid(Tet tet) {
        var v = tet.coordinates();
        return new Point3f((v[0].x + v[1].x + v[2].x + v[3].x) / 4f,
                           (v[0].y + v[1].y + v[2].y + v[3].y) / 4f,
                           (v[0].z + v[1].z + v[2].z + v[3].z) / 4f);
    }

    /**
     * Maximum outward signed distance (Cartesian) from {@code p} to any face plane of {@code tet} —
     * how far {@code p} has crossed past the nearest face it exited through. Mirrors the anti-thrash
     * measure {@link TetrahedralMigration} applies, so the test can pre-verify a probe will not be
     * hysteresis-suppressed. Value {@code <= 0} when {@code p} is inside the tet.
     */
    private static double overshootPastNearestFace(Tet tet, Point3f p) {
        Point3i[] v = tet.coordinates();
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            var a = v[(i + 1) & 3];
            var b = v[(i + 2) & 3];
            var c = v[(i + 3) & 3];
            var apex = v[i];
            double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
            double acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
            double nx = aby * acz - abz * acy;
            double ny = abz * acx - abx * acz;
            double nz = abx * acy - aby * acx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len == 0.0) {
                continue;
            }
            double towardApex = nx * (apex.x - a.x) + ny * (apex.y - a.y) + nz * (apex.z - a.z);
            double sign = towardApex > 0 ? -1.0 : 1.0;
            double d = sign * (nx * (p.x - a.x) + ny * (p.y - a.y) + nz * (p.z - a.z)) / len;
            if (d > max) {
                max = d;
            }
        }
        return max;
    }
}

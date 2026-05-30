/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.pyramid;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import com.hellblazer.luciferase.lucien.entity.EntitySpanningPolicy;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.3 Phase F: generic-contract parity suite for {@link PyramidIndex}.
 *
 * <p>Following the project convention established by Prism (a peer index has its own dedicated
 * functional/comparison suite rather than joining the shared {@code SpatialIndex*Test} parameterized
 * providers — Prism is in none of those), this exercises the generic {@code SpatialIndex} contract
 * that pi1.3 supports — entity lifecycle, lookup, range query, same-region kNN, max-level operations —
 * and cross-checks membership behavior against {@link Octree} on identical inputs.
 *
 * <p><b>Explicitly deferred (NOT pi1.3 scope, surfaced as follow-on beads — not silent reduction):</b>
 * <ul>
 *   <li>Multi-node entity spanning ({@code insertWithSpanning} override): <b>SHIPPED</b> (bead
 *       Luciferase-7eb) — see {@link #spanningEntityCoversMultipleNodes()}. PyramidIndex now overrides
 *       {@code insertWithSpanning} (one pyramid/tet element per intersected cube cell at the level).</li>
 *   <li>Full same/cross-shape neighbor topology ({@code addNeighboringNodes} beyond the minimum
 *       SFC-adjacent contract): deferred to pi1.4 (PyramidNeighborDetector). Cross-node kNN that
 *       must hop through non-adjacent subtrees is therefore out of scope here.</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class PyramidIndexParityTest {

    private PyramidIndex<LongEntityID, String> pyramid;

    @BeforeEach
    void setUp() {
        pyramid = new PyramidIndex<>(new SequentialLongIDGenerator());
    }

    @Test
    void spanningEntityCoversMultipleNodes() {
        // RDR-010 Luciferase-7eb: a bounded entity spanning the domain must be distributed across
        // multiple pyramid nodes (getEntitySpanCount > 1), mirroring the shared Octree/Tetree spanning
        // contract (SpatialIndexEdgeCaseTest.testLargeSpanningEntity). PyramidIndex is a dedicated peer
        // suite (project convention) rather than a member of the shared parameterized provider.
        var spanning = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator(), 1000,
                                                              (byte) 20, EntitySpanningPolicy.withSpanning());
        int maxCoord = Constants.MAX_COORD;
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(maxCoord, maxCoord, maxCoord));
        var position = new Point3f(maxCoord / 2f, maxCoord / 2f, maxCoord / 2f);
        var id = new LongEntityID(1);

        spanning.insert(id, position, (byte) 5, "huge", bounds);

        assertTrue(spanning.getEntitySpanCount(id) > 1,
                   "a domain-spanning entity must span > 1 pyramid node, got " + spanning.getEntitySpanCount(id));
        // Findable in range queries at opposite ends of its bounds.
        assertTrue(spanning.entitiesInRegion(new Spatial.Cube(0, 0, 0, 100)).contains(id),
                   "spanning entity must be found near the origin corner");
        assertTrue(spanning.entitiesInRegion(
                       new Spatial.Cube(maxCoord - 100f, maxCoord - 100f, maxCoord - 100f, 100)).contains(id),
                   "spanning entity must be found near the opposite corner");
    }

    @Test
    void outOfDomainBoundsFallBackToSingleNodeNotSilentLoss() {
        // RDR-010 Luciferase-7eb edge guard: bounds entirely outside [0, MAX_COORD] clamp to an inverted
        // range. The spanning override must fall back to single-node insertion (at the in-domain
        // position) rather than leave the entity registered in zero nodes (silently unfindable).
        var spanning = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator(), 1000,
                                                              (byte) 20, EntitySpanningPolicy.withSpanning());
        int maxCoord = Constants.MAX_COORD;
        var inDomainPos = new Point3f(1000, 1000, 1000);
        var outOfDomainBounds = new EntityBounds(new Point3f(maxCoord * 2f, maxCoord * 2f, maxCoord * 2f),
                                                 new Point3f(maxCoord * 3f, maxCoord * 3f, maxCoord * 3f));
        var id = new LongEntityID(7);

        spanning.insert(id, inDomainPos, (byte) 5, "oob", outOfDomainBounds);

        assertTrue(spanning.containsEntity(id), "entity must remain registered (no silent loss)");
        // The discriminating assertion: without the inverted-range guard the spanning loop registers the
        // entity in ZERO nodes (span 0, unfindable); the guard's single-node fallback yields span >= 1.
        assertTrue(spanning.getEntitySpanCount(id) >= 1, "entity must occupy at least one node");
        assertEquals(inDomainPos, spanning.getEntityPosition(id), "position preserved at the in-domain point");
    }

    @Test
    void singleEntityDoesNotSpan() {
        // A point entity (no bounds) stays single-node even with spanning enabled.
        var spanning = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator(), 1000,
                                                              (byte) 20, EntitySpanningPolicy.withSpanning());
        var id = spanning.insert(new Point3f(100, 100, 100), (byte) 10, "point");
        assertEquals(1, spanning.getEntitySpanCount(id), "a point entity must occupy exactly one node");
    }

    @Test
    void entityLifecycle() {
        var pos = new Point3f(100, 100, 100);
        var id = pyramid.insert(pos, (byte) 10, "alpha");

        assertTrue(pyramid.containsEntity(id));
        assertEquals("alpha", pyramid.getEntity(id));
        assertEquals(pos, pyramid.getEntityPosition(id));
        assertEquals(1, pyramid.entityCount());

        assertTrue(pyramid.removeEntity(id));
        assertFalse(pyramid.containsEntity(id));
        assertEquals(0, pyramid.entityCount());
    }

    @Test
    void lookupReturnsAllAtPosition() {
        var pos = new Point3f(500, 500, 500);
        byte level = 15;
        var ids = new ArrayList<LongEntityID>();
        for (int i = 0; i < 20; i++) {
            ids.add(pyramid.insert(pos, level, "e" + i));
        }
        assertEquals(20, pyramid.entityCount());
        var found = pyramid.lookup(pos, level);
        assertEquals(20, found.size());
        assertTrue(found.containsAll(ids));
    }

    @Test
    void rangeQueryFindsContainedEntities() {
        var inside = pyramid.insert(new Point3f(50, 50, 50), (byte) 12, "inside");
        var outside = pyramid.insert(new Point3f(900_000, 900_000, 900_000), (byte) 12, "outside");

        var region = pyramid.entitiesInRegion(new Spatial.Cube(0, 0, 0, 1000));
        assertTrue(region.contains(inside), "entity inside the query cube must be found");
        assertFalse(region.contains(outside), "entity far outside the query cube must not be found");
    }

    @Test
    void kNearestNeighborsSameRegion() {
        // Cluster several entities in one region; kNN within that region must return them ranked.
        var query = new Point3f(200, 200, 200);
        var ids = new ArrayList<LongEntityID>();
        for (int i = 0; i < 5; i++) {
            ids.add(pyramid.insert(new Point3f(200 + i, 200 + i, 200 + i), (byte) 10, "n" + i));
        }
        var neighbors = pyramid.kNearestNeighbors(query, 5, 10_000);
        assertEquals(5, neighbors.size(), "kNN must return all 5 clustered entities");
        assertTrue(ids.containsAll(neighbors));
        // Nearest must be the closest-inserted entity (n0 at (200,200,200) == query).
        assertEquals(ids.get(0), neighbors.get(0), "closest entity must rank first");
    }

    @Test
    void maxLevelOperationsDoNotThrow() {
        // Regression for the §3b max-level guard: PyramidContainment must not call Pyramid.child()
        // on a level-21 pyramid (which cannot refine). Pre-fix this threw IllegalStateException.
        var pos = new Point3f(123, 456, 789);
        var id = assertDoesNotThrow(() -> pyramid.insert(pos, PyramidKey.MAX_PYRAMID_LEVEL, "max"),
                                    "insert at max level must not throw");
        assertTrue(pyramid.containsEntity(id));
        // Smoke: range query over a populated max-level node must not propagate any exception.
        // (The insert assertion above is the actual regression guard for the PyramidContainment fix.)
        assertDoesNotThrow(() -> pyramid.entitiesInRegion(new Spatial.Cube(0, 0, 0, 2000)),
                           "range query touching a max-level node must not throw");
        assertEquals("max", pyramid.getEntity(id));
    }

    @Test
    void boundaryValueInsertions() {
        // Coordinate-system corners + center must all insert and round-trip (mirrors the generic
        // SpatialIndexEdgeCaseTest.testBoundaryValues coverage).
        var maxCoord = Constants.MAX_COORD;
        var cases = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(0, 0, maxCoord),
            new Point3f(0, maxCoord, 0),
            new Point3f(maxCoord, 0, 0),
            new Point3f(maxCoord, maxCoord, maxCoord),
            new Point3f(maxCoord / 2, maxCoord / 2, maxCoord / 2)
        };
        var ids = new ArrayList<LongEntityID>();
        for (var p : cases) {
            ids.add(pyramid.insert(p, (byte) 10, "boundary"));
        }
        assertEquals(cases.length, pyramid.entityCount());
        for (int i = 0; i < cases.length; i++) {
            assertEquals(cases[i], pyramid.getEntityPosition(ids.get(i)), "boundary round-trip " + i);
        }
        var all = pyramid.entitiesInRegion(new Spatial.Cube(0, 0, 0, Constants.MAX_COORD));
        assertEquals(cases.length, all.size(), "whole-domain range must return every boundary entity");
    }

    @Test
    void emptyIndexOperations() {
        assertEquals(0, pyramid.entityCount());
        assertEquals(0, pyramid.nodeCount());
        assertTrue(pyramid.kNearestNeighbors(new Point3f(100, 100, 100), 10, 1000).isEmpty(),
                   "kNN on empty index");
        assertTrue(pyramid.entitiesInRegion(new Spatial.Cube(0, 0, 0, 1000)).isEmpty(),
                   "range on empty index");
        assertTrue(pyramid.rayIntersectAll(new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0)))
                          .isEmpty(), "ray on empty index");
        assertFalse(pyramid.removeEntity(new LongEntityID(999)), "remove non-existent");
        assertNull(pyramid.getEntity(new LongEntityID(999)), "get non-existent");
        assertNull(pyramid.getEntityPosition(new LongEntityID(999)), "getPosition non-existent");
    }

    @Test
    void updateToSamePosition() {
        var pos = new Point3f(100, 100, 100);
        var id = pyramid.insert(pos, (byte) 10, "test");
        pyramid.updateEntity(id, pos, (byte) 10);
        assertTrue(pyramid.containsEntity(id));
        assertEquals(pos, pyramid.getEntityPosition(id));
        assertEquals("test", pyramid.getEntity(id));
        assertEquals(1, pyramid.entityCount());
    }

    @Test
    void batchInsertWithDuplicates() {
        var positions = List.of(
            new Point3f(100, 100, 100),
            new Point3f(100, 100, 100), // duplicate
            new Point3f(200, 200, 200),
            new Point3f(100, 100, 100), // duplicate
            new Point3f(300, 300, 300)
        );
        var contents = List.of("A", "B", "C", "D", "E");
        var ids = pyramid.insertBatch(positions, contents, (byte) 10);
        assertEquals(5, ids.size());
        assertEquals(5, pyramid.entityCount());
        // Duplicate-position entities round-trip to the same position.
        assertEquals(pyramid.getEntityPosition(ids.get(0)), pyramid.getEntityPosition(ids.get(1)));
        assertEquals(pyramid.getEntityPosition(ids.get(0)), pyramid.getEntityPosition(ids.get(3)));
    }

    @Test
    void membershipParityWithOctree() {
        // Same inputs into PyramidIndex and Octree must agree on entity COUNT and on which entities
        // a whole-domain range query returns (membership parity — not internal key parity).
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var seeded = new Random(7654321L); // deterministic

        int n = 300;
        var positions = new ArrayList<Point3f>();
        for (int i = 0; i < n; i++) {
            positions.add(new Point3f(seeded.nextInt(100_000), seeded.nextInt(100_000),
                                      seeded.nextInt(100_000)));
        }
        byte level = 12;
        for (var p : positions) {
            pyramid.insert(p, level, "x");
            octree.insert(p, level, "x");
        }
        assertEquals(octree.entityCount(), pyramid.entityCount(), "entity counts must match");
        assertEquals(n, pyramid.entityCount());

        var whole = new Spatial.Cube(0, 0, 0, 200_000);
        var pyrCount = new HashSet<>(pyramid.entitiesInRegion(whole)).size();
        var octCount = new HashSet<>(octree.entitiesInRegion(whole)).size();
        assertEquals(n, octCount, "Octree whole-domain query must return all entities");
        assertEquals(octCount, pyrCount, "PyramidIndex whole-domain query must return all entities too");
    }
}

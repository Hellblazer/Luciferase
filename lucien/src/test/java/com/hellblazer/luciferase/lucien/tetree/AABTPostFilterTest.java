/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Tetree#boundingFiltered(Spatial.aabt)}: AABB traversal + AABT post-filter.
 *
 * <p>The post-filter applies {@link Tet#intersectsBound(Spatial.aabt)} on each AABB candidate,
 * removing false positives before returning results to callers. This gives fast AABB traversal
 * speed with tighter tet-geometry filtering.</p>
 *
 * @author hal.hildebrand
 */
class AABTPostFilterTest {

    // Use a moderate level: cell size = 2^(21-13) = 256 units
    private static final byte TEST_LEVEL = 13;
    // Range of coordinates to populate: within a few hundred grid cells at TEST_LEVEL
    private static final float COORD_RANGE = Constants.lengthAtLevel(TEST_LEVEL) * 50f;

    private Tetree<LongEntityID, String> tetree;

    @BeforeEach
    void setup() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
    }

    // -------------------------------------------------------------------------
    // Correctness: filtered is a subset of unfiltered AABB result
    // -------------------------------------------------------------------------

    /**
     * For a box-shaped query the AABT post-filter degenerates to the same AABB check that
     * {@link Tetree#bounding} already performs, so the filtered result must be a subset of
     * the unfiltered result (no false negatives introduced by the filter itself).
     */
    @Test
    void filteredIsSubsetOfAabb_boxQuery() {
        int cellSize = Constants.lengthAtLevel(TEST_LEVEL);
        populateInRegion(50, 0, 20 * cellSize);

        // Query box spanning several cells in the populated region
        float qMin = cellSize * 2f;
        float qMax = cellSize * 15f;
        var queryBox = new Spatial.aabt.Box(qMin, qMin, qMin, qMax, qMax, qMax);
        var queryAabb = new Spatial.aabb(qMin, qMin, qMin, qMax, qMax, qMax);

        Set<TetreeKey<?>> unfiltered = tetree.bounding(queryAabb).map(
        SpatialIndex.SpatialNode::sfcIndex).collect(Collectors.toSet());

        Set<TetreeKey<?>> filtered = tetree.boundingFiltered(queryBox).map(
        SpatialIndex.SpatialNode::sfcIndex).collect(Collectors.toSet());

        // Filtered must be a subset — no new keys introduced
        for (var key : filtered) {
            assertTrue(unfiltered.contains(key), "filtered returned key not in unfiltered bounding(): " + key);
        }

        System.out.printf("boxQuery  — unfiltered=%d  filtered=%d%n", unfiltered.size(), filtered.size());
        // At least some nodes should be found for a non-trivial test
        assertTrue(unfiltered.size() > 0, "expected unfiltered to find nodes in populated region");
    }

    /**
     * For a tet-shaped query the AABT post-filter uses full tet-vs-tet SAT, so the filtered
     * set must still be a strict subset of the AABB unfiltered set (no false negatives allowed).
     */
    @Test
    void filteredIsSubsetOfAabb_tetQuery() {
        int cellSize = Constants.lengthAtLevel(TEST_LEVEL);
        populateInRegion(100, 0, 20 * cellSize);

        // Query tet in the middle of the populated region
        var queryTet = new Tet(cellSize * 8, cellSize * 8, cellSize * 8, TEST_LEVEL, (byte) 2);
        var vb = queryTet.toVolumeBounds();
        var queryAabb = new Spatial.aabb(vb.minX(), vb.minY(), vb.minZ(), vb.maxX(), vb.maxY(), vb.maxZ());

        Set<TetreeKey<?>> unfiltered = tetree.bounding(queryAabb).map(
        SpatialIndex.SpatialNode::sfcIndex).collect(Collectors.toSet());

        Set<TetreeKey<?>> filtered = tetree.boundingFiltered(queryTet).map(
        SpatialIndex.SpatialNode::sfcIndex).collect(Collectors.toSet());

        // Every filtered key must appear in the unfiltered AABB set
        for (var key : filtered) {
            assertTrue(unfiltered.contains(key),
                       "filteredIsSubsetOfAabb_tetQuery: filtered returned key not in unfiltered set: " + key);
        }

        System.out.printf("tetQuery  — unfiltered=%d  filtered=%d  reduction=%.1f%%%n", unfiltered.size(),
                          filtered.size(),
                          100.0 * (unfiltered.size() - filtered.size()) / Math.max(1, unfiltered.size()));
    }

    // -------------------------------------------------------------------------
    // False-positive reduction: tet queries prune candidates
    // -------------------------------------------------------------------------

    /**
     * For a tet-shaped query the AABT post-filter must return no more candidates than the plain
     * AABB path. When the tree has nodes at the boundary of the tet's AABB envelope, the SAT
     * test rejects nodes whose geometry does not actually intersect the tet.
     */
    @Test
    void filteredHasNoMoreCandidatesThanAabb_tetQuery() {
        int cellSize = Constants.lengthAtLevel(TEST_LEVEL);
        populateInRegion(200, 0, 30 * cellSize);

        // A tet whose AABB envelope covers several grid cells, but whose actual geometry
        // only covers a fraction — boundary cells should be pruned by AABT.
        var queryTet = new Tet(cellSize * 5, cellSize * 5, cellSize * 5, TEST_LEVEL, (byte) 1);
        var vb = queryTet.toVolumeBounds();
        var queryAabb = new Spatial.aabb(vb.minX(), vb.minY(), vb.minZ(), vb.maxX(), vb.maxY(), vb.maxZ());

        long unfilteredCount = tetree.bounding(queryAabb).count();
        long filteredCount = tetree.boundingFiltered(queryTet).count();

        assertTrue(filteredCount <= unfilteredCount,
                   "filtered count (" + filteredCount + ") should be <= unfiltered count (" + unfilteredCount + ")");

        System.out.printf("tetPrune  — unfiltered=%d  filtered=%d  reduction=%.1f%%%n", unfilteredCount,
                          filteredCount,
                          100.0 * (unfilteredCount - filteredCount) / Math.max(1, unfilteredCount));
    }

    /**
     * For a box-shaped query the post-filter result must equal the AABB result — a box query
     * cannot improve on AABB because the SAT test for a box falls back to an AABB check.
     */
    @Test
    void filteredSameCandidateCount_boxQuery() {
        int cellSize = Constants.lengthAtLevel(TEST_LEVEL);
        populateInRegion(100, 0, 25 * cellSize);

        float qMin = cellSize * 3f;
        float qMax = cellSize * 18f;
        var queryBox = new Spatial.aabt.Box(qMin, qMin, qMin, qMax, qMax, qMax);
        var queryAabb = new Spatial.aabb(qMin, qMin, qMin, qMax, qMax, qMax);

        long unfilteredCount = tetree.bounding(queryAabb).count();
        long filteredCount = tetree.boundingFiltered(queryBox).count();

        assertEquals(unfilteredCount, filteredCount,
                     "For a box query, filtered and unfiltered should return the same count");
    }

    // -------------------------------------------------------------------------
    // No false negatives: every truly-intersecting stored node must appear
    // -------------------------------------------------------------------------

    /**
     * For every node in the tree whose Tet actually intersects the query tet (per SAT), the
     * filtered query must include it — no false negatives permitted.
     */
    @Test
    void filteredHasNoFalseNegatives_tetQuery() {
        int cellSize = Constants.lengthAtLevel(TEST_LEVEL);
        populateInRegion(100, 0, 20 * cellSize);

        var queryTet = new Tet(cellSize * 6, cellSize * 6, cellSize * 6, TEST_LEVEL, (byte) 0);

        Set<TetreeKey<?>> filtered = tetree.boundingFiltered(queryTet).map(
        SpatialIndex.SpatialNode::sfcIndex).collect(Collectors.toSet());

        // Walk every stored node and verify any true intersection is in the result
        tetree.nodeStream().forEach(node -> {
            var key = node.sfcIndex();
            var tet = Tet.tetrahedron(key);
            if (tet.intersectsBound(queryTet)) {
                assertTrue(filtered.contains(key),
                           "filteredHasNoFalseNegatives: true-intersecting node missing: " + key);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Entity retrieval: entities in filtered nodes are the right ones
    // -------------------------------------------------------------------------

    /**
     * An entity inserted at the centroid of the query tet must be retrievable via
     * {@code boundingFiltered}. Entities far outside the query tet must not appear.
     */
    @Test
    void entityRetrievalWithFilter_returnsCorrectEntities() {
        // Query tet at level 12, cell (0,0,0), type 0 — a well-defined tet with known geometry
        var queryTet = new Tet(0, 0, 0, (byte) 12, (byte) 0);

        // Insert one entity at the centroid of the query tet — definitionally inside
        var coords = queryTet.coordinates();
        float cx = (coords[0].x + coords[1].x + coords[2].x + coords[3].x) / 4f;
        float cy = (coords[0].y + coords[1].y + coords[2].y + coords[3].y) / 4f;
        float cz = (coords[0].z + coords[1].z + coords[2].z + coords[3].z) / 4f;
        var insideId = tetree.insert(new Point3f(cx, cy, cz), (byte) 12, "inside");

        // Insert entities far from the query tet — on the opposite end of the domain
        int farCoord = Constants.lengthAtLevel((byte) 1);   // 2^20 = 1M units away
        var outsideId1 = tetree.insert(new Point3f(farCoord * 0.8f, farCoord * 0.8f, farCoord * 0.8f), (byte) 12,
                                       "outside1");
        var outsideId2 = tetree.insert(new Point3f(farCoord * 0.6f, farCoord * 0.3f, farCoord * 0.5f), (byte) 12,
                                       "outside2");

        // Collect all entity IDs from nodes that pass the AABT post-filter
        Set<LongEntityID> foundIds = tetree.boundingFiltered(queryTet).flatMap(
        node -> node.entityIds().stream()).collect(Collectors.toSet());

        // The entity at the centroid must be found
        assertTrue(foundIds.contains(insideId),
                   "entity at centroid of query tet should be found by boundingFiltered");

        // Entities far away must not appear
        assertFalse(foundIds.contains(outsideId1),
                    "entity far outside the query tet should not be found by boundingFiltered");
        assertFalse(foundIds.contains(outsideId2),
                    "entity far outside the query tet should not be found by boundingFiltered");

        System.out.printf("entityRetrieval — foundIds=%d%n", foundIds.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Insert {@code count} entities uniformly within the axis-aligned box
     * [{@code minCoord}, {@code maxCoord}] at {@link #TEST_LEVEL}.
     */
    private void populateInRegion(int count, float minCoord, float maxCoord) {
        var rng = new Random(42L);
        float range = maxCoord - minCoord;
        for (int i = 0; i < count; i++) {
            float x = minCoord + rng.nextFloat() * range;
            float y = minCoord + rng.nextFloat() * range;
            float z = minCoord + rng.nextFloat() * range;
            // Ensure strictly positive (Tetree requirement)
            x = Math.max(1f, x);
            y = Math.max(1f, y);
            z = Math.max(1f, z);
            tetree.insert(new Point3f(x, y, z), TEST_LEVEL, "entity-" + i);
        }
    }
}

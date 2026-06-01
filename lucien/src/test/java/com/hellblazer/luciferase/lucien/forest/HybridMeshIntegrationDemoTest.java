/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.lucien.forest;

import com.hellblazer.luciferase.lucien.AbstractSpatialIndex;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.balancing.ShapeWeightPartitioner;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.prism.Prism;
import com.hellblazer.luciferase.lucien.pyramid.PyramidIndex;
import com.hellblazer.luciferase.lucien.pyramid.PyramidKey;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDR-010 pi1.7 — hybrid-mesh integration demo (bead Luciferase-pi1.7, Knapp 2026 §7 in spirit).
 *
 * <p>Exercises the full RDR-010 stack end-to-end across all four element shapes: hexahedron (Octree),
 * tetrahedron (Tetree), pyramid (PyramidIndex), and prism (Prism). Each shape is driven through
 * insert / kNN / spatial-key / neighbor-detection in its own {@link Forest}, then the two genuinely
 * <em>coupled</em> shapes are exercised across the pyramid↔tet cross-shape seam (pi1.5), and the
 * shape-aware partition weight (pi1.6) is demonstrated on a heterogeneous weight set.
 *
 * <p><b>Why multi-forest, not one forest.</b> {@link Forest} is parameterized by a single
 * {@link SpatialKey} type, so a single forest instance is homogeneous in key — hex (MortonKey),
 * tet (TetreeKey), pyramid (PyramidKey), and prism cannot co-reside in one {@code Forest}. The realistic
 * "hybrid mesh" surface in Luciferase is therefore: per-shape indices as peers + the element-level
 * pyramid↔tet cross-shape coupling (the RDR's core contribution) + a shape-weighted partition computed
 * over mixed per-shape weights. This demo asserts each of those legs.
 */
class HybridMeshIntegrationDemoTest {

    private static final int N = 200;

    @Test
    void hexLegInsertsQueriesAndFindsNeighbors() {
        var octree = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var forest = new Forest<com.hellblazer.luciferase.lucien.octree.MortonKey, LongEntityID, String>();
        forest.addTree(octree);
        seedGrid(octree, 100f, 5000f);
        assertShapeLegHealthy(octree, "hex/Octree");
    }

    @Test
    void tetLegInsertsQueriesAndFindsNeighbors() {
        var tetree = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        var forest = new Forest<com.hellblazer.luciferase.lucien.tetree.TetreeKey<?>, LongEntityID, String>();
        forest.addTree(tetree);
        seedGrid(tetree, 100f, 5000f);
        assertShapeLegHealthy(tetree, "tet/Tetree");
    }

    @Test
    void pyramidLegInsertsQueriesAndFindsNeighbors() {
        var pyramid = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        var forest = new Forest<PyramidKey, LongEntityID, String>();
        forest.addTree(pyramid);
        seedGrid(pyramid, 100f, 5000f);
        assertShapeLegHealthy(pyramid, "pyramid/PyramidIndex");
    }

    @Test
    void prismLegInsertsAndQueriesInTriangularDomain() {
        // Prism uses a normalized triangular domain (worldSize 1.0, x + y < 1).
        var prism = new Prism<LongEntityID, String>(new SequentialLongIDGenerator());
        int n = 0;
        for (int i = 1; i < 10; i++) {
            for (int j = 1; j < 10 - i; j++) {
                float x = i / 20f;
                float y = j / 20f;
                if (x + y < 0.95f) {
                    prism.insert(new Point3f(x, y, 0.5f), (byte) 10, "p" + (n++));
                }
            }
        }
        assertTrue(prism.entityCount() > 0, "prism leg must insert entities in its triangular domain");
        assertTrue(prism.nodeCount() > 0, "prism must occupy nodes");
        var knn = prism.kNearestNeighbors(new Point3f(0.25f, 0.25f, 0.5f), 5, Float.MAX_VALUE);
        assertFalse(knn.isEmpty(), "prism kNN must return neighbors");
    }

    @Test
    void crossShapePyramidTetSeamCouplesTheTwoShapes() {
        // The genuine hybrid coupling (pi1.5): a pyramid's four triangular faces neighbor tetrahedra.
        // Driven entirely through the public API — seed a PyramidIndex, then scan occupied keys (and
        // their face neighbors) for a cross-shape pyramid→tet adjacency surfaced by the detector.
        var pyramid = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        var detector = pyramid.getNeighborDetector();
        assertNotNull(detector, "pyramid index wires a cross-shape neighbor detector");
        seedGrid(pyramid, 100f, 5000f);

        boolean foundCrossShape = false;
        for (var key : pyramid.getSpatialKeys()) {
            for (var fk : detector.findFaceNeighbors(key)) {
                if (isTetLeafKey(fk)) { // leaf type 0-5 = tet (cross-shape); 6/7 = pyramid (same-shape)
                    foundCrossShape = true;
                    break;
                }
            }
            if (foundCrossShape) {
                break;
            }
        }
        assertTrue(foundCrossShape,
                   "the hybrid mesh must couple pyramid↔tet: an occupied pyramid's face neighbors a tet");
    }

    /** A PyramidKey whose leaf-level type is a tet type (0-5), i.e. a cross-shape tet-leaf neighbor. */
    private static boolean isTetLeafKey(PyramidKey key) {
        byte t = key.getTypeAtLevel(key.getLevel());
        return t != com.hellblazer.luciferase.lucien.pyramid.Pyramid.TYPE_6
               && t != com.hellblazer.luciferase.lucien.pyramid.Pyramid.TYPE_7;
    }

    @Test
    void ghostLayerWiresForThePyramidShape() {
        // The fourth bead leg (insert/query/neighbor/GHOST): demonstrate the ghost boundary stack wires
        // end-to-end for the pyramid shape in the hybrid mesh. Substantive cross-shape / distributed
        // ghost-exchange correctness (multi-rank fan-out via the inverted seam) is covered by
        // PyramidCrossShapeGhostTest (pi1.5); here we only show the ghost layer builds over a populated
        // pyramid index without error and surfaces boundary elements.
        var pyramid = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        seedGrid(pyramid, 1f, 8000f); // span the domain
        var detector = pyramid.getNeighborDetector();
        var ghost = new com.hellblazer.luciferase.lucien.forest.ghost.GhostBoundaryDetector<>(
            pyramid, detector,
            com.hellblazer.luciferase.lucien.forest.ghost.GhostType.FACES,
            com.hellblazer.luciferase.lucien.forest.ghost.GhostAlgorithm.MINIMAL);

        // Luciferase-3uwx: the boundary set is the PARTITION seam (a face neighbor owned by a different rank),
        // not the domain edge. Establish a seam by assigning a remote owner to an absent face neighbor of an
        // occupied element so the ghost-exchange boundary set is non-empty.
        outer:
        for (var key : pyramid.getSpatialKeys()) {
            for (var fk : detector.findFaceNeighbors(key)) {
                if (!pyramid.containsSpatialKey(fk)) {
                    ghost.setElementOwner(fk, 1); // remote relative to default local rank 0
                    break outer;
                }
            }
        }

        assertDoesNotThrow(ghost::createGhostLayer, "pyramid ghost layer must build without error");
        assertNotNull(ghost.getGhostLayer(), "pyramid ghost layer must be present");
        assertFalse(ghost.getBoundaryElements().isEmpty(),
                    "a partition seam (remote-owned face neighbor) must yield boundary elements for ghost exchange");
    }

    @Test
    void shapeAwarePartitionBalancesAHeterogeneousWeightSet() {
        // pi1.6: a hybrid mesh's partition must weight pyramid roots by N_pyramid = 2·8^ℓ − 6^ℓ, not 1:8.
        var hex = new Octree<LongEntityID, String>(new SequentialLongIDGenerator());
        var tet = new Tetree<LongEntityID, String>(new SequentialLongIDGenerator());
        var pyr = new PyramidIndex<LongEntityID, String>(new SequentialLongIDGenerator());
        // A heterogeneous "forest" of roots: hex, tet, pyramid, pyramid, hex, tet at level 2.
        long[] weights = ShapeWeightPartitioner.weightsAtLevel(
            java.util.List.of(hex, tet, pyr, pyr, hex, tet), 2);
        assertArrayEquals(new long[] { 64, 64, 92, 92, 64, 64 }, weights,
                          "N_hex=N_tet=64, N_pyramid=92 at level 2");

        int[] ranks = ShapeWeightPartitioner.assign(weights, 3);
        // Contiguous, in range, and the two heavy pyramid roots pull a partition boundary.
        for (int i = 0; i < ranks.length; i++) {
            assertTrue(ranks[i] >= 0 && ranks[i] < 3);
            if (i > 0) {
                assertTrue(ranks[i] >= ranks[i - 1], "partition must be contiguous in SFC order");
            }
        }
        int[] shapeBlind = ShapeWeightPartitioner.assign(new long[] { 64, 64, 64, 64, 64, 64 }, 3);
        assertFalse(java.util.Arrays.equals(ranks, shapeBlind),
                    "shape-aware partition must differ from the shape-blind 1:8 partition");
    }

    // ===== helpers =====

    /** Insert a coarse grid of entities into a MAX_COORD-space index. */
    private static <K extends SpatialKey<K>> void seedGrid(AbstractSpatialIndex<K, LongEntityID, String> index,
                                                           float origin, float span) {
        int n = 0;
        int side = (int) Math.cbrt(N) + 1;
        float step = span / side;
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                for (int k = 0; k < side && n < N; k++) {
                    index.insert(new Point3f(origin + i * step, origin + j * step, origin + k * step),
                                 (byte) 10, "e" + (n++));
                }
            }
        }
    }

    /** Assert a MAX_COORD-space shape leg inserts, queries (kNN), and exposes a working neighbor detector. */
    private static <K extends SpatialKey<K>> void assertShapeLegHealthy(
        AbstractSpatialIndex<K, LongEntityID, String> index, String label) {
        assertTrue(index.entityCount() > 0, label + ": must insert entities");
        assertTrue(index.nodeCount() > 0, label + ": must occupy nodes");

        var knn = index.kNearestNeighbors(new Point3f(2500f, 2500f, 2500f), 8, Float.MAX_VALUE);
        assertFalse(knn.isEmpty(), label + ": kNN must return neighbors");

        var detector = index.getNeighborDetector();
        assertNotNull(detector, label + ": must wire a neighbor detector");
        // Neighbor detection must not throw for an occupied key (and the API is exercised end-to-end).
        var anyKey = index.getSpatialKeys().iterator().next();
        assertDoesNotThrow(() -> detector.findFaceNeighbors(anyKey),
                           label + ": face-neighbor detection must not throw for an occupied key");
    }
}

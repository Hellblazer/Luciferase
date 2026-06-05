/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.hellblazer.luciferase.esvt.builder;

import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Luciferase-7wzml.167: propagateTypesTopDown was seeding types[0]=0 unconditionally, silently overriding
 * the key-derived root type used by build() (line 112) and buildWithVoxelTracking (line 331). A non-type-0
 * root tree would have its root type silently corrupted to 0.
 *
 * <p>Fix: seed types[0] from nodeList.get(0).tetType; throw IllegalStateException if that is != 0
 * (violates the S0-root invariant) rather than silently forcing 0.
 *
 * <p>This test verifies:
 * <ol>
 *   <li>Normal builds produce rootType matching the key-derived tet type (type 0 for S0-canonical trees).</li>
 *   <li>A non-type-0 root node causes propagateTypesTopDown to throw IllegalStateException (fail loud,
 *       not silently corrupt to 0) — verifying the guard is active and attributable.</li>
 * </ol>
 *
 * @author hal.hildebrand
 */
class ESVTBuilderRootTypeTest {

    /**
     * Normal build: rootType reported in ESVTData must match the key-derived tet type (type 0 for S0-canonical).
     */
    @Test
    void normalBuild_rootTypeMatchesKeyDerivedType() {
        var tetree = new Tetree<>(new SequentialLongIDGenerator());
        tetree.insert(new javax.vecmath.Point3f(10, 10, 10), (byte) 5, "v1");
        tetree.insert(new javax.vecmath.Point3f(12, 10, 10), (byte) 5, "v2");
        tetree.insert(new javax.vecmath.Point3f(10, 12, 10), (byte) 5, "v3");

        var data = new ESVTBuilder().build(tetree);

        // The S0-root invariant guarantees root is always type 0.
        // rootType must be 0 — not some arbitrary silently-forced value.
        assertEquals(0, data.rootType(),
            "rootType must equal the key-derived tet type (0 for S0-canonical root), not a hardcoded 0 "
            + "that may mask a real divergence (Luciferase-7wzml.167)");
    }

    /**
     * Normal build via buildFromVoxels: rootType in ESVTData must be 0 (key-derived, not hardcoded).
     */
    @Test
    void buildFromVoxels_rootTypeIsKeyDerived() {
        var voxels = List.of(
            new Point3i(5, 5, 5),
            new Point3i(6, 5, 5),
            new Point3i(5, 6, 5)
        );
        var data = new ESVTBuilder().buildFromVoxels(voxels, 6, 64);

        assertEquals(0, data.rootType(),
            "rootType from buildFromVoxels must be the key-derived S0-canonical type 0 (Luciferase-7wzml.167)");
        assertTrue(data.nodeCount() > 0, "tree must be non-empty");
    }

    /**
     * Guard: propagateTypesTopDown must throw IllegalStateException when the root node has tetType != 0,
     * rather than silently overriding it to 0 and corrupting all descendant type derivations.
     *
     * <p>This is tested by constructing a synthetic NodeEntry list (same package, reflective access to
     * the private record) whose root has tetType=1, then calling the private method directly.
     */
    @Test
    void propagateTypesTopDown_nonZeroRootThrowsIllegalState() throws Exception {
        var builder = new ESVTBuilder();

        // Reflect on the private NodeEntry record constructor
        // NodeEntry(TetreeKey key, Tet tet, byte tetType, boolean isLeaf, TetreeKey parentKey)
        Class<?> nodeEntryClass = null;
        for (var dc : ESVTBuilder.class.getDeclaredClasses()) {
            if (dc.getSimpleName().equals("NodeEntry")) {
                nodeEntryClass = dc;
                break;
            }
        }
        assertNotNull(nodeEntryClass, "NodeEntry inner record must exist");

        // Build a Tet whose canonical type is NOT 0 (type 1 — valid S0-S5 type, just not the S0-root).
        // We use a pure-Tetree tet (NO_TET_ANCESTOR) at level 2, type 1.
        int cellSize = Constants.lengthAtLevel((byte) 2);
        // 5-arg Tet constructor: pure tetree (no pyramid ancestor)
        var nonZeroTypeTet = new Tet(cellSize, cellSize, cellSize, (byte) 2, (byte) 1);
        var nonZeroKey = (TetreeKey<? extends TetreeKey<?>>) nonZeroTypeTet.tmIndex();

        // Construct NodeEntry with tetType=1 via reflection
        var nodeEntryCtor = nodeEntryClass.getDeclaredConstructors()[0];
        nodeEntryCtor.setAccessible(true);
        var rootEntry = nodeEntryCtor.newInstance(nonZeroKey, nonZeroTypeTet, (byte) 1, true, null);

        var nodeList = new ArrayList<>();
        nodeList.add(rootEntry);

        // Reflect on propagateTypesTopDown
        Method propagate = ESVTBuilder.class.getDeclaredMethod(
            "propagateTypesTopDown", List.class, Map.class, Map.class);
        propagate.setAccessible(true);

        // Must throw IllegalStateException (wrapped in InvocationTargetException by reflection)
        var ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> propagate.invoke(builder, nodeList, Map.of(), Map.of()));

        assertInstanceOf(IllegalStateException.class, ex.getCause(),
            "cause must be IllegalStateException when root tetType != 0");
        assertTrue(ex.getCause().getMessage().contains("Luciferase-7wzml.167"),
            "message must cite the bead, got: " + ex.getCause().getMessage());
        assertTrue(ex.getCause().getMessage().contains("type 1"),
            "message must report the actual non-zero type, got: " + ex.getCause().getMessage());
    }
}

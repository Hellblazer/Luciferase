/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test to identify index space mismatch between:
 * - ESVTChildOrder (uses Bey indices from CHILDREN_AT_FACE)
 * - ESVTNodeUnified (uses Morton indices for childMask)
 * - ESVTTraversal (must convert between them)
 */
class ESVTIndexSpaceDiagnosticTest {

    /**
     * Verify that INDEX_TO_BEY_NUMBER provides Morton -> Bey mapping
     */
    @Test
    void testMortonToBeyMapping() {
        System.out.println("=== Morton -> Bey Mapping (INDEX_TO_BEY_NUMBER) ===");
        for (int type = 0; type < 6; type++) {
            System.out.printf("Type %d: ", type);
            for (int morton = 0; morton < 8; morton++) {
                int bey = TetreeConnectivity.INDEX_TO_BEY_NUMBER[type][morton];
                System.out.printf("M%d->B%d ", morton, bey);
            }
            System.out.println();
        }
    }

    /**
     * Compute and display inverse mapping: Bey -> Morton
     */
    @Test
    void testBeyToMortonMapping() {
        System.out.println("\n=== Bey -> Morton Mapping (Inverse, REQUIRED FOR FIX) ===");
        byte[][] BEY_TO_MORTON = new byte[6][8];

        for (int type = 0; type < 6; type++) {
            // Compute inverse mapping
            for (int morton = 0; morton < 8; morton++) {
                int bey = TetreeConnectivity.INDEX_TO_BEY_NUMBER[type][morton];
                BEY_TO_MORTON[type][bey] = (byte) morton;
            }

            System.out.printf("Type %d: { ", type);
            for (int bey = 0; bey < 8; bey++) {
                System.out.printf("%d", BEY_TO_MORTON[type][bey]);
                if (bey < 7) System.out.print(", ");
            }
            System.out.println(" }");
        }
    }

    /**
     * Show what CHILDREN_AT_FACE returns (Bey indices)
     */
    @Test
    void testChildrenAtFaceAreBeyIndices() {
        System.out.println("\n=== CHILDREN_AT_FACE (Bey indices) ===");
        for (int face = 0; face < 4; face++) {
            byte[] children = TetreeConnectivity.CHILDREN_AT_FACE[0][face];
            System.out.printf("Face %d: Bey children = {%d, %d, %d, %d}%n",
                face, children[0], children[1], children[2], children[3]);
        }
    }

    /**
     * Demonstrate the index space mismatch
     */
    @Test
    void testIndexSpaceMismatch() {
        System.out.println("\n=== INDEX SPACE MISMATCH DEMONSTRATION ===");

        // Build a small tree
        var builder = new ESVTBuilder();
        List<Point3i> voxels = new ArrayList<>();
        // Create 8 voxels at corners
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    voxels.add(new Point3i(x * 10, y * 10, z * 10));
                }
            }
        }
        var data = builder.buildFromVoxels(voxels, 3);

        System.out.printf("Built tree: %d nodes%n", data.nodeCount());

        var root = data.root();
        int childMask = root.getChildMask();
        System.out.printf("Root childMask (MORTON space): 0x%02X = ", childMask);
        for (int i = 0; i < 8; i++) {
            if ((childMask & (1 << i)) != 0) {
                System.out.printf("M%d ", i);
            }
        }
        System.out.println();

        // Now show what ESVTChildOrder.getChildOrder returns for entry face 1
        byte[] childOrder = ESVTChildOrder.getChildOrder(0, 1); // Type 0, face 1
        System.out.printf("ESVTChildOrder.getChildOrder(type=0, face=1) returns: {%d, %d, %d, %d} (BEY indices!)%n",
            childOrder[0], childOrder[1], childOrder[2], childOrder[3]);

        // Show the mismatch
        System.out.println("\nMISMATCH:");
        for (byte beyIdx : childOrder) {
            boolean hasChildWrong = root.hasChild(beyIdx); // WRONG: using Bey as Morton!
            // Compute correct Morton index
            int correctMorton = findMortonForBey(0, beyIdx);
            boolean hasChildCorrect = root.hasChild(correctMorton);

            System.out.printf("  Bey %d: hasChild(beyIdx=%d)=%b WRONG, hasChild(morton=%d)=%b CORRECT%n",
                beyIdx, beyIdx, hasChildWrong, correctMorton, hasChildCorrect);
        }
    }

    /**
     * Test that demonstrates the fix: convert Bey to Morton
     */
    @Test
    void testFixedTraversal() {
        System.out.println("\n=== FIXED TRAVERSAL (Bey -> Morton conversion) ===");

        // Build inverse mapping
        byte[][] BEY_TO_MORTON = computeBeyToMortonTable();

        // Build test tree
        var builder = new ESVTBuilder();
        List<Point3i> voxels = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        var data = builder.buildFromVoxels(voxels, 4);
        var nodes = data.nodes();
        var root = nodes[0];

        byte rootType = root.getTetType();
        System.out.printf("Root type: %d, childMask: 0x%02X%n", rootType, root.getChildMask());

        // Test traversal with face 1 (what the failing test uses based on entryFace=1)
        byte[] childOrderBey = ESVTChildOrder.getChildOrder(rootType, 1);
        System.out.println("Face 1 children (Bey): " + java.util.Arrays.toString(childOrderBey));

        int childrenFound = 0;
        for (byte beyIdx : childOrderBey) {
            // FIXED: Convert Bey to Morton
            int mortonIdx = BEY_TO_MORTON[rootType][beyIdx];
            if (root.hasChild(mortonIdx)) {
                childrenFound++;
                System.out.printf("  Found child at Bey %d (Morton %d)%n", beyIdx, mortonIdx);
            }
        }

        System.out.printf("Children found: %d (should be > 0)%n", childrenFound);
        assertTrue(childrenFound > 0, "Should find at least one child when using correct index conversion");
    }

    /**
     * Full ray traversal diagnostic with verbose output
     */
    @Test
    void testRayTraversalDiagnostic() {
        System.out.println("\n=== RAY TRAVERSAL DIAGNOSTIC ===");

        // Build test cube
        var builder = new ESVTBuilder();
        List<Point3i> voxels = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }
        var data = builder.buildFromVoxels(voxels, 4);
        var nodes = data.nodes();

        System.out.printf("Tree: %d nodes, %d leaves%n", data.nodeCount(), data.leafCount());

        // Test ray
        var rayOrigin = new Point3f(-0.1f, 0.5f, 0.5f);
        var rayDir = new Vector3f(1, 0, 0);

        // Test root intersection
        var intersector = MollerTrumboreIntersection.create();
        var tetResult = new MollerTrumboreIntersection.TetrahedronResult();

        var s0Verts = Constants.SIMPLEX_STANDARD[0];
        var v0 = new Point3f(s0Verts[0].x, s0Verts[0].y, s0Verts[0].z);
        var v1 = new Point3f(s0Verts[1].x, s0Verts[1].y, s0Verts[1].z);
        var v2 = new Point3f(s0Verts[2].x, s0Verts[2].y, s0Verts[2].z);
        var v3 = new Point3f(s0Verts[3].x, s0Verts[3].y, s0Verts[3].z);

        boolean hitsRoot = intersector.intersectTetrahedron(rayOrigin, rayDir, v0, v1, v2, v3, tetResult);
        System.out.printf("Root S0 intersection: hit=%b, tEntry=%.4f, entryFace=%d%n",
            hitsRoot, tetResult.tEntry, tetResult.entryFace);

        assertTrue(hitsRoot, "Ray should hit root S0 tetrahedron");

        int entryFace = tetResult.entryFace;
        byte rootType = nodes[0].getTetType();

        // Get children at entry face
        byte[] childOrderBey = ESVTChildOrder.getChildOrder(rootType, entryFace);
        System.out.printf("Entry face %d, children (Bey): %s%n",
            entryFace, java.util.Arrays.toString(childOrderBey));

        // Check children with and without conversion
        byte[][] BEY_TO_MORTON = computeBeyToMortonTable();

        System.out.println("\nWithout Bey->Morton conversion (CURRENT BUG):");
        for (byte beyIdx : childOrderBey) {
            boolean found = nodes[0].hasChild(beyIdx);
            System.out.printf("  hasChild(%d) = %b%n", beyIdx, found);
        }

        System.out.println("\nWith Bey->Morton conversion (REQUIRED FIX):");
        int fixedChildCount = 0;
        for (byte beyIdx : childOrderBey) {
            int mortonIdx = BEY_TO_MORTON[rootType][beyIdx];
            boolean found = nodes[0].hasChild(mortonIdx);
            System.out.printf("  Bey %d -> Morton %d, hasChild = %b%n", beyIdx, mortonIdx, found);
            if (found) fixedChildCount++;
        }

        System.out.printf("\nChildren found with fix: %d%n", fixedChildCount);

        // The test should pass when using correct conversion
        assertTrue(fixedChildCount > 0,
            "With correct Bey->Morton conversion, should find children at entry face");
    }

    // Helper: compute inverse mapping
    private byte[][] computeBeyToMortonTable() {
        byte[][] table = new byte[6][8];
        for (int type = 0; type < 6; type++) {
            for (int morton = 0; morton < 8; morton++) {
                int bey = TetreeConnectivity.INDEX_TO_BEY_NUMBER[type][morton];
                table[type][bey] = (byte) morton;
            }
        }
        return table;
    }

    // Helper: find Morton index for a given Bey index and type
    private int findMortonForBey(int type, int beyIdx) {
        for (int morton = 0; morton < 8; morton++) {
            if (TetreeConnectivity.INDEX_TO_BEY_NUMBER[type][morton] == beyIdx) {
                return morton;
            }
        }
        return -1;
    }
}

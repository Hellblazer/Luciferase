/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to dump ESVT node types and verify correctness
 */
class ESVTNodeTypeDumpTest {

    @Test
    void testNodeTypes() {
        // Build solid cube
        int resolution = 16;
        List<Point3i> voxels = new ArrayList<>();
        for (int x = 0; x < resolution; x++) {
            for (int y = 0; y < resolution; y++) {
                for (int z = 0; z < resolution; z++) {
                    voxels.add(new Point3i(x, y, z));
                }
            }
        }

        var builder = new ESVTBuilder();
        var data = builder.buildFromVoxels(voxels, 4);
        var nodes = data.nodes();

        System.out.printf("Tree: %d nodes, %d leaves%n", data.nodeCount(), data.leafCount());

        // Dump first 20 nodes
        System.out.println("\nFirst 20 nodes:");
        for (int i = 0; i < Math.min(20, nodes.length); i++) {
            System.out.printf("  Node %d: type=%d, childMask=0x%02X, leafMask=0x%02X, childPtr=%d%n",
                i, nodes[i].getTetType(), nodes[i].getChildMask(),
                nodes[i].getLeafMask(), nodes[i].getChildPtr());
        }

        // Root should be type 0
        byte rootType = nodes[0].getTetType();
        System.out.printf("%nRoot type: %d%n", rootType);
        assertEquals(0, rootType, "Root should be type 0");

        // Check children of root
        System.out.println("\nRoot: childMask=0x" + Integer.toHexString(nodes[0].getChildMask()) +
                          ", childPtr=" + nodes[0].getChildPtr());
        System.out.println("\nRoot children (comparing getChildType() derived vs stored node type):");
        for (int mortonIdx = 0; mortonIdx < 8; mortonIdx++) {
            if (!nodes[0].hasChild(mortonIdx)) {
                System.out.printf("  Morton %d: NO CHILD%n", mortonIdx);
                continue;
            }

            int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[rootType][mortonIdx];
            int childNodeIdx = nodes[0].getChildIndex(mortonIdx, 0); // root at index 0
            byte storedType = nodes[childNodeIdx].getTetType();
            byte expectedType = TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[rootType][beyIdx];
            byte derivedType = nodes[0].getChildType(mortonIdx);  // Uses stored parent type + lookup table

            System.out.printf("  Morton %d (Bey %d): childIdx=%d, storedType=%d, expectedType=%d, derivedType=%d %s%n",
                mortonIdx, beyIdx, childNodeIdx, storedType, expectedType, derivedType,
                storedType == expectedType ? "OK" : "*** MISMATCH (stored != expected) ***");
        }

        // Verify all root children have correct types
        System.out.println("\nVerifying child types...");
        int mismatches = 0;
        for (int mortonIdx = 0; mortonIdx < 8; mortonIdx++) {
            if (nodes[0].hasChild(mortonIdx)) {
                int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[rootType][mortonIdx];
                int childNodeIdx = nodes[0].getChildIndex(mortonIdx, 0); // root at index 0
                byte storedType = nodes[childNodeIdx].getTetType();
                byte expectedType = TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[rootType][beyIdx];

                if (expectedType != storedType) {
                    System.out.printf("MISMATCH: Morton %d (Bey %d) child should have type %d but has %d%n",
                        mortonIdx, beyIdx, expectedType, storedType);
                    mismatches++;
                }
            }
        }

        // Also check what getChildType() returns (computed via lookup table)
        System.out.println("\nESVTNodeUnified.getChildType() for each morton index (computed, not stored):");
        for (int mortonIdx = 0; mortonIdx < 8; mortonIdx++) {
            if (nodes[0].hasChild(mortonIdx)) {
                byte derivedType = nodes[0].getChildType(mortonIdx);
                int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[rootType][mortonIdx];
                byte expectedType = TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[rootType][beyIdx];
                System.out.printf("  Morton %d: getChildType=%d, expected=%d %s%n",
                    mortonIdx, derivedType, expectedType,
                    derivedType == expectedType ? "OK" : "*** WRONG ***");
            }
        }

        System.out.println("\n=== Conclusion ===");
        System.out.println("Mismatches between stored node types and expected types: " + mismatches);

        if (mismatches > 0) {
            fail("Found " + mismatches + " type mismatches - see output above for details");
        }
    }
}

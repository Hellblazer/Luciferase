/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.esvt.traversal;

import com.hellblazer.luciferase.lucien.tetree.BeySubdivision;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to verify type derivation from TetreeKey
 */
class TypeDerivationDebugTest {

    @Test
    void testChildTypeFromKey() {
        System.out.println("=== TYPE DERIVATION DEBUG ===\n");

        // Create root S0 tetrahedron at level 0
        var root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        System.out.printf("Root: type=%d, level=%d%n", root.type, root.l);
        assertEquals(0, root.type, "Root should be type 0");

        // Check child types according to CHILD_TYPES table
        // For parent type 0: { 0, 0, 0, 0, 4, 5, 2, 1 }
        System.out.println("\nExpected child types for parent type 0 (from CHILD_TYPES):");
        System.out.println("  Bey 0 -> type 0 (corner)");
        System.out.println("  Bey 1 -> type 0 (corner)");
        System.out.println("  Bey 2 -> type 0 (corner)");
        System.out.println("  Bey 3 -> type 0 (corner)");
        System.out.println("  Bey 4 -> type 4 (octahedral)");
        System.out.println("  Bey 5 -> type 5 (octahedral)");
        System.out.println("  Bey 6 -> type 2 (octahedral)");
        System.out.println("  Bey 7 -> type 1 (octahedral)");

        System.out.println("\nActual child types via Tet.child(mortonIdx).type:");
        for (int mortonIdx = 0; mortonIdx < 8; mortonIdx++) {
            var child = root.child(mortonIdx);
            int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[root.type][mortonIdx];
            System.out.printf("  Morton %d -> Bey %d -> child.type=%d%n",
                mortonIdx, beyIdx, child.type);
        }

        // Check type via TetreeKey
        System.out.println("\nTypes via Tet.tetrahedron(key).type:");
        for (int mortonIdx = 0; mortonIdx < 8; mortonIdx++) {
            var child = root.child(mortonIdx);
            var key = child.tmIndex();
            var tet = Tet.tetrahedron(key);
            int beyIdx = TetreeConnectivity.INDEX_TO_BEY_NUMBER[root.type][mortonIdx];
            System.out.printf("  Morton %d -> Bey %d -> key-derived type=%d (expected=%d)%n",
                mortonIdx, beyIdx, tet.type, getExpectedType(0, beyIdx));
        }

        // Verify Morton 6 (Bey 6) -> type 2
        var child6 = root.child(6);  // Morton 6
        int bey6 = TetreeConnectivity.INDEX_TO_BEY_NUMBER[0][6];
        assertEquals(6, bey6, "Morton 6 should map to Bey 6 for type 0");

        var key6 = child6.tmIndex();
        var tet6 = Tet.tetrahedron(key6);
        System.out.printf("\nMorton 6 child: direct type=%d, key-derived type=%d, expected=2%n",
            child6.type, tet6.type);

        // Check PARENT_TYPE_TO_CHILD_TYPE
        byte expected = TetreeConnectivity.PARENT_TYPE_TO_CHILD_TYPE[0][6];
        System.out.printf("PARENT_TYPE_TO_CHILD_TYPE[0][6] = %d%n", expected);

        // The key assertion
        assertEquals(2, tet6.type, "Morton 6 child of type 0 should have type 2");
    }

    private int getExpectedType(int parentType, int beyIdx) {
        // From BeySubdivision.CHILD_TYPES
        int[][] childTypes = {
            { 0, 0, 0, 0, 4, 5, 2, 1 },  // Parent type 0
            { 1, 1, 1, 1, 3, 2, 5, 0 },  // Parent type 1
            { 2, 2, 2, 2, 0, 1, 4, 3 },  // Parent type 2
            { 3, 3, 3, 3, 5, 4, 1, 2 },  // Parent type 3
            { 4, 4, 4, 4, 2, 3, 0, 5 },  // Parent type 4
            { 5, 5, 5, 5, 1, 0, 3, 4 }   // Parent type 5
        };
        return childTypes[parentType][beyIdx];
    }
}

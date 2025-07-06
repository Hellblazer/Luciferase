package com.hellblazer.luciferase.lucien.tetree;

import java.util.HashSet;
import java.util.Set;

/**
 * Helper class to demonstrate the fix for tetree collision detection.
 *
 * The issue: Entities in different tetrahedra within the same grid cell aren't being checked for collisions.
 *
 * The solution: When checking collisions, also check all 6 tetrahedra within the same grid cell, not just neighboring
 * grid cells.
 */
public class TetreeCollisionFix {

    /**
     * Demonstrates the fix needed in Tetree.findAllCollisions or similar method.
     *
     * Instead of just checking neighboring grid cells, we also need to check all tetrahedra within the same grid cell.
     */
    public static void demonstrateFix() {
        // Example: Entity at (100, 100, 100) in tetrahedron type 3
        var tet1 = new Tet(0, 0, 0, (byte) 10, (byte) 3);

        // Entity at (100.05, 100, 100) in tetrahedron type 0 (same grid cell!)
        var tet2 = new Tet(0, 0, 0, (byte) 10, (byte) 0);

        System.out.println("Entity 1 in: " + tet1);
        System.out.println("Entity 2 in: " + tet2);
        System.out.println("Same grid cell? " + (tet1.x() == tet2.x() && tet1.y() == tet2.y() && tet1.z() == tet2.z()));
        System.out.println("Same tetrahedron? " + (tet1.tmIndex().equals(tet2.tmIndex())));

        // The fix: Check all tetrahedra in the same cell
        var tetrahedraToCheck = getTetrahedraInSameCell(tet1);
        System.out.println("\nTetrahedra to check in same cell: " + tetrahedraToCheck.size());
        for (var tetKey : tetrahedraToCheck) {
            var tet = Tet.tetrahedron(tetKey);
            System.out.println("  Type " + tet.type() + ": " + tetKey);
        }
    }

    /**
     * Get all tetrahedral indices within the same grid cell as the given tetrahedron.
     *
     * @param tet The reference tetrahedron
     * @return Set of all 6 tetrahedral indices in the same grid cell
     */
    public static Set<TetreeKey<?>> getTetrahedraInSameCell(Tet tet) {
        var indices = new HashSet<TetreeKey<?>>();

        // The grid cell contains 6 tetrahedra (types 0-5)
        for (byte type = 0; type < 6; type++) {
            var sameCellTet = new Tet(tet.x(), tet.y(), tet.z(), tet.l(), type);
            indices.add(sameCellTet.tmIndex());
        }

        return indices;
    }
}

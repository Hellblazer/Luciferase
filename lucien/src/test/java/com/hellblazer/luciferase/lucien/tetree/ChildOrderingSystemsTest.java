package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

/**
 * Test demonstrating the two different child ordering systems in Tetree: 
 * 1. Morton Order (used by child() method - default)
 * 2. TM-Index Order (used by childTM() method - true space-filling curve)
 */
public class ChildOrderingSystemsTest {

    @Test
    public void demonstrateTwoOrderingSystems() {
        System.out.println("=== Two Child Ordering Systems in Tetree ===\n");

        // Start with root tetrahedron
        Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        System.out.println("Root tetrahedron: " + root);
        System.out.println("\nComparing child generation methods:\n");

        // Headers
        System.out.println("Index | Morton Order   | TM-Index Order  | Same Position?");
        System.out.println("      | (child())      | (childTM())     |");
        System.out.println("------|----------------|-----------------|---------------");

        for (int i = 0; i < 8; i++) {
            // Generate children using both methods
            Tet mortonChild = root.child(i);
            Tet tmChild = root.childTM(i);

            // Format output
            System.out.printf("%5d | Type %d at %-6s | Type %d at %-6s | %s\n", 
                i, 
                mortonChild.type(), formatCoords(mortonChild), 
                tmChild.type(), formatCoords(tmChild),
                coordsMatch(mortonChild, tmChild) ? "Yes" : "No");
        }

        System.out.println("\n=== Key Insights ===\n");

        System.out.println("1. TM-Index Order is the true space-filling curve:");
        System.out.println("   - Provides better spatial locality");
        System.out.println("   - Adjacent indices correspond to spatially adjacent tetrahedra");
        System.out.println("   - childTM() now correctly uses TM-to-Bey permutation");

        System.out.println("\n2. Morton Order is the default:");
        System.out.println("   - Based on bit interleaving");
        System.out.println("   - Not optimal for tetrahedral spatial locality");
        System.out.println("   - Uses vertex midpoint algorithm internally");

        System.out.println("\n3. Both use the same geometric subdivision:");
        System.out.println("   - Both internally convert to Bey order for geometry");
        System.out.println("   - The difference is only in the access order");
        System.out.println("   - TM order preserves spatial locality better");
    }

    private boolean coordsMatch(Tet a, Tet b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
    }

    private String formatCoords(Tet tet) {
        return String.format("(%d,%d,%d)", tet.x(), tet.y(), tet.z());
    }
}
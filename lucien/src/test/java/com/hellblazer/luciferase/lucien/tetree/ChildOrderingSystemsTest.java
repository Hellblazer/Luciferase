package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

/**
 * Test demonstrating the three different child ordering systems in Tetree: 1. Bey Order (used by child() method) 2.
 * TM-Index Order (used by childTM() method) 3. Standard/Morton Order (used by childStandard() method)
 */
public class ChildOrderingSystemsTest {

    @Test
    public void demonstrateThreeOrderingSystems() {
        System.out.println("=== Three Child Ordering Systems in Tetree ===\n");

        // Start with root tetrahedron
        Tet root = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        System.out.println("Root tetrahedron: " + root);
        System.out.println("\nComparing child generation methods:\n");

        // Headers
        System.out.println("Index | Bey Order      | TM-Index Order  | Standard Order  | Coordinate Differences");
        System.out.println("      | (child())      | (childTM())     | (childStandard())");
        System.out.println("------|----------------|-----------------|-----------------|----------------------");

        for (int i = 0; i < 8; i++) {
            // Generate children using all three methods
            Tet beyChild = root.child(i);
            Tet tmChild = root.childTM((byte) i);
            Tet standardChild = root.childStandard(i);

            // Format output
            System.out.printf("%5d | Type %d at %-6s | Type %d at %-6s | Type %d at %-6s | ", i, beyChild.type(),
                              formatCoords(beyChild), tmChild.type(), formatCoords(tmChild), standardChild.type(),
                              formatCoords(standardChild));

            // Show if coordinates differ
            if (!coordsMatch(beyChild, tmChild) || !coordsMatch(beyChild, standardChild)) {
                System.out.print("DIFFERENT");
            } else {
                System.out.print("Same");
            }
            System.out.println();
        }

        System.out.println("\n=== Key Insights ===\n");

        // Show the remapping table
        System.out.println("1. TM-Index Order Remapping:");
        System.out.println("   childTM(i) actually calls child(TYPE_TO_TYPE_OF_CHILD_MORTON[type][i])");
        System.out.println("   For type 0 parent:");
        byte[] remapping = Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[0];
        for (int i = 0; i < 8; i++) {
            System.out.printf("   childTM(%d) -> child(%d)\n", i, remapping[i]);
        }

        System.out.println("\n2. Coordinate Calculation Differences:");
        System.out.println("   - Bey Order: Uses vertex midpoint algorithm (Bey's tetrahedral refinement)");
        System.out.println("   - TM-Index Order: Same as Bey (just reordered)");
        System.out.println("   - Standard Order: Uses grid-aligned octant positions (like octree)");

        System.out.println("\n3. Type Assignment Differences:");
        System.out.println("   - Bey Order: Uses t8code connectivity tables (TYPE_OF_CHILD)");
        System.out.println("   - TM-Index Order: Same types as Bey, just accessed in different order");
        System.out.println("   - Standard Order: Uses TYPE_TO_TYPE_OF_CHILD table directly");

        // Show the actual coordinates at level 1
        System.out.println("\n=== Actual Coordinates at Level 1 ===");
        System.out.println("Cell size at level 1: " + Constants.lengthAtLevel((byte) 1));
        System.out.println("\nStandard/Morton order (grid-aligned octants):");
        for (int i = 0; i < 8; i++) {
            Tet child = root.childStandard(i);
            System.out.printf("Child %d: (%d, %d, %d)\n", i, child.x(), child.y(), child.z());
        }

        System.out.println("\nBey order (vertex midpoints):");
        for (int i = 0; i < 8; i++) {
            Tet child = root.child(i);
            System.out.printf("Child %d: (%d, %d, %d)\n", i, child.x(), child.y(), child.z());
        }
    }

    private boolean coordsMatch(Tet a, Tet b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
    }

    private String formatCoords(Tet tet) {
        // Show coordinates in grid units (divide by cell size)
        int cellSize = Constants.lengthAtLevel(tet.l());
        int gridX = tet.x() / cellSize;
        int gridY = tet.y() / cellSize;
        int gridZ = tet.z() / cellSize;
        return String.format("(%d,%d,%d)", gridX, gridY, gridZ);
    }
}

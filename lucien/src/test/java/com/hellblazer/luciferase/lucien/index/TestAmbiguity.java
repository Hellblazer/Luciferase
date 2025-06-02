package com.hellblazer.luciferase.lucien.index;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.Tet;

// Test to demonstrate the ambiguity in the transformation chain
public class TestAmbiguity {
    public static void main(String[] args) {
        // Start with tet1
        Tet tet1 = new Tet(Constants.lengthAtLevel((byte) 3), Constants.lengthAtLevel((byte) 3),
                           Constants.lengthAtLevel((byte) 3), (byte) 3, (byte) 1);

        System.out.println("Original tet1: " + tet1);

        // Step 1-3: tet1 -> index1 -> tet1 (should be perfect)
        long index1 = tet1.index();
        Tet roundtrip1 = Tet.tetrahedron(index1);
        System.out.println("After SFC roundtrip: " + roundtrip1);
        System.out.println("SFC roundtrip perfect: " + tet1.equals(roundtrip1));

        // Step 4: tet1 -> child(3) -> tet2 (geometric subdivision)
        Tet tet2 = tet1.child((byte) 3);
        System.out.println("Child tet2: " + tet2);

        // Step 5-6: tet2 -> index2 -> tet2 (should be perfect)
        long index2 = tet2.index();
        Tet roundtrip2 = Tet.tetrahedron(index2);
        System.out.println("Child after SFC roundtrip: " + roundtrip2);
        System.out.println("Child SFC roundtrip perfect: " + tet2.equals(roundtrip2));

        // Step 7: tet2 -> parent -> ??? (SFC tree traversal)
        Tet parentResult = tet2.parent();
        System.out.println("Parent result: " + parentResult);
        System.out.println("Parent matches original: " + tet1.equals(parentResult));

        // Show the coordinates to see the difference
        if (!tet1.equals(parentResult)) {
            System.out.println("AMBIGUITY DETECTED:");
            System.out.println("  Original tet1 coords: " + java.util.Arrays.toString(tet1.coordinates()));
            System.out.println("  Parent result coords: " + java.util.Arrays.toString(parentResult.coordinates()));
        }
    }
}

package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validate that the Bey refinement implementation follows t8code conventions
 */
public class BeyRefinementValidationTest {

    @Test
    void testBeyRefinementProperties() {
        System.out.println("=== Testing Bey Refinement Properties ===\n");

        var parent = new Tet(0, 0, 0, (byte) 0, (byte) 0);

        // Bey refinement should create 8 children
        var children = new Tet[8];
        for (int i = 0; i < 8; i++) {
            children[i] = parent.child(i);
        }

        // Each child should be at the next level
        for (int i = 0; i < 8; i++) {
            assertEquals(parent.l() + 1, children[i].l(), "Child should be at parent level + 1");
        }

        // Children should have distinct tm-indices
        var indices = new java.util.HashSet<TetreeKey<?>>();
        for (int i = 0; i < 8; i++) {
            var tmIndex = children[i].tmIndex();
            assertTrue(indices.add(tmIndex), "Each child should have a unique tm-index");
        }

        System.out.println("All 8 children have unique tm-indices");
    }

    @Test
    void testChildTypesFollowT8Code() {
        System.out.println("=== Testing Child Types Follow t8code ===\n");

        // Test specific known parent-child type relationships from t8code
        var testCases = new int[][] {
        // {parentType, childIndex, expectedChildType}
        { 0, 0, 0 }, // Type 0 parent, child 0 should be type 0
        { 0, 7, 0 }, // Type 0 parent, child 7 should be type 0
        // Add more test cases based on t8code tables
        };

        for (var testCase : testCases) {
            var parentType = (byte) testCase[0];
            var childIndex = testCase[1];
            var expectedType = (byte) testCase[2];

            var parent = new Tet(0, 0, 0, (byte) 1, parentType);
            var child = parent.child(childIndex);

            System.out.printf("Parent type %d, child %d -> type %d (expected %d)\n", parentType, childIndex,
                              child.type(), expectedType);

            assertEquals(expectedType, child.type(),
                         String.format("Child %d of parent type %d should have type %d", childIndex, parentType,
                                       expectedType));
        }
    }

    @Test
    void testParentChildRoundTrip() {
        System.out.println("=== Testing Parent-Child Round Trip ===\n");

        // For each parent type
        for (byte parentType = 0; parentType < 6; parentType++) {
            var parent = new Tet(0, 0, 0, (byte) 1, parentType);
            System.out.printf("Parent type %d:\n", parentType);

            // Get all children and verify they compute back to the same parent
            for (int i = 0; i < 8; i++) {
                var child = parent.child(i);
                var computedParent = child.parent();

                System.out.printf("  Child %d (type %d) -> parent type %d\n", i, child.type(), computedParent.type());

                assertEquals(parent.x(), computedParent.x(), "Parent X should match");
                assertEquals(parent.y(), computedParent.y(), "Parent Y should match");
                assertEquals(parent.z(), computedParent.z(), "Parent Z should match");
                assertEquals(parent.l(), computedParent.l(), "Parent level should match");
                assertEquals(parent.type(), computedParent.type(), "Parent type should match");
            }
            System.out.println();
        }
    }
}

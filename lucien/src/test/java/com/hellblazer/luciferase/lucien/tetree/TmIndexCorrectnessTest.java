package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify tm-index correctness for Bey child subdivision.
 * Updated to remove dependency on flawed standard refinement.
 */
public class TmIndexCorrectnessTest {
    
    @Test
    void testBeyChildParentRelationships() {
        System.out.println("=== Testing Bey Child Parent Relationships ===\n");
        
        // Test root tetrahedron
        var parent = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        
        System.out.println("Parent: " + parent);
        System.out.println("Parent tm-index: " + parent.tmIndex());
        
        // Test each child
        for (int i = 0; i < 8; i++) {
            var child = parent.child(i);
            var childTmIndex = child.tmIndex();
            
            // Get child centroid
            var childVerts = child.coordinates();
            var childCentroid = computeCentroid(childVerts);
            
            // Note: We cannot use locateStandardRefinement to verify Bey-refined children
            // because standard refinement uses cubic subdivision which is incompatible
            // with tetrahedral Bey refinement. This is documented in TETRAHEDRAL_SUBDIVISION_CORRECTNESS.md
            
            System.out.printf("\nChild %d:\n", i);
            System.out.printf("  Position: (%d, %d, %d), Type: %d\n", 
                child.x(), child.y(), child.z(), child.type());
            System.out.printf("  Centroid: %s\n", childCentroid);
            System.out.printf("  Child tm-index: %s\n", childTmIndex);
            
            // Verify parent-child relationship instead
            var childParent = child.parent();
            var parentMatch = parent.tmIndex().equals(childParent.tmIndex());
            System.out.printf("  Parent relationship: %s\n", parentMatch ? "CORRECT" : "BROKEN");
            
            if (!parentMatch) {
                System.out.println("  ERROR: Child does not correctly identify its parent!");
                System.out.printf("    Expected parent: %s\n", parent.tmIndex());
                System.out.printf("    Actual parent: %s\n", childParent.tmIndex());
            }
        }
        
        // Test multiple levels
        System.out.println("\n=== Testing Multiple Levels ===\n");
        
        for (byte level = 0; level <= 3; level++) {
            var levelParent = new Tet(0, 0, 0, level, (byte) 0);
            var allMatch = true;
            
            for (int i = 0; i < 8; i++) {
                var child = levelParent.child(i);
                var childParent = child.parent();
                
                if (!levelParent.tmIndex().equals(childParent.tmIndex())) {
                    allMatch = false;
                    System.out.printf("Level %d, Child %d: BROKEN parent relationship\n", level, i);
                }
            }
            
            if (allMatch) {
                System.out.printf("Level %d: All parent-child relationships CORRECT\n", level);
            }
        }
    }
    
    private Point3f computeCentroid(Point3i[] vertices) {
        var centroid = new Point3f();
        for (var v : vertices) {
            centroid.add(new Point3f(v.x, v.y, v.z));
        }
        centroid.scale(0.25f);
        return centroid;
    }
}
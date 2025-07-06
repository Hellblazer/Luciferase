package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify if Tet.child() and BeySubdivision.getMortonChild() produce the same results.
 */
public class TetChildVsBeySubdivisionTest {
    
    @Test
    void compareChildMethods() {
        System.out.println("=== Comparing Tet.child() vs BeySubdivision.getMortonChild() ===\n");
        
        boolean allMatch = true;
        
        for (byte parentType = 0; parentType < 6; parentType++) {
            System.out.printf("Parent type %d:\n", parentType);
            Tet parent = new Tet(0, 0, 0, (byte) 10, parentType);
            
            for (int childIndex = 0; childIndex < 8; childIndex++) {
                // Get child using Tet's method
                Tet tetChild = parent.child(childIndex);
                
                // Get child using BeySubdivision
                Tet beyChild = BeySubdivision.getMortonChild(parent, childIndex);
                
                // Compare coordinates
                boolean xMatch = tetChild.x() == beyChild.x();
                boolean yMatch = tetChild.y() == beyChild.y();
                boolean zMatch = tetChild.z() == beyChild.z();
                boolean typeMatch = tetChild.type() == beyChild.type();
                
                if (!xMatch || !yMatch || !zMatch || !typeMatch) {
                    allMatch = false;
                    System.out.printf("  Child %d MISMATCH:\n", childIndex);
                    System.out.printf("    Tet.child():     (%d, %d, %d) type %d\n", 
                        tetChild.x(), tetChild.y(), tetChild.z(), tetChild.type());
                    System.out.printf("    BeySubdivision: (%d, %d, %d) type %d\n", 
                        beyChild.x(), beyChild.y(), beyChild.z(), beyChild.type());
                } else {
                    System.out.printf("  Child %d: ✓ Match\n", childIndex);
                }
            }
            System.out.println();
        }
        
        if (allMatch) {
            System.out.println("✓ All children match! Tet.child() can be replaced with BeySubdivision.getMortonChild()");
        } else {
            System.out.println("✗ Methods produce different results - cannot replace");
        }
        
        assertTrue(allMatch, "Tet.child() and BeySubdivision.getMortonChild() should produce identical results");
    }
    
    @Test
    void analyzeCoordinateDifferences() {
        System.out.println("\n=== Analyzing Coordinate Systems ===\n");
        
        Tet parent = new Tet(0, 0, 0, (byte) 21, (byte) 0); // Unit tet at level 21
        
        System.out.println("Parent coordinates (t8code):");
        var t8Coords = parent.coordinates();
        for (int i = 0; i < 4; i++) {
            System.out.printf("  v%d: (%d, %d, %d)\n", i, 
                t8Coords[i].x, t8Coords[i].y, t8Coords[i].z);
        }
        
        System.out.println("\nParent subdivision coordinates:");
        var subCoords = parent.subdivisionCoordinates();
        for (int i = 0; i < 4; i++) {
            System.out.printf("  v%d: (%d, %d, %d)\n", i, 
                subCoords[i].x, subCoords[i].y, subCoords[i].z);
        }
        
        // Check if they're the same
        boolean same = true;
        for (int i = 0; i < 4; i++) {
            if (t8Coords[i].x != subCoords[i].x || 
                t8Coords[i].y != subCoords[i].y || 
                t8Coords[i].z != subCoords[i].z) {
                same = false;
                break;
            }
        }
        
        System.out.println("\nCoordinate systems are " + (same ? "IDENTICAL" : "DIFFERENT"));
    }
}
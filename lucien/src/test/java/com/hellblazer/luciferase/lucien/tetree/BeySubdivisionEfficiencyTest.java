package com.hellblazer.luciferase.lucien.tetree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the efficient single-child methods in BeySubdivision.
 */
public class BeySubdivisionEfficiencyTest {
    
    @Test
    void testGetBeyChildMatchesSubdivide() {
        // Test for each parent type
        for (byte parentType = 0; parentType < 6; parentType++) {
            // Use coordinates aligned to level 5 grid (cell size 65536)
            Tet parent = new Tet(65536, 131072, 196608, (byte) 5, parentType);
            
            // Get all children using subdivide
            Tet[] allChildren = BeySubdivision.subdivide(parent);
            
            // For each Bey child index
            for (int beyIndex = 0; beyIndex < 8; beyIndex++) {
                // Get single child efficiently
                Tet efficientChild = BeySubdivision.getBeyChild(parent, beyIndex);
                
                // Find corresponding child from subdivide (need to convert from TM to Bey order)
                Tet subdivideChild = null;
                for (int i = 0; i < 8; i++) {
                    // Check if this TM-ordered child matches our Bey index
                    if (allChildren[i].equals(efficientChild)) {
                        subdivideChild = allChildren[i];
                        break;
                    }
                }
                
                assertNotNull(subdivideChild, 
                    String.format("Parent type %d, Bey child %d not found in subdivide results", 
                        parentType, beyIndex));
                
                // Verify they are identical
                assertEquals(subdivideChild.x(), efficientChild.x());
                assertEquals(subdivideChild.y(), efficientChild.y());
                assertEquals(subdivideChild.z(), efficientChild.z());
                assertEquals(subdivideChild.l(), efficientChild.l());
                assertEquals(subdivideChild.type(), efficientChild.type());
            }
        }
    }
    
    @Test
    void testGetTMChildMatchesSubdivide() {
        // Test for each parent type
        for (byte parentType = 0; parentType < 6; parentType++) {
            // Use coordinates aligned to level 7 grid (cell size 16384)
            Tet parent = new Tet(16384, 32768, 49152, (byte) 7, parentType);
            
            // Get all children using subdivide (returns in TM order)
            Tet[] tmChildren = BeySubdivision.subdivide(parent);
            
            // For each TM child index
            for (int tmIndex = 0; tmIndex < 8; tmIndex++) {
                // Get single child efficiently
                Tet efficientChild = BeySubdivision.getTMChild(parent, tmIndex);
                
                // Get child from subdivide array
                Tet subdivideChild = tmChildren[tmIndex];
                
                // Verify they are identical
                assertEquals(subdivideChild.x(), efficientChild.x(),
                    String.format("Parent type %d, TM child %d: x mismatch", parentType, tmIndex));
                assertEquals(subdivideChild.y(), efficientChild.y(),
                    String.format("Parent type %d, TM child %d: y mismatch", parentType, tmIndex));
                assertEquals(subdivideChild.z(), efficientChild.z(),
                    String.format("Parent type %d, TM child %d: z mismatch", parentType, tmIndex));
                assertEquals(subdivideChild.l(), efficientChild.l(),
                    String.format("Parent type %d, TM child %d: level mismatch", parentType, tmIndex));
                assertEquals(subdivideChild.type(), efficientChild.type(),
                    String.format("Parent type %d, TM child %d: type mismatch", parentType, tmIndex));
            }
        }
    }
    
    @Test
    void testInvalidIndices() {
        Tet parent = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        
        // Test invalid Bey indices
        assertThrows(IllegalArgumentException.class, () -> 
            BeySubdivision.getBeyChild(parent, -1));
        assertThrows(IllegalArgumentException.class, () -> 
            BeySubdivision.getBeyChild(parent, 8));
        
        // Test invalid TM indices
        assertThrows(IllegalArgumentException.class, () -> 
            BeySubdivision.getTMChild(parent, -1));
        assertThrows(IllegalArgumentException.class, () -> 
            BeySubdivision.getTMChild(parent, 8));
    }
    
    @Test
    void testEfficiencyBenefit() {
        // This test demonstrates the efficiency benefit by timing operations
        Tet parent = new Tet(0, 0, 0, (byte) 10, (byte) 0);
        int iterations = 10000;
        
        // Time full subdivision
        long startFull = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Tet[] children = BeySubdivision.subdivide(parent);
            Tet child = children[3]; // Get just one child
        }
        long timeFull = System.nanoTime() - startFull;
        
        // Time efficient single child
        long startEfficient = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Tet child = BeySubdivision.getTMChild(parent, 3);
        }
        long timeEfficient = System.nanoTime() - startEfficient;
        
        // Print results
        System.out.printf("Full subdivision time: %d ns (%d ns per iteration)\n", 
            timeFull, timeFull / iterations);
        System.out.printf("Efficient child time: %d ns (%d ns per iteration)\n", 
            timeEfficient, timeEfficient / iterations);
        System.out.printf("Speedup: %.2fx\n", (double) timeFull / timeEfficient);
        
        // Efficient method should be significantly faster
        assertTrue(timeEfficient < timeFull, 
            "Efficient method should be faster than full subdivision");
    }
    
    @Test
    void testGetMortonChild() {
        // Test that getMortonChild produces same results as Tet.child()
        for (byte parentType = 0; parentType < 6; parentType++) {
            Tet parent = new Tet(0, 0, 0, (byte) 10, parentType);
            
            for (int mortonIndex = 0; mortonIndex < 8; mortonIndex++) {
                // Get child using Tet's built-in method
                Tet tetChild = parent.child(mortonIndex);
                
                // Get child using BeySubdivision efficient method
                Tet beyChild = BeySubdivision.getMortonChild(parent, mortonIndex);
                
                // Verify they are identical
                assertEquals(tetChild.x(), beyChild.x(),
                    String.format("Parent type %d, Morton child %d: x mismatch", parentType, mortonIndex));
                assertEquals(tetChild.y(), beyChild.y(),
                    String.format("Parent type %d, Morton child %d: y mismatch", parentType, mortonIndex));
                assertEquals(tetChild.z(), beyChild.z(),
                    String.format("Parent type %d, Morton child %d: z mismatch", parentType, mortonIndex));
                assertEquals(tetChild.l(), beyChild.l(),
                    String.format("Parent type %d, Morton child %d: level mismatch", parentType, mortonIndex));
                assertEquals(tetChild.type(), beyChild.type(),
                    String.format("Parent type %d, Morton child %d: type mismatch", parentType, mortonIndex));
            }
        }
    }
}
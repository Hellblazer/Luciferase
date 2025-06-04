package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

public class ValidCoordinateTest {
    
    @Test
    public void testValidCoordinates() {
        System.out.println("=== UNDERSTANDING VALID COORDINATE SPACES ===");
        
        // For each level, show the valid coordinate range
        for (byte level = 0; level <= 5; level++) {
            int stepSize = Constants.lengthAtLevel(level);
            System.out.println("\nLevel " + level + ":");
            System.out.println("  Step size: " + stepSize);
            System.out.println("  Max extent: " + Constants.MAX_EXTENT);
            System.out.println("  Valid coordinates: 0, " + stepSize + ", " + (2 * stepSize) + ", ..., " + (Constants.MAX_EXTENT - stepSize));
            
            // Test a few valid coordinates at this level
            System.out.println("  Testing some coordinates:");
            
            // Test (0, 0, 0) at this level
            var tet1 = new Tet(0, 0, 0, level, (byte) 0);
            long index1 = tet1.index();
            var reconstructed1 = Tet.tetrahedron(index1);
            System.out.println("    (0, 0, 0) -> index " + index1 + " -> " + reconstructed1 + " (round-trip: " + tet1.equals(reconstructed1) + ")");
            
            // Test (stepSize, 0, 0) at this level
            if (stepSize < Constants.MAX_EXTENT) {
                var tet2 = new Tet(stepSize, 0, 0, level, (byte) 0);
                long index2 = tet2.index();
                var reconstructed2 = Tet.tetrahedron(index2);
                System.out.println("    (" + stepSize + ", 0, 0) -> index " + index2 + " -> " + reconstructed2 + " (round-trip: " + tet2.equals(reconstructed2) + ")");
            }
            
            // Test (0, 0, stepSize) at this level  
            if (stepSize < Constants.MAX_EXTENT) {
                var tet3 = new Tet(0, 0, stepSize, level, (byte) 0);
                long index3 = tet3.index();
                var reconstructed3 = Tet.tetrahedron(index3);
                System.out.println("    (0, 0, " + stepSize + ") -> index " + index3 + " -> " + reconstructed3 + " (round-trip: " + tet3.equals(reconstructed3) + ")");
            }
        }
        
        // Test what happens with boundary coordinates
        System.out.println("\n=== BOUNDARY COORDINATE ANALYSIS ===");
        System.out.println("MAX_EXTENT = " + Constants.MAX_EXTENT);
        
        // Test the coordinate that was failing
        int problemCoord = 2097152;
        System.out.println("Problem coordinate: " + problemCoord);
        System.out.println("Equals MAX_EXTENT: " + (problemCoord == Constants.MAX_EXTENT));
        
        // For level 0, step size is also 2097152, so valid coordinates are 0, 2097152, 4194304, ...
        // But 2097152 == MAX_EXTENT, so it's at the boundary
        byte level = 0;
        int stepSize = Constants.lengthAtLevel(level);
        System.out.println("Level 0 step size: " + stepSize);
        System.out.println("Coordinate 2097152 is " + (problemCoord / stepSize) + " * stepSize");
        
        // The issue might be that coordinates at the boundary are invalid
        // Try coordinates within the valid range
        var validTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        System.out.println("Valid level 0 tet: " + validTet);
        System.out.println("Index: " + validTet.index());
        var reconstructedValid = Tet.tetrahedron(validTet.index());
        System.out.println("Reconstructed: " + reconstructedValid);
        System.out.println("Round-trip: " + validTet.equals(reconstructedValid));
    }
}
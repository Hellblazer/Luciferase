/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.mesh.explorer;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;

/**
 * Test to demonstrate the TM-index mismatch between Tet.childTM() and proper TM-to-Bey ordering.
 *
 * @author hal.hildebrand
 */
public class TMIndexComparisonTest {
    
    // The correct TM-to-Bey permutation table from TetrahedralSubdivision
    private static final int[][] TM_TO_BEY_PERMUTATION = {
        { 0, 1, 4, 7, 2, 3, 6, 5 }, // Parent type 0
        { 0, 1, 5, 7, 2, 3, 6, 4 }, // Parent type 1
        { 0, 3, 4, 7, 1, 2, 6, 5 }, // Parent type 2
        { 0, 1, 6, 7, 2, 3, 4, 5 }, // Parent type 3
        { 0, 3, 5, 7, 1, 2, 4, 6 }, // Parent type 4
        { 0, 3, 6, 7, 2, 1, 4, 5 }  // Parent type 5
    };
    
    @Test
    public void demonstrateTMIndexMismatch() {
        System.out.println("=== DEMONSTRATING TM-INDEX MISMATCH ===\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        System.out.println("Parent: " + parent);
        System.out.println("Parent type: " + parent.type());
        System.out.println();
        
        System.out.println("Comparing childTM() behavior with correct TM-to-Bey mapping:\n");
        
        System.out.println("TM | TYPE_TO_TYPE_OF_CHILD_MORTON | Tet.childTM() returns | Correct Bey index | Should return");
        System.out.println("---|------------------------------|----------------------|-------------------|---------------");
        
        for (int tm = 0; tm < 8; tm++) {
            // What TYPE_TO_TYPE_OF_CHILD_MORTON gives us (a TYPE, not an index!)
            byte childType = Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[parent.type()][tm];
            
            // What childTM currently returns (wrongly using type as index)
            Tet wrongChild = parent.childTM((byte) tm);
            
            // What the correct Bey index should be
            int correctBeyIndex = TM_TO_BEY_PERMUTATION[parent.type()][tm];
            
            // What we should actually get
            Tet correctChild = parent.child(correctBeyIndex);
            
            System.out.printf("%2d | %28d | child(%d) = %s | %17d | child(%d) = %s%n",
                tm, 
                childType,
                childType, formatTet(wrongChild),
                correctBeyIndex,
                correctBeyIndex, formatTet(correctChild)
            );
        }
        
        System.out.println("\nPROBLEM: childTM() is using TYPE (0-5) as child INDEX (0-7)!");
        System.out.println("For example, TM child 3 should be Bey child 7, but childTM(3) returns child(5)");
        System.out.println("\nThis explains why childTM() has fewer containment violations - it's returning");
        System.out.println("a random subset of children, not the actual TM-ordered children!");
    }
    
    @Test
    public void verifyCorrectTMOrdering() {
        System.out.println("\n=== VERIFYING CORRECT TM ORDERING ===\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        System.out.println("Using corrected TM child access:\n");
        
        for (int tm = 0; tm < 8; tm++) {
            int beyIndex = TM_TO_BEY_PERMUTATION[parent.type()][tm];
            Tet child = parent.child(beyIndex);
            
            System.out.printf("TM child %d = Bey child %d: %s%n", tm, beyIndex, child);
        }
        
        System.out.println("\nNote: The 'correct' TM ordering follows the pattern from the thesis,");
        System.out.println("where TM-to-Bey permutation properly maps space-filling curve order.");
    }
    
    @Test
    public void showWhyChildTMHasFewViolations() {
        System.out.println("\n=== WHY CHILDTM() HAS FEWER VIOLATIONS ===\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        System.out.println("childTM() appears to have fewer violations because it's not actually");
        System.out.println("returning all 8 children - it's returning a subset due to the bug!\n");
        
        System.out.println("Children returned by childTM():");
        boolean[] childrenReturned = new boolean[8];
        
        for (int tm = 0; tm < 8; tm++) {
            byte childType = Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[parent.type()][tm];
            if (childType < 8) {
                childrenReturned[childType] = true;
                System.out.printf("  TM %d -> child(%d)%n", tm, childType);
            }
        }
        
        System.out.println("\nActual children covered:");
        int count = 0;
        for (int i = 0; i < 8; i++) {
            if (childrenReturned[i]) {
                System.out.println("  Child " + i + ": ✓");
                count++;
            } else {
                System.out.println("  Child " + i + ": ✗ MISSING");
            }
        }
        
        System.out.printf("\nOnly %d/8 unique children are returned!%n", count);
        System.out.println("This is why it appears to have fewer violations - it's testing fewer children!");
    }
    
    private String formatTet(Tet tet) {
        Point3i[] coords = tet.coordinates();
        return String.format("anchor=(%d,%d,%d)", coords[0].x, coords[0].y, coords[0].z);
    }
}
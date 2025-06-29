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

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import org.junit.jupiter.api.Test;

/**
 * Clarifies the confusion about TM vs Bey ordering in tetrahedral subdivision.
 *
 * @author hal.hildebrand
 */
public class TMBeyOrderingClarificationTest {
    
    @Test
    public void clarifyOrdering() {
        System.out.println("=== CLARIFYING TM vs BEY ORDERING ===\n");
        
        System.out.println("The confusion comes from multiple ordering systems:\n");
        
        System.out.println("1. BEY ORDER: The 'natural' geometric subdivision order");
        System.out.println("   - Children 0-3: Corner children (at original vertices)");
        System.out.println("   - Children 4-7: Octahedral children (at edge midpoints)");
        System.out.println("   - This is what subdivideTetrahedron() returns");
        System.out.println();
        
        System.out.println("2. MORTON ORDER: What Tet.child(i) expects as input");
        System.out.println("   - The child() method takes a Morton index (0-7)");
        System.out.println("   - Internally converts Morton → Bey using getBeyChildId()");
        System.out.println("   - This is the 'default' ordering for the Tet class");
        System.out.println();
        
        System.out.println("3. TM ORDER: A third ordering for space-filling curves");
        System.out.println("   - Different from both Bey and Morton orderings");
        System.out.println("   - Requires TM → Bey conversion to access the right child");
        System.out.println();
        
        System.out.println("WHY THE NAME 'TM_TO_BEY_PERMUTATION' IS CORRECT:");
        System.out.println("- When you want TM child #3, you need to know which Bey child that corresponds to");
        System.out.println("- The table maps: TM index → Bey index");
        System.out.println("- Since subdivide() returns children in Bey order, we use this to pick the right one");
        System.out.println();
        
        demonstrateOrderings();
    }
    
    @Test
    public void demonstrateOrderings() {
        System.out.println("=== DEMONSTRATING THE THREE ORDERINGS ===\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        // Show Morton to Bey conversion that happens inside child()
        System.out.println("Morton → Bey conversion (inside Tet.child()):");
        System.out.println("Morton | Bey ID | Description");
        System.out.println("-------|--------|-------------");
        for (int morton = 0; morton < 8; morton++) {
            byte beyId = TetreeConnectivity.getBeyChildId(parent.type(), morton);
            String desc = beyId < 4 ? "Corner child " + beyId : "Octahedral child " + (beyId - 4);
            System.out.printf("%6d | %6d | %s%n", morton, beyId, desc);
        }
        System.out.println();
        
        // The TM to Bey permutation from TetrahedralSubdivision
        int[][] TM_TO_BEY = {
            { 0, 1, 4, 7, 2, 3, 6, 5 }, // Type 0
            { 0, 1, 5, 7, 2, 3, 6, 4 }, // Type 1
            { 0, 3, 4, 7, 1, 2, 6, 5 }, // Type 2
            { 0, 1, 6, 7, 2, 3, 4, 5 }, // Type 3
            { 0, 3, 5, 7, 1, 2, 4, 6 }, // Type 4
            { 0, 3, 6, 7, 2, 1, 4, 5 }  // Type 5
        };
        
        System.out.println("TM → Bey conversion (from thesis/TetrahedralSubdivision):");
        System.out.println("TM | Bey | Description");
        System.out.println("---|-----|-------------");
        for (int tm = 0; tm < 8; tm++) {
            int beyIndex = TM_TO_BEY[parent.type()][tm];
            String desc = beyIndex < 4 ? "Corner child " + beyIndex : "Octahedral child " + (beyIndex - 4);
            System.out.printf("%2d | %3d | %s%n", tm, beyIndex, desc);
        }
        System.out.println();
        
        System.out.println("SUMMARY:");
        System.out.println("- Bey order: Natural geometric subdivision (what subdivide() returns)");
        System.out.println("- Morton order: What Tet.child(i) expects as input");  
        System.out.println("- TM order: Space-filling curve order from the thesis");
        System.out.println();
        System.out.println("To get TM child #i:");
        System.out.println("1. Look up beyIndex = TM_TO_BEY[parentType][i]");
        System.out.println("2. Get beyChildren[beyIndex] from the subdivision");
        System.out.println();
        System.out.println("The current Tet.childTM() is broken because it uses TYPE_TO_TYPE_OF_CHILD_MORTON");
        System.out.println("which gives child TYPES in TM order, not child INDICES!");
    }
}
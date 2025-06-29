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

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * Clarifies what each Tet child method corresponds to in terms of ordering.
 *
 * @author hal.hildebrand
 */
public class ChildMethodsClarificationTest {
    
    @Test
    public void clarifyChildMethods() {
        System.out.println("=== CLARIFICATION: Tet CHILD METHODS AND THEIR ORDERINGS ===\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        System.out.println("We have three child methods on Tet:\n");
        
        System.out.println("1. child(int i) - MORTON ORDER");
        System.out.println("   - Takes Morton index (0-7) as input");
        System.out.println("   - Internally converts Morton → Bey using getBeyChildId()");
        System.out.println("   - This is the 'default' child accessor");
        System.out.println("   - Morton order is based on bit interleaving, not optimal for tetrahedra");
        System.out.println();
        
        System.out.println("2. child(int i) - ALSO MORTON ORDER!");
        System.out.println("   - Despite the name, this ALSO takes Morton index as input");
        System.out.println("   - Uses SIMPLEX_STANDARD instead of SIMPLEX for vertex lookup");
        System.out.println("   - Still goes through the same Morton → Bey conversion");
        System.out.println("   - The 'standard' refers to vertex ordering, NOT child ordering");
        System.out.println();
        
        System.out.println("3. childTM(byte i) - SUPPOSED TO BE TM ORDER (but broken!)");
        System.out.println("   - Should take TM index (0-7) and return TM-ordered child");
        System.out.println("   - Currently BROKEN - uses TYPE_TO_TYPE_OF_CHILD_MORTON incorrectly");
        System.out.println("   - Should use TM → Bey conversion for true space-filling curve");
        System.out.println();
        
        demonstrateOrderings(parent);
    }
    
    @Test
    public void demonstrateOrderings(Tet parent) {
        System.out.println("=== DEMONSTRATION WITH ACTUAL CALLS ===\n");
        
        System.out.println("Parent: " + parent + "\n");
        
        // Show what each method returns
        System.out.println("child(i) - Morton Order:");
        System.out.println("i | Child coordinates (anchor point) | Bey ID | Type");
        System.out.println("--|----------------------------------|--------|------");
        for (int i = 0; i < 8; i++) {
            Tet child = parent.child(i);
            byte beyId = TetreeConnectivity.getBeyChildId(parent.type(), i);
            Point3i anchor = child.coordinates()[0];
            System.out.printf("%d | (%4d, %4d, %4d)                | %6d | %4d%n", 
                i, anchor.x, anchor.y, anchor.z, beyId, child.type());
        }
        System.out.println();
        
        System.out.println("child(i) - Also Morton Order (different vertex arrangement):");
        System.out.println("i | Child coordinates (anchor point) | Bey ID | Type");
        System.out.println("--|----------------------------------|--------|------");
        for (int i = 0; i < 8; i++) {
            Tet child = parent.child(i);
            byte beyId = TetreeConnectivity.getBeyChildId(parent.type(), i);
            Point3i anchor = child.coordinates()[0];
            System.out.printf("%d | (%4d, %4d, %4d)                | %6d | %4d%n", 
                i, anchor.x, anchor.y, anchor.z, beyId, child.type());
        }
        System.out.println();
        
        System.out.println("childTM(i) - BROKEN (returns wrong children):");
        System.out.println("i | What it returns | What it SHOULD return (TM order)");
        System.out.println("--|-----------------|----------------------------------");
        
        // Correct TM to Bey mapping
        int[][] TM_TO_BEY = {
            { 0, 1, 4, 7, 2, 3, 6, 5 }, // Type 0
            { 0, 1, 5, 7, 2, 3, 6, 4 }, // Type 1
            { 0, 3, 4, 7, 1, 2, 6, 5 }, // Type 2
            { 0, 1, 6, 7, 2, 3, 4, 5 }, // Type 3
            { 0, 3, 5, 7, 1, 2, 4, 6 }, // Type 4
            { 0, 3, 6, 7, 2, 1, 4, 5 }  // Type 5
        };
        
        for (int i = 0; i < 8; i++) {
            Tet wrongChild = parent.childTM((byte) i);
            
            // What it should return
            int correctBeyIndex = TM_TO_BEY[parent.type()][i];
            int correctMortonIndex = beyToMorton(parent.type(), correctBeyIndex);
            Tet correctChild = parent.child(correctMortonIndex);
            
            Point3i wrongAnchor = wrongChild.coordinates()[0];
            Point3i correctAnchor = correctChild.coordinates()[0];
            
            System.out.printf("%d | child(%d) → (%4d,%4d,%4d) | Bey %d → Morton %d → (%4d,%4d,%4d)%n",
                i, 
                com.hellblazer.luciferase.lucien.Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[parent.type()][i],
                wrongAnchor.x, wrongAnchor.y, wrongAnchor.z,
                correctBeyIndex, correctMortonIndex,
                correctAnchor.x, correctAnchor.y, correctAnchor.z);
        }
        
        System.out.println("\nSUMMARY:");
        System.out.println("- child() and child() BOTH use Morton ordering");
        System.out.println("- childTM() is supposed to use TM ordering but is broken");
        System.out.println("- None of them directly use Bey ordering (though it's used internally)");
        System.out.println("- For proper geometric subdivision, use Bey order directly");
        System.out.println("- For space-filling curve, fix childTM() to use proper TM order");
    }
    
    private int beyToMorton(byte parentType, int beyIndex) {
        for (int morton = 0; morton < 8; morton++) {
            if (TetreeConnectivity.getBeyChildId(parentType, morton) == beyIndex) {
                return morton;
            }
        }
        throw new IllegalArgumentException("Invalid Bey index: " + beyIndex);
    }
}
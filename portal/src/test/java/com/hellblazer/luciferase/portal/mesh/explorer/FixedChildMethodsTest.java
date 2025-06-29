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

import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the fixed child methods after refactoring.
 *
 * @author hal.hildebrand
 */
public class FixedChildMethodsTest {
    
    // The correct TM-to-Bey permutation table
    private static final int[][] TM_TO_BEY_PERMUTATION = {
        { 0, 1, 4, 7, 2, 3, 6, 5 }, // Parent type 0
        { 0, 1, 5, 7, 2, 3, 6, 4 }, // Parent type 1  
        { 0, 3, 4, 7, 1, 2, 6, 5 }, // Parent type 2
        { 0, 1, 6, 7, 2, 3, 4, 5 }, // Parent type 3
        { 0, 3, 5, 7, 1, 2, 4, 6 }, // Parent type 4
        { 0, 3, 6, 7, 2, 1, 4, 5 }  // Parent type 5
    };
    
    @Test
    public void testChildMethod() {
        System.out.println("=== TESTING child() METHOD (Morton order) ===\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        // Test that child() works correctly with Morton ordering
        for (int i = 0; i < 8; i++) {
            Tet child = parent.child(i);
            assertNotNull(child, "Child " + i + " should not be null");
            assertEquals(parent.l() + 1, child.l(), "Child should be one level deeper");
            
            // Verify it's using the correct Morton→Bey conversion
            byte beyId = TetreeConnectivity.getBeyChildId(parent.type(), i);
            byte expectedType = TetreeConnectivity.getChildType(parent.type(), beyId);
            assertEquals(expectedType, child.type(), "Child " + i + " should have correct type");
        }
        
        System.out.println("✅ child() working correctly with Morton order");
    }
    
    
    @Test
    public void testFixedChildTM() {
        System.out.println("\n=== TESTING FIXED childTM() METHOD ===\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        System.out.println("Parent: " + parent);
        System.out.println("\nTM Order → Actual Child:");
        System.out.println("TM | Expected Bey | Actual Result");
        System.out.println("---|--------------|---------------");
        
        for (int tm = 0; tm < 8; tm++) {
            Tet tmChild = parent.childTM(tm);
            
            // What we expect based on the permutation table
            int expectedBeyIndex = TM_TO_BEY_PERMUTATION[parent.type()][tm];
            
            // Verify we get the correct child
            int actualMortonIndex = findMortonIndexForChild(parent, tmChild);
            int actualBeyIndex = TetreeConnectivity.getBeyChildId(parent.type(), actualMortonIndex);
            
            System.out.printf("%2d | %12d | Bey %d (Morton %d)%n", 
                tm, expectedBeyIndex, actualBeyIndex, actualMortonIndex);
            
            assertEquals(expectedBeyIndex, actualBeyIndex, 
                "TM child " + tm + " should map to Bey child " + expectedBeyIndex);
        }
        
        System.out.println("\n✅ childTM() now correctly returns TM-ordered children!");
    }
    
    @Test
    public void testTMOrderLocality() {
        System.out.println("\n=== TESTING TM ORDER SPATIAL LOCALITY ===\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        // Get children in TM order
        Tet[] tmChildren = new Tet[8];
        for (int i = 0; i < 8; i++) {
            tmChildren[i] = parent.childTM(i);
        }
        
        // Calculate centroids and distances
        System.out.println("TM order path through space:");
        float totalDistance = 0;
        for (int i = 0; i < 8; i++) {
            Point3i[] vertices = tmChildren[i].coordinates();
            float cx = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
            float cy = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
            float cz = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;
            
            System.out.printf("TM %d: centroid = (%.1f, %.1f, %.1f)%n", i, cx, cy, cz);
            
            if (i > 0) {
                Point3i[] prevVertices = tmChildren[i-1].coordinates();
                float px = (prevVertices[0].x + prevVertices[1].x + prevVertices[2].x + prevVertices[3].x) / 4.0f;
                float py = (prevVertices[0].y + prevVertices[1].y + prevVertices[2].y + prevVertices[3].y) / 4.0f;
                float pz = (prevVertices[0].z + prevVertices[1].z + prevVertices[2].z + prevVertices[3].z) / 4.0f;
                
                float dist = (float) Math.sqrt((cx-px)*(cx-px) + (cy-py)*(cy-py) + (cz-pz)*(cz-pz));
                totalDistance += dist;
            }
        }
        
        System.out.printf("\nAverage consecutive distance: %.2f%n", totalDistance / 7);
        System.out.println("(Lower values indicate better spatial locality)");
    }
    
    @Test
    public void testNoMoreChildStandard() {
        System.out.println("\n=== VERIFYING childStandard() IS REMOVED ===\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        // This should not compile if childStandard is properly removed
        try {
            // Use reflection to check if method exists
            parent.getClass().getMethod("childStandard", int.class);
            fail("childStandard method should have been removed!");
        } catch (NoSuchMethodException e) {
            System.out.println("✅ childStandard() method successfully removed");
        }
    }
    
    private int findMortonIndexForChild(Tet parent, Tet child) {
        // Find which Morton index produces this child
        for (int morton = 0; morton < 8; morton++) {
            Tet test = parent.child(morton);
            if (test.x() == child.x() && test.y() == child.y() && 
                test.z() == child.z() && test.type() == child.type()) {
                return morton;
            }
        }
        return -1;
    }
}
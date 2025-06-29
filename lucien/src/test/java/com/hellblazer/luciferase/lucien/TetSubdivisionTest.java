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
package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.octree.MortonKey;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test subdivision methods for both Tet and Octree
 *
 * @author hal.hildebrand
 */
public class TetSubdivisionTest {

    @Test
    public void testTetSubdivision() {
        // Create a root tetrahedron
        Tet root = new Tet(0, 0, 0, (byte)0, (byte)0);
        
        // Subdivide it
        Tet[] children = root.subdivide();
        
        // Verify we get 8 children
        assertEquals(8, children.length);
        
        // Verify all children are at level 1
        for (int i = 0; i < 8; i++) {
            assertEquals(1, children[i].l(), "Child " + i + " should be at level 1");
            assertNotNull(children[i]);
        }
        
        // Verify children have different types
        boolean[] typesFound = new boolean[6];
        for (Tet child : children) {
            assertTrue(child.type() >= 0 && child.type() <= 5, 
                      "Child type should be 0-5, got: " + child.type());
            typesFound[child.type()] = true;
        }
        
        // At least multiple types should be represented
        int typeCount = 0;
        for (boolean found : typesFound) {
            if (found) typeCount++;
        }
        assertTrue(typeCount > 1, "Should have multiple child types, found: " + typeCount);
    }
    
    @Test
    public void testOctreeHexahedronSubdivision() {
        // Create a root Morton key
        MortonKey root = MortonKey.getRoot();
        
        // Subdivide it
        MortonKey[] children = Octree.HexahedronSubdivision.subdivide(root);
        
        // Verify we get 8 children
        assertEquals(8, children.length);
        
        // Verify all children are at level 1
        for (int i = 0; i < 8; i++) {
            assertEquals(1, children[i].getLevel(), "Child " + i + " should be at level 1");
            assertNotNull(children[i]);
        }
        
        // Verify children have different Morton codes
        for (int i = 0; i < 8; i++) {
            for (int j = i + 1; j < 8; j++) {
                assertNotEquals(children[i].getMortonCode(), children[j].getMortonCode(),
                               "Children " + i + " and " + j + " should have different Morton codes");
            }
        }
    }
    
    @Test
    public void testOctreeGetChild() {
        MortonKey root = MortonKey.getRoot();
        
        // Test getting individual children
        for (int i = 0; i < 8; i++) {
            MortonKey child = Octree.HexahedronSubdivision.getChild(root, i);
            assertNotNull(child);
            assertEquals(1, child.getLevel());
        }
        
        // Verify getChild matches subdivide
        MortonKey[] allChildren = Octree.HexahedronSubdivision.subdivide(root);
        for (int i = 0; i < 8; i++) {
            MortonKey individual = Octree.HexahedronSubdivision.getChild(root, i);
            assertEquals(allChildren[i].getMortonCode(), individual.getMortonCode());
        }
    }
    
    @Test
    public void testMaxLevelSubdivision() {
        // Test that we can't subdivide at max level
        Tet maxLevelTet = new Tet(0, 0, 0, Constants.getMaxRefinementLevel(), (byte)0);
        assertThrows(IllegalStateException.class, maxLevelTet::subdivide);
        
        // Same for octree
        MortonKey maxLevelKey = new MortonKey(0, Constants.getMaxRefinementLevel());
        assertThrows(IllegalStateException.class, () -> Octree.HexahedronSubdivision.subdivide(maxLevelKey));
    }
}
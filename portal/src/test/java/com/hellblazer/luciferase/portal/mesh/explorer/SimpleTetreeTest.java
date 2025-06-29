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
import com.hellblazer.luciferase.lucien.Constants;
import javax.vecmath.Point3i;
import org.junit.jupiter.api.Test;

/**
 * Simple test to debug tetree visualization
 *
 * @author hal.hildebrand
 */
public class SimpleTetreeTest {
    
    @Test
    public void testRootTetCoordinates() {
        Tet rootTet = new Tet(0, 0, 0, (byte)0, (byte)0);
        System.out.println("Root tet: " + rootTet);
        System.out.println("Root tet length: " + rootTet.length());
        System.out.println("Max extent: " + Constants.MAX_EXTENT);
        
        Point3i[] coords = rootTet.coordinates();
        System.out.println("\nCoordinates:");
        for (int i = 0; i < 4; i++) {
            System.out.println("  v" + i + ": " + coords[i]);
        }
        
        // Try a smaller tet
        System.out.println("\nLevel 10 tet:");
        Tet smallTet = new Tet(0, 0, 0, (byte)10, (byte)0);
        System.out.println("Small tet: " + smallTet);
        System.out.println("Small tet length: " + smallTet.length());
        
        Point3i[] smallCoords = smallTet.coordinates();
        System.out.println("\nSmall Coordinates:");
        for (int i = 0; i < 4; i++) {
            System.out.println("  v" + i + ": " + smallCoords[i]);
        }
        
        // Test subdivision
        System.out.println("\nSubdividing root tet:");
        Tet[] children = rootTet.subdivide();
        for (int i = 0; i < children.length; i++) {
            System.out.println("  Child " + i + ": " + children[i]);
        }
    }
}
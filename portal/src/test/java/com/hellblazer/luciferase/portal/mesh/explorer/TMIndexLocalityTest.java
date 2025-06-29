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
 * Demonstrates why TM-index is the true space-filling curve while naive Morton ordering doesn't preserve locality.
 *
 * @author hal.hildebrand
 */
public class TMIndexLocalityTest {
    
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
    public void demonstrateSFCLocality() {
        System.out.println("=== TM-INDEX: THE TRUE SPACE-FILLING CURVE ===\n");
        
        System.out.println("KEY INSIGHT: The TM-index ordering creates a true space-filling curve");
        System.out.println("that preserves spatial locality, while naive Morton ordering does not.\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        System.out.println("Parent: " + parent);
        System.out.println("Parent centroid: " + calculateCentroid(parent));
        System.out.println("\nComparing spatial locality of different orderings:\n");
        
        // Get children in different orderings
        Tet[] mortonChildren = new Tet[8];
        Tet[] tmChildren = new Tet[8];
        Tet[] beyChildren = new Tet[8];
        
        // Morton order (what child() gives us)
        for (int i = 0; i < 8; i++) {
            mortonChildren[i] = parent.child(i);
        }
        
        // Bey order (geometric subdivision)
        for (int i = 0; i < 8; i++) {
            int mortonIndex = getBeyToMorton(parent.type(), i);
            beyChildren[i] = parent.child(mortonIndex);
        }
        
        // TM order (true SFC)
        for (int i = 0; i < 8; i++) {
            int beyIndex = TM_TO_BEY_PERMUTATION[parent.type()][i];
            int mortonIndex = getBeyToMorton(parent.type(), beyIndex);
            tmChildren[i] = parent.child(mortonIndex);
        }
        
        // Analyze locality
        System.out.println("MORTON ORDER (naive bit interleaving):");
        analyzeLocality(mortonChildren, "Morton");
        
        System.out.println("\nBEY ORDER (geometric subdivision):");
        analyzeLocality(beyChildren, "Bey");
        
        System.out.println("\nTM ORDER (true space-filling curve):");
        analyzeLocality(tmChildren, "TM");
        
        System.out.println("\nWHY TM-INDEX IS SUPERIOR:");
        System.out.println("1. TM-index creates a continuous path through space");
        System.out.println("2. Adjacent indices in TM order are spatially adjacent");
        System.out.println("3. This preserves locality for cache efficiency and spatial queries");
        System.out.println("4. Morton order has 'jumps' that break spatial locality");
        
        // Show the path
        System.out.println("\nTM-INDEX PATH THROUGH SPACE:");
        for (int i = 0; i < 8; i++) {
            Point3f centroid = calculateCentroid(tmChildren[i]);
            System.out.printf("TM %d: centroid = (%.1f, %.1f, %.1f)", i, centroid.x, centroid.y, centroid.z);
            
            if (i < 7) {
                Point3f nextCentroid = calculateCentroid(tmChildren[i + 1]);
                float distance = distance(centroid, nextCentroid);
                System.out.printf(" → TM %d: distance = %.1f", i + 1, distance);
            }
            System.out.println();
        }
    }
    
    @Test
    public void showWhyTMIndexMatters() {
        System.out.println("\n=== WHY TM-INDEX MATTERS FOR TETRAHEDRAL SFC ===\n");
        
        System.out.println("The tetrahedral space-filling curve (Tet SFC) needs to maintain");
        System.out.println("spatial locality across the irregular tetrahedral decomposition.\n");
        
        System.out.println("MORTON ORDER PROBLEMS:");
        System.out.println("1. Designed for regular cubic grids");
        System.out.println("2. Bit interleaving assumes axis-aligned subdivisions");
        System.out.println("3. Doesn't account for tetrahedral geometry");
        System.out.println("4. Creates discontinuities in the space-filling curve\n");
        
        System.out.println("TM-INDEX ADVANTAGES:");
        System.out.println("1. Specifically designed for tetrahedral decomposition");
        System.out.println("2. Maintains continuous path through irregular geometry");
        System.out.println("3. Preserves parent-child locality relationships");
        System.out.println("4. Enables efficient spatial indexing and queries\n");
        
        System.out.println("This is why the thesis uses TM-index for the actual SFC,");
        System.out.println("and why childTM() should return children in TM order!");
    }
    
    private void analyzeLocality(Tet[] children, String orderName) {
        float totalDistance = 0;
        float maxJump = 0;
        
        for (int i = 0; i < 7; i++) {
            Point3f c1 = calculateCentroid(children[i]);
            Point3f c2 = calculateCentroid(children[i + 1]);
            float dist = distance(c1, c2);
            totalDistance += dist;
            maxJump = Math.max(maxJump, dist);
        }
        
        float avgDistance = totalDistance / 7;
        System.out.printf("  Average consecutive distance: %.2f\n", avgDistance);
        System.out.printf("  Maximum jump: %.2f\n", maxJump);
        System.out.printf("  Locality score: %.2f (lower is better)\n", maxJump / avgDistance);
    }
    
    private Point3f calculateCentroid(Tet tet) {
        Point3i[] vertices = tet.coordinates();
        float x = 0, y = 0, z = 0;
        for (Point3i v : vertices) {
            x += v.x;
            y += v.y;
            z += v.z;
        }
        return new Point3f(x / 4, y / 4, z / 4);
    }
    
    private float distance(Point3f p1, Point3f p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        float dz = p1.z - p2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    
    // Helper to convert Bey index to Morton index
    private int getBeyToMorton(byte parentType, int beyIndex) {
        // This is the inverse of getBeyChildId
        for (int morton = 0; morton < 8; morton++) {
            if (TetreeConnectivity.getBeyChildId(parentType, morton) == beyIndex) {
                return morton;
            }
        }
        throw new IllegalArgumentException("Invalid Bey index: " + beyIndex);
    }
}
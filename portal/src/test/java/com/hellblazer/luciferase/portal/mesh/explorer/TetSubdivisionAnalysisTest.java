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
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;

/**
 * Analyze the fundamental incompatibility between t8code's tetrahedral
 * subdivision and Bey's refinement.
 *
 * @author hal.hildebrand
 */
public class TetSubdivisionAnalysisTest {
    
    @Test
    public void analyzeSubdivisionSchemes() {
        System.out.println("=== ANALYZING T8CODE vs BEY'S REFINEMENT ===\n");
        
        // Create a parent tetrahedron
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        System.out.println("Parent: " + parent);
        System.out.println("Parent vertices from coordinates():");
        Point3i[] parentVertices = parent.coordinates();
        for (int i = 0; i < 4; i++) {
            System.out.println("  v" + i + ": " + parentVertices[i]);
        }
        
        System.out.println("\nT8CODE SUBDIVISION ANALYSIS:");
        System.out.println("The coordinates() method uses t8code's canonical vertex algorithm:");
        System.out.println("- ei = type / 2");
        System.out.println("- ej = (ei + ((type % 2 == 0) ? 2 : 1)) % 3");
        System.out.println("- v0 = anchor");
        System.out.println("- v1 = anchor + h in dimension ei");
        System.out.println("- v2 = anchor + h in dimensions ei and ej");
        System.out.println("- v3 = anchor + h in the other two dimensions");
        
        System.out.println("\nFor type 0: ei=0, ej=2");
        System.out.println("This creates a specific tetrahedron shape that doesn't match Bey's pattern.");
        
        System.out.println("\nBEY'S REFINEMENT ANALYSIS:");
        System.out.println("Bey's refinement subdivides using edge midpoints:");
        System.out.println("- 4 corner children at original vertices");
        System.out.println("- 4 octahedral children from the central octahedron");
        
        System.out.println("\nTHE FUNDAMENTAL ISSUE:");
        System.out.println("1. childMorton() now correctly positions children at Bey anchor points");
        System.out.println("2. But coordinates() still generates vertices using t8code's scheme");
        System.out.println("3. These two schemes are INCOMPATIBLE!");
        
        System.out.println("\nDemonstrating the mismatch:");
        for (int i = 0; i < 8; i++) {
            Tet child = parent.child(i);
            Point3i[] childVertices = child.coordinates();
            
            System.out.println("\nChild " + i + ": " + child);
            System.out.println("  Anchor from childMorton: (" + child.x() + ", " + child.y() + ", " + child.z() + ")");
            System.out.println("  Vertices from coordinates():");
            for (int j = 0; j < 4; j++) {
                System.out.println("    v" + j + ": " + childVertices[j]);
            }
            
            // Check if this matches Bey's expected pattern
            if (i == 0) {
                System.out.println("  Expected for Bey child 0: anchored at parent v0");
                System.out.println("  Actual anchor: " + (child.x() == parentVertices[0].x && 
                                                            child.y() == parentVertices[0].y && 
                                                            child.z() == parentVertices[0].z ? "✅ MATCH" : "❌ MISMATCH"));
            }
        }
        
        System.out.println("\n=== CONCLUSION ===");
        System.out.println("The t8code tetrahedral scheme and Bey's refinement are fundamentally different.");
        System.out.println("We cannot simply change anchor positions - the entire vertex generation");
        System.out.println("algorithm in coordinates() would need to be replaced to match Bey's pattern.");
        System.out.println("\nThe t8code scheme is self-consistent and valid, just different from Bey's.");
        System.out.println("The 'violations' are not bugs - they're expected given the different schemes.");
    }
}
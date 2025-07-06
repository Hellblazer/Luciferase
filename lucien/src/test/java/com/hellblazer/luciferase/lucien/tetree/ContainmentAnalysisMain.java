package com.hellblazer.luciferase.lucien.tetree;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * Analysis of the containment vs tm-index issue.
 */
public class ContainmentAnalysisMain {
    
    public static void main(String[] args) {
        System.out.println("=== TM-Index and Containment Analysis ===\n");
        
        // Key insight: The tm-index encodes:
        // 1. Anchor coordinates (x, y, z)
        // 2. Level
        // 3. Type hierarchy (path from root)
        //
        // It does NOT encode vertex positions!
        
        System.out.println("1. Vertex Differences Between Coordinate Systems:\n");
        
        // Show how V3 differs between t8code and subdivision
        for (byte type = 0; type <= 5; type++) {
            Tet tet = new Tet(0, 0, 0, (byte) 3, type);
            Point3i[] t8Coords = tet.coordinates();
            Point3i[] subCoords = tet.subdivisionCoordinates();
            
            System.out.println("Type " + type + ":");
            System.out.println("  t8code V3: " + t8Coords[3]);
            System.out.println("  subdiv V3: " + subCoords[3]);
            System.out.println("  Difference: " + (!t8Coords[3].equals(subCoords[3]) ? "DIFFERENT" : "same"));
        }
        
        System.out.println("\n2. TM-Index Encoding Independence:\n");
        
        // Show that tm-index depends only on anchor, level, and type
        Tet tet = new Tet(64, 32, 96, (byte) 4, (byte) 3);
        TetreeKey<?> tmIndex = tet.tmIndex();
        
        System.out.println("Tet: anchor=(" + tet.x + "," + tet.y + "," + tet.z + 
                         "), level=" + tet.l + ", type=" + tet.type);
        System.out.println("TM-Index: " + tmIndex);
        
        // Decode to verify
        Tet decoded = Tet.tetrahedron(tmIndex);
        System.out.println("Decoded: anchor=(" + decoded.x + "," + decoded.y + "," + decoded.z + 
                         "), level=" + decoded.l + ", type=" + decoded.type);
        
        System.out.println("\n3. Impact Analysis:\n");
        
        System.out.println("CRITICAL INSIGHTS:");
        System.out.println("- The tm-index encodes the TYPE, not the vertex positions");
        System.out.println("- Changing contains() to use subdivision coordinates would NOT affect tm-index");
        System.out.println("- The tm-index would still correctly encode the tetrahedral hierarchy");
        System.out.println();
        
        System.out.println("HOWEVER:");
        System.out.println("- locate() uses contains() to find which tetrahedron contains a point");
        System.out.println("- If contains() uses different vertices, locate() might return a different type");
        System.out.println("- This could break the fundamental tetrahedral decomposition structure");
        System.out.println();
        
        System.out.println("EXAMPLE PROBLEM:");
        System.out.println("- Point P is in tetrahedron type 3 using t8code vertices");
        System.out.println("- Same point P might be in type 5 using subdivision vertices");
        System.out.println("- locate(P) would return different types!");
        System.out.println("- This breaks the space-filling curve property");
        
        System.out.println("\n4. Conclusion:\n");
        System.out.println("We CANNOT simply change contains() to use subdivision coordinates because:");
        System.out.println("1. It would make locate() inconsistent with the t8code tetrahedral decomposition");
        System.out.println("2. The space-filling curve relies on consistent type assignment");
        System.out.println("3. Different contains() would mean different spatial partitioning");
        System.out.println();
        System.out.println("The subdivision coordinates are ONLY for geometric subdivision operations.");
        System.out.println("The t8code coordinates define the actual tetrahedral space decomposition.");
    }
}
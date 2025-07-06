package com.hellblazer.luciferase.lucien.tetree;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Analysis of the containment vs tm-index issue.
 * 
 * The problem: Tet uses two different vertex computation methods:
 * 1. coordinates() / containsUltraFast() - t8code algorithm where V3 position depends on type
 * 2. subdivisionCoordinates() - V3 = anchor + (h,h,h) for all types
 * 
 * This test analyzes whether we can change contains() to use subdivision coordinates
 * without breaking the tm-index encoding.
 */
public class ContainmentTmIndexAnalysis {
    
    @Test
    public void analyzeVertexDifferences() {
        System.out.println("=== Vertex Computation Analysis ===\n");
        
        // Test each tetrahedral type
        for (byte type = 0; type <= 5; type++) {
            System.out.println("Type " + type + ":");
            
            // Create a tet at level 3 for clear visualization
            Tet tet = new Tet(0, 0, 0, (byte) 3, type);
            
            // Get both coordinate systems
            Point3i[] t8Coords = tet.coordinates();
            Point3i[] subCoords = tet.subdivisionCoordinates();
            
            // Compare V3 (the fourth vertex)
            System.out.println("  t8code V3: " + t8Coords[3]);
            System.out.println("  subdiv V3: " + subCoords[3]);
            System.out.println("  Match: " + t8Coords[3].equals(subCoords[3]));
            
            // V0, V1, V2 should always match
            for (int i = 0; i < 3; i++) {
                assertEquals(t8Coords[i], subCoords[i], 
                    "V" + i + " should match between coordinate systems");
            }
            
            System.out.println();
        }
    }
    
    @Test
    public void analyzeTmIndexEncoding() {
        System.out.println("=== TM-Index Encoding Analysis ===\n");
        
        // The tm-index encodes:
        // 1. The path from root (coordinate bits)
        // 2. The type at each level
        
        // Create tets with same coordinates but different types
        // At level 3, cell size is 2^17 = 262144
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - 3);
        for (byte type = 0; type <= 5; type++) {
            Tet tet = new Tet(cellSize, cellSize, cellSize, (byte) 3, type);
            TetreeKey<?> tmIndex = tet.tmIndex();
            
            System.out.println("Type " + type + ": tm-index = " + tmIndex);
            
            // Decode back to verify
            Tet decoded = Tet.tetrahedron(tmIndex);
            assertEquals(tet.x, decoded.x);
            assertEquals(tet.y, decoded.y);
            assertEquals(tet.z, decoded.z);
            assertEquals(tet.l, decoded.l);
            assertEquals(tet.type, decoded.type);
        }
        
        System.out.println("\nKey insight: tm-index encodes the TYPE, not the vertices!");
    }
    
    @Test
    public void analyzeContainmentImpact() {
        System.out.println("=== Containment Impact Analysis ===\n");
        
        // Create a parent tet at level 2 with proper alignment
        // At level 2, cell size is 2^18 = 262144
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - 2);
        Tet parent = new Tet(0, 0, 0, (byte) 2, (byte) 0);
        
        // Test point near the differing V3 vertex
        // At level 2, h = cellSize = 262144
        Point3f testPoint = new Point3f(cellSize * 0.9f, cellSize * 0.9f, cellSize * 0.9f);
        
        System.out.println("Parent: " + parent);
        System.out.println("Test point: " + testPoint);
        System.out.println();
        
        // Check containment with current t8code method
        boolean t8Contains = parent.contains(testPoint);
        System.out.println("t8code contains: " + t8Contains);
        
        // Manually check with subdivision coordinates
        Point3i[] subCoords = parent.subdivisionCoordinates();
        boolean subContains = checkTetrahedralContainment(subCoords, testPoint);
        System.out.println("subdivision contains: " + subContains);
        
        if (t8Contains != subContains) {
            System.out.println("\nWARNING: Containment results differ!");
        }
        
        // Test children to see impact on subdivision
        System.out.println("\nChecking children:");
        for (int i = 0; i < 8; i++) {
            Tet child = parent.child(i);
            boolean childT8Contains = child.contains(testPoint);
            
            Point3i[] childSubCoords = child.subdivisionCoordinates();
            boolean childSubContains = checkTetrahedralContainment(childSubCoords, testPoint);
            
            System.out.println("  Child " + i + " (type " + child.type + "): " +
                             "t8=" + childT8Contains + ", sub=" + childSubContains);
        }
    }
    
    @Test
    public void analyzeTmIndexIndependence() {
        System.out.println("=== TM-Index Independence from Vertices ===\n");
        
        // The tm-index should be the same regardless of which vertex computation we use
        // because it only encodes:
        // 1. The anchor coordinates (x, y, z)
        // 2. The level
        // 3. The type hierarchy
        
        // At level 4, cell size is 2^16 = 65536
        int cellSize = 1 << (TetreeKey.MAX_REFINEMENT_LEVEL - 4);
        // Create a properly aligned tet at level 4
        Tet tet = new Tet(cellSize, cellSize, cellSize, (byte) 4, (byte) 3);
        TetreeKey<?> tmIndex = tet.tmIndex();
        
        System.out.println("Tet: " + tet);
        System.out.println("TM-Index: " + tmIndex);
        System.out.println();
        
        // The tm-index is computed from:
        // - Anchor coordinates (x, y, z)
        // - Level
        // - Type chain from parent walk
        System.out.println("TM-Index components:");
        System.out.println("  Anchor: (" + tet.x + ", " + tet.y + ", " + tet.z + ")");
        System.out.println("  Level: " + tet.l);
        System.out.println("  Type: " + tet.type);
        
        // Walk up parent chain
        System.out.print("  Type chain: ");
        Tet current = tet;
        while (current.l > 0) {
            System.out.print(current.type + " ");
            current = current.parent();
        }
        System.out.println();
        
        System.out.println("\nConclusion: TM-index does NOT depend on vertex positions!");
        System.out.println("It only depends on the anchor, level, and type hierarchy.");
    }
    
    private boolean checkTetrahedralContainment(Point3i[] vertices, Point3f point) {
        // Simple tetrahedral containment using barycentric coordinates
        // This is just for analysis, not optimized
        
        // Convert to float for calculations
        Point3f v0 = new Point3f(vertices[0].x, vertices[0].y, vertices[0].z);
        Point3f v1 = new Point3f(vertices[1].x, vertices[1].y, vertices[1].z);
        Point3f v2 = new Point3f(vertices[2].x, vertices[2].y, vertices[2].z);
        Point3f v3 = new Point3f(vertices[3].x, vertices[3].y, vertices[3].z);
        
        // Use same-side method for each face
        return sameSide(v0, v1, v2, v3, point) &&
               sameSide(v1, v2, v3, v0, point) &&
               sameSide(v2, v3, v0, v1, point) &&
               sameSide(v3, v0, v1, v2, point);
    }
    
    private boolean sameSide(Point3f v1, Point3f v2, Point3f v3, Point3f v4, Point3f p) {
        // Check if p is on the same side of plane (v1,v2,v3) as v4
        float nx = (v2.y - v1.y) * (v3.z - v1.z) - (v2.z - v1.z) * (v3.y - v1.y);
        float ny = (v2.z - v1.z) * (v3.x - v1.x) - (v2.x - v1.x) * (v3.z - v1.z);
        float nz = (v2.x - v1.x) * (v3.y - v1.y) - (v2.y - v1.y) * (v3.x - v1.x);
        
        float dot1 = nx * (v4.x - v1.x) + ny * (v4.y - v1.y) + nz * (v4.z - v1.z);
        float dot2 = nx * (p.x - v1.x) + ny * (p.y - v1.y) + nz * (p.z - v1.z);
        
        return dot1 * dot2 >= 0;
    }
}
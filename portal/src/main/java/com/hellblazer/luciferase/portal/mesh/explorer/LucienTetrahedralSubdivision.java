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
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * Correct tetrahedral subdivision implementation using the lucien framework.
 * This mirrors the TetrahedralSubdivision class but integrates with Tet, Constants, and TetreeConnectivity.
 * 
 * This implements proper Bey's red-refinement that produces children contained within the parent tetrahedron.
 *
 * @author hal.hildebrand
 */
public class LucienTetrahedralSubdivision {
    
    private static final float EPSILON = 1e-6f;
    
    /**
     * Vertex indices for each child from Bey's subdivision.
     * Format: each row is [v0_idx, v1_idx, v2_idx, v3_idx] where indices refer to:
     * 0-3: original vertices, 4-9: edge midpoints in order 01,02,03,12,13,23
     * 
     * Based on the TetrahedralSubdivision class pattern but adapted for lucien framework.
     */
    private static final int[][] CHILD_VERTEX_INDICES = {
        { 0, 4, 5, 6 },  // T0: [x0, x01, x02, x03]
        { 4, 1, 7, 8 },  // T1: [x01, x1, x12, x13]
        { 5, 7, 2, 9 },  // T2: [x02, x12, x2, x23]
        { 6, 8, 9, 3 },  // T3: [x03, x13, x23, x3]
        { 4, 5, 6, 8 },  // T4: [x01, x02, x03, x13]
        { 4, 7, 5, 8 },  // T5: [x01, x12, x02, x13] - corrected ordering
        { 5, 6, 8, 9 },  // T6: [x02, x03, x13, x23]
        { 5, 8, 7, 9 }   // T7: [x02, x13, x12, x23] - corrected ordering
    };
    
    /**
     * TM-order to Bey-order permutation table from Constants.
     * This matches the TM_TO_BEY_PERMUTATION from TetrahedralSubdivision.
     */
    private static final int[][] TM_TO_BEY_PERMUTATION = {
        { 0, 1, 4, 7, 2, 3, 6, 5 }, // Parent type 0
        { 0, 1, 5, 7, 2, 3, 6, 4 }, // Parent type 1
        { 0, 3, 4, 7, 1, 2, 6, 5 }, // Parent type 2
        { 0, 1, 6, 7, 2, 3, 4, 5 }, // Parent type 3
        { 0, 3, 5, 7, 1, 2, 4, 6 }, // Parent type 4
        { 0, 3, 6, 7, 2, 1, 4, 5 }  // Parent type 5
    };
    
    /**
     * Subdivide a Tet using proper Bey's refinement that ensures geometric containment.
     * 
     * @param parent The parent Tet to subdivide
     * @return Array of 8 child Tets in Bey order
     */
    public static Tet[] subdivideGeometrically(Tet parent) {
        if (parent.l() >= Constants.getMaxRefinementLevel()) {
            throw new IllegalStateException("Cannot subdivide at max refinement level");
        }
        
        // Get parent vertices using lucien framework
        Point3i[] parentVerticesInt = parent.coordinates();
        Point3f[] parentVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
        }
        
        // Compute all vertices (4 original + 6 edge midpoints)
        Point3f[] allVertices = new Point3f[10];
        
        // Original vertices
        System.arraycopy(parentVertices, 0, allVertices, 0, 4);
        
        // Edge midpoints in standard order: 01,02,03,12,13,23
        allVertices[4] = midpoint(parentVertices[0], parentVertices[1]); // 01
        allVertices[5] = midpoint(parentVertices[0], parentVertices[2]); // 02
        allVertices[6] = midpoint(parentVertices[0], parentVertices[3]); // 03
        allVertices[7] = midpoint(parentVertices[1], parentVertices[2]); // 12
        allVertices[8] = midpoint(parentVertices[1], parentVertices[3]); // 13
        allVertices[9] = midpoint(parentVertices[2], parentVertices[3]); // 23
        
        // Create 8 children using the tables from lucien Constants
        Tet[] children = new Tet[8];
        byte[] childTypes = Constants.TYPE_TO_TYPE_OF_CHILD[parent.type()];
        byte childLevel = (byte) (parent.l() + 1);
        
        for (int i = 0; i < 8; i++) {
            int[] vertexIndices = CHILD_VERTEX_INDICES[i];
            
            // Convert floating point vertices back to integer coordinates
            Point3f[] childVerticesFloat = {
                allVertices[vertexIndices[0]],
                allVertices[vertexIndices[1]], 
                allVertices[vertexIndices[2]],
                allVertices[vertexIndices[3]]
            };
            
            // Create child Tet using the anchor point (first vertex)
            Point3f anchor = childVerticesFloat[0];
            children[i] = new Tet(
                Math.round(anchor.x), 
                Math.round(anchor.y), 
                Math.round(anchor.z),
                childLevel,
                childTypes[i]
            );
        }
        
        return children;
    }
    
    /**
     * Subdivide a Tet and return children in TM-order instead of Bey order.
     * 
     * @param parent The parent Tet to subdivide
     * @return Array of 8 child Tets in TM order
     */
    public static Tet[] subdivideGeometricallyTMOrder(Tet parent) {
        // Get children in Bey order first
        Tet[] beyChildren = subdivideGeometrically(parent);
        
        // Reorder to TM order using permutation table
        Tet[] tmChildren = new Tet[8];
        int[] tmToBey = TM_TO_BEY_PERMUTATION[parent.type()];
        
        for (int tm = 0; tm < 8; tm++) {
            int beyIndex = tmToBey[tm];
            tmChildren[tm] = beyChildren[beyIndex];
        }
        
        return tmChildren;
    }
    
    /**
     * Get a specific child by TM-order index.
     * 
     * @param parent The parent Tet
     * @param tmIndex The TM-order index (0-7)
     * @return The child Tet at that TM position
     */
    public static Tet getTMChild(Tet parent, int tmIndex) {
        if (tmIndex < 0 || tmIndex >= 8) {
            throw new IllegalArgumentException("TM index must be between 0 and 7");
        }
        
        // Get all children in Bey order
        Tet[] beyChildren = subdivideGeometrically(parent);
        
        // Convert TM index to Bey index using the permutation table
        int beyIndex = TM_TO_BEY_PERMUTATION[parent.type()][tmIndex];
        
        return beyChildren[beyIndex];
    }
    
    /**
     * Validate that a subdivision produces geometrically contained children.
     * 
     * @param parent The parent Tet
     * @param children The children to validate
     * @return true if all children are properly contained
     */
    public static boolean validateGeometricContainment(Tet parent, Tet[] children) {
        if (children.length != 8) {
            System.err.println("ERROR: Expected 8 children, got " + children.length);
            return false;
        }
        
        // Get parent vertices for containment testing
        Point3i[] parentVerticesInt = parent.coordinates();
        Point3f[] parentVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
        }
        
        // Test each child
        for (int i = 0; i < children.length; i++) {
            Tet child = children[i];
            Point3i[] childVerticesInt = child.coordinates();
            
            // Test each vertex of the child
            for (int j = 0; j < 4; j++) {
                Point3f childVertex = new Point3f(childVerticesInt[j].x, childVerticesInt[j].y, childVerticesInt[j].z);
                
                if (!isPointInTetrahedron(childVertex, parentVertices)) {
                    System.err.printf("ERROR: Child %d vertex %d (%s) not contained in parent\n", 
                                    i, j, childVertex);
                    return false;
                }
            }
            
            // Test child centroid
            Point3f centroid = calculateCentroid(childVerticesInt);
            if (!isPointInTetrahedron(centroid, parentVertices)) {
                System.err.printf("ERROR: Child %d centroid (%s) not contained in parent\n", 
                                i, centroid);
                return false;
            }
        }
        
        System.out.println("✅ All children geometrically contained in parent");
        return true;
    }
    
    /**
     * Test if a point is inside a tetrahedron using barycentric coordinates.
     */
    private static boolean isPointInTetrahedron(Point3f point, Point3f[] tetVertices) {
        Point3f v0 = tetVertices[0];
        Point3f v1 = tetVertices[1];
        Point3f v2 = tetVertices[2];
        Point3f v3 = tetVertices[3];
        
        // Calculate barycentric coordinates
        Point3f v01 = subtract(v1, v0);
        Point3f v02 = subtract(v2, v0);
        Point3f v03 = subtract(v3, v0);
        Point3f v0p = subtract(point, v0);
        
        float det = determinant3x3(v01, v02, v03);
        
        if (Math.abs(det) < EPSILON) {
            return false;
        }
        
        float lambda1 = determinant3x3(v0p, v02, v03) / det;
        float lambda2 = determinant3x3(v01, v0p, v03) / det;
        float lambda3 = determinant3x3(v01, v02, v0p) / det;
        float lambda0 = 1.0f - lambda1 - lambda2 - lambda3;
        
        return lambda0 >= -EPSILON && lambda1 >= -EPSILON && 
               lambda2 >= -EPSILON && lambda3 >= -EPSILON;
    }
    
    private static float determinant3x3(Point3f a, Point3f b, Point3f c) {
        return a.x * (b.y * c.z - b.z * c.y) - 
               a.y * (b.x * c.z - b.z * c.x) + 
               a.z * (b.x * c.y - b.y * c.x);
    }
    
    private static Point3f midpoint(Point3f p1, Point3f p2) {
        return new Point3f((p1.x + p2.x) / 2.0f, (p1.y + p2.y) / 2.0f, (p1.z + p2.z) / 2.0f);
    }
    
    private static Point3f subtract(Point3f p1, Point3f p2) {
        return new Point3f(p1.x - p2.x, p1.y - p2.y, p1.z - p2.z);
    }
    
    private static Point3f calculateCentroid(Point3i[] vertices) {
        float x = (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f;
        float y = (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f;
        float z = (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f;
        return new Point3f(x, y, z);
    }
    
    /**
     * Test the geometric subdivision with validation.
     * NOTE: This demonstrates the CORRECT geometric subdivision pattern,
     * but the Tet constructor currently uses SFC-based coordinates rather than geometric coordinates.
     */
    public static void testGeometricSubdivision() {
        System.out.println("=== TESTING LUCIEN GEOMETRIC SUBDIVISION ===\n");
        
        // Test at multiple levels
        for (int level = 5; level <= 15; level += 5) {
            System.out.println("Testing at level " + level);
            
            Tet parent = new Tet(1000, 1000, 1000, (byte) level, (byte) 0);
            
            System.out.println("Parent: " + parent);
            System.out.println("Parent length: " + parent.length());
            
            // Demonstrate the CORRECT geometric subdivision approach
            demonstrateCorrectGeometricSubdivision(parent, level);
            
            System.out.println();
        }
    }
    
    /**
     * Demonstrate the correct geometric subdivision approach.
     * This shows how proper Bey's refinement would work with actual geometric coordinates.
     */
    private static void demonstrateCorrectGeometricSubdivision(Tet parent, int level) {
        // Get actual tetrahedral vertices from the Tet
        Point3i[] parentVerticesInt = parent.coordinates();
        Point3f[] parentVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
        }
        
        System.out.println("Parent tetrahedron vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.println("  v" + i + ": " + parentVertices[i]);
        }
        
        // Compute midpoints (the key to Bey's refinement)
        Point3f[] allVertices = new Point3f[10];
        System.arraycopy(parentVertices, 0, allVertices, 0, 4);
        
        allVertices[4] = midpoint(parentVertices[0], parentVertices[1]); // 01
        allVertices[5] = midpoint(parentVertices[0], parentVertices[2]); // 02  
        allVertices[6] = midpoint(parentVertices[0], parentVertices[3]); // 03
        allVertices[7] = midpoint(parentVertices[1], parentVertices[2]); // 12
        allVertices[8] = midpoint(parentVertices[1], parentVertices[3]); // 13
        allVertices[9] = midpoint(parentVertices[2], parentVertices[3]); // 23
        
        System.out.println("Edge midpoints:");
        String[] edgeNames = {"01", "02", "03", "12", "13", "23"};
        for (int i = 0; i < 6; i++) {
            System.out.println("  " + edgeNames[i] + ": " + allVertices[4 + i]);
        }
        
        // Show the 8 children that would be created by proper Bey's refinement
        System.out.println("Correct Bey's refinement children (geometric):");
        for (int i = 0; i < 8; i++) {
            int[] vertexIndices = CHILD_VERTEX_INDICES[i];
            System.out.println("  Child " + i + ":");
            for (int j = 0; j < 4; j++) {
                System.out.println("    v" + j + ": " + allVertices[vertexIndices[j]]);
            }
            
            // Test if this geometric child would be contained
            boolean contained = true;
            for (int j = 0; j < 4; j++) {
                if (!isPointInTetrahedron(allVertices[vertexIndices[j]], parentVertices)) {
                    contained = false;
                    break;
                }
            }
            System.out.println("    Contained: " + (contained ? "✅" : "❌"));
        }
        
        // Compare with what the current Tet.childXXX methods produce
        System.out.println("\nComparison with current Tet methods:");
        compareWithCurrentMethods(parent);
    }
    
    private static void compareWithCurrentMethods(Tet parent) {
        String[] methods = {"child", "childTM"};
        
        for (String method : methods) {
            System.out.println("  " + method + "() violations:");
            
            int violations = 0;
            for (int i = 0; i < 8; i++) {
                try {
                    Tet child = switch (method) {
                        case "child" -> parent.child(i);
                        case "childTM" -> parent.childTM((byte) i);
                        default -> throw new IllegalArgumentException("Unknown method");
                    };
                    
                    if (!validateSingleChild(parent, child)) {
                        violations++;
                    }
                } catch (Exception e) {
                    violations++;
                }
            }
            System.out.println("    " + violations + "/8 children have containment violations");
        }
    }
    
    private static boolean validateSingleChild(Tet parent, Tet child) {
        Point3i[] parentVerticesInt = parent.coordinates();
        Point3f[] parentVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
        }
        
        Point3i[] childVerticesInt = child.coordinates();
        for (Point3i cv : childVerticesInt) {
            Point3f childVertex = new Point3f(cv.x, cv.y, cv.z);
            if (!isPointInTetrahedron(childVertex, parentVertices)) {
                return false;
            }
        }
        return true;
    }
}
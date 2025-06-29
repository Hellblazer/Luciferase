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
import static org.junit.jupiter.api.Assertions.*;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;

/**
 * Rigorous test to validate tetrahedral subdivision using proper geometric containment.
 *
 * @author hal.hildebrand
 */
public class TetSubdivisionRigorousTest {
    
    private static final float EPSILON = 1e-6f;
    
    @Test
    public void testRigorousContainment() {
        System.out.println("=== RIGOROUS TETRAHEDRAL CONTAINMENT TEST ===\n");
        
        // Test specific level
        int level = 10;
        Tet parent = new Tet(1000, 1000, 1000, (byte) level, (byte) 0);
        
        System.out.println("Parent: " + parent);
        System.out.println("Parent length: " + parent.length());
        
        // Get parent vertices as floating point for precise calculations
        Point3i[] parentVerticesInt = parent.coordinates();
        Point3f[] parentVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
        }
        
        System.out.println("\nParent vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.println("  v" + i + ": " + parentVertices[i]);
        }
        
        // Calculate parent volume for reference
        float parentVolume = calculateTetrahedronVolume(parentVertices);
        System.out.println("Parent volume: " + parentVolume);
        
        // Subdivide
        Tet[] children = parent.subdivide();
        
        // Test each child
        float totalChildVolume = 0.0f;
        int containmentViolations = 0;
        int alignmentIssues = 0;
        
        for (int i = 0; i < 8; i++) {
            Tet child = children[i];
            byte beyId = TetreeConnectivity.getBeyChildId(parent.type(), i);
            
            System.out.println("\n--- Child " + i + " (Morton " + i + " → Bey " + beyId + ") ---");
            System.out.println("Child: " + child);
            
            // Get child vertices
            Point3i[] childVerticesInt = child.coordinates();
            Point3f[] childVertices = new Point3f[4];
            for (int j = 0; j < 4; j++) {
                childVertices[j] = new Point3f(childVerticesInt[j].x, childVerticesInt[j].y, childVerticesInt[j].z);
            }
            
            // Calculate child volume
            float childVolume = calculateTetrahedronVolume(childVertices);
            totalChildVolume += childVolume;
            System.out.println("Child volume: " + childVolume);
            
            // Test 1: Rigorous containment using barycentric coordinates
            boolean allVerticesContained = true;
            for (int j = 0; j < 4; j++) {
                boolean contained = isPointInTetrahedron(childVertices[j], parentVertices);
                if (!contained) {
                    System.out.println("  ❌ Vertex " + j + " (" + childVertices[j] + ") NOT contained in parent!");
                    allVerticesContained = false;
                    containmentViolations++;
                }
            }
            
            if (allVerticesContained) {
                System.out.println("  ✅ All vertices rigorously contained in parent");
            }
            
            // Test 2: Check if child centroid is within parent
            Point3f childCentroid = calculateCentroid(childVertices);
            boolean centroidContained = isPointInTetrahedron(childCentroid, parentVertices);
            System.out.println("  Child centroid: " + childCentroid + 
                             (centroidContained ? " ✅ contained" : " ❌ NOT contained"));
            
            if (!centroidContained) {
                containmentViolations++;
            }
            
            // Test 3: Validate child tetrahedron is non-degenerate
            if (Math.abs(childVolume) < EPSILON) {
                System.out.println("  ❌ Child tetrahedron is degenerate (zero volume)!");
                alignmentIssues++;
            } else {
                System.out.println("  ✅ Child tetrahedron is non-degenerate");
            }
            
            // Test 4: Check orientation consistency
            boolean orientationCorrect = checkOrientation(childVertices);
            if (!orientationCorrect) {
                System.out.println("  ❌ Child tetrahedron has incorrect orientation!");
                alignmentIssues++;
            } else {
                System.out.println("  ✅ Child tetrahedron has correct orientation");
            }
        }
        
        // Summary validation
        System.out.println("\n=== VALIDATION SUMMARY ===");
        System.out.println("Parent volume: " + parentVolume);
        System.out.println("Sum of child volumes: " + totalChildVolume);
        System.out.println("Volume ratio (children/parent): " + (totalChildVolume / parentVolume));
        System.out.println("Containment violations: " + containmentViolations);
        System.out.println("Alignment issues: " + alignmentIssues);
        
        // Volume conservation check (children should sum to approximately parent volume)
        float volumeRatio = totalChildVolume / parentVolume;
        boolean volumeConserved = Math.abs(volumeRatio - 1.0f) < 0.1f; // 10% tolerance
        
        if (volumeConserved) {
            System.out.println("✅ Volume approximately conserved");
        } else {
            System.out.println("❌ Volume NOT conserved - subdivision may be incorrect");
        }
        
        // Overall assessment
        if (containmentViolations == 0 && alignmentIssues == 0 && volumeConserved) {
            System.out.println("\n🎉 SUBDIVISION VALIDATION PASSED - All children properly contained and aligned");
        } else {
            System.out.println("\n❌ SUBDIVISION VALIDATION FAILED");
            System.out.println("   - Containment violations: " + containmentViolations);
            System.out.println("   - Alignment issues: " + alignmentIssues);
            System.out.println("   - Volume conserved: " + volumeConserved);
        }
        
        // Assertions for test framework
        assertEquals(0, containmentViolations, "All child vertices and centroids should be contained in parent");
        assertEquals(0, alignmentIssues, "All children should be non-degenerate with correct orientation");
        assertTrue(volumeConserved, "Volume should be approximately conserved");
    }
    
    /**
     * Test if a point is inside a tetrahedron using barycentric coordinates.
     * This is the mathematically rigorous containment test.
     */
    private boolean isPointInTetrahedron(Point3f point, Point3f[] tetVertices) {
        Point3f v0 = tetVertices[0];
        Point3f v1 = tetVertices[1];
        Point3f v2 = tetVertices[2];
        Point3f v3 = tetVertices[3];
        
        // Calculate barycentric coordinates
        // If all coordinates are >= 0 and sum <= 1, point is inside
        
        // Vectors from v0 to other vertices
        Vector3f v01 = new Vector3f();
        v01.sub(v1, v0);
        Vector3f v02 = new Vector3f();
        v02.sub(v2, v0);
        Vector3f v03 = new Vector3f();
        v03.sub(v3, v0);
        
        // Vector from v0 to point
        Vector3f v0p = new Vector3f();
        v0p.sub(point, v0);
        
        // Calculate determinants for barycentric coordinates
        float det = determinant3x3(v01, v02, v03);
        
        if (Math.abs(det) < EPSILON) {
            return false; // Degenerate tetrahedron
        }
        
        float lambda1 = determinant3x3(v0p, v02, v03) / det;
        float lambda2 = determinant3x3(v01, v0p, v03) / det;
        float lambda3 = determinant3x3(v01, v02, v0p) / det;
        float lambda0 = 1.0f - lambda1 - lambda2 - lambda3;
        
        // Point is inside if all barycentric coordinates are non-negative
        return lambda0 >= -EPSILON && lambda1 >= -EPSILON && 
               lambda2 >= -EPSILON && lambda3 >= -EPSILON;
    }
    
    /**
     * Calculate determinant of 3x3 matrix formed by three vectors.
     */
    private float determinant3x3(Vector3f a, Vector3f b, Vector3f c) {
        return a.x * (b.y * c.z - b.z * c.y) - 
               a.y * (b.x * c.z - b.z * c.x) + 
               a.z * (b.x * c.y - b.y * c.x);
    }
    
    /**
     * Calculate volume of a tetrahedron.
     */
    private float calculateTetrahedronVolume(Point3f[] vertices) {
        Point3f v0 = vertices[0];
        Point3f v1 = vertices[1];
        Point3f v2 = vertices[2];
        Point3f v3 = vertices[3];
        
        Vector3f v01 = new Vector3f();
        v01.sub(v1, v0);
        Vector3f v02 = new Vector3f();
        v02.sub(v2, v0);
        Vector3f v03 = new Vector3f();
        v03.sub(v3, v0);
        
        float det = determinant3x3(v01, v02, v03);
        return Math.abs(det) / 6.0f;
    }
    
    /**
     * Calculate centroid of a tetrahedron.
     */
    private Point3f calculateCentroid(Point3f[] vertices) {
        Point3f centroid = new Point3f();
        for (Point3f vertex : vertices) {
            centroid.add(vertex);
        }
        centroid.scale(0.25f);
        return centroid;
    }
    
    /**
     * Check if tetrahedron has correct orientation (positive volume).
     */
    private boolean checkOrientation(Point3f[] vertices) {
        Vector3f v01 = new Vector3f();
        v01.sub(vertices[1], vertices[0]);
        Vector3f v02 = new Vector3f();
        v02.sub(vertices[2], vertices[0]);
        Vector3f v03 = new Vector3f();
        v03.sub(vertices[3], vertices[0]);
        
        float det = determinant3x3(v01, v02, v03);
        return det > EPSILON; // Positive orientation
    }
    
    @Test
    public void testMultipleLevelsDetailed() {
        System.out.println("\n=== MULTI-LEVEL DETAILED VALIDATION ===");
        
        for (int level = 5; level <= 15; level += 5) {
            System.out.println("\n--- Testing Level " + level + " ---");
            
            Tet parent = new Tet(100, 100, 100, (byte) level, (byte) 0);
            Tet[] children = parent.subdivide();
            
            Point3i[] parentVerticesInt = parent.coordinates();
            Point3f[] parentVertices = new Point3f[4];
            for (int i = 0; i < 4; i++) {
                parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
            }
            
            float parentVolume = calculateTetrahedronVolume(parentVertices);
            float totalChildVolume = 0.0f;
            
            for (Tet child : children) {
                Point3i[] childVerticesInt = child.coordinates();
                Point3f[] childVertices = new Point3f[4];
                for (int j = 0; j < 4; j++) {
                    childVertices[j] = new Point3f(childVerticesInt[j].x, childVerticesInt[j].y, childVerticesInt[j].z);
                }
                totalChildVolume += calculateTetrahedronVolume(childVertices);
            }
            
            float volumeRatio = totalChildVolume / parentVolume;
            System.out.println("Level " + level + " - Volume ratio: " + volumeRatio);
            
            // For Bey refinement, volume should be conserved
            assertTrue(Math.abs(volumeRatio - 1.0f) < 0.01f, 
                      "Volume should be conserved at level " + level);
        }
    }
}
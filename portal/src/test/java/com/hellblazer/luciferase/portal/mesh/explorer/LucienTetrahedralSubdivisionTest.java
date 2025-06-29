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

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

/**
 * Test the LucienTetrahedralSubdivision class to verify it produces geometrically contained children.
 *
 * @author hal.hildebrand
 */
public class LucienTetrahedralSubdivisionTest {

    @Test
    public void testGeometricSubdivisionContainment() {
        System.out.println("=== TESTING LUCIEN GEOMETRIC SUBDIVISION CONTAINMENT ===\n");
        
        // Test at various levels between 3 and 20
        int[] testLevels = {5, 10, 15};
        
        for (int level : testLevels) {
            System.out.println("--- Testing at level " + level + " ---");
            
            // Create parent Tet
            Tet parent = new Tet(1000, 1000, 1000, (byte) level, (byte) 0);
            
            System.out.println("Parent: " + parent);
            System.out.println("Parent length: " + parent.length());
            
            // Test Bey-order subdivision
            System.out.println("\nTesting Bey-order subdivision:");
            Tet[] beyChildren = LucienTetrahedralSubdivision.subdivideGeometrically(parent);
            boolean beyValid = LucienTetrahedralSubdivision.validateGeometricContainment(parent, beyChildren);
            
            // Test TM-order subdivision
            System.out.println("\nTesting TM-order subdivision:");
            Tet[] tmChildren = LucienTetrahedralSubdivision.subdivideGeometricallyTMOrder(parent);
            boolean tmValid = LucienTetrahedralSubdivision.validateGeometricContainment(parent, tmChildren);
            
            // Test individual child retrieval
            System.out.println("\nTesting individual TM child retrieval:");
            boolean individualValid = true;
            for (int i = 0; i < 8; i++) {
                Tet tmChild = LucienTetrahedralSubdivision.getTMChild(parent, i);
                
                // Verify it matches the corresponding child from full subdivision
                if (!tetEquals(tmChild, tmChildren[i])) {
                    System.err.println("ERROR: Individual TM child " + i + " doesn't match bulk subdivision");
                    individualValid = false;
                }
            }
            
            System.out.println("Individual TM children valid: " + (individualValid ? "✅ PASS" : "❌ FAIL"));
            
            System.out.println("\nLevel " + level + " Summary:");
            System.out.println("  Bey subdivision: " + (beyValid ? "✅ PASS" : "❌ FAIL"));
            System.out.println("  TM subdivision: " + (tmValid ? "✅ PASS" : "❌ FAIL"));
            System.out.println("  Individual retrieval: " + (individualValid ? "✅ PASS" : "❌ FAIL"));
            System.out.println();
        }
    }
    
    @Test
    public void compareWithExistingMethods() {
        System.out.println("=== COMPARING LUCIEN GEOMETRIC VS EXISTING METHODS ===\n");
        
        System.out.println("Level | Lucien Geo | child() | childTM()");
        System.out.println("------|------------|---------|----------");
        
        for (int level = 5; level <= 15; level += 5) {
            Tet parent = new Tet(1000, 1000, 1000, (byte) level, (byte) 0);
            
            // Test Lucien geometric subdivision
            Tet[] lucienChildren = LucienTetrahedralSubdivision.subdivideGeometrically(parent);
            boolean lucienValid = LucienTetrahedralSubdivision.validateGeometricContainment(parent, lucienChildren);
            
            // Test existing methods
            boolean childValid = testExistingMethod(parent, "child");
            boolean tmValid = testExistingMethod(parent, "childTM");
            
            System.out.printf("%5d | %10s | %7s | %9s%n",
                level,
                lucienValid ? "PASS" : "FAIL",
                childValid ? "PASS" : "FAIL",
                tmValid ? "PASS" : "FAIL"
            );
        }
    }
    
    @Test
    public void testVolumeConservation() {
        System.out.println("\n=== TESTING VOLUME CONSERVATION ===\n");
        
        for (int level = 5; level <= 15; level += 5) {
            Tet parent = new Tet(1000, 1000, 1000, (byte) level, (byte) 0);
            
            // Get parent volume
            Point3i[] parentVerticesInt = parent.coordinates();
            Point3f[] parentVertices = new Point3f[4];
            for (int i = 0; i < 4; i++) {
                parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
            }
            float parentVolume = calculateTetrahedronVolume(parentVertices);
            
            // Get children and calculate total volume
            Tet[] children = LucienTetrahedralSubdivision.subdivideGeometrically(parent);
            float totalChildVolume = 0.0f;
            
            for (Tet child : children) {
                Point3i[] childVerticesInt = child.coordinates();
                Point3f[] childVertices = new Point3f[4];
                for (int i = 0; i < 4; i++) {
                    childVertices[i] = new Point3f(childVerticesInt[i].x, childVerticesInt[i].y, childVerticesInt[i].z);
                }
                totalChildVolume += calculateTetrahedronVolume(childVertices);
            }
            
            float ratio = totalChildVolume / parentVolume;
            boolean conserved = Math.abs(ratio - 1.0f) < 0.01f; // 1% tolerance
            
            System.out.printf("Level %2d: Parent=%.6e, Children=%.6e, Ratio=%.6f %s%n",
                level, parentVolume, totalChildVolume, ratio,
                conserved ? "✅" : "❌");
        }
    }
    
    @Test
    public void runMainDemo() {
        System.out.println("\n=== RUNNING MAIN DEMONSTRATION ===\n");
        LucienTetrahedralSubdivision.testGeometricSubdivision();
    }
    
    private boolean testExistingMethod(Tet parent, String method) {
        try {
            Tet[] children = new Tet[8];
            
            for (int i = 0; i < 8; i++) {
                switch (method) {
                    case "child" -> children[i] = parent.child(i);
                    case "childTM" -> children[i] = parent.childTM((byte) i);
                    default -> throw new IllegalArgumentException("Unknown method: " + method);
                }
            }
            
            return LucienTetrahedralSubdivision.validateGeometricContainment(parent, children);
            
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean tetEquals(Tet a, Tet b) {
        if (a.l() != b.l() || a.type() != b.type()) {
            return false;
        }
        
        Point3i[] aCoords = a.coordinates();
        Point3i[] bCoords = b.coordinates();
        
        for (int i = 0; i < 4; i++) {
            if (!aCoords[i].equals(bCoords[i])) {
                return false;
            }
        }
        
        return true;
    }
    
    private float calculateTetrahedronVolume(Point3f[] vertices) {
        // Using scalar triple product: |det(v1, v2, v3)| / 6
        Point3f v1 = new Point3f();
        v1.sub(vertices[1], vertices[0]);
        Point3f v2 = new Point3f();
        v2.sub(vertices[2], vertices[0]);
        Point3f v3 = new Point3f();
        v3.sub(vertices[3], vertices[0]);
        
        // Calculate determinant
        float det = v1.x * (v2.y * v3.z - v2.z * v3.y) -
                    v1.y * (v2.x * v3.z - v2.z * v3.x) +
                    v1.z * (v2.x * v3.y - v2.y * v3.x);
        
        return Math.abs(det) / 6.0f;
    }
}
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
import javax.vecmath.Vector3f;

/**
 * Compare different subdivision methods: child(), child(), and childTM().
 *
 * @author hal.hildebrand
 */
public class SubdivisionMethodComparisonTest {
    
    private static final float EPSILON = 1e-6f;
    
    @Test
    public void compareSubdivisionMethods() {
        System.out.println("=== SUBDIVISION METHOD COMPARISON ===\n");
        
        // Test at level 10
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        System.out.println("Parent: " + parent);
        System.out.println("Parent length: " + parent.length());
        
        Point3i[] parentVerticesInt = parent.coordinates();
        Point3f[] parentVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
        }
        
        System.out.println("\nParent vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.println("  v" + i + ": " + parentVertices[i]);
        }
        
        float parentVolume = calculateTetrahedronVolume(parentVertices);
        System.out.println("Parent volume: " + parentVolume);
        
        // Test Method 1: Regular child() method (Bey refinement)
        System.out.println("\n=== METHOD 1: child() - Bey Refinement ===");
        testSubdivisionMethod(parent, "child()", parentVertices, parentVolume);
        
        // Test Method 2: child() method
        System.out.println("\n=== METHOD 2: child() - Standard Refinement ===");
        testChildStandardMethod(parent, parentVertices, parentVolume);
        
        // Test Method 3: childTM() method
        System.out.println("\n=== METHOD 3: childTM() - TM Refinement ===");
        testChildTMMethod(parent, parentVertices, parentVolume);
    }
    
    private void testSubdivisionMethod(Tet parent, String methodName, Point3f[] parentVertices, float parentVolume) {
        Tet[] children = parent.subdivide(); // Uses child() method
        analyzeChildren(children, methodName, parentVertices, parentVolume);
    }
    
    private void testChildStandardMethod(Tet parent, Point3f[] parentVertices, float parentVolume) {
        Tet[] children = new Tet[8];
        for (int i = 0; i < 8; i++) {
            children[i] = parent.child(i);
        }
        analyzeChildren(children, "child()", parentVertices, parentVolume);
    }
    
    private void testChildTMMethod(Tet parent, Point3f[] parentVertices, float parentVolume) {
        Tet[] children = new Tet[8];
        for (int i = 0; i < 8; i++) {
            children[i] = parent.childTM((byte) i);
        }
        analyzeChildren(children, "childTM()", parentVertices, parentVolume);
    }
    
    private void analyzeChildren(Tet[] children, String methodName, Point3f[] parentVertices, float parentVolume) {
        float totalChildVolume = 0.0f;
        int containmentViolations = 0;
        int orientationIssues = 0;
        
        for (int i = 0; i < 8; i++) {
            Tet child = children[i];
            
            // Get child vertices
            Point3i[] childVerticesInt = child.coordinates();
            Point3f[] childVertices = new Point3f[4];
            for (int j = 0; j < 4; j++) {
                childVertices[j] = new Point3f(childVerticesInt[j].x, childVerticesInt[j].y, childVerticesInt[j].z);
            }
            
            // Calculate child volume
            float childVolume = calculateTetrahedronVolume(childVertices);
            totalChildVolume += childVolume;
            
            // Test containment
            boolean allContained = true;
            for (Point3f vertex : childVertices) {
                if (!isPointInTetrahedron(vertex, parentVertices)) {
                    allContained = false;
                    containmentViolations++;
                    break;
                }
            }
            
            // Test centroid containment
            Point3f centroid = calculateCentroid(childVertices);
            boolean centroidContained = isPointInTetrahedron(centroid, parentVertices);
            if (!centroidContained) {
                containmentViolations++;
            }
            
            // Check orientation
            if (!checkOrientation(childVertices)) {
                orientationIssues++;
            }
            
            if (i < 3) { // Show details for first 3 children
                System.out.println("Child " + i + ": " + child);
                System.out.println("  Vertices contained: " + allContained);
                System.out.println("  Centroid contained: " + centroidContained);
                System.out.println("  Volume: " + childVolume);
            }
        }
        
        float volumeRatio = totalChildVolume / parentVolume;
        boolean volumeConserved = Math.abs(volumeRatio - 1.0f) < 0.1f;
        
        System.out.println("\n" + methodName + " SUMMARY:");
        System.out.println("  Containment violations: " + containmentViolations);
        System.out.println("  Orientation issues: " + orientationIssues);
        System.out.println("  Volume ratio: " + volumeRatio);
        System.out.println("  Volume conserved: " + volumeConserved);
        
        if (containmentViolations == 0 && orientationIssues == 0 && volumeConserved) {
            System.out.println("  ✅ " + methodName + " PASSED - Perfect subdivision!");
        } else {
            System.out.println("  ❌ " + methodName + " FAILED");
        }
    }
    
    @Test
    public void detailedChildStandardAnalysis() {
        System.out.println("\n=== DETAILED child() ANALYSIS ===");
        
        Tet parent = new Tet(500, 500, 500, (byte) 12, (byte) 0);
        
        System.out.println("Parent: " + parent);
        System.out.println("Parent length: " + parent.length());
        
        Point3i[] parentVertices = parent.coordinates();
        System.out.println("\nParent vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.println("  v" + i + ": " + parentVertices[i]);
        }
        
        // Calculate parent bounding box
        int minX = parentVertices[0].x, maxX = parentVertices[0].x;
        int minY = parentVertices[0].y, maxY = parentVertices[0].y;
        int minZ = parentVertices[0].z, maxZ = parentVertices[0].z;
        
        for (Point3i v : parentVertices) {
            minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
            minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
        }
        
        System.out.println("Parent bounding box: [" + minX + "," + maxX + "] x [" + 
                           minY + "," + maxY + "] x [" + minZ + "," + maxZ + "]");
        
        System.out.println("\nchild() subdivision:");
        for (int i = 0; i < 8; i++) {
            Tet child = parent.child(i);
            Point3i[] childVertices = child.coordinates();
            
            System.out.println("\nChild " + i + " (Morton index " + i + "):");
            System.out.println("  Tet: " + child);
            System.out.println("  Anchor: (" + child.x() + ", " + child.y() + ", " + child.z() + ")");
            
            boolean hasViolation = false;
            for (int j = 0; j < 4; j++) {
                Point3i cv = childVertices[j];
                boolean outOfBounds = cv.x < minX || cv.x > maxX || 
                                     cv.y < minY || cv.y > maxY || 
                                     cv.z < minZ || cv.z > maxZ;
                
                if (outOfBounds) {
                    System.out.println("  ❌ v" + j + ": " + cv + " is OUTSIDE parent bounds!");
                    hasViolation = true;
                } else {
                    System.out.println("  ✅ v" + j + ": " + cv + " is within parent bounds");
                }
            }
            
            if (!hasViolation) {
                System.out.println("  ✅ Child " + i + " is properly contained");
            }
        }
    }
    
    /**
     * Test if a point is inside a tetrahedron using barycentric coordinates.
     */
    private boolean isPointInTetrahedron(Point3f point, Point3f[] tetVertices) {
        Point3f v0 = tetVertices[0];
        Point3f v1 = tetVertices[1];
        Point3f v2 = tetVertices[2];
        Point3f v3 = tetVertices[3];
        
        Vector3f v01 = new Vector3f();
        v01.sub(v1, v0);
        Vector3f v02 = new Vector3f();
        v02.sub(v2, v0);
        Vector3f v03 = new Vector3f();
        v03.sub(v3, v0);
        Vector3f v0p = new Vector3f();
        v0p.sub(point, v0);
        
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
    
    private float determinant3x3(Vector3f a, Vector3f b, Vector3f c) {
        return a.x * (b.y * c.z - b.z * c.y) - 
               a.y * (b.x * c.z - b.z * c.x) + 
               a.z * (b.x * c.y - b.y * c.x);
    }
    
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
    
    private Point3f calculateCentroid(Point3f[] vertices) {
        Point3f centroid = new Point3f();
        for (Point3f vertex : vertices) {
            centroid.add(vertex);
        }
        centroid.scale(0.25f);
        return centroid;
    }
    
    private boolean checkOrientation(Point3f[] vertices) {
        Vector3f v01 = new Vector3f();
        v01.sub(vertices[1], vertices[0]);
        Vector3f v02 = new Vector3f();
        v02.sub(vertices[2], vertices[0]);
        Vector3f v03 = new Vector3f();
        v03.sub(vertices[3], vertices[0]);
        
        float det = determinant3x3(v01, v02, v03);
        return det > EPSILON;
    }
}
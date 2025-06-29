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
 * Corrected containment test using proper tetrahedral geometry instead of AABB.
 *
 * @author hal.hildebrand
 */
public class CorrectedContainmentTest {
    
    private static final float EPSILON = 1e-6f;
    
    @Test
    public void testCorrectedAllLevelsChild() {
        System.out.println("=== CORRECTED TESTING: child() USING PROPER TETRAHEDRAL CONTAINMENT ===\n");
        
        System.out.println("Level | ContainViol | OrientIssues | VolumeRatio | Status | KeyType | CellSize");
        System.out.println("------|-------------|--------------|-------------|--------|---------|----------");
        
        for (int level = 0; level <= 20; level++) {
            try {
                int baseCoord = 1000 + level * 100;
                Tet parent = new Tet(baseCoord, baseCoord, baseCoord, (byte) level, (byte) 0);
                
                SubdivisionResult result = testChildWithTetrahedralContainment(parent);
                String keyType = level <= 10 ? "Compact" : "Regular";
                
                System.out.printf("%5d | %11d | %12d | %11.6f | %6s | %7s | %8d%n",
                    level, 
                    result.containmentViolations,
                    result.orientationIssues,
                    result.volumeRatio,
                    result.isPerfect() ? "PASS" : "FAIL",
                    keyType,
                    parent.length()
                );
                
                // Show detailed analysis for first few levels and level 10-11
                if (level <= 3 || level == 10 || level == 11) {
                    System.out.println("    📐 CORRECTED TETRAHEDRAL ANALYSIS FOR LEVEL " + level + ":");
                    analyzeChildWithTetrahedralGeometry(parent, level);
                }
                
            } catch (Exception e) {
                System.out.printf("%5d | %11s | %12s | %11s | %6s | %7s | %8s%n",
                    level, "ERROR", "ERROR", "ERROR", "ERROR", "ERROR", "ERROR");
                System.out.println("    Error: " + e.getMessage());
            }
        }
    }
    
    @Test  
    public void testCorrectedAllMethodsComparison() {
        System.out.println("\n=== CORRECTED COMPARISON: ALL METHODS WITH PROPER TETRAHEDRAL GEOMETRY ===\n");
        
        System.out.println("Level | child() Violations | childTM() Violations");
        System.out.println("------|--------------------|----------------------");
        
        for (int level = 0; level <= 20; level++) {
            try {
                Tet parent = new Tet(1000, 1000, 1000, (byte) level, (byte) 0);
                
                SubdivisionResult childResult = testChildWithTetrahedralContainment(parent);
                SubdivisionResult tmResult = testChildTMWithTetrahedralContainment(parent);
                
                System.out.printf("%5d | %18d | %20d%n",
                    level,
                    childResult.containmentViolations,
                    tmResult.containmentViolations
                );
                
            } catch (Exception e) {
                System.out.printf("%5d | %18s | %20s%n",
                    level, "ERROR", "ERROR");
            }
        }
    }
    
    @Test
    public void demonstrateAABBvsTetrahedronDifference() {
        System.out.println("\n=== DEMONSTRATING AABB vs TETRAHEDRON DIFFERENCE ===\n");
        
        Tet parent = new Tet(1000, 1000, 1000, (byte) 10, (byte) 0);
        
        Point3i[] parentVerticesInt = parent.coordinates();
        Point3f[] parentVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
        }
        
        // Calculate AABB
        float minX = parentVertices[0].x, maxX = parentVertices[0].x;
        float minY = parentVertices[0].y, maxY = parentVertices[0].y;
        float minZ = parentVertices[0].z, maxZ = parentVertices[0].z;
        
        for (Point3f v : parentVertices) {
            minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
            minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
        }
        
        float aabbVolume = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        float tetrahedronVolume = calculateTetrahedronVolume(parentVertices);
        
        System.out.println("Parent tetrahedron vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.println("  v" + i + ": " + parentVertices[i]);
        }
        System.out.println();
        
        System.out.println("AABB bounds: [" + minX + "," + maxX + "] x [" + 
                           minY + "," + maxY + "] x [" + minZ + "," + maxZ + "]");
        System.out.println("AABB volume: " + aabbVolume);
        System.out.println("Tetrahedron volume: " + tetrahedronVolume);
        System.out.println("AABB is " + (aabbVolume / tetrahedronVolume) + "x larger than tetrahedron!");
        System.out.println();
        
        // Test first child with both methods
        Tet child = parent.child(0);
        Point3i[] childVerticesInt = child.coordinates();
        Point3f[] childVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            childVertices[i] = new Point3f(childVerticesInt[i].x, childVerticesInt[i].y, childVerticesInt[i].z);
        }
        
        System.out.println("Child 0 vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.println("  v" + i + ": " + childVertices[i]);
        }
        System.out.println();
        
        // Test each child vertex with both methods
        int aabbContained = 0, tetrahedronContained = 0;
        for (int i = 0; i < 4; i++) {
            Point3f vertex = childVertices[i];
            
            // AABB test
            boolean inAABB = vertex.x >= minX && vertex.x <= maxX &&
                            vertex.y >= minY && vertex.y <= maxY &&
                            vertex.z >= minZ && vertex.z <= maxZ;
            
            // Tetrahedral test
            boolean inTetrahedron = isPointInTetrahedron(vertex, parentVertices);
            
            if (inAABB) aabbContained++;
            if (inTetrahedron) tetrahedronContained++;
            
            System.out.println("Child vertex " + i + ": " + vertex);
            System.out.println("  In AABB: " + (inAABB ? "✅" : "❌"));
            System.out.println("  In Tetrahedron: " + (inTetrahedron ? "✅" : "❌"));
        }
        
        System.out.println("\nSUMMARY for Child 0:");
        System.out.println("  Vertices in AABB: " + aabbContained + "/4");
        System.out.println("  Vertices in Tetrahedron: " + tetrahedronContained + "/4");
    }
    
    private void analyzeChildWithTetrahedralGeometry(Tet parent, int level) {
        Point3i[] parentVerticesInt = parent.coordinates();
        Point3f[] parentVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
        }
        
        System.out.println("    Parent tetrahedron vertices:");
        for (int i = 0; i < 4; i++) {
            System.out.println("      v" + i + ": " + parentVertices[i]);
        }
        
        int violationCount = 0;
        for (int i = 0; i < 8; i++) {
            try {
                Tet child = parent.child(i);
                Point3i[] childVerticesInt = child.coordinates();
                Point3f[] childVertices = new Point3f[4];
                for (int j = 0; j < 4; j++) {
                    childVertices[j] = new Point3f(childVerticesInt[j].x, childVerticesInt[j].y, childVerticesInt[j].z);
                }
                
                boolean hasViolation = false;
                for (Point3f cv : childVertices) {
                    if (!isPointInTetrahedron(cv, parentVertices)) {
                        hasViolation = true;
                        violationCount++;
                        break;
                    }
                }
                
                if (hasViolation && violationCount <= 3) { // Show first few violations
                    System.out.println("    Child " + i + " violation: " + child);
                    for (int j = 0; j < 4; j++) {
                        Point3f cv = childVertices[j];
                        boolean contained = isPointInTetrahedron(cv, parentVertices);
                        if (!contained) {
                            System.out.println("      v" + j + ": " + cv + " NOT in tetrahedron");
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("    Child " + i + " error: " + e.getMessage());
                violationCount++;
            }
        }
        
        if (violationCount == 0) {
            System.out.println("    ✅ All children properly contained in tetrahedron");
        } else {
            System.out.println("    ❌ " + violationCount + " tetrahedral containment violations found");
        }
    }
    
    
    private SubdivisionResult testChildWithTetrahedralContainment(Tet parent) {
        return testSubdivisionMethodWithTetrahedralContainment(parent, "child");
    }
    
    private SubdivisionResult testChildTMWithTetrahedralContainment(Tet parent) {
        return testSubdivisionMethodWithTetrahedralContainment(parent, "childTM");
    }
    
    private SubdivisionResult testSubdivisionMethodWithTetrahedralContainment(Tet parent, String method) {
        Tet[] children = new Tet[8];
        
        // Get children using specified method
        for (int i = 0; i < 8; i++) {
            switch (method) {
                case "child" -> children[i] = parent.child(i);
                case "childTM" -> children[i] = parent.childTM((byte) i);
                default -> throw new IllegalArgumentException("Unknown method: " + method);
            }
        }
        
        // Analyze containment using PROPER tetrahedral geometry
        Point3i[] parentVerticesInt = parent.coordinates();
        Point3f[] parentVertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            parentVertices[i] = new Point3f(parentVerticesInt[i].x, parentVerticesInt[i].y, parentVerticesInt[i].z);
        }
        
        float parentVolume = calculateTetrahedronVolume(parentVertices);
        float totalChildVolume = 0.0f;
        int containmentViolations = 0;
        int orientationIssues = 0;
        
        for (Tet child : children) {
            Point3i[] childVerticesInt = child.coordinates();
            Point3f[] childVertices = new Point3f[4];
            for (int j = 0; j < 4; j++) {
                childVertices[j] = new Point3f(childVerticesInt[j].x, childVerticesInt[j].y, childVerticesInt[j].z);
            }
            
            float childVolume = calculateTetrahedronVolume(childVertices);
            totalChildVolume += childVolume;
            
            // Test TETRAHEDRAL containment (not AABB!)
            for (Point3f vertex : childVertices) {
                if (!isPointInTetrahedron(vertex, parentVertices)) {
                    containmentViolations++;
                    break;
                }
            }
            
            // Test centroid containment
            Point3f centroid = calculateCentroid(childVertices);
            if (!isPointInTetrahedron(centroid, parentVertices)) {
                containmentViolations++;
            }
            
            // Check orientation
            if (!checkOrientation(childVertices)) {
                orientationIssues++;
            }
        }
        
        float volumeRatio = totalChildVolume / parentVolume;
        return new SubdivisionResult(containmentViolations, orientationIssues, volumeRatio);
    }
    
    // Utility methods
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
        Vector3f v01 = new Vector3f();
        v01.sub(vertices[1], vertices[0]);
        Vector3f v02 = new Vector3f();
        v02.sub(vertices[2], vertices[0]);
        Vector3f v03 = new Vector3f();
        v03.sub(vertices[3], vertices[0]);
        
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
    
    private static class SubdivisionResult {
        final int containmentViolations;
        final int orientationIssues;
        final float volumeRatio;
        
        SubdivisionResult(int containmentViolations, int orientationIssues, float volumeRatio) {
            this.containmentViolations = containmentViolations;
            this.orientationIssues = orientationIssues;
            this.volumeRatio = volumeRatio;
        }
        
        boolean isPerfect() {
            return containmentViolations == 0 && orientationIssues == 0 && 
                   Math.abs(volumeRatio - 1.0f) < 0.1f;
        }
    }
}
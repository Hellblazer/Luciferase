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
 * Test all subdivision methods across all 21 levels (0-20) to identify patterns.
 *
 * @author hal.hildebrand
 */
public class AllLevelsSubdivisionTest {
    
    private static final float EPSILON = 1e-6f;
    
    @Test
    public void testAllLevelsChild() {
        System.out.println("=== TESTING child() ACROSS ALL LEVELS 0-20 ===\n");
        
        System.out.println("Level | ContainViol | OrientIssues | VolumeRatio | Status | KeyType | CellSize");
        System.out.println("------|-------------|--------------|-------------|--------|---------|----------");
        
        for (int level = 0; level <= 20; level++) {
            try {
                // Use different starting positions to avoid edge cases
                int baseCoord = 1000 + level * 100; // Vary starting position
                Tet parent = new Tet(baseCoord, baseCoord, baseCoord, (byte) level, (byte) 0);
                
                // Test child subdivision
                SubdivisionResult result = testChildAtLevel(parent);
                
                // Determine key type based on level (from CompactTetreeKey usage)
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
                
                // Show detailed analysis for suspicious levels
                if (level == 10 || level == 11 || result.containmentViolations > 0) {
                    System.out.println("    ⚠️  DETAILED ANALYSIS FOR LEVEL " + level + ":");
                    analyzeChildDetailed(parent, level);
                }
                
            } catch (Exception e) {
                System.out.printf("%5d | %11s | %12s | %11s | %6s | %7s | %8s%n",
                    level, "ERROR", "ERROR", "ERROR", "ERROR", "ERROR", "ERROR");
                System.out.println("    Error: " + e.getMessage());
            }
        }
    }
    
    @Test  
    public void testAllLevelsAllMethods() {
        System.out.println("\n=== COMPARING ALL METHODS ACROSS ALL LEVELS ===\n");
        
        System.out.println("Level | child() Violations | childTM() Violations");
        System.out.println("------|--------------------|----------------------");
        
        for (int level = 0; level <= 20; level++) {
            try {
                Tet parent = new Tet(1000, 1000, 1000, (byte) level, (byte) 0);
                
                // Test both methods
                SubdivisionResult childResult = testChildAtLevel(parent);
                SubdivisionResult tmResult = testChildTMAtLevel(parent);
                
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
    public void investigateLevel10Transition() {
        System.out.println("\n=== INVESTIGATING LEVEL 10-11 TRANSITION ===\n");
        
        // Test levels around the CompactTetreeKey transition
        for (int level = 9; level <= 12; level++) {
            System.out.println("--- LEVEL " + level + " DETAILED ANALYSIS ---");
            
            Tet parent = new Tet(1000, 1000, 1000, (byte) level, (byte) 0);
            
            System.out.println("Parent: " + parent);
            System.out.println("Cell size: " + parent.length());
            System.out.println("Key type: " + (level <= 10 ? "CompactTetreeKey" : "TetreeKey"));
            
            // Test tmIndex() behavior
            try {
                var tmIndex = parent.tmIndex();
                System.out.println("TM Index: " + tmIndex.getClass().getSimpleName());
                System.out.println("TM Index level: " + tmIndex.getLevel());
            } catch (Exception e) {
                System.out.println("TM Index error: " + e.getMessage());
            }
            
            // Detailed child analysis
            analyzeChildDetailed(parent, level);
            System.out.println();
        }
    }
    
    private void analyzeChildDetailed(Tet parent, int level) {
        Point3i[] parentVertices = parent.coordinates();
        
        // Calculate parent bounding box
        int minX = parentVertices[0].x, maxX = parentVertices[0].x;
        int minY = parentVertices[0].y, maxY = parentVertices[0].y;
        int minZ = parentVertices[0].z, maxZ = parentVertices[0].z;
        
        for (Point3i v : parentVertices) {
            minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
            minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
        }
        
        System.out.println("    Parent bounds: [" + minX + "," + maxX + "] x [" + 
                           minY + "," + maxY + "] x [" + minZ + "," + maxZ + "]");
        
        int violationCount = 0;
        for (int i = 0; i < 8; i++) {
            try {
                Tet child = parent.child(i);
                Point3i[] childVertices = child.coordinates();
                
                boolean hasViolation = false;
                for (Point3i cv : childVertices) {
                    if (cv.x < minX || cv.x > maxX || cv.y < minY || cv.y > maxY || cv.z < minZ || cv.z > maxZ) {
                        hasViolation = true;
                        violationCount++;
                        break;
                    }
                }
                
                if (hasViolation && violationCount <= 3) { // Show first few violations
                    System.out.println("    Child " + i + " violation: " + child);
                    for (int j = 0; j < 4; j++) {
                        Point3i cv = childVertices[j];
                        boolean outOfBounds = cv.x < minX || cv.x > maxX || 
                                             cv.y < minY || cv.y > maxY || 
                                             cv.z < minZ || cv.z > maxZ;
                        if (outOfBounds) {
                            System.out.println("      v" + j + ": " + cv + " OUTSIDE bounds");
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("    Child " + i + " error: " + e.getMessage());
                violationCount++;
            }
        }
        
        if (violationCount == 0) {
            System.out.println("    ✅ All children properly contained");
        } else {
            System.out.println("    ❌ " + violationCount + " containment violations found");
        }
    }
    
    
    private SubdivisionResult testChildAtLevel(Tet parent) {
        return testSubdivisionMethod(parent, "child");
    }
    
    private SubdivisionResult testChildTMAtLevel(Tet parent) {
        return testSubdivisionMethod(parent, "childTM");
    }
    
    private SubdivisionResult testSubdivisionMethod(Tet parent, String method) {
        Tet[] children = new Tet[8];
        
        // Get children using specified method
        for (int i = 0; i < 8; i++) {
            switch (method) {
                case "child" -> children[i] = parent.child(i);
                case "childTM" -> children[i] = parent.childTM((byte) i);
                default -> throw new IllegalArgumentException("Unknown method: " + method);
            }
        }
        
        // Analyze containment and orientation
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
            
            // Test containment
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
    
    // Utility methods (same as before)
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
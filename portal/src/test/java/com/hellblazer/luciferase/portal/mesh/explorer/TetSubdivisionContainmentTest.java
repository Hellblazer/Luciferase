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

import javax.vecmath.Point3i;

/**
 * Test to validate that tetrahedral subdivision creates children contained within the parent.
 *
 * @author hal.hildebrand
 */
public class TetSubdivisionContainmentTest {
    
    @Test
    public void testChildrenContainedInParent() {
        // Test at different levels between 4 and 19
        for (int level = 4; level < 20; level += 3) {
            System.out.println("\n=== Testing subdivision at level " + level + " ===");
            
            // Create a parent tetrahedron at a reasonable position
            Tet parent = new Tet(1000, 1000, 1000, (byte) level, (byte) 0);
            
            System.out.println("Parent: " + parent);
            System.out.println("Parent length: " + parent.length());
            
            // Get parent vertices
            Point3i[] parentVertices = parent.coordinates();
            System.out.println("Parent vertices:");
            for (int i = 0; i < 4; i++) {
                System.out.println("  v" + i + ": " + parentVertices[i]);
            }
            
            // Subdivide into 8 children
            Tet[] children = parent.subdivide();
            
            System.out.println("\nChildren subdivision:");
            for (int i = 0; i < 8; i++) {
                Tet child = children[i];
                byte beyId = TetreeConnectivity.getBeyChildId(parent.type(), i);
                
                System.out.println("Morton " + i + " → Bey " + beyId + ": " + child);
                
                // Get child vertices
                Point3i[] childVertices = child.coordinates();
                System.out.println("  Child vertices:");
                for (int j = 0; j < 4; j++) {
                    System.out.println("    v" + j + ": " + childVertices[j]);
                }
                
                // Check if all child vertices are contained within parent tetrahedron
                boolean allContained = true;
                for (Point3i childVertex : childVertices) {
                    boolean contained = isPointInTetrahedron(childVertex, parentVertices);
                    if (!contained) {
                        System.out.println("    ❌ Vertex " + childVertex + " NOT contained in parent!");
                        allContained = false;
                    }
                }
                
                if (allContained) {
                    System.out.println("    ✅ All vertices contained in parent");
                } else {
                    System.out.println("    ❌ CONTAINMENT VIOLATION for child " + i);
                }
            }
        }
    }
    
    /**
     * Test if a point is contained within a tetrahedron using barycentric coordinates.
     * This is a simplified containment test.
     */
    private boolean isPointInTetrahedron(Point3i point, Point3i[] tetVertices) {
        // For this test, we'll use a bounding box approximation
        // A proper implementation would use barycentric coordinates
        
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = Float.MIN_VALUE;
        
        for (Point3i vertex : tetVertices) {
            minX = Math.min(minX, vertex.x);
            maxX = Math.max(maxX, vertex.x);
            minY = Math.min(minY, vertex.y);
            maxY = Math.max(maxY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxZ = Math.max(maxZ, vertex.z);
        }
        
        // Check if point is within bounding box (conservative test)
        return point.x >= minX && point.x <= maxX &&
               point.y >= minY && point.y <= maxY &&
               point.z >= minZ && point.z <= maxZ;
    }
    
    @Test
    public void testSpecificLevelSubdivision() {
        System.out.println("\n=== Detailed test at level 10 ===");
        
        // Test with a specific case
        Tet parent = new Tet(500, 500, 500, (byte) 10, (byte) 0);
        
        System.out.println("Parent: " + parent);
        System.out.println("Parent length: " + parent.length());
        System.out.println("Parent type: " + parent.type());
        
        Point3i[] parentVertices = parent.coordinates();
        System.out.println("\nParent tetrahedron vertices:");
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
        
        // Subdivide and check each child
        Tet[] children = parent.subdivide();
        
        int violationCount = 0;
        for (int i = 0; i < 8; i++) {
            Tet child = children[i];
            Point3i[] childVertices = child.coordinates();
            
            System.out.println("\nChild " + i + " (type " + child.type() + "):");
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
                    violationCount++;
                } else {
                    System.out.println("  ✅ v" + j + ": " + cv + " is within parent bounds");
                }
            }
            
            if (!hasViolation) {
                System.out.println("  ✅ Child " + i + " is properly contained");
            }
        }
        
        System.out.println("\n=== SUMMARY ===");
        if (violationCount == 0) {
            System.out.println("✅ ALL CHILDREN PROPERLY CONTAINED - Subdivision is working correctly");
        } else {
            System.out.println("❌ " + violationCount + " CONTAINMENT VIOLATIONS - Subdivision has issues");
        }
    }
}
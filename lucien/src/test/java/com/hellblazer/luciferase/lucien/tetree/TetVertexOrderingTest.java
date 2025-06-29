/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify vertex ordering and determine if we need standardOrderCoordinates().
 * 
 * @author hal.hildebrand
 */
public class TetVertexOrderingTest {

    @Test
    public void testVertexOrderingVsStandard() {
        // Test all 6 tetrahedral types at the root level
        for (int type = 0; type < 6; type++) {
            System.out.println("\nType " + type + ":");
            
            // Create a tet at the origin with the given type
            Tet tet = new Tet(0, 0, 0, (byte) 0, (byte) type);
            Point3i[] coords = tet.coordinates();
            Point3i[] standardOrdered = tet.standardOrderCoordinates();
            
            // Get the standard ordering from SIMPLEX_STANDARD
            Point3i[] standard = Constants.SIMPLEX_STANDARD[type];
            
            System.out.println("  Tet.coordinates(): ");
            for (int i = 0; i < 4; i++) {
                System.out.println("    v" + i + ": " + coords[i]);
            }
            
            System.out.println("  Tet.standardOrderCoordinates(): ");
            for (int i = 0; i < 4; i++) {
                System.out.println("    v" + i + ": " + standardOrdered[i]);
            }
            
            System.out.println("  SIMPLEX_STANDARD: ");
            for (int i = 0; i < 4; i++) {
                System.out.println("    v" + i + ": " + standard[i]);
            }
            
            // Check if they match
            boolean matches = true;
            for (int i = 0; i < 4; i++) {
                if (!coords[i].equals(standard[i])) {
                    matches = false;
                    break;
                }
            }
            
            System.out.println("  Original matches standard: " + matches);
            
            // Verify face normals point outward
            if (!matches) {
                System.out.println("  Checking original face normals:");
                checkFaceNormals(coords, type);
            }
            
            // Now check standardOrderCoordinates normals
            System.out.println("  Checking standardOrderCoordinates face normals:");
            checkFaceNormals(standardOrdered, type);
        }
    }
    
    private void checkFaceNormals(Point3i[] vertices, int type) {
        // Face indices for a tetrahedron (4 triangular faces)
        int[][] faces = {
            {0, 1, 2},  // Face 0
            {0, 1, 3},  // Face 1
            {0, 2, 3},  // Face 2
            {1, 2, 3}   // Face 3
        };
        
        // Compute centroid of tetrahedron
        Vector3f centroid = new Vector3f();
        for (Point3i v : vertices) {
            centroid.x += v.x;
            centroid.y += v.y;
            centroid.z += v.z;
        }
        centroid.scale(0.25f);
        
        // Check each face
        for (int i = 0; i < faces.length; i++) {
            int[] face = faces[i];
            Vector3f normal = computeFaceNormal(
                vertices[face[0]], 
                vertices[face[1]], 
                vertices[face[2]]
            );
            
            // Vector from centroid to face center
            Vector3f faceCenter = new Vector3f();
            for (int j = 0; j < 3; j++) {
                faceCenter.x += vertices[face[j]].x;
                faceCenter.y += vertices[face[j]].y;
                faceCenter.z += vertices[face[j]].z;
            }
            faceCenter.scale(1.0f / 3.0f);
            
            Vector3f toFace = new Vector3f();
            toFace.sub(faceCenter, centroid);
            
            // Normal should point in same direction as vector from centroid to face
            float dot = normal.dot(toFace);
            System.out.println("    Face " + i + ": normal dot toFace = " + dot + 
                             (dot > 0 ? " (outward)" : " (inward)"));
        }
    }
    
    private Vector3f computeFaceNormal(Point3i v0, Point3i v1, Point3i v2) {
        // Compute two edge vectors
        Vector3f edge1 = new Vector3f(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        Vector3f edge2 = new Vector3f(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);
        
        // Cross product gives normal
        Vector3f normal = new Vector3f();
        normal.cross(edge1, edge2);
        normal.normalize();
        
        return normal;
    }
    
    @Test
    public void testNonRootLevelVertexOrdering() {
        // Test a tet at a deeper level
        byte level = 5;
        int x = 100, y = 200, z = 300;
        
        for (int type = 0; type < 6; type++) {
            Tet tet = new Tet(x, y, z, level, (byte) type);
            Point3i[] coords = tet.coordinates();
            
            System.out.println("\nLevel " + level + ", Type " + type + " at (" + x + "," + y + "," + z + "):");
            for (int i = 0; i < 4; i++) {
                System.out.println("  v" + i + ": " + coords[i]);
            }
            
            // Verify the vertices form a proper tetrahedron
            // Check that we have 4 distinct vertices
            for (int i = 0; i < 4; i++) {
                for (int j = i + 1; j < 4; j++) {
                    assertFalse(coords[i].equals(coords[j]), 
                        "Vertices " + i + " and " + j + " should be distinct");
                }
            }
        }
    }
    
    @Test
    public void testStandardOrderNormalsAllOutward() {
        // Test that standardOrderCoordinates produces consistent outward normals
        for (int type = 0; type < 6; type++) {
            Tet tet = new Tet(0, 0, 0, (byte) 5, (byte) type); // Use level 5 for smaller numbers
            Point3i[] vertices = tet.standardOrderCoordinates();
            
            // Face indices for proper tetrahedral faces with right-hand rule
            // Using the correct winding order for outward normals
            int[][] standardFaces = {
                {0, 2, 1},  // Face 0-2-1 (base, viewed from below)
                {0, 1, 3},  // Face 0-1-3 (front right)
                {0, 3, 2},  // Face 0-3-2 (back left)
                {1, 2, 3}   // Face 1-2-3 (top, viewed from above)
            };
            
            // Compute centroid
            Vector3f centroid = new Vector3f();
            for (Point3i v : vertices) {
                centroid.x += v.x;
                centroid.y += v.y;
                centroid.z += v.z;
            }
            centroid.scale(0.25f);
            
            boolean allOutward = true;
            for (int i = 0; i < standardFaces.length; i++) {
                int[] face = standardFaces[i];
                Vector3f normal = computeFaceNormal(
                    vertices[face[0]], 
                    vertices[face[1]], 
                    vertices[face[2]]
                );
                
                // Vector from centroid to face center
                Vector3f faceCenter = new Vector3f();
                for (int j = 0; j < 3; j++) {
                    faceCenter.x += vertices[face[j]].x;
                    faceCenter.y += vertices[face[j]].y;
                    faceCenter.z += vertices[face[j]].z;
                }
                faceCenter.scale(1.0f / 3.0f);
                
                Vector3f toFace = new Vector3f();
                toFace.sub(faceCenter, centroid);
                
                float dot = normal.dot(toFace);
                if (dot < 0) {
                    allOutward = false;
                    System.out.println("Type " + type + ", Face " + i + " has inward normal!");
                }
            }
            
            assertTrue(allOutward, "Type " + type + " should have all outward normals");
        }
    }
}
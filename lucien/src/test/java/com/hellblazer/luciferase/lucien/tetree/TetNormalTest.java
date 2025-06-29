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
package com.hellblazer.luciferase.lucien.tetree;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test face normals for all 6 tetrahedron types
 *
 * @author hal.hildebrand
 */
public class TetNormalTest {
    
    @Test
    public void testAllTetTypeFaceNormals() {
        // Test all 6 tetrahedron types
        for (byte type = 0; type < 6; type++) {
            System.out.println("\n=== Testing Type " + type + " ===");
            
            Tet tet = new Tet(0, 0, 0, (byte)10, type); // Use level 10 for reasonable size
            
            // Get vertices using both methods
            Point3i[] standardCoords = tet.standardOrderCoordinates();
            Point3i[] coords = tet.coordinates();
            
            System.out.println("Standard order vertices:");
            for (int i = 0; i < 4; i++) {
                System.out.println("  v" + i + ": " + standardCoords[i]);
            }
            
            System.out.println("Coordinates() vertices:");
            for (int i = 0; i < 4; i++) {
                System.out.println("  v" + i + ": " + coords[i]);
            }
            
            // Test face normals with standard order
            testFaceNormals(standardCoords, "standardOrderCoordinates");
            
            // Test face normals with coordinates()
            testFaceNormals(coords, "coordinates");
        }
    }
    
    private void testFaceNormals(Point3i[] vertices, String method) {
        System.out.println("\nTesting face normals with " + method + ":");
        
        // Convert to Point3f
        Point3f[] v = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            v[i] = new Point3f(vertices[i].x, vertices[i].y, vertices[i].z);
        }
        
        // Calculate centroid
        Point3f centroid = new Point3f(
            (v[0].x + v[1].x + v[2].x + v[3].x) / 4,
            (v[0].y + v[1].y + v[2].y + v[3].y) / 4,
            (v[0].z + v[1].z + v[2].z + v[3].z) / 4
        );
        
        // Test each face with the winding order from TetreeDemo
        testFace(v[0], v[2], v[1], v[3], centroid, "0-2-1 (opp. v3)");
        testFace(v[0], v[1], v[3], v[2], centroid, "0-1-3 (opp. v2)");
        testFace(v[0], v[3], v[2], v[1], centroid, "0-3-2 (opp. v1)");
        testFace(v[1], v[2], v[3], v[0], centroid, "1-2-3 (opp. v0)");
    }
    
    private void testFace(Point3f a, Point3f b, Point3f c, Point3f opposite, 
                         Point3f centroid, String faceName) {
        Vector3f normal = computeFaceNormal(a, b, c);
        
        // Vector from face to centroid
        Vector3f toCentroid = new Vector3f();
        toCentroid.sub(centroid, a);
        
        // Vector from face to opposite vertex
        Vector3f toOpposite = new Vector3f();
        toOpposite.sub(opposite, a);
        
        float dotCentroid = normal.dot(toCentroid);
        float dotOpposite = normal.dot(toOpposite);
        
        System.out.printf("  Face %s: normal·toCentroid=%.3f, normal·toOpposite=%.3f %s\n",
            faceName, dotCentroid, dotOpposite,
            (dotCentroid < 0) ? "✓ (outward)" : "✗ (inward)");
    }
    
    private Vector3f computeFaceNormal(Point3f a, Point3f b, Point3f c) {
        Vector3f ab = new Vector3f();
        ab.sub(b, a);
        
        Vector3f ac = new Vector3f();
        ac.sub(c, a);
        
        Vector3f normal = new Vector3f();
        normal.cross(ab, ac);
        normal.normalize();
        
        return normal;
    }
}
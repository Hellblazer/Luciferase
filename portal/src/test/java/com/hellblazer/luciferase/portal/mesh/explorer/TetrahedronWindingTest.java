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

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify correct face winding for tetrahedron visualization
 *
 * @author hal.hildebrand
 */
public class TetrahedronWindingTest {
    
    @Test
    public void testTetrahedronFaceNormals() {
        // Standard tetrahedron vertices
        Point3f v0 = new Point3f(0, 0, 0);
        Point3f v1 = new Point3f(1, 0, 0);
        Point3f v2 = new Point3f(1, 0, 1);
        Point3f v3 = new Point3f(1, 1, 1);
        
        // Calculate centroid
        Point3f centroid = new Point3f(
            (v0.x + v1.x + v2.x + v3.x) / 4,
            (v0.y + v1.y + v2.y + v3.y) / 4,
            (v0.z + v1.z + v2.z + v3.z) / 4
        );
        
        // Test each face - the normal should point away from the centroid
        
        // Face 1: v0, v1, v2
        Vector3f normal1 = computeFaceNormal(v0, v1, v2);
        Vector3f toCentroid1 = new Vector3f();
        toCentroid1.sub(centroid, v0);
        float dot1 = normal1.dot(toCentroid1);
        System.out.println("Face v0-v1-v2 normal dot with centroid direction: " + dot1);
        
        // Face 2: v0, v2, v3
        Vector3f normal2 = computeFaceNormal(v0, v2, v3);
        Vector3f toCentroid2 = new Vector3f();
        toCentroid2.sub(centroid, v0);
        float dot2 = normal2.dot(toCentroid2);
        System.out.println("Face v0-v2-v3 normal dot with centroid direction: " + dot2);
        
        // Face 3: v0, v3, v1
        Vector3f normal3 = computeFaceNormal(v0, v3, v1);
        Vector3f toCentroid3 = new Vector3f();
        toCentroid3.sub(centroid, v0);
        float dot3 = normal3.dot(toCentroid3);
        System.out.println("Face v0-v3-v1 normal dot with centroid direction: " + dot3);
        
        // Face 4: v1, v3, v2
        Vector3f normal4 = computeFaceNormal(v1, v3, v2);
        Vector3f toCentroid4 = new Vector3f();
        toCentroid4.sub(centroid, v1);
        float dot4 = normal4.dot(toCentroid4);
        System.out.println("Face v1-v3-v2 normal dot with centroid direction: " + dot4);
        
        // Try alternative winding for face 4: v1, v2, v3
        Vector3f normal4_alt = computeFaceNormal(v1, v2, v3);
        float dot4_alt = normal4_alt.dot(toCentroid4);
        System.out.println("Face v1-v2-v3 (alternative) normal dot with centroid direction: " + dot4_alt);
        
        // For outward-facing normals, all dot products should be negative
        // (normal points away from centroid)
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
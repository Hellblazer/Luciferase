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

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that points are correctly contained within Tet type-0 bounds (RDR-010 Luciferase-4pd:
 * t8code dtet labeling — type 0 has vertices V0=(0,0,0), V1=(max,0,0), V5=(max,0,max), V7=(max,max,max);
 * ordering x ≥ z ≥ y).
 *
 * @author hal.hildebrand
 */
public class S0ContainmentTest {

    @Test
    public void testS0Vertices() {
        // Type 0 at level 0 should have vertices at corners of the space (t8code dtet)
        int maxCoord = 1 << MortonCurve.MAX_REFINEMENT_LEVEL;

        // Tet.coordinates() for type 0: cube vertices 0, 1, 5, 7
        // V0 = (0,0,0), V1 = (h,0,0), V5 = (h,0,h), V7 = (h,h,h)
        Point3i[] expectedVertices = new Point3i[] {
            new Point3i(0, 0, 0),                        // V0
            new Point3i(maxCoord, 0, 0),                 // V1
            new Point3i(maxCoord, 0, maxCoord),          // V5
            new Point3i(maxCoord, maxCoord, maxCoord)    // V7
        };

        Tet s0 = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        Point3i[] actualVertices = s0.coordinates();

        for (int i = 0; i < 4; i++) {
            assertEquals(expectedVertices[i].x, actualVertices[i].x,
                String.format("Vertex %d X mismatch", i));
            assertEquals(expectedVertices[i].y, actualVertices[i].y,
                String.format("Vertex %d Y mismatch", i));
            assertEquals(expectedVertices[i].z, actualVertices[i].z,
                String.format("Vertex %d Z mismatch", i));
        }
    }

    @Test
    public void testPointContainment() {
        int maxCoord = 1 << MortonCurve.MAX_REFINEMENT_LEVEL;

        // Test points that should be inside type 0 (ordering x ≥ z ≥ y)
        assertTrue(isPointInS0(new Point3f(0, 0, 0)), "Origin should be in type 0");
        assertTrue(isPointInS0(new Point3f(maxCoord / 4.0f, maxCoord / 4.0f, maxCoord / 4.0f)),
            "Diagonal point should be in type 0");
        assertTrue(isPointInS0(new Point3f(maxCoord * 0.9f, 0, 0)), "Point near V1 should be in type 0");
        assertTrue(isPointInS0(new Point3f(maxCoord * 0.9f, maxCoord * 0.9f, maxCoord * 0.9f)),
            "Point near V7 should be in type 0");

        // Test points that should be outside type 0
        assertFalse(isPointInS0(new Point3f(0, maxCoord, 0)), "(0,max,0) should be outside type 0");
        assertFalse(isPointInS0(new Point3f(0, 0, maxCoord)), "(0,0,max) should be outside type 0");
        assertFalse(isPointInS0(new Point3f(0, maxCoord, maxCoord)), "(0,max,max) should be outside type 0");

        // Points in other tetrahedra (ordering not x ≥ z ≥ y) should be outside type 0
        assertFalse(isPointInS0(new Point3f(maxCoord * 0.1f, maxCoord * 0.9f, maxCoord * 0.1f)),
            "Point in another tetrahedron should be outside type 0");
        assertFalse(isPointInS0(new Point3f(maxCoord * 0.1f, maxCoord * 0.9f, maxCoord * 0.5f)),
            "Point in another tetrahedron should be outside type 0");
    }

    @Test
    public void testGenerationMethod() {
        int maxCoord = 1 << MortonCurve.MAX_REFINEMENT_LEVEL;

        // Test the barycentric coordinate method
        for (int i = 0; i < 100; i++) {
            float t0 = (float) Math.random();
            float t1 = (float) Math.random() * (1 - t0);
            float t2 = (float) Math.random() * (1 - t0 - t1);
            float t3 = 1 - t0 - t1 - t2;

            // Generate point using barycentric coordinates of type-0 vertices
            // type 0: V0=(0,0,0), V1=(max,0,0), V5=(max,0,max), V7=(max,max,max)
            float x = t0 * 0 + t1 * maxCoord + t2 * maxCoord + t3 * maxCoord;
            float y = t0 * 0 + t1 * 0       + t2 * 0       + t3 * maxCoord;
            float z = t0 * 0 + t1 * 0       + t2 * maxCoord + t3 * maxCoord;

            Point3f p = new Point3f(x, y, z);
            assertTrue(isPointInS0(p),
                String.format("Barycentric point (%.2f,%.2f,%.2f) should be in type 0", x, y, z));
        }
    }

    /**
     * Check if a point is inside Tet type 0 (t8code dtet labeling).
     * Vertices: V0=(0,0,0), V1=(max,0,0), V5=(max,0,max), V7=(max,max,max).
     */
    private boolean isPointInS0(Point3f p) {
        int maxCoord = 1 << MortonCurve.MAX_REFINEMENT_LEVEL;

        Point3f v0 = new Point3f(0, 0, 0);
        Point3f v1 = new Point3f(maxCoord, 0, 0);
        Point3f v5 = new Point3f(maxCoord, 0, maxCoord);
        Point3f v7 = new Point3f(maxCoord, maxCoord, maxCoord);

        return isPointInTetrahedron(p, v0, v1, v5, v7);
    }
    
    private boolean isPointInTetrahedron(Point3f p, Point3f a, Point3f b, Point3f c, Point3f d) {
        // Compute barycentric coordinates
        float v0x = b.x - a.x, v0y = b.y - a.y, v0z = b.z - a.z;
        float v1x = c.x - a.x, v1y = c.y - a.y, v1z = c.z - a.z;
        float v2x = d.x - a.x, v2y = d.y - a.y, v2z = d.z - a.z;
        float v3x = p.x - a.x, v3y = p.y - a.y, v3z = p.z - a.z;
        
        // Compute determinant
        float det = v0x * (v1y * v2z - v1z * v2y) - 
                   v0y * (v1x * v2z - v1z * v2x) + 
                   v0z * (v1x * v2y - v1y * v2x);
        
        if (Math.abs(det) < 1e-10) return false; // Degenerate tetrahedron
        
        // Compute barycentric coordinates
        float l1 = (v3x * (v1y * v2z - v1z * v2y) - 
                   v3y * (v1x * v2z - v1z * v2x) + 
                   v3z * (v1x * v2y - v1y * v2x)) / det;
        
        float l2 = (v0x * (v3y * v2z - v3z * v2y) - 
                   v0y * (v3x * v2z - v3z * v2x) + 
                   v0z * (v3x * v2y - v3y * v2x)) / det;
        
        float l3 = (v0x * (v1y * v3z - v1z * v3y) - 
                   v0y * (v1x * v3z - v1z * v3x) + 
                   v0z * (v1x * v3y - v1y * v3x)) / det;
        
        float l0 = 1 - l1 - l2 - l3;
        
        // Point is inside if all barycentric coordinates are non-negative
        return l0 >= 0 && l1 >= 0 && l2 >= 0 && l3 >= 0;
    }
}
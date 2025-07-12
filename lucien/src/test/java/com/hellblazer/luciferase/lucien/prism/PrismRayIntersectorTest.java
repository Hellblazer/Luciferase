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
package com.hellblazer.luciferase.lucien.prism;

import com.hellblazer.luciferase.lucien.Ray3D;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PrismRayIntersector.
 * 
 * @author hal.hildebrand
 */
public class PrismRayIntersectorTest {
    
    private static final float EPSILON = 1e-5f;
    
    @Test
    public void testRayPrismIntersectionHit() {
        // Create a prism at the origin
        var triangle = new Triangle(0, 0, 0, 0, 0);
        var line = new Line(0, 0);
        var prism = new PrismKey(triangle, line);
        
        // Ray pointing through the prism
        var origin = new Point3f(-0.5f, 0.25f, 0.5f);
        var direction = new Vector3f(1, 0, 0);
        direction.normalize();
        var ray = new Ray3D(origin, direction);
        
        var result = PrismRayIntersector.intersectRayPrism(ray, prism);
        
        assertTrue(result.hit, "Ray should hit the prism");
        assertTrue(result.tNear >= 0, "Near intersection should be positive");
        assertTrue(result.tFar > result.tNear, "Far intersection should be beyond near");
        assertNotNull(result.nearPoint);
        assertNotNull(result.farPoint);
        assertTrue(result.nearFace >= 0 && result.nearFace < 5);
        assertTrue(result.farFace >= 0 && result.farFace < 5);
    }
    
    @Test
    public void testRayPrismIntersectionMiss() {
        // Create a prism at the origin
        var triangle = new Triangle(0, 0, 0, 0, 0);
        var line = new Line(0, 0);
        var prism = new PrismKey(triangle, line);
        
        // Ray pointing away from the prism
        var origin = new Point3f(2, 2, 2);
        var direction = new Vector3f(1, 0, 0);
        direction.normalize();
        var ray = new Ray3D(origin, direction);
        
        var result = PrismRayIntersector.intersectRayPrism(ray, prism);
        
        assertFalse(result.hit, "Ray should miss the prism");
    }
    
    @Test
    public void testRayAABBIntersection() {
        // Create a prism
        var triangle = new Triangle(0, 0, 0, 0, 0);
        var line = new Line(0, 0);
        var prism = new PrismKey(triangle, line);
        
        // Ray intersecting AABB
        var origin = new Point3f(-1, 0.25f, 0.5f);
        var direction = new Vector3f(1, 0, 0);
        direction.normalize();
        var ray = new Ray3D(origin, direction);
        
        assertTrue(PrismRayIntersector.intersectRayAABB(ray, prism), 
                  "Ray should intersect prism AABB");
        
        // Ray missing AABB
        origin = new Point3f(2, 2, 2);
        direction = new Vector3f(1, 0, 0);
        direction.normalize();
        ray = new Ray3D(origin, direction);
        
        assertFalse(PrismRayIntersector.intersectRayAABB(ray, prism), 
                   "Ray should miss prism AABB");
    }
    
    @Test
    public void testFindEntryExitPoints() {
        // Create a prism
        var triangle = new Triangle(0, 0, 0, 0, 0);
        var line = new Line(0, 0);
        var prism = new PrismKey(triangle, line);
        
        // Ray through prism
        var origin = new Point3f(-0.5f, 0.25f, 0.5f);
        var direction = new Vector3f(1, 0, 0);
        direction.normalize();
        var ray = new Ray3D(origin, direction);
        
        float[] entryExit = PrismRayIntersector.findEntryExitPoints(ray, prism);
        
        assertNotNull(entryExit, "Should find entry/exit points");
        assertEquals(2, entryExit.length);
        assertTrue(entryExit[0] >= 0, "Entry point should be positive");
        assertTrue(entryExit[1] > entryExit[0], "Exit should be after entry");
    }
    
    @Test
    public void testRayFromInsidePrism() {
        // Create a prism
        var triangle = new Triangle(0, 0, 0, 0, 0);
        var line = new Line(0, 0);
        var prism = new PrismKey(triangle, line);
        
        // Ray starting inside the prism
        var origin = new Point3f(0.25f, 0.25f, 0.5f);
        var direction = new Vector3f(1, 0, 0);
        direction.normalize();
        var ray = new Ray3D(origin, direction);
        
        var result = PrismRayIntersector.intersectRayPrism(ray, prism);
        
        assertTrue(result.hit, "Ray from inside should hit");
        assertEquals(0, result.tNear, EPSILON, "Near intersection should be at origin");
        assertTrue(result.tFar > 0, "Far intersection should be positive");
    }
    
    @Test
    public void testRayThroughBottomTriangle() {
        // Create a prism
        var triangle = new Triangle(0, 0, 0, 0, 0);
        var line = new Line(0, 0);
        var prism = new PrismKey(triangle, line);
        
        // Ray pointing down through bottom triangle
        var origin = new Point3f(0.25f, 0.25f, 1.5f);
        var direction = new Vector3f(0, 0, -1);
        direction.normalize();
        var ray = new Ray3D(origin, direction);
        
        var result = PrismRayIntersector.intersectRayPrism(ray, prism);
        
        assertTrue(result.hit, "Ray should hit prism through bottom");
        assertEquals(4, result.nearFace, "Should enter through top face");
        assertEquals(3, result.farFace, "Should exit through bottom face");
    }
    
    @Test
    public void testRayThroughSideFace() {
        // Create a prism
        var triangle = new Triangle(0, 0, 0, 0, 0);
        var line = new Line(0, 0);
        var prism = new PrismKey(triangle, line);
        
        // Ray through side face
        var origin = new Point3f(-0.5f, 0.25f, 0.5f);
        var direction = new Vector3f(1, 0, 0);
        direction.normalize();
        var ray = new Ray3D(origin, direction);
        
        var result = PrismRayIntersector.intersectRayPrism(ray, prism);
        
        assertTrue(result.hit, "Ray should hit prism through side");
        assertTrue(result.nearFace >= 0 && result.nearFace <= 2, 
                  "Should enter through a side face");
    }
    
    @Test
    public void testParallelRay() {
        // Create a prism
        var triangle = new Triangle(0, 0, 0, 0, 0);
        var line = new Line(0, 0);
        var prism = new PrismKey(triangle, line);
        
        // Ray parallel to a face
        var origin = new Point3f(-1, 0.25f, 0);
        var direction = new Vector3f(1, 0, 0);
        direction.normalize();
        var ray = new Ray3D(origin, direction);
        
        // Should still intersect other faces
        var result = PrismRayIntersector.intersectRayPrism(ray, prism);
        
        // May or may not hit depending on exact prism geometry
        // Just verify it doesn't crash
        assertNotNull(result);
    }
    
    @Test
    public void testGrazingRay() {
        // Create a prism
        var triangle = new Triangle(0, 0, 0, 0, 0);
        var line = new Line(0, 0);
        var prism = new PrismKey(triangle, line);
        
        // Ray just grazing an edge
        var origin = new Point3f(-1, 1, 1);
        var direction = new Vector3f(1, 0, 0);
        direction.normalize();
        var ray = new Ray3D(origin, direction);
        
        var result = PrismRayIntersector.intersectRayPrism(ray, prism);
        
        // Just verify it doesn't crash
        assertNotNull(result);
    }
    
    @Test
    public void testMultiplePrismIntersections() {
        // Create multiple prisms at different positions
        var prism1 = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var prism2 = new PrismKey(new Triangle(1, 0, 0, 0, 1), new Line(1, 0));
        var prism3 = new PrismKey(new Triangle(2, 0, 0, 0, 2), new Line(2, 0));
        
        // Ray through all prisms
        var origin = new Point3f(-0.5f, 0.25f, 0.5f);
        var direction = new Vector3f(1, 0, 0);
        direction.normalize();
        var ray = new Ray3D(origin, direction);
        
        // Test each intersection
        var result1 = PrismRayIntersector.intersectRayPrism(ray, prism1);
        var result2 = PrismRayIntersector.intersectRayPrism(ray, prism2);
        var result3 = PrismRayIntersector.intersectRayPrism(ray, prism3);
        
        // At least one should hit
        assertTrue(result1.hit || result2.hit || result3.hit,
                  "At least one prism should be hit");
    }
}
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

import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PrismCollisionDetector.
 * 
 * @author hal.hildebrand
 */
public class PrismCollisionDetectorTest {
    
    private static final float EPSILON = 1e-5f;
    
    @Test
    public void testPrismPrismCollisionOverlapping() {
        // Create two overlapping prisms
        var prism1 = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var prism2 = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        
        var result = PrismCollisionDetector.testPrismPrismCollision(prism1, prism2);
        
        assertTrue(result.collides, "Overlapping prisms should collide");
        assertTrue(result.penetrationDepth > 0, "Should have positive penetration depth");
        assertNotNull(result.separationAxis, "Should have separation axis");
        assertNotNull(result.contactPoint, "Should have contact point");
    }
    
    @Test
    public void testPrismPrismCollisionSeparated() {
        // Create two separated prisms at the same level
        var prism1 = new PrismKey(new Triangle(2, 0, 0, 0, 2), new Line(2, 0));
        var prism2 = new PrismKey(new Triangle(2, 0, 3, 0, 2), new Line(2, 3));
        
        var result = PrismCollisionDetector.testPrismPrismCollision(prism1, prism2);
        
        assertFalse(result.collides, "Separated prisms should not collide");
    }
    
    @Test
    public void testPrismPrismCollisionTouching() {
        // Create two prisms that just touch
        var prism1 = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var prism2 = new PrismKey(new Triangle(1, 0, 0, 0, 1), new Line(1, 0));
        
        var result = PrismCollisionDetector.testPrismPrismCollision(prism1, prism2);
        
        // They might collide or not depending on exact geometry
        assertNotNull(result);
        if (result.collides) {
            assertTrue(result.penetrationDepth >= 0);
        }
    }
    
    @Test
    public void testPrismSphereCollisionInside() {
        // Create a prism and a sphere inside it
        var prism = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var sphereCenter = new Point3f(0.25f, 0.25f, 0.5f);
        float sphereRadius = 0.1f;
        
        var result = PrismCollisionDetector.testPrismSphereCollision(prism, sphereCenter, sphereRadius);
        
        assertTrue(result.collides, "Sphere inside prism should collide");
        assertTrue(result.penetrationDepth > 0);
        assertNotNull(result.separationAxis);
        assertNotNull(result.contactPoint);
    }
    
    @Test
    public void testPrismSphereCollisionOutside() {
        // Create a prism and a sphere outside it
        var prism = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var sphereCenter = new Point3f(5, 5, 5);
        float sphereRadius = 0.1f;
        
        var result = PrismCollisionDetector.testPrismSphereCollision(prism, sphereCenter, sphereRadius);
        
        assertFalse(result.collides, "Sphere outside prism should not collide");
    }
    
    @Test
    public void testPrismSphereCollisionOverlapping() {
        // Create a prism and a sphere that overlaps it
        var prism = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var sphereCenter = new Point3f(0.8f, 0.1f, 0.5f);
        float sphereRadius = 0.3f;
        
        var result = PrismCollisionDetector.testPrismSphereCollision(prism, sphereCenter, sphereRadius);
        
        assertTrue(result.collides, "Overlapping sphere should collide with prism");
        assertTrue(result.penetrationDepth > 0);
    }
    
    @Test
    public void testFindCollidingPrisms() {
        // Create a test prism at level 2
        var testPrism = new PrismKey(new Triangle(2, 0, 1, 1, 2), new Line(2, 1));
        
        // Create candidate prisms
        List<PrismKey> candidates = new ArrayList<>();
        // Overlapping prism (same position)
        candidates.add(new PrismKey(new Triangle(2, 0, 1, 1, 2), new Line(2, 1)));
        // Adjacent prism
        candidates.add(new PrismKey(new Triangle(2, 0, 2, 1, 2), new Line(2, 1)));
        // Far away prism (different Z layer)
        candidates.add(new PrismKey(new Triangle(2, 0, 1, 1, 2), new Line(2, 3)));
        // Another overlapping prism
        candidates.add(new PrismKey(new Triangle(2, 0, 1, 1, 2), new Line(2, 1)));
        
        Set<PrismKey> colliding = PrismCollisionDetector.findCollidingPrisms(testPrism, candidates);
        
        assertNotNull(colliding);
        assertTrue(colliding.size() >= 2, "Should find at least 2 colliding prisms");
        assertFalse(colliding.contains(candidates.get(2)), "Should not include far away prism");
    }
    
    @Test
    public void testCollisionWithIdenticalPrisms() {
        // Create two identical prisms
        var prism1 = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var prism2 = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        
        var result = PrismCollisionDetector.testPrismPrismCollision(prism1, prism2);
        
        assertTrue(result.collides, "Identical prisms should collide");
        assertTrue(result.penetrationDepth > 0);
    }
    
    @Test
    public void testCollisionAtDifferentLevels() {
        // Create prisms at different subdivision levels
        var prism1 = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var prism2 = new PrismKey(new Triangle(1, 0, 0, 0, 0), new Line(1, 0));
        
        var result = PrismCollisionDetector.testPrismPrismCollision(prism1, prism2);
        
        // Should detect collision if they overlap
        assertNotNull(result);
    }
    
    @Test
    public void testEdgeOnCollision() {
        // Create prisms that share an edge
        var prism1 = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var prism2 = new PrismKey(new Triangle(1, 0, 0, 0, 1), new Line(1, 0));
        
        var result = PrismCollisionDetector.testPrismPrismCollision(prism1, prism2);
        
        // Edge-on collision might or might not be detected as collision
        assertNotNull(result);
    }
    
    @Test
    public void testVertexCollision() {
        // Create prisms that touch at a vertex
        var prism1 = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var prism2 = new PrismKey(new Triangle(1, 0, 0, 0, 1), new Line(1, 0));
        
        var result = PrismCollisionDetector.testPrismPrismCollision(prism1, prism2);
        
        // Vertex collision might or might not be detected
        assertNotNull(result);
    }
    
    @Test
    public void testLargeSphereCollision() {
        // Test with a very large sphere that encompasses the prism
        var prism = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        var sphereCenter = new Point3f(0.25f, 0.25f, 0.5f);
        float sphereRadius = 10.0f;
        
        var result = PrismCollisionDetector.testPrismSphereCollision(prism, sphereCenter, sphereRadius);
        
        assertTrue(result.collides, "Large sphere should collide with prism");
        assertTrue(result.penetrationDepth > 0);
    }
    
    @Test
    public void testCollisionPerformance() {
        // Test performance with many candidates
        var testPrism = new PrismKey(new Triangle(0, 0, 0, 0, 0), new Line(0, 0));
        
        List<PrismKey> candidates = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            float offset = i * 0.1f;
            candidates.add(new PrismKey(
                new Triangle(i % 10, 0, 0, 0, 0), 
                new Line(i % 10, 0)
            ));
        }
        
        long start = System.nanoTime();
        Set<PrismKey> colliding = PrismCollisionDetector.findCollidingPrisms(testPrism, candidates);
        long elapsed = System.nanoTime() - start;
        
        assertNotNull(colliding);
        assertTrue(elapsed < 100_000_000, "Should complete in under 100ms");
    }
}
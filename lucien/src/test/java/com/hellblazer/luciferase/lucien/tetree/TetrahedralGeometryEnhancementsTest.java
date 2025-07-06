package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import org.junit.jupiter.api.Test;
import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the enhanced geometric utilities in TetrahedralGeometry
 */
public class TetrahedralGeometryEnhancementsTest {
    
    private static final float EPSILON = 1e-6f;
    
    @Test
    void testContainsPoint() {
        // Create a simple tetrahedron
        Point3f[] vertices = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0),
            new Point3f(0, 0, 100)
        };
        
        // Test center point (should be inside)
        Point3f center = new Point3f(25, 25, 25);
        assertTrue(TetrahedralGeometry.containsPoint(center, vertices),
            "Center point should be inside tetrahedron");
        
        // Test vertex (should be inside/on boundary)
        assertTrue(TetrahedralGeometry.containsPoint(vertices[0], vertices),
            "Vertex should be inside tetrahedron");
        
        // Test point outside
        Point3f outside = new Point3f(200, 200, 200);
        assertFalse(TetrahedralGeometry.containsPoint(outside, vertices),
            "Far point should be outside tetrahedron");
        
        // Test point near face
        Point3f nearFace = new Point3f(30, 30, 39);
        assertTrue(TetrahedralGeometry.containsPoint(nearFace, vertices),
            "Point near face should be inside");
        
        // Test point just outside face
        Point3f justOutside = new Point3f(35, 35, 31);
        assertFalse(TetrahedralGeometry.containsPoint(justOutside, vertices),
            "Point just outside face should be outside");
    }
    
    @Test
    void testContainsPointDegenerateTetrahedron() {
        // Create a degenerate tetrahedron (all points coplanar)
        Point3f[] vertices = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0),
            new Point3f(50, 50, 0)
        };
        
        Point3f testPoint = new Point3f(25, 25, 0);
        assertFalse(TetrahedralGeometry.containsPoint(testPoint, vertices),
            "Degenerate tetrahedron should return false");
    }
    
    @Test
    void testTetrahedraIntersect() {
        // Create two intersecting tetrahedra
        Point3f[] tet1 = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0),
            new Point3f(0, 0, 100)
        };
        
        // Create a tetrahedron that clearly overlaps with tet1
        // Using a point that's definitely inside tet1 (25, 25, 25)
        Point3f[] tet2 = new Point3f[] {
            new Point3f(25, 25, 25),  // Inside tet1
            new Point3f(125, 25, 25),
            new Point3f(25, 125, 25),
            new Point3f(25, 25, 125)
        };
        
        assertTrue(TetrahedralGeometry.tetrahedraIntersect(tet1, tet2),
            "Overlapping tetrahedra should intersect");
        
        // Create two non-intersecting tetrahedra
        Point3f[] tet3 = new Point3f[] {
            new Point3f(200, 200, 200),
            new Point3f(300, 200, 200),
            new Point3f(200, 300, 200),
            new Point3f(200, 200, 300)
        };
        
        assertFalse(TetrahedralGeometry.tetrahedraIntersect(tet1, tet3),
            "Non-overlapping tetrahedra should not intersect");
        
        // Test edge case: touching at a vertex
        Point3f[] tet4 = new Point3f[] {
            new Point3f(100, 0, 0),  // Shares vertex with tet1
            new Point3f(200, 0, 0),
            new Point3f(100, 100, 0),
            new Point3f(100, 0, 100)
        };
        
        assertTrue(TetrahedralGeometry.tetrahedraIntersect(tet1, tet4),
            "Tetrahedra sharing a vertex should intersect");
    }
    
    @Test
    void testAABBIntersectsTetrahedron() {
        // Create a tetrahedron
        Point3f[] vertices = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0),
            new Point3f(0, 0, 100)
        };
        
        // Test AABB completely inside
        EntityBounds insideBounds = new EntityBounds(new Point3f(20, 20, 20), new Point3f(30, 30, 30));
        assertTrue(TetrahedralGeometry.aabbIntersectsTetrahedron(insideBounds, vertices),
            "AABB inside tetrahedron should intersect");
        
        // Test AABB completely outside
        EntityBounds outsideBounds = new EntityBounds(new Point3f(200, 200, 200), new Point3f(300, 300, 300));
        assertFalse(TetrahedralGeometry.aabbIntersectsTetrahedron(outsideBounds, vertices),
            "AABB outside tetrahedron should not intersect");
        
        // Test AABB partially overlapping
        EntityBounds partialBounds = new EntityBounds(new Point3f(-10, -10, -10), new Point3f(50, 50, 50));
        assertTrue(TetrahedralGeometry.aabbIntersectsTetrahedron(partialBounds, vertices),
            "AABB partially overlapping should intersect");
        
        // Test AABB containing entire tetrahedron
        EntityBounds containingBounds = new EntityBounds(new Point3f(-10, -10, -10), new Point3f(110, 110, 110));
        assertTrue(TetrahedralGeometry.aabbIntersectsTetrahedron(containingBounds, vertices),
            "AABB containing tetrahedron should intersect");
        
        // Test AABB touching edge
        EntityBounds edgeBounds = new EntityBounds(new Point3f(45, 45, -10), new Point3f(55, 55, 10));
        assertTrue(TetrahedralGeometry.aabbIntersectsTetrahedron(edgeBounds, vertices),
            "AABB touching edge should intersect");
    }
    
    @Test
    void testAABBIntersectsTetrahedronInvalidInput() {
        Point3f[] invalidVertices = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0)
        };
        
        EntityBounds bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(50, 50, 50));
        
        assertThrows(IllegalArgumentException.class, 
            () -> TetrahedralGeometry.aabbIntersectsTetrahedron(bounds, invalidVertices),
            "Should throw exception for invalid vertex count");
    }
    
    @Test
    void testContainsPointInvalidInput() {
        Point3f[] invalidVertices = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0)
        };
        
        Point3f point = new Point3f(25, 25, 25);
        
        assertThrows(IllegalArgumentException.class, 
            () -> TetrahedralGeometry.containsPoint(point, invalidVertices),
            "Should throw exception for invalid vertex count");
    }
    
    @Test
    void testTetrahedraIntersectInvalidInput() {
        Point3f[] validTet = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0),
            new Point3f(0, 0, 100)
        };
        
        Point3f[] invalidTet = new Point3f[] {
            new Point3f(0, 0, 0),
            new Point3f(100, 0, 0),
            new Point3f(0, 100, 0)
        };
        
        assertThrows(IllegalArgumentException.class, 
            () -> TetrahedralGeometry.tetrahedraIntersect(validTet, invalidTet),
            "Should throw exception for invalid vertex count");
    }
}
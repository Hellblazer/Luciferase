/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.lucien.collision;

import com.hellblazer.luciferase.lucien.Ray3D;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConvexHullShape collision detection.
 *
 * @author hal.hildebrand
 */
public class ConvexHullShapeTest {
    
    private static final float EPSILON = 0.001f;
    private List<Point3f> tetrahedronVertices;
    private List<Point3f> boxVertices;
    
    @BeforeEach
    void setUp() {
        // Create tetrahedron vertices
        tetrahedronVertices = Arrays.asList(
            new Point3f(0, 0, 0),
            new Point3f(10, 0, 0),
            new Point3f(5, 10, 0),
            new Point3f(5, 5, 10)
        );
        
        // Create box vertices
        boxVertices = Arrays.asList(
            new Point3f(-5, -5, -5),
            new Point3f(5, -5, -5),
            new Point3f(5, 5, -5),
            new Point3f(-5, 5, -5),
            new Point3f(-5, -5, 5),
            new Point3f(5, -5, 5),
            new Point3f(5, 5, 5),
            new Point3f(-5, 5, 5)
        );
    }
    
    @Test
    void testConvexHullCreation() {
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), tetrahedronVertices);
        
        assertEquals(4, hull.getVertices().size());
        assertNotNull(hull.getFaces());
        assertNotNull(hull.getAABB());
        
        // Test centroid
        var centroid = hull.getCentroid();
        assertNotNull(centroid);
        // Centroid of tetrahedron should be average of vertices
        assertEquals(5.0f, centroid.x, EPSILON);
        assertEquals(3.75f, centroid.y, EPSILON);
        assertEquals(2.5f, centroid.z, EPSILON);
    }
    
    @Test
    void testConvexHullBounds() {
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), tetrahedronVertices);
        var bounds = hull.getAABB();
        
        assertEquals(0.0f, bounds.getMinX(), EPSILON);
        assertEquals(0.0f, bounds.getMinY(), EPSILON);
        assertEquals(0.0f, bounds.getMinZ(), EPSILON);
        assertEquals(10.0f, bounds.getMaxX(), EPSILON);
        assertEquals(10.0f, bounds.getMaxY(), EPSILON);
        assertEquals(10.0f, bounds.getMaxZ(), EPSILON);
    }
    
    @Test
    void testConvexHullTranslation() {
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), tetrahedronVertices);
        hull.translate(new Vector3f(10, 5, 3));
        
        var bounds = hull.getAABB();
        assertEquals(10.0f, bounds.getMinX(), EPSILON);
        assertEquals(5.0f, bounds.getMinY(), EPSILON);
        assertEquals(3.0f, bounds.getMinZ(), EPSILON);
        assertEquals(20.0f, bounds.getMaxX(), EPSILON);
        assertEquals(15.0f, bounds.getMaxY(), EPSILON);
        assertEquals(13.0f, bounds.getMaxZ(), EPSILON);
        
        // Check that vertices are translated
        var vertices = hull.getVertices();
        assertEquals(10.0f, vertices.get(0).x, EPSILON);
        assertEquals(5.0f, vertices.get(0).y, EPSILON);
        assertEquals(3.0f, vertices.get(0).z, EPSILON);
    }
    
    @Test
    void testConvexHullVsSphereCollision() {
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), boxVertices);
        
        // Sphere inside hull
        var sphere1 = new SphereShape(new Point3f(0, 0, 0), 3.0f);
        var result1 = hull.collidesWith(sphere1);
        assertTrue(result1.collides, "Sphere inside convex hull should collide");
        
        // Sphere outside hull
        var sphere2 = new SphereShape(new Point3f(20, 0, 0), 3.0f);
        var result2 = hull.collidesWith(sphere2);
        assertFalse(result2.collides, "Distant sphere should not collide with hull");
        
        // Sphere touching hull face
        var sphere3 = new SphereShape(new Point3f(8, 0, 0), 3.0f);
        var result3 = hull.collidesWith(sphere3);
        assertTrue(result3.collides, "Sphere touching hull should collide");
    }
    
    @Test
    void testConvexHullVsBoxCollision() {
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), tetrahedronVertices);
        
        // Box overlapping hull
        var box1 = new BoxShape(new Point3f(5, 5, 5), new Vector3f(3, 3, 3));
        var result1 = hull.collidesWith(box1);
        assertTrue(result1.collides, "Box should collide with convex hull");
        
        // Box not overlapping
        var box2 = new BoxShape(new Point3f(30, 0, 0), new Vector3f(3, 3, 3));
        var result2 = hull.collidesWith(box2);
        assertFalse(result2.collides, "Distant box should not collide with hull");
    }
    
    @Test
    void testConvexHullVsCapsuleCollision() {
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), boxVertices);
        
        // Capsule passing through hull
        var capsule1 = new CapsuleShape(new Point3f(0, -10, 0), new Point3f(0, 10, 0), 2.0f);
        var result1 = hull.collidesWith(capsule1);
        assertTrue(result1.collides, "Capsule should collide with convex hull");
        
        // Capsule missing hull
        var capsule2 = new CapsuleShape(new Point3f(20, 0, 0), new Point3f(20, 10, 0), 2.0f);
        var result2 = hull.collidesWith(capsule2);
        assertFalse(result2.collides, "Distant capsule should not collide with hull");
    }
    
    @Test
    void testConvexHullVsConvexHullCollision() {
        var hull1 = new ConvexHullShape(new Point3f(0, 0, 0), tetrahedronVertices);
        var hull2 = new ConvexHullShape(new Point3f(5, 5, 5), tetrahedronVertices);
        
        var result = hull1.collidesWith(hull2);
        assertTrue(result.collides, "Overlapping convex hulls should collide");
        
        // Non-overlapping hulls
        var hull3 = new ConvexHullShape(new Point3f(50, 50, 50), tetrahedronVertices);
        var result2 = hull1.collidesWith(hull3);
        assertFalse(result2.collides, "Distant hulls should not collide");
    }
    
    @Test
    void testConvexHullVsMeshCollision() {
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), boxVertices);
        
        // Create a simple triangle mesh
        var meshData = new TriangleMeshData();
        meshData.addVertex(new Point3f(0, 0, 0));
        meshData.addVertex(new Point3f(10, 0, 0));
        meshData.addVertex(new Point3f(5, 10, 0));
        meshData.addTriangle(0, 1, 2);
        
        var mesh = new MeshShape(new Point3f(0, 0, 0), meshData);
        var result = hull.collidesWith(mesh);
        assertTrue(result.collides, "Hull should collide with intersecting mesh");
    }
    
    @Test
    void testRayIntersectionWithConvexHull() {
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), boxVertices);
        
        // Ray hitting hull
        var ray1 = new Ray3D(new Point3f(-10, 0, 0), new Vector3f(1, 0, 0));
        var result1 = hull.intersectRay(ray1);
        assertTrue(result1.intersects, "Ray should hit convex hull");
        assertEquals(5.0f, result1.distance, EPSILON);
        assertEquals(-5.0f, result1.intersectionPoint.x, EPSILON);
        
        // Ray missing hull
        var ray2 = new Ray3D(new Point3f(-10, 10, 10), new Vector3f(1, 0, 0));
        var result2 = hull.intersectRay(ray2);
        assertFalse(result2.intersects, "Ray should miss convex hull");
        
        // Ray from inside hull
        var ray3 = new Ray3D(new Point3f(0, 0, 0), new Vector3f(1, 0, 0));
        var result3 = hull.intersectRay(ray3);
        assertTrue(result3.intersects, "Ray from inside should hit hull");
    }
    
    @Test
    void testConvexHullSupport() {
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), boxVertices);
        
        // Support in X direction
        var supportX = hull.getSupport(new Vector3f(1, 0, 0));
        assertEquals(5.0f, supportX.x, EPSILON);
        
        // Support in negative X direction
        var supportNegX = hull.getSupport(new Vector3f(-1, 0, 0));
        assertEquals(-5.0f, supportNegX.x, EPSILON);
        
        // Support in diagonal direction
        var supportDiag = hull.getSupport(new Vector3f(1, 1, 1));
        assertEquals(5.0f, supportDiag.x, EPSILON);
        assertEquals(5.0f, supportDiag.y, EPSILON);
        assertEquals(5.0f, supportDiag.z, EPSILON);
    }
    
    @Test
    void testHullFaceGeneration() {
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), tetrahedronVertices);
        var faces = hull.getFaces();
        
        // Tetrahedron should have 4 faces
        assertEquals(4, faces.size());
        
        // Each face should have a valid normal
        for (var face : faces) {
            var normal = face.normal;
            assertNotNull(normal);
            // Normal should be unit length
            assertEquals(1.0f, normal.length(), EPSILON);
        }
    }
    
    @Test
    void testComplexConvexHull() {
        // Create an octahedron
        var octahedronVertices = Arrays.asList(
            new Point3f(10, 0, 0),   // +X
            new Point3f(-10, 0, 0),  // -X
            new Point3f(0, 10, 0),   // +Y
            new Point3f(0, -10, 0),  // -Y
            new Point3f(0, 0, 10),   // +Z
            new Point3f(0, 0, -10)   // -Z
        );
        
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), octahedronVertices);
        
        // Test support in various directions
        var supportX = hull.getSupport(new Vector3f(1, 0, 0));
        assertEquals(10.0f, supportX.x, EPSILON);
        assertEquals(0.0f, supportX.y, EPSILON);
        assertEquals(0.0f, supportX.z, EPSILON);
        
        var supportY = hull.getSupport(new Vector3f(0, 1, 0));
        assertEquals(0.0f, supportY.x, EPSILON);
        assertEquals(10.0f, supportY.y, EPSILON);
        assertEquals(0.0f, supportY.z, EPSILON);
        
        var supportZ = hull.getSupport(new Vector3f(0, 0, 1));
        assertEquals(0.0f, supportZ.x, EPSILON);
        assertEquals(0.0f, supportZ.y, EPSILON);
        assertEquals(10.0f, supportZ.z, EPSILON);
    }
    
    @Test
    void testDegenerateHull() {
        // Create a hull with only 3 vertices (degenerate - becomes a triangle)
        var triangleVertices = Arrays.asList(
            new Point3f(0, 0, 0),
            new Point3f(10, 0, 0),
            new Point3f(5, 10, 0)
        );
        
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), triangleVertices);
        
        // Should still work for collision detection
        var sphere = new SphereShape(new Point3f(5, 5, 0), 3.0f);
        var result = hull.collidesWith(sphere);
        assertTrue(result.collides, "Degenerate hull should still detect collisions");
    }
}
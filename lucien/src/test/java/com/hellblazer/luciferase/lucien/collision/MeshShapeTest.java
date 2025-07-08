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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MeshShape collision detection and BVH acceleration.
 *
 * @author hal.hildebrand
 */
public class MeshShapeTest {
    
    private static final float EPSILON = 0.001f;
    private TriangleMeshData triangleMesh;
    private TriangleMeshData cubeMesh;
    
    @BeforeEach
    void setUp() {
        // Create a simple triangle mesh (single triangle)
        triangleMesh = new TriangleMeshData();
        triangleMesh.addVertex(new Point3f(0, 0, 0));
        triangleMesh.addVertex(new Point3f(10, 0, 0));
        triangleMesh.addVertex(new Point3f(5, 10, 0));
        triangleMesh.addTriangle(0, 1, 2);
        
        // Create a cube mesh
        cubeMesh = createCubeMesh(new Point3f(0, 0, 0), 5.0f);
    }
    
    private TriangleMeshData createCubeMesh(Point3f center, float halfSize) {
        var mesh = new TriangleMeshData();
        
        // Define cube vertices
        var vertices = new Point3f[] {
            new Point3f(center.x - halfSize, center.y - halfSize, center.z - halfSize),
            new Point3f(center.x + halfSize, center.y - halfSize, center.z - halfSize),
            new Point3f(center.x + halfSize, center.y + halfSize, center.z - halfSize),
            new Point3f(center.x - halfSize, center.y + halfSize, center.z - halfSize),
            new Point3f(center.x - halfSize, center.y - halfSize, center.z + halfSize),
            new Point3f(center.x + halfSize, center.y - halfSize, center.z + halfSize),
            new Point3f(center.x + halfSize, center.y + halfSize, center.z + halfSize),
            new Point3f(center.x - halfSize, center.y + halfSize, center.z + halfSize)
        };
        
        for (var vertex : vertices) {
            mesh.addVertex(vertex);
        }
        
        // Define cube faces (2 triangles per face)
        // Front face
        mesh.addTriangle(0, 1, 2);
        mesh.addTriangle(0, 2, 3);
        // Back face
        mesh.addTriangle(4, 6, 5);
        mesh.addTriangle(4, 7, 6);
        // Left face
        mesh.addTriangle(0, 3, 7);
        mesh.addTriangle(0, 7, 4);
        // Right face
        mesh.addTriangle(1, 5, 6);
        mesh.addTriangle(1, 6, 2);
        // Top face
        mesh.addTriangle(3, 2, 6);
        mesh.addTriangle(3, 6, 7);
        // Bottom face
        mesh.addTriangle(0, 4, 5);
        mesh.addTriangle(0, 5, 1);
        
        return mesh;
    }
    
    @Test
    void testMeshCreation() {
        var mesh = new MeshShape(new Point3f(0, 0, 0), triangleMesh);
        
        assertEquals(3, mesh.getMeshData().getVertexCount());
        assertEquals(1, mesh.getMeshData().getTriangleCount());
        assertNotNull(mesh.getBVH());
        assertNotNull(mesh.getAABB());
    }
    
    @Test
    void testMeshBounds() {
        var mesh = new MeshShape(new Point3f(0, 0, 0), triangleMesh);
        var bounds = mesh.getAABB();
        
        assertEquals(0.0f, bounds.getMinX(), EPSILON);
        assertEquals(0.0f, bounds.getMinY(), EPSILON);
        assertEquals(0.0f, bounds.getMinZ(), EPSILON);
        assertEquals(10.0f, bounds.getMaxX(), EPSILON);
        assertEquals(10.0f, bounds.getMaxY(), EPSILON);
        assertEquals(0.0f, bounds.getMaxZ(), EPSILON);
    }
    
    @Test
    void testMeshTranslation() {
        var mesh = new MeshShape(new Point3f(0, 0, 0), triangleMesh);
        mesh.translate(new Vector3f(5, 5, 5));
        
        var bounds = mesh.getAABB();
        assertEquals(5.0f, bounds.getMinX(), EPSILON);
        assertEquals(5.0f, bounds.getMinY(), EPSILON);
        assertEquals(5.0f, bounds.getMinZ(), EPSILON);
        assertEquals(15.0f, bounds.getMaxX(), EPSILON);
        assertEquals(15.0f, bounds.getMaxY(), EPSILON);
        assertEquals(5.0f, bounds.getMaxZ(), EPSILON);
    }
    
    @Test
    void testMeshVsSphereCollision() {
        var mesh = new MeshShape(new Point3f(0, 0, 0), triangleMesh);
        
        // Sphere intersecting the triangle
        var sphere1 = new SphereShape(new Point3f(5, 5, 0), 3.0f);
        var result1 = mesh.collidesWith(sphere1);
        assertTrue(result1.collides, "Sphere should intersect triangle mesh");
        
        // Sphere not intersecting
        var sphere2 = new SphereShape(new Point3f(20, 20, 20), 3.0f);
        var result2 = mesh.collidesWith(sphere2);
        assertFalse(result2.collides, "Distant sphere should not intersect mesh");
        
        // Sphere just touching triangle edge
        var sphere3 = new SphereShape(new Point3f(5, 0, 3), 3.0f);
        var result3 = mesh.collidesWith(sphere3);
        assertTrue(result3.collides, "Sphere touching edge should collide");
    }
    
    @Test
    void testMeshVsBoxCollision() {
        // Use a simple triangle mesh instead
        var mesh = new MeshShape(new Point3f(0, 0, 0), triangleMesh);
        
        // Box overlapping triangle (triangle spans 0-10 in X, 0-10 in Y)
        var box1 = new BoxShape(new Point3f(5, 5, 0), new Vector3f(2, 2, 2));
        var result1 = box1.collidesWith(mesh);
        assertTrue(result1.collides, "Box should collide with triangle mesh");
        
        // Box not overlapping
        var box2 = new BoxShape(new Point3f(20, 0, 0), new Vector3f(3, 3, 3));
        var result2 = box2.collidesWith(mesh);
        assertFalse(result2.collides, "Distant box should not collide with mesh");
    }
    
    @Test
    void testMeshVsCapsuleCollision() {
        var mesh = new MeshShape(new Point3f(0, 0, 0), triangleMesh);
        
        // Capsule passing through triangle
        var capsule1 = new CapsuleShape(new Point3f(5, -5, 0), new Point3f(5, 15, 0), 2.0f);
        var result1 = mesh.collidesWith(capsule1);
        assertTrue(result1.collides, "Capsule should collide with triangle mesh");
        
        // Capsule missing triangle
        var capsule2 = new CapsuleShape(new Point3f(20, 0, 0), new Point3f(20, 10, 0), 2.0f);
        var result2 = mesh.collidesWith(capsule2);
        assertFalse(result2.collides, "Distant capsule should not collide with mesh");
    }
    
    @Test
    void testMeshVsMeshCollision() {
        var mesh1 = new MeshShape(new Point3f(0, 0, 0), triangleMesh);
        var mesh2 = new MeshShape(new Point3f(5, 5, 0), triangleMesh);
        
        var result = mesh1.collidesWith(mesh2);
        assertTrue(result.collides, "Overlapping meshes should collide");
        
        // Non-overlapping meshes
        var mesh3 = new MeshShape(new Point3f(50, 50, 50), triangleMesh);
        var result2 = mesh1.collidesWith(mesh3);
        assertFalse(result2.collides, "Distant meshes should not collide");
    }
    
    @Test
    void testRayIntersectionWithMesh() {
        var mesh = new MeshShape(new Point3f(0, 0, 0), triangleMesh);
        
        // Ray hitting triangle
        var ray1 = new Ray3D(new Point3f(5, 5, -10), new Vector3f(0, 0, 1));
        var result1 = mesh.intersectRay(ray1);
        assertTrue(result1.intersects, "Ray should hit triangle");
        assertEquals(10.0f, result1.distance, EPSILON);
        assertEquals(0.0f, result1.intersectionPoint.z, EPSILON);
        
        // Ray missing triangle
        var ray2 = new Ray3D(new Point3f(20, 20, -10), new Vector3f(0, 0, 1));
        var result2 = mesh.intersectRay(ray2);
        assertFalse(result2.intersects, "Ray should miss triangle");
        
        // Ray parallel to triangle
        var ray3 = new Ray3D(new Point3f(5, 5, 0), new Vector3f(1, 0, 0));
        var result3 = mesh.intersectRay(ray3);
        assertFalse(result3.intersects, "Ray parallel to triangle should not intersect");
    }
    
    @Test
    void testBVHAcceleration() {
        // Create a large mesh with many triangles
        var largeMesh = new TriangleMeshData();
        
        // Create a grid of triangles
        int gridSize = 10;
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                float x0 = x * 10.0f;
                float y0 = y * 10.0f;
                
                int v0 = largeMesh.addVertex(new Point3f(x0, y0, 0));
                int v1 = largeMesh.addVertex(new Point3f(x0 + 10, y0, 0));
                int v2 = largeMesh.addVertex(new Point3f(x0 + 10, y0 + 10, 0));
                int v3 = largeMesh.addVertex(new Point3f(x0, y0 + 10, 0));
                
                largeMesh.addTriangle(v0, v1, v2);
                largeMesh.addTriangle(v0, v2, v3);
            }
        }
        
        var meshShape = new MeshShape(new Point3f(0, 0, 0), largeMesh);
        
        // Test BVH query with small sphere
        var sphere = new SphereShape(new Point3f(15, 15, 0), 2.0f);
        var result = meshShape.collidesWith(sphere);
        assertTrue(result.collides, "Sphere should collide with mesh grid");
        
        // Verify BVH is working by checking triangles returned for small region
        var bvh = meshShape.getBVH();
        var smallBounds = sphere.getAABB();
        var trianglesInRegion = bvh.getTrianglesInAABB(smallBounds);
        
        // Should only return triangles near the sphere, not all 200 triangles
        assertTrue(trianglesInRegion.size() < 20, 
            "BVH should return only nearby triangles, got " + trianglesInRegion.size());
    }
    
    @Test
    void testMeshSupport() {
        var mesh = new MeshShape(new Point3f(0, 0, 0), triangleMesh);
        
        // Support in X direction should be rightmost vertex
        var supportX = mesh.getSupport(new Vector3f(1, 0, 0));
        assertEquals(10.0f, supportX.x, EPSILON);
        assertEquals(0.0f, supportX.y, EPSILON);
        
        // Support in Y direction should be top vertex
        var supportY = mesh.getSupport(new Vector3f(0, 1, 0));
        assertEquals(5.0f, supportY.x, EPSILON);
        assertEquals(10.0f, supportY.y, EPSILON);
        
        // Support in negative X direction should be leftmost vertex
        var supportNegX = mesh.getSupport(new Vector3f(-1, 0, 0));
        assertEquals(0.0f, supportNegX.x, EPSILON);
        assertEquals(0.0f, supportNegX.y, EPSILON);
    }
    
    @Test
    void testTriangleMeshDataOperations() {
        var mesh = new TriangleMeshData();
        
        // Test vertex addition
        int v0 = mesh.addVertex(new Point3f(0, 0, 0));
        int v1 = mesh.addVertex(new Point3f(1, 0, 0));
        int v2 = mesh.addVertex(new Point3f(0, 1, 0));
        
        assertEquals(0, v0);
        assertEquals(1, v1);
        assertEquals(2, v2);
        assertEquals(3, mesh.getVertexCount());
        
        // Test triangle addition
        mesh.addTriangle(v0, v1, v2);
        assertEquals(1, mesh.getTriangleCount());
        
        // Test vertex retrieval
        var vertex = mesh.getVertex(v1);
        assertEquals(1.0f, vertex.x, EPSILON);
        assertEquals(0.0f, vertex.y, EPSILON);
        
        // Test triangle retrieval
        var triangle = mesh.getTriangle(0);
        assertEquals(v0, triangle.v0);
        assertEquals(v1, triangle.v1);
        assertEquals(v2, triangle.v2);
        
        // Test bounds computation
        var bounds = mesh.getBounds();
        assertEquals(0.0f, bounds.getMinX(), EPSILON);
        assertEquals(1.0f, bounds.getMaxX(), EPSILON);
        assertEquals(1.0f, bounds.getMaxY(), EPSILON);
        
        // Test centroid computation
        var centroid = mesh.computeCentroid();
        assertEquals(1.0f/3.0f, centroid.x, EPSILON);
        assertEquals(1.0f/3.0f, centroid.y, EPSILON);
        assertEquals(0.0f, centroid.z, EPSILON);
    }
}
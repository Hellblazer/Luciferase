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
 * Tests for HeightmapShape terrain collision detection.
 *
 * @author hal.hildebrand
 */
public class HeightmapShapeTest {
    
    private static final float EPSILON = 0.001f;
    private float[][] flatTerrain;
    private float[][] slopedTerrain;
    private float[][] valleyTerrain;
    
    @BeforeEach
    void setUp() {
        // Create flat terrain at height 5
        flatTerrain = new float[10][10];
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                flatTerrain[x][z] = 5.0f;
            }
        }
        
        // Create sloped terrain
        slopedTerrain = new float[10][10];
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                slopedTerrain[x][z] = x * 2.0f; // Slope in X direction
            }
        }
        
        // Create valley terrain
        valleyTerrain = new float[20][20];
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                float distFromCenter = (float)Math.sqrt((x-10)*(x-10) + (z-10)*(z-10));
                valleyTerrain[x][z] = distFromCenter * 0.5f;
            }
        }
    }
    
    @Test
    void testHeightmapCreation() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        
        assertEquals(10, heightmap.getWidth());
        assertEquals(10, heightmap.getDepth());
        assertEquals(1.0f, heightmap.getCellSize(), EPSILON);
        assertNotNull(heightmap.getAABB());
    }
    
    @Test
    void testHeightmapBounds() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), slopedTerrain, 2.0f);
        var bounds = heightmap.getAABB();
        
        // With cell size 2, the terrain spans 0 to 18 in X and Z
        assertEquals(0.0f, bounds.getMinX(), EPSILON);
        assertEquals(0.0f, bounds.getMinY(), EPSILON);
        assertEquals(0.0f, bounds.getMinZ(), EPSILON);
        assertEquals(18.0f, bounds.getMaxX(), EPSILON);
        assertEquals(18.0f, bounds.getMaxY(), EPSILON); // Max height is 9*2 = 18
        assertEquals(18.0f, bounds.getMaxZ(), EPSILON);
    }
    
    @Test
    void testHeightAtPosition() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        
        // Test height at various positions
        assertEquals(5.0f, heightmap.getHeightAtPosition(0, 0), EPSILON);
        assertEquals(5.0f, heightmap.getHeightAtPosition(5, 5), EPSILON);
        assertEquals(5.0f, heightmap.getHeightAtPosition(9, 9), EPSILON);
        
        // Test interpolation between grid points
        assertEquals(5.0f, heightmap.getHeightAtPosition(2.5f, 3.7f), EPSILON);
    }
    
    @Test
    void testHeightAtPositionSloped() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), slopedTerrain, 1.0f);
        
        // Test exact grid points
        assertEquals(0.0f, heightmap.getHeightAtPosition(0, 0), EPSILON);
        assertEquals(10.0f, heightmap.getHeightAtPosition(5, 0), EPSILON);
        assertEquals(18.0f, heightmap.getHeightAtPosition(9, 0), EPSILON);
        
        // Test interpolation
        assertEquals(5.0f, heightmap.getHeightAtPosition(2.5f, 0), EPSILON);
        assertEquals(7.0f, heightmap.getHeightAtPosition(3.5f, 0), EPSILON);
    }
    
    @Test
    void testNormalAtPosition() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        
        // Normal on flat terrain should point up
        var normal = heightmap.getNormalAtPosition(5, 5);
        assertEquals(0.0f, normal.x, EPSILON);
        assertEquals(1.0f, Math.abs(normal.y), EPSILON); // Allow for sign
        assertEquals(0.0f, normal.z, EPSILON);
    }
    
    @Test
    void testNormalAtPositionSloped() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), slopedTerrain, 1.0f);
        
        // Normal on sloped terrain should tilt
        var normal = heightmap.getNormalAtPosition(5, 5);
        assertTrue(normal.x < 0, "Normal should tilt in negative X direction");
        assertTrue(normal.y > 0, "Normal should have positive Y component");
        assertEquals(0.0f, normal.z, EPSILON);
        assertEquals(1.0f, normal.length(), EPSILON); // Should be normalized
    }
    
    @Test
    void testHeightmapVsSphereCollision() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        
        // Sphere above terrain
        var sphere1 = new SphereShape(new Point3f(5, 10, 5), 3.0f);
        var result1 = heightmap.collidesWith(sphere1);
        assertFalse(result1.collides, "Sphere above terrain should not collide");
        
        // Sphere intersecting terrain
        var sphere2 = new SphereShape(new Point3f(5, 5, 5), 3.0f);
        var result2 = heightmap.collidesWith(sphere2);
        assertTrue(result2.collides, "Sphere intersecting terrain should collide");
        assertEquals(3.0f, result2.penetrationDepth, EPSILON);
        
        // Sphere below terrain
        var sphere3 = new SphereShape(new Point3f(5, 0, 5), 3.0f);
        var result3 = heightmap.collidesWith(sphere3);
        assertTrue(result3.collides, "Sphere below terrain should collide");
        assertEquals(8.0f, result3.penetrationDepth, EPSILON);
    }
    
    @Test
    void testHeightmapVsBoxCollision() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), slopedTerrain, 1.0f);
        
        // Box straddling slope
        var box1 = new BoxShape(new Point3f(5, 10, 5), new Vector3f(3, 3, 3));
        var result1 = heightmap.collidesWith(box1);
        assertTrue(result1.collides, "Box should collide with sloped terrain");
        
        // Box above slope
        var box2 = new BoxShape(new Point3f(5, 20, 5), new Vector3f(3, 3, 3));
        var result2 = heightmap.collidesWith(box2);
        assertFalse(result2.collides, "Box above terrain should not collide");
    }
    
    @Test
    void testHeightmapVsCapsuleCollision() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        
        // Vertical capsule touching terrain
        var capsule1 = new CapsuleShape(new Point3f(5, 7, 5), 5.0f, 2.0f);
        var result1 = heightmap.collidesWith(capsule1);
        assertTrue(result1.collides, "Capsule should collide with terrain");
        
        // Horizontal capsule above terrain
        var capsule2 = new CapsuleShape(new Point3f(0, 10, 5), new Point3f(10, 10, 5), 2.0f);
        var result2 = heightmap.collidesWith(capsule2);
        assertFalse(result2.collides, "Capsule above terrain should not collide");
    }
    
    @Test
    void testHeightmapVsConvexHullCollision() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        
        // Create a simple tetrahedron hull
        var vertices = Arrays.asList(
            new Point3f(5, 3, 5),
            new Point3f(6, 3, 5),
            new Point3f(5, 3, 6),
            new Point3f(5, 8, 5)
        );
        
        var hull = new ConvexHullShape(new Point3f(0, 0, 0), vertices);
        var result = heightmap.collidesWith(hull);
        assertTrue(result.collides, "Hull should collide with terrain");
    }
    
    @Test
    void testHeightmapVsMeshCollision() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        
        // Create a triangle mesh
        var meshData = new TriangleMeshData();
        meshData.addVertex(new Point3f(5, 3, 5));
        meshData.addVertex(new Point3f(6, 3, 5));
        meshData.addVertex(new Point3f(5, 8, 6));
        meshData.addTriangle(0, 1, 2);
        
        var mesh = new MeshShape(new Point3f(0, 0, 0), meshData);
        var result = heightmap.collidesWith(mesh);
        assertTrue(result.collides, "Mesh should collide with terrain");
    }
    
    @Test
    void testHeightmapVsHeightmapCollision() {
        var heightmap1 = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        // Place second heightmap at same Y level so bounds overlap
        var heightmap2 = new HeightmapShape(new Point3f(5, 0, 5), flatTerrain, 1.0f);
        
        var result = heightmap1.collidesWith(heightmap2);
        assertTrue(result.collides, "Overlapping heightmaps should collide");
        
        // Non-overlapping heightmaps
        var heightmap3 = new HeightmapShape(new Point3f(50, 0, 50), flatTerrain, 1.0f);
        var result2 = heightmap1.collidesWith(heightmap3);
        assertFalse(result2.collides, "Distant heightmaps should not collide");
    }
    
    @Test
    void testRayIntersectionWithHeightmap() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        
        // Ray hitting terrain from above
        var ray1 = new Ray3D(new Point3f(5, 10, 5), new Vector3f(0, -1, 0));
        var result1 = heightmap.intersectRay(ray1);
        assertTrue(result1.intersects, "Ray should hit terrain");
        assertEquals(5.0f, result1.distance, EPSILON);
        assertEquals(5.0f, result1.intersectionPoint.y, EPSILON);
        
        // Ray missing terrain
        var ray2 = new Ray3D(new Point3f(50, 10, 50), new Vector3f(0, -1, 0));
        var result2 = heightmap.intersectRay(ray2);
        assertFalse(result2.intersects, "Ray should miss terrain");
        
        // Ray parallel to terrain
        var ray3 = new Ray3D(new Point3f(5, 6, 5), new Vector3f(1, 0, 0));
        var result3 = heightmap.intersectRay(ray3);
        assertFalse(result3.intersects, "Ray above terrain should not intersect");
    }
    
    @Test
    void testHeightmapSupport() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), valleyTerrain, 1.0f);
        
        // Support in Y direction should be highest point
        var supportY = heightmap.getSupport(new Vector3f(0, 1, 0));
        assertTrue(supportY.y > 5.0f, "Support in Y should be at edge of valley");
        
        // Support in negative Y direction should be lowest point (center)
        var supportNegY = heightmap.getSupport(new Vector3f(0, -1, 0));
        assertTrue(supportNegY.y < 2.0f, "Support in -Y should be at center of valley");
    }
    
    @Test
    void testHeightmapTranslation() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        heightmap.translate(new Vector3f(10, 5, 10));
        
        // Check bounds are translated
        var bounds = heightmap.getAABB();
        assertEquals(10.0f, bounds.getMinX(), EPSILON);
        assertEquals(10.0f, bounds.getMinY(), EPSILON);
        assertEquals(10.0f, bounds.getMinZ(), EPSILON);
        
        // Check height queries are translated
        assertEquals(10.0f, heightmap.getHeightAtPosition(15, 15), EPSILON);
    }
    
    @Test
    void testBilinearInterpolation() {
        // Create terrain with specific height values for testing interpolation
        var testTerrain = new float[3][3];
        testTerrain[0][0] = 0.0f;
        testTerrain[1][0] = 4.0f;
        testTerrain[2][0] = 8.0f;
        testTerrain[0][1] = 2.0f;
        testTerrain[1][1] = 6.0f;
        testTerrain[2][1] = 10.0f;
        testTerrain[0][2] = 4.0f;
        testTerrain[1][2] = 8.0f;
        testTerrain[2][2] = 12.0f;
        
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), testTerrain, 1.0f);
        
        // Test interpolation at center of first cell
        assertEquals(3.0f, heightmap.getHeightAtPosition(0.5f, 0.5f), EPSILON);
        
        // Test interpolation at other positions
        assertEquals(2.0f, heightmap.getHeightAtPosition(0.5f, 0.0f), EPSILON);  // Midpoint between 0 and 4
        assertEquals(4.0f, heightmap.getHeightAtPosition(0.5f, 1.0f), EPSILON);  // Midpoint between 2 and 6
    }
    
    @Test
    void testEdgeClamping() {
        var heightmap = new HeightmapShape(new Point3f(0, 0, 0), flatTerrain, 1.0f);
        
        // Test positions outside heightmap bounds
        assertEquals(5.0f, heightmap.getHeightAtPosition(-5, -5), EPSILON);
        assertEquals(5.0f, heightmap.getHeightAtPosition(15, 15), EPSILON);
        
        // Normals at edge should still be valid
        var normal = heightmap.getNormalAtPosition(-5, -5);
        assertEquals(1.0f, normal.length(), EPSILON);
    }
}
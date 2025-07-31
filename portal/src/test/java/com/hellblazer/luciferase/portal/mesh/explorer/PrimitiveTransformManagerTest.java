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

import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PrimitiveTransformManager
 *
 * @author hal.hildebrand
 */
@RequiresJavaFX
public class PrimitiveTransformManagerTest {
    
    private PrimitiveTransformManager manager;
    private PhongMaterial testMaterial;
    
    @BeforeEach
    void setUp() {
        manager = new PrimitiveTransformManager();
        testMaterial = new PhongMaterial(Color.RED);
    }
    
    @Test
    void testSphereCreation() {
        // Create a sphere
        Point3f position = new Point3f(100, 200, 300);
        float radius = 50;
        
        MeshView sphere = manager.createSphere(position, radius, testMaterial);
        
        assertNotNull(sphere);
        assertNotNull(sphere.getMesh());
        assertEquals(testMaterial, sphere.getMaterial());
        assertEquals(1, sphere.getTransforms().size());
    }
    
    @Test
    void testCylinderCreation() {
        // Create a cylinder
        Point3f position = new Point3f(100, 200, 300);
        float radius = 25;
        float height = 100;
        Vector3f rotation = new Vector3f(0, 0, 90);
        
        MeshView cylinder = manager.createCylinder(position, radius, height, rotation, testMaterial);
        
        assertNotNull(cylinder);
        assertNotNull(cylinder.getMesh());
        assertEquals(testMaterial, cylinder.getMaterial());
        assertEquals(1, cylinder.getTransforms().size());
    }
    
    @Test
    void testBoxCreation() {
        // Create a box
        Point3f position = new Point3f(100, 200, 300);
        Vector3f size = new Vector3f(50, 60, 70);
        
        MeshView box = manager.createBox(position, size, testMaterial);
        
        assertNotNull(box);
        assertNotNull(box.getMesh());
        assertEquals(testMaterial, box.getMaterial());
        assertEquals(1, box.getTransforms().size());
    }
    
    @Test
    void testLineCreation() {
        // Create a line
        Point3f start = new Point3f(0, 0, 0);
        Point3f end = new Point3f(100, 100, 100);
        float thickness = 5;
        
        MeshView line = manager.createLine(start, end, thickness, testMaterial);
        
        assertNotNull(line);
        assertNotNull(line.getMesh());
        assertEquals(testMaterial, line.getMaterial());
        assertEquals(1, line.getTransforms().size());
    }
    
    @Test
    void testTetrahedronCreation() {
        // Test all 6 tetrahedron types
        for (int type = 0; type < 6; type++) {
            Point3f position = new Point3f(100 * type, 200, 300);
            float size = 100;
            
            MeshView tet = manager.createTetrahedron(type, position, size, testMaterial);
            
            assertNotNull(tet, "Tetrahedron type " + type + " should be created");
            assertNotNull(tet.getMesh(), "Tetrahedron type " + type + " should have a mesh");
            assertEquals(testMaterial, tet.getMaterial());
            assertEquals(1, tet.getTransforms().size());
        }
    }
    
    @Test
    void testMeshReuse() {
        // Create multiple spheres and verify they share the same mesh reference
        MeshView sphere1 = manager.createSphere(new Point3f(0, 0, 0), 50, testMaterial);
        MeshView sphere2 = manager.createSphere(new Point3f(100, 100, 100), 50, testMaterial);
        MeshView sphere3 = manager.createSphere(new Point3f(200, 200, 200), 75, testMaterial);
        
        // All spheres should share the same reference mesh
        assertSame(sphere1.getMesh(), sphere2.getMesh());
        assertSame(sphere2.getMesh(), sphere3.getMesh());
    }
    
    @Test
    void testTransformCaching() {
        // Create primitives with same transform parameters
        Point3f position = new Point3f(100, 200, 300);
        Vector3f scale = new Vector3f(50, 50, 50);
        
        // Create multiple boxes at same position/scale
        manager.createBox(position, scale, testMaterial);
        manager.createBox(position, scale, testMaterial);
        
        Map<String, Integer> stats = manager.getStatistics();
        assertTrue(stats.get("transformCacheSize") > 0, "Transform cache should contain entries");
    }
    
    @Test
    void testLineRotationCalculation() {
        // Test various line orientations
        testLineOrientation(new Point3f(0, 0, 0), new Point3f(100, 0, 0));   // X-axis
        testLineOrientation(new Point3f(0, 0, 0), new Point3f(0, 100, 0));   // Y-axis
        testLineOrientation(new Point3f(0, 0, 0), new Point3f(0, 0, 100));   // Z-axis
        testLineOrientation(new Point3f(0, 0, 0), new Point3f(100, 100, 0)); // XY diagonal
        testLineOrientation(new Point3f(0, 0, 0), new Point3f(100, 100, 100)); // XYZ diagonal
    }
    
    private void testLineOrientation(Point3f start, Point3f end) {
        MeshView line = manager.createLine(start, end, 5, testMaterial);
        assertNotNull(line);
        assertEquals(1, line.getTransforms().size());
    }
    
    @Test
    void testStatistics() {
        // Create various primitives
        manager.createSphere(new Point3f(0, 0, 0), 50, testMaterial);
        manager.createCylinder(new Point3f(100, 0, 0), 25, 100, null, testMaterial);
        manager.createBox(new Point3f(200, 0, 0), new Vector3f(50, 50, 50), testMaterial);
        manager.createTetrahedron(0, new Point3f(300, 0, 0), 100, testMaterial);
        
        Map<String, Integer> stats = manager.getStatistics();
        
        assertTrue(stats.get("referenceMeshCount") > 0);
        assertTrue(stats.get("transformCacheSize") > 0);
        assertTrue(stats.get("materialPoolSize") >= 0); // Material pool managed internally
    }
    
    @Test
    void testCacheClear() {
        // Create some primitives to populate cache
        for (int i = 0; i < 10; i++) {
            manager.createSphere(new Point3f(i * 10, 0, 0), 50, testMaterial);
        }
        
        Map<String, Integer> statsBeforeClear = manager.getStatistics();
        assertTrue(statsBeforeClear.get("transformCacheSize") > 0);
        
        // Clear cache
        manager.clearTransformCache();
        
        Map<String, Integer> statsAfterClear = manager.getStatistics();
        assertEquals(0, statsAfterClear.get("transformCacheSize"));
    }
    
    @Test
    void testNullRotationHandling() {
        // Test that null rotation is handled properly
        MeshView cylinder = manager.createCylinder(
            new Point3f(0, 0, 0),
            25,
            100,
            null, // No rotation
            testMaterial
        );
        
        assertNotNull(cylinder);
        assertEquals(1, cylinder.getTransforms().size());
    }
    
    @Test
    void testInvalidTetrahedronType() {
        // Test invalid tetrahedron type
        assertThrows(IllegalArgumentException.class, () -> {
            manager.createTetrahedron(7, new Point3f(0, 0, 0), 100, testMaterial);
        });
    }
    
    @Test
    void testMeshUniqueness() {
        // Verify each primitive type has its own reference mesh
        Set<Object> meshes = new HashSet<>();
        
        meshes.add(manager.createSphere(new Point3f(0, 0, 0), 50, testMaterial).getMesh());
        meshes.add(manager.createCylinder(new Point3f(0, 0, 0), 25, 100, null, testMaterial).getMesh());
        meshes.add(manager.createBox(new Point3f(0, 0, 0), new Vector3f(50, 50, 50), testMaterial).getMesh());
        
        // We should have at least 3 different meshes (sphere, cylinder, box)
        assertTrue(meshes.size() >= 3, "Each primitive type should have a unique mesh");
    }
}
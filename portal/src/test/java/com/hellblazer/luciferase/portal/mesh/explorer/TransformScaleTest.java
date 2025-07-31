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

import javafx.application.Platform;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test transform-based rendering with different scales to verify correctness.
 *
 * @author hal.hildebrand
 */
@RequiresJavaFX
public class TransformScaleTest {
    
    private static PrimitiveTransformManager transformManager;
    
    @BeforeAll
    static void setup() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Platform already initialized
        }
        
        transformManager = new PrimitiveTransformManager();
    }
    
    @Test
    void testDifferentScalesBox() {
        PhongMaterial material = new PhongMaterial(Color.RED);
        
        // Test small scale
        MeshView smallBox = transformManager.createBox(
            new Point3f(0, 0, 0),
            new Vector3f(0.1f, 0.1f, 0.1f),
            material
        );
        assertNotNull(smallBox);
        assertEquals(1, smallBox.getTransforms().size());
        
        // Test medium scale
        MeshView mediumBox = transformManager.createBox(
            new Point3f(10, 10, 10),
            new Vector3f(5, 5, 5),
            material
        );
        assertNotNull(mediumBox);
        
        // Test large scale
        MeshView largeBox = transformManager.createBox(
            new Point3f(1000, 1000, 1000),
            new Vector3f(500, 500, 500),
            material
        );
        assertNotNull(largeBox);
        
        // Test extreme scale (as used in axes)
        double axisLength = 2306867.2;
        double axisRadius = 8192;
        MeshView extremeBox = transformManager.createBox(
            new Point3f((float)axisLength, 0, 0),
            new Vector3f((float)axisLength, (float)axisRadius, (float)axisRadius),
            material
        );
        assertNotNull(extremeBox);
        
        // All should share the same reference mesh
        assertEquals(smallBox.getMesh(), mediumBox.getMesh());
        assertEquals(mediumBox.getMesh(), largeBox.getMesh());
        assertEquals(largeBox.getMesh(), extremeBox.getMesh());
        
        System.out.println("Box scaling test passed:");
        System.out.println("  - Small: 0.1 x 0.1 x 0.1");
        System.out.println("  - Medium: 5 x 5 x 5");
        System.out.println("  - Large: 500 x 500 x 500");
        System.out.println("  - Extreme: " + axisLength + " x " + axisRadius + " x " + axisRadius);
        System.out.println("  - All use same reference mesh");
    }
    
    @Test
    void testDifferentScalesTetrahedra() {
        PhongMaterial material = new PhongMaterial(Color.BLUE);
        
        // Test different scales for each tetrahedron type
        for (int type = 0; type < 6; type++) {
            MeshView small = transformManager.createTetrahedron(
                type,
                new Point3f(0, 0, 0),
                1.0f,
                material
            );
            
            MeshView medium = transformManager.createTetrahedron(
                type,
                new Point3f(100, 100, 100),
                50.0f,
                material
            );
            
            MeshView large = transformManager.createTetrahedron(
                type,
                new Point3f(10000, 10000, 10000),
                1000.0f,
                material
            );
            
            // Verify all share the same reference mesh for the type
            assertEquals(small.getMesh(), medium.getMesh());
            assertEquals(medium.getMesh(), large.getMesh());
            
            // Verify different types have different meshes
            if (type > 0) {
                MeshView prevType = transformManager.createTetrahedron(
                    type - 1,
                    new Point3f(0, 0, 0),
                    1.0f,
                    material
                );
                assertNotEquals(small.getMesh(), prevType.getMesh(),
                    "Type " + type + " should have different mesh than type " + (type - 1));
            }
        }
        
        System.out.println("Tetrahedra scaling test passed:");
        System.out.println("  - Tested all 6 types (S0-S5)");
        System.out.println("  - Each type at scales: 1, 50, 1000");
        System.out.println("  - Each type uses its own reference mesh");
        System.out.println("  - All instances of same type share mesh");
    }
    
    @Test
    void testTransformCaching() {
        PhongMaterial material = new PhongMaterial(Color.GREEN);
        
        // Clear cache first
        transformManager.clearTransformCache();
        var stats1 = transformManager.getStatistics();
        assertEquals(0, stats1.get("transformCacheSize"));
        
        // Create multiple objects with same parameters
        for (int i = 0; i < 10; i++) {
            transformManager.createSphere(
                new Point3f(50, 50, 50),
                10.0f,
                material
            );
        }
        
        // Should have only one cached transform
        var stats2 = transformManager.getStatistics();
        assertEquals(1, stats2.get("transformCacheSize"),
            "Should cache transform for identical parameters");
        
        // Create with different parameters
        transformManager.createSphere(
            new Point3f(100, 100, 100),
            10.0f,
            material
        );
        
        // Should now have two cached transforms
        var stats3 = transformManager.getStatistics();
        assertEquals(2, stats3.get("transformCacheSize"),
            "Should create new cache entry for different parameters");
        
        System.out.println("Transform caching test passed:");
        System.out.println("  - Cache properly stores unique transforms");
        System.out.println("  - Identical parameters reuse cached transform");
    }
}
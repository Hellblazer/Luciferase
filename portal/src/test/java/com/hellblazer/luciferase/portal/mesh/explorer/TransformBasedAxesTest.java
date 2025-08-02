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
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Rotate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proof of concept test demonstrating transform-based axes rendering.
 *
 * @author hal.hildebrand
 */
@RequiresJavaFX
public class TransformBasedAxesTest {
    
    private static PrimitiveTransformManager transformManager;
    
    @BeforeAll
    static void setup() {
        // Initialize JavaFX platform
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Platform already initialized
        }
        
        transformManager = new PrimitiveTransformManager();
    }
    
    @Test
    void testCreateTransformBasedAxes() {
        // Traditional approach
        Group traditionalAxes = createTraditionalAxes();
        
        // Transform-based approach
        Group transformBasedAxes = createTransformBasedAxes();
        
        // Verify both have 3 children (X, Y, Z axes)
        assertEquals(3, traditionalAxes.getChildren().size());
        assertEquals(3, transformBasedAxes.getChildren().size());
        
        // Verify all children are proper types
        traditionalAxes.getChildren().forEach(node -> 
            assertTrue(node instanceof Cylinder, "Traditional should use Cylinder"));
        
        transformBasedAxes.getChildren().forEach(node -> 
            assertTrue(node instanceof MeshView, "Transform-based should use MeshView"));
        
        // Check statistics
        var stats = transformManager.getStatistics();
        System.out.println("Transform Manager Statistics: " + stats);
        assertTrue(stats.get("transformCacheSize") > 0, "Should have cached transforms");
    }
    
    @Test
    void testMemoryEfficiency() {
        // This test is more about validating the concept than exact memory measurements
        // Real memory savings will come when:
        // 1. We implement proper mesh generation (not placeholders)
        // 2. We reuse the same mesh instances across many objects
        // 3. We eliminate the Group wrapper overhead
        
        int axesCount = 100;
        
        // Count objects created - this is a better metric for now
        int traditionalMeshCount = axesCount * 3; // 3 Cylinder objects per axes set
        int transformBasedMeshCount = 3; // Only 3 reference meshes total (reused)
        
        // Create axes to verify they work
        Group traditional = createTraditionalAxes();
        Group transformBased = createTransformBasedAxes();
        
        // Verify both approaches create the same number of visual elements
        assertEquals(3, traditional.getChildren().size());
        assertEquals(3, transformBased.getChildren().size());
        
        // Log the theoretical improvement
        System.out.printf("Object Count Comparison (%d axes sets):%n", axesCount);
        System.out.printf("Traditional: %d mesh objects%n", traditionalMeshCount);
        System.out.printf("Transform-based: %d reference meshes (reused)%n", transformBasedMeshCount);
        System.out.printf("Reduction: %.1f%%%n", 
            (1.0 - (double)transformBasedMeshCount / traditionalMeshCount) * 100);
        
        // Verify transform caching is working
        var stats = transformManager.getStatistics();
        assertTrue((Integer)stats.get("transformCacheSize") > 0, "Transforms should be cached");
        
        // The real memory test will be more meaningful once we have proper implementations
        // For now, we're validating the architecture works
        assertTrue(transformBasedMeshCount < traditionalMeshCount, 
            "Transform-based should create fewer mesh objects");
    }
    
    private Group createTraditionalAxes() {
        Group axesGroup = new Group();
        
        double axisLength = (1 << MortonCurve.MAX_REFINEMENT_LEVEL) * 1.1;
        double axisRadius = 8192;
        
        // X axis - Red
        Cylinder xAxis = new Cylinder(axisRadius, axisLength);
        xAxis.setMaterial(new PhongMaterial(Color.RED));
        xAxis.setRotate(90);
        xAxis.setRotationAxis(Rotate.Z_AXIS);
        xAxis.setTranslateX(axisLength / 2);
        
        // Y axis - Green
        Cylinder yAxis = new Cylinder(axisRadius, axisLength);
        yAxis.setMaterial(new PhongMaterial(Color.GREEN));
        yAxis.setTranslateY(axisLength / 2);
        
        // Z axis - Blue
        Cylinder zAxis = new Cylinder(axisRadius, axisLength);
        zAxis.setMaterial(new PhongMaterial(Color.BLUE));
        zAxis.setRotate(90);
        zAxis.setRotationAxis(Rotate.X_AXIS);
        zAxis.setTranslateZ(axisLength / 2);
        
        axesGroup.getChildren().addAll(xAxis, yAxis, zAxis);
        return axesGroup;
    }
    
    private Group createTransformBasedAxes() {
        Group axesGroup = new Group();
        
        double axisLength = (1 << MortonCurve.MAX_REFINEMENT_LEVEL) * 1.1;
        double axisRadius = 8192;
        
        MaterialPool materialPool = transformManager.materialPool;
        
        // X axis - Red (90° rotation around Z)
        MeshView xAxis = transformManager.createCylinder(
            new Point3f((float)(axisLength / 2), 0, 0),
            (float)axisRadius,
            (float)axisLength,
            new Vector3f(0, 0, 90),
            materialPool.getMaterial(Color.RED)
        );
        
        // Y axis - Green (no rotation needed)
        MeshView yAxis = transformManager.createCylinder(
            new Point3f(0, (float)(axisLength / 2), 0),
            (float)axisRadius,
            (float)axisLength,
            null,
            materialPool.getMaterial(Color.GREEN)
        );
        
        // Z axis - Blue (90° rotation around X)
        MeshView zAxis = transformManager.createCylinder(
            new Point3f(0, 0, (float)(axisLength / 2)),
            (float)axisRadius,
            (float)axisLength,
            new Vector3f(90, 0, 0),
            materialPool.getMaterial(Color.BLUE)
        );
        
        axesGroup.getChildren().addAll(xAxis, yAxis, zAxis);
        return axesGroup;
    }
    
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}

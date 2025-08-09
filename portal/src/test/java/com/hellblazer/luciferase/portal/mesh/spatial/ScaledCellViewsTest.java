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
package com.hellblazer.luciferase.portal.mesh.spatial;

import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.portal.mesh.explorer.RequiresJavaFX;
import javafx.scene.Group;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Transform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ScaledCellViews to ensure proper scaling of tetrahedral visualizations.
 *
 * @author hal.hildebrand
 */
@RequiresJavaFX
public class ScaledCellViewsTest {
    
    private ScaledCellViews scaledCellViews;
    
    @BeforeEach
    void setUp() {
        scaledCellViews = new ScaledCellViews();
    }
    
    @Test
    void testMeshViewCreation() {
        // Create a tetrahedron at level 10
        // Cell size at level 10 is 2048, so coordinates must be multiples of 2048
        Tet tet = new Tet(2048, 4096, 6144, (byte) 10, (byte) 0);
        
        // Create mesh view
        MeshView meshView = scaledCellViews.createMeshView(tet);
        
        assertNotNull(meshView);
        assertNotNull(meshView.getMesh());
        
        // Should have one transform (the scaling transform)
        assertEquals(1, meshView.getTransforms().size());
        
        Transform transform = meshView.getTransforms().get(0);
        assertTrue(transform instanceof javafx.scene.transform.Affine);
    }
    
    @Test
    void testWireframeCreation() {
        // Create a tetrahedron at level 15 (finer detail)
        // Cell size at level 15 is 64, so coordinates must be multiples of 64
        Tet tet = new Tet(64, 128, 192, (byte) 15, (byte) 2);
        
        // Create wireframe
        Group wireframe = scaledCellViews.createWireframe(tet);
        
        assertNotNull(wireframe);
        assertFalse(wireframe.getChildren().isEmpty());
        
        // Should have one transform
        assertEquals(1, wireframe.getTransforms().size());
    }
    
    @Test
    void testDifferentLevelScaling() {
        // Create tetrahedra at different levels
        // Level 0 must be at origin
        Tet level0 = new Tet(0, 0, 0, (byte) 0, (byte) 0);
        // Level 10 cell size is 2048
        Tet level10 = new Tet(2048, 2048, 2048, (byte) 10, (byte) 0);
        // Level 20 cell size is 2
        Tet level20 = new Tet(2, 2, 2, (byte) 20, (byte) 0);
        
        // Create mesh views
        MeshView mesh0 = scaledCellViews.createMeshView(level0);
        MeshView mesh10 = scaledCellViews.createMeshView(level10);
        MeshView mesh20 = scaledCellViews.createMeshView(level20);
        
        // All should be created successfully
        assertNotNull(mesh0);
        assertNotNull(mesh10);
        assertNotNull(mesh20);
        
        // Extract scale factors from transforms
        javafx.scene.transform.Affine transform0 = (javafx.scene.transform.Affine) mesh0.getTransforms().get(0);
        javafx.scene.transform.Affine transform10 = (javafx.scene.transform.Affine) mesh10.getTransforms().get(0);
        javafx.scene.transform.Affine transform20 = (javafx.scene.transform.Affine) mesh20.getTransforms().get(0);
        
        double scale0 = transform0.getMxx();
        double scale10 = transform10.getMxx();
        double scale20 = transform20.getMxx();
        
        // Finer levels should have larger scale factors
        assertTrue(scale10 > scale0);
        assertTrue(scale20 > scale10);
        
        // Scale ratios should match level differences
        assertEquals(Math.pow(2, 10), scale10 / scale0, 0.001);
        assertEquals(Math.pow(2, 10), scale20 / scale10, 0.001);
    }
    
    @Test
    void testCachingBehavior() {
        // Create same tetrahedron twice
        // Cell size at level 12 is 512, so coordinates must be multiples of 512
        Tet tet = new Tet(512, 1024, 1536, (byte) 12, (byte) 3);
        
        MeshView mesh1 = scaledCellViews.createMeshView(tet);
        MeshView mesh2 = scaledCellViews.createMeshView(tet);
        
        // Both should be created
        assertNotNull(mesh1);
        assertNotNull(mesh2);
        
        // Should use cached transform (verify by checking they have equivalent transforms)
        javafx.scene.transform.Affine transform1 = (javafx.scene.transform.Affine) mesh1.getTransforms().get(0);
        javafx.scene.transform.Affine transform2 = (javafx.scene.transform.Affine) mesh2.getTransforms().get(0);
        
        // Transforms should be equal in value (though different instances)
        assertEquals(transform1.getMxx(), transform2.getMxx(), 0.0001);
        assertEquals(transform1.getTx(), transform2.getTx(), 0.0001);
    }
    
    @Test
    void testCacheClear() {
        // Create a tetrahedron
        // Cell size at level 10 is 2048
        Tet tet = new Tet(2048, 2048, 2048, (byte) 10, (byte) 0);
        scaledCellViews.createMeshView(tet);
        
        // Clear cache
        scaledCellViews.clearTransformCache();
        
        // Should still be able to create new views
        MeshView meshAfterClear = scaledCellViews.createMeshView(tet);
        assertNotNull(meshAfterClear);
    }
    
    @Test
    void testExtremeCoordinates() {
        // Test near origin
        Tet nearOrigin = new Tet(10, 10, 10, (byte) 20, (byte) 0);
        MeshView meshNearOrigin = scaledCellViews.createMeshView(nearOrigin);
        assertNotNull(meshNearOrigin);
        
        // Test near maximum coordinates
        // Cell size at level 5 is 65536
        int alignedNearMax = 65536 * 16; // 1048576, which is aligned
        Tet nearMaxTet = new Tet(alignedNearMax, alignedNearMax, alignedNearMax, (byte) 5, (byte) 0);
        MeshView meshNearMax = scaledCellViews.createMeshView(nearMaxTet);
        assertNotNull(meshNearMax);
    }
}
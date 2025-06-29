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

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import javafx.application.Platform;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for TetreeVisualization to verify correct face normal orientation.
 *
 * @author hal.hildebrand
 */
public class TetreeVisualizationTest {

    @BeforeAll
    public static void initializeJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Platform already initialized
        }
    }

    @Test
    public void testTetrahedralFaceNormals() {
        // Create a simple Tetree
        Tetree<LongEntityID, String> tetree = new Tetree<>(new SequentialLongIDGenerator());
        
        // Add an entity to create a node
        LongEntityID id = tetree.insert(new Point3f(100, 100, 100), (byte)10, "test");
        
        // Create visualization
        TetreeVisualization<LongEntityID, String> visualization = new TetreeVisualization<>(tetree);
        
        // Enable node bounds to ensure mesh creation
        visualization.showNodeBoundsProperty().set(true);
        
        // Render nodes
        visualization.renderNodes();
        
        // Find the mesh view in the node group
        MeshView meshView = null;
        for (var node : visualization.getNodeGroup().getChildren()) {
            if (node instanceof javafx.scene.Group) {
                for (var child : ((javafx.scene.Group) node).getChildren()) {
                    if (child instanceof MeshView) {
                        meshView = (MeshView) child;
                        break;
                    }
                }
            }
        }
        
        assertTrue(meshView != null, "Should have created a mesh view");
        
        // Verify the mesh has correct face winding
        TriangleMesh mesh = (TriangleMesh) meshView.getMesh();
        
        // Get the face definitions
        var faces = mesh.getFaces();
        
        // Check we have 4 faces (6 elements per face: 3 vertices * 2 values each)
        assertTrue(faces.size() == 24, "Should have 24 face elements (4 faces * 3 vertices * 2 values)");
        
        // Verify face 0: vertices 0-2-1 (base, viewed from below)
        assertTrue(faces.get(0) == 0);
        assertTrue(faces.get(2) == 2);
        assertTrue(faces.get(4) == 1);
        
        // Verify face 1: vertices 0-1-3 (front right)
        assertTrue(faces.get(6) == 0);
        assertTrue(faces.get(8) == 1);
        assertTrue(faces.get(10) == 3);
        
        // Verify face 2: vertices 0-3-2 (back left)
        assertTrue(faces.get(12) == 0);
        assertTrue(faces.get(14) == 3);
        assertTrue(faces.get(16) == 2);
        
        // Verify face 3: vertices 1-2-3 (top, viewed from above)
        assertTrue(faces.get(18) == 1);
        assertTrue(faces.get(20) == 2);
        assertTrue(faces.get(22) == 3);
    }
    
    /**
     * Helper method to compute face normal using cross product.
     * Not used in the test but demonstrates the correct calculation.
     */
    private Vector3f computeFaceNormal(Point3f v0, Point3f v1, Point3f v2) {
        // Edge vectors
        Vector3f edge1 = new Vector3f(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z);
        Vector3f edge2 = new Vector3f(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z);
        
        // Cross product gives normal
        Vector3f normal = new Vector3f();
        normal.cross(edge1, edge2);
        normal.normalize();
        
        return normal;
    }
}
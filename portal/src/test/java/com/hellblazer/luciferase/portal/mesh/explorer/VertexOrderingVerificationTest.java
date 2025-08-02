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
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the vertex ordering in PrimitiveTransformManager matches
 * the cube subdivision vertex ordering used in TetreeVisualizationDemo.
 * 
 * This test ensures that transform-based tetrahedra are not mirror images
 * of the traditional mesh tetrahedra.
 *
 * @author hal.hildebrand
 */
@RequiresJavaFX
public class VertexOrderingVerificationTest {
    
    // The correct vertex indices from Tet.coordinates() cube subdivision
    private static final int[][] CORRECT_TET_INDICES = { 
        { 0, 1, 3, 7 },  // S0: vertices 0, 1, 3, 7 of cube
        { 0, 2, 3, 7 },  // S1: vertices 0, 2, 3, 7 of cube
        { 0, 4, 5, 7 },  // S2: vertices 0, 4, 5, 7 of cube
        { 0, 4, 6, 7 },  // S3: vertices 0, 4, 6, 7 of cube
        { 0, 1, 5, 7 },  // S4: vertices 0, 1, 5, 7 of cube
        { 0, 2, 6, 7 }   // S5: vertices 0, 2, 6, 7 of cube
    };
    
    // Cube vertex positions
    private static final float[][] CUBE_VERTICES = {
        {0, 0, 0}, // 0
        {1, 0, 0}, // 1
        {0, 1, 0}, // 2
        {1, 1, 0}, // 3
        {0, 0, 1}, // 4
        {1, 0, 1}, // 5
        {0, 1, 1}, // 6
        {1, 1, 1}  // 7
    };
    
    @BeforeAll
    public static void setupJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Already initialized
        }
    }
    
    @Test
    public void testTetrahedronVertexOrdering() {
        PrimitiveTransformManager manager = new PrimitiveTransformManager();
        
        // Test each tetrahedron type S0-S5
        for (int type = 0; type < 6; type++) {
            // Create a tetrahedron at origin with unit size
            MeshView meshView = manager.createTetrahedron(type, new Point3f(0, 0, 0), 1.0f, null);
            TriangleMesh mesh = (TriangleMesh) meshView.getMesh();
            
            // Extract vertex positions from the mesh
            float[] points = new float[mesh.getPoints().size()];
            mesh.getPoints().toArray(points);
            
            // Verify we have exactly 4 vertices (12 floats)
            assertEquals(12, points.length, "Tetrahedron should have 4 vertices (12 float values)");
            
            // Extract the 4 vertices
            float[][] actualVertices = new float[4][3];
            for (int i = 0; i < 4; i++) {
                actualVertices[i][0] = points[i * 3];
                actualVertices[i][1] = points[i * 3 + 1];
                actualVertices[i][2] = points[i * 3 + 2];
            }
            
            // The vertices should match the cube vertices at the correct indices
            int[] expectedIndices = CORRECT_TET_INDICES[type];
            
            // Verify each vertex matches the expected cube vertex
            for (int i = 0; i < 4; i++) {
                float[] expectedVertex = CUBE_VERTICES[expectedIndices[i]];
                float[] actualVertex = actualVertices[i];
                
                // Check each coordinate
                assertEquals(expectedVertex[0], actualVertex[0], 0.001f,
                    String.format("Type S%d vertex %d X coordinate mismatch", type, i));
                assertEquals(expectedVertex[1], actualVertex[1], 0.001f,
                    String.format("Type S%d vertex %d Y coordinate mismatch", type, i));
                assertEquals(expectedVertex[2], actualVertex[2], 0.001f,
                    String.format("Type S%d vertex %d Z coordinate mismatch", type, i));
            }
            
            // Additional check: verify that all types share vertices 0 and 7
            // This is a characteristic of the S0-S5 cube subdivision
            assertVertexAtPosition(actualVertices, CUBE_VERTICES[0], 
                String.format("Type S%d should include cube vertex 0", type));
            assertVertexAtPosition(actualVertices, CUBE_VERTICES[7], 
                String.format("Type S%d should include cube vertex 7", type));
        }
    }
    
    @Test
    public void testNoMirrorImageArtifacts() {
        PrimitiveTransformManager manager = new PrimitiveTransformManager();
        
        // Create S0 tetrahedron which should have vertices {0,1,3,7}
        MeshView meshView = manager.createTetrahedron(0, new Point3f(0, 0, 0), 1.0f, null);
        TriangleMesh mesh = (TriangleMesh) meshView.getMesh();
        
        float[] points = new float[mesh.getPoints().size()];
        mesh.getPoints().toArray(points);
        
        // S0 should NOT have vertex 5 (which would be present in Constants.SIMPLEX_STANDARD)
        // Instead it should have vertex 3
        boolean hasVertex3 = false;
        boolean hasVertex5 = false;
        
        for (int i = 0; i < 4; i++) {
            float x = points[i * 3];
            float y = points[i * 3 + 1];
            float z = points[i * 3 + 2];
            
            // Check if this is vertex 3 (1,1,0)
            if (Math.abs(x - 1) < 0.001 && Math.abs(y - 1) < 0.001 && Math.abs(z - 0) < 0.001) {
                hasVertex3 = true;
            }
            
            // Check if this is vertex 5 (1,0,1)
            if (Math.abs(x - 1) < 0.001 && Math.abs(y - 0) < 0.001 && Math.abs(z - 1) < 0.001) {
                hasVertex5 = true;
            }
        }
        
        assertTrue(hasVertex3, "S0 should have vertex 3 (1,1,0) from cube subdivision");
        assertFalse(hasVertex5, "S0 should NOT have vertex 5 (1,0,1) from Constants.SIMPLEX_STANDARD");
    }
    
    private void assertVertexAtPosition(float[][] vertices, float[] expectedPos, String message) {
        boolean found = false;
        for (float[] vertex : vertices) {
            if (Math.abs(vertex[0] - expectedPos[0]) < 0.001 &&
                Math.abs(vertex[1] - expectedPos[1]) < 0.001 &&
                Math.abs(vertex[2] - expectedPos[2]) < 0.001) {
                found = true;
                break;
            }
        }
        assertTrue(found, message);
    }
}

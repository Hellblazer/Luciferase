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
import javafx.scene.transform.Affine;
import javafx.scene.transform.Transform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that traditional mesh tetrahedra and transform-based tetrahedra
 * are exactly congruent - same position, orientation, and edge lengths.
 *
 * @author hal.hildebrand
 */
public class TetrahedronCongruenceTest {
    
    @BeforeAll
    public static void setupJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Already initialized
        }
    }
    
    @Test
    public void testTetrahedraCongruence() {
        PrimitiveTransformManager transformManager = new PrimitiveTransformManager();
        
        // Test parameters
        float x = 100.0f;
        float y = 200.0f;
        float z = 300.0f;
        float size = 50.0f;
        
        // Vertex indices from TetreeVisualizationDemo (matching Tet.coordinates())
        int[][] tetIndices = { 
            { 0, 1, 3, 7 },  // S0: vertices 0, 1, 3, 7 of cube
            { 0, 2, 3, 7 },  // S1: vertices 0, 2, 3, 7 of cube
            { 0, 4, 5, 7 },  // S2: vertices 0, 4, 5, 7 of cube
            { 0, 4, 6, 7 },  // S3: vertices 0, 4, 6, 7 of cube
            { 0, 1, 5, 7 },  // S4: vertices 0, 1, 5, 7 of cube
            { 0, 2, 6, 7 }   // S5: vertices 0, 2, 6, 7 of cube
        };
        
        // Test each tetrahedron type
        for (int type = 0; type < 6; type++) {
            // Create traditional mesh (like TetreeVisualizationDemo)
            MeshView traditionalMesh = createTraditionalTetrahedron(x, y, z, size, tetIndices[type]);
            
            // Create transform-based mesh
            MeshView transformMesh = transformManager.createTetrahedron(type, new Point3f(x, y, z), size, null);
            
            // Extract actual vertex positions from both meshes
            float[][] tradVertices = extractActualVertices(traditionalMesh);
            float[][] transformVertices = extractActualVertices(transformMesh);
            
            // Check that we have the same number of vertices
            assertEquals(4, tradVertices.length, "Traditional mesh should have 4 vertices");
            assertEquals(4, transformVertices.length, "Transform mesh should have 4 vertices");
            
            // Check that vertices match (within tolerance for floating point)
            assertVerticesMatch(tradVertices, transformVertices, type);
            
            // Check edge lengths match
            assertEdgeLengthsMatch(tradVertices, transformVertices, type);
            
            // Check that both tetrahedra have the same volume (orientation check)
            float tradVolume = calculateTetrahedronVolume(tradVertices);
            float transformVolume = calculateTetrahedronVolume(transformVertices);
            assertEquals(Math.abs(tradVolume), Math.abs(transformVolume), 0.01f,
                String.format("S%d volumes should match", type));
        }
    }
    
    /**
     * Create a traditional tetrahedron mesh like TetreeVisualizationDemo does.
     */
    private MeshView createTraditionalTetrahedron(float x, float y, float z, float size, int[] indices) {
        // Create unit cube vertices for this position
        Point3f[] cubeVerts = new Point3f[8];
        cubeVerts[0] = new Point3f(x, y, z);                           // (0,0,0)
        cubeVerts[1] = new Point3f(x + size, y, z);                   // (1,0,0)
        cubeVerts[2] = new Point3f(x, y + size, z);                   // (0,1,0)
        cubeVerts[3] = new Point3f(x + size, y + size, z);           // (1,1,0)
        cubeVerts[4] = new Point3f(x, y, z + size);                   // (0,0,1)
        cubeVerts[5] = new Point3f(x + size, y, z + size);           // (1,0,1)
        cubeVerts[6] = new Point3f(x, y + size, z + size);           // (0,1,1)
        cubeVerts[7] = new Point3f(x + size, y + size, z + size);   // (1,1,1)
        
        // Create tetrahedron from the specified vertices
        Point3f[] tetVerts = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            tetVerts[i] = cubeVerts[indices[i]];
        }
        
        // Create mesh
        TriangleMesh mesh = new TriangleMesh();
        
        // Add vertices
        for (Point3f v : tetVerts) {
            mesh.getPoints().addAll(v.x, v.y, v.z);
        }
        
        // Add texture coordinates
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);
        
        // Add faces (same winding as TetrahedralViews)
        mesh.getFaces().addAll(
            0, 0, 2, 2, 1, 1,  // Face 0-2-1
            0, 0, 1, 1, 3, 3,  // Face 0-1-3
            0, 0, 3, 3, 2, 2,  // Face 0-3-2
            1, 1, 2, 2, 3, 3   // Face 1-2-3
        );
        
        return new MeshView(mesh);
    }
    
    /**
     * Extract the actual world-space vertices from a mesh, accounting for any transforms.
     */
    private float[][] extractActualVertices(MeshView meshView) {
        TriangleMesh mesh = (TriangleMesh) meshView.getMesh();
        float[] points = new float[mesh.getPoints().size()];
        mesh.getPoints().toArray(points);
        
        int numVertices = points.length / 3;
        float[][] vertices = new float[numVertices][3];
        
        // Get the transforms
        Affine combinedTransform = new Affine();
        for (Transform t : meshView.getTransforms()) {
            combinedTransform.append(t);
        }
        
        // Transform each vertex
        for (int i = 0; i < numVertices; i++) {
            double x = points[i * 3];
            double y = points[i * 3 + 1];
            double z = points[i * 3 + 2];
            
            // Apply transform
            double tx = combinedTransform.getMxx() * x + combinedTransform.getMxy() * y + 
                       combinedTransform.getMxz() * z + combinedTransform.getTx();
            double ty = combinedTransform.getMyx() * x + combinedTransform.getMyy() * y + 
                       combinedTransform.getMyz() * z + combinedTransform.getTy();
            double tz = combinedTransform.getMzx() * x + combinedTransform.getMzy() * y + 
                       combinedTransform.getMzz() * z + combinedTransform.getTz();
            
            vertices[i][0] = (float) tx;
            vertices[i][1] = (float) ty;
            vertices[i][2] = (float) tz;
        }
        
        return vertices;
    }
    
    /**
     * Check that two sets of vertices match (order may differ).
     */
    private void assertVerticesMatch(float[][] expected, float[][] actual, int type) {
        // For each expected vertex, find a matching actual vertex
        boolean[] matched = new boolean[actual.length];
        
        for (int i = 0; i < expected.length; i++) {
            boolean foundMatch = false;
            for (int j = 0; j < actual.length; j++) {
                if (!matched[j]) {
                    float dx = Math.abs(expected[i][0] - actual[j][0]);
                    float dy = Math.abs(expected[i][1] - actual[j][1]);
                    float dz = Math.abs(expected[i][2] - actual[j][2]);
                    
                    if (dx < 0.01f && dy < 0.01f && dz < 0.01f) {
                        matched[j] = true;
                        foundMatch = true;
                        break;
                    }
                }
            }
            
            assertTrue(foundMatch, 
                String.format("S%d: Could not find match for vertex (%.2f, %.2f, %.2f)",
                    type, expected[i][0], expected[i][1], expected[i][2]));
        }
        
        // All actual vertices should be matched
        for (int i = 0; i < matched.length; i++) {
            assertTrue(matched[i], 
                String.format("S%d: Actual vertex %d was not matched", type, i));
        }
    }
    
    /**
     * Check that edge lengths match between two tetrahedra.
     */
    private void assertEdgeLengthsMatch(float[][] vertices1, float[][] vertices2, int type) {
        float[] edges1 = calculateEdgeLengths(vertices1);
        float[] edges2 = calculateEdgeLengths(vertices2);
        
        // Sort edges to compare regardless of vertex order
        Arrays.sort(edges1);
        Arrays.sort(edges2);
        
        for (int i = 0; i < 6; i++) {
            assertEquals(edges1[i], edges2[i], 0.01f,
                String.format("S%d: Edge %d length mismatch", type, i));
        }
    }
    
    /**
     * Calculate all 6 edge lengths of a tetrahedron.
     */
    private float[] calculateEdgeLengths(float[][] vertices) {
        float[] edges = new float[6];
        int idx = 0;
        
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                float dx = vertices[i][0] - vertices[j][0];
                float dy = vertices[i][1] - vertices[j][1];
                float dz = vertices[i][2] - vertices[j][2];
                edges[idx++] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
        
        return edges;
    }
    
    /**
     * Calculate the volume of a tetrahedron using the scalar triple product.
     */
    private float calculateTetrahedronVolume(float[][] vertices) {
        // Volume = |det(v1-v0, v2-v0, v3-v0)| / 6
        float[] v0 = vertices[0];
        float[] v1 = vertices[1];
        float[] v2 = vertices[2];
        float[] v3 = vertices[3];
        
        float ax = v1[0] - v0[0];
        float ay = v1[1] - v0[1];
        float az = v1[2] - v0[2];
        
        float bx = v2[0] - v0[0];
        float by = v2[1] - v0[1];
        float bz = v2[2] - v0[2];
        
        float cx = v3[0] - v0[0];
        float cy = v3[1] - v0[1];
        float cz = v3[2] - v0[2];
        
        // Scalar triple product
        float det = ax * (by * cz - bz * cy) - 
                   ay * (bx * cz - bz * cx) + 
                   az * (bx * cy - by * cx);
        
        return Math.abs(det) / 6.0f;
    }
}
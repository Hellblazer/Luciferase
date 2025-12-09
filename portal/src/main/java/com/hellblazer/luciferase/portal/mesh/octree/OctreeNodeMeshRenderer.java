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
package com.hellblazer.luciferase.portal.mesh.octree;

import com.hellblazer.luciferase.esvo.util.ESVONodeGeometry;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Translate;

import javax.vecmath.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Prototype renderer for ESVO octree nodes, providing three rendering strategies for performance comparison:
 * <ol>
 *   <li><b>INSTANCING</b>: Creates individual Box shapes for each node (simple, high overhead)</li>
 *   <li><b>BATCHED</b>: Merges all nodes into a single TriangleMesh (complex, low overhead)</li>
 *   <li><b>HYBRID</b>: Uses reference mesh with MeshView instances (balanced approach)</li>
 * </ol>
 *
 * <p>This class is designed for Phase 0.4 benchmarking to determine the optimal rendering approach
 * for the ESVO Octree Inspector visualization tool.</p>
 *
 * @author hal.hildebrand
 */
public class OctreeNodeMeshRenderer {

    /**
     * Rendering strategy enumeration.
     */
    public enum Strategy {
        /** Individual Box shapes per node - simplest but highest overhead */
        INSTANCING,
        /** Single merged TriangleMesh - most complex but lowest overhead */
        BATCHED,
        /** Reference mesh with MeshView instances - balanced approach */
        HYBRID
    }

    private final int maxDepth;
    private final Strategy strategy;
    private final PhongMaterial material;

    // Reference mesh for HYBRID strategy (shared across all nodes)
    private TriangleMesh referenceMesh;

    /**
     * Create a new octree node renderer.
     *
     * @param maxDepth Maximum octree depth for node calculations
     * @param strategy Rendering strategy to use
     * @param material Material to apply to rendered nodes
     */
    public OctreeNodeMeshRenderer(int maxDepth, Strategy strategy, PhongMaterial material) {
        this.maxDepth = maxDepth;
        this.strategy = strategy;
        this.material = material;

        if (strategy == Strategy.HYBRID) {
            // Create unit cube reference mesh for instancing
            this.referenceMesh = createUnitCubeMesh();
        }
    }

    /**
     * Convenience constructor with default material (semi-transparent blue).
     *
     * @param maxDepth Maximum octree depth
     * @param strategy Rendering strategy
     */
    public OctreeNodeMeshRenderer(int maxDepth, Strategy strategy) {
        this(maxDepth, strategy, createDefaultMaterial());
    }

    /**
     * Render a collection of octree nodes using the configured strategy.
     *
     * @param nodeIndices List of octree node indices to render
     * @return A Group containing the rendered nodes
     */
    public Group render(List<Integer> nodeIndices) {
        return switch (strategy) {
            case INSTANCING -> renderWithInstancing(nodeIndices);
            case BATCHED -> renderWithBatching(nodeIndices);
            case HYBRID -> renderWithHybrid(nodeIndices);
        };
    }

    /**
     * Get the current rendering strategy.
     *
     * @return The strategy
     */
    public Strategy getStrategy() {
        return strategy;
    }

    /**
     * INSTANCING strategy: Create individual Box shapes for each node.
     * <p>
     * Pros: Simple, easy to debug, supports per-node materials
     * Cons: High memory overhead, many scene graph nodes, slower rendering
     * </p>
     */
    private Group renderWithInstancing(List<Integer> nodeIndices) {
        var group = new Group();

        for (int nodeIndex : nodeIndices) {
            var bounds = ESVONodeGeometry.getNodeBounds(nodeIndex, maxDepth);

            // Calculate size and center
            float width = bounds.max.x - bounds.min.x;
            float height = bounds.max.y - bounds.min.y;
            float depth = bounds.max.z - bounds.min.z;

            float centerX = (bounds.min.x + bounds.max.x) / 2.0f;
            float centerY = (bounds.min.y + bounds.max.y) / 2.0f;
            float centerZ = (bounds.min.z + bounds.max.z) / 2.0f;

            // Create Box at center with proper dimensions
            Box box = new Box(width, height, depth);
            box.setTranslateX(centerX);
            box.setTranslateY(centerY);
            box.setTranslateZ(centerZ);
            box.setMaterial(material);
            box.setDrawMode(DrawMode.LINE); // Wireframe
            box.setCullFace(CullFace.NONE);

            group.getChildren().add(box);
        }

        return group;
    }

    /**
     * BATCHED strategy: Merge all nodes into a single TriangleMesh.
     * <p>
     * Pros: Minimal memory overhead, single scene graph node, fast rendering
     * Cons: Complex implementation, harder to debug, no per-node materials
     * </p>
     */
    private Group renderWithBatching(List<Integer> nodeIndices) {
        var group = new Group();

        // Collect all vertices and faces
        var vertices = new ArrayList<Float>();
        var texCoords = new ArrayList<Float>();
        var faces = new ArrayList<Integer>();

        // Add single texture coordinate (required by JavaFX)
        texCoords.add(0.0f);
        texCoords.add(0.0f);

        int vertexOffset = 0;

        for (int nodeIndex : nodeIndices) {
            var bounds = ESVONodeGeometry.getNodeBounds(nodeIndex, maxDepth);

            // Add 8 cube vertices for this node
            float minX = bounds.min.x;
            float minY = bounds.min.y;
            float minZ = bounds.min.z;
            float maxX = bounds.max.x;
            float maxY = bounds.max.y;
            float maxZ = bounds.max.z;

            // Cube vertices (8 corners)
            float[] nodeVertices = {
                minX, minY, minZ,  // 0: min corner
                maxX, minY, minZ,  // 1
                minX, maxY, minZ,  // 2
                maxX, maxY, minZ,  // 3
                minX, minY, maxZ,  // 4
                maxX, minY, maxZ,  // 5
                minX, maxY, maxZ,  // 6
                maxX, maxY, maxZ   // 7: max corner
            };

            for (float v : nodeVertices) {
                vertices.add(v);
            }

            // Add 12 triangular faces (2 per cube face, 6 faces total)
            // Front face (z = minZ)
            addTriangle(faces, vertexOffset + 0, vertexOffset + 1, vertexOffset + 3);
            addTriangle(faces, vertexOffset + 0, vertexOffset + 3, vertexOffset + 2);

            // Back face (z = maxZ)
            addTriangle(faces, vertexOffset + 4, vertexOffset + 6, vertexOffset + 7);
            addTriangle(faces, vertexOffset + 4, vertexOffset + 7, vertexOffset + 5);

            // Left face (x = minX)
            addTriangle(faces, vertexOffset + 0, vertexOffset + 2, vertexOffset + 6);
            addTriangle(faces, vertexOffset + 0, vertexOffset + 6, vertexOffset + 4);

            // Right face (x = maxX)
            addTriangle(faces, vertexOffset + 1, vertexOffset + 5, vertexOffset + 7);
            addTriangle(faces, vertexOffset + 1, vertexOffset + 7, vertexOffset + 3);

            // Bottom face (y = minY)
            addTriangle(faces, vertexOffset + 0, vertexOffset + 4, vertexOffset + 5);
            addTriangle(faces, vertexOffset + 0, vertexOffset + 5, vertexOffset + 1);

            // Top face (y = maxY)
            addTriangle(faces, vertexOffset + 2, vertexOffset + 3, vertexOffset + 7);
            addTriangle(faces, vertexOffset + 2, vertexOffset + 7, vertexOffset + 6);

            vertexOffset += 8; // Move to next cube's vertices
        }

        // Create mesh
        var mesh = new TriangleMesh();
        
        // Add vertices
        float[] vertexArray = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            vertexArray[i] = vertices.get(i);
        }
        mesh.getPoints().addAll(vertexArray);

        // Add texture coordinates
        float[] texCoordArray = new float[texCoords.size()];
        for (int i = 0; i < texCoords.size(); i++) {
            texCoordArray[i] = texCoords.get(i);
        }
        mesh.getTexCoords().addAll(texCoordArray);

        // Add faces
        int[] faceArray = new int[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            faceArray[i] = faces.get(i);
        }
        mesh.getFaces().addAll(faceArray);

        // Create mesh view
        var meshView = new MeshView(mesh);
        meshView.setMaterial(material);
        meshView.setDrawMode(DrawMode.LINE); // Wireframe
        meshView.setCullFace(CullFace.NONE);

        group.getChildren().add(meshView);
        return group;
    }

    /**
     * HYBRID strategy: Use reference mesh with transformed MeshView instances.
     * <p>
     * Pros: Balanced memory/performance, simpler than batching, supports transforms
     * Cons: More overhead than batching, less flexible than instancing
     * </p>
     */
    private Group renderWithHybrid(List<Integer> nodeIndices) {
        var group = new Group();

        for (int nodeIndex : nodeIndices) {
            var bounds = ESVONodeGeometry.getNodeBounds(nodeIndex, maxDepth);
            var center = ESVONodeGeometry.getNodeCenter(nodeIndex, maxDepth);

            // Calculate size
            float width = bounds.max.x - bounds.min.x;
            float height = bounds.max.y - bounds.min.y;
            float depth = bounds.max.z - bounds.min.z;

            // Create MeshView with reference mesh
            var meshView = new MeshView(referenceMesh);
            meshView.setMaterial(material);
            meshView.setDrawMode(DrawMode.LINE); // Wireframe
            meshView.setCullFace(CullFace.NONE);

            // Transform: scale from unit cube to actual size, then translate to position
            meshView.setScaleX(width);
            meshView.setScaleY(height);
            meshView.setScaleZ(depth);
            
            // Note: Box center is at (0.5, 0.5, 0.5) in unit cube space
            // After scaling, we need to translate to the actual center position
            meshView.getTransforms().add(new Translate(center.x, center.y, center.z));

            group.getChildren().add(meshView);
        }

        return group;
    }

    /**
     * Create a unit cube mesh (1x1x1 centered at origin).
     */
    private TriangleMesh createUnitCubeMesh() {
        var mesh = new TriangleMesh();

        // Unit cube vertices (centered at origin, size 1)
        float[] vertices = {
            -0.5f, -0.5f, -0.5f,  // 0: min corner
             0.5f, -0.5f, -0.5f,  // 1
            -0.5f,  0.5f, -0.5f,  // 2
             0.5f,  0.5f, -0.5f,  // 3
            -0.5f, -0.5f,  0.5f,  // 4
             0.5f, -0.5f,  0.5f,  // 5
            -0.5f,  0.5f,  0.5f,  // 6
             0.5f,  0.5f,  0.5f   // 7: max corner
        };
        mesh.getPoints().addAll(vertices);

        // Texture coordinates (single point)
        mesh.getTexCoords().addAll(0.0f, 0.0f);

        // Faces (12 triangles, 2 per cube face)
        int[] faces = new int[72]; // 12 triangles * 6 values per triangle
        int idx = 0;

        // Front face (z = -0.5)
        faces[idx++] = 0; faces[idx++] = 0; faces[idx++] = 1; faces[idx++] = 0; faces[idx++] = 3; faces[idx++] = 0;
        faces[idx++] = 0; faces[idx++] = 0; faces[idx++] = 3; faces[idx++] = 0; faces[idx++] = 2; faces[idx++] = 0;

        // Back face (z = 0.5)
        faces[idx++] = 4; faces[idx++] = 0; faces[idx++] = 6; faces[idx++] = 0; faces[idx++] = 7; faces[idx++] = 0;
        faces[idx++] = 4; faces[idx++] = 0; faces[idx++] = 7; faces[idx++] = 0; faces[idx++] = 5; faces[idx++] = 0;

        // Left face (x = -0.5)
        faces[idx++] = 0; faces[idx++] = 0; faces[idx++] = 2; faces[idx++] = 0; faces[idx++] = 6; faces[idx++] = 0;
        faces[idx++] = 0; faces[idx++] = 0; faces[idx++] = 6; faces[idx++] = 0; faces[idx++] = 4; faces[idx++] = 0;

        // Right face (x = 0.5)
        faces[idx++] = 1; faces[idx++] = 0; faces[idx++] = 5; faces[idx++] = 0; faces[idx++] = 7; faces[idx++] = 0;
        faces[idx++] = 1; faces[idx++] = 0; faces[idx++] = 7; faces[idx++] = 0; faces[idx++] = 3; faces[idx++] = 0;

        // Bottom face (y = -0.5)
        faces[idx++] = 0; faces[idx++] = 0; faces[idx++] = 4; faces[idx++] = 0; faces[idx++] = 5; faces[idx++] = 0;
        faces[idx++] = 0; faces[idx++] = 0; faces[idx++] = 5; faces[idx++] = 0; faces[idx++] = 1; faces[idx++] = 0;

        // Top face (y = 0.5)
        faces[idx++] = 2; faces[idx++] = 0; faces[idx++] = 3; faces[idx++] = 0; faces[idx++] = 7; faces[idx++] = 0;
        faces[idx++] = 2; faces[idx++] = 0; faces[idx++] = 7; faces[idx++] = 0; faces[idx++] = 6; faces[idx++] = 0;

        mesh.getFaces().addAll(faces);

        return mesh;
    }

    /**
     * Helper to add a triangle face to the face list.
     * Each triangle needs 6 values: v1, t1, v2, t2, v3, t3 (vertex and texture coord indices).
     */
    private void addTriangle(List<Integer> faces, int v1, int v2, int v3) {
        faces.add(v1); faces.add(0);  // Vertex 1, texture coord 0
        faces.add(v2); faces.add(0);  // Vertex 2, texture coord 0
        faces.add(v3); faces.add(0);  // Vertex 3, texture coord 0
    }

    /**
     * Create default material (semi-transparent blue).
     */
    private static PhongMaterial createDefaultMaterial() {
        var material = new PhongMaterial(Color.DODGERBLUE.deriveColor(0, 1, 1, 0.5));
        material.setSpecularColor(Color.LIGHTBLUE);
        return material;
    }
}

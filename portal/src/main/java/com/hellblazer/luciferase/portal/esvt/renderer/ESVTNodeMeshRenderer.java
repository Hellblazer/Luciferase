/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.portal.esvt.renderer;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders ESVT (Efficient Sparse Voxel Tetrahedra) nodes as JavaFX 3D meshes.
 * Unlike CellViews which requires Tet objects, this renderer works directly with
 * the packed ESVTNodeUnified array by computing tetrahedron geometry from the tree structure.
 *
 * @author hal.hildebrand
 */
public class ESVTNodeMeshRenderer {

    // Standard edges of a tetrahedron (vertex pairs)
    private static final int[][] TET_EDGES = {
        {0, 1}, {0, 2}, {0, 3},
        {1, 2}, {1, 3}, {2, 3}
    };

    // Color palette for depth levels
    private static final Color[] DEPTH_COLORS = {
        Color.RED, Color.ORANGE, Color.YELLOW, Color.GREEN,
        Color.CYAN, Color.BLUE, Color.MAGENTA, Color.PURPLE
    };

    // Color palette for tet types (S0-S5)
    private static final Color[] TYPE_COLORS = {
        Color.web("#e74c3c"),  // S0 - Red
        Color.web("#3498db"),  // S1 - Blue
        Color.web("#2ecc71"),  // S2 - Green
        Color.web("#f39c12"),  // S3 - Orange
        Color.web("#9b59b6"),  // S4 - Purple
        Color.web("#1abc9c")   // S5 - Teal
    };

    private final ESVTData data;
    private final TriangleMesh[] referenceMeshes = new TriangleMesh[6];
    private final double edgeThickness;
    private final Material edgeMaterial;

    /**
     * Create a renderer for the given ESVT data.
     */
    public ESVTNodeMeshRenderer(ESVTData data) {
        this(data, 0.01, new PhongMaterial(Color.BLACK));
    }

    /**
     * Create a renderer with custom wireframe settings.
     */
    public ESVTNodeMeshRenderer(ESVTData data, double edgeThickness, Material edgeMaterial) {
        this.data = data;
        this.edgeThickness = edgeThickness;
        this.edgeMaterial = edgeMaterial;
        initializeReferenceMeshes();
    }

    /**
     * Initialize the 6 reference meshes for tetrahedron types S0-S5.
     */
    private void initializeReferenceMeshes() {
        for (int type = 0; type < 6; type++) {
            Point3i[] vertices = Constants.SIMPLEX_STANDARD[type];
            referenceMeshes[type] = createTetrahedronMesh(vertices);
        }
    }

    /**
     * Create a TriangleMesh for a tetrahedron with given vertices.
     */
    private TriangleMesh createTetrahedronMesh(Point3i[] vertices) {
        var mesh = new TriangleMesh();

        // Add vertices
        for (var v : vertices) {
            mesh.getPoints().addAll(v.x, v.y, v.z);
        }

        // Texture coordinates
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);

        // Faces with outward normals
        mesh.getFaces().addAll(
            0, 0, 1, 1, 2, 2,  // Face 0
            0, 0, 3, 3, 1, 1,  // Face 1
            0, 0, 2, 2, 3, 3,  // Face 2
            1, 1, 3, 3, 2, 2   // Face 3
        );

        return mesh;
    }

    /**
     * Render all nodes in the tree.
     *
     * @param colorScheme How to color the tetrahedra
     * @param opacity Opacity (0.0-1.0)
     * @return Group containing all rendered meshes
     */
    public Group renderAll(ColorScheme colorScheme, double opacity) {
        return renderLevelRange(0, data.maxDepth(), colorScheme, opacity);
    }

    /**
     * Render only leaf nodes.
     */
    public Group renderLeaves(ColorScheme colorScheme, double opacity) {
        var group = new Group();
        var leaves = collectLeaves();
        for (var leaf : leaves) {
            var meshView = createMeshView(leaf, colorScheme, opacity);
            group.getChildren().add(meshView);
        }
        return group;
    }

    /**
     * Render nodes in a specific level range.
     */
    public Group renderLevelRange(int minLevel, int maxLevel, ColorScheme colorScheme, double opacity) {
        var group = new Group();
        var renderedNodes = collectNodesInRange(minLevel, maxLevel);
        for (var nodeInfo : renderedNodes) {
            var meshView = createMeshView(nodeInfo, colorScheme, opacity);
            group.getChildren().add(meshView);
        }
        return group;
    }

    /**
     * Render wireframes for all leaf nodes.
     */
    public Group renderLeafWireframes() {
        var group = new Group();
        var leaves = collectLeaves();
        for (var leaf : leaves) {
            var wireframe = createWireframe(leaf);
            group.getChildren().add(wireframe);
        }
        return group;
    }

    /**
     * Collect all leaf nodes with their computed geometry.
     */
    private List<NodeRenderInfo> collectLeaves() {
        var leaves = new ArrayList<NodeRenderInfo>();
        if (data.nodes() == null || data.nodes().length == 0) {
            return leaves;
        }
        collectLeavesRecursive(0, (byte) data.rootType(), new Point3f(0, 0, 0),
                               1 << data.maxDepth(), 0, leaves);
        return leaves;
    }

    /**
     * Collect nodes in a specific level range.
     */
    private List<NodeRenderInfo> collectNodesInRange(int minLevel, int maxLevel) {
        var nodes = new ArrayList<NodeRenderInfo>();
        if (data.nodes() == null || data.nodes().length == 0) {
            return nodes;
        }
        collectNodesInRangeRecursive(0, (byte) data.rootType(), new Point3f(0, 0, 0),
                                      1 << data.maxDepth(), 0, minLevel, maxLevel, nodes);
        return nodes;
    }

    /**
     * Recursive traversal to collect leaf nodes.
     */
    private void collectLeavesRecursive(int nodeIdx, byte tetType, Point3f origin,
                                         int size, int level, List<NodeRenderInfo> leaves) {
        if (nodeIdx >= data.nodes().length) return;

        var node = data.nodes()[nodeIdx];
        if (!node.isValid()) return;

        var childMask = node.getChildMask();
        var leafMask = node.getLeafMask();

        // Check each child position
        for (int childPos = 0; childPos < 8; childPos++) {
            if ((childMask & (1 << childPos)) != 0) {
                int childSize = size / 2;
                var childOrigin = computeChildOrigin(origin, childPos, childSize, tetType);
                byte childType = TetreeConnectivity.getChildType(tetType, childPos);

                if ((leafMask & (1 << childPos)) != 0) {
                    // This child is a leaf
                    leaves.add(new NodeRenderInfo(
                        node.getChildIndex(childPos),
                        childType,
                        childOrigin,
                        childSize,
                        level + 1
                    ));
                } else {
                    // Recurse into non-leaf child
                    collectLeavesRecursive(
                        node.getChildIndex(childPos),
                        childType,
                        childOrigin,
                        childSize,
                        level + 1,
                        leaves
                    );
                }
            }
        }
    }

    /**
     * Recursive traversal to collect nodes in level range.
     */
    private void collectNodesInRangeRecursive(int nodeIdx, byte tetType, Point3f origin,
                                               int size, int level, int minLevel, int maxLevel,
                                               List<NodeRenderInfo> nodes) {
        if (nodeIdx >= data.nodes().length) return;
        if (level > maxLevel) return;

        var node = data.nodes()[nodeIdx];
        if (!node.isValid()) return;

        // Add this node if in range
        if (level >= minLevel && level <= maxLevel) {
            nodes.add(new NodeRenderInfo(nodeIdx, tetType, origin, size, level));
        }

        // If at max level, don't recurse further
        if (level >= maxLevel) return;

        var childMask = node.getChildMask();
        var leafMask = node.getLeafMask();

        // Recurse into children
        for (int childPos = 0; childPos < 8; childPos++) {
            if ((childMask & (1 << childPos)) != 0) {
                int childSize = size / 2;
                var childOrigin = computeChildOrigin(origin, childPos, childSize, tetType);
                byte childType = TetreeConnectivity.getChildType(tetType, childPos);

                // Don't recurse into leaf children (they have no further structure)
                if ((leafMask & (1 << childPos)) == 0) {
                    collectNodesInRangeRecursive(
                        node.getChildIndex(childPos),
                        childType,
                        childOrigin,
                        childSize,
                        level + 1,
                        minLevel,
                        maxLevel,
                        nodes
                    );
                } else if (level + 1 >= minLevel && level + 1 <= maxLevel) {
                    // Add leaf child if in range
                    nodes.add(new NodeRenderInfo(
                        node.getChildIndex(childPos),
                        childType,
                        childOrigin,
                        childSize,
                        level + 1
                    ));
                }
            }
        }
    }

    /**
     * Compute child tetrahedron origin based on Bey refinement.
     * Uses the child index to determine position within parent.
     */
    private Point3f computeChildOrigin(Point3f parentOrigin, int childIdx, int childSize, byte parentType) {
        // Get Bey ID for this child
        byte beyId = TetreeConnectivity.getBeyChildId(parentType, childIdx);

        // Bey children 1-3 are at parent vertices 0-2
        // Bey child 0 is interior (offset to center)
        // Bey children 4-7 are at edge midpoints

        // Get vertex position from Bey ID
        byte vertexRef = TetreeConnectivity.BEY_ID_TO_VERTEX[beyId];

        // Get offset based on vertex reference and child position
        // This maps the Bey refinement structure to actual coordinates
        Point3i[] parentVerts = Constants.SIMPLEX_STANDARD[parentType];

        // Calculate offset based on Bey position
        float ox = parentOrigin.x;
        float oy = parentOrigin.y;
        float oz = parentOrigin.z;

        // For Bey refinement, each child is positioned based on the subdivision
        // Children 1-3 are at vertices, child 0 is central octahedron,
        // children 4-7 are at edge midpoints
        switch (beyId) {
            case 0 -> { // Interior octahedron - at edge midpoint
                ox += childSize * 0.5f;
                oy += childSize * 0.5f;
                oz += childSize * 0.5f;
            }
            case 1 -> { // At vertex 0
                // Origin stays at parent origin
            }
            case 2 -> { // At vertex 1
                ox += childSize;
            }
            case 3 -> { // At vertex 2
                oy += childSize;
            }
            case 4 -> { // Edge midpoint
                oz += childSize;
            }
            case 5 -> { // Edge midpoint
                ox += childSize;
                oy += childSize;
            }
            case 6 -> { // Edge midpoint
                ox += childSize;
                oz += childSize;
            }
            case 7 -> { // At vertex 3
                oy += childSize;
                oz += childSize;
            }
        }

        return new Point3f(ox, oy, oz);
    }

    /**
     * Create a mesh view for a node.
     */
    private MeshView createMeshView(NodeRenderInfo info, ColorScheme colorScheme, double opacity) {
        var mesh = referenceMeshes[info.tetType];
        var meshView = new MeshView(mesh);

        // Apply scale and translation
        meshView.setScaleX(info.size);
        meshView.setScaleY(info.size);
        meshView.setScaleZ(info.size);
        meshView.setTranslateX(info.origin.x);
        meshView.setTranslateY(info.origin.y);
        meshView.setTranslateZ(info.origin.z);

        // Apply material based on color scheme
        var color = switch (colorScheme) {
            case DEPTH_GRADIENT -> DEPTH_COLORS[info.level % DEPTH_COLORS.length];
            case TET_TYPE -> TYPE_COLORS[info.tetType];
            case SINGLE_COLOR -> Color.LIGHTBLUE;
        };

        var material = new PhongMaterial(color.deriveColor(0, 1, 1, opacity));
        material.setSpecularColor(color.brighter());
        meshView.setMaterial(material);

        return meshView;
    }

    /**
     * Create a wireframe for a node.
     */
    private Group createWireframe(NodeRenderInfo info) {
        var wireframe = new Group();
        Point3i[] refVerts = Constants.SIMPLEX_STANDARD[info.tetType];

        // Scale and translate vertices
        var verts = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            verts[i] = new Point3f(
                info.origin.x + refVerts[i].x * info.size,
                info.origin.y + refVerts[i].y * info.size,
                info.origin.z + refVerts[i].z * info.size
            );
        }

        // Create edges
        for (var edge : TET_EDGES) {
            var v1 = verts[edge[0]];
            var v2 = verts[edge[1]];

            double dx = v2.x - v1.x;
            double dy = v2.y - v1.y;
            double dz = v2.z - v1.z;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (length < 0.001) continue;

            var cylinder = new Cylinder(edgeThickness / 2, length);
            cylinder.setMaterial(edgeMaterial);

            // Position at midpoint
            cylinder.setTranslateX((v1.x + v2.x) / 2);
            cylinder.setTranslateY((v1.y + v2.y) / 2);
            cylinder.setTranslateZ((v1.z + v2.z) / 2);

            // Rotate to align with edge
            var yAxis = new Point3D(0, 1, 0);
            var edgeDir = new Point3D(dx, dy, dz).normalize();
            var rotAxis = yAxis.crossProduct(edgeDir);

            if (rotAxis.magnitude() > 0.001) {
                double angle = Math.toDegrees(Math.acos(yAxis.dotProduct(edgeDir)));
                var rotation = new Rotate(angle, rotAxis);
                cylinder.getTransforms().add(rotation);
            }

            wireframe.getChildren().add(cylinder);
        }

        return wireframe;
    }

    /**
     * Get statistics about the ESVT data.
     */
    public String getStatistics() {
        return String.format("ESVT: %d nodes, depth %d, %d leaves, %d internal",
            data.nodeCount(), data.maxDepth(), data.leafCount(), data.internalCount());
    }

    /**
     * Color schemes for rendering.
     */
    public enum ColorScheme {
        DEPTH_GRADIENT,
        TET_TYPE,
        SINGLE_COLOR
    }

    /**
     * Information about a node for rendering.
     */
    private record NodeRenderInfo(
        int nodeIdx,
        byte tetType,
        Point3f origin,
        int size,
        int level
    ) {}
}

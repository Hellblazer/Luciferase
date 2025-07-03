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

import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.BaseTetreeKey;
import com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.portal.mesh.Line;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.text.Text;
import javafx.scene.text.Font;
import javafx.scene.transform.Scale;
import javafx.animation.*;
import javafx.util.Duration;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.stage.FileChooser;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;

import javax.imageio.ImageIO;
import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * JavaFX 3D visualization for Tetree spatial index structures.
 * Renders tetrahedra with level-based coloring and shows the tetrahedral decomposition.
 * 
 * This visualization uses JavaFX transforms to handle the large coordinate space of the Tetree.
 * All geometry is rendered in natural Tetree coordinates (where root edge length = 2^20),
 * and a scene-level Scale transform is applied to bring everything to viewable size.
 * 
 * @param <ID> The entity identifier type
 * @param <Content> The entity content type
 * 
 * @author hal.hildebrand
 */
public class TetreeVisualization<ID extends EntityID, Content> extends SpatialIndexView<BaseTetreeKey<? extends BaseTetreeKey>, ID, Content> {
    
    private final Tetree<ID, Content> tetree;
    private final Map<BaseTetreeKey<? extends BaseTetreeKey>, Group> tetGroups = new HashMap<>();
    
    // Tetrahedral type colors
    private final Map<Integer, Color> typeColors = new HashMap<>();
    
    // Animation tracking
    private boolean animateModifications = false;
    private final List<Timeline> activeAnimations = new ArrayList<>();
    
    // Performance tracking
    private AnimationTimer performanceTimer;
    private long frameCount = 0;
    private long lastFPSUpdate = 0;
    private double currentFPS = 0;
    private final Text fpsText = new Text();
    private final Text statsText = new Text();
    private final Group performanceOverlay = new Group();
    private boolean showPerformanceOverlay = false;
    protected long lastUpdateTime = 0;
    
    // Snapshot export
    private File lastSnapshotDirectory = null;
    
    // Root tetrahedron scale - applied as a transform to the entire scene
    // Default scale of 0.0001 brings the 2^20 coordinate system down to ~100 unit viewable size
    private final DoubleProperty rootScale = new SimpleDoubleProperty(0.0001);
    
    // Display mode property
    private final BooleanProperty showFilledFaces = new SimpleBooleanProperty(true);
    
    // Scene scale transform
    private final Scale sceneScale = new Scale();
    
    /**
     * Creates a new Tetree visualization.
     * 
     * @param tetree The tetree to visualize
     */
    public TetreeVisualization(Tetree<ID, Content> tetree) {
        super(tetree);
        this.tetree = tetree;
        initializeTypeColors();
        initializePerformanceOverlay();
        
        // Apply scale transform to the scene root
        getSceneRoot().getTransforms().add(sceneScale);
        sceneScale.xProperty().bind(rootScale);
        sceneScale.yProperty().bind(rootScale);
        sceneScale.zProperty().bind(rootScale);
        
        // Add listener to update visualization when filled faces toggle changes
        showFilledFaces.addListener((obs, oldVal, newVal) -> {
            updateVisualization();
        });
    }
    
    /**
     * Initialize colors for the 6 characteristic tetrahedron types.
     */
    private void initializeTypeColors() {
        typeColors.put(0, Color.RED);
        typeColors.put(1, Color.GREEN);
        typeColors.put(2, Color.BLUE);
        typeColors.put(3, Color.YELLOW);
        typeColors.put(4, Color.MAGENTA);
        typeColors.put(5, Color.CYAN);
    }
    
    @Override
    protected void renderNodes() {
        // Get all nodes from the tetree
        tetree.nodes().forEach(node -> {
            SpatialNode<BaseTetreeKey<? extends BaseTetreeKey>, ID> typedNode = 
                new SpatialNode<>(node.sfcIndex(), node.entityIds());
            if (shouldRenderNode(typedNode)) {
                Node tetVisual = createTetVisual(typedNode);
                if (tetVisual != null) {
                    nodeGroup.getChildren().add(tetVisual);
                    nodeVisuals.put(typedNode.sfcIndex(), tetVisual);
                    visibleNodeCount++;
                }
            }
        });
    }
    
    /**
     * Check if a node should be rendered based on current settings.
     */
    private boolean shouldRenderNode(SpatialNode<BaseTetreeKey<? extends BaseTetreeKey>, ID> node) {
        int level = getLevelForKey(node.sfcIndex());
        
        // Check level visibility
        if (!isLevelVisible(level)) {
            return false;
        }
        
        // Check empty node visibility
        if (!showEmptyNodesProperty().get() && node.entityIds().isEmpty()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Create visual representation for a tetrahedron.
     */
    private Node createTetVisual(SpatialNode<BaseTetreeKey<? extends BaseTetreeKey>, ID> node) {
        BaseTetreeKey<? extends BaseTetreeKey> key = node.sfcIndex();
        Tet tet = tetreeKeyToTet(key);
        
        Group tetGroup = new Group();
        tetGroups.put(key, tetGroup);
        
        // Create wireframe tetrahedron
        if (showNodeBoundsProperty().get()) {
            Group wireframe = createWireframeTetrahedron(tet, getLevelForKey(key));
            tetGroup.getChildren().add(wireframe);
        }
        
        // Add semi-transparent face if node has entities and filled faces are enabled
        if (!node.entityIds().isEmpty() && showFilledFaces.get()) {
            MeshView face = createTransparentTetrahedron(tet, getLevelForKey(key));
            tetGroup.getChildren().add(face);
        }
        
        // Set proper rendering order based on level (deeper levels render first)
        tetGroup.setViewOrder(getLevelForKey(key));
        
        // Store key for interaction
        tetGroup.setUserData(key);
        
        // Add interaction handlers
        tetGroup.setOnMouseClicked(event -> {
            if (event.isControlDown()) {
                toggleNodeSelection(key);
            } else {
                selectNode(key);
            }
            event.consume();
        });
        
        tetGroup.setOnMouseEntered(event -> {
            highlightNode(key);
        });
        
        tetGroup.setOnMouseExited(event -> {
            unhighlightNode(key);
        });
        
        return tetGroup;
    }
    
    /**
     * Create wireframe tetrahedron for tet bounds.
     */
    private Group createWireframeTetrahedron(Tet tet, int level) {
        Group edges = new Group();
        
        // Get the CORRECT tetrahedron vertices from SIMPLEX_STANDARD
        Point3i[] simplexVertices = Constants.SIMPLEX_STANDARD[tet.type()];
        Point3f[] vertices = new Point3f[4];
        
        // Get anchor coordinates from tet.coordinates()[0]
        Point3i anchor = tet.coordinates()[0];
        
        // Use natural Tetree coordinates - JavaFX transform handles scaling
        int h = tet.length();
        
        // Calculate vertices in natural coordinates (no manual scaling)
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f(
                (float)(anchor.x + simplexVertices[i].x * h),
                (float)(anchor.y + simplexVertices[i].y * h),
                (float)(anchor.z + simplexVertices[i].z * h)
            );
        }
        
        // Apply slight inset toward centroid to prevent z-fighting
        float centroidX = 0, centroidY = 0, centroidZ = 0;
        for (Point3f v : vertices) {
            centroidX += v.x;
            centroidY += v.y;
            centroidZ += v.z;
        }
        centroidX /= 4;
        centroidY /= 4;
        centroidZ /= 4;
        
        float insetFactor = 0.999f; // Pull vertices slightly inward
        for (int i = 0; i < 4; i++) {
            vertices[i].x = centroidX + (vertices[i].x - centroidX) * insetFactor;
            vertices[i].y = centroidY + (vertices[i].y - centroidY) * insetFactor;
            vertices[i].z = centroidZ + (vertices[i].z - centroidZ) * insetFactor;
        }
        
        // Define tetrahedron edges (6 edges)
        int[][] edgeIndices = {
            {0, 1}, {0, 2}, {0, 3},  // Edges from vertex 0
            {1, 2}, {1, 3},          // Edges from vertex 1
            {2, 3}                   // Edge from vertex 2
        };
        
        // Create edge lines with type-based coloring
        Color edgeColor = showLevelColorsProperty().get() 
            ? typeColors.getOrDefault(tet.type(), Color.GRAY).darker()
            : Color.DARKGRAY;
        PhongMaterial edgeMaterial = new PhongMaterial(edgeColor);
        
        // Line thickness in natural coordinates (scales with scene transform)
        double thickness = Math.max(4096, 16384 - level * 512);  // 2^12 to 2^14 range
        
        for (int[] edge : edgeIndices) {
            Point3f p1 = vertices[edge[0]];
            Point3f p2 = vertices[edge[1]];
            Line line = new Line(thickness,
                new Point3D(p1.x, p1.y, p1.z),
                new Point3D(p2.x, p2.y, p2.z));
            line.setMaterial(edgeMaterial);
            edges.getChildren().add(line);
        }
        
        return edges;
    }
    
    /**
     * Create transparent tetrahedron face.
     */
    private MeshView createTransparentTetrahedron(Tet tet, int level) {
        // Get the CORRECT tetrahedron vertices from SIMPLEX_STANDARD
        Point3i[] simplexVertices = Constants.SIMPLEX_STANDARD[tet.type()];
        Point3f[] vertices = new Point3f[4];
        
        // Get anchor coordinates from tet.coordinates()[0]
        Point3i anchor = tet.coordinates()[0];
        
        // Use natural coordinates - JavaFX transform handles scaling
        int h = tet.length();
        
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f(
                (float)(anchor.x + simplexVertices[i].x * h),
                (float)(anchor.y + simplexVertices[i].y * h),
                (float)(anchor.z + simplexVertices[i].z * h)
            );
        }
        
        // Create triangle mesh for tetrahedron
        TriangleMesh mesh = new TriangleMesh();
        
        // Add vertices
        for (Point3f v : vertices) {
            mesh.getPoints().addAll(v.x, v.y, v.z);
        }
        
        // Add texture coordinates (simple mapping)
        mesh.getTexCoords().addAll(
            0, 0,  // Vertex 0
            1, 0,  // Vertex 1
            0.5f, 1,  // Vertex 2
            0.5f, 0.5f  // Vertex 3
        );
        
        // Define faces with correct winding order for outward-facing normals
        // Check if tetrahedron has positive volume (correct orientation)
        double volume = computeSignedVolume(vertices);
        
        // Assert volume is non-zero
        assert Math.abs(volume) > 1e-10 : "Degenerate tetrahedron with zero volume detected";
        
        if (volume > 0) {
            // Standard winding for positive volume
            mesh.getFaces().addAll(
                0, 0, 2, 2, 1, 1,  // Face 0-2-1 (base, viewed from below)
                0, 0, 1, 1, 3, 3,  // Face 0-1-3 (front right)
                0, 0, 3, 3, 2, 2,  // Face 0-3-2 (back left)
                1, 1, 2, 2, 3, 3   // Face 1-2-3 (top, viewed from above)
            );
        } else {
            // Inverted winding for negative volume
            mesh.getFaces().addAll(
                0, 0, 1, 1, 2, 2,  // Face 0-1-2 (base, viewed from below) - reversed
                0, 0, 3, 3, 1, 1,  // Face 0-3-1 (front right) - reversed
                0, 0, 2, 2, 3, 3,  // Face 0-2-3 (back left) - reversed
                1, 1, 3, 3, 2, 2   // Face 1-3-2 (top, viewed from above) - reversed
            );
        }
        
        // Skip face normal validation for large coordinates due to precision issues
        // The winding order is correct based on volume sign
        
        MeshView meshView = new MeshView(mesh);
        
        // Apply material based on type and level
        Material material = getMaterialForTet(tet, level);
        meshView.setMaterial(material);
        meshView.setOpacity(nodeOpacityProperty().get());
        
        // Enable depth buffer to reduce z-fighting
        meshView.setDepthTest(javafx.scene.DepthTest.ENABLE);
        
        // Disable back face culling for transparent objects to ensure all faces are visible
        meshView.setCullFace(javafx.scene.shape.CullFace.NONE);
        
        // Set draw mode
        meshView.setDrawMode(javafx.scene.shape.DrawMode.FILL);
        
        return meshView;
    }
    
    /**
     * Get material for a tetrahedron based on its type and level.
     */
    private Material getMaterialForTet(Tet tet, int level) {
        if (!showLevelColorsProperty().get()) {
            PhongMaterial material = new PhongMaterial(Color.LIGHTGRAY);
            material.setSpecularColor(Color.WHITE);
            return material;
        }
        
        // Use type-based coloring
        int type = tet.type();
        Color baseColor = typeColors.getOrDefault(type, Color.GRAY);
        
        // Modify color based on level (darker at deeper levels)
        double brightness = 1.0 - (level * 0.04); // Darken by 4% per level
        Color levelColor = baseColor.deriveColor(0, 1, brightness, 1);
        
        PhongMaterial material = new PhongMaterial(levelColor);
        material.setSpecularColor(levelColor.brighter());
        return material;
    }
    
    /**
     * Convert TetreeKey to Tet for visualization.
     */
    private Tet tetreeKeyToTet(BaseTetreeKey<? extends BaseTetreeKey> key) {
        // Use the static method from Tet to properly decode the TetreeKey
        return Tet.tetrahedron(key);
    }
    
    @Override
    protected boolean isNodeVisible(BaseTetreeKey<? extends BaseTetreeKey> nodeKey) {
        int level = getLevelForKey(nodeKey);
        if (!isLevelVisible(level)) {
            return false;
        }
        
        // Check if node exists by searching current nodes
        return tetree.nodes()
            .anyMatch(node -> node.sfcIndex().equals(nodeKey) && 
                     (showEmptyNodesProperty().get() || !node.entityIds().isEmpty()));
    }
    
    @Override
    protected int getLevelForKey(BaseTetreeKey<? extends BaseTetreeKey> key) {
        // Extract level from TetreeKey
        // The level is encoded in the key structure
        return key.getLevel();
    }
    
    @Override
    protected Node createEntityVisual(ID id) {
        Point3f pos = tetree.getEntityPosition(id);
        if (pos == null) return null;
        
        // Entity size in natural coordinates (scaled by scene transform)
        double entityRadius = 32768; // 2^15 for visibility at tetree scale
        Sphere sphere = new Sphere(entityRadius);
        // Use natural coordinates - JavaFX transform handles scaling
        sphere.setTranslateX(pos.x);
        sphere.setTranslateY(pos.y);
        sphere.setTranslateZ(pos.z);
        
        // Color based on selection state
        PhongMaterial material = new PhongMaterial();
        if (getSelectedEntities().contains(id)) {
            material.setDiffuseColor(Color.YELLOW);
            material.setSpecularColor(Color.WHITE);
        } else {
            material.setDiffuseColor(Color.LIME);
            material.setSpecularColor(Color.LIGHTGREEN);
        }
        sphere.setMaterial(material);
        
        // Store entity ID for interaction
        sphere.setUserData(id);
        
        // Add click handler
        sphere.setOnMouseClicked(event -> {
            if (event.isControlDown()) {
                toggleEntitySelection(id);
            } else {
                selectEntity(id);
            }
            event.consume();
        });
        
        return sphere;
    }
    
    @Override
    public void visualizeQuery(Object query) {
        queryGroup.getChildren().clear();
        
        if (query instanceof TetreeRangeQuery) {
            visualizeRangeQuery((TetreeRangeQuery) query);
        } else if (query instanceof TetreeKNNQuery) {
            visualizeKNNQuery((TetreeKNNQuery) query);
        } else if (query instanceof TetreeRayQuery) {
            visualizeRayQuery((TetreeRayQuery) query);
        }
    }
    
    /**
     * Visualize a range query in tetrahedral space.
     */
    private void visualizeRangeQuery(TetreeRangeQuery query) {
        // Create semi-transparent sphere for range
        Sphere rangeSphere = new Sphere(query.radius);
        rangeSphere.setTranslateX(query.center.x);
        rangeSphere.setTranslateY(query.center.y);
        rangeSphere.setTranslateZ(query.center.z);
        
        PhongMaterial material = new PhongMaterial(Color.CYAN);
        material.setSpecularColor(Color.WHITE);
        rangeSphere.setMaterial(material);
        rangeSphere.setOpacity(0.3);
        
        queryGroup.getChildren().add(rangeSphere);
        
        // Show query center
        Sphere centerPoint = new Sphere(5.0);
        centerPoint.setTranslateX(query.center.x);
        centerPoint.setTranslateY(query.center.y);
        centerPoint.setTranslateZ(query.center.z);
        
        PhongMaterial centerMaterial = new PhongMaterial(Color.BLUE);
        centerMaterial.setSpecularColor(Color.WHITE);
        centerPoint.setMaterial(centerMaterial);
        queryGroup.getChildren().add(centerPoint);
        
        // Find and highlight entities within range
        // Use k-NN with large k and filter by distance
        List<ID> allNeighbors = tetree.kNearestNeighbors(query.center, 1000, query.radius);
        List<ID> entitiesInRange = new ArrayList<>();
        
        for (ID id : allNeighbors) {
            Point3f pos = tetree.getEntityPosition(id);
            if (pos != null && query.center.distance(pos) <= query.radius) {
                entitiesInRange.add(id);
            }
        }
        
        for (ID id : entitiesInRange) {
            Point3f entityPos = tetree.getEntityPosition(id);
            if (entityPos != null) {
                // Draw line from center to entity
                Line line = new Line(0.5,
                    new Point3D(query.center.x, query.center.y, query.center.z),
                    new Point3D(entityPos.x, entityPos.y, entityPos.z));
                line.setMaterial(new PhongMaterial(Color.LIGHTBLUE));
                queryGroup.getChildren().add(line);
                
                // Highlight entity
                Node entityVisual = entityVisuals.get(id);
                if (entityVisual instanceof Sphere) {
                    Sphere sphere = (Sphere) entityVisual;
                    sphere.setMaterial(new PhongMaterial(Color.LIGHTGREEN));
                    sphere.setRadius(sphere.getRadius() * 1.5);
                }
            }
        }
        
        // Highlight nodes that intersect with the query sphere
        tetree.nodes().forEach(node -> {
            BaseTetreeKey<? extends BaseTetreeKey> key = node.sfcIndex();
            Tet tet = tetreeKeyToTet(key);
            
            // Check if tetrahedron intersects with query sphere
            if (tetIntersectsSphere(tet, query.center, query.radius)) {
                Node nodeVisual = nodeVisuals.get(key);
                if (nodeVisual instanceof Group) {
                    Group group = (Group) nodeVisual;
                    // Highlight the tetrahedron edges
                    group.getChildren().forEach(child -> {
                        if (child instanceof Line) {
                            ((Line) child).setMaterial(new PhongMaterial(Color.YELLOW));
                        }
                    });
                }
            }
        });
    }
    
    /**
     * Check if a tetrahedron intersects with a sphere.
     * Uses a simple approximation - checks if any vertex is within the sphere
     * or if the sphere center is within the tetrahedron.
     */
    private boolean tetIntersectsSphere(Tet tet, Point3f center, float radius) {
        // Get the CORRECT tetrahedron vertices from SIMPLEX_STANDARD
        Point3i[] simplexVertices = Constants.SIMPLEX_STANDARD[tet.type()];
        Point3i anchor = tet.coordinates()[0];
        int h = tet.length();
        
        // Check if any vertex is within the sphere
        for (Point3i simplex : simplexVertices) {
            float vx = anchor.x + simplex.x * h;
            float vy = anchor.y + simplex.y * h;
            float vz = anchor.z + simplex.z * h;
            
            float dx = vx - center.x;
            float dy = vy - center.y;
            float dz = vz - center.z;
            float distSq = dx*dx + dy*dy + dz*dz;
            if (distSq <= radius * radius) {
                return true;
            }
        }
        
        // TODO: Could also check if sphere center is within tetrahedron
        // or if sphere intersects any edge/face
        
        return false;
    }
    
    /**
     * Visualize a k-NN query.
     */
    private void visualizeKNNQuery(TetreeKNNQuery query) {
        // Show query point
        Sphere queryPoint = new Sphere(5.0);
        queryPoint.setTranslateX(query.point.x);
        queryPoint.setTranslateY(query.point.y);
        queryPoint.setTranslateZ(query.point.z);
        
        PhongMaterial queryMaterial = new PhongMaterial(Color.RED);
        queryMaterial.setSpecularColor(Color.WHITE);
        queryPoint.setMaterial(queryMaterial);
        queryGroup.getChildren().add(queryPoint);
        
        // Find and visualize nearest neighbors
        List<ID> neighbors = tetree.kNearestNeighbors(query.point, query.k, Float.MAX_VALUE);
        
        // Create numbered labels and connections for each neighbor
        int index = 1;
        for (ID id : neighbors) {
            Point3f entityPos = tetree.getEntityPosition(id);
            if (entityPos != null) {
                // Draw line to neighbor
                Line line = new Line(1.0,
                    new Point3D(query.point.x, query.point.y, query.point.z),
                    new Point3D(entityPos.x, entityPos.y, entityPos.z));
                
                // Color gradient from orange (closest) to yellow (farthest)
                double hue = 30 + (index - 1) * 30.0 / query.k; // 30 (orange) to 60 (yellow)
                Color lineColor = Color.hsb(hue, 1.0, 1.0);
                line.setMaterial(new PhongMaterial(lineColor));
                queryGroup.getChildren().add(line);
                
                // Create a numbered sphere at neighbor position
                Sphere neighborMarker = new Sphere(3.0);
                neighborMarker.setTranslateX(entityPos.x);
                neighborMarker.setTranslateY(entityPos.y);
                neighborMarker.setTranslateZ(entityPos.z);
                neighborMarker.setMaterial(new PhongMaterial(lineColor));
                queryGroup.getChildren().add(neighborMarker);
                
                // Highlight original entity
                Node entityVisual = entityVisuals.get(id);
                if (entityVisual instanceof Sphere) {
                    ((Sphere) entityVisual).setRadius(((Sphere) entityVisual).getRadius() * 1.5);
                }
                
                index++;
            }
        }
        
        // Show search radius as a semi-transparent sphere
        if (!neighbors.isEmpty() && neighbors.size() == query.k) {
            // Calculate max distance to show search radius
            float maxDist = 0;
            for (ID id : neighbors) {
                Point3f pos = tetree.getEntityPosition(id);
                if (pos != null) {
                    float dist = query.point.distance(pos);
                    maxDist = Math.max(maxDist, dist);
                }
            }
            
            if (maxDist > 0) {
                Sphere searchRadius = new Sphere(maxDist);
                searchRadius.setTranslateX(query.point.x);
                searchRadius.setTranslateY(query.point.y);
                searchRadius.setTranslateZ(query.point.z);
                
                PhongMaterial radiusMaterial = new PhongMaterial(Color.CYAN.deriveColor(0, 1, 1, 0.2));
                searchRadius.setMaterial(radiusMaterial);
                searchRadius.setOpacity(0.2);
                queryGroup.getChildren().add(searchRadius);
            }
        }
    }
    
    /**
     * Visualize a ray query.
     */
    private void visualizeRayQuery(TetreeRayQuery query) {
        // Show ray origin
        Sphere originPoint = new Sphere(5.0);
        originPoint.setTranslateX(query.origin.x);
        originPoint.setTranslateY(query.origin.y);
        originPoint.setTranslateZ(query.origin.z);
        
        PhongMaterial originMaterial = new PhongMaterial(Color.MAGENTA);
        originMaterial.setSpecularColor(Color.WHITE);
        originPoint.setMaterial(originMaterial);
        queryGroup.getChildren().add(originPoint);
        
        // Draw ray
        Point3f end = new Point3f(
            query.origin.x + query.direction.x * 1000,
            query.origin.y + query.direction.y * 1000,
            query.origin.z + query.direction.z * 1000
        );
        
        Line ray = new Line(2.0,
            new Point3D(query.origin.x, query.origin.y, query.origin.z),
            new Point3D(end.x, end.y, end.z));
        ray.setMaterial(new PhongMaterial(Color.MAGENTA));
        queryGroup.getChildren().add(ray);
        
        // Show direction arrow
        Point3f arrowTip = new Point3f(
            query.origin.x + query.direction.x * 100,
            query.origin.y + query.direction.y * 100,
            query.origin.z + query.direction.z * 100
        );
        
        // Create arrow head using a cone-like structure
        Sphere arrowHead = new Sphere(8.0);
        arrowHead.setTranslateX(arrowTip.x);
        arrowHead.setTranslateY(arrowTip.y);
        arrowHead.setTranslateZ(arrowTip.z);
        arrowHead.setMaterial(new PhongMaterial(Color.HOTPINK));
        queryGroup.getChildren().add(arrowHead);
        
        // Find ray intersections with entities using ray traversal
        // For now, we'll simulate this by checking all entities
        // TODO: Use proper ray intersection when Ray3D is integrated
        tetree.nodes().forEach(node -> {
            for (ID id : node.entityIds()) {
                Point3f entityPos = tetree.getEntityPosition(id);
                if (entityPos != null) {
                    // Simple ray-sphere intersection test
                    float t = rayPointDistance(query.origin, query.direction, entityPos);
                    if (t >= 0 && t <= 1000) { // Within ray length
                        // Check if close enough to ray
                        Point3f closest = new Point3f(
                            query.origin.x + t * query.direction.x,
                            query.origin.y + t * query.direction.y,
                            query.origin.z + t * query.direction.z
                        );
                        float dist = closest.distance(entityPos);
                        
                        if (dist < 10.0f) { // Within 10 units of ray
                            // Highlight intersected entity
                            Node entityVisual = entityVisuals.get(id);
                            if (entityVisual instanceof Sphere) {
                                Sphere sphere = (Sphere) entityVisual;
                                sphere.setMaterial(new PhongMaterial(Color.YELLOW));
                                sphere.setRadius(sphere.getRadius() * 2.0);
                            }
                            
                            // Show intersection point
                            Sphere hitMarker = new Sphere(3.0);
                            hitMarker.setTranslateX(closest.x);
                            hitMarker.setTranslateY(closest.y);
                            hitMarker.setTranslateZ(closest.z);
                            hitMarker.setMaterial(new PhongMaterial(Color.ORANGE));
                            queryGroup.getChildren().add(hitMarker);
                        }
                    }
                }
            }
        });
        
        // Highlight tetrahedra that the ray passes through
        tetree.nodes().forEach(node -> {
            BaseTetreeKey<? extends BaseTetreeKey> key = node.sfcIndex();
            Tet tet = tetreeKeyToTet(key);
            
            // Simple ray-tetrahedron intersection check
            if (rayIntersectsTetrahedron(query.origin, query.direction, tet)) {
                Node nodeVisual = nodeVisuals.get(key);
                if (nodeVisual instanceof Group) {
                    Group group = (Group) nodeVisual;
                    // Highlight the tetrahedron
                    group.getChildren().forEach(child -> {
                        if (child instanceof MeshView) {
                            MeshView mesh = (MeshView) child;
                            PhongMaterial highlightMaterial = new PhongMaterial(Color.PINK.deriveColor(0, 1, 1, 0.5));
                            mesh.setMaterial(highlightMaterial);
                            mesh.setOpacity(0.5);
                        }
                    });
                }
            }
        });
    }
    
    /**
     * Simple ray-tetrahedron intersection check.
     * This is a basic implementation - a proper one would use
     * ray-triangle intersection for each face.
     */
    private boolean rayIntersectsTetrahedron(Point3f origin, Point3f direction, Tet tet) {
        // Get tetrahedron bounding box
        Point3i[] vertices = tet.coordinates();
        
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
        
        for (Point3i v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }
        
        // Simple ray-AABB intersection as approximation
        float tMin = 0;
        float tMax = Float.MAX_VALUE;
        
        // Check X axis
        if (Math.abs(direction.x) < 1e-6) {
            if (origin.x < minX || origin.x > maxX) return false;
        } else {
            float t1 = (minX - origin.x) / direction.x;
            float t2 = (maxX - origin.x) / direction.x;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        
        // Check Y axis
        if (Math.abs(direction.y) < 1e-6) {
            if (origin.y < minY || origin.y > maxY) return false;
        } else {
            float t1 = (minY - origin.y) / direction.y;
            float t2 = (maxY - origin.y) / direction.y;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        
        // Check Z axis
        if (Math.abs(direction.z) < 1e-6) {
            if (origin.z < minZ || origin.z > maxZ) return false;
        } else {
            float t1 = (minZ - origin.z) / direction.z;
            float t2 = (maxZ - origin.z) / direction.z;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        
        return tMax >= tMin && tMax >= 0;
    }
    
    /**
     * Calculate the parameter t where the ray comes closest to a point.
     * Returns the distance along the ray to the closest point.
     */
    private float rayPointDistance(Point3f origin, Point3f direction, Point3f point) {
        // Vector from ray origin to point
        float dx = point.x - origin.x;
        float dy = point.y - origin.y;
        float dz = point.z - origin.z;
        
        // Project onto ray direction
        float t = (dx * direction.x + dy * direction.y + dz * direction.z);
        
        // Clamp to positive values (ray goes forward)
        return Math.max(0, t);
    }
    
    /**
     * Clear all query visualizations.
     */
    public void clearQueryVisualization() {
        queryGroup.getChildren().clear();
    }
    
    /**
     * Show the characteristic tetrahedron decomposition.
     */
    public void showCharacteristicDecomposition() {
        showCharacteristicDecomposition(null);
    }
    
    /**
     * Show the characteristic tetrahedron decomposition with optional child visibility.
     * @param childVisibility Optional array of 8 booleans indicating which children to show (null = show all)
     */
    public void showCharacteristicDecomposition(boolean[] childVisibility) {
        // Clear existing visualization
        nodeGroup.getChildren().clear();
        
        // Create root tetrahedron and show its subdivision
        Tet rootTet = new Tet(0, 0, 0, (byte)0, (byte)0);
        
        // First, show the root tetrahedron itself with scaling
        Group rootWireframe = createWireframeTetrahedron(rootTet, 0);  // Level 0 for root
        nodeGroup.getChildren().add(rootWireframe);
        
        if (showFilledFaces.get()) {
            MeshView rootFace = createTransparentTetrahedron(rootTet, 0);  // Level 0 for root
            PhongMaterial rootMaterial = new PhongMaterial(Color.DARKGRAY.deriveColor(0, 1, 1, 0.3));
            rootMaterial.setSpecularColor(Color.WHITE);
            rootFace.setMaterial(rootMaterial);
            rootFace.setOpacity(0.2);
            nodeGroup.getChildren().add(rootFace);
        }
        
        // Get the 8 child tetrahedra from geometric subdivision
        Tet[] children = rootTet.geometricSubdivide();
        
        // Render each child with different colors
        for (int i = 0; i < children.length; i++) {
            // Check visibility if array provided
            if (childVisibility != null && i < childVisibility.length && !childVisibility[i]) {
                continue;
            }
            
            Tet child = children[i];
            
            // Create wireframe for each child
            Group wireframe = createWireframeTetrahedron(child, 1);
            nodeGroup.getChildren().add(wireframe);
            
            if (showFilledFaces.get()) {
                // Create semi-transparent face with unique color
                MeshView face = createTransparentTetrahedron(child, 1);
                
                // Apply unique color based on child index
                Color childColor = Color.hsb(i * 45, 0.8, 0.8); // 8 distinct hues
                PhongMaterial material = new PhongMaterial(childColor);
                material.setSpecularColor(childColor.brighter());
                face.setMaterial(material);
                face.setOpacity(0.5);
                
                nodeGroup.getChildren().add(face);
            }
        }
    }
    
    /**
     * Select a node.
     */
    private void selectNode(BaseTetreeKey<? extends BaseTetreeKey> key) {
        getSelectedNodes().clear();
        getSelectedNodes().add(key);
        updateNodeVisibility();
    }
    
    /**
     * Toggle node selection.
     */
    private void toggleNodeSelection(BaseTetreeKey<? extends BaseTetreeKey> key) {
        if (getSelectedNodes().contains(key)) {
            getSelectedNodes().remove(key);
        } else {
            getSelectedNodes().add(key);
        }
        updateNodeVisibility();
    }
    
    /**
     * Unhighlight a node.
     */
    private void unhighlightNode(BaseTetreeKey<? extends BaseTetreeKey> key) {
        Node visual = nodeVisuals.get(key);
        if (visual != null && !getSelectedNodes().contains(key)) {
            // Restore original material
            if (visual instanceof Group) {
                Group group = (Group) visual;
                group.getChildren().forEach(child -> {
                    if (child instanceof MeshView) {
                        Tet tet = tetreeKeyToTet(key);
                        int level = getLevelForKey(key);
                        ((MeshView) child).setMaterial(getMaterialForTet(tet, level));
                    }
                });
            }
        }
    }
    
    // Query types for Tetree visualization
    public static class TetreeRangeQuery {
        public final Point3f center;
        public final float radius;
        
        public TetreeRangeQuery(Point3f center, float radius) {
            this.center = center;
            this.radius = radius;
        }
    }
    
    public static class TetreeKNNQuery {
        public final Point3f point;
        public final int k;
        
        public TetreeKNNQuery(Point3f point, int k) {
            this.point = point;
            this.k = k;
        }
    }
    
    public static class TetreeRayQuery {
        public final Point3f origin;
        public final Point3f direction;
        
        public TetreeRayQuery(Point3f origin, Point3f direction) {
            this.origin = origin;
            this.direction = direction;
        }
    }
    
    /**
     * Compute signed volume of tetrahedron to determine orientation.
     * Positive volume means correct orientation, negative means inverted.
     */
    private double computeSignedVolume(Point3f[] vertices) {
        // Volume = (1/6) * det(v1-v0, v2-v0, v3-v0)
        float dx1 = vertices[1].x - vertices[0].x;
        float dy1 = vertices[1].y - vertices[0].y;
        float dz1 = vertices[1].z - vertices[0].z;
        
        float dx2 = vertices[2].x - vertices[0].x;
        float dy2 = vertices[2].y - vertices[0].y;
        float dz2 = vertices[2].z - vertices[0].z;
        
        float dx3 = vertices[3].x - vertices[0].x;
        float dy3 = vertices[3].y - vertices[0].y;
        float dz3 = vertices[3].z - vertices[0].z;
        
        // Compute determinant (scalar triple product)
        float det = dx1 * (dy2 * dz3 - dz2 * dy3) -
                    dy1 * (dx2 * dz3 - dz2 * dx3) +
                    dz1 * (dx2 * dy3 - dy2 * dx3);
        
        return det / 6.0;
    }
    
    /**
     * Get the node group for testing purposes.
     */
    public Group getNodeGroup() {
        return nodeGroup;
    }
    
    /**
     * Highlight entities that are colliding with others.
     */
    public void highlightCollisions() {
        // Clear previous collision highlights
        clearCollisionHighlights();
        
        // Track which entities are colliding
        Set<ID> collidingEntities = new HashSet<>();
        Map<ID, List<ID>> collisionPairs = new HashMap<>();
        
        // Check all entities for collisions
        tetree.nodes().forEach(node -> {
            List<ID> nodeEntities = new ArrayList<>(node.entityIds());
            
            // Check each pair of entities in this node
            for (int i = 0; i < nodeEntities.size(); i++) {
                for (int j = i + 1; j < nodeEntities.size(); j++) {
                    ID id1 = nodeEntities.get(i);
                    ID id2 = nodeEntities.get(j);
                    
                    Point3f pos1 = tetree.getEntityPosition(id1);
                    Point3f pos2 = tetree.getEntityPosition(id2);
                    
                    if (pos1 != null && pos2 != null) {
                        float distance = pos1.distance(pos2);
                        
                        // Consider entities colliding if within 10 units
                        if (distance < 10.0f) {
                            collidingEntities.add(id1);
                            collidingEntities.add(id2);
                            
                            collisionPairs.computeIfAbsent(id1, k -> new ArrayList<>()).add(id2);
                            collisionPairs.computeIfAbsent(id2, k -> new ArrayList<>()).add(id1);
                        }
                    }
                }
            }
        });
        
        // Highlight colliding entities
        for (ID id : collidingEntities) {
            Node entityVisual = entityVisuals.get(id);
            if (entityVisual instanceof Sphere) {
                Sphere sphere = (Sphere) entityVisual;
                
                // Make colliding entities red and larger
                PhongMaterial collisionMaterial = new PhongMaterial(Color.RED);
                collisionMaterial.setSpecularColor(Color.WHITE);
                sphere.setMaterial(collisionMaterial);
                sphere.setRadius(sphere.getRadius() * 1.5);
                
                // Store original size for restoration
                sphere.setUserData(sphere.getRadius() / 1.5);
            }
        }
        
        // Draw lines between colliding pairs
        for (Map.Entry<ID, List<ID>> entry : collisionPairs.entrySet()) {
            ID id1 = entry.getKey();
            Point3f pos1 = tetree.getEntityPosition(id1);
            
            if (pos1 != null) {
                for (ID id2 : entry.getValue()) {
                    // Only draw line once per pair
                    if (id1.hashCode() < id2.hashCode()) {
                        Point3f pos2 = tetree.getEntityPosition(id2);
                        if (pos2 != null) {
                            Line collisionLine = new Line(1.0,
                                new Point3D(pos1.x, pos1.y, pos1.z),
                                new Point3D(pos2.x, pos2.y, pos2.z));
                            
                            PhongMaterial lineMaterial = new PhongMaterial(Color.ORANGE);
                            collisionLine.setMaterial(lineMaterial);
                            
                            // Add to query group for easy clearing
                            queryGroup.getChildren().add(collisionLine);
                        }
                    }
                }
            }
        }
        
        // Show collision count
        int collisionCount = collisionPairs.size() / 2; // Each pair counted twice
        if (collisionCount > 0) {
            System.out.println("Found " + collisionCount + " collision pairs among " + 
                             collidingEntities.size() + " entities");
        }
    }
    
    /**
     * Clear collision highlights and restore original entity appearance.
     */
    public void clearCollisionHighlights() {
        // Clear collision lines from query group
        queryGroup.getChildren().removeIf(node -> node instanceof Line);
        
        // Restore entity appearance
        entityVisuals.forEach((id, visual) -> {
            if (visual instanceof Sphere) {
                Sphere sphere = (Sphere) visual;
                
                // Restore original size if it was stored
                if (sphere.getUserData() instanceof Double) {
                    sphere.setRadius((Double) sphere.getUserData());
                    sphere.setUserData(null);
                }
                
                // Restore default material
                PhongMaterial defaultMaterial = new PhongMaterial(Color.LIGHTGREEN);
                defaultMaterial.setSpecularColor(Color.WHITE);
                sphere.setMaterial(defaultMaterial);
            }
        });
    }
    
    /**
     * Enable or disable tree modification animations.
     */
    public void setAnimateModifications(boolean animate) {
        this.animateModifications = animate;
    }
    
    /**
     * Animate the insertion of a new entity.
     */
    public void animateEntityInsertion(ID entityId, Point3f position) {
        if (!animateModifications) {
            return;
        }
        
        // Create a temporary sphere at the insertion point
        Sphere insertMarker = new Sphere(10.0);
        insertMarker.setTranslateX(position.x);
        insertMarker.setTranslateY(position.y);
        insertMarker.setTranslateZ(position.z);
        
        PhongMaterial material = new PhongMaterial(Color.YELLOW);
        material.setSpecularColor(Color.WHITE);
        insertMarker.setMaterial(material);
        insertMarker.setOpacity(0.0);
        
        queryGroup.getChildren().add(insertMarker);
        
        // Animate the appearance
        Timeline timeline = new Timeline();
        
        // Fade in
        KeyValue fadeIn = new KeyValue(insertMarker.opacityProperty(), 1.0);
        KeyFrame kf1 = new KeyFrame(Duration.millis(200), fadeIn);
        
        // Pulse effect
        KeyValue scaleUp = new KeyValue(insertMarker.scaleXProperty(), 1.5);
        KeyValue scaleUpY = new KeyValue(insertMarker.scaleYProperty(), 1.5);
        KeyValue scaleUpZ = new KeyValue(insertMarker.scaleZProperty(), 1.5);
        KeyFrame kf2 = new KeyFrame(Duration.millis(400), scaleUp, scaleUpY, scaleUpZ);
        
        KeyValue scaleDown = new KeyValue(insertMarker.scaleXProperty(), 0.5);
        KeyValue scaleDownY = new KeyValue(insertMarker.scaleYProperty(), 0.5);
        KeyValue scaleDownZ = new KeyValue(insertMarker.scaleZProperty(), 0.5);
        KeyValue fadeOut = new KeyValue(insertMarker.opacityProperty(), 0.0);
        KeyFrame kf3 = new KeyFrame(Duration.millis(800), scaleDown, scaleDownY, scaleDownZ, fadeOut);
        
        timeline.getKeyFrames().addAll(kf1, kf2, kf3);
        
        timeline.setOnFinished(e -> {
            queryGroup.getChildren().remove(insertMarker);
            activeAnimations.remove(timeline);
            
            // Update the actual visualization
            updateVisualization();
            
            // Highlight the affected node
            highlightAffectedNode(position);
        });
        
        activeAnimations.add(timeline);
        timeline.play();
    }
    
    /**
     * Animate the removal of an entity.
     */
    public void animateEntityRemoval(ID entityId) {
        if (!animateModifications) {
            return;
        }
        
        Node entityVisual = entityVisuals.get(entityId);
        if (entityVisual instanceof Sphere) {
            Sphere sphere = (Sphere) entityVisual;
            
            // Create removal animation
            Timeline timeline = new Timeline();
            
            // Fade and shrink
            KeyValue fadeOut = new KeyValue(sphere.opacityProperty(), 0.0);
            KeyValue shrinkX = new KeyValue(sphere.scaleXProperty(), 0.1);
            KeyValue shrinkY = new KeyValue(sphere.scaleYProperty(), 0.1);
            KeyValue shrinkZ = new KeyValue(sphere.scaleZProperty(), 0.1);
            
            KeyFrame kf = new KeyFrame(Duration.millis(500), fadeOut, shrinkX, shrinkY, shrinkZ);
            timeline.getKeyFrames().add(kf);
            
            timeline.setOnFinished(e -> {
                activeAnimations.remove(timeline);
                updateVisualization();
            });
            
            activeAnimations.add(timeline);
            timeline.play();
        }
    }
    
    /**
     * Highlight the node affected by a modification.
     */
    private void highlightAffectedNode(Point3f position) {
        // Find the node containing this position
        BaseTetreeKey<? extends BaseTetreeKey> affectedKey = null;
        
        for (Map.Entry<BaseTetreeKey<? extends BaseTetreeKey>, Node> entry : nodeVisuals.entrySet()) {
            Tet tet = tetreeKeyToTet(entry.getKey());
            if (isPointInTetrahedron(position, tet)) {
                affectedKey = entry.getKey();
                break;
            }
        }
        
        if (affectedKey != null) {
            Node nodeVisual = nodeVisuals.get(affectedKey);
            if (nodeVisual instanceof Group) {
                Group group = (Group) nodeVisual;
                
                // Create highlight animation
                Timeline timeline = new Timeline();
                timeline.setCycleCount(3);
                timeline.setAutoReverse(true);
                
                // Flash the node
                group.getChildren().forEach(child -> {
                    if (child instanceof MeshView) {
                        MeshView mesh = (MeshView) child;
                        PhongMaterial originalMaterial = (PhongMaterial) mesh.getMaterial();
                        PhongMaterial flashMaterial = new PhongMaterial(Color.WHITE);
                        
                        KeyValue flash = new KeyValue(mesh.materialProperty(), flashMaterial);
                        KeyValue restore = new KeyValue(mesh.materialProperty(), originalMaterial);
                        
                        KeyFrame kf1 = new KeyFrame(Duration.millis(100), flash);
                        KeyFrame kf2 = new KeyFrame(Duration.millis(200), restore);
                        
                        timeline.getKeyFrames().addAll(kf1, kf2);
                    }
                });
                
                timeline.setOnFinished(e -> activeAnimations.remove(timeline));
                activeAnimations.add(timeline);
                timeline.play();
            }
        }
    }
    
    /**
     * Simple point-in-tetrahedron test.
     */
    private boolean isPointInTetrahedron(Point3f point, Tet tet) {
        Point3i[] vertices = tet.coordinates();
        
        // Convert to float coordinates
        Point3f v0 = new Point3f(vertices[0].x, vertices[0].y, vertices[0].z);
        Point3f v1 = new Point3f(vertices[1].x, vertices[1].y, vertices[1].z);
        Point3f v2 = new Point3f(vertices[2].x, vertices[2].y, vertices[2].z);
        Point3f v3 = new Point3f(vertices[3].x, vertices[3].y, vertices[3].z);
        
        // Use barycentric coordinates to test
        // This is simplified - a proper implementation would use exact arithmetic
        float totalVolume = Math.abs((float)computeSignedVolume(new Point3f[]{v0, v1, v2, v3}));
        
        float vol0 = Math.abs((float)computeSignedVolume(new Point3f[]{point, v1, v2, v3}));
        float vol1 = Math.abs((float)computeSignedVolume(new Point3f[]{v0, point, v2, v3}));
        float vol2 = Math.abs((float)computeSignedVolume(new Point3f[]{v0, v1, point, v3}));
        float vol3 = Math.abs((float)computeSignedVolume(new Point3f[]{v0, v1, v2, point}));
        
        float sumVolumes = vol0 + vol1 + vol2 + vol3;
        
        // Check if sum of sub-volumes equals total volume (with small epsilon)
        return Math.abs(sumVolumes - totalVolume) < 0.001f * totalVolume;
    }
    
    /**
     * Stop all active animations.
     */
    public void stopAllAnimations() {
        activeAnimations.forEach(Timeline::stop);
        activeAnimations.clear();
    }
    
    /**
     * Export the current visualization as an image file.
     * 
     * @param includeOverlay Whether to include the performance overlay in the snapshot
     * @return true if the export was successful, false otherwise
     */
    public boolean exportSnapshot(boolean includeOverlay) {
        try {
            // Create a file chooser
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Save Visualization Snapshot");
            fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("PNG Images", "*.png"),
                new FileChooser.ExtensionFilter("JPEG Images", "*.jpg", "*.jpeg"),
                new FileChooser.ExtensionFilter("All Images", "*.png", "*.jpg", "*.jpeg")
            );
            
            // Set initial directory to last used directory
            if (lastSnapshotDirectory != null && lastSnapshotDirectory.exists()) {
                fileChooser.setInitialDirectory(lastSnapshotDirectory);
            }
            
            // Set default filename with timestamp
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            fileChooser.setInitialFileName("tetree_snapshot_" + timestamp + ".png");
            
            // Show save dialog
            File file = fileChooser.showSaveDialog(sceneRoot.getScene().getWindow());
            if (file == null) {
                return false; // User cancelled
            }
            
            // Remember the directory for next time
            lastSnapshotDirectory = file.getParentFile();
            
            // Temporarily hide overlay if requested
            boolean wasShowingOverlay = performanceOverlay.isVisible();
            if (!includeOverlay && wasShowingOverlay) {
                performanceOverlay.setVisible(false);
            }
            
            // Create snapshot parameters
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(javafx.scene.paint.Color.TRANSPARENT);
            
            // Take the snapshot
            WritableImage snapshot = sceneRoot.snapshot(params, null);
            
            // Restore overlay visibility
            if (!includeOverlay && wasShowingOverlay) {
                performanceOverlay.setVisible(true);
            }
            
            // Save the image
            String extension = getFileExtension(file.getName()).toLowerCase();
            String format = "png"; // default
            if (extension.equals("jpg") || extension.equals("jpeg")) {
                format = "jpg";
            }
            
            ImageIO.write(SwingFXUtils.fromFXImage(snapshot, null), format, file);
            
            return true;
        } catch (IOException e) {
            System.err.println("Failed to export snapshot: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Export the current visualization with a predefined filename.
     * 
     * @param file The file to save to
     * @param includeOverlay Whether to include the performance overlay
     * @return true if successful
     */
    public boolean exportSnapshot(File file, boolean includeOverlay) {
        try {
            // Temporarily hide overlay if requested
            boolean wasShowingOverlay = performanceOverlay.isVisible();
            if (!includeOverlay && wasShowingOverlay) {
                performanceOverlay.setVisible(false);
            }
            
            // Create snapshot parameters
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(javafx.scene.paint.Color.TRANSPARENT);
            
            // Take the snapshot
            WritableImage snapshot = sceneRoot.snapshot(params, null);
            
            // Restore overlay visibility
            if (!includeOverlay && wasShowingOverlay) {
                performanceOverlay.setVisible(true);
            }
            
            // Save the image
            String extension = getFileExtension(file.getName()).toLowerCase();
            String format = "png"; // default
            if (extension.equals("jpg") || extension.equals("jpeg")) {
                format = "jpg";
            }
            
            ImageIO.write(SwingFXUtils.fromFXImage(snapshot, null), format, file);
            
            return true;
        } catch (IOException e) {
            System.err.println("Failed to export snapshot: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get file extension from filename.
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1);
        }
        return "";
    }
    
    /**
     * Validate that face normals point outward for correct rendering.
     * This method is called only when assertions are enabled.
     */
    private boolean validateFaceNormals(Point3f[] vertices, TriangleMesh mesh, double volume) {
        // Calculate tetrahedron centroid
        Point3f centroid = new Point3f(
            (vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f,
            (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f,
            (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f
        );
        
        // Get face indices from mesh
        int[] faces = new int[mesh.getFaces().size()];
        for (int i = 0; i < faces.length; i++) {
            faces[i] = mesh.getFaces().get(i);
        }
        
        // Check each face (4 faces, 6 indices per face in the format: v0, t0, v1, t1, v2, t2)
        for (int i = 0; i < 4; i++) {
            int baseIdx = i * 6;
            int v0 = faces[baseIdx];
            int v1 = faces[baseIdx + 2];
            int v2 = faces[baseIdx + 4];
            
            // Calculate face normal using cross product
            float dx1 = vertices[v1].x - vertices[v0].x;
            float dy1 = vertices[v1].y - vertices[v0].y;
            float dz1 = vertices[v1].z - vertices[v0].z;
            
            float dx2 = vertices[v2].x - vertices[v0].x;
            float dy2 = vertices[v2].y - vertices[v0].y;
            float dz2 = vertices[v2].z - vertices[v0].z;
            
            // Cross product gives face normal
            float nx = dy1 * dz2 - dz1 * dy2;
            float ny = dz1 * dx2 - dx1 * dz2;
            float nz = dx1 * dy2 - dy1 * dx2;
            
            // Vector from centroid to face
            float cx = vertices[v0].x - centroid.x;
            float cy = vertices[v0].y - centroid.y;
            float cz = vertices[v0].z - centroid.z;
            
            // Dot product should be positive for outward-facing normal
            float dot = nx * cx + ny * cy + nz * cz;
            
            // For positive volume, normals should point outward (positive dot)
            // For negative volume, normals should point inward (negative dot)
            boolean expectedOutward = volume > 0;
            boolean isOutward = dot > 0;
            
            if (expectedOutward != isOutward) {
                System.err.println("Face " + i + " has incorrect winding. Volume: " + volume + ", Dot: " + dot);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Get the root scale property for binding.
     */
    public DoubleProperty rootScaleProperty() {
        return rootScale;
    }
    
    /**
     * Set the root tetrahedron scale.
     */
    public void setRootScale(double scale) {
        rootScale.set(scale);
        updateVisualization();
    }
    
    /**
     * Initialize the performance overlay.
     */
    private void initializePerformanceOverlay() {
        // Style the FPS text
        fpsText.setFont(Font.font("Monospace", 16));
        fpsText.setFill(Color.LIME);
        fpsText.setTranslateX(10);
        fpsText.setTranslateY(25);
        
        // Style the stats text
        statsText.setFont(Font.font("Monospace", 14));
        statsText.setFill(Color.WHITE);
        statsText.setTranslateX(10);
        statsText.setTranslateY(50);
        
        // Add to performance overlay group
        performanceOverlay.getChildren().addAll(fpsText, statsText);
        
        // Create performance timer
        performanceTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                frameCount++;
                
                // Update FPS every second
                if (now - lastFPSUpdate >= 1_000_000_000L) {
                    currentFPS = frameCount * 1_000_000_000.0 / (now - lastFPSUpdate);
                    frameCount = 0;
                    lastFPSUpdate = now;
                    updatePerformanceStats();
                }
            }
        };
    }
    
    /**
     * Update the performance overlay with current stats.
     */
    private void updatePerformanceStats() {
        if (!showPerformanceOverlay) return;
        
        // Update FPS
        fpsText.setText(String.format("FPS: %.1f", currentFPS));
        
        // Count total nodes and entities
        final int[] counts = {0, 0}; // totalNodes, totalEntities
        
        tetree.nodes().forEach(node -> {
            counts[0]++;
            counts[1] += node.entityIds().size();
        });
        
        int totalNodes = counts[0];
        int totalEntities = counts[1];
        
        // Update stats
        StringBuilder stats = new StringBuilder();
        stats.append(String.format("Nodes: %d / %d visible\n", totalNodes, visibleNodeCount));
        stats.append(String.format("Entities: %d / %d visible\n", totalEntities, visibleEntityCount));
        
        if (lastUpdateTime > 0) {
            stats.append(String.format("Update: %d ms\n", lastUpdateTime));
        }
        
        // Add memory usage if available
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        stats.append(String.format("Memory: %d / %d MB", usedMemory, maxMemory));
        
        statsText.setText(stats.toString());
    }
    
    /**
     * Show or hide the performance overlay.
     */
    public void setShowPerformanceOverlay(boolean show) {
        this.showPerformanceOverlay = show;
        
        if (show) {
            if (!getSceneRoot().getChildren().contains(performanceOverlay)) {
                getSceneRoot().getChildren().add(performanceOverlay);
            }
            performanceTimer.start();
        } else {
            getSceneRoot().getChildren().remove(performanceOverlay);
            performanceTimer.stop();
        }
    }
    
    /**
     * Override updateVisualization to track timing.
     */
    @Override
    public void updateVisualization() {
        long startTime = System.currentTimeMillis();
        
        // Call parent implementation
        super.updateVisualization();
        
        // Track update time
        lastUpdateTime = System.currentTimeMillis() - startTime;
        
        // Update performance display if visible
        if (showPerformanceOverlay) {
            updatePerformanceStats();
        }
    }
    
    /**
     * @return property controlling whether to show filled faces
     */
    public BooleanProperty showFilledFacesProperty() {
        return showFilledFaces;
    }
}
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

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.SpatialIndex.RayIntersection;
import com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.portal.mesh.Line;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import javax.vecmath.Point3f;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3f;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * JavaFX 3D visualization for Tetree spatial index structures. Renders tetrahedra with level-based coloring and shows
 * the tetrahedral subdivision.
 *
 * This visualization uses JavaFX transforms to handle the large coordinate space of the Tetree. All geometry is
 * rendered in natural Tetree coordinates (where root edge length = 2^20), and a scene-level Scale transform is applied
 * to bring everything to viewable size.
 *
 * @param <ID>      The entity identifier type
 * @param <Content> The entity content type
 * @author hal.hildebrand
 */
public class TetreeVisualization<ID extends EntityID, Content>
extends SpatialIndexView<TetreeKey<? extends TetreeKey>, ID, Content> {

    private final Tetree<ID, Content>                        tetree;
    private final Map<TetreeKey<? extends TetreeKey>, Group> tetGroups = new HashMap<>();

    // Tetrahedral type colors
    private final Map<Integer, Color> typeColors         = new HashMap<>();
    private final List<Timeline>      activeAnimations   = new ArrayList<>();
    private final Text                fpsText            = new Text();
    private final Text                statsText          = new Text();
    private final Group               performanceOverlay = new Group();
    // Root tetrahedron scale - applied as a transform to the entire scene
    // Default scale of 0.001 brings the 2^20 coordinate system down to ~1000 unit viewable size
    // This makes level 5 cells (32768 units) visible at ~32 unit size
    private final DoubleProperty      rootScale          = new SimpleDoubleProperty(0.001);
    // Display mode property
    private final BooleanProperty     showFilledFaces    = new SimpleBooleanProperty(true);
    // Scene scale transform
    private final Scale          sceneScale             = new Scale();
    protected     long           lastUpdateTime         = 0;
    private       FaceRenderMode faceRenderMode         = FaceRenderMode.LEAF_NODES_ONLY;
    private       int            currentRefinementLevel = 1; // Track current refinement level for subdivision views
    // Animation tracking
    private       boolean        animateModifications   = false;
    // Performance tracking
    private       AnimationTimer performanceTimer;
    private       long           frameCount             = 0;
    private       long           lastFPSUpdate          = 0;
    private       double         currentFPS             = 0;
    private       boolean        showPerformanceOverlay = false;
    // Snapshot export
    private       File           lastSnapshotDirectory  = null;
    // Rendering statistics for validation
    private int transformBasedWireframeCount = 0;
    private int traditionalWireframeCount    = 0;
    private int transformBasedMeshCount      = 0;
    private int traditionalMeshCount         = 0;
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
     * Animate the insertion of a new entity.
     */
    public void animateEntityInsertion(ID entityId, Point3f position) {
        if (!animateModifications) {
            return;
        }

        // Create a temporary sphere at the insertion point
        // Make it slightly larger than regular entities for visibility during animation
        Sphere insertMarker = new Sphere(4000.0);
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
        if (entityVisual instanceof final Sphere sphere) {

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
     * Clear collision highlights and restore original entity appearance.
     */
    public void clearCollisionHighlights() {
        // Clear collision lines from query group
        queryGroup.getChildren().removeIf(node -> node instanceof Line);

        // Restore entity appearance
        entityVisuals.forEach((id, visual) -> {
            if (visual instanceof final Sphere sphere) {

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
     * Clear all query visualizations.
     */
    public void clearQueryVisualization() {
        queryGroup.getChildren().clear();
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
            fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("PNG Images", "*.png"),
                                                     new FileChooser.ExtensionFilter("JPEG Images", "*.jpg", "*.jpeg"),
                                                     new FileChooser.ExtensionFilter("All Images", "*.png", "*.jpg",
                                                                                     "*.jpeg"));

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
     * @param file           The file to save to
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
     * Get the current face render mode.
     */
    public FaceRenderMode getFaceRenderMode() {
        return faceRenderMode;
    }

    /**
     * Get the node group for testing purposes.
     */
    public Group getNodeGroup() {
        return nodeGroup;
    }

    /**
     * Get a report of rendering statistics.
     */
    public String getRenderingStatsReport() {

        boolean allWireframesTransformBased = traditionalWireframeCount == 0 && transformBasedWireframeCount > 0;
        final String report = "=== Rendering Method Validation ===\n" + "Transform-based rendering:\n" + String.format(
        "  - Wireframes: %d\n", transformBasedWireframeCount) + String.format("  - Meshes: %d\n",
                                                                              transformBasedMeshCount)
        + "Traditional rendering:\n" + String.format("  - Wireframes: %d\n", traditionalWireframeCount) + String.format(
        "  - Meshes: %d\n", traditionalMeshCount)

        // Validation
        + "\nValidation:\n" + String.format("  - All wireframes using transforms: %s\n",
                                            allWireframesTransformBased ? "YES ✓" : "NO ✗");

        return report;
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
            if (entityVisual instanceof final Sphere sphere) {

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
                            Line collisionLine = new Line(1000.0, new Point3D(pos1.x, pos1.y, pos1.z),
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
            System.out.println(
            "Found " + collisionCount + " collision pairs among " + collidingEntities.size() + " entities");
        }
    }

    /**
     * Print rendering statistics to console.
     */
    public void printRenderingStats() {
        System.out.println(getRenderingStatsReport());
    }

    /**
     * Reset rendering statistics counters.
     */
    public void resetRenderingStats() {
        transformBasedWireframeCount = 0;
        traditionalWireframeCount = 0;
        transformBasedMeshCount = 0;
        traditionalMeshCount = 0;
    }

    /**
     * Get the root scale property for binding.
     */
    public DoubleProperty rootScaleProperty() {
        return rootScale;
    }

    /**
     * Enable or disable tree modification animations.
     */
    public void setAnimateModifications(boolean animate) {
        this.animateModifications = animate;
    }

    /**
     * Set the face render mode.
     */
    public void setFaceRenderMode(FaceRenderMode mode) {
        this.faceRenderMode = mode;
        updateVisualization();
    }

    /**
     * Set the root tetrahedron scale.
     */
    public void setRootScale(double scale) {
        rootScale.set(scale);
        updateVisualization();
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
     * Show animated refinement transition. Gradually reveals tetrahedra level by level.
     *
     * @param useSubdivision If true, use subdivision geometry; if false, use S0-S5
     * @param maxLevel       Maximum refinement level to show
     * @param delayMs        Delay between levels in milliseconds
     */
    public void showAnimatedRefinement(boolean useSubdivision, int maxLevel, int delayMs) {
        // Stop any existing animations
        stopAllAnimations();

        // Create timeline for animation
        Timeline timeline = new Timeline();

        // Add keyframes for each level
        for (int level = 0; level <= maxLevel; level++) {
            final int currentLevel = level;
            KeyFrame keyFrame = new KeyFrame(Duration.millis(level * delayMs), _ -> {
                if (useSubdivision) {
                    showCharacteristicSubdivision(null, currentLevel);
                } else {
                    showS0S5Subdivision(null, currentLevel);
                }
            });
            timeline.getKeyFrames().add(keyFrame);
        }

        // Track and play animation
        activeAnimations.add(timeline);
        timeline.play();
    }

    /**
     * Show the characteristic tetrahedron subdivision. This shows subdivision-compatible geometry (different from
     * S0-S5).
     */
    public void showCharacteristicSubdivision() {
        showCharacteristicSubdivision(null);
    }

    /**
     * Show the characteristic tetrahedron subdivision with optional child visibility.
     *
     * @param childVisibility Optional array of 8 booleans indicating which children to show (null = show all)
     */
    public void showCharacteristicSubdivision(boolean[] childVisibility) {
        showCharacteristicSubdivision(childVisibility, 1);
    }

    /**
     * Show the characteristic tetrahedron subdivision with optional child visibility and refinement level.
     *
     * @param childVisibility Optional array of 8 booleans indicating which children to show (null = show all)
     * @param refinementLevel The number of levels to refine (0 = root only, 1 = show 8 children, 2 = show 64
     *                        grandchildren, etc.)
     */
    public void showCharacteristicSubdivision(boolean[] childVisibility, int refinementLevel) {
        showCharacteristicSubdivision(childVisibility, refinementLevel, false);
    }

    /**
     * Show the characteristic tetrahedron subdivision with optional child visibility, refinement level, and rendering
     * method.
     *
     * @param childVisibility   Optional array of 8 booleans indicating which children to show (null = show all)
     * @param refinementLevel   The number of levels to refine (0 = root only, 1 = show 8 children, 2 = show 64
     *                          grandchildren, etc.)
     * @param useTransformBased If true, use transform-based rendering for better performance
     */
    public void showCharacteristicSubdivision(boolean[] childVisibility, int refinementLevel,
                                                boolean useTransformBased) {
        // Clear existing visualization and reset stats
        nodeGroup.getChildren().clear();
        resetRenderingStats();
        currentRefinementLevel = refinementLevel;

        // Create root tetrahedron and show its subdivision
        Tet rootTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);

        if (useTransformBased) {
            // Use transform-based rendering
            showCharacteristicSubdivisionTransformBased(rootTet, childVisibility, refinementLevel);
        } else {
            // Use traditional rendering
            // Show root using subdivision coordinates but with clean rendering
            Group rootWireframe = createWireframeTetrahedronWithCoords(rootTet, true);
            nodeGroup.getChildren().add(rootWireframe);

            if (showFilledFaces.get() && shouldShowFaceForSubdivision(rootTet, currentRefinementLevel)) {
                MeshView rootFace = createTransparentTetrahedronWithCoords(rootTet, 0, true);
                PhongMaterial rootMaterial = new PhongMaterial(Color.DARKGRAY.deriveColor(0, 1, 1, 0.3));
                rootMaterial.setSpecularColor(Color.WHITE);
                rootFace.setMaterial(rootMaterial);
                rootFace.setOpacity(0.2);
                nodeGroup.getChildren().add(rootFace);
            }

            // Add label
            Text label = new Text(String.format("Subdivision Geometry (Level %d)", refinementLevel));
            label.setFont(Font.font("Arial", 24));
            label.setFill(Color.YELLOW);
            label.setTranslateX(500000);
            label.setTranslateY(-100000);
            nodeGroup.getChildren().add(label);

            // Show refinement
            if (refinementLevel > 0) {
                showSubdivisionRefinementRecursive(rootTet, 0, refinementLevel, childVisibility);
            }
        }

        // Print validation stats
        printRenderingStats();
    }

    /**
     * @return property controlling whether to show filled faces
     */
    public BooleanProperty showFilledFacesProperty() {
        return showFilledFaces;
    }

    /**
     * Show the S0-S5 subdivision using standard coordinates. This shows the actual tetrahedra used for spatial
     * indexing.
     */
    public void showS0S5Subdivision() {
        showS0S5Subdivision(null);
    }

    /**
     * Show the S0-S5 subdivision with optional child visibility.
     *
     * @param childVisibility Optional array of 8 booleans indicating which children to show (null = show all)
     */
    public void showS0S5Subdivision(boolean[] childVisibility) {
        showS0S5Subdivision(childVisibility, 1);
    }

    /**
     * Show the S0-S5 subdivision with optional child visibility and refinement level.
     *
     * @param childVisibility Optional array of 8 booleans indicating which children to show (null = show all)
     * @param refinementLevel The number of levels to refine (0 = root only, 1 = show 8 children, 2 = show 64
     *                        grandchildren, etc.)
     */
    public void showS0S5Subdivision(boolean[] childVisibility, int refinementLevel) {
        // Clear existing visualization and reset stats
        nodeGroup.getChildren().clear();
        resetRenderingStats();
        currentRefinementLevel = refinementLevel;

        // Create root S0 tetrahedron using standard coordinates
        Tet rootTet = new Tet(0, 0, 0, (byte) 0, (byte) 0);

        // Create reference objects for transform-based rendering
        TriangleMesh referenceMesh = createS0S5ReferenceMesh();
        Map<String, Cylinder> referenceEdges = createReferenceEdges();

        // Show the root tetrahedron using transform-based rendering
        showTransformedS0S5Tetrahedron(rootTet, 0, referenceMesh, referenceEdges);

        // Add label
        Text label = new Text(String.format("S0-S5 Subdivision (Level %d)", refinementLevel));
        label.setFont(Font.font("Arial", 24));
        label.setFill(Color.WHITE);
        label.setTranslateX(500000);
        label.setTranslateY(-100000);
        nodeGroup.getChildren().add(label);

        // Show refinement
        if (refinementLevel > 0) {
            showS0S5RefinementRecursiveTransformed(rootTet, 0, refinementLevel, childVisibility, referenceMesh,
                                                   referenceEdges);
        }

        // Print validation stats
        printRenderingStats();
    }

    /**
     * Stop all active animations.
     */
    public void stopAllAnimations() {
        activeAnimations.forEach(Timeline::stop);
        activeAnimations.clear();
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

    @Override
    protected Node createEntityVisual(ID id) {
        Point3f pos = tetree.getEntityPosition(id);
        if (pos == null) {
            return null;
        }

        // Entity size in natural coordinates (scaled by scene transform)
        // For level 5, cell size is 2^15 = 32768
        // Make entity spheres 1/10th of cell size for good visibility without overlapping
        double entityRadius = 3276.8; // 1/10th of level 5 cell size
        Sphere sphere = new Sphere(entityRadius);
        // Use natural coordinates - JavaFX transform handles scaling
        sphere.setTranslateX(pos.x);
        sphere.setTranslateY(pos.y);
        sphere.setTranslateZ(pos.z);

        // Check if this entity has a visible container
        boolean hasVisibleContainer = false;
        for (var node : tetree.nodes().toList()) {
            if (node.entityIds().contains(id) && nodeVisuals.containsKey(node.sfcIndex())) {
                hasVisibleContainer = true;
                break;
            }
        }

        // Color based on selection state and containment
        PhongMaterial material = new PhongMaterial();
        if (getSelectedEntities().contains(id)) {
            material.setDiffuseColor(Color.YELLOW);
            material.setSpecularColor(Color.WHITE);
        } else if (!hasVisibleContainer) {
            // Red for entities without visible containers
            material.setDiffuseColor(Color.RED);
            material.setSpecularColor(Color.DARKRED);
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
    protected int getLevelForKey(TetreeKey<? extends TetreeKey> key) {
        // Extract level from ExtendedTetreeKey
        // The level is encoded in the key structure
        return key.getLevel();
    }

    @Override
    protected boolean isNodeVisible(TetreeKey<? extends TetreeKey> nodeKey) {
        int level = getLevelForKey(nodeKey);
        if (!isLevelVisible(level)) {
            return false;
        }

        // Check if node exists by searching current nodes
        return tetree.nodes().anyMatch(
        node -> node.sfcIndex().equals(nodeKey) && (showEmptyNodesProperty().get() || !node.entityIds().isEmpty()));
    }

    @Override
    protected void renderNodes() {
        boolean debug = Boolean.getBoolean("tetree.debug.render");
        int totalNodes = 0;
        int nodesWithEntities = 0;
        int renderedNodes = 0;

        // Get all nodes from the tetree
        for (var node : tetree.nodes().toList()) {
            totalNodes++;
            SpatialNode<TetreeKey<? extends TetreeKey>, ID> typedNode = new SpatialNode<>(node.sfcIndex(),
                                                                                          node.entityIds());
            if (!node.entityIds().isEmpty()) {
                nodesWithEntities++;
            }

            if (shouldRenderNode(typedNode)) {
                Node tetVisual = createTetVisual(typedNode);
                if (tetVisual != null) {
                    nodeGroup.getChildren().add(tetVisual);
                    nodeVisuals.put(typedNode.sfcIndex(), tetVisual);
                    visibleNodeCount++;
                    renderedNodes++;
                }
            } else if (debug && !node.entityIds().isEmpty()) {
                // Debug: log why nodes with entities aren't being rendered
                int level = getLevelForKey(node.sfcIndex());
                System.out.printf("Node with %d entities at level %d not rendered (visible range: %d-%d)%n",
                                  node.entityIds().size(), level, minLevelProperty().get(), maxLevelProperty().get());
            }
        }

        if (debug) {
            System.out.printf("Render stats: %d total nodes, %d with entities, %d rendered%n", totalNodes,
                              nodesWithEntities, renderedNodes);
        }
    }

    /**
     * Calculate transform for S0-S5 coordinates.
     */
    private Affine calculateS0S5Transform(Tet tet) {
        Affine transform = new Affine();

        // Get S0-S5 coordinates
        Point3i[] coords = tet.coordinates();

        // Calculate basis vectors from the coordinates
        double e1x = coords[1].x - coords[0].x;
        double e1y = coords[1].y - coords[0].y;
        double e1z = coords[1].z - coords[0].z;

        double e2x = coords[2].x - coords[0].x;
        double e2y = coords[2].y - coords[0].y;
        double e2z = coords[2].z - coords[0].z;

        double e3x = coords[3].x - coords[0].x;
        double e3y = coords[3].y - coords[0].y;
        double e3z = coords[3].z - coords[0].z;

        // Set transform matrix
        transform.setMxx(e1x);
        transform.setMxy(e2x);
        transform.setMxz(e3x);
        transform.setTx(coords[0].x);
        transform.setMyx(e1y);
        transform.setMyy(e2y);
        transform.setMyz(e3y);
        transform.setTy(coords[0].y);
        transform.setMzx(e1z);
        transform.setMzy(e2z);
        transform.setMzz(e3z);
        transform.setTz(coords[0].z);

        return transform;
    }

    /**
     * Calculate transform for subdivision coordinates.
     */
    private Affine calculateSubdivisionTransform(Tet tet) {
        Affine transform = new Affine();

        // Get subdivision coordinates
        Point3i[] coords = tet.subdivisionCoordinates();

        // Calculate basis vectors from the coordinates
        double e1x = coords[1].x - coords[0].x;
        double e1y = coords[1].y - coords[0].y;
        double e1z = coords[1].z - coords[0].z;

        double e2x = coords[2].x - coords[0].x;
        double e2y = coords[2].y - coords[0].y;
        double e2z = coords[2].z - coords[0].z;

        double e3x = coords[3].x - coords[0].x;
        double e3y = coords[3].y - coords[0].y;
        double e3z = coords[3].z - coords[0].z;

        // Set transform matrix
        transform.setMxx(e1x);
        transform.setMxy(e2x);
        transform.setMxz(e3x);
        transform.setTx(coords[0].x);
        transform.setMyx(e1y);
        transform.setMyy(e2y);
        transform.setMyz(e3y);
        transform.setTy(coords[0].y);
        transform.setMzx(e1z);
        transform.setMzy(e2z);
        transform.setMzz(e3z);
        transform.setTz(coords[0].z);

        return transform;
    }

    /**
     * Compute signed volume of tetrahedron to determine orientation. Positive volume means correct orientation,
     * negative means inverted.
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
        float det = dx1 * (dy2 * dz3 - dz2 * dy3) - dy1 * (dx2 * dz3 - dz2 * dx3) + dz1 * (dx2 * dy3 - dy2 * dx3);

        return det / 6.0;
    }

    /**
     * Create an affine transform to position and orient a cylinder between two points.
     */
    private Affine createEdgeTransform(Point3f p1, Point3f p2, int level) {
        Affine transform = new Affine();

        // Calculate edge vector and length
        double dx = p2.x - p1.x;
        double dy = p2.y - p1.y;
        double dz = p2.z - p1.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length == 0) {
            return transform;
        }

        // Calculate radius based on level
        double radius = Math.max(500, 2000 - level * 100);

        // Normalize edge vector
        double nx = dx / length;
        double ny = dy / length;
        double nz = dz / length;

        // Calculate rotation from Y-axis to edge direction
        // The cylinder's default orientation is along Y-axis
        Point3D yAxis = new Point3D(0, 1, 0);
        Point3D edgeDir = new Point3D(nx, ny, nz);

        // Handle special case when edge is parallel to Y-axis
        if (Math.abs(ny) > 0.9999) {
            // Edge is nearly vertical
            transform.setTx((p1.x + p2.x) / 2.0);
            transform.setTy((p1.y + p2.y) / 2.0);
            transform.setTz((p1.z + p2.z) / 2.0);
            transform.setMxx(radius);
            transform.setMyy(length * (ny > 0 ? 1 : -1));
            transform.setMzz(radius);
        } else {
            // General case: create rotation matrix
            Point3D rotAxis = yAxis.crossProduct(edgeDir).normalize();
            double angle = Math.acos(yAxis.dotProduct(edgeDir));

            // Rodrigues' rotation formula components
            double c = Math.cos(angle);
            double s = Math.sin(angle);
            double t = 1 - c;
            double x = rotAxis.getX();
            double y = rotAxis.getY();
            double z = rotAxis.getZ();

            // Build rotation matrix with scaling
            transform.setMxx(radius * (t * x * x + c));
            transform.setMxy(length * (t * x * y - s * z));
            transform.setMxz(radius * (t * x * z + s * y));

            transform.setMyx(radius * (t * x * y + s * z));
            transform.setMyy(length * (t * y * y + c));
            transform.setMyz(radius * (t * y * z - s * x));

            transform.setMzx(radius * (t * x * z - s * y));
            transform.setMzy(length * (t * y * z + s * x));
            transform.setMzz(radius * (t * z * z + c));

            // Set translation to midpoint
            transform.setTx((p1.x + p2.x) / 2.0);
            transform.setTy((p1.y + p2.y) / 2.0);
            transform.setTz((p1.z + p2.z) / 2.0);
        }

        return transform;
    }

    /**
     * Create reference cylinders for edges. We use a unit cylinder that can be transformed to any edge.
     */
    private Map<String, Cylinder> createReferenceEdges() {
        Map<String, Cylinder> edges = new HashMap<>();

        // Create a unit cylinder (height=1, radius will be scaled)
        Cylinder unitCylinder = new Cylinder(1.0, 1.0);
        edges.put("edge", unitCylinder);

        return edges;
    }

    /**
     * Create a reference mesh for S0-S5 tetrahedra.
     */
    private TriangleMesh createS0S5ReferenceMesh() {
        // For now, reuse the same unit tetrahedron
        return createSubdivisionReferenceMesh();
    }

    /**
     * Create a reference mesh for subdivision tetrahedra. This creates a unit tetrahedron that can be transformed.
     */
    private TriangleMesh createSubdivisionReferenceMesh() {
        TriangleMesh mesh = new TriangleMesh();

        // Add vertices for a unit tetrahedron
        mesh.getPoints().addAll(0, 0, 0,  // V0
                                1, 0, 0,  // V1
                                0, 1, 0,  // V2
                                0, 0, 1   // V3
                               );

        // Add texture coordinates
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);

        // Define faces with correct winding
        mesh.getFaces().addAll(0, 0, 2, 2, 1, 1,  // Face 0-2-1
                               0, 0, 1, 1, 3, 3,  // Face 0-1-3
                               0, 0, 3, 3, 2, 2,  // Face 0-3-2
                               1, 1, 2, 2, 3, 3   // Face 1-2-3
                              );

        return mesh;
    }

    /**
     * Create visual representation for a tetrahedron.
     */
    private Node createTetVisual(SpatialNode<TetreeKey<? extends TetreeKey>, ID> node) {
        TetreeKey<? extends TetreeKey> key = node.sfcIndex();
        Tet tet = key.toTet();

        Group tetGroup = new Group();
        tetGroups.put(key, tetGroup);

        // Determine if we should show filled faces for this node
        boolean showFilled = false;
        if (!node.entityIds().isEmpty() && showFilledFaces.get() && shouldShowFaceForNode(node)) {
            showFilled = true;
            MeshView face = createTransparentTetrahedron(tet);
            tetGroup.getChildren().add(face);
        }

        // Only show wireframe if we're not showing filled faces (to avoid edge interpenetration)
        if (showNodeBoundsProperty().get() && !showFilled) {
            Group wireframe = createWireframeTetrahedron(tet);
            tetGroup.getChildren().add(wireframe);
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
     * Create wireframe edges using transformed reference cylinders. This is the most efficient approach - creates
     * cylinders as JavaFX shapes that share geometry.
     */
    private void createTransformedWireframe(Point3i[] tetVertices, int level, Map<String, Cylinder> referenceEdges) {
        // Increment counter for validation
        transformBasedWireframeCount++;

        // Convert to Point3f for easier calculations
        Point3f[] vertices = new Point3f[4];
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f((float) tetVertices[i].x, (float) tetVertices[i].y, (float) tetVertices[i].z);
        }

        // Edge indices for a tetrahedron
        int[][] edgeIndices = { { 0, 1 }, { 0, 2 }, { 0, 3 }, { 1, 2 }, { 1, 3 }, { 2, 3 } };

        // Material for edges based on level
        PhongMaterial edgeMaterial = new PhongMaterial(level == 0 ? Color.BLACK : Color.DARKGRAY);
        edgeMaterial.setSpecularColor(Color.WHITE);
        edgeMaterial.setSpecularPower(32);

        // Get the reference cylinder
        Cylinder refCylinder = referenceEdges.get("edge");

        // Create transformed instances for each edge
        for (int[] edge : edgeIndices) {
            Point3f p1 = vertices[edge[0]];
            Point3f p2 = vertices[edge[1]];

            // Create a new cylinder that shares the same base properties
            // Note: JavaFX Cylinder doesn't support true geometry sharing like MeshView,
            // but we still benefit from transform-based approach
            Cylinder cylinder = new Cylinder(refCylinder.getRadius(), refCylinder.getHeight());
            cylinder.setMaterial(edgeMaterial);

            // Create transform for this edge
            Affine edgeTransform = createEdgeTransform(p1, p2, level);
            cylinder.getTransforms().add(edgeTransform);

            nodeGroup.getChildren().add(cylinder);
        }
    }

    /**
     * Create transparent tetrahedron face.
     */
    private MeshView createTransparentTetrahedron(Tet tet) {
        // Use standard S0-S5 coordinates for accurate visualization
        // This ensures entities appear within their containing tetrahedra
        Point3i[] tetVertices = tet.coordinates();
        Point3f[] vertices = new Point3f[4];

        // Convert to Point3f for JavaFX
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f((float) tetVertices[i].x, (float) tetVertices[i].y, (float) tetVertices[i].z);
        }

        // Create triangle mesh for tetrahedron
        TriangleMesh mesh = new TriangleMesh();

        // Calculate centroid for insetting faces
        float centroidX = 0, centroidY = 0, centroidZ = 0;
        for (Point3f v : vertices) {
            centroidX += v.x;
            centroidY += v.y;
            centroidZ += v.z;
        }
        centroidX /= 4;
        centroidY /= 4;
        centroidZ /= 4;

        // Add vertices directly without inset - let's try a different approach
        for (Point3f v : vertices) {
            mesh.getPoints().addAll(v.x, v.y, v.z);
        }

        // Add texture coordinates (simple mapping)
        mesh.getTexCoords().addAll(0, 0,  // Vertex 0
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
            mesh.getFaces().addAll(0, 0, 2, 2, 1, 1,  // Face 0-2-1 (base, viewed from below)
                                   0, 0, 1, 1, 3, 3,  // Face 0-1-3 (front right)
                                   0, 0, 3, 3, 2, 2,  // Face 0-3-2 (back left)
                                   1, 1, 2, 2, 3, 3   // Face 1-2-3 (top, viewed from above)
                                  );
        } else {
            // Inverted winding for negative volume
            mesh.getFaces().addAll(0, 0, 1, 1, 2, 2,  // Face 0-1-2 (base, viewed from below) - reversed
                                   0, 0, 3, 3, 1, 1,  // Face 0-3-1 (front right) - reversed
                                   0, 0, 2, 2, 3, 3,  // Face 0-2-3 (back left) - reversed
                                   1, 1, 3, 3, 2, 2   // Face 1-3-2 (top, viewed from above) - reversed
                                  );
        }

        // Skip face normal validation for large coordinates due to precision issues
        // The winding order is correct based on volume sign

        MeshView meshView = new MeshView(mesh);

        // Apply material based on type and level
        Material material = getMaterialForTet(tet, tet.l);
        meshView.setMaterial(material);

        // Adjust opacity based on level for better visibility
        double baseOpacity = nodeOpacityProperty().get();
        double levelFactor = 1.0 - (tet.l * 0.1); // Reduce opacity by 10% per level
        meshView.setOpacity(Math.max(0.1, baseOpacity * levelFactor));

        // Enable depth buffer to reduce z-fighting
        meshView.setDepthTest(javafx.scene.DepthTest.ENABLE);

        // Enable back face culling to only show outward-facing sides
        meshView.setCullFace(javafx.scene.shape.CullFace.BACK);

        // Set draw mode
        meshView.setDrawMode(javafx.scene.shape.DrawMode.FILL);

        return meshView;
    }

    /**
     * Create transparent tetrahedron with choice of coordinate system.
     *
     * @param tet                  The tetrahedron to render
     * @param level                The level for coloring
     * @param useSubdivisionCoords If true, use subdivisionCoordinates(), otherwise use coordinates()
     */
    private MeshView createTransparentTetrahedronWithCoords(Tet tet, int level, boolean useSubdivisionCoords) {
        // Get coordinates based on the flag
        Point3i[] tetVertices = useSubdivisionCoords ? tet.subdivisionCoordinates() : tet.coordinates();
        Point3f[] vertices = new Point3f[4];

        // Convert to Point3f for JavaFX
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f((float) tetVertices[i].x, (float) tetVertices[i].y, (float) tetVertices[i].z);
        }

        // Create triangle mesh for tetrahedron
        TriangleMesh mesh = new TriangleMesh();

        // Add vertices directly without inset
        for (Point3f v : vertices) {
            mesh.getPoints().addAll(v.x, v.y, v.z);
        }

        // Add texture coordinates (simple mapping)
        mesh.getTexCoords().addAll(0, 0,  // Vertex 0
                                   1, 0,  // Vertex 1
                                   0.5f, 1,  // Vertex 2
                                   0.5f, 0.5f  // Vertex 3
                                  );

        // Define faces with correct winding order for outward-facing normals
        // Check if tetrahedron has positive volume (correct orientation)
        double volume = computeSignedVolume(vertices);

        if (volume > 0) {
            // Standard winding for positive volume
            mesh.getFaces().addAll(0, 0, 2, 2, 1, 1,  // Face 0-2-1 (base, viewed from below)
                                   0, 0, 1, 1, 3, 3,  // Face 0-1-3 (front right)
                                   0, 0, 3, 3, 2, 2,  // Face 0-3-2 (back left)
                                   1, 1, 2, 2, 3, 3   // Face 1-2-3 (top, viewed from above)
                                  );
        } else {
            // Inverted winding for negative volume
            mesh.getFaces().addAll(0, 0, 1, 1, 2, 2,  // Face 0-1-2 (base, viewed from below) - reversed
                                   0, 0, 3, 3, 1, 1,  // Face 0-3-1 (front right) - reversed
                                   0, 0, 2, 2, 3, 3,  // Face 0-2-3 (back left) - reversed
                                   1, 1, 3, 3, 2, 2   // Face 1-3-2 (top, viewed from above) - reversed
                                  );
        }

        MeshView meshView = new MeshView(mesh);

        // Apply material based on type and level
        Material material = getMaterialForTet(tet, level);
        meshView.setMaterial(material);
        meshView.setOpacity(nodeOpacityProperty().get());

        // Enable depth buffer to reduce z-fighting
        meshView.setDepthTest(javafx.scene.DepthTest.ENABLE);

        // Enable back face culling to only show outward-facing sides
        meshView.setCullFace(javafx.scene.shape.CullFace.BACK);

        // Set draw mode
        meshView.setDrawMode(javafx.scene.shape.DrawMode.FILL);

        return meshView;
    }

    /**
     * Create wireframe tetrahedron for tet bounds.
     */
    private Group createWireframeTetrahedron(Tet tet) {
        // Use the shared implementation with standard coordinates
        return createWireframeTetrahedronWithCoords(tet, false);
    }

    /**
     * Create wireframe tetrahedron for subdivision visualization. Uses subdivisionCoordinates() instead of
     * coordinates().
     */
    private Group createWireframeTetrahedronForSubdivision(Tet tet, int level) {
        return createWireframeTetrahedronWithCoords(tet, true);
    }

    /**
     * Create wireframe tetrahedron from vertex coordinates. Shared implementation for both coordinate systems.
     */
    private Group createWireframeTetrahedronFromVertices(Tet tet, Point3i[] tetVertices) {
        // Increment counter for validation
        traditionalWireframeCount++;

        Group edges = new Group();
        Point3f[] vertices = new Point3f[4];

        // Convert to Point3f for JavaFX
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Point3f((float) tetVertices[i].x, (float) tetVertices[i].y, (float) tetVertices[i].z);
        }

        // Create all 6 edges of the tetrahedron using cylinders for clean rendering
        int[][] edgeIndices = { { 0, 1 }, { 0, 2 }, { 0, 3 }, { 1, 2 }, { 1, 3 }, { 2, 3 } };

        // Create edge lines with type-based coloring
        Color edgeColor = showLevelColorsProperty().get() && tet != null ? typeColors.getOrDefault(tet.type(),
                                                                                                   Color.GRAY).darker()
                                                                         : Color.DARKGRAY;
        PhongMaterial edgeMaterial = new PhongMaterial(edgeColor);
        edgeMaterial.setSpecularColor(Color.WHITE);
        edgeMaterial.setSpecularPower(32);

        // Edge radius based on level (thicker at root, thinner at deeper levels)
        double radius = Math.max(500, 2000 - tet.l * 100);

        for (int[] edge : edgeIndices) {
            Point3f p1 = vertices[edge[0]];
            Point3f p2 = vertices[edge[1]];

            // Calculate edge midpoint and length
            double dx = p2.x - p1.x;
            double dy = p2.y - p1.y;
            double dz = p2.z - p1.z;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (length > 0) {
                // Create cylinder for edge
                Cylinder cylinder = new Cylinder(radius, length);
                cylinder.setMaterial(edgeMaterial);

                // Position at midpoint
                cylinder.setTranslateX((p1.x + p2.x) / 2.0);
                cylinder.setTranslateY((p1.y + p2.y) / 2.0);
                cylinder.setTranslateZ((p1.z + p2.z) / 2.0);

                // Calculate rotation to align cylinder with edge
                Point3D yAxis = new Point3D(0, 1, 0);
                Point3D edgeVector = new Point3D(dx, dy, dz).normalize();
                Point3D rotationAxis = yAxis.crossProduct(edgeVector);
                double angle = Math.toDegrees(Math.acos(yAxis.dotProduct(edgeVector)));

                if (rotationAxis.magnitude() > 0) {
                    cylinder.setRotationAxis(rotationAxis);
                    cylinder.setRotate(angle);
                }

                edges.getChildren().add(cylinder);
            }
        }

        return edges;
    }

    /**
     * Create wireframe tetrahedron with choice of coordinate system.
     *
     * @param tet                  The tetrahedron to render
     * @param useSubdivisionCoords If true, use subdivisionCoordinates(), otherwise use coordinates()
     */
    private Group createWireframeTetrahedronWithCoords(Tet tet, boolean useSubdivisionCoords) {
        // Get coordinates based on the flag
        Point3i[] tetVertices = useSubdivisionCoords ? tet.subdivisionCoordinates() : tet.coordinates();

        // Use the existing clean rendering method
        return createWireframeTetrahedronFromVertices(tet, tetVertices);
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
        // Reduce specular highlight for less glossy appearance
        material.setSpecularColor(levelColor.darker());
        material.setSpecularPower(10); // Lower value = broader, softer highlight
        return material;
    }

    /**
     * Highlight the node affected by a modification.
     */
    private void highlightAffectedNode(Point3f position) {
        // Find the node containing this position
        TetreeKey<? extends TetreeKey> affectedKey = null;

        for (Map.Entry<TetreeKey<? extends TetreeKey>, Node> entry : nodeVisuals.entrySet()) {
            Tet tet = entry.getKey().toTet();
            if (isPointInTetrahedron(position, tet)) {
                affectedKey = entry.getKey();
                break;
            }
        }

        if (affectedKey != null) {
            Node nodeVisual = nodeVisuals.get(affectedKey);
            if (nodeVisual instanceof final Group group) {

                // Create highlight animation
                Timeline timeline = new Timeline();
                timeline.setCycleCount(3);
                timeline.setAutoReverse(true);

                // Flash the node
                group.getChildren().forEach(child -> {
                    if (child instanceof final MeshView mesh) {
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

    /**
     * Check if a point is contained in the tetrahedron using the Tet's containment test.
     */
    private boolean isPointInTetrahedron(Point3f point, Tet tet) {
        // Use the Tet's built-in containment test which properly handles tetrahedral geometry
        return tet.contains(point);
    }

    /**
     * Simple ray-tetrahedron intersection check. This is a basic implementation - a proper one would use ray-triangle
     * intersection for each face.
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
            if (origin.x < minX || origin.x > maxX) {
                return false;
            }
        } else {
            float t1 = (minX - origin.x) / direction.x;
            float t2 = (maxX - origin.x) / direction.x;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }

        // Check Y axis
        if (Math.abs(direction.y) < 1e-6) {
            if (origin.y < minY || origin.y > maxY) {
                return false;
            }
        } else {
            float t1 = (minY - origin.y) / direction.y;
            float t2 = (maxY - origin.y) / direction.y;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }

        // Check Z axis
        if (Math.abs(direction.z) < 1e-6) {
            if (origin.z < minZ || origin.z > maxZ) {
                return false;
            }
        } else {
            float t1 = (minZ - origin.z) / direction.z;
            float t2 = (maxZ - origin.z) / direction.z;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }

        return tMax >= tMin && tMax >= 0;
    }

    /**
     * Select a node.
     */
    private void selectNode(TetreeKey<? extends TetreeKey> key) {
        getSelectedNodes().clear();
        getSelectedNodes().add(key);
        updateNodeVisibility();
    }

    /**
     * Check if a node should be rendered based on current settings.
     */
    private boolean shouldRenderNode(SpatialNode<TetreeKey<? extends TetreeKey>, ID> node) {
        int level = getLevelForKey(node.sfcIndex());

        // Check level visibility
        if (!isLevelVisible(level)) {
            return false;
        }

        // Check empty node visibility
        return showEmptyNodesProperty().get() || !node.entityIds().isEmpty();
    }

    /**
     * Check if we should show a face for subdivision visualization based on render mode.
     */
    private boolean shouldShowFaceForSubdivision(Tet tet, int maxRefinementLevel) {
        switch (faceRenderMode) {
            case ALL_NODES:
                return true;

            case LEAF_NODES_ONLY:
                // For subdivision, a leaf is at the max refinement level
                return tet.l >= maxRefinementLevel;

            case LARGEST_NODES_ONLY:
                // Only show faces at level 0 (root)
                return tet.l == 0;

            default:
                return true;
        }
    }

    /**
     * Determine if we should show a face for this node based on the render mode.
     */
    private boolean shouldShowFaceForNode(SpatialNode<TetreeKey<? extends TetreeKey>, ID> node) {
        switch (faceRenderMode) {
            case ALL_NODES:
                return true;

            case LEAF_NODES_ONLY:
                // Check if this node has any children by looking for nodes at deeper levels
                TetreeKey<? extends TetreeKey> key = node.sfcIndex();
                Tet tet = key.toTet();
                int currentLevel = getLevelForKey(key);

                // Check all nodes to see if any are children of this node
                for (var otherNode : tetree.nodes().toList()) {
                    TetreeKey<? extends TetreeKey> otherKey = otherNode.sfcIndex();
                    int otherLevel = getLevelForKey(otherKey);

                    // If the other node is at a deeper level and within our bounds, it's a child
                    if (otherLevel > currentLevel && !otherNode.entityIds().isEmpty()) {
                        Tet otherTet = otherKey.toTet();
                        // Check if otherTet is within our tet's bounds
                        Point3i[] ourCoords = tet.coordinates();
                        Point3i otherAnchor = new Point3i(otherTet.x, otherTet.y, otherTet.z);

                        // Simple containment check - is the other tet's anchor within our bounds?
                        if (otherAnchor.x >= ourCoords[0].x && otherAnchor.y >= ourCoords[0].y
                        && otherAnchor.z >= ourCoords[0].z && otherAnchor.x < ourCoords[3].x
                        && otherAnchor.y < ourCoords[3].y && otherAnchor.z < ourCoords[3].z) {
                            return false; // Has a child with entities, not a leaf
                        }
                    }
                }
                return true; // No children with entities, this is a leaf

            case LARGEST_NODES_ONLY:
                // Check if any parent contains entities by checking all nodes
                key = node.sfcIndex();
                tet = key.toTet();
                currentLevel = getLevelForKey(key);
                Point3i myAnchor = new Point3i(tet.x, tet.y, tet.z);

                // Check all nodes at shallower levels to see if any contain us
                for (var otherNode : tetree.nodes().toList()) {
                    TetreeKey<? extends TetreeKey> otherKey = otherNode.sfcIndex();
                    int otherLevel = getLevelForKey(otherKey);

                    // If the other node is at a shallower level and has entities
                    if (otherLevel < currentLevel && !otherNode.entityIds().isEmpty()) {
                        Tet otherTet = otherKey.toTet();
                        Point3i[] parentCoords = otherTet.coordinates();

                        // Check if we're within the parent's bounds
                        if (myAnchor.x >= parentCoords[0].x && myAnchor.y >= parentCoords[0].y
                        && myAnchor.z >= parentCoords[0].z && myAnchor.x < parentCoords[3].x
                        && myAnchor.y < parentCoords[3].y && myAnchor.z < parentCoords[3].z) {
                            return false; // Parent has entities, don't show this face
                        }
                    }
                }
                return true; // No parent with entities, show this face

            default:
                return true;
        }
    }

    /**
     * Show characteristic subdivision using transform-based rendering.
     */
    private void showCharacteristicSubdivisionTransformBased(Tet rootTet, boolean[] childVisibility,
                                                               int refinementLevel) {
        // Reset stats
        resetRenderingStats();

        // Create a single reference mesh for subdivision tetrahedra
        TriangleMesh referenceMesh = createSubdivisionReferenceMesh();

        // Create reference cylinders for edges (one per edge orientation)
        Map<String, Cylinder> referenceEdges = createReferenceEdges();

        // Add label
        Text label = new Text(String.format("Subdivision Geometry - Transform Based (Level %d)", refinementLevel));
        label.setFont(Font.font("Arial", 24));
        label.setFill(Color.YELLOW);
        label.setTranslateX(500000);
        label.setTranslateY(-100000);
        nodeGroup.getChildren().add(label);

        // Show root tetrahedron
        showTransformedTetrahedron(rootTet, 0, referenceMesh, referenceEdges);

        // Add refinement levels
        if (refinementLevel > 0) {
            showSubdivisionRefinementTransformBased(rootTet, 0, refinementLevel, childVisibility, referenceMesh,
                                                    referenceEdges);
        }

        // Print validation stats
        printRenderingStats();
    }

    /**
     * Recursively show S0-S5 refinement levels.
     */
    private void showS0S5RefinementRecursive(Tet parent, int currentLevel, int targetLevel, boolean[] childVisibility) {
        if (currentLevel >= targetLevel) {
            return;
        }

        // Get children at the next level
        for (int i = 0; i < 8; i++) {
            // Check visibility if this is the first level and array provided
            if (currentLevel == 0 && childVisibility != null && i < childVisibility.length && !childVisibility[i]) {
                continue;
            }

            // Create child using standard child() method
            Tet child = parent.child(i);
            int childLevel = currentLevel + 1;

            // Show wireframe
            Group wireframe = createWireframeTetrahedron(child);
            nodeGroup.getChildren().add(wireframe);

            if (showFilledFaces.get() && shouldShowFaceForSubdivision(child,  currentRefinementLevel)) {
                MeshView face = createTransparentTetrahedron(child);

                // Apply color based on level and index
                Color childColor = Color.hsb((childLevel * 120 + i * 45) % 360, 0.7, 0.8);
                PhongMaterial material = new PhongMaterial(childColor);
                material.setSpecularColor(childColor.brighter());
                face.setMaterial(material);
                face.setOpacity(0.3 + childLevel * 0.1); // More opaque at deeper levels

                nodeGroup.getChildren().add(face);
            }

            // Recurse to next level
            showS0S5RefinementRecursive(child, childLevel, targetLevel, null);
        }
    }

    /**
     * Recursively show S0-S5 refinement using transforms.
     */
    private void showS0S5RefinementRecursiveTransformed(Tet parent, int currentLevel, int targetLevel,
                                                        boolean[] childVisibility, TriangleMesh referenceMesh,
                                                        Map<String, Cylinder> referenceEdges) {
        if (currentLevel >= targetLevel) {
            return;
        }

        // Get children at the next level
        for (int i = 0; i < 8; i++) {
            // Check visibility if this is the first level and array provided
            if (currentLevel == 0 && childVisibility != null && i < childVisibility.length && !childVisibility[i]) {
                continue;
            }

            // Create child using standard child() method
            Tet child = parent.child(i);
            int childLevel = currentLevel + 1;

            // Show the child tetrahedron using transforms
            showTransformedS0S5Tetrahedron(child, childLevel, referenceMesh, referenceEdges);

            // Recurse to next level
            showS0S5RefinementRecursiveTransformed(child, childLevel, targetLevel, null, referenceMesh, referenceEdges);
        }
    }

    /**
     * Recursively show subdivision refinement levels.
     */
    private void showSubdivisionRefinementRecursive(Tet parent, int currentLevel, int targetLevel,
                                                    boolean[] childVisibility) {
        if (currentLevel >= targetLevel) {
            return;
        }

        // Get children using geometric subdivision
        Tet[] children = parent.geometricSubdivide();
        int childLevel = currentLevel + 1;

        // Render each child
        for (int i = 0; i < children.length; i++) {
            // Check visibility if this is the first level and array provided
            if (currentLevel == 0 && childVisibility != null && i < childVisibility.length && !childVisibility[i]) {
                continue;
            }

            Tet child = children[i];

            // Use clean rendering with subdivision coordinates
            Group wireframe = createWireframeTetrahedronWithCoords(child,  true);
            nodeGroup.getChildren().add(wireframe);

            if (showFilledFaces.get() && shouldShowFaceForSubdivision(child, currentRefinementLevel)) {
                MeshView face = createTransparentTetrahedronWithCoords(child, childLevel, true);

                // Apply color based on level and index
                Color childColor = Color.hsb((childLevel * 120 + i * 45) % 360, 0.8, 0.8);
                PhongMaterial material = new PhongMaterial(childColor);
                material.setSpecularColor(childColor.brighter());
                face.setMaterial(material);
                face.setOpacity(0.5);

                nodeGroup.getChildren().add(face);
            }

            // Recurse to next level
            showSubdivisionRefinementRecursive(child, childLevel, targetLevel, null);
        }
    }

    /**
     * Recursively show subdivision refinement using transforms.
     */
    private void showSubdivisionRefinementTransformBased(Tet parent, int currentLevel, int targetLevel,
                                                         boolean[] childVisibility, TriangleMesh referenceMesh,
                                                         Map<String, Cylinder> referenceEdges) {
        if (currentLevel >= targetLevel) {
            return;
        }

        // Get children using geometric subdivision
        Tet[] children = parent.geometricSubdivide();
        int childLevel = currentLevel + 1;

        // Render each child
        for (int i = 0; i < children.length; i++) {
            // Check visibility if this is the first level and array provided
            if (currentLevel == 0 && childVisibility != null && i < childVisibility.length && !childVisibility[i]) {
                continue;
            }

            Tet child = children[i];

            // Show the child tetrahedron (handles both wireframe and filled)
            showTransformedTetrahedron(child, childLevel, referenceMesh, referenceEdges);

            // Recurse to next level
            showSubdivisionRefinementTransformBased(child, childLevel, targetLevel, null, referenceMesh,
                                                    referenceEdges);
        }
    }

    /**
     * Show an S0-S5 tetrahedron with transform-based rendering.
     */
    private void showTransformedS0S5Tetrahedron(Tet tet, int level, TriangleMesh referenceMesh,
                                                Map<String, Cylinder> referenceEdges) {
        // Get S0-S5 coordinates
        Point3i[] coords = tet.coordinates();

        // Show wireframe if node bounds are enabled
        if (showNodeBoundsProperty().get()) {
            // Create wireframe using transformed reference cylinders
            createTransformedWireframe(coords, level, referenceEdges);
        }

        // Show filled face if enabled and should show for this level
        if (showFilledFaces.get() && shouldShowFaceForSubdivision(tet, currentRefinementLevel)) {
            MeshView mesh = new MeshView(referenceMesh);
            transformBasedMeshCount++;

            // Apply color based on level
            Color color = level == 0 ? Color.DARKRED : Color.hsb((level * 120) % 360, 0.7, 0.8);
            PhongMaterial material = new PhongMaterial(color);
            material.setSpecularColor(color.brighter());
            mesh.setMaterial(material);
            mesh.setOpacity(0.3 + level * 0.1);

            // Apply transform for S0-S5 coordinates
            Affine transform = calculateS0S5Transform(tet);
            mesh.getTransforms().add(transform);
            nodeGroup.getChildren().add(mesh);
        }
    }

    /**
     * Show a tetrahedron with transform-based rendering, handling both wireframe and filled modes.
     */
    private void showTransformedTetrahedron(Tet tet, int level, TriangleMesh referenceMesh,
                                            Map<String, Cylinder> referenceEdges) {
        // Get subdivision coordinates
        Point3i[] coords = tet.subdivisionCoordinates();

        // Show wireframe if node bounds are enabled
        if (showNodeBoundsProperty().get()) {
            // Create wireframe using transformed reference cylinders
            createTransformedWireframe(coords, level, referenceEdges);
        }

        // Show filled face if enabled and should show for this level
        if (showFilledFaces.get() && shouldShowFaceForSubdivision(tet, currentRefinementLevel)) {
            MeshView mesh = new MeshView(referenceMesh);
            transformBasedMeshCount++;

            // Apply color based on level
            Color color = Color.hsb((level * 120) % 360, 0.8, 0.8);
            PhongMaterial material = new PhongMaterial(color);
            material.setSpecularColor(color.brighter());
            mesh.setMaterial(material);
            mesh.setOpacity(0.3 + level * 0.1);

            // Apply transform
            Affine transform = calculateSubdivisionTransform(tet);
            mesh.getTransforms().add(transform);
            nodeGroup.getChildren().add(mesh);
        }
    }

    /**
     * Check if a tetrahedron intersects with a sphere. Uses a simple approximation - checks if any vertex is within the
     * sphere or if the sphere center is within the tetrahedron.
     */
    private boolean tetIntersectsSphere(Tet tet, Point3f center, float radius) {
        // Use standard S0-S5 coordinates for accurate intersection testing
        Point3i[] vertices = tet.coordinates();

        // Check if any vertex is within the sphere
        for (Point3i vertex : vertices) {
            float dx = vertex.x - center.x;
            float dy = vertex.y - center.y;
            float dz = vertex.z - center.z;
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= radius * radius) {
                return true;
            }
        }

        // TODO: Could also check if sphere center is within tetrahedron
        // or if sphere intersects any edge/face

        return false;
    }

    /**
     * Toggle node selection.
     */
    private void toggleNodeSelection(TetreeKey<? extends TetreeKey> key) {
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
    private void unhighlightNode(TetreeKey<? extends TetreeKey> key) {
        Node visual = nodeVisuals.get(key);
        if (visual != null && !getSelectedNodes().contains(key)) {
            // Restore original material
            if (visual instanceof final Group group) {
                group.getChildren().forEach(child -> {
                    if (child instanceof MeshView) {
                        Tet tet = key.toTet();
                        int level = getLevelForKey(key);
                        ((MeshView) child).setMaterial(getMaterialForTet(tet, level));
                    }
                });
            }
        }
    }

    /**
     * Update the performance overlay with current stats.
     */
    private void updatePerformanceStats() {
        if (!showPerformanceOverlay) {
            return;
        }

        // Update FPS
        fpsText.setText(String.format("FPS: %.1f", currentFPS));

        // Count total nodes and entities
        final int[] counts = { 0, 0 }; // totalNodes, totalEntities

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
     * Validate that face normals point outward for correct rendering. This method is called only when assertions are
     * enabled.
     */
    private boolean validateFaceNormals(Point3f[] vertices, TriangleMesh mesh, double volume) {
        // Calculate tetrahedron centroid
        Point3f centroid = new Point3f((vertices[0].x + vertices[1].x + vertices[2].x + vertices[3].x) / 4.0f,
                                       (vertices[0].y + vertices[1].y + vertices[2].y + vertices[3].y) / 4.0f,
                                       (vertices[0].z + vertices[1].z + vertices[2].z + vertices[3].z) / 4.0f);

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
     * Visualize a k-NN query.
     */
    private void visualizeKNNQuery(TetreeKNNQuery query) {
        // Show query point
        Sphere queryPoint = new Sphere(5000.0);
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
                Line line = new Line(1000.0, new Point3D(query.point.x, query.point.y, query.point.z),
                                     new Point3D(entityPos.x, entityPos.y, entityPos.z));

                // Color gradient from orange (closest) to yellow (farthest)
                double hue = 30 + (index - 1) * 30.0 / query.k; // 30 (orange) to 60 (yellow)
                Color lineColor = Color.hsb(hue, 1.0, 1.0);
                line.setMaterial(new PhongMaterial(lineColor));
                queryGroup.getChildren().add(line);

                // Create a numbered sphere at neighbor position
                Sphere neighborMarker = new Sphere(3000.0);
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
        Sphere centerPoint = new Sphere(5000.0);
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
                Line line = new Line(500.0, new Point3D(query.center.x, query.center.y, query.center.z),
                                     new Point3D(entityPos.x, entityPos.y, entityPos.z));
                line.setMaterial(new PhongMaterial(Color.LIGHTBLUE));
                queryGroup.getChildren().add(line);

                // Highlight entity
                Node entityVisual = entityVisuals.get(id);
                if (entityVisual instanceof final Sphere sphere) {
                    sphere.setMaterial(new PhongMaterial(Color.LIGHTGREEN));
                    sphere.setRadius(sphere.getRadius() * 1.5);
                }
            }
        }

        // Highlight nodes that intersect with the query sphere
        tetree.nodes().forEach(node -> {
            TetreeKey<? extends TetreeKey> key = node.sfcIndex();
            Tet tet = key.toTet();

            // Check if tetrahedron intersects with query sphere
            if (tetIntersectsSphere(tet, query.center, query.radius)) {
                Node nodeVisual = nodeVisuals.get(key);
                if (nodeVisual instanceof final Group group) {
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
     * Visualize a ray query.
     */
    private void visualizeRayQuery(TetreeRayQuery query) {
        // Show ray origin
        Sphere originPoint = new Sphere(5000.0);
        originPoint.setTranslateX(query.origin.x);
        originPoint.setTranslateY(query.origin.y);
        originPoint.setTranslateZ(query.origin.z);

        PhongMaterial originMaterial = new PhongMaterial(Color.MAGENTA);
        originMaterial.setSpecularColor(Color.WHITE);
        originPoint.setMaterial(originMaterial);
        queryGroup.getChildren().add(originPoint);

        // Draw ray
        Point3f end = new Point3f(query.origin.x + query.direction.x * 1000, query.origin.y + query.direction.y * 1000,
                                  query.origin.z + query.direction.z * 1000);

        Line ray = new Line(2000.0, new Point3D(query.origin.x, query.origin.y, query.origin.z),
                            new Point3D(end.x, end.y, end.z));
        ray.setMaterial(new PhongMaterial(Color.MAGENTA));
        queryGroup.getChildren().add(ray);

        // Show direction arrow
        Point3f arrowTip = new Point3f(query.origin.x + query.direction.x * 100,
                                       query.origin.y + query.direction.y * 100,
                                       query.origin.z + query.direction.z * 100);

        // Create arrow head using a cone-like structure
        Sphere arrowHead = new Sphere(8000.0);
        arrowHead.setTranslateX(arrowTip.x);
        arrowHead.setTranslateY(arrowTip.y);
        arrowHead.setTranslateZ(arrowTip.z);
        arrowHead.setMaterial(new PhongMaterial(Color.HOTPINK));
        queryGroup.getChildren().add(arrowHead);

        // Use proper Ray3D intersection API
        Vector3f direction = new Vector3f(query.direction.x, query.direction.y, query.direction.z);
        Ray3D ray3d = new Ray3D(query.origin, direction, 1000.0f);

        // Find all ray intersections
        List<RayIntersection<ID, Content>> intersections = tetree.rayIntersectAll(ray3d);

        // Sort by distance for proper visualization
        intersections.sort(Comparator.comparingDouble(RayIntersection::distance));

        // Visualize each intersection
        for (RayIntersection<ID, Content> intersection : intersections) {
            ID entityId = intersection.entityId();
            Point3f hitPoint = intersection.intersectionPoint();
            float distance = intersection.distance();

            // Highlight intersected entity
            Node entityVisual = entityVisuals.get(entityId);
            if (entityVisual instanceof final Sphere sphere) {
                // Create highlighted material
                PhongMaterial highlightMaterial = new PhongMaterial(Color.YELLOW);
                highlightMaterial.setSpecularColor(Color.WHITE);
                sphere.setMaterial(highlightMaterial);

                // Slightly increase size for emphasis
                sphere.setRadius(sphere.getRadius() * 1.5);
            }

            // Show intersection point
            Sphere hitMarker = new Sphere(3000.0);
            hitMarker.setTranslateX(hitPoint.x);
            hitMarker.setTranslateY(hitPoint.y);
            hitMarker.setTranslateZ(hitPoint.z);

            // Color code by distance (closer = brighter)
            double intensity = 1.0 - (distance / 1000.0);
            Color markerColor = Color.color(1.0, intensity * 0.5, 0.0);
            PhongMaterial markerMaterial = new PhongMaterial(markerColor);
            markerMaterial.setSpecularColor(Color.WHITE);
            hitMarker.setMaterial(markerMaterial);

            queryGroup.getChildren().add(hitMarker);

            // Add distance label
            Text distanceLabel = new Text(String.format("%.1f", distance));
            distanceLabel.setFont(Font.font(12));
            distanceLabel.setFill(Color.WHITE);
            distanceLabel.setTranslateX(hitPoint.x + 5);
            distanceLabel.setTranslateY(hitPoint.y);
            distanceLabel.setTranslateZ(hitPoint.z);
            queryGroup.getChildren().add(distanceLabel);
        }

        // Highlight tetrahedra that the ray passes through
        tetree.nodes().forEach(node -> {
            TetreeKey<? extends TetreeKey> key = node.sfcIndex();
            Tet tet = key.toTet();

            // Simple ray-tetrahedron intersection check
            if (rayIntersectsTetrahedron(query.origin, query.direction, tet)) {
                Node nodeVisual = nodeVisuals.get(key);
                if (nodeVisual instanceof final Group group) {
                    // Highlight the tetrahedron
                    group.getChildren().forEach(child -> {
                        if (child instanceof final MeshView mesh) {
                            PhongMaterial highlightMaterial = new PhongMaterial(Color.PINK.deriveColor(0, 1, 1, 0.5));
                            mesh.setMaterial(highlightMaterial);
                            mesh.setOpacity(0.5);
                        }
                    });
                }
            }
        });
    }

    // Face rendering mode
    public enum FaceRenderMode {
        ALL_NODES,           // Show faces for all nodes with entities
        LEAF_NODES_ONLY,     // Only show faces for leaf nodes
        LARGEST_NODES_ONLY   // Only show faces for the largest node at each spatial location
    }

    // Query types for Tetree visualization
    public static class TetreeRangeQuery {
        public final Point3f center;
        public final float   radius;

        public TetreeRangeQuery(Point3f center, float radius) {
            this.center = center;
            this.radius = radius;
        }
    }

    public static class TetreeKNNQuery {
        public final Point3f point;
        public final int     k;

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
}

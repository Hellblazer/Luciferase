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
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
// KuhnTetree import removed - using base Tetree with positive volume correction
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.vecmath.Point3f;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Demo application for TetreeVisualization showing tetrahedral spatial indexing.
 *
 * The visualization uses JavaFX transforms to handle the large Tetree coordinate space: - Natural Tetree coordinates
 * range from 0 to 2^20 (1,048,576) - A scene-level Scale transform (default 0.0001) brings this to viewable size - All
 * geometry (tetrahedra, entities, axes) uses natural coordinates
 *
 * @author hal.hildebrand
 */
public class TetreeVisualizationDemo extends Application {

    private static final double SCENE_WIDTH     = 1200;
    private static final double SCENE_HEIGHT    = 800;
    private static final double CAMERA_DISTANCE = 5000;  // Distance for viewing scaled scene at 0.001 scale

    private final Rotate    rotateX   = new Rotate(0, Rotate.X_AXIS);
    private final Rotate    rotateY   = new Rotate(0, Rotate.Y_AXIS);
    private final Translate translate = new Translate(0, 0, 0);

    private TetreeVisualization<LongEntityID, String>               visualization;
    private TransformBasedTetreeVisualization<LongEntityID, String> transformBasedViz;
    private Tetree<LongEntityID, String>                            tetree;
    private SubScene                                                scene3D;
    private double                                                  mouseX, mouseY;

    // Additional visualization groups
    private Group cubeDecompositionGroup;
    private Group characteristicTypesGroup;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Tetree Visualization Demo");

        // Create the Tetree - now with built-in positive volume correction
        tetree = new Tetree<>(new SequentialLongIDGenerator());

        // Create visualization
        visualization = new TetreeVisualization<>(tetree);

        // Setup 3D scene
        Group root3D = visualization.getSceneRoot();
        root3D.getTransforms().addAll(translate, rotateX, rotateY);

        // Add coordinate axes
        Group axes = createAxes();
        root3D.getChildren().add(axes);

        // Initialize additional visualization groups
        cubeDecompositionGroup = new Group();
        characteristicTypesGroup = new Group();
        root3D.getChildren().addAll(cubeDecompositionGroup, characteristicTypesGroup);

        scene3D = new SubScene(root3D, SCENE_WIDTH - 320, SCENE_HEIGHT, true, SceneAntialiasing.BALANCED);
        scene3D.setFill(Color.DARKGRAY);

        // Setup camera with appropriate clipping planes for scaled coordinates
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.01);
        camera.setFarClip(100000.0);
        camera.setTranslateZ(-CAMERA_DISTANCE);
        scene3D.setCamera(camera);

        // Setup lighting with softer, more diffuse lighting
        AmbientLight ambientLight = new AmbientLight(Color.WHITE.deriveColor(0, 1, 0.6, 1)); // Increased ambient

        // Add multiple point lights for better coverage
        PointLight pointLight1 = new PointLight(Color.WHITE.deriveColor(0, 1, 0.7, 1));
        pointLight1.setTranslateX(2000);
        pointLight1.setTranslateY(-2000);
        pointLight1.setTranslateZ(-3000);

        PointLight pointLight2 = new PointLight(Color.WHITE.deriveColor(0, 1, 0.5, 1));
        pointLight2.setTranslateX(-2000);
        pointLight2.setTranslateY(1000);
        pointLight2.setTranslateZ(-2000);

        root3D.getChildren().addAll(ambientLight, pointLight1, pointLight2);

        // Setup mouse controls
        setupMouseControls();

        // Create UI controls
        VBox controls = createControls();

        // Wrap controls in a ScrollPane
        ScrollPane scrollPane = new ScrollPane(controls);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefWidth(320);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        // Layout
        BorderPane borderPane = new BorderPane();
        borderPane.setCenter(scene3D);
        borderPane.setRight(scrollPane);

        Scene scene = new Scene(borderPane, SCENE_WIDTH, SCENE_HEIGHT);
        primaryStage.setScene(scene);
        primaryStage.show();

        // Add some initial entities for demonstration
        addRandomEntities(20);

        // Initial visualization
        visualization.updateVisualization();

        // Center view on entities after initial load
        javafx.application.Platform.runLater(() -> {
            if (tetree.size() > 0) {
                centerViewOnEntities();
            }
        });

        // Focus the scene
        scene3D.requestFocus();
    }

    private void addRandomEntities(int count) {
        Random random = new Random();
        // Insert entities at a coarser level (5) for better visibility
        // At level 5, cell size is 2^15 = 32768
        // This gives us much larger, more visible tetrahedra
        byte level = 5;
        float maxCoord = (float) Math.pow(2, MortonCurve.MAX_REFINEMENT_LEVEL);

        // Spread entities across the middle portion of the space
        // This ensures they're visible and not at the edges
        float minRange = maxCoord * 0.2f;  // Start at 20% of max
        float maxRange = maxCoord * 0.8f;  // End at 80% of max
        float range = maxRange - minRange;

        System.out.println("\n=== Inserting " + count + " entities at level " + level + " ===");

        for (int i = 0; i < count; i++) {
            float x = minRange + random.nextFloat() * range;
            float y = minRange + random.nextFloat() * range;
            float z = minRange + random.nextFloat() * range;
            Point3f position = new Point3f(x, y, z);

            // Debug: find the enclosing tetrahedron before insertion
            var enclosingNode = tetree.enclosing(new javax.vecmath.Point3i((int) x, (int) y, (int) z), level);
            if (enclosingNode != null) {
                System.out.printf("Entity %d at (%.0f, %.0f, %.0f) -> Enclosing node found at level %d%n", i, x, y, z,
                                  level);
            } else {
                System.out.printf("Entity %d at (%.0f, %.0f, %.0f) -> No enclosing node found%n", i, x, y, z);
            }

            tetree.insert(position, level, "Entity " + i);
        }

        // Verify all entities are in nodes
        System.out.println("\n=== Verifying entity containment ===");
        verifyEntityContainment();

        visualization.updateVisualization();
    }

    private void centerViewOnEntities() {
        if (tetree.size() > 0) {
            // Calculate center of all entities
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;

            int entityCount = 0;
            for (var node : tetree.nodes().toList()) {
                for (var entityId : node.entityIds()) {
                    Point3f pos = tetree.getEntityPosition(entityId);
                    if (pos != null) {
                        minX = Math.min(minX, pos.x);
                        minY = Math.min(minY, pos.y);
                        minZ = Math.min(minZ, pos.z);
                        maxX = Math.max(maxX, pos.x);
                        maxY = Math.max(maxY, pos.y);
                        maxZ = Math.max(maxZ, pos.z);
                        entityCount++;
                    }
                }
            }

            if (entityCount > 0) {
                // Center the camera on the entity bounding box
                float centerX = (minX + maxX) / 2;
                float centerY = (minY + maxY) / 2;
                float centerZ = (minZ + maxZ) / 2;

                System.out.printf(
                "Centering on %d entities: bounds [%.1f,%.1f,%.1f] to [%.1f,%.1f,%.1f], center [%.1f,%.1f,%.1f]%n",
                entityCount, minX, minY, minZ, maxX, maxY, maxZ, centerX, centerY, centerZ);

                // Reset rotation to look straight at entities
                rotateX.setAngle(-20); // Slight downward tilt
                rotateY.setAngle(0);

                // Apply scale to convert from Tetree coordinates to scene coordinates
                double scale = visualization.rootScaleProperty().get();
                System.out.printf("Scale: %.6f%n", scale);

                // Center the view by translating in the opposite direction
                translate.setX(-centerX * scale);
                translate.setY(-centerY * scale);
                translate.setZ(0); // Keep Z at 0, camera distance handles depth

                // Adjust camera distance based on entity spread
                float spread = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
                double scaledSpread = spread * scale;
                double optimalDistance = scaledSpread * 2.5; // View from 2.5x the spread distance
                scene3D.getCamera().setTranslateZ(-Math.max(optimalDistance, 1000));

                System.out.printf("Camera position: translate [%.1f,%.1f,%.1f], distance %.1f%n", translate.getX(),
                                  translate.getY(), translate.getZ(), scene3D.getCamera().getTranslateZ());
            }
        }
    }

    private double computeSignedVolume(Point3f v0, Point3f v1, Point3f v2, Point3f v3) {
        float dx1 = v1.x - v0.x;
        float dy1 = v1.y - v0.y;
        float dz1 = v1.z - v0.z;

        float dx2 = v2.x - v0.x;
        float dy2 = v2.y - v0.y;
        float dz2 = v2.z - v0.z;

        float dx3 = v3.x - v0.x;
        float dy3 = v3.y - v0.y;
        float dz3 = v3.z - v0.z;

        float det = dx1 * (dy2 * dz3 - dz2 * dy3) - dy1 * (dx2 * dz3 - dz2 * dx3) + dz1 * (dx2 * dy3 - dy2 * dx3);

        return det / 6.0;
    }

    private Group createAxes() {
        Group axesGroup = new Group();

        // Axes in natural coordinates (will be scaled by scene transform)
        // Root tet edge length is 2^20, so make axes 2^21 for visibility
        double axisLength = 2097152; // 2^21
        double axisRadius = 8192;    // 2^13

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

    private VBox createControls() {
        VBox controls = new VBox(8);
        controls.setPadding(new Insets(10));
        controls.setPrefWidth(280);

        // Entity controls
        controls.getChildren().add(new Label("Entity Controls"));
        Button addRandomEntities = new Button("Add 10 Random Entities");
        addRandomEntities.setOnAction(_ -> addRandomEntities(10));

        Button clearEntities = new Button("Clear All Entities");
        clearEntities.setOnAction(_ -> {
            // Collect all entity IDs from nodes and remove them
            var entityIds = tetree.nodes().flatMap(node -> node.entityIds().stream()).distinct().toList();
            entityIds.forEach(tetree::removeEntity);
            visualization.updateVisualization();
        });

        controls.getChildren().addAll(addRandomEntities, clearEntities);

        // Visualization controls
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Visualization Settings"));

        CheckBox showEmptyNodes = new CheckBox("Show Empty Nodes");
        showEmptyNodes.selectedProperty().bindBidirectional(visualization.showEmptyNodesProperty());

        CheckBox showEntityPositions = new CheckBox("Show Entity Positions");
        showEntityPositions.setSelected(true);
        showEntityPositions.selectedProperty().bindBidirectional(visualization.showEntityPositionsProperty());

        CheckBox showNodeBounds = new CheckBox("Show Node Bounds");
        showNodeBounds.setSelected(true);
        showNodeBounds.selectedProperty().bindBidirectional(visualization.showNodeBoundsProperty());

        CheckBox showLevelColors = new CheckBox("Show Level Colors");
        showLevelColors.setSelected(true);
        showLevelColors.selectedProperty().bindBidirectional(visualization.showLevelColorsProperty());

        CheckBox showFilledFaces = new CheckBox("Show Filled Faces");
        showFilledFaces.setSelected(false);  // Start with wireframe only
        showFilledFaces.selectedProperty().bindBidirectional(visualization.showFilledFacesProperty());

        controls.getChildren().addAll(showEmptyNodes, showEntityPositions, showNodeBounds, showLevelColors,
                                      showFilledFaces);

        // Camera control
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Camera Controls"));

        Button centerOnEntities = new Button("Center View on Entities");
        centerOnEntities.setOnAction(_ -> centerViewOnEntities());

        Button resetView = new Button("Reset View");
        resetView.setOnAction(_ -> resetView());

        controls.getChildren().addAll(centerOnEntities, resetView);

        // Add containment verification button
        Button verifyContainment = new Button("Verify Entity Containment");
        verifyContainment.setTooltip(new Tooltip("Check if all entities have visible containing tetrahedra"));
        verifyContainment.setOnAction(_ -> verifyEntityContainment());
        controls.getChildren().add(verifyContainment);

        // Transform-based rendering option
        CheckBox useTransformBased = new CheckBox("Transform-Based Rendering");
        useTransformBased.setTooltip(
        new Tooltip("Uses 6 reference meshes with transforms\ninstead of creating individual meshes"));
        useTransformBased.setOnAction(_ -> {
            if (useTransformBased.isSelected()) {
                showTransformBasedVisualization();
                // Verify it's working - need to count from the transform root specifically
                if (transformBasedViz != null) {
                    TransformBasedVerification.printVerificationStats(transformBasedViz.getSceneRoot(),
                                                                      "Transform-Based");
                }
            } else {
                // Switch back to traditional rendering
                Group root3D = visualization.getSceneRoot();

                // Remove transform-based visualization if present
                if (transformBasedViz != null) {
                    root3D.getChildren().remove(transformBasedViz.getSceneRoot());
                }

                // Show all traditional meshes again
                root3D.getChildren().forEach(child -> {
                    if (child instanceof Group || child instanceof MeshView) {
                        child.setVisible(true);
                    }
                });

                visualization.updateVisualization();
                // Show traditional stats for comparison
                TransformBasedVerification.printVerificationStats(visualization.getSceneRoot(), "Traditional");
            }
        });
        controls.getChildren().add(useTransformBased);

        // Add verification button
        Button verifyBtn = new Button("Verify Rendering Mode");
        verifyBtn.setTooltip(new Tooltip("Print statistics about current rendering mode"));
        verifyBtn.setOnAction(_ -> {
            // Run later to ensure scene graph is updated
            javafx.application.Platform.runLater(() -> {
                if (useTransformBased.isSelected() && transformBasedViz != null) {
                    System.out.println("\nDEBUG: Verifying transform-based scene root:");
                    System.out.println("  Root children: " + transformBasedViz.getSceneRoot().getChildren().size());
                    TransformBasedVerification.printVerificationStats(transformBasedViz.getSceneRoot(),
                                                                      "Transform-Based");
                } else {
                    TransformBasedVerification.printVerificationStats(visualization.getSceneRoot(), "Traditional");
                }
            });
        });
        controls.getChildren().add(verifyBtn);

        // Level controls
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Level Range"));

        Slider minLevel = new Slider(0, 20, 0);
        minLevel.setShowTickLabels(true);
        minLevel.setShowTickMarks(true);
        minLevel.setMajorTickUnit(5);
        minLevel.setValue(3); // Show from level 3 to capture larger tets
        minLevel.valueProperty().bindBidirectional(visualization.minLevelProperty());

        Slider maxLevel = new Slider(0, 20, 20);
        maxLevel.setShowTickLabels(true);
        maxLevel.setShowTickMarks(true);
        maxLevel.setMajorTickUnit(5);
        maxLevel.setValue(7); // Show up to level 7 to see entity tets at level 5
        maxLevel.valueProperty().bindBidirectional(visualization.maxLevelProperty());

        controls.getChildren().addAll(new Label("Min Level:"), minLevel, new Label("Max Level:"), maxLevel);

        // Opacity control
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Node Opacity"));

        Slider opacity = new Slider(0, 1, 0.5);
        opacity.setShowTickLabels(true);
        opacity.setShowTickMarks(true);
        opacity.setMajorTickUnit(0.2);
        opacity.valueProperty().bindBidirectional(visualization.nodeOpacityProperty());

        controls.getChildren().add(opacity);

        // Root tetrahedron scale control
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Scene Scale"));

        // Scale range for JavaFX transform
        // Root tet has edge length 2^20 = 1,048,576
        // Scale 0.001 (default) brings this to ~1050 units for comfortable viewing
        // This makes level 5 cells visible at ~32 unit size
        Slider rootScale = new Slider(0.0001, 0.01, 0.001);
        rootScale.setShowTickLabels(true);
        rootScale.setShowTickMarks(true);
        rootScale.setMajorTickUnit(0.002);
        rootScale.setMinorTickCount(1);
        rootScale.valueProperty().bindBidirectional(visualization.rootScaleProperty());

        Label scaleLabel = new Label(String.format("Scale: %.6f", rootScale.getValue()));
        rootScale.valueProperty().addListener(
        (_, _, newVal) -> scaleLabel.setText(String.format("Scale: %.6f", newVal.doubleValue())));

        controls.getChildren().addAll(scaleLabel, rootScale);

        // Special visualization
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Special Visualizations"));

        Button showSubdivision = new Button("Show Tetrahedral Subdivision");
        showSubdivision.setOnAction(_ -> visualization.showCharacteristicDecomposition());
        controls.getChildren().add(showSubdivision);

        CheckBox showCubeDecomposition = new CheckBox("Show Cube Decomposition");
        showCubeDecomposition.setOnAction(_ -> {
            if (showCubeDecomposition.isSelected()) {
                showCubeDecompositionVisualization();
            } else {
                hideCubeDecompositionVisualization();
            }
        });
        controls.getChildren().add(showCubeDecomposition);

        CheckBox showCharacteristicTypes = new CheckBox("Show 6 Characteristic Types");
        showCharacteristicTypes.setOnAction(_ -> {
            if (showCharacteristicTypes.isSelected()) {
                showCharacteristicTypesVisualization();
            } else {
                hideCharacteristicTypesVisualization();
            }
        });
        controls.getChildren().add(showCharacteristicTypes);

        // Query visualization controls
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Query Visualization"));

        // Range query
        HBox rangeBox = new HBox(5);
        TextField radiusField = new TextField("200");
        radiusField.setPrefWidth(60);
        Button rangeQueryBtn = new Button("Range Query");
        rangeQueryBtn.setOnAction(_ -> {
            try {
                float radius = Float.parseFloat(radiusField.getText());
                // Use center of scene as query center
                Point3f center = new Point3f(500, 500, 500);
                visualization.visualizeQuery(new TetreeVisualization.TetreeRangeQuery(center, radius));
            } catch (NumberFormatException ex) {
                radiusField.setText("200");
            }
        });
        rangeBox.getChildren().addAll(new Label("Radius:"), radiusField, rangeQueryBtn);
        controls.getChildren().add(rangeBox);

        // k-NN query
        HBox knnBox = new HBox(5);
        TextField kField = new TextField("5");
        kField.setPrefWidth(60);
        Button knnQueryBtn = new Button("k-NN Query");
        knnQueryBtn.setOnAction(_ -> {
            try {
                int k = Integer.parseInt(kField.getText());
                // Use a random point for k-NN query
                Random rand = new Random();
                Point3f queryPoint = new Point3f(rand.nextFloat() * 800 + 100, rand.nextFloat() * 800 + 100,
                                                 rand.nextFloat() * 800 + 100);
                visualization.visualizeQuery(new TetreeVisualization.TetreeKNNQuery(queryPoint, k));
            } catch (NumberFormatException ex) {
                kField.setText("5");
            }
        });
        knnBox.getChildren().addAll(new Label("k:"), kField, knnQueryBtn);
        controls.getChildren().add(knnBox);

        // Ray query
        Button rayQueryBtn = new Button("Ray Query (Random)");
        rayQueryBtn.setOnAction(_ -> {
            Random rand = new Random();
            Point3f origin = new Point3f(rand.nextFloat() * 200, rand.nextFloat() * 200, rand.nextFloat() * 200);
            // Random direction vector (normalized)
            float dx = rand.nextFloat() * 2 - 1;
            float dy = rand.nextFloat() * 2 - 1;
            float dz = rand.nextFloat() * 2 - 1;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            Point3f direction = new Point3f(dx / len, dy / len, dz / len);

            visualization.visualizeQuery(new TetreeVisualization.TetreeRayQuery(origin, direction));
        });
        controls.getChildren().add(rayQueryBtn);

        Button clearQueriesBtn = new Button("Clear Queries");
        clearQueriesBtn.setOnAction(_ -> visualization.clearQueryVisualization());
        controls.getChildren().add(clearQueriesBtn);

        // Collision detection
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Collision Detection"));

        Button detectCollisionsBtn = new Button("Highlight Collisions");
        detectCollisionsBtn.setOnAction(_ -> visualization.highlightCollisions());
        controls.getChildren().add(detectCollisionsBtn);

        Button clearCollisionsBtn = new Button("Clear Collision Highlights");
        clearCollisionsBtn.setOnAction(_ -> visualization.clearCollisionHighlights());
        controls.getChildren().add(clearCollisionsBtn);

        // Tree modification animation
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Tree Modification Animation"));

        CheckBox animateModifications = new CheckBox("Animate Modifications");
        animateModifications.setSelected(false);
        animateModifications.selectedProperty().addListener(
        (_, _, newVal) -> visualization.setAnimateModifications(newVal));
        controls.getChildren().add(animateModifications);

        Button animatedAddBtn = new Button("Add Entity (Animated)");
        animatedAddBtn.setOnAction(_ -> {
            Random rand = new Random();
            float x = rand.nextFloat() * 800 + 100;
            float y = rand.nextFloat() * 800 + 100;
            float z = rand.nextFloat() * 800 + 100;
            Point3f position = new Point3f(x, y, z);

            // Get the next entity ID
            LongEntityID newId = tetree.insert(position, (byte) 10, "Animated Entity");

            // Animate the insertion
            visualization.animateEntityInsertion(newId, position);
        });
        controls.getChildren().add(animatedAddBtn);

        // Performance overlay
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Performance Monitoring"));

        CheckBox showPerformance = new CheckBox("Show Performance Overlay");
        showPerformance.setSelected(false);
        showPerformance.selectedProperty().addListener(
        (_, _, newVal) -> visualization.setShowPerformanceOverlay(newVal));
        controls.getChildren().add(showPerformance);

        // Export snapshot
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("Export Visualization"));

        CheckBox includeOverlay = new CheckBox("Include Overlay in Export");
        includeOverlay.setSelected(false);

        Button exportSnapshotBtn = new Button("Export Snapshot...");
        exportSnapshotBtn.setOnAction(_ -> {
            boolean success = visualization.exportSnapshot(includeOverlay.isSelected());
            if (success) {
                // Briefly show success feedback
                String originalText = exportSnapshotBtn.getText();
                exportSnapshotBtn.setText("✓ Exported!");
                exportSnapshotBtn.setDisable(true);

                // Reset button after 2 seconds
                Timeline reset = new Timeline(new KeyFrame(Duration.seconds(2), _ -> {
                    exportSnapshotBtn.setText(originalText);
                    exportSnapshotBtn.setDisable(false);
                }));
                reset.play();
            }
        });

        controls.getChildren().addAll(includeOverlay, exportSnapshotBtn);

        // Performance info
        controls.getChildren().add(new Separator());
        Label performanceLabel = new Label();
        Button updateButton = new Button("Update Visualization");
        updateButton.setOnAction(_ -> {
            long start = System.currentTimeMillis();
            visualization.updateVisualization();
            long time = System.currentTimeMillis() - start;
            performanceLabel.setText(
            String.format("Update time: %d ms\nNodes: %d\nEntities: %d", time, visualization.getVisibleNodeCount(),
                          visualization.getVisibleEntityCount()));
        });

        controls.getChildren().addAll(updateButton, performanceLabel);

        // Educational info
        controls.getChildren().add(new Separator());
        controls.getChildren().add(new Label("About Tetree"));

        TextArea infoText = new TextArea("The Tetree uses tetrahedral decomposition of space.\n\n"
                                         + "A cube can be decomposed into 6 characteristic tetrahedra (S0-S5), "
                                         + "which form the basis for the spatial indexing structure.\n\n"
                                         + "Each tetrahedron can be further subdivided into 8 smaller tetrahedra, "
                                         + "creating a hierarchical structure similar to an octree but using "
                                         + "tetrahedral cells instead of cubic ones.");
        infoText.setWrapText(true);
        infoText.setEditable(false);
        infoText.setPrefRowCount(6);
        infoText.setStyle("-fx-font-size: 10px;");
        controls.getChildren().add(infoText);

        // Add help text
        controls.getChildren().add(new Separator());
        Label helpLabel = new Label(
        "Controls:\n" + "• Left drag: Rotate\n" + "• Right drag: Pan\n" + "• Scroll: Zoom\n" + "• WASD: Move X/Y\n"
        + "• Q/E: Move Z\n" + "• R: Reset view\n" + "• Hold Shift: Move faster");
        helpLabel.setStyle("-fx-font-size: 10px;");
        controls.getChildren().add(helpLabel);

        return controls;
    }

    private MeshView createTetrahedronMesh(Point3f v0, Point3f v1, Point3f v2, Point3f v3, Color color) {
        TriangleMesh mesh = new TriangleMesh();

        // Add vertices
        mesh.getPoints().addAll(v0.x, v0.y, v0.z, v1.x, v1.y, v1.z, v2.x, v2.y, v2.z, v3.x, v3.y, v3.z);

        // Add texture coordinates
        mesh.getTexCoords().addAll(0, 0, 1, 0, 0.5f, 1, 0.5f, 0.5f);

        // Define faces with correct winding order
        double volume = computeSignedVolume(v0, v1, v2, v3);

        if (volume > 0) {
            mesh.getFaces().addAll(0, 0, 2, 2, 1, 1,  // Face 0-2-1
                                   0, 0, 1, 1, 3, 3,  // Face 0-1-3
                                   0, 0, 3, 3, 2, 2,  // Face 0-3-2
                                   1, 1, 2, 2, 3, 3   // Face 1-2-3
                                  );
        } else {
            mesh.getFaces().addAll(0, 0, 1, 1, 2, 2,  // Face 0-1-2
                                   0, 0, 3, 3, 1, 1,  // Face 0-3-1
                                   0, 0, 2, 2, 3, 3,  // Face 0-2-3
                                   1, 1, 3, 3, 2, 2   // Face 1-3-2
                                  );
        }

        MeshView meshView = new MeshView(mesh);
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(color.brighter());
        meshView.setMaterial(material);

        return meshView;
    }

    private Group createWireframeCube(double offset, double size) {
        Group wireframe = new Group();

        // Create 12 edges of the cube
        PhongMaterial edgeMaterial = new PhongMaterial(Color.BLACK);
        double edgeThickness = size * 0.005; // Scale thickness with cube size

        // Helper to create edge
        var createEdge = new java.util.function.Function<double[], Box>() {
            @Override
            public Box apply(double[] params) {
                Box edge = new Box(params[0], params[1], params[2]);
                edge.setTranslateX(params[3]);
                edge.setTranslateY(params[4]);
                edge.setTranslateZ(params[5]);
                edge.setMaterial(edgeMaterial);
                return edge;
            }
        };

        // Bottom face edges
        wireframe.getChildren().add(
        createEdge.apply(new double[] { size, edgeThickness, edgeThickness, offset + size / 2, offset, offset }));
        wireframe.getChildren().add(createEdge.apply(
        new double[] { edgeThickness, size, edgeThickness, offset + size, offset + size / 2, offset }));
        wireframe.getChildren().add(createEdge.apply(
        new double[] { size, edgeThickness, edgeThickness, offset + size / 2, offset + size, offset }));
        wireframe.getChildren().add(
        createEdge.apply(new double[] { edgeThickness, size, edgeThickness, offset, offset + size / 2, offset }));

        // Top face edges
        wireframe.getChildren().add(createEdge.apply(
        new double[] { size, edgeThickness, edgeThickness, offset + size / 2, offset, offset + size }));
        wireframe.getChildren().add(createEdge.apply(
        new double[] { edgeThickness, size, edgeThickness, offset + size, offset + size / 2, offset + size }));
        wireframe.getChildren().add(createEdge.apply(
        new double[] { size, edgeThickness, edgeThickness, offset + size / 2, offset + size, offset + size }));
        wireframe.getChildren().add(createEdge.apply(
        new double[] { edgeThickness, size, edgeThickness, offset, offset + size / 2, offset + size }));

        // Vertical edges
        wireframe.getChildren().add(
        createEdge.apply(new double[] { edgeThickness, edgeThickness, size, offset, offset, offset + size / 2 }));
        wireframe.getChildren().add(createEdge.apply(
        new double[] { edgeThickness, edgeThickness, size, offset + size, offset, offset + size / 2 }));
        wireframe.getChildren().add(createEdge.apply(
        new double[] { edgeThickness, edgeThickness, size, offset + size, offset + size, offset + size / 2 }));
        wireframe.getChildren().add(createEdge.apply(
        new double[] { edgeThickness, edgeThickness, size, offset, offset + size, offset + size / 2 }));

        return wireframe;
    }

    private void hideCharacteristicTypesVisualization() {
        characteristicTypesGroup.getChildren().clear();
    }

    private void hideCubeDecompositionVisualization() {
        cubeDecompositionGroup.getChildren().clear();
    }

    private void resetView() {
        rotateX.setAngle(0);
        rotateY.setAngle(0);
        translate.setX(0);
        translate.setY(0);
        translate.setZ(0);
        scene3D.getCamera().setTranslateZ(-CAMERA_DISTANCE);
    }

    private void setupMouseControls() {
        scene3D.setOnMousePressed(event -> {
            mouseX = event.getSceneX();
            mouseY = event.getSceneY();
        });

        scene3D.setOnMouseDragged(event -> {
            double deltaX = event.getSceneX() - mouseX;
            double deltaY = event.getSceneY() - mouseY;
            mouseX = event.getSceneX();
            mouseY = event.getSceneY();

            if (event.isPrimaryButtonDown()) {
                rotateX.setAngle(rotateX.getAngle() - (deltaY * 0.2));
                rotateY.setAngle(rotateY.getAngle() + (deltaX * 0.2));
            }

            if (event.isSecondaryButtonDown()) {
                translate.setX(translate.getX() + deltaX);
                translate.setY(translate.getY() + deltaY);
            }
        });

        scene3D.setOnScroll(event -> {
            double delta = event.getDeltaY();
            scene3D.getCamera().setTranslateZ(scene3D.getCamera().getTranslateZ() + delta * 2);
        });

        // Keyboard controls
        scene3D.setOnKeyPressed(event -> {
            double moveAmount = 10;
            if (event.isShiftDown()) {
                moveAmount = 50;
            }

            switch (event.getCode()) {
                case W -> translate.setY(translate.getY() - moveAmount);
                case S -> translate.setY(translate.getY() + moveAmount);
                case A -> translate.setX(translate.getX() - moveAmount);
                case D -> translate.setX(translate.getX() + moveAmount);
                case Q -> translate.setZ(translate.getZ() - moveAmount);
                case E -> translate.setZ(translate.getZ() + moveAmount);
                case R -> resetView();
            }
        });

        // Make scene3D focusable for keyboard events
        scene3D.setFocusTraversable(true);
    }

    private void showCharacteristicTypesVisualization() {
        characteristicTypesGroup.getChildren().clear();

        // Create 6 separate tetrahedra showing the characteristic types
        double spacing = 350000; // Space them out in natural coordinates
        double size = 200000;    // Size of each tetrahedron
        double baseX = 100000;   // Start position
        double baseY = 100000;

        Color[] colors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN };

        for (int i = 0; i < 6; i++) {
            double x = baseX + (i % 3) * spacing;
            double y = baseY + (i < 3 ? 0 : 1) * spacing;

            // Create a standard tetrahedron for each type
            Point3f v0 = new Point3f((float) x, (float) y, 0);
            Point3f v1 = new Point3f((float) (x + size), (float) y, 0);
            Point3f v2 = new Point3f((float) (x + size / 2), (float) (y + size * 0.866), 0);
            Point3f v3 = new Point3f((float) (x + size / 2), (float) (y + size * 0.289), (float) (size * 0.816));

            MeshView tet = createTetrahedronMesh(v0, v1, v2, v3, colors[i].deriveColor(0, 1, 1, 0.7));
            characteristicTypesGroup.getChildren().add(tet);
        }
    }

    private void showCubeDecompositionVisualization() {
        cubeDecompositionGroup.getChildren().clear();

        // Create wireframe cube and 6 tetrahedra in natural coordinates
        double cubeSize = 524288; // 2^19 - half the root tet size
        double offset = 262144;   // 2^18 - offset to position in positive quadrant

        // Add wireframe cube
        Group wireframeCube = createWireframeCube(offset, cubeSize);
        cubeDecompositionGroup.getChildren().add(wireframeCube);

        // Cube vertices
        Point3f[] cubeVertices = new Point3f[8];
        cubeVertices[0] = new Point3f((float) offset, (float) offset,
                                      (float) offset);                    // c0 = (0,0,0)
        cubeVertices[1] = new Point3f((float) (offset + cubeSize), (float) offset,
                                      (float) offset);           // c1 = (1,0,0)
        cubeVertices[2] = new Point3f((float) offset, (float) (offset + cubeSize),
                                      (float) offset);           // c2 = (0,1,0)
        cubeVertices[3] = new Point3f((float) (offset + cubeSize), (float) (offset + cubeSize),
                                      (float) offset);  // c3 = (1,1,0)
        cubeVertices[4] = new Point3f((float) offset, (float) offset,
                                      (float) (offset + cubeSize));           // c4 = (0,0,1)
        cubeVertices[5] = new Point3f((float) (offset + cubeSize), (float) offset,
                                      (float) (offset + cubeSize));  // c5 = (1,0,1)
        cubeVertices[6] = new Point3f((float) offset, (float) (offset + cubeSize),
                                      (float) (offset + cubeSize));  // c6 = (0,1,1)
        cubeVertices[7] = new Point3f((float) (offset + cubeSize), (float) (offset + cubeSize),
                                      (float) (offset + cubeSize)); // c7 = (1,1,1)

        // Create the 6 characteristic tetrahedra
        // Using correct vertex ordering for positive volume tetrahedra
        int[][] tetIndices = { { 0, 1, 3, 7 },  // S0 (RED)
                               { 0, 5, 1, 7 },  // S1 (GREEN) - fixed winding order
                               { 0, 3, 2, 7 },  // S2 (BLUE) - fixed winding order
                               { 0, 2, 6, 7 },  // S3 (YELLOW)
                               { 0, 4, 5, 7 },  // S4 (MAGENTA)
                               { 0, 6, 4, 7 }   // S5 (CYAN) - fixed winding order
        };

        Color[] colors = { Color.RED.deriveColor(0, 1, 1, 0.3), Color.GREEN.deriveColor(0, 1, 1, 0.3),
                           Color.BLUE.deriveColor(0, 1, 1, 0.3), Color.YELLOW.deriveColor(0, 1, 1, 0.3),
                           Color.MAGENTA.deriveColor(0, 1, 1, 0.3), Color.CYAN.deriveColor(0, 1, 1, 0.3) };

        for (int i = 0; i < 6; i++) {
            MeshView tet = createTetrahedronMesh(cubeVertices[tetIndices[i][0]], cubeVertices[tetIndices[i][1]],
                                                 cubeVertices[tetIndices[i][2]], cubeVertices[tetIndices[i][3]],
                                                 colors[i]);
            cubeDecompositionGroup.getChildren().add(tet);
        }
    }

    private void showTransformBasedVisualization() {
        // Initialize transform-based visualization if needed
        if (transformBasedViz == null) {
            transformBasedViz = new TransformBasedTetreeVisualization<>();
        }

        // Get the scene root
        Group root3D = visualization.getSceneRoot();

        // Hide only the tetrahedral meshes, not axes or other elements
        root3D.getChildren().forEach(child -> {
            if (child instanceof Group && child.getUserData() instanceof TetreeKey) {
                // This is a tet group
                child.setVisible(false);
            } else if (child instanceof MeshView) {
                // Individual mesh views
                child.setVisible(false);
            }
            // Keep axes, lights, and special groups visible
        });

        // Clear and populate transform-based visualization
        transformBasedViz.clear();
        transformBasedViz.demonstrateUsage(tetree);

        // Add transform-based visualization to the main root
        Group transformRoot = transformBasedViz.getSceneRoot();

        // The transform root should be added to the already-scaled scene root
        if (!root3D.getChildren().contains(transformRoot)) {
            root3D.getChildren().add(transformRoot);
        }

        // Make it visible
        transformRoot.setVisible(true);

        System.out.println("DEBUG: Transform-based visualization active");
        System.out.println("  Transform root children: " + transformRoot.getChildren().size());
        System.out.println("  Root3D children: " + root3D.getChildren().size());
        System.out.println("  Root3D transforms: " + root3D.getTransforms());
    }

    private void verifyEntityContainment() {
        int totalEntities = 0;
        int entitiesInVisibleNodes = 0;
        int entitiesWithoutVisibleContainer = 0;

        // Get current visibility settings
        int minLevel = visualization.minLevelProperty().get();
        int maxLevel = visualization.maxLevelProperty().get();
        boolean showEmpty = visualization.showEmptyNodesProperty().get();

        System.out.println("\n=== Entity Containment Verification ===");
        System.out.println("Visibility settings: levels " + minLevel + "-" + maxLevel + ", showEmpty=" + showEmpty);

        // Track entities outside visible range
        Map<Integer, Integer> entitiesByLevel = new HashMap<>();

        // Check all entities by iterating through nodes
        for (var node : tetree.nodes().toList()) {
            for (var entityId : node.entityIds()) {
                var position = tetree.getEntityPosition(entityId);
                if (position == null) {
                    continue;
                }
                totalEntities++;

                // Find which node contains this entity
                boolean foundInVisibleNode = false;
                for (var nodeCheck : tetree.nodes().toList()) {
                    if (nodeCheck.entityIds().contains(entityId)) {
                        var key = nodeCheck.sfcIndex();
                        int level = visualization.getLevelForKey(key);
                        entitiesByLevel.merge(level, 1, Integer::sum);

                        boolean isVisible = level >= minLevel && level <= maxLevel && (showEmpty
                                                                                       || !nodeCheck.entityIds()
                                                                                                    .isEmpty());

                        if (isVisible) {
                            entitiesInVisibleNodes++;
                            foundInVisibleNode = true;
                        } else {
                            // Debug: show why not visible
                            if (level < minLevel || level > maxLevel) {
                                System.out.printf(
                                "Entity %s at (%.0f,%.0f,%.0f) in node at level %d (outside visible range)%n", entityId,
                                position.x, position.y, position.z, level);
                            } else if (!showEmpty && nodeCheck.entityIds().size() == 1) {
                                System.out.printf("Entity %s at (%.0f,%.0f,%.0f) in 'empty' node (only 1 entity)%n",
                                                  entityId, position.x, position.y, position.z);
                            }
                        }
                        break;
                    }
                }

                if (!foundInVisibleNode) {
                    entitiesWithoutVisibleContainer++;
                    System.out.printf("WARNING: Entity %s at (%.0f,%.0f,%.0f) has no visible container!%n", entityId,
                                      position.x, position.y, position.z);

                    // Try to find enclosing node to understand why
                    try {
                        var enclosingNode = tetree.enclosing(
                        new javax.vecmath.Point3i((int) position.x, (int) position.y, (int) position.z), (byte) 10);
                        if (enclosingNode != null) {
                            System.out.printf("  -> Found enclosing node at level 10%n");
                        } else {
                            System.out.printf("  -> No enclosing node found at level 10%n");
                        }
                    } catch (Exception e) {
                        System.out.printf("  -> Failed to find enclosing node: %s%n", e.getMessage());
                    }
                }
            }
        }

        System.out.println("\nEntity distribution by level:");
        entitiesByLevel.forEach((level, count) -> System.out.printf("  Level %d: %d entities%s%n", level, count,
                                                                    (level < minLevel || level > maxLevel)
                                                                    ? " (not visible)" : ""));

        System.out.printf("\nSummary: %d total entities, %d in visible nodes, %d without visible container%n",
                          totalEntities, entitiesInVisibleNodes, entitiesWithoutVisibleContainer);
    }

    /**
     * Launcher class for IDEs that have issues with JavaFX.
     */
    public static class Launcher {
        public static void main(String[] args) {
            TetreeVisualizationDemo.main(args);
        }
    }
}

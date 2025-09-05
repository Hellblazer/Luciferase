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

import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.portal.CameraView;
import com.hellblazer.luciferase.portal.mesh.explorer.grid.AdaptiveGrid;
import com.hellblazer.luciferase.portal.mesh.spatial.ScaledTetreeView;
import javafx.application.Application;
import javafx.scene.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.stage.Stage;

import javax.vecmath.Point3f;

/**
 * TetreeInspector with CameraView and control panel.
 * Provides a complete UI for exploring tetrahedral spatial index structures.
 *
 * @author hal.hildebrand
 */
public class TetreeInspector extends Application {
    
    private Tetree<LongEntityID, String> tetree;
    private ScaledTetreeView tetreeView;
    private CameraView cameraView;
    private InspectorControlPanel controlPanel;
    private Group worldGroup;
    private Group axisGroup;
    private Group gridGroup;
    private Group visualizationGroup;
    private AdaptiveGrid adaptiveGrid;
    private ScalingStrategy scalingStrategy;
    private int currentLevel = 10;
    
    @Override
    public void start(Stage primaryStage) {
        // Create the 3D content
        BorderPane root = new BorderPane();
        
        // Create the 3D world
        worldGroup = new Group();
        visualizationGroup = new Group();
        axisGroup = new Group();
        gridGroup = new Group();
        
        // Build the scene content
        buildAxes();
        buildVisualization();
        
        // Add groups to world
        worldGroup.getChildren().addAll(gridGroup, axisGroup, visualizationGroup);
        
        // Add ambient light to world group so it affects all elements
        AmbientLight ambientLight = new AmbientLight(Color.WHITE);
        worldGroup.getChildren().add(ambientLight);
        
        // Create SubScene for 3D content
        SubScene subScene = new SubScene(worldGroup, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.LIGHTGRAY);
        
        // Create and configure CameraView
        cameraView = new CameraView(subScene);
        cameraView.setFirstPersonNavigationEabled(true);
        cameraView.startViewing();
        
        // Bind the camera view size to the window
        cameraView.fitWidthProperty().bind(root.widthProperty().subtract(350)); // Account for control panel
        cameraView.fitHeightProperty().bind(root.heightProperty());
        
        // Create control panel
        controlPanel = new InspectorControlPanel(
            cameraView,
            () -> cameraView.resetCamera(),
            () -> {
                // Toggle axes based on current checkbox state
                boolean shouldBeVisible = controlPanel.getShowAxesState();
                axisGroup.setVisible(shouldBeVisible);
            },
            () -> {
                // Toggle grid based on current checkbox state
                boolean shouldBeVisible = controlPanel.getShowGridState();
                gridGroup.setVisible(shouldBeVisible);
            },
            () -> visualizationGroup.setVisible(!visualizationGroup.isVisible()),
            this::updateGridLevel
        );
        
        // Sync initial checkbox state with actual visibility
        controlPanel.setShowAxes(true);  // Axes start visible
        controlPanel.setShowGrid(true);  // Grid starts visible
        controlPanel.setShowVisualization(true);  // Visualization starts visible
        
        // Layout
        root.setCenter(cameraView);
        root.setRight(controlPanel);
        
        // Create main scene
        Scene scene = new Scene(root, 1200, 800);
        
        // Configure stage
        primaryStage.setTitle("Tetree Inspector - Scaled Visualization");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        printInstructions();
    }
    
    private void buildAxes() {
        // Initialize scaling strategy and adaptive grid
        scalingStrategy = new ScalingStrategy();
        adaptiveGrid = new AdaptiveGrid(scalingStrategy);
        
        // Create grid materials
        PhongMaterial xMaterial = new PhongMaterial(Color.RED.darker());
        PhongMaterial yMaterial = new PhongMaterial(Color.GREEN.darker());
        PhongMaterial zMaterial = new PhongMaterial(Color.BLUE.darker());
        
        // Build grid for default level (10) - pass null for viewFrustum initially
        Group grid = adaptiveGrid.constructForLevel(10, xMaterial, yMaterial, zMaterial, null);
        gridGroup.getChildren().add(grid);
        
        // Add axis indicators
        addAxisIndicatorsToGroup();
        
        System.out.println("Grid group has " + gridGroup.getChildren().size() + " children");
        System.out.println("Axis group has " + axisGroup.getChildren().size() + " children");
        
        gridGroup.setVisible(true); // Start with grid visible
        axisGroup.setVisible(true); // Start with axes visible
    }
    
    private void buildVisualization() {
        // Create and populate a Tetree
        createSampleTetree();
        
        // Create TetreeView with ScaledCellViews
        setupTetreeView();
        
        // Update the view with the tetree data
        tetreeView.updateFromTetree(tetree);
        
        // Add the view to our group
        visualizationGroup.getChildren().add(tetreeView);
        
        // Add reference markers
        addReferenceMarkers();
    }
    
    private void createSampleTetree() {
        tetree = new Tetree<>(new SequentialLongIDGenerator());
        
        // Add entities at different levels to demonstrate scaling
        // Level 10 - medium detail
        addEntitiesAtLevel(10);
        
        // Level 15 - finer detail
        addEntitiesAtLevel(15);
        
        // Level 5 - coarser detail
        addEntitiesAtLevel(5);
        
        System.out.println("Created Tetree with " + tetree.entityCount() + " entities");
        System.out.println("Node count: " + tetree.nodeCount());
    }
    
    private void addEntitiesAtLevel(int level) {
        // Calculate cell size for proper alignment
        int cellSize = 1 << (21 - level);
        
        // Add a few entities at this level
        for (int i = 0; i < 3; i++) {
            int x = cellSize * (i + 1);
            int y = cellSize * (i + 2);
            int z = cellSize * (i + 1);
            
            Point3f position = new Point3f(x, y, z);
            String content = String.format("Entity L%d-%d", level, i);
            
            tetree.insert(position, (byte) level, content);
            System.out.println(String.format("Added entity at (%d, %d, %d) level %d", x, y, z, level));
        }
    }
    
    private void setupTetreeView() {
        // Configure materials
        Material occupiedMeshMaterial = new PhongMaterial(Color.RED.deriveColor(0, 1, 1, 0.6));
        Material occupiedWireframeMaterial = new PhongMaterial(Color.RED);
        Material parentWireframeMaterial = new PhongMaterial(Color.BLACK.deriveColor(0, 1, 1, 0.3));
        
        // Create ScaledTetreeView which uses ScaledCellViews internally
        tetreeView = new ScaledTetreeView(true, occupiedMeshMaterial, occupiedWireframeMaterial, parentWireframeMaterial);
    }
    
    private void addReferenceMarkers() {
        // Add small spheres at key positions for reference
        PhongMaterial whiteMaterial = new PhongMaterial(Color.WHITE);
        
        // Origin marker
        javafx.scene.shape.Sphere origin = new javafx.scene.shape.Sphere(5);
        origin.setMaterial(whiteMaterial);
        visualizationGroup.getChildren().add(origin);
        
        // Axis indicators are now added to the axisGroup in buildAxes()
    }
    
    private void printInstructions() {
        System.out.println("\n=== Tetree Inspector ===");
        System.out.println("Visualizing tetrahedral spatial index with proper scaling");
        System.out.println();
        System.out.println("Legend:");
        System.out.println("- Red translucent meshes: Occupied tetrahedra (containing entities)");
        System.out.println("- Black wireframes: Parent nodes in the hierarchy");
        System.out.println("- White sphere: Origin (0,0,0)");
        System.out.println("- Colored axes: X (red), Y (green), Z (blue)");
        System.out.println();
        System.out.println("Use the control panel on the right to adjust camera settings and display options.");
        System.out.println();
        System.out.println("Note: Coordinates are automatically scaled from spatial index range (0-2^21)");
        System.out.println("to JavaFX-friendly coordinates for proper visualization.");
    }
    
    /**
     * Update the grid to match the specified level.
     */
    private void updateGridLevel(int level) {
        // Clear current grid
        gridGroup.getChildren().clear();
        
        // Create grid materials
        PhongMaterial xMaterial = new PhongMaterial(Color.RED.darker());
        PhongMaterial yMaterial = new PhongMaterial(Color.GREEN.darker());
        PhongMaterial zMaterial = new PhongMaterial(Color.BLUE.darker());
        
        // Build new grid for the specified level
        Group grid = adaptiveGrid.constructForLevel(level, xMaterial, yMaterial, zMaterial, null);
        gridGroup.getChildren().add(grid);
    }
    
    /**
     * Add axis indicators to the axis group.
     */
    private void addAxisIndicatorsToGroup() {
        // Use bright, self-illuminated materials
        PhongMaterial redMaterial = new PhongMaterial(Color.RED.brighter());
        redMaterial.setSpecularColor(Color.WHITE);
        redMaterial.setSpecularPower(5);
        
        PhongMaterial greenMaterial = new PhongMaterial(Color.LIME);
        greenMaterial.setSpecularColor(Color.WHITE);
        greenMaterial.setSpecularPower(5);
        
        PhongMaterial blueMaterial = new PhongMaterial(Color.CYAN);
        blueMaterial.setSpecularColor(Color.WHITE);
        blueMaterial.setSpecularPower(5);
        
        // Create axis lines with reasonable size
        double axisLength = 200;  // Reasonable length for reference
        double axisThickness = 2; // Thin lines for minimal visual interference
        
        // X axis indicator (red) - extends along X
        javafx.scene.shape.Box xAxis = new javafx.scene.shape.Box(axisLength, axisThickness, axisThickness);
        xAxis.setMaterial(redMaterial);
        axisGroup.getChildren().add(xAxis);
        // Debug output removed - axes are now at reasonable sizes
        
        // Y axis indicator (green) - extends along Y
        javafx.scene.shape.Box yAxis = new javafx.scene.shape.Box(axisThickness, axisLength, axisThickness);
        yAxis.setMaterial(greenMaterial);
        axisGroup.getChildren().add(yAxis);
        
        // Z axis indicator (blue) - extends along Z
        javafx.scene.shape.Box zAxis = new javafx.scene.shape.Box(axisThickness, axisThickness, axisLength);
        zAxis.setMaterial(blueMaterial);
        axisGroup.getChildren().add(zAxis);
        
        // Add small arrows/cones at the positive ends
        addAxisArrows(axisLength / 2);
    }
    
    /**
     * Add arrow indicators at the ends of axes.
     */
    private void addAxisArrows(double distance) {
        PhongMaterial redMaterial = new PhongMaterial(Color.RED);
        PhongMaterial greenMaterial = new PhongMaterial(Color.GREEN);
        PhongMaterial blueMaterial = new PhongMaterial(Color.BLUE);
        
        // X axis arrow (pointing in +X direction)
        javafx.scene.shape.Cylinder xArrow = new javafx.scene.shape.Cylinder(3, 6);
        xArrow.setMaterial(redMaterial);
        xArrow.setTranslateX(distance);
        xArrow.setRotationAxis(javafx.scene.transform.Rotate.Z_AXIS);
        xArrow.setRotate(90);
        axisGroup.getChildren().add(xArrow);
        
        // Y axis arrow (pointing in +Y direction)
        javafx.scene.shape.Cylinder yArrow = new javafx.scene.shape.Cylinder(3, 6);
        yArrow.setMaterial(greenMaterial);
        yArrow.setTranslateY(distance);
        axisGroup.getChildren().add(yArrow);
        
        // Z axis arrow (pointing in +Z direction)
        javafx.scene.shape.Cylinder zArrow = new javafx.scene.shape.Cylinder(3, 6);
        zArrow.setMaterial(blueMaterial);
        zArrow.setTranslateZ(distance);
        zArrow.setRotationAxis(javafx.scene.transform.Rotate.X_AXIS);
        zArrow.setRotate(90);
        axisGroup.getChildren().add(zArrow);
    }
    
    /**
     * Launcher class for proper JavaFX application startup.
     */
    public static class Launcher {
        public static void main(String[] args) {
            Application.launch(TetreeInspector.class, args);
        }
    }
}

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
package com.hellblazer.luciferase.portal.esvo;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.CameraView;
import com.hellblazer.luciferase.portal.esvo.ProceduralVoxelGenerator;
import com.hellblazer.luciferase.portal.esvo.bridge.ESVOBridge;
import com.hellblazer.luciferase.portal.esvo.renderer.OctreeRenderer;
import com.hellblazer.luciferase.portal.esvo.renderer.VoxelRenderer;
import com.hellblazer.luciferase.portal.esvo.ui.OctreeControlPanel;
import com.hellblazer.luciferase.portal.esvo.ui.OctreeStructureDiagram;
import com.hellblazer.luciferase.portal.esvo.visualization.RayCastVisualizer;
import com.hellblazer.luciferase.portal.mesh.octree.OctreeNodeMeshRenderer;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal.DeepTraversalResult;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.stage.Stage;
import javafx.scene.control.SplitPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ESVO Octree Inspector - Interactive JavaFX application for exploring and visualizing
 * ESVO octree structures with procedural geometry generation and real-time rendering.
 * 
 * This application provides:
 * - Interactive 3D camera controls via CameraView
 * - Procedural voxel geometry generation (sphere, cube, torus, fractals)
 * - ESVO octree building and visualization
 * - Ray casting visualization and traversal debugging
 * - Performance metrics and statistics overlay
 * 
 * Architecture:
 * - Uses CameraView for camera management (first-person navigation)
 * - BorderPane layout: center for 3D content, right for control panel
 * - Separate scene graph groups for different visualization layers
 * - Background thread processing for octree building
 * 
 * @author hal.hildebrand
 */
public class OctreeInspectorApp extends Application {
    
    private static final Logger log = LoggerFactory.getLogger(OctreeInspectorApp.class);
    
    // UI Components
    private CameraView cameraView;
    private OctreeControlPanel controlPanel;
    private OctreeStructureDiagram structureDiagram;
    private SplitPane mainSplitPane;
    
    // 3D Scene Groups
    private Group worldGroup;
    private Group axisGroup;
    private Group gridGroup;
    private Group octreeGroup;
    private Group rayGroup;
    private Group voxelGroup;
    
    // ESVO Components
    private ESVOBridge esvoBridge;
    private OctreeRenderer octreeRenderer;
    private VoxelRenderer voxelRenderer;
    private ProceduralVoxelGenerator voxelGenerator;
    private ESVOOctreeData currentOctree;
    private RayCastVisualizer rayCastVisualizer;
    
    // Background executor for octree building
    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OctreeBuilder");
        t.setDaemon(true);
        return t;
    });
    
    // Current state
    private int currentLevel = 10;
    
    @Override
    public void start(Stage primaryStage) {
        // Initialize ESVO components
        esvoBridge = new ESVOBridge();
        voxelGenerator = new ProceduralVoxelGenerator();
        octreeRenderer = new OctreeRenderer(currentLevel, 
                                           OctreeNodeMeshRenderer.Strategy.BATCHED,
                                           OctreeRenderer.ColorScheme.DEPTH_GRADIENT,
                                           true);
        voxelRenderer = VoxelRenderer.builder()
                                     .voxelSize(1.5)
                                     .materialScheme(VoxelRenderer.MaterialScheme.POSITION_GRADIENT)
                                     .renderMode(VoxelRenderer.RenderMode.FILLED)
                                     .build();
        rayCastVisualizer = new RayCastVisualizer();
        
        log.info("ESVO components initialized");
        
        // Create the main layout
        BorderPane root = new BorderPane();
        
        // Create the 3D world with organized scene groups
        worldGroup = new Group();
        axisGroup = new Group();
        gridGroup = new Group();
        octreeGroup = new Group();
        rayGroup = new Group();
        voxelGroup = new Group();
        
        // Build initial scene content
        buildAxes();
        buildGrid();
        
        // Add all groups to world (order matters for rendering)
        worldGroup.getChildren().addAll(gridGroup, axisGroup, voxelGroup, octreeGroup, rayGroup);
        
        // Add ambient lighting to illuminate all elements
        AmbientLight ambientLight = new AmbientLight(Color.WHITE);
        worldGroup.getChildren().add(ambientLight);
        
        // Create SubScene for 3D content with anti-aliasing
        SubScene subScene = new SubScene(worldGroup, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.LIGHTGRAY);
        
        // Create and configure CameraView with first-person navigation
        cameraView = new CameraView(subScene);
        cameraView.setFirstPersonNavigationEabled(true);
        cameraView.startViewing();
        
        // Create octree structure diagram
        structureDiagram = new OctreeStructureDiagram();
        
        // Create split pane for 3D view and structure diagram
        mainSplitPane = new SplitPane();
        mainSplitPane.getItems().addAll(cameraView, structureDiagram);
        mainSplitPane.setDividerPositions(0.7); // 70% for 3D view, 30% for diagram
        
        // Bind sizes
        cameraView.fitWidthProperty().bind(mainSplitPane.widthProperty().multiply(0.7));
        cameraView.fitHeightProperty().bind(root.heightProperty());
        
        // Create control panel with event handlers
        controlPanel = new OctreeControlPanel(
            cameraView,
            this::handleResetCamera,
            this::handleToggleAxes,
            this::handleToggleGrid,
            this::handleToggleOctree,
            this::handleToggleVoxels,
            this::handleToggleRays,
            this::handleLevelChange,
            this::handleShapeChange,
            this::handleRenderModeChange,
            this::handleMaterialSchemeChange,
            this::handleCameraPreset,
            this::handleLodChanged
        );
        
        // Set initial visibility states
        controlPanel.setShowAxes(true);
        controlPanel.setShowGrid(true);
        controlPanel.setShowOctree(false);  // Initially empty
        controlPanel.setShowVoxels(false);  // Initially empty
        controlPanel.setShowRays(false);
        
        // Layout: split pane in center, controls on right
        root.setCenter(mainSplitPane);
        root.setRight(controlPanel);
        
        // Create main scene
        Scene scene = new Scene(root, 1200, 800);
        
        // Add mouse click handler for ray casting
        subScene.setOnMouseClicked(this::handleMouseClick);
        
        // Configure and show stage
        primaryStage.setTitle("ESVO Octree Inspector");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // Print usage instructions
        printInstructions();
        
        // Generate initial demo octree
        generateDemoOctree();
    }
    
    @Override
    public void stop() throws Exception {
        log.info("Shutting down OctreeInspectorApp");
        buildExecutor.shutdown();
        super.stop();
    }
    
    /**
     * Build coordinate axes for spatial reference.
     */
    private void buildAxes() {
        // Create bright, visible materials for axes
        PhongMaterial redMaterial = new PhongMaterial(Color.RED.brighter());
        redMaterial.setSpecularColor(Color.WHITE);
        redMaterial.setSpecularPower(5);
        
        PhongMaterial greenMaterial = new PhongMaterial(Color.LIME);
        greenMaterial.setSpecularColor(Color.WHITE);
        greenMaterial.setSpecularPower(5);
        
        PhongMaterial blueMaterial = new PhongMaterial(Color.CYAN);
        blueMaterial.setSpecularColor(Color.WHITE);
        blueMaterial.setSpecularPower(5);
        
        // Create axis lines
        double axisLength = 200;
        double axisThickness = 2;
        
        // X axis (red) - extends along X
        var xAxis = new javafx.scene.shape.Box(axisLength, axisThickness, axisThickness);
        xAxis.setMaterial(redMaterial);
        axisGroup.getChildren().add(xAxis);
        
        // Y axis (green) - extends along Y
        var yAxis = new javafx.scene.shape.Box(axisThickness, axisLength, axisThickness);
        yAxis.setMaterial(greenMaterial);
        axisGroup.getChildren().add(yAxis);
        
        // Z axis (blue) - extends along Z
        var zAxis = new javafx.scene.shape.Box(axisThickness, axisThickness, axisLength);
        zAxis.setMaterial(blueMaterial);
        axisGroup.getChildren().add(zAxis);
        
        // Add arrow indicators at positive ends
        addAxisArrows(axisLength / 2);
        
        axisGroup.setVisible(true);
    }
    
    /**
     * Add arrow indicators at the ends of axes.
     */
    private void addAxisArrows(double distance) {
        PhongMaterial redMaterial = new PhongMaterial(Color.RED);
        PhongMaterial greenMaterial = new PhongMaterial(Color.GREEN);
        PhongMaterial blueMaterial = new PhongMaterial(Color.BLUE);
        
        // X axis arrow (pointing in +X direction)
        var xArrow = new javafx.scene.shape.Cylinder(3, 6);
        xArrow.setMaterial(redMaterial);
        xArrow.setTranslateX(distance);
        xArrow.setRotationAxis(javafx.scene.transform.Rotate.Z_AXIS);
        xArrow.setRotate(90);
        axisGroup.getChildren().add(xArrow);
        
        // Y axis arrow (pointing in +Y direction)
        var yArrow = new javafx.scene.shape.Cylinder(3, 6);
        yArrow.setMaterial(greenMaterial);
        yArrow.setTranslateY(distance);
        axisGroup.getChildren().add(yArrow);
        
        // Z axis arrow (pointing in +Z direction)
        var zArrow = new javafx.scene.shape.Cylinder(3, 6);
        zArrow.setMaterial(blueMaterial);
        zArrow.setTranslateZ(distance);
        zArrow.setRotationAxis(javafx.scene.transform.Rotate.X_AXIS);
        zArrow.setRotate(90);
        axisGroup.getChildren().add(zArrow);
    }
    
    /**
     * Build reference grid (placeholder for adaptive grid).
     */
    private void buildGrid() {
        // TODO: Integrate AdaptiveGrid in Phase 2
        // For now, create a simple reference grid
        PhongMaterial gridMaterial = new PhongMaterial(Color.GRAY.deriveColor(0, 1, 1, 0.3));
        
        double gridSize = 200;
        int gridLines = 10;
        double spacing = gridSize / gridLines;
        
        for (int i = -gridLines / 2; i <= gridLines / 2; i++) {
            double pos = i * spacing;
            
            // Lines parallel to X axis
            var lineX = new javafx.scene.shape.Box(gridSize, 0.5, 0.5);
            lineX.setMaterial(gridMaterial);
            lineX.setTranslateZ(pos);
            gridGroup.getChildren().add(lineX);
            
            // Lines parallel to Z axis
            var lineZ = new javafx.scene.shape.Box(0.5, 0.5, gridSize);
            lineZ.setMaterial(gridMaterial);
            lineZ.setTranslateX(pos);
            gridGroup.getChildren().add(lineZ);
        }
        
        gridGroup.setVisible(true);
    }
    
    /**
     * Reset camera to default position and orientation.
     */
    private void handleResetCamera() {
        cameraView.resetCamera();
    }
    
    /**
     * Toggle visibility of coordinate axes.
     */
    private void handleToggleAxes() {
        boolean shouldBeVisible = controlPanel.getShowAxesState();
        axisGroup.setVisible(shouldBeVisible);
    }
    
    /**
     * Toggle visibility of reference grid.
     */
    private void handleToggleGrid() {
        boolean shouldBeVisible = controlPanel.getShowGridState();
        gridGroup.setVisible(shouldBeVisible);
    }
    
    /**
     * Toggle visibility of octree visualization.
     */
    private void handleToggleOctree() {
        boolean shouldBeVisible = controlPanel.getShowOctreeState();
        octreeGroup.setVisible(shouldBeVisible);
    }
    
    /**
     * Toggle visibility of voxel visualization.
     */
    private void handleToggleVoxels() {
        boolean shouldBeVisible = controlPanel.getShowVoxelsState();
        voxelGroup.setVisible(shouldBeVisible);
    }
    
    /**
     * Toggle visibility of ray casting visualization.
     */
    private void handleToggleRays() {
        boolean shouldBeVisible = controlPanel.getShowRaysState();
        rayGroup.setVisible(shouldBeVisible);
    }
    
    /**
     * Handle octree level change from control panel.
     */
    private void handleLevelChange(int newLevel) {
        if (newLevel == currentLevel) {
            return; // No change
        }
        
        log.info("Level changed from {} to {}", currentLevel, newLevel);
        currentLevel = newLevel;
        
        // Rebuild octree with new depth
        generateDemoOctree();
    }
    
    /**
     * Handle shape selection change from control panel.
     */
    private void handleShapeChange(ProceduralVoxelGenerator.Shape newShape) {
        log.info("Shape changed to: {}", ProceduralVoxelGenerator.getShapeName(newShape));
        // Rebuild octree with new shape
        generateDemoOctree();
    }
    
    /**
     * Handle render mode change from control panel.
     */
    private void handleRenderModeChange(VoxelRenderer.RenderMode newMode) {
        log.info("Render mode changed to: {}", newMode);
        
        // Recreate voxel renderer with new render mode
        voxelRenderer = VoxelRenderer.builder()
                                     .voxelSize(1.5)
                                     .materialScheme(controlPanel.getSelectedMaterialScheme())
                                     .renderMode(newMode)
                                     .build();
        
        // Re-render voxels if we have current data
        if (currentOctree != null) {
            generateDemoOctree();
        }
    }
    
    /**
     * Handle material scheme change from control panel.
     */
    private void handleMaterialSchemeChange(VoxelRenderer.MaterialScheme newScheme) {
        log.info("Material scheme changed to: {}", newScheme);
        
        // Recreate voxel renderer with new material scheme
        voxelRenderer = VoxelRenderer.builder()
                                     .voxelSize(1.5)
                                     .materialScheme(newScheme)
                                     .renderMode(controlPanel.getSelectedRenderMode())
                                     .build();
        
        // Re-render voxels if we have current data
        if (currentOctree != null) {
            generateDemoOctree();
        }
    }
    
    /**
     * Handle camera preset button click.
     * Note: This is a placeholder - camera presets will be implemented in a future phase.
     */
    private void handleCameraPreset() {
        log.info("Camera preset clicked - not yet implemented");
        // TODO: Implement camera preset positions in Phase 3.2+
    }
    
    /**
     * Handle LOD (Level of Detail) control changes from control panel.
     * Updates the octree visualization to show/hide specific levels based on LOD settings.
     */
    private void handleLodChanged() {
        if (currentOctree == null) {
            return; // No octree to update
        }
        
        log.info("LOD settings changed - updating octree visualization");
        
        // Get LOD settings from control panel
        int minLevel = controlPanel.getMinLevel();
        int maxLevel = controlPanel.getMaxLevel();
        boolean isolateLevel = controlPanel.isIsolateLevelEnabled();
        int isolatedLevel = controlPanel.getIsolatedLevel();
        boolean ghostMode = controlPanel.isGhostModeEnabled();
        
        // Clear existing octree visualization
        octreeGroup.getChildren().clear();
        
        // Re-create renderer with current depth
        octreeRenderer = new OctreeRenderer(currentLevel,
                                           OctreeNodeMeshRenderer.Strategy.BATCHED,
                                           OctreeRenderer.ColorScheme.DEPTH_GRADIENT,
                                           true);
        
        // Render with LOD settings
        Group renderedGroup;
        if (isolateLevel) {
            // Render only the isolated level
            log.info("Rendering isolated level: {}", isolatedLevel);
            renderedGroup = octreeRenderer.render(currentOctree, isolatedLevel, isolatedLevel);
        } else {
            // Render level range
            log.info("Rendering level range: {} to {}", minLevel, maxLevel);
            renderedGroup = octreeRenderer.render(currentOctree, minLevel, maxLevel);
        }
        
        octreeGroup.getChildren().add(renderedGroup);
        
        // Apply ghost mode if enabled (semi-transparent rendering)
        if (ghostMode) {
            renderedGroup.setOpacity(0.3);
        } else {
            renderedGroup.setOpacity(1.0);
        }
        
        log.info("LOD visualization updated");
    }
    
    /**
     * Generate and visualize a demo octree.
     * Uses the shape and resolution selected in the control panel.
     */
    private void generateDemoOctree() {
        // Save current camera state before rebuilding
        var savedCameraState = cameraView.saveCameraState();
        
        // Show progress indicator and update status
        controlPanel.showRebuildProgress();
        controlPanel.setRebuildStatus("Building...");
        
        // Get current shape and resolution from control panel
        var shape = controlPanel.getSelectedShape();
        int resolution = controlPanel.getResolution();
        
        log.info("Generating demo octree: shape={}, resolution={}, depth={}", 
                 ProceduralVoxelGenerator.getShapeName(shape), resolution, currentLevel);
        
        // Build octree in background thread
        CompletableFuture.supplyAsync(() -> {
            try {
                // Generate voxels for selected shape
                var voxels = voxelGenerator.generate(shape, resolution);
                log.info("Generated {} voxels", voxels.size());
                
                // Build ESVO octree
                var octree = esvoBridge.buildOctree(voxels, currentLevel);
                log.info("Built octree with {} nodes", octree.getNodeCount());
                
                // Return shape, resolution, voxels, and octree
                return new Object[] { shape, resolution, voxels, octree };
            } catch (Exception e) {
                log.error("Failed to build octree", e);
                return null;
            }
        }, buildExecutor)
        .thenAcceptAsync(result -> {
            if (result != null) {
                @SuppressWarnings("unchecked")
                var resultShape = (ProceduralVoxelGenerator.Shape) result[0];
                var resultResolution = (Integer) result[1];
                @SuppressWarnings("unchecked")
                var resultVoxels = (List<Point3i>) result[2];
                var resultOctree = (ESVOOctreeData) result[3];
                
                // Update visualization on JavaFX thread
                updateOctreeVisualization(resultOctree);
                updateVoxelVisualization(resultVoxels, resultResolution);
                
                // Restore camera state to maintain user's view
                cameraView.restoreCameraState(savedCameraState);
                
                // Hide progress indicator and update status
                controlPanel.hideRebuildProgress();
                controlPanel.setRebuildStatus("Ready");
            } else {
                // Handle build failure
                controlPanel.hideRebuildProgress();
                controlPanel.setRebuildStatus("Build Failed");
            }
        }, Platform::runLater);
    }
    
    /**
     * Update the octree visualization with new data.
     * Must be called on JavaFX Application Thread.
     */
    private void updateOctreeVisualization(ESVOOctreeData octree) {
        try {
            // Clear existing octree visualization
            octreeGroup.getChildren().clear();
            
            // Store current octree
            currentOctree = octree;
            
            // Re-create renderer with current depth
            octreeRenderer = new OctreeRenderer(currentLevel,
                                               OctreeNodeMeshRenderer.Strategy.BATCHED,
                                               OctreeRenderer.ColorScheme.DEPTH_GRADIENT,
                                               true);
            
            // Render all levels
            var renderedGroup = octreeRenderer.renderAllLevels(octree);
            octreeGroup.getChildren().add(renderedGroup);
            
            // Show octree by default
            octreeGroup.setVisible(true);
            controlPanel.setShowOctree(true);
            
            // Update structure diagram
            structureDiagram.setOctreeData(octree, currentLevel);
            
            log.info("Octree visualization updated: {} nodes rendered", octree.getNodeCount());
            
        } catch (Exception e) {
            log.error("Failed to update octree visualization", e);
        }
    }
    
    /**
     * Update the voxel visualization with new voxel data.
     * Must be called on JavaFX Application Thread.
     * 
     * @param voxels the voxel positions to render
     * @param resolution the resolution used to generate the voxels
     */
    private void updateVoxelVisualization(List<Point3i> voxels, int resolution) {
        try {
            // Clear existing voxel visualization
            voxelGroup.getChildren().clear();
            
            // Render voxels with actual resolution
            var renderedVoxels = voxelRenderer.render(voxels, resolution);
            voxelGroup.getChildren().add(renderedVoxels);
            
            // Show voxels by default
            voxelGroup.setVisible(true);
            controlPanel.setShowVoxels(true);
            
            log.info("Voxel visualization updated: {} voxels rendered at resolution {}", voxels.size(), resolution);
            
        } catch (Exception e) {
            log.error("Failed to update voxel visualization", e);
        }
    }
    
    /**
     * Handle mouse clicks for ray casting visualization.
     * - Interactive mode: any click casts a ray
     * - Non-interactive: Ctrl+Click casts a ray
     */
    private void handleMouseClick(MouseEvent event) {
        // Check if we should cast a ray based on interactive mode
        boolean isInteractiveMode = controlPanel.isRayCastingModeEnabled();
        boolean shouldCastRay = event.getButton() == MouseButton.PRIMARY && 
                               (isInteractiveMode || event.isControlDown());
        
        if (!shouldCastRay) {
            return;
        }
        
        // Check if octree is available
        if (currentOctree == null || !esvoBridge.hasOctree()) {
            log.warn("No octree available for ray casting");
            controlPanel.updateRayStatistics("No octree available.\nGenerate an octree first.");
            return;
        }
        
        // Get camera position and compute ray direction
        var camera = cameraView.getCamera();
        
        // Camera position in scene coordinates
        var cameraPos = new Vector3f(
            (float) camera.getTranslateX(),
            (float) camera.getTranslateY(),
            (float) camera.getTranslateZ()
        );
        
        // Normalize to [0,1] space (scene is 100x100x100, centered at origin)
        var normalizedOrigin = new Vector3f(
            (cameraPos.x + 50.0f) / 100.0f,
            (cameraPos.y + 50.0f) / 100.0f,
            (cameraPos.z + 50.0f) / 100.0f
        );
        
        // Get camera look direction from rotation transforms
        // For now, use simple forward direction (can be enhanced with actual transforms)
        var direction = new Vector3f(0.0f, 0.0f, 1.0f); // Forward direction
        
        log.info("Casting ray from camera position: {}", normalizedOrigin);
        
        try {
            // Cast ray through octree
            DeepTraversalResult result = esvoBridge.castRay(normalizedOrigin, direction);
            
            // Visualize the ray
            if (result != null) {
                visualizeRayCast(normalizedOrigin, direction, result);
                
                // Display statistics in UI
                String stats = formatRayStatistics(result, normalizedOrigin, direction);
                controlPanel.updateRayStatistics(stats);
                
                // Auto-enable ray visualization if it was off
                if (!rayGroup.isVisible()) {
                    rayGroup.setVisible(true);
                    controlPanel.setShowRays(true);
                }
            } else {
                log.warn("Ray casting returned null result");
                controlPanel.updateRayStatistics("Ray cast failed.\nNo result returned.");
            }
            
        } catch (Exception e) {
            log.error("Failed to cast ray", e);
        }
    }
    
    /**
     * Visualize a ray cast result in the scene.
     */
    private void visualizeRayCast(Vector3f origin, Vector3f direction, DeepTraversalResult result) {
        try {
            // Clear previous ray visualization
            rayGroup.getChildren().clear();
            
            // Create ray visualization
            float maxDistance = 2.0f; // Max ray length in normalized space
            Group rayVis = rayCastVisualizer.visualize(origin, direction, result, maxDistance);
            
            // Add to ray group
            rayGroup.getChildren().add(rayVis);
            
            log.info("Ray visualization added: hit={}, distance={:.3f}", 
                    result.hit, result.distance);
            
        } catch (Exception e) {
            log.error("Failed to visualize ray cast", e);
        }
    }
    
    /**
     * Format ray casting statistics for UI display.
     * 
     * @param result The ray traversal result
     * @param origin Ray origin point
     * @param direction Ray direction vector
     * @return Formatted statistics string
     */
    private String formatRayStatistics(DeepTraversalResult result, Vector3f origin, Vector3f direction) {
        StringBuilder stats = new StringBuilder();
        
        // Ray parameters
        stats.append("RAY CAST RESULTS\n");
        stats.append("================\n\n");
        stats.append(String.format("Origin:    (%.3f, %.3f, %.3f)\n", origin.x, origin.y, origin.z));
        stats.append(String.format("Direction: (%.3f, %.3f, %.3f)\n", direction.x, direction.y, direction.z));
        stats.append("\n");
        
        // Hit status
        stats.append(String.format("Hit:       %s\n", result.hit ? "YES" : "NO"));
        
        if (result.hit) {
            // Hit details
            stats.append(String.format("Distance:  %.3f\n", result.distance));
            
            if (result.hitPoint != null) {
                stats.append(String.format("Hit Point: (%.3f, %.3f, %.3f)\n", 
                    result.hitPoint.x, result.hitPoint.y, result.hitPoint.z));
            }
            
            if (result.normal != null) {
                stats.append(String.format("Normal:    (%.3f, %.3f, %.3f)\n", 
                    result.normal.x, result.normal.y, result.normal.z));
            }
        }
        
        stats.append("\n");
        
        // Traversal statistics
        stats.append("TRAVERSAL STATISTICS\n");
        stats.append("====================\n");
        stats.append(String.format("Traversal Depth: %d\n", result.traversalDepth));
        stats.append(String.format("Iterations:      %d\n", result.iterations));
        
        if (result.hit && result.leafNode >= 0) {
            stats.append(String.format("Leaf Node:       %d\n", result.leafNode));
        }
        
        return stats.toString();
    }
    
    /**
     * Print usage instructions to console.
     */
    private void printInstructions() {
        System.out.println("\n=== ESVO Octree Inspector ===");
        System.out.println("Interactive visualization tool for ESVO octree structures");
        System.out.println();
        System.out.println("Navigation:");
        System.out.println("- Mouse drag: Rotate camera");
        System.out.println("- Mouse wheel: Zoom in/out");
        System.out.println("- WASD keys: Move camera (when first-person mode enabled)");
        System.out.println();
        System.out.println("Ray Casting:");
        System.out.println("- Ctrl+Click: Cast ray from camera through scene");
        System.out.println("- Yellow line: Ray path");
        System.out.println("- Red sphere: Hit point");
        System.out.println("- Cyan line: Surface normal at hit");
        System.out.println();
        System.out.println("Control Panel (right side):");
        System.out.println("- Octree Parameters: Adjust depth and resolution");
        System.out.println("- Shape Selection: Choose procedural geometry");
        System.out.println("- Visualization: Toggle display elements");
        System.out.println("- Camera: Reset view and adjust settings");
        System.out.println();
        System.out.println("Legend:");
        System.out.println("- Red axis: X direction");
        System.out.println("- Green axis: Y direction");
        System.out.println("- Blue axis: Z direction");
        System.out.println("- Gray grid: XZ plane reference");
        System.out.println();
        System.out.println("Ready for octree visualization!");
    }
    
    /**
     * Launcher class for proper JavaFX application startup.
     * Use this pattern to avoid JavaFX initialization issues.
     */
    public static class Launcher {
        public static void main(String[] args) {
            Application.launch(OctreeInspectorApp.class, args);
        }
    }
}

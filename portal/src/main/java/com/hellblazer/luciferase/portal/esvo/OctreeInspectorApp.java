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

import com.hellblazer.luciferase.esvo.app.ESVOPerformanceMonitor;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.CameraView;
import com.hellblazer.luciferase.portal.esvo.ProceduralVoxelGenerator;
import com.hellblazer.luciferase.portal.esvo.bridge.ESVOBridge;
import com.hellblazer.luciferase.portal.esvo.renderer.OctreeRenderer;
import com.hellblazer.luciferase.portal.esvo.renderer.VoxelRenderer;
import com.hellblazer.luciferase.portal.esvo.visualization.RayCastVisualizer;
import com.hellblazer.luciferase.portal.mesh.octree.OctreeNodeMeshRenderer;
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal.DeepTraversalResult;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.stage.Stage;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.vecmath.Vector3f;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private BorderPane root;
    private CameraView cameraView;
    private TreeView<String> octreeTreeView;
    private TabPane rightTabPane;
    
    // Status bar labels
    private Label statusLabel;
    private Label nodesLabel;
    private Label fpsLabel;
    private Label memoryLabel;
    private Label levelLabel;
    
    // UI controls
    private TextArea rayStatsTextArea;
    private TextArea nodePropsTextArea;
    private Button screenshotBtn;
    private Button recordBtn;
    private Button resetViewBtn;
    private CheckBox showAxesCheck;
    private CheckBox showGridCheck;
    private CheckBox showOctreeCheck;
    private CheckBox showVoxelsCheck;
    private CheckBox showRaysCheck;
    private CheckBox firstPersonCheck;
    private CheckBox rayInteractiveCheck;
    private Spinner<Integer> depthSpinner;
    private Spinner<Integer> resolutionSpinner;
    private ComboBox<ProceduralVoxelGenerator.Shape> shapeComboBox;
    private ComboBox<VoxelRenderer.MaterialScheme> materialComboBox;
    private ToggleGroup renderModeGroup;
    private Slider minLevelSlider;
    private Slider maxLevelSlider;
    private Slider isolatedLevelSlider;
    private CheckBox isolateLevelCheck;
    private CheckBox ghostModeCheck;
    
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
    private ESVOPerformanceMonitor performanceMonitor;
    private AnimationTimer performanceUpdateTimer;
    
    // Background executor for octree building
    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OctreeBuilder");
        t.setDaemon(true);
        return t;
    });
    
    // Current state
    private int currentLevel = 10;
    
    // Screenshot/Recording state
    private boolean isRecording = false;
    private int frameCounter = 0;
    
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
        performanceMonitor = new ESVOPerformanceMonitor();
        
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
        
        // Create UI sections using helper methods
        ToolBar toolbar = createTopToolbar();
        VBox leftPanel = createLeftPanel();
        TabPane rightPanel = createRightTabPane();
        HBox statusBar = createStatusBar();
        
        // Assemble new layout
        root = new BorderPane();  // Store reference for screenshot/recording
        root.setTop(toolbar);
        root.setLeft(leftPanel);
        root.setCenter(cameraView);  // CameraView directly in center
        root.setRight(rightPanel);
        root.setBottom(statusBar);
        
        // Create main scene
        Scene scene = new Scene(root, 1200, 800);
        
        // Add keyboard shortcuts for feature toggles
        scene.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case X:
                    showAxesCheck.setSelected(!showAxesCheck.isSelected());
                    handleToggleAxes();
                    break;
                case G:
                    showGridCheck.setSelected(!showGridCheck.isSelected());
                    handleToggleGrid();
                    break;
                case O:
                    showOctreeCheck.setSelected(!showOctreeCheck.isSelected());
                    handleToggleOctree();
                    break;
                case V:
                    showVoxelsCheck.setSelected(!showVoxelsCheck.isSelected());
                    handleToggleVoxels();
                    break;
                case R:
                    handleResetCamera();
                    break;
                case S:
                    handleScreenshot();
                    break;
                default:
                    break;
            }
        });
        
        // Add mouse click handler for ray casting
        subScene.setOnMouseClicked(this::handleMouseClick);
        
        // Configure and show stage
        primaryStage.setTitle("ESVO Octree Inspector");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // Start performance monitoring
        startPerformanceMonitoring();
        
        // Print usage instructions
        printInstructions();
        
        // Generate initial demo octree
        generateDemoOctree();
    }
    
    @Override
    public void stop() throws Exception {
        log.info("Shutting down OctreeInspectorApp");
        if (performanceUpdateTimer != null) {
            performanceUpdateTimer.stop();
        }
        buildExecutor.shutdown();
        super.stop();
    }
    
    /**
     * Start performance monitoring with AnimationTimer.
     * Updates FPS and performance metrics at regular intervals.
     */
    private void startPerformanceMonitoring() {
        performanceUpdateTimer = new AnimationTimer() {
            private long lastUpdate = 0;
            private static final long UPDATE_INTERVAL = 500_000_000; // 500ms in nanoseconds
            
            @Override
            public void handle(long now) {
                // Record frame start for FPS tracking
                performanceMonitor.startFrame();
                
                // Capture frame if recording is enabled
                if (isRecording) {
                    captureFrame();
                }
                
                // Update performance overlay every 500ms
                if (now - lastUpdate >= UPDATE_INTERVAL) {
                    var summary = performanceMonitor.getPerformanceSummary();
                    // Update status bar labels
                    fpsLabel.setText(String.format("FPS: %.1f", summary.currentFPS()));
                    
                    // Calculate memory usage
                    var runtime = Runtime.getRuntime();
                    var usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
                    memoryLabel.setText(String.format("Memory: %.1f MB", usedMemoryMB));
                    
                    lastUpdate = now;
                }
            }
        };
        performanceUpdateTimer.start();
        
        log.info("Performance monitoring started");
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
        boolean shouldBeVisible = showAxesCheck.isSelected();
        axisGroup.setVisible(shouldBeVisible);
    }
    
    /**
     * Toggle visibility of reference grid.
     */
    private void handleToggleGrid() {
        boolean shouldBeVisible = showGridCheck.isSelected();
        gridGroup.setVisible(shouldBeVisible);
    }
    
    /**
     * Toggle visibility of octree visualization.
     */
    private void handleToggleOctree() {
        boolean shouldBeVisible = showOctreeCheck.isSelected();
        octreeGroup.setVisible(shouldBeVisible);
    }
    
    /**
     * Toggle visibility of voxel visualization.
     */
    private void handleToggleVoxels() {
        boolean shouldBeVisible = showVoxelsCheck.isSelected();
        voxelGroup.setVisible(shouldBeVisible);
    }
    
    /**
     * Toggle visibility of ray casting visualization.
     */
    private void handleToggleRays() {
        boolean shouldBeVisible = showRaysCheck.isSelected();
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
                                     .materialScheme(materialComboBox.getValue())
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
        
        // Get current render mode from toggle group
        VoxelRenderer.RenderMode currentMode = VoxelRenderer.RenderMode.FILLED; // default
        if (renderModeGroup.getSelectedToggle() != null) {
            RadioButton selected = (RadioButton) renderModeGroup.getSelectedToggle();
            String text = selected.getText();
            if ("Wireframe".equals(text)) {
                currentMode = VoxelRenderer.RenderMode.WIREFRAME;
            } else if ("Points".equals(text)) {
                currentMode = VoxelRenderer.RenderMode.POINTS;
            }
        }
        
        // Recreate voxel renderer with new material scheme
        voxelRenderer = VoxelRenderer.builder()
                                     .voxelSize(1.5)
                                     .materialScheme(newScheme)
                                     .renderMode(currentMode)
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
        
        // Get LOD settings from UI controls
        int minLevel = (int) minLevelSlider.getValue();
        int maxLevel = (int) maxLevelSlider.getValue();
        boolean isolateLevel = isolateLevelCheck.isSelected();
        int isolatedLevel = (int) isolatedLevelSlider.getValue();
        boolean ghostMode = ghostModeCheck.isSelected();
        
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
        
        // Update status bar
        statusLabel.setText("Status: Building...");
        
        // Get current shape and resolution from UI controls
        var shape = shapeComboBox.getValue();
        int resolution = resolutionSpinner.getValue();
        
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
                
                // Update status bar
                statusLabel.setText("Status: Ready");
                nodesLabel.setText("Nodes: " + resultOctree.getNodeCount());
            } else {
                // Handle build failure
                statusLabel.setText("Status: Build Failed");
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
            showOctreeCheck.setSelected(true);
            
            // Update octree tree view
            populateOctreeTree(octree);
            
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
            showVoxelsCheck.setSelected(true);
            
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
        boolean isInteractiveMode = rayInteractiveCheck.isSelected();
        boolean shouldCastRay = event.getButton() == MouseButton.PRIMARY && 
                               (isInteractiveMode || event.isControlDown());
        
        if (!shouldCastRay) {
            return;
        }
        
        // Check if octree is available
        if (currentOctree == null || !esvoBridge.hasOctree()) {
            log.warn("No octree available for ray casting");
            rayStatsTextArea.setText("No octree available.\nGenerate an octree first.");
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
                
                // Record traversal statistics for performance monitoring
                int voxelsHit = result.hit ? 1 : 0;
                performanceMonitor.recordTraversal(1, result.iterations, voxelsHit);
                
                // Display statistics in UI
                String stats = formatRayStatistics(result, normalizedOrigin, direction);
                rayStatsTextArea.setText(stats);
                
                // Auto-enable ray visualization if it was off
                if (!rayGroup.isVisible()) {
                    rayGroup.setVisible(true);
                    showRaysCheck.setSelected(true);
                }
            } else {
                log.warn("Ray casting returned null result");
                rayStatsTextArea.setText("Ray cast failed.\nNo result returned.");
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
     * Handle screenshot capture request.
     * Captures the current 3D view and saves it as a PNG file with metadata.
     */
    private void handleScreenshot() {
        log.info("Screenshot requested");
        
        try {
            // Create screenshots directory if it doesn't exist
            Path screenshotsDir = Paths.get("screenshots");
            if (!Files.exists(screenshotsDir)) {
                Files.createDirectories(screenshotsDir);
                log.info("Created screenshots directory: {}", screenshotsDir.toAbsolutePath());
            }
            
            // Generate timestamp-based filename
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            String timestamp = LocalDateTime.now().format(formatter);
            String filename = String.format("octree_screenshot_%s.png", timestamp);
            Path outputPath = screenshotsDir.resolve(filename);
            
            // Capture the entire root BorderPane
            WritableImage snapshot = root.snapshot(null, null);
            
            // Convert to BufferedImage for saving
            var bufferedImage = SwingFXUtils.fromFXImage(snapshot, null);
            
            // Save as PNG
            ImageIO.write(bufferedImage, "png", outputPath.toFile());
            
            // Log success with statistics
            String stats = String.format("Screenshot saved: %s (%.1f KB, %dx%d)",
                filename,
                outputPath.toFile().length() / 1024.0,
                (int) snapshot.getWidth(),
                (int) snapshot.getHeight());
            
            if (currentOctree != null) {
                stats += String.format(" [Octree: %d nodes, depth %d]",
                    currentOctree.getNodeCount(),
                    currentLevel);
            }
            
            log.info(stats);
            statusLabel.setText("Status: Screenshot saved - " + filename);
            
        } catch (IOException e) {
            log.error("Failed to save screenshot", e);
            statusLabel.setText("Status: Screenshot failed!");
        }
    }
    
    /**
     * Handle toggle recording request.
     * Toggles frame sequence recording on/off.
     */
    private void handleToggleRecording() {
        isRecording = !isRecording;
        
        if (isRecording) {
            // Start recording - reset frame counter
            frameCounter = 0;
            statusLabel.setText("Status: Recording started (0 frames)");
            log.info("Recording started - frames will be captured in recordings/ directory");
            
            // Create recordings directory if it doesn't exist
            try {
                Path recordingsDir = Paths.get("recordings");
                if (!Files.exists(recordingsDir)) {
                    Files.createDirectories(recordingsDir);
                    log.info("Created recordings directory: {}", recordingsDir.toAbsolutePath());
                }
            } catch (IOException e) {
                log.error("Failed to create recordings directory", e);
                isRecording = false; // Disable recording on failure
                statusLabel.setText("Status: Recording failed - can't create directory");
            }
        } else {
            // Stop recording
            log.info("Recording stopped - {} frames captured", frameCounter);
            statusLabel.setText(String.format("Status: Recording complete - %d frames", frameCounter));
        }
    }
    
    /**
     * Capture a single frame during recording.
     * Called from the animation timer when recording is active.
     * Saves frames as octree_frame_000001.png, octree_frame_000002.png, etc.
     */
    private void captureFrame() {
        try {
            // Increment frame counter
            frameCounter++;
            
            // Generate filename with zero-padded frame number
            String filename = String.format("octree_frame_%06d.png", frameCounter);
            Path outputPath = Paths.get("recordings").resolve(filename);
            
            // Capture the entire root BorderPane
            WritableImage snapshot = root.snapshot(null, null);
            
            // Convert to BufferedImage for saving
            var bufferedImage = SwingFXUtils.fromFXImage(snapshot, null);
            
            // Save as PNG
            ImageIO.write(bufferedImage, "png", outputPath.toFile());
            
            // Log progress (every 10 frames to avoid excessive logging)
            if (frameCounter % 10 == 0) {
                log.debug("Recording frame {} captured", frameCounter);
            }
            
        } catch (IOException e) {
            log.error("Failed to capture frame {}", frameCounter, e);
            // Don't stop recording on single frame failure - just log it
        }
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
     * Create the top toolbar with action buttons.
     */
    private ToolBar createTopToolbar() {
        ToolBar toolbar = new ToolBar();
        toolbar.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 5;");
        
        // Screenshot button
        screenshotBtn = new Button("📸 Screenshot");
        screenshotBtn.setTooltip(new Tooltip("Capture current view (S)"));
        screenshotBtn.setOnAction(e -> handleScreenshot());
        
        // Record button
        recordBtn = new Button("⏺ Record");
        recordBtn.setTooltip(new Tooltip("Toggle frame recording"));
        recordBtn.setOnAction(e -> handleToggleRecording());
        
        // Reset view button
        resetViewBtn = new Button("↺ Reset View");
        resetViewBtn.setTooltip(new Tooltip("Reset camera (R)"));
        resetViewBtn.setOnAction(e -> handleResetCamera());
        
        // Export button (placeholder)
        Button exportBtn = new Button("💾 Export");
        exportBtn.setTooltip(new Tooltip("Export octree data"));
        exportBtn.setOnAction(e -> log.info("Export not yet implemented"));
        
        toolbar.getItems().addAll(screenshotBtn, recordBtn, resetViewBtn, exportBtn);
        
        return toolbar;
    }
    
    /**
     * Create the left panel with octree hierarchy tree.
     */
    private VBox createLeftPanel() {
        VBox leftPanel = new VBox(5);
        leftPanel.setPrefWidth(200);
        leftPanel.setMaxWidth(200);
        leftPanel.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 5;");
        
        Label titleLabel = new Label("Octree Hierarchy");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        
        octreeTreeView = new TreeView<>();
        octreeTreeView.setStyle("-fx-background-color: #1e1e1e;");
        octreeTreeView.setRoot(new TreeItem<>("No octree loaded"));
        octreeTreeView.setShowRoot(true);
        
        // Handle tree selection
        octreeTreeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && nodePropsTextArea != null) {
                nodePropsTextArea.setText("Selected: " + newVal.getValue());
            }
        });
        
        VBox.setVgrow(octreeTreeView, Priority.ALWAYS);
        leftPanel.getChildren().addAll(titleLabel, octreeTreeView);
        
        return leftPanel;
    }
    
    /**
     * Create the right tab pane with View/Properties/Rendering/Analysis tabs.
     */
    private TabPane createRightTabPane() {
        rightTabPane = new TabPane();
        rightTabPane.setPrefWidth(350);
        rightTabPane.setMaxWidth(350);
        rightTabPane.setStyle("-fx-background-color: #2b2b2b;");
        
        // Create tabs
        Tab viewTab = createViewTab();
        Tab propsTab = createPropertiesTab();
        Tab renderTab = createRenderingTab();
        Tab analysisTab = createAnalysisTab();
        
        rightTabPane.getTabs().addAll(viewTab, propsTab, renderTab, analysisTab);
        rightTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        return rightTabPane;
    }
    
    /**
     * Create the View tab with camera and display controls.
     */
    private Tab createViewTab() {
        Tab tab = new Tab("View");
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #1e1e1e;");
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setStyle("-fx-background-color: #1e1e1e;");
        
        // Display options
        TitledPane displayPane = new TitledPane();
        displayPane.setText("Display Options");
        VBox displayContent = new VBox(5);
        
        showAxesCheck = new CheckBox("Show Axes (X)");
        showAxesCheck.setSelected(true);
        showAxesCheck.setStyle("-fx-text-fill: white;");
        showAxesCheck.setOnAction(e -> handleToggleAxes());
        
        showGridCheck = new CheckBox("Show Grid (G)");
        showGridCheck.setSelected(true);
        showGridCheck.setStyle("-fx-text-fill: white;");
        showGridCheck.setOnAction(e -> handleToggleGrid());
        
        showOctreeCheck = new CheckBox("Show Octree (O)");
        showOctreeCheck.setStyle("-fx-text-fill: white;");
        showOctreeCheck.setOnAction(e -> handleToggleOctree());
        
        showVoxelsCheck = new CheckBox("Show Voxels (V)");
        showVoxelsCheck.setStyle("-fx-text-fill: white;");
        showVoxelsCheck.setOnAction(e -> handleToggleVoxels());
        
        showRaysCheck = new CheckBox("Show Rays");
        showRaysCheck.setStyle("-fx-text-fill: white;");
        showRaysCheck.setOnAction(e -> handleToggleRays());
        
        displayContent.getChildren().addAll(showAxesCheck, showGridCheck, showOctreeCheck, showVoxelsCheck, showRaysCheck);
        displayPane.setContent(displayContent);
        displayPane.setExpanded(true);
        
        // Camera controls
        TitledPane cameraPane = new TitledPane();
        cameraPane.setText("Camera Controls");
        VBox cameraContent = new VBox(5);
        
        Button resetBtn = new Button("Reset View");
        resetBtn.setOnAction(e -> handleResetCamera());
        
        firstPersonCheck = new CheckBox("First Person Mode");
        firstPersonCheck.setStyle("-fx-text-fill: white;");
        firstPersonCheck.setSelected(true);
        firstPersonCheck.setOnAction(e -> cameraView.setFirstPersonNavigationEabled(firstPersonCheck.isSelected()));
        
        cameraContent.getChildren().addAll(resetBtn, firstPersonCheck);
        cameraPane.setContent(cameraContent);
        
        content.getChildren().addAll(displayPane, cameraPane);
        scroll.setContent(content);
        tab.setContent(scroll);
        
        return tab;
    }
    
    /**
     * Create the Properties tab for node details.
     */
    private Tab createPropertiesTab() {
        Tab tab = new Tab("Properties");
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setStyle("-fx-background-color: #1e1e1e;");
        
        Label infoLabel = new Label("Click on tree node to view properties");
        infoLabel.setStyle("-fx-text-fill: #888;");
        
        nodePropsTextArea = new TextArea();
        nodePropsTextArea.setEditable(false);
        nodePropsTextArea.setStyle("-fx-control-inner-background: #1e1e1e; -fx-text-fill: white;");
        nodePropsTextArea.setText("No node selected");
        VBox.setVgrow(nodePropsTextArea, Priority.ALWAYS);
        
        content.getChildren().addAll(infoLabel, nodePropsTextArea);
        tab.setContent(content);
        
        return tab;
    }
    
    /**
     * Create the Rendering tab for octree and voxel rendering controls.
     */
    private Tab createRenderingTab() {
        Tab tab = new Tab("Rendering");
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #1e1e1e;");
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setStyle("-fx-background-color: #1e1e1e;");
        
        // Octree Parameters
        TitledPane octreePane = new TitledPane();
        octreePane.setText("Octree Parameters");
        GridPane octreeGrid = new GridPane();
        octreeGrid.setHgap(10);
        octreeGrid.setVgap(5);
        
        Label depthLabel = new Label("Depth:");
        depthLabel.setStyle("-fx-text-fill: white;");
        depthSpinner = new Spinner<>(1, 20, currentLevel);
        depthSpinner.setEditable(true);
        depthSpinner.valueProperty().addListener((obs, oldVal, newVal) -> handleLevelChange(newVal));
        
        Label resLabel = new Label("Resolution:");
        resLabel.setStyle("-fx-text-fill: white;");
        resolutionSpinner = new Spinner<>(10, 200, 50, 10);
        resolutionSpinner.setEditable(true);
        
        Label shapeLabel = new Label("Shape:");
        shapeLabel.setStyle("-fx-text-fill: white;");
        shapeComboBox = new ComboBox<>();
        shapeComboBox.getItems().addAll(ProceduralVoxelGenerator.Shape.values());
        shapeComboBox.setValue(ProceduralVoxelGenerator.Shape.SPHERE);
        shapeComboBox.setOnAction(e -> handleShapeChange(shapeComboBox.getValue()));
        
        Button rebuildBtn = new Button("Rebuild Octree");
        rebuildBtn.setMaxWidth(Double.MAX_VALUE);
        rebuildBtn.setOnAction(e -> generateDemoOctree());
        
        octreeGrid.add(depthLabel, 0, 0);
        octreeGrid.add(depthSpinner, 1, 0);
        octreeGrid.add(resLabel, 0, 1);
        octreeGrid.add(resolutionSpinner, 1, 1);
        octreeGrid.add(shapeLabel, 0, 2);
        octreeGrid.add(shapeComboBox, 1, 2);
        octreeGrid.add(rebuildBtn, 0, 3, 2, 1);
        
        octreePane.setContent(octreeGrid);
        octreePane.setExpanded(true);
        
        // LOD Controls
        TitledPane lodPane = new TitledPane();
        lodPane.setText("LOD Controls");
        GridPane lodGrid = new GridPane();
        lodGrid.setHgap(10);
        lodGrid.setVgap(5);
        
        Label minLevelLabel = new Label("Min Level:");
        minLevelLabel.setStyle("-fx-text-fill: white;");
        minLevelSlider = new Slider(0, 20, 0);
        minLevelSlider.setShowTickLabels(true);
        minLevelSlider.setShowTickMarks(true);
        minLevelSlider.setMajorTickUnit(5);
        
        Label maxLevelLabel = new Label("Max Level:");
        maxLevelLabel.setStyle("-fx-text-fill: white;");
        maxLevelSlider = new Slider(0, 20, 20);
        maxLevelSlider.setShowTickLabels(true);
        maxLevelSlider.setShowTickMarks(true);
        maxLevelSlider.setMajorTickUnit(5);
        
        isolateLevelCheck = new CheckBox("Isolate Level");
        isolateLevelCheck.setStyle("-fx-text-fill: white;");
        
        Label isolatedLabel = new Label("Isolated Level:");
        isolatedLabel.setStyle("-fx-text-fill: white;");
        isolatedLevelSlider = new Slider(0, 20, 10);
        isolatedLevelSlider.setShowTickLabels(true);
        isolatedLevelSlider.setShowTickMarks(true);
        isolatedLevelSlider.setMajorTickUnit(5);
        
        ghostModeCheck = new CheckBox("Ghost Mode");
        ghostModeCheck.setStyle("-fx-text-fill: white;");
        
        lodGrid.add(minLevelLabel, 0, 0);
        lodGrid.add(minLevelSlider, 1, 0);
        lodGrid.add(maxLevelLabel, 0, 1);
        lodGrid.add(maxLevelSlider, 1, 1);
        lodGrid.add(isolateLevelCheck, 0, 2, 2, 1);
        lodGrid.add(isolatedLabel, 0, 3);
        lodGrid.add(isolatedLevelSlider, 1, 3);
        lodGrid.add(ghostModeCheck, 0, 4, 2, 1);
        
        lodPane.setContent(lodGrid);
        
        // Voxel Rendering
        TitledPane voxelPane = new TitledPane();
        voxelPane.setText("Voxel Rendering");
        VBox voxelContent = new VBox(5);
        
        Label modeLabel = new Label("Render Mode:");
        modeLabel.setStyle("-fx-text-fill: white;");
        
        renderModeGroup = new ToggleGroup();
        RadioButton filledRadio = new RadioButton("Filled");
        filledRadio.setToggleGroup(renderModeGroup);
        filledRadio.setSelected(true);
        filledRadio.setStyle("-fx-text-fill: white;");
        filledRadio.setOnAction(e -> handleRenderModeChange(VoxelRenderer.RenderMode.FILLED));
        
        RadioButton wireframeRadio = new RadioButton("Wireframe");
        wireframeRadio.setToggleGroup(renderModeGroup);
        wireframeRadio.setStyle("-fx-text-fill: white;");
        wireframeRadio.setOnAction(e -> handleRenderModeChange(VoxelRenderer.RenderMode.WIREFRAME));
        
        RadioButton pointsRadio = new RadioButton("Points");
        pointsRadio.setToggleGroup(renderModeGroup);
        pointsRadio.setStyle("-fx-text-fill: white;");
        pointsRadio.setOnAction(e -> handleRenderModeChange(VoxelRenderer.RenderMode.POINTS));
        
        Label materialLabel = new Label("Material Scheme:");
        materialLabel.setStyle("-fx-text-fill: white;");
        materialComboBox = new ComboBox<>();
        materialComboBox.getItems().addAll(VoxelRenderer.MaterialScheme.values());
        materialComboBox.setValue(VoxelRenderer.MaterialScheme.POSITION_GRADIENT);
        materialComboBox.setOnAction(e -> handleMaterialSchemeChange(materialComboBox.getValue()));
        
        voxelContent.getChildren().addAll(modeLabel, filledRadio, wireframeRadio, pointsRadio, 
                                          materialLabel, materialComboBox);
        voxelPane.setContent(voxelContent);
        
        content.getChildren().addAll(octreePane, lodPane, voxelPane);
        scroll.setContent(content);
        tab.setContent(scroll);
        
        return tab;
    }
    
    /**
     * Create the Analysis tab for performance and ray casting stats.
     */
    private Tab createAnalysisTab() {
        Tab tab = new Tab("Analysis");
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #1e1e1e;");
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setStyle("-fx-background-color: #1e1e1e;");
        
        // Performance Metrics (placeholder - will be updated by animation timer)
        TitledPane perfPane = new TitledPane();
        perfPane.setText("Performance Metrics");
        VBox perfContent = new VBox(5);
        
        Label perfLabel = new Label("Performance metrics will appear here");
        perfLabel.setStyle("-fx-text-fill: #888;");
        perfContent.getChildren().add(perfLabel);
        perfPane.setContent(perfContent);
        
        // Ray Casting
        TitledPane rayPane = new TitledPane();
        rayPane.setText("Ray Casting");
        VBox rayContent = new VBox(5);
        
        rayInteractiveCheck = new CheckBox("Interactive Mode");
        rayInteractiveCheck.setStyle("-fx-text-fill: white;");
        
        rayStatsTextArea = new TextArea();
        rayStatsTextArea.setEditable(false);
        rayStatsTextArea.setStyle("-fx-control-inner-background: #1e1e1e; -fx-text-fill: white; -fx-font-family: monospace;");
        rayStatsTextArea.setText("No ray cast yet.\nCtrl+Click to cast ray.");
        rayStatsTextArea.setPrefRowCount(10);
        
        rayContent.getChildren().addAll(rayInteractiveCheck, rayStatsTextArea);
        rayPane.setContent(rayContent);
        rayPane.setExpanded(true);
        
        content.getChildren().addAll(perfPane, rayPane);
        scroll.setContent(content);
        tab.setContent(scroll);
        
        return tab;
    }
    
    /**
     * Create the bottom status bar with live metrics.
     */
    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(5));
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #444; -fx-border-width: 1 0 0 0;");
        
        statusLabel = new Label("Status: Ready");
        statusLabel.setStyle("-fx-text-fill: white;");
        
        Separator sep1 = new Separator();
        sep1.setOrientation(javafx.geometry.Orientation.VERTICAL);
        
        nodesLabel = new Label("Nodes: --");
        nodesLabel.setStyle("-fx-text-fill: white;");
        
        Separator sep2 = new Separator();
        sep2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        
        fpsLabel = new Label("FPS: --");
        fpsLabel.setStyle("-fx-text-fill: white;");
        
        Separator sep3 = new Separator();
        sep3.setOrientation(javafx.geometry.Orientation.VERTICAL);
        
        memoryLabel = new Label("Memory: --");
        memoryLabel.setStyle("-fx-text-fill: white;");
        
        Separator sep4 = new Separator();
        sep4.setOrientation(javafx.geometry.Orientation.VERTICAL);
        
        levelLabel = new Label("Level: " + currentLevel);
        levelLabel.setStyle("-fx-text-fill: white;");
        
        statusBar.getChildren().addAll(statusLabel, sep1, nodesLabel, sep2, fpsLabel, sep3, memoryLabel, sep4, levelLabel);
        
        return statusBar;
    }
    
    /**
     * Populate the octree tree view with octree structure.
     */
    private void populateOctreeTree(ESVOOctreeData octree) {
        if (octreeTreeView == null || octree == null) {
            return;
        }
        
        TreeItem<String> root = new TreeItem<>("Octree Root (" + octree.getNodeCount() + " nodes)");
        root.setExpanded(true);
        
        // Add level nodes
        for (int i = 0; i <= currentLevel; i++) {
            TreeItem<String> levelItem = new TreeItem<>("Level " + i);
            root.getChildren().add(levelItem);
        }
        
        octreeTreeView.setRoot(root);
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

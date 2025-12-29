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
package com.hellblazer.luciferase.portal.esvt;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.CameraView;
import com.hellblazer.luciferase.portal.esvt.bridge.ESVTBridge;
import com.hellblazer.luciferase.portal.esvt.renderer.ESVTNodeMeshRenderer;
import com.hellblazer.luciferase.portal.esvt.renderer.ESVTOpenCLRenderBridge;
import com.hellblazer.luciferase.portal.esvt.renderer.ESVTRenderer;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ESVT (Efficient Sparse Voxel Tetrahedra) Inspector Application.
 *
 * <p>Interactive JavaFX application for exploring and visualizing ESVT
 * tetrahedral tree structures with procedural geometry generation and
 * real-time rendering.
 *
 * <p>Features:
 * <ul>
 *   <li>Interactive 3D camera controls via CameraView</li>
 *   <li>Procedural voxel geometry generation (sphere, cube, torus)</li>
 *   <li>ESVT tree building and visualization</li>
 *   <li>Ray casting visualization</li>
 *   <li>Performance metrics and statistics</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTInspectorApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(ESVTInspectorApp.class);

    // UI Components
    private BorderPane root;
    private CameraView cameraView;
    private Label statusLabel;
    private Label statsLabel;
    private TextArea metricsArea;

    // 3D Scene Groups
    private Group worldGroup;
    private Group axisGroup;
    private Group gridGroup;
    private Group esvtGroup;
    private Group rayGroup;

    // ESVT Components
    private ESVTBridge esvtBridge;
    private ESVTRenderer esvtRenderer;
    private ESVTVoxelGenerator voxelGenerator;
    private ESVTData currentData;

    // GPU Rendering Components (OpenCL-based for macOS compatibility)
    private ESVTOpenCLRenderBridge gpuBridge;
    private ImageView gpuImageView;
    private AnimationTimer gpuRenderTimer;
    private final AtomicBoolean gpuRenderPending = new AtomicBoolean(false);
    private boolean gpuModeActive = false;

    // UI Controls
    private Spinner<Integer> depthSpinner;
    private ComboBox<ESVTVoxelGenerator.Shape> shapeComboBox;
    private ComboBox<ESVTNodeMeshRenderer.ColorScheme> colorSchemeComboBox;
    private ComboBox<ESVTRenderer.RenderMode> renderModeComboBox;
    private CheckBox showAxesCheck;
    private CheckBox showGridCheck;
    private CheckBox showWireframeCheck;
    private Slider opacitySlider;

    // Background executor for ESVT building
    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "ESVTBuilder");
        t.setDaemon(true);
        return t;
    });

    // Current state
    private int currentLevel = 6;

    @Override
    public void start(Stage primaryStage) {
        log.info("Starting ESVT Inspector Application");

        // Initialize ESVT components
        esvtBridge = new ESVTBridge();
        voxelGenerator = new ESVTVoxelGenerator();
        esvtRenderer = ESVTRenderer.builder()
            .maxDepth(currentLevel)
            .colorScheme(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE)
            .renderMode(ESVTRenderer.RenderMode.LEAVES_ONLY)
            .opacity(0.7)
            .build();

        // Create the main layout
        root = new BorderPane();

        // Create 3D scene groups
        worldGroup = new Group();
        axisGroup = new Group();
        gridGroup = new Group();
        esvtGroup = new Group();
        rayGroup = new Group();

        // Build initial scene content
        buildAxes();
        buildGrid();

        // Add all groups to world
        worldGroup.getChildren().addAll(gridGroup, axisGroup, esvtGroup, rayGroup);

        // Add lighting
        var ambientLight = new AmbientLight(Color.WHITE);
        var pointLight = new PointLight(Color.WHITE);
        pointLight.setTranslateX(500);
        pointLight.setTranslateY(-500);
        pointLight.setTranslateZ(-500);
        worldGroup.getChildren().addAll(ambientLight, pointLight);

        // Create SubScene for 3D content
        var subScene = new SubScene(worldGroup, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.DARKSLATEGRAY);

        // Create and configure CameraView with first-person navigation
        cameraView = new CameraView(subScene);
        cameraView.setFirstPersonNavigationEabled(true);
        cameraView.startViewing();

        // Create control panel
        var controlPanel = createControlPanel();
        var controlScroll = new ScrollPane(controlPanel);
        controlScroll.setFitToWidth(true);
        controlScroll.setPrefWidth(300);

        // Create status bar
        var statusBar = createStatusBar();

        // Assemble the layout - CameraView directly in center (like OctreeInspectorApp)
        root.setCenter(cameraView);
        root.setRight(controlScroll);
        root.setBottom(statusBar);

        // Create the scene
        var scene = new Scene(root, 1200, 800);

        // Bind CameraView size to fill available space in BorderPane center
        cameraView.fitWidthProperty().bind(scene.widthProperty().subtract(controlScroll.widthProperty()));
        cameraView.fitHeightProperty().bind(scene.heightProperty().subtract(statusBar.heightProperty()));
        cameraView.setPreserveRatio(false);  // Fill the entire center area

        // Reset camera to default position (looking at origin where ESVT is centered)
        cameraView.resetCamera();

        primaryStage.setTitle("ESVT Inspector - Efficient Sparse Voxel Tetrahedra");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> shutdown());
        primaryStage.show();

        // Build initial geometry
        Platform.runLater(this::generateAndBuild);
    }

    /**
     * Create the control panel with all UI controls.
     */
    private VBox createControlPanel() {
        var panel = new VBox(10);
        panel.setPadding(new Insets(10));
        panel.setAlignment(Pos.TOP_LEFT);

        // Generation section
        var genSection = new TitledPane("Generation", createGenerationControls());
        genSection.setExpanded(true);

        // Rendering section
        var renderSection = new TitledPane("Rendering", createRenderingControls());
        renderSection.setExpanded(true);

        // Visibility section
        var visSection = new TitledPane("Visibility", createVisibilityControls());
        visSection.setExpanded(true);

        // Metrics section
        var metricsSection = new TitledPane("Metrics", createMetricsPanel());
        metricsSection.setExpanded(false);

        panel.getChildren().addAll(genSection, renderSection, visSection, metricsSection);
        return panel;
    }

    private VBox createGenerationControls() {
        var box = new VBox(8);
        box.setPadding(new Insets(5));

        // Shape selection - ESVT shapes inscribed within S0 tetrahedron
        shapeComboBox = new ComboBox<>();
        shapeComboBox.getItems().addAll(ESVTVoxelGenerator.Shape.values());
        shapeComboBox.setValue(ESVTVoxelGenerator.Shape.SPHERE);
        shapeComboBox.setMaxWidth(Double.MAX_VALUE);

        // Depth selection
        depthSpinner = new Spinner<>(3, 10, currentLevel);
        depthSpinner.setEditable(true);
        depthSpinner.setMaxWidth(Double.MAX_VALUE);

        // Generate button
        var generateBtn = new Button("Generate ESVT");
        generateBtn.setMaxWidth(Double.MAX_VALUE);
        generateBtn.setOnAction(e -> generateAndBuild());

        box.getChildren().addAll(
            new Label("Shape:"), shapeComboBox,
            new Label("Depth:"), depthSpinner,
            generateBtn
        );
        return box;
    }

    private VBox createRenderingControls() {
        var box = new VBox(8);
        box.setPadding(new Insets(5));

        // Color scheme
        colorSchemeComboBox = new ComboBox<>();
        colorSchemeComboBox.getItems().addAll(ESVTNodeMeshRenderer.ColorScheme.values());
        colorSchemeComboBox.setValue(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE);
        colorSchemeComboBox.setMaxWidth(Double.MAX_VALUE);
        colorSchemeComboBox.setOnAction(e -> updateRendering());

        // Render mode
        renderModeComboBox = new ComboBox<>();
        renderModeComboBox.getItems().addAll(ESVTRenderer.RenderMode.values());
        renderModeComboBox.setValue(ESVTRenderer.RenderMode.LEAVES_ONLY);
        renderModeComboBox.setMaxWidth(Double.MAX_VALUE);
        renderModeComboBox.setOnAction(e -> updateRendering());

        // Opacity slider
        opacitySlider = new Slider(0.1, 1.0, 0.7);
        opacitySlider.setShowTickLabels(true);
        opacitySlider.setShowTickMarks(true);
        opacitySlider.setOnMouseReleased(e -> updateRendering());

        // Wireframe check
        showWireframeCheck = new CheckBox("Show Wireframe");
        showWireframeCheck.setSelected(false);
        showWireframeCheck.setOnAction(e -> updateRendering());

        box.getChildren().addAll(
            new Label("Color Scheme:"), colorSchemeComboBox,
            new Label("Render Mode:"), renderModeComboBox,
            new Label("Opacity:"), opacitySlider,
            showWireframeCheck
        );
        return box;
    }

    private VBox createVisibilityControls() {
        var box = new VBox(8);
        box.setPadding(new Insets(5));

        showAxesCheck = new CheckBox("Show Axes");
        showAxesCheck.setSelected(true);
        showAxesCheck.setOnAction(e -> axisGroup.setVisible(showAxesCheck.isSelected()));

        showGridCheck = new CheckBox("Show Grid");
        showGridCheck.setSelected(true);
        showGridCheck.setOnAction(e -> gridGroup.setVisible(showGridCheck.isSelected()));

        var resetViewBtn = new Button("Reset View");
        resetViewBtn.setMaxWidth(Double.MAX_VALUE);
        resetViewBtn.setOnAction(e -> cameraView.resetCamera());

        box.getChildren().addAll(showAxesCheck, showGridCheck, resetViewBtn);
        return box;
    }

    private VBox createMetricsPanel() {
        var box = new VBox(5);
        box.setPadding(new Insets(5));

        metricsArea = new TextArea();
        metricsArea.setEditable(false);
        metricsArea.setPrefRowCount(10);
        metricsArea.setFont(javafx.scene.text.Font.font("Monospace", 11));

        box.getChildren().add(metricsArea);
        return box;
    }

    private HBox createStatusBar() {
        var statusBar = new HBox(20);
        statusBar.setPadding(new Insets(5, 10, 5, 10));
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setStyle("-fx-background-color: #e0e0e0;");

        statusLabel = new Label("Ready");
        statsLabel = new Label("");

        statusBar.getChildren().addAll(statusLabel, statsLabel);
        return statusBar;
    }

    /**
     * Generate voxels and build ESVT tree.
     */
    private void generateAndBuild() {
        currentLevel = depthSpinner.getValue();
        var shape = shapeComboBox.getValue();

        statusLabel.setText("Generating " + shape + " at depth " + currentLevel + "...");

        CompletableFuture.supplyAsync(() -> {
            // Generate voxels using resolution
            int resolution = 1 << currentLevel;
            List<Point3i> voxels = voxelGenerator.generate(shape, resolution);

            log.info("Generated {} voxels for {} at resolution {}", voxels.size(), shape, resolution);

            // Build ESVT with explicit grid resolution to preserve spatial relationships
            return esvtBridge.buildFromVoxels(voxels, currentLevel, resolution).getData();
        }, buildExecutor).thenAcceptAsync(data -> {
            currentData = data;
            esvtRenderer.setData(data);
            updateRendering();
            updateMetrics();
            statusLabel.setText("Built ESVT: " + data.nodeCount() + " nodes, " + data.leafCount() + " leaves");
            statsLabel.setText(String.format("Depth: %d | Build time: %.1fms",
                data.maxDepth(), esvtBridge.getLastBuildTimeMs()));
        }, Platform::runLater).exceptionally(ex -> {
            log.error("Error building ESVT", ex);
            Platform.runLater(() -> statusLabel.setText("Error: " + ex.getMessage()));
            return null;
        });
    }

    /**
     * Update the rendered visualization.
     */
    private void updateRendering() {
        if (currentData == null) return;

        var renderMode = renderModeComboBox.getValue();
        boolean isGpuMode = ESVTRenderer.isGPUMode(renderMode);

        // Handle GPU mode transitions
        if (isGpuMode && !gpuModeActive) {
            enableGpuMode();
        } else if (!isGpuMode && gpuModeActive) {
            disableGpuMode();
        }

        if (isGpuMode) {
            // GPU mode - rendering handled by animation timer
            return;
        }

        // Standard JavaFX rendering
        esvtGroup.getChildren().clear();

        // Create new renderer with current settings
        var newRenderer = ESVTRenderer.builder()
            .maxDepth(currentLevel)
            .colorScheme(colorSchemeComboBox.getValue())
            .renderMode(renderMode)
            .opacity(opacitySlider.getValue())
            .build();
        newRenderer.setData(currentData);

        // Render
        var rendering = newRenderer.render();
        esvtGroup.getChildren().add(rendering);

        // Add wireframe if requested
        if (showWireframeCheck.isSelected()) {
            var meshRenderer = new ESVTNodeMeshRenderer(currentData);
            esvtGroup.getChildren().add(meshRenderer.renderLeafWireframes());
        }

        // Note: Scaling and centering is now handled in ESVTNodeMeshRenderer
        // with default worldSize=400, so meshes span [-200, +200] in each axis
    }

    /**
     * Enable GPU rendering mode (uses OpenCL for macOS compatibility).
     */
    private void enableGpuMode() {
        if (gpuModeActive) return;

        // Check if OpenCL is available
        if (!ESVTOpenCLRenderBridge.isAvailable()) {
            statusLabel.setText("GPU not available (OpenCL required)");
            renderModeComboBox.setValue(ESVTRenderer.RenderMode.LEAVES_ONLY);
            return;
        }

        statusLabel.setText("Initializing OpenCL GPU renderer...");

        // Hide JavaFX 3D content
        esvtGroup.getChildren().clear();

        // Create GPU image view if needed
        if (gpuImageView == null) {
            gpuImageView = new ImageView();
            gpuImageView.setPreserveRatio(true);
        }

        // Get dimensions from CameraView
        int width = (int) Math.max(800, cameraView.getFitWidth());
        int height = (int) Math.max(600, cameraView.getFitHeight());

        // Initialize GPU bridge (OpenCL-based)
        if (gpuBridge == null) {
            gpuBridge = new ESVTOpenCLRenderBridge(width, height);
        }

        gpuBridge.initialize().thenCompose(v -> {
            // Upload current data
            return gpuBridge.uploadData(currentData);
        }).thenRunAsync(() -> {
            // Add GPU image view to scene
            if (!root.getChildren().contains(gpuImageView)) {
                // Stack GPU image over the CameraView
                var stack = new StackPane(cameraView, gpuImageView);
                root.setCenter(stack);
            }

            gpuImageView.setImage(gpuBridge.getOutputImage());
            gpuImageView.setFitWidth(width);
            gpuImageView.setFitHeight(height);

            // Start render timer
            startGpuRenderTimer();

            gpuModeActive = true;
            statusLabel.setText("OpenCL GPU rendering active");
        }, Platform::runLater).exceptionally(ex -> {
            log.error("Failed to initialize OpenCL GPU renderer", ex);
            Platform.runLater(() -> {
                statusLabel.setText("OpenCL GPU init failed: " + ex.getMessage());
                // Fall back to CPU rendering
                renderModeComboBox.setValue(ESVTRenderer.RenderMode.LEAVES_ONLY);
            });
            return null;
        });
    }

    /**
     * Disable GPU rendering mode.
     */
    private void disableGpuMode() {
        if (!gpuModeActive) return;

        // Stop render timer
        if (gpuRenderTimer != null) {
            gpuRenderTimer.stop();
            gpuRenderTimer = null;
        }

        // Restore standard layout
        root.setCenter(cameraView);

        gpuModeActive = false;
        statusLabel.setText("CPU rendering active");
    }

    /**
     * Start the GPU render animation timer.
     */
    private void startGpuRenderTimer() {
        if (gpuRenderTimer != null) {
            gpuRenderTimer.stop();
        }

        gpuRenderTimer = new AnimationTimer() {
            private long lastRender = 0;
            private static final long RENDER_INTERVAL_NS = 33_333_333L; // ~30 FPS

            @Override
            public void handle(long now) {
                if (now - lastRender < RENDER_INTERVAL_NS) return;
                if (!gpuModeActive || gpuBridge == null || !gpuBridge.isInitialized()) return;
                if (gpuRenderPending.get()) return; // Still processing previous frame

                lastRender = now;
                gpuRenderPending.set(true);

                // Update camera from CameraView
                updateGpuCamera();

                // Request GPU render
                gpuBridge.renderAsync(image -> {
                    gpuImageView.setImage(image);
                    gpuRenderPending.set(false);
                });
            }
        };
        gpuRenderTimer.start();
    }

    /**
     * Update GPU camera matrices from CameraView.
     *
     * <p>Coordinates must be transformed from JavaFX world space (where ESVT is
     * rendered at worldSize=400 centered at origin) to ESVT normalized [0,1] space.
     */
    private void updateGpuCamera() {
        if (gpuBridge == null || cameraView == null) return;

        // Get camera from CameraView
        var camera = cameraView.getCamera();
        if (!(camera instanceof PerspectiveCamera perspCamera)) return;

        // Extract camera position from transforms
        float camX = 0, camY = 0, camZ = -500; // Default position in JavaFX space

        // Try to get actual camera position from the camera's scene transform
        var localToScene = perspCamera.getLocalToSceneTransform();
        camX = (float) localToScene.getTx();
        camY = (float) localToScene.getTy();
        camZ = (float) localToScene.getTz();

        // Transform from JavaFX world space to ESVT [0,1] space
        // JavaFX: worldSize=400 centered at origin, so range is [-200, +200]
        // ESVT: normalized [0,1] space
        // Conversion: esvt_coord = (javafx_coord + 200) / 400
        float worldSize = 400.0f;
        float halfWorld = worldSize / 2.0f;

        // Convert camera position to ESVT [0,1] space
        float esvtCamX = (camX + halfWorld) / worldSize;
        float esvtCamY = (camY + halfWorld) / worldSize;
        float esvtCamZ = (camZ + halfWorld) / worldSize;

        // Look at center of ESVT space (0.5, 0.5, 0.5)
        var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);

        // Clamp near/far to reasonable values for [0,1] space
        float near = 0.01f;
        float far = 10.0f;

        // Set camera parameters in ESVT normalized space
        gpuBridge.setCamera(
            new Vector3f(esvtCamX, esvtCamY, esvtCamZ),
            lookAt,
            new Vector3f(0, 1, 0),  // Up vector
            (float) perspCamera.getFieldOfView(),
            near,
            far
        );

        // Set identity transforms (ESVT data is already in normalized space)
        var identity = new Matrix4f();
        identity.setIdentity();
        gpuBridge.setTransforms(identity, identity);
    }

    /**
     * Update the metrics display.
     */
    private void updateMetrics() {
        if (esvtBridge == null) return;

        var metrics = esvtBridge.getPerformanceMetrics();
        var sb = new StringBuilder();
        sb.append("=== ESVT Metrics ===\n");
        sb.append(String.format("Nodes: %d\n", metrics.nodeCount()));
        sb.append(String.format("Leaves: %d\n", metrics.leafCount()));
        sb.append(String.format("Depth: %d\n", metrics.maxDepth()));
        sb.append(String.format("Build Time: %.2fms\n", metrics.buildTimeMs()));
        sb.append("\n=== Ray Stats ===\n");
        sb.append(String.format("Rays Cast: %d\n", metrics.totalRays()));
        sb.append(String.format("Hits: %d (%.1f%%)\n", metrics.totalHits(), metrics.hitRatePercent()));
        sb.append(String.format("Avg Iterations: %.1f\n", metrics.avgIterations()));

        metricsArea.setText(sb.toString());
    }

    /**
     * Build the axis visualization.
     */
    private void buildAxes() {
        axisGroup.getChildren().clear();
        double length = 200;
        double radius = 2;

        // X axis (red)
        var xAxis = new javafx.scene.shape.Cylinder(radius, length);
        xAxis.setMaterial(new javafx.scene.paint.PhongMaterial(Color.RED));
        xAxis.setRotationAxis(javafx.scene.transform.Rotate.Z_AXIS);
        xAxis.setRotate(90);
        xAxis.setTranslateX(length / 2);

        // Y axis (green)
        var yAxis = new javafx.scene.shape.Cylinder(radius, length);
        yAxis.setMaterial(new javafx.scene.paint.PhongMaterial(Color.GREEN));
        yAxis.setTranslateY(-length / 2);

        // Z axis (blue)
        var zAxis = new javafx.scene.shape.Cylinder(radius, length);
        zAxis.setMaterial(new javafx.scene.paint.PhongMaterial(Color.BLUE));
        zAxis.setRotationAxis(javafx.scene.transform.Rotate.X_AXIS);
        zAxis.setRotate(90);
        zAxis.setTranslateZ(length / 2);

        axisGroup.getChildren().addAll(xAxis, yAxis, zAxis);
    }

    /**
     * Build the grid visualization.
     */
    private void buildGrid() {
        gridGroup.getChildren().clear();
        int gridSize = 500;
        int spacing = 50;
        var material = new javafx.scene.paint.PhongMaterial(Color.LIGHTGRAY);

        for (int i = -gridSize; i <= gridSize; i += spacing) {
            // Lines parallel to X axis
            var lineX = new javafx.scene.shape.Cylinder(0.5, gridSize * 2);
            lineX.setMaterial(material);
            lineX.setRotationAxis(javafx.scene.transform.Rotate.Z_AXIS);
            lineX.setRotate(90);
            lineX.setTranslateZ(i);
            gridGroup.getChildren().add(lineX);

            // Lines parallel to Z axis
            var lineZ = new javafx.scene.shape.Cylinder(0.5, gridSize * 2);
            lineZ.setMaterial(material);
            lineZ.setRotationAxis(javafx.scene.transform.Rotate.X_AXIS);
            lineZ.setRotate(90);
            lineZ.setTranslateX(i);
            gridGroup.getChildren().add(lineZ);
        }
    }

    /**
     * Shutdown the application.
     */
    private void shutdown() {
        log.info("Shutting down ESVT Inspector");

        // Stop GPU rendering
        if (gpuRenderTimer != null) {
            gpuRenderTimer.stop();
            gpuRenderTimer = null;
        }

        // Close GPU bridge
        if (gpuBridge != null) {
            try {
                gpuBridge.close();
            } catch (Exception e) {
                log.error("Error closing GPU bridge", e);
            }
            gpuBridge = null;
        }

        buildExecutor.shutdownNow();
    }

    /**
     * Launcher inner class for JavaFX.
     */
    public static class Launcher {
        public static void main(String[] args) {
            Application.launch(ESVTInspectorApp.class, args);
        }
    }

    public static void main(String[] args) {
        Application.launch(args);
    }
}

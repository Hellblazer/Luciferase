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
package com.hellblazer.luciferase.portal.inspector;

import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.CameraView;
import com.hellblazer.luciferase.render.inspector.SpatialData;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Abstract base class for spatial data inspector applications.
 *
 * <p>Provides common infrastructure for visualizing and exploring spatial
 * data structures (octrees, tetrahedra trees, etc.) with:
 * <ul>
 *   <li>Interactive 3D camera controls via CameraView</li>
 *   <li>Standard UI layout (toolbar, control panel, status bar)</li>
 *   <li>Scene graph management (axes, grid, content, rays)</li>
 *   <li>Performance monitoring</li>
 *   <li>Screenshot and recording</li>
 *   <li>Background task execution</li>
 * </ul>
 *
 * <p>Subclasses implement the abstract methods to provide data-specific
 * behavior while inheriting the common infrastructure.
 *
 * @param <D> The spatial data type (must implement SpatialData)
 * @param <B> The bridge type for building spatial data
 * @author hal.hildebrand
 */
public abstract class SpatialInspectorApp<D extends SpatialData, B extends SpatialBridge<D>> extends Application {

    private static final Logger log = LoggerFactory.getLogger(SpatialInspectorApp.class);

    // UI Components
    protected BorderPane root;
    protected CameraView cameraView;
    protected SubScene subScene;

    // Status bar labels
    protected Label statusLabel;
    protected Label nodesLabel;
    protected Label fpsLabel;
    protected Label memoryLabel;

    // Common UI controls
    protected CheckBox showAxesCheck;
    protected CheckBox showGridCheck;
    protected CheckBox showContentCheck;
    protected CheckBox showRaysCheck;
    protected Spinner<Integer> depthSpinner;
    protected ComboBox<String> shapeComboBox;

    // Managers
    protected SceneGroupManager sceneManager;
    protected MediaCaptureManager mediaCapture;
    protected PerformanceOverlay performanceOverlay;

    // Background executor
    protected final ExecutorService buildExecutor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, getDataTypeName() + "Builder");
        t.setDaemon(true);
        return t;
    });

    // Current state
    protected int currentDepth = 6;
    protected D currentData;
    protected B bridge;
    protected RenderConfiguration renderConfig = RenderConfiguration.DEFAULT;

    // ==================== Abstract Methods (Subclass Implements) ====================

    /**
     * Get the application title for the window.
     */
    protected abstract String getApplicationTitle();

    /**
     * Get the data type name (e.g., "Octree", "ESVT").
     */
    protected abstract String getDataTypeName();

    /**
     * Create the bridge for building spatial data.
     */
    protected abstract B createBridge();

    /**
     * Generate voxel coordinates for a shape.
     *
     * @param shapeName Shape name from the shape combo box
     * @param resolution Grid resolution
     * @return List of voxel coordinates
     */
    protected abstract List<Point3i> generateVoxels(String shapeName, int resolution);

    /**
     * Render the spatial data to a Group.
     *
     * @param data The spatial data to render
     * @param config Render configuration
     * @return JavaFX Group containing the rendered visualization
     */
    protected abstract Group renderData(D data, RenderConfiguration config);

    /**
     * Check if GPU rendering is supported.
     */
    protected abstract boolean supportsGPURendering();

    /**
     * Enable GPU rendering mode.
     */
    protected abstract void enableGPUMode();

    /**
     * Disable GPU rendering mode.
     */
    protected abstract void disableGPUMode();

    /**
     * Create data-specific control panels.
     *
     * @return VBox containing additional controls
     */
    protected abstract VBox createDataSpecificControls();

    /**
     * Create analysis tabs specific to this data type.
     *
     * @return TabPane with analysis tabs, or null if not needed
     */
    protected abstract TabPane createAnalysisTabs();

    /**
     * Get available shape names for the shape combo box.
     */
    protected abstract String[] getAvailableShapes();

    /**
     * Get the default shape name.
     */
    protected abstract String getDefaultShape();

    // ==================== Optional Override Methods ====================

    /**
     * Get the minimum allowed depth. Override to customize.
     * Default: 1
     */
    protected int getMinDepth() {
        return 1;
    }

    /**
     * Get the maximum allowed depth. Override to customize.
     * Default: 15 (ESVT can handle up to 21, ESVO limited to ~5)
     */
    protected int getMaxDepth() {
        return 15;
    }

    /**
     * Get the default depth. Override to customize.
     * Default: 6
     */
    protected int getDefaultDepth() {
        return 6;
    }

    // ==================== Template Method Pattern ====================

    @Override
    public final void start(Stage primaryStage) {
        log.info("Starting {} Inspector Application", getDataTypeName());

        // Initialize components
        initializeComponents();

        // Create the main layout
        root = new BorderPane();

        // Setup scene graph
        setupSceneGraph();

        // Create SubScene for 3D content
        subScene = new SubScene(sceneManager.getWorldGroup(), 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(getBackgroundColor());

        // Create and configure CameraView
        cameraView = new CameraView(subScene);
        cameraView.setFirstPersonNavigationEabled(true);
        cameraView.startViewing();

        // Create UI sections
        var toolbar = createTopToolbar();
        var leftPanel = createLeftPanel();
        var rightPanel = createRightPanel();
        var statusBar = createStatusBar();

        // Assemble layout
        root.setTop(toolbar);
        if (leftPanel != null) {
            root.setLeft(leftPanel);
        }
        root.setCenter(cameraView);
        root.setRight(rightPanel);
        root.setBottom(statusBar);

        // Create main scene
        var scene = new Scene(root, 1200, 800);

        // Bind CameraView size
        double leftWidth = leftPanel != null ? 200 : 0;
        double rightWidth = 350;
        cameraView.fitWidthProperty().bind(scene.widthProperty().subtract(leftWidth + rightWidth));
        cameraView.fitHeightProperty().bind(scene.heightProperty().subtract(toolbar.heightProperty()).subtract(statusBar.heightProperty()));
        cameraView.setPreserveRatio(false);

        // Setup keyboard shortcuts
        setupKeyboardShortcuts(scene);

        // Configure and show stage
        primaryStage.setTitle(getApplicationTitle());
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> shutdown());
        primaryStage.show();

        // Start performance monitoring
        startPerformanceMonitoring();

        // Print instructions and generate initial data
        printInstructions();
        Platform.runLater(this::generateAndBuild);
    }

    @Override
    public final void stop() throws Exception {
        log.info("Shutting down {} Inspector", getDataTypeName());
        shutdown();
        super.stop();
    }

    // ==================== Initialization ====================

    /**
     * Initialize all components. Called before UI creation.
     */
    protected void initializeComponents() {
        // Initialize depth from subclass override
        currentDepth = getDefaultDepth();

        // Initialize render config with proper depth limits
        renderConfig = RenderConfiguration.DEFAULT.withMaxDepth(currentDepth);

        bridge = createBridge();
        sceneManager = new SceneGroupManager();
        mediaCapture = new MediaCaptureManager(getDataTypeName().toLowerCase());
        performanceOverlay = new PerformanceOverlay();

        mediaCapture.setStatusCallback(this::updateStatus);

        log.info("{} components initialized with depth {}", getDataTypeName(), currentDepth);
    }

    /**
     * Setup the scene graph with axes, grid, and lighting.
     */
    protected void setupSceneGraph() {
        sceneManager.initialize();
        sceneManager.buildAxes();
        sceneManager.buildGrid();
    }

    /**
     * Get the background color for the 3D scene.
     */
    protected Color getBackgroundColor() {
        return Color.LIGHTGRAY;
    }

    // ==================== UI Creation ====================

    /**
     * Create the top toolbar with action buttons.
     */
    protected ToolBar createTopToolbar() {
        var toolbar = new ToolBar();
        toolbar.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 5;");

        var screenshotBtn = new Button("Screenshot");
        screenshotBtn.setTooltip(new Tooltip("Capture current view (S)"));
        screenshotBtn.setOnAction(e -> handleScreenshot());

        var recordBtn = new Button("Record");
        recordBtn.setTooltip(new Tooltip("Toggle frame recording"));
        recordBtn.setOnAction(e -> handleToggleRecording());

        var resetViewBtn = new Button("Reset View");
        resetViewBtn.setTooltip(new Tooltip("Reset camera (R)"));
        resetViewBtn.setOnAction(e -> handleResetCamera());

        toolbar.getItems().addAll(screenshotBtn, recordBtn, resetViewBtn);

        // Allow subclasses to add more toolbar items
        addToolbarItems(toolbar);

        return toolbar;
    }

    /**
     * Override to add additional toolbar items.
     */
    protected void addToolbarItems(ToolBar toolbar) {
        // Default: no additional items
    }

    /**
     * Create the left panel. Override to provide content.
     */
    protected VBox createLeftPanel() {
        return null; // Default: no left panel
    }

    /**
     * Create the right panel with controls.
     */
    protected VBox createRightPanel() {
        var panel = new VBox(10);
        panel.setPadding(new Insets(10));
        panel.setPrefWidth(350);
        panel.setMaxWidth(350);
        panel.setStyle("-fx-background-color: #2b2b2b;");

        // Common controls
        var commonPane = new TitledPane("Common Controls", createCommonControls());
        commonPane.setExpanded(true);

        // Display options
        var displayPane = new TitledPane("Display Options", createDisplayControls());
        displayPane.setExpanded(true);

        // Data-specific controls
        var specificControls = createDataSpecificControls();
        var specificPane = new TitledPane(getDataTypeName() + " Controls", specificControls);
        specificPane.setExpanded(true);

        panel.getChildren().addAll(commonPane, displayPane, specificPane);

        // Add analysis tabs if provided
        var analysisTabs = createAnalysisTabs();
        if (analysisTabs != null) {
            VBox.setVgrow(analysisTabs, Priority.ALWAYS);
            panel.getChildren().add(analysisTabs);
        }

        return panel;
    }

    /**
     * Create common controls (depth, shape, rebuild button).
     */
    protected VBox createCommonControls() {
        var box = new VBox(8);
        box.setPadding(new Insets(5));

        // Depth spinner
        var depthLabel = new Label("Depth:");
        depthLabel.setStyle("-fx-text-fill: white;");
        depthSpinner = new Spinner<>(getMinDepth(), getMaxDepth(), currentDepth);
        depthSpinner.setEditable(true);
        depthSpinner.setMaxWidth(Double.MAX_VALUE);

        // Shape combo
        var shapeLabel = new Label("Shape:");
        shapeLabel.setStyle("-fx-text-fill: white;");
        shapeComboBox = new ComboBox<>();
        shapeComboBox.getItems().addAll(getAvailableShapes());
        shapeComboBox.setValue(getDefaultShape());
        shapeComboBox.setMaxWidth(Double.MAX_VALUE);

        // Rebuild button
        var rebuildBtn = new Button("Rebuild " + getDataTypeName());
        rebuildBtn.setMaxWidth(Double.MAX_VALUE);
        rebuildBtn.setOnAction(e -> generateAndBuild());

        box.getChildren().addAll(depthLabel, depthSpinner, shapeLabel, shapeComboBox, rebuildBtn);
        return box;
    }

    /**
     * Create display controls (axes, grid, content visibility).
     */
    protected VBox createDisplayControls() {
        var box = new VBox(5);
        box.setPadding(new Insets(5));
        box.setStyle("-fx-background-color: #1e1e1e;");

        showAxesCheck = new CheckBox("Show Axes (X)");
        showAxesCheck.setSelected(true);
        showAxesCheck.setStyle("-fx-text-fill: white;");
        showAxesCheck.setOnAction(e -> sceneManager.setVisibility("axis", showAxesCheck.isSelected()));

        showGridCheck = new CheckBox("Show Grid (G)");
        showGridCheck.setSelected(true);
        showGridCheck.setStyle("-fx-text-fill: white;");
        showGridCheck.setOnAction(e -> sceneManager.setVisibility("grid", showGridCheck.isSelected()));

        showContentCheck = new CheckBox("Show " + getDataTypeName() + " (O)");
        showContentCheck.setSelected(true);
        showContentCheck.setStyle("-fx-text-fill: white;");
        showContentCheck.setOnAction(e -> sceneManager.setVisibility("content", showContentCheck.isSelected()));

        showRaysCheck = new CheckBox("Show Rays");
        showRaysCheck.setStyle("-fx-text-fill: white;");
        showRaysCheck.setOnAction(e -> sceneManager.setVisibility("ray", showRaysCheck.isSelected()));

        var resetViewBtn = new Button("Reset View");
        resetViewBtn.setMaxWidth(Double.MAX_VALUE);
        resetViewBtn.setOnAction(e -> handleResetCamera());

        box.getChildren().addAll(showAxesCheck, showGridCheck, showContentCheck, showRaysCheck, resetViewBtn);
        return box;
    }

    /**
     * Create the status bar.
     */
    protected HBox createStatusBar() {
        var statusBar = new HBox(10);
        statusBar.setPadding(new Insets(5));
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #444; -fx-border-width: 1 0 0 0;");

        statusLabel = new Label("Status: Ready");
        statusLabel.setStyle("-fx-text-fill: white;");

        var sep1 = new Separator(Orientation.VERTICAL);

        nodesLabel = new Label("Nodes: --");
        nodesLabel.setStyle("-fx-text-fill: white;");

        var sep2 = new Separator(Orientation.VERTICAL);

        fpsLabel = new Label("FPS: --");
        fpsLabel.setStyle("-fx-text-fill: white;");

        var sep3 = new Separator(Orientation.VERTICAL);

        memoryLabel = new Label("Memory: --");
        memoryLabel.setStyle("-fx-text-fill: white;");

        statusBar.getChildren().addAll(statusLabel, sep1, nodesLabel, sep2, fpsLabel, sep3, memoryLabel);
        return statusBar;
    }

    // ==================== Keyboard Shortcuts ====================

    /**
     * Setup keyboard shortcuts.
     */
    protected void setupKeyboardShortcuts(Scene scene) {
        scene.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case X -> {
                    showAxesCheck.setSelected(!showAxesCheck.isSelected());
                    sceneManager.setVisibility("axis", showAxesCheck.isSelected());
                }
                case G -> {
                    showGridCheck.setSelected(!showGridCheck.isSelected());
                    sceneManager.setVisibility("grid", showGridCheck.isSelected());
                }
                case O -> {
                    showContentCheck.setSelected(!showContentCheck.isSelected());
                    sceneManager.setVisibility("content", showContentCheck.isSelected());
                }
                case R -> handleResetCamera();
                case S -> handleScreenshot();
                default -> handleCustomKeyPress(event.getCode());
            }
        });
    }

    /**
     * Override to handle custom key presses.
     */
    protected void handleCustomKeyPress(javafx.scene.input.KeyCode code) {
        // Default: no custom handling
    }

    // ==================== Event Handlers ====================

    /**
     * Reset camera to default position.
     */
    protected void handleResetCamera() {
        cameraView.resetCamera();
    }

    /**
     * Handle screenshot capture.
     */
    protected void handleScreenshot() {
        String additionalInfo = "";
        if (currentData != null) {
            additionalInfo = String.format("[%s: %d nodes, depth %d]",
                getDataTypeName(), currentData.nodeCount(), currentDepth);
        }
        mediaCapture.captureScreenshot(root, additionalInfo);
    }

    /**
     * Handle recording toggle.
     */
    protected void handleToggleRecording() {
        mediaCapture.toggleRecording();
    }

    // ==================== Data Building ====================

    /**
     * Generate voxels and build spatial data.
     */
    protected void generateAndBuild() {
        var savedCameraState = cameraView.saveCameraState();
        currentDepth = depthSpinner.getValue();
        // Update render config to match new depth
        renderConfig = renderConfig.withMaxDepth(currentDepth);
        var shapeName = shapeComboBox.getValue();

        updateStatus("Building " + shapeName + " at depth " + currentDepth + "...");

        CompletableFuture.supplyAsync(() -> {
            int resolution = 1 << currentDepth;
            var voxels = generateVoxels(shapeName, resolution);
            log.info("Generated {} voxels for {} at resolution {}", voxels.size(), shapeName, resolution);

            return bridge.buildFromVoxels(voxels, currentDepth, resolution);
        }, buildExecutor).thenAcceptAsync(result -> {
            if (result.isSuccess()) {
                currentData = result.getData();
                updateVisualization();
                cameraView.restoreCameraState(savedCameraState);

                updateStatus("Ready");
                nodesLabel.setText("Nodes: " + currentData.nodeCount());
            } else {
                updateStatus("Build failed: " + result.message());
                log.error("Build failed: {}", result.message());
            }
        }, Platform::runLater).exceptionally(ex -> {
            log.error("Error building " + getDataTypeName(), ex);
            Platform.runLater(() -> updateStatus("Error: " + ex.getMessage()));
            return null;
        });
    }

    /**
     * Update the visualization with current data.
     */
    protected void updateVisualization() {
        if (currentData == null) return;

        sceneManager.clearContent();

        var renderedGroup = renderData(currentData, renderConfig);
        sceneManager.getContentGroup().getChildren().add(renderedGroup);

        sceneManager.setVisibility("content", true);
        showContentCheck.setSelected(true);

        log.info("{} visualization updated: {} nodes rendered", getDataTypeName(), currentData.nodeCount());
    }

    // ==================== Performance Monitoring ====================

    /**
     * Start performance monitoring.
     */
    protected void startPerformanceMonitoring() {
        performanceOverlay.setMetricsCallback(this::updatePerformanceMetrics);

        // Setup frame callback for recording
        performanceOverlay.setFrameCallback(() -> {
            if (mediaCapture.isRecording()) {
                mediaCapture.captureFrame(root);
            }
        });

        performanceOverlay.start();
        log.info("Performance monitoring started");
    }

    /**
     * Update performance metrics display.
     */
    protected void updatePerformanceMetrics(PerformanceOverlay.Metrics metrics) {
        fpsLabel.setText(String.format("FPS: %.1f", metrics.fps()));
        memoryLabel.setText(String.format("Memory: %.1f MB", metrics.usedMemoryMB()));
    }

    // ==================== Utility Methods ====================

    /**
     * Update the status label.
     */
    protected void updateStatus(String message) {
        if (statusLabel != null) {
            statusLabel.setText("Status: " + message);
        }
    }

    /**
     * Run a task asynchronously and handle the result on the JavaFX thread.
     */
    protected <T> void runAsync(Supplier<T> task, Consumer<T> onSuccess) {
        CompletableFuture.supplyAsync(task, buildExecutor)
            .thenAcceptAsync(onSuccess, Platform::runLater)
            .exceptionally(ex -> {
                log.error("Async task failed", ex);
                Platform.runLater(() -> updateStatus("Error: " + ex.getMessage()));
                return null;
            });
    }

    /**
     * Shutdown and cleanup resources.
     */
    protected void shutdown() {
        if (performanceOverlay != null) {
            performanceOverlay.stop();
        }
        disableGPUMode();
        buildExecutor.shutdown();
    }

    /**
     * Print usage instructions.
     */
    protected void printInstructions() {
        System.out.println("\n=== " + getApplicationTitle() + " ===");
        System.out.println("Interactive visualization tool for " + getDataTypeName() + " structures");
        System.out.println();
        System.out.println("Navigation:");
        System.out.println("- Mouse drag: Rotate camera");
        System.out.println("- Mouse wheel: Zoom in/out");
        System.out.println("- WASD keys: Move camera (first-person mode)");
        System.out.println();
        System.out.println("Shortcuts:");
        System.out.println("- X: Toggle axes");
        System.out.println("- G: Toggle grid");
        System.out.println("- O: Toggle " + getDataTypeName());
        System.out.println("- R: Reset view");
        System.out.println("- S: Screenshot");
        System.out.println();
    }
}

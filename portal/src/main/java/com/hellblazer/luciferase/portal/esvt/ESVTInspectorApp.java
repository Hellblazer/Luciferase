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
import com.hellblazer.luciferase.portal.esvt.bridge.ESVTBridge;
import com.hellblazer.luciferase.portal.esvt.renderer.ESVTNodeMeshRenderer;
import com.hellblazer.luciferase.portal.esvt.renderer.ESVTOpenCLRenderBridge;
import com.hellblazer.luciferase.portal.esvt.renderer.ESVTRenderer;
import com.hellblazer.luciferase.portal.inspector.RenderConfiguration;
import com.hellblazer.luciferase.portal.inspector.SpatialInspectorApp;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ESVT (Efficient Sparse Voxel Tetrahedra) Inspector Application.
 *
 * <p>Extends SpatialInspectorApp with ESVT-specific features:
 * <ul>
 *   <li>GPU rendering via OpenCL</li>
 *   <li>Tetrahedral color schemes</li>
 *   <li>Opacity and wireframe controls</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ESVTInspectorApp extends SpatialInspectorApp<ESVTData, ESVTBridge> {

    private static final Logger log = LoggerFactory.getLogger(ESVTInspectorApp.class);

    // ESVT-specific components
    private ESVTRenderer esvtRenderer;
    private ESVTVoxelGenerator voxelGenerator;

    // GPU Rendering
    private ESVTOpenCLRenderBridge gpuBridge;
    private ImageView gpuImageView;
    private AnimationTimer gpuRenderTimer;
    private final AtomicBoolean gpuRenderPending = new AtomicBoolean(false);
    private boolean gpuModeActive = false;
    private int gpuDebugFrameCount = 0;

    // ESVT-specific UI controls
    private ComboBox<ESVTNodeMeshRenderer.ColorScheme> colorSchemeComboBox;
    private ComboBox<ESVTRenderer.RenderMode> renderModeComboBox;
    private Slider opacitySlider;
    private CheckBox showWireframeCheck;
    private TextArea metricsArea;

    // ==================== Abstract Method Implementations ====================

    @Override
    protected String getApplicationTitle() {
        return "ESVT Inspector - Efficient Sparse Voxel Tetrahedra";
    }

    @Override
    protected String getDataTypeName() {
        return "ESVT";
    }

    @Override
    protected ESVTBridge createBridge() {
        return new ESVTBridge();
    }

    @Override
    protected String[] getAvailableShapes() {
        var shapes = ESVTVoxelGenerator.Shape.values();
        var names = new String[shapes.length];
        for (int i = 0; i < shapes.length; i++) {
            names[i] = shapes[i].name();
        }
        return names;
    }

    @Override
    protected String getDefaultShape() {
        return ESVTVoxelGenerator.Shape.SPHERE.name();
    }

    @Override
    protected List<Point3i> generateVoxels(String shapeName, int resolution) {
        var shape = ESVTVoxelGenerator.Shape.valueOf(shapeName);
        return voxelGenerator.generate(shape, resolution);
    }

    @Override
    protected Group renderData(ESVTData data, RenderConfiguration config) {
        // Create new renderer with current settings
        var colorScheme = colorSchemeComboBox != null ?
            colorSchemeComboBox.getValue() : ESVTNodeMeshRenderer.ColorScheme.TET_TYPE;
        var renderMode = renderModeComboBox != null ?
            renderModeComboBox.getValue() : ESVTRenderer.RenderMode.LEAVES_ONLY;
        var opacity = opacitySlider != null ? opacitySlider.getValue() : 0.7;

        esvtRenderer = ESVTRenderer.builder()
            .maxDepth(currentDepth)
            .colorScheme(colorScheme)
            .renderMode(renderMode)
            .opacity(opacity)
            .build();
        esvtRenderer.setData(data);

        var rendered = esvtRenderer.render();

        // Add wireframe if requested
        if (showWireframeCheck != null && showWireframeCheck.isSelected()) {
            var meshRenderer = new ESVTNodeMeshRenderer(data);
            var wireframeGroup = new Group(rendered, meshRenderer.renderLeafWireframes());
            return wireframeGroup;
        }

        return rendered;
    }

    @Override
    protected boolean supportsGPURendering() {
        return ESVTOpenCLRenderBridge.isAvailable();
    }

    @Override
    protected void enableGPUMode() {
        if (gpuModeActive) return;

        if (!ESVTOpenCLRenderBridge.isAvailable()) {
            updateStatus("GPU not available (OpenCL required)");
            if (renderModeComboBox != null) {
                renderModeComboBox.setValue(ESVTRenderer.RenderMode.LEAVES_ONLY);
            }
            return;
        }

        updateStatus("Initializing OpenCL GPU renderer...");

        // Hide JavaFX 3D content
        sceneManager.clearContent();

        // Create GPU image view
        if (gpuImageView == null) {
            gpuImageView = new ImageView();
            gpuImageView.setPreserveRatio(true);
            gpuImageView.setMouseTransparent(true);
            gpuImageView.setPickOnBounds(false);
        }

        // Get dimensions
        int width = (int) Math.max(800, cameraView.getFitWidth());
        int height = (int) Math.max(600, cameraView.getFitHeight());

        // Initialize GPU bridge
        if (gpuBridge == null) {
            gpuBridge = new ESVTOpenCLRenderBridge(width, height);
        }

        gpuBridge.initialize().thenCompose(v -> {
            if (currentData == null) {
                throw new IllegalStateException("No ESVT data available");
            }
            log.info("Uploading ESVT data: {}", currentData);
            return gpuBridge.uploadData(currentData);
        }).thenRunAsync(() -> {
            // Stack GPU image over the CameraView
            if (!root.getChildren().contains(gpuImageView)) {
                var stack = new StackPane(cameraView, gpuImageView);
                root.setCenter(stack);
            }

            gpuImageView.setImage(gpuBridge.getOutputImage());
            gpuImageView.setFitWidth(width);
            gpuImageView.setFitHeight(height);

            gpuDebugFrameCount = 0;
            startGpuRenderTimer();

            gpuModeActive = true;
            String dataInfo = currentData != null ?
                String.format("ESVT: %d nodes, %d leaves, depth %d",
                    currentData.nodeCount(), currentData.leafCount(), currentData.maxDepth()) :
                "No ESVT data";
            updateStatus("OpenCL GPU active - " + dataInfo);
        }, Platform::runLater).exceptionally(ex -> {
            log.error("Failed to initialize OpenCL GPU renderer", ex);
            Platform.runLater(() -> {
                updateStatus("OpenCL GPU init failed: " + ex.getMessage());
                if (renderModeComboBox != null) {
                    renderModeComboBox.setValue(ESVTRenderer.RenderMode.LEAVES_ONLY);
                }
            });
            return null;
        });
    }

    @Override
    protected void disableGPUMode() {
        if (!gpuModeActive) return;

        if (gpuRenderTimer != null) {
            gpuRenderTimer.stop();
            gpuRenderTimer = null;
        }

        // Restore standard layout
        root.setCenter(cameraView);

        gpuModeActive = false;
        updateStatus("CPU rendering active");
    }

    @Override
    protected VBox createDataSpecificControls() {
        var box = new VBox(10);

        // Rendering section
        var renderPane = new TitledPane("Rendering", createRenderingControls());
        renderPane.setExpanded(true);

        // Metrics section
        var metricsPane = new TitledPane("Metrics", createMetricsPanel());
        metricsPane.setExpanded(false);

        box.getChildren().addAll(renderPane, metricsPane);
        return box;
    }

    @Override
    protected TabPane createAnalysisTabs() {
        return null; // No additional tabs for ESVT
    }

    // ==================== Initialization ====================

    @Override
    protected void initializeComponents() {
        super.initializeComponents();

        voxelGenerator = new ESVTVoxelGenerator();
        esvtRenderer = ESVTRenderer.builder()
            .maxDepth(currentDepth)
            .colorScheme(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE)
            .renderMode(ESVTRenderer.RenderMode.LEAVES_ONLY)
            .opacity(0.7)
            .build();
    }

    @Override
    protected Color getBackgroundColor() {
        return Color.DARKSLATEGRAY;
    }

    // ==================== Rendering Controls ====================

    private VBox createRenderingControls() {
        var box = new VBox(8);
        box.setPadding(new Insets(5));

        // Color scheme
        var colorLabel = new Label("Color Scheme:");
        colorLabel.setStyle("-fx-text-fill: white;");
        colorSchemeComboBox = new ComboBox<>();
        colorSchemeComboBox.getItems().addAll(ESVTNodeMeshRenderer.ColorScheme.values());
        colorSchemeComboBox.setValue(ESVTNodeMeshRenderer.ColorScheme.TET_TYPE);
        colorSchemeComboBox.setMaxWidth(Double.MAX_VALUE);
        colorSchemeComboBox.setOnAction(e -> handleRenderingChanged());

        // Render mode
        var modeLabel = new Label("Render Mode:");
        modeLabel.setStyle("-fx-text-fill: white;");
        renderModeComboBox = new ComboBox<>();
        renderModeComboBox.getItems().addAll(ESVTRenderer.RenderMode.values());
        renderModeComboBox.setValue(ESVTRenderer.RenderMode.LEAVES_ONLY);
        renderModeComboBox.setMaxWidth(Double.MAX_VALUE);
        renderModeComboBox.setOnAction(e -> handleRenderModeChanged());

        // Opacity slider
        var opacityLabel = new Label("Opacity:");
        opacityLabel.setStyle("-fx-text-fill: white;");
        opacitySlider = new Slider(0.1, 1.0, 0.7);
        opacitySlider.setShowTickLabels(true);
        opacitySlider.setShowTickMarks(true);
        opacitySlider.setOnMouseReleased(e -> handleRenderingChanged());

        // Wireframe checkbox
        showWireframeCheck = new CheckBox("Show Wireframe");
        showWireframeCheck.setStyle("-fx-text-fill: white;");
        showWireframeCheck.setOnAction(e -> handleRenderingChanged());

        box.getChildren().addAll(
            colorLabel, colorSchemeComboBox,
            modeLabel, renderModeComboBox,
            opacityLabel, opacitySlider,
            showWireframeCheck
        );
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

    private void handleRenderModeChanged() {
        var renderMode = renderModeComboBox.getValue();
        boolean isGpuMode = ESVTRenderer.isGPUMode(renderMode);

        if (isGpuMode && !gpuModeActive) {
            enableGPUMode();
        } else if (!isGpuMode && gpuModeActive) {
            disableGPUMode();
        }

        if (isGpuMode) {
            // GPU mode - upload new data if bridge is ready
            if (gpuBridge != null && gpuBridge.isInitialized() && currentData != null) {
                gpuBridge.uploadData(currentData).thenRun(() -> {
                    log.info("Re-uploaded ESVT data to GPU: {} nodes", currentData.nodeCount());
                });
            }
        } else {
            handleRenderingChanged();
        }
    }

    private void handleRenderingChanged() {
        if (!gpuModeActive) {
            updateVisualization();
        }
    }

    // ==================== GPU Rendering ====================

    private void startGpuRenderTimer() {
        if (gpuRenderTimer != null) {
            gpuRenderTimer.stop();
        }

        gpuRenderTimer = new AnimationTimer() {
            private long lastRender = 0;
            private long frameCount = 0;
            private static final long RENDER_INTERVAL_NS = 33_333_333L; // ~30 FPS

            @Override
            public void handle(long now) {
                if (now - lastRender < RENDER_INTERVAL_NS) return;
                if (!gpuModeActive || gpuBridge == null || !gpuBridge.isInitialized()) return;
                if (gpuRenderPending.get()) return;

                lastRender = now;
                gpuRenderPending.set(true);
                frameCount++;

                updateGpuCamera();

                final long frame = frameCount;
                gpuBridge.renderAsync(image -> {
                    gpuImageView.setImage(image);
                    gpuRenderPending.set(false);

                    if (frame % 30 == 0) {
                        String dataInfo = currentData != null ?
                            String.format("ESVT: %d nodes, depth %d, frame %d",
                                currentData.nodeCount(), currentData.maxDepth(), frame) :
                            "No ESVT data";
                        updateStatus("OpenCL GPU active - " + dataInfo);
                    }
                });
            }
        };
        gpuRenderTimer.start();
    }

    private void updateGpuCamera() {
        if (gpuBridge == null || cameraView == null) return;

        gpuDebugFrameCount++;

        var camera = cameraView.getCamera();
        if (!(camera instanceof PerspectiveCamera perspCamera)) return;

        var localToScene = perspCamera.getLocalToSceneTransform();

        float camX = (float) localToScene.getTx();
        float camY = (float) localToScene.getTy();
        float camZ = (float) localToScene.getTz();

        float worldSize = 400.0f;

        float esvtCamX = camX / worldSize + 0.5f;
        float esvtCamY = camY / worldSize + 0.5f;
        float esvtCamZ = camZ / worldSize + 0.5f;

        var lookAt = new Vector3f(0.5f, 0.5f, 0.5f);

        if (gpuDebugFrameCount % 60 == 0) {
            log.info("GPU Camera: JavaFX({}, {}, {}) -> ESVT({}, {}, {})",
                camX, camY, camZ, esvtCamX, esvtCamY, esvtCamZ);
        }

        gpuBridge.setCamera(
            new Vector3f(esvtCamX, esvtCamY, esvtCamZ),
            lookAt,
            new Vector3f(0, 1, 0),
            (float) perspCamera.getFieldOfView(),
            0.01f,
            100.0f
        );

        var identity = new Matrix4f();
        identity.setIdentity();
        gpuBridge.setTransforms(identity, identity);
    }

    // ==================== Metrics ====================

    @Override
    protected void updateVisualization() {
        super.updateVisualization();
        updateMetrics();
    }

    private void updateMetrics() {
        if (bridge == null || metricsArea == null) return;

        var metrics = bridge.getPerformanceMetrics();
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

    // ==================== Shutdown ====================

    @Override
    protected void shutdown() {
        if (gpuRenderTimer != null) {
            gpuRenderTimer.stop();
            gpuRenderTimer = null;
        }

        if (gpuBridge != null) {
            try {
                gpuBridge.close();
            } catch (Exception e) {
                log.error("Error closing GPU bridge", e);
            }
            gpuBridge = null;
        }

        super.shutdown();
    }

    // ==================== Launcher ====================

    public static class Launcher {
        public static void main(String[] args) {
            Application.launch(ESVTInspectorApp.class, args);
        }
    }

    public static void main(String[] args) {
        Application.launch(args);
    }
}

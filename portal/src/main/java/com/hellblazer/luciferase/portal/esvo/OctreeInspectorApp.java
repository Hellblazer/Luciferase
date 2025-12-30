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
import com.hellblazer.luciferase.esvo.traversal.StackBasedRayTraversal.DeepTraversalResult;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.portal.esvo.bridge.ESVOBridge;
import com.hellblazer.luciferase.portal.esvo.renderer.OctreeRenderer;
import com.hellblazer.luciferase.portal.esvo.renderer.VoxelRenderer;
import com.hellblazer.luciferase.portal.esvo.visualization.RayCastVisualizer;
import com.hellblazer.luciferase.portal.inspector.RenderConfiguration;
import com.hellblazer.luciferase.portal.inspector.SpatialInspectorApp;
import com.hellblazer.luciferase.portal.mesh.octree.OctreeNodeMeshRenderer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Vector3f;
import java.util.List;

/**
 * ESVO Octree Inspector - JavaFX application for exploring and visualizing
 * ESVO octree structures with procedural geometry generation and real-time rendering.
 *
 * <p>Extends SpatialInspectorApp with octree-specific features:
 * <ul>
 *   <li>Level-of-detail (LOD) controls</li>
 *   <li>Level isolation and ghost mode</li>
 *   <li>Ray casting visualization</li>
 *   <li>Multiple material schemes</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class OctreeInspectorApp extends SpatialInspectorApp<ESVOOctreeData, ESVOBridge> {

    private static final Logger log = LoggerFactory.getLogger(OctreeInspectorApp.class);

    // Octree-specific components
    private OctreeRenderer octreeRenderer;
    private VoxelRenderer voxelRenderer;
    private ProceduralVoxelGenerator voxelGenerator;
    private RayCastVisualizer rayCastVisualizer;

    // Octree-specific UI controls
    private Slider minLevelSlider;
    private Slider maxLevelSlider;
    private Slider isolatedLevelSlider;
    private CheckBox isolateLevelCheck;
    private CheckBox ghostModeCheck;
    private CheckBox rayInteractiveCheck;
    private ComboBox<VoxelRenderer.MaterialScheme> materialComboBox;
    private ToggleGroup renderModeGroup;
    private TextArea rayStatsTextArea;

    // Voxel visualization
    private Group voxelGroup;
    private CheckBox showVoxelsCheck;
    private List<Point3i> currentVoxels;
    private int currentResolution;

    // ==================== Abstract Method Implementations ====================

    @Override
    protected String getApplicationTitle() {
        return "ESVO Octree Inspector";
    }

    @Override
    protected String getDataTypeName() {
        return "Octree";
    }

    @Override
    protected ESVOBridge createBridge() {
        return new ESVOBridge();
    }

    @Override
    protected String[] getAvailableShapes() {
        var shapes = ProceduralVoxelGenerator.Shape.values();
        var names = new String[shapes.length];
        for (int i = 0; i < shapes.length; i++) {
            names[i] = ProceduralVoxelGenerator.getShapeName(shapes[i]);
        }
        return names;
    }

    @Override
    protected String getDefaultShape() {
        return ProceduralVoxelGenerator.getShapeName(ProceduralVoxelGenerator.Shape.SPHERE);
    }

    @Override
    protected int getMaxDepth() {
        // ESVO uses 14-bit child pointers, limiting tree size to ~16K nodes
        // Depth 5 with a full cube can exceed this, so limit to 4
        return 5;
    }

    @Override
    protected int getDefaultDepth() {
        // Start with depth 4 for safe default
        return 4;
    }

    @Override
    protected List<Point3i> generateVoxels(String shapeName, int resolution) {
        var shape = ProceduralVoxelGenerator.Shape.valueOf(shapeName.toUpperCase());
        currentResolution = resolution;
        currentVoxels = voxelGenerator.generate(shape, resolution);
        return currentVoxels;
    }

    @Override
    protected Group renderData(ESVOOctreeData data, RenderConfiguration config) {
        // Recreate renderer with current settings
        octreeRenderer = new OctreeRenderer(currentDepth,
            OctreeNodeMeshRenderer.Strategy.BATCHED,
            OctreeRenderer.ColorScheme.DEPTH_GRADIENT,
            true);

        // Apply LOD settings
        Group renderedGroup;
        if (config.isLevelIsolated()) {
            renderedGroup = octreeRenderer.render(data, config.isolateLevel(), config.isolateLevel());
        } else {
            renderedGroup = octreeRenderer.render(data, config.minLevel(), config.maxLevel());
        }

        // Apply ghost mode
        if (config.ghostMode()) {
            renderedGroup.setOpacity(0.3);
        } else {
            renderedGroup.setOpacity(config.opacity());
        }

        return renderedGroup;
    }

    @Override
    protected boolean supportsGPURendering() {
        return false; // ESVO GPU rendering not yet implemented
    }

    @Override
    protected void enableGPUMode() {
        // Not supported yet
        log.info("GPU rendering not yet supported for ESVO");
    }

    @Override
    protected void disableGPUMode() {
        // Nothing to do
    }

    @Override
    protected VBox createDataSpecificControls() {
        var box = new VBox(10);

        // LOD Controls
        var lodPane = new TitledPane("LOD Controls", createLodControls());
        lodPane.setExpanded(true);

        // Voxel Rendering
        var voxelPane = new TitledPane("Voxel Rendering", createVoxelControls());
        voxelPane.setExpanded(false);

        box.getChildren().addAll(lodPane, voxelPane);
        return box;
    }

    @Override
    protected TabPane createAnalysisTabs() {
        var tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Ray Casting tab
        var rayTab = new Tab("Ray Casting");
        rayTab.setContent(createRayCastingPanel());

        tabPane.getTabs().add(rayTab);
        return tabPane;
    }

    // ==================== Initialization ====================

    @Override
    protected void initializeComponents() {
        super.initializeComponents();

        // Initialize octree-specific components
        voxelGenerator = new ProceduralVoxelGenerator();
        octreeRenderer = new OctreeRenderer(currentDepth,
            OctreeNodeMeshRenderer.Strategy.BATCHED,
            OctreeRenderer.ColorScheme.DEPTH_GRADIENT,
            true);
        voxelRenderer = VoxelRenderer.builder()
            .voxelSize(5.0)
            .materialScheme(VoxelRenderer.MaterialScheme.POSITION_GRADIENT)
            .renderMode(VoxelRenderer.RenderMode.FILLED)
            .build();
        rayCastVisualizer = new RayCastVisualizer();

        // Create voxel group
        voxelGroup = new Group();
    }

    @Override
    protected void setupSceneGraph() {
        super.setupSceneGraph();

        // Register voxel group
        sceneManager.registerGroup("voxels", voxelGroup);

        // Add voxel group to world (after content, before ray)
        var world = sceneManager.getWorldGroup();
        var children = world.getChildren();
        int rayIndex = children.indexOf(sceneManager.getRayGroup());
        if (rayIndex >= 0) {
            children.add(rayIndex, voxelGroup);
        } else {
            children.add(voxelGroup);
        }
    }

    @Override
    protected Color getBackgroundColor() {
        return Color.LIGHTGRAY;
    }

    // ==================== LOD Controls ====================

    private VBox createLodControls() {
        var box = new VBox(8);
        box.setPadding(new Insets(5));

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);

        var minLevelLabel = new Label("Min Level:");
        minLevelLabel.setStyle("-fx-text-fill: white;");
        minLevelSlider = new Slider(0, 20, 0);
        minLevelSlider.setShowTickLabels(true);
        minLevelSlider.setShowTickMarks(true);
        minLevelSlider.setMajorTickUnit(5);
        minLevelSlider.setOnMouseReleased(e -> handleLodChanged());

        var maxLevelLabel = new Label("Max Level:");
        maxLevelLabel.setStyle("-fx-text-fill: white;");
        maxLevelSlider = new Slider(0, 20, 20);
        maxLevelSlider.setShowTickLabels(true);
        maxLevelSlider.setShowTickMarks(true);
        maxLevelSlider.setMajorTickUnit(5);
        maxLevelSlider.setOnMouseReleased(e -> handleLodChanged());

        isolateLevelCheck = new CheckBox("Isolate Level");
        isolateLevelCheck.setStyle("-fx-text-fill: white;");
        isolateLevelCheck.setOnAction(e -> handleLodChanged());

        var isolatedLabel = new Label("Isolated Level:");
        isolatedLabel.setStyle("-fx-text-fill: white;");
        isolatedLevelSlider = new Slider(0, 20, 10);
        isolatedLevelSlider.setShowTickLabels(true);
        isolatedLevelSlider.setShowTickMarks(true);
        isolatedLevelSlider.setMajorTickUnit(5);
        isolatedLevelSlider.setOnMouseReleased(e -> handleLodChanged());

        ghostModeCheck = new CheckBox("Ghost Mode");
        ghostModeCheck.setStyle("-fx-text-fill: white;");
        ghostModeCheck.setOnAction(e -> handleLodChanged());

        grid.add(minLevelLabel, 0, 0);
        grid.add(minLevelSlider, 1, 0);
        grid.add(maxLevelLabel, 0, 1);
        grid.add(maxLevelSlider, 1, 1);
        grid.add(isolateLevelCheck, 0, 2, 2, 1);
        grid.add(isolatedLabel, 0, 3);
        grid.add(isolatedLevelSlider, 1, 3);
        grid.add(ghostModeCheck, 0, 4, 2, 1);

        box.getChildren().add(grid);
        return box;
    }

    private void handleLodChanged() {
        if (currentData == null) return;

        // Update render configuration
        int minLevel = (int) minLevelSlider.getValue();
        int maxLevel = (int) maxLevelSlider.getValue();
        int isolatedLevel = (int) isolatedLevelSlider.getValue();
        boolean isolate = isolateLevelCheck.isSelected();
        boolean ghost = ghostModeCheck.isSelected();

        renderConfig = renderConfig
            .withLevelRange(minLevel, maxLevel)
            .withIsolateLevel(isolate ? isolatedLevel : -1, ghost);

        updateVisualization();
    }

    // ==================== Voxel Controls ====================

    private VBox createVoxelControls() {
        var box = new VBox(8);
        box.setPadding(new Insets(5));

        showVoxelsCheck = new CheckBox("Show Voxels (V)");
        showVoxelsCheck.setSelected(true);
        showVoxelsCheck.setStyle("-fx-text-fill: white;");
        showVoxelsCheck.setOnAction(e -> voxelGroup.setVisible(showVoxelsCheck.isSelected()));

        var modeLabel = new Label("Render Mode:");
        modeLabel.setStyle("-fx-text-fill: white;");

        renderModeGroup = new ToggleGroup();
        var filledRadio = new RadioButton("Filled");
        filledRadio.setToggleGroup(renderModeGroup);
        filledRadio.setSelected(true);
        filledRadio.setStyle("-fx-text-fill: white;");
        filledRadio.setOnAction(e -> handleVoxelRenderModeChange(VoxelRenderer.RenderMode.FILLED));

        var wireframeRadio = new RadioButton("Wireframe");
        wireframeRadio.setToggleGroup(renderModeGroup);
        wireframeRadio.setStyle("-fx-text-fill: white;");
        wireframeRadio.setOnAction(e -> handleVoxelRenderModeChange(VoxelRenderer.RenderMode.WIREFRAME));

        var pointsRadio = new RadioButton("Points");
        pointsRadio.setToggleGroup(renderModeGroup);
        pointsRadio.setStyle("-fx-text-fill: white;");
        pointsRadio.setOnAction(e -> handleVoxelRenderModeChange(VoxelRenderer.RenderMode.POINTS));

        var materialLabel = new Label("Material Scheme:");
        materialLabel.setStyle("-fx-text-fill: white;");
        materialComboBox = new ComboBox<>();
        materialComboBox.getItems().addAll(VoxelRenderer.MaterialScheme.values());
        materialComboBox.setValue(VoxelRenderer.MaterialScheme.POSITION_GRADIENT);
        materialComboBox.setMaxWidth(Double.MAX_VALUE);
        materialComboBox.setOnAction(e -> handleMaterialSchemeChange());

        box.getChildren().addAll(
            showVoxelsCheck,
            modeLabel, filledRadio, wireframeRadio, pointsRadio,
            materialLabel, materialComboBox
        );
        return box;
    }

    private void handleVoxelRenderModeChange(VoxelRenderer.RenderMode mode) {
        voxelRenderer = VoxelRenderer.builder()
            .voxelSize(5.0)
            .materialScheme(materialComboBox.getValue())
            .renderMode(mode)
            .build();
        updateVoxelVisualization();
    }

    private void handleMaterialSchemeChange() {
        var currentMode = VoxelRenderer.RenderMode.FILLED;
        if (renderModeGroup.getSelectedToggle() != null) {
            var selected = (RadioButton) renderModeGroup.getSelectedToggle();
            if ("Wireframe".equals(selected.getText())) {
                currentMode = VoxelRenderer.RenderMode.WIREFRAME;
            } else if ("Points".equals(selected.getText())) {
                currentMode = VoxelRenderer.RenderMode.POINTS;
            }
        }

        voxelRenderer = VoxelRenderer.builder()
            .voxelSize(5.0)
            .materialScheme(materialComboBox.getValue())
            .renderMode(currentMode)
            .build();
        updateVoxelVisualization();
    }

    private void updateVoxelVisualization() {
        if (currentVoxels == null || currentVoxels.isEmpty()) return;

        voxelGroup.getChildren().clear();
        var rendered = voxelRenderer.render(currentVoxels, currentResolution);
        voxelGroup.getChildren().add(rendered);
        voxelGroup.setVisible(showVoxelsCheck.isSelected());
    }

    // ==================== Ray Casting ====================

    private VBox createRayCastingPanel() {
        var box = new VBox(8);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #1e1e1e;");

        rayInteractiveCheck = new CheckBox("Interactive Mode (click to cast)");
        rayInteractiveCheck.setStyle("-fx-text-fill: white;");

        rayStatsTextArea = new TextArea();
        rayStatsTextArea.setEditable(false);
        rayStatsTextArea.setStyle("-fx-control-inner-background: #1e1e1e; -fx-text-fill: white; -fx-font-family: monospace;");
        rayStatsTextArea.setText("No ray cast yet.\nCtrl+Click or enable Interactive Mode.");
        rayStatsTextArea.setPrefRowCount(12);

        box.getChildren().addAll(rayInteractiveCheck, rayStatsTextArea);
        return box;
    }

    @Override
    protected void setupKeyboardShortcuts(javafx.scene.Scene scene) {
        super.setupKeyboardShortcuts(scene);

        // Add mouse click handler for ray casting
        subScene.setOnMouseClicked(this::handleMouseClick);
    }

    @Override
    protected void handleCustomKeyPress(javafx.scene.input.KeyCode code) {
        if (code == javafx.scene.input.KeyCode.V) {
            showVoxelsCheck.setSelected(!showVoxelsCheck.isSelected());
            voxelGroup.setVisible(showVoxelsCheck.isSelected());
        }
    }

    private void handleMouseClick(MouseEvent event) {
        boolean isInteractiveMode = rayInteractiveCheck != null && rayInteractiveCheck.isSelected();
        boolean shouldCastRay = event.getButton() == MouseButton.PRIMARY &&
            (isInteractiveMode || event.isControlDown());

        if (!shouldCastRay) return;

        if (currentData == null || !bridge.hasOctree()) {
            rayStatsTextArea.setText("No octree available.\nGenerate an octree first.");
            return;
        }

        // Get camera position and compute ray direction
        var camera = cameraView.getCamera();

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

        var direction = new Vector3f(0.0f, 0.0f, 1.0f); // Forward direction

        try {
            DeepTraversalResult result = bridge.castRay(normalizedOrigin, direction);

            if (result != null) {
                visualizeRayCast(normalizedOrigin, direction, result);
                rayStatsTextArea.setText(formatRayStatistics(result, normalizedOrigin, direction));

                // Auto-enable ray visualization
                if (!sceneManager.isVisible("ray")) {
                    sceneManager.setVisibility("ray", true);
                    showRaysCheck.setSelected(true);
                }
            } else {
                rayStatsTextArea.setText("Ray cast failed.\nNo result returned.");
            }
        } catch (Exception e) {
            log.error("Failed to cast ray", e);
            rayStatsTextArea.setText("Ray cast error: " + e.getMessage());
        }
    }

    private void visualizeRayCast(Vector3f origin, Vector3f direction, DeepTraversalResult result) {
        sceneManager.clearRays();
        float maxDistance = 2.0f;
        Group rayVis = rayCastVisualizer.visualize(origin, direction, result, maxDistance);
        sceneManager.getRayGroup().getChildren().add(rayVis);
    }

    private String formatRayStatistics(DeepTraversalResult result, Vector3f origin, Vector3f direction) {
        var sb = new StringBuilder();

        sb.append("RAY CAST RESULTS\n");
        sb.append("================\n\n");
        sb.append(String.format("Origin:    (%.3f, %.3f, %.3f)\n", origin.x, origin.y, origin.z));
        sb.append(String.format("Direction: (%.3f, %.3f, %.3f)\n", direction.x, direction.y, direction.z));
        sb.append("\n");

        sb.append(String.format("Hit:       %s\n", result.hit ? "YES" : "NO"));

        if (result.hit) {
            sb.append(String.format("Distance:  %.3f\n", result.distance));

            if (result.hitPoint != null) {
                sb.append(String.format("Hit Point: (%.3f, %.3f, %.3f)\n",
                    result.hitPoint.x, result.hitPoint.y, result.hitPoint.z));
            }

            if (result.normal != null) {
                sb.append(String.format("Normal:    (%.3f, %.3f, %.3f)\n",
                    result.normal.x, result.normal.y, result.normal.z));
            }
        }

        sb.append("\n");
        sb.append("TRAVERSAL STATISTICS\n");
        sb.append("====================\n");
        sb.append(String.format("Traversal Depth: %d\n", result.traversalDepth));
        sb.append(String.format("Iterations:      %d\n", result.iterations));

        if (result.hit && result.leafNode >= 0) {
            sb.append(String.format("Leaf Node:       %d\n", result.leafNode));
        }

        return sb.toString();
    }

    // ==================== Override updateVisualization ====================

    @Override
    protected void updateVisualization() {
        super.updateVisualization();

        // Also update voxel visualization
        updateVoxelVisualization();
    }

    // ==================== Launcher ====================

    /**
     * Launcher class for proper JavaFX application startup.
     */
    public static class Launcher {
        public static void main(String[] args) {
            Application.launch(OctreeInspectorApp.class, args);
        }
    }
}

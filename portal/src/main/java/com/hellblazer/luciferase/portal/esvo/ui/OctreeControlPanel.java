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
package com.hellblazer.luciferase.portal.esvo.ui;

import com.hellblazer.luciferase.portal.CameraView;
import com.hellblazer.luciferase.portal.esvo.ProceduralVoxelGenerator;
import com.hellblazer.luciferase.portal.esvo.renderer.VoxelRenderer;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.IntConsumer;
import java.util.function.Consumer;

/**
 * Control panel for the ESVO Octree Inspector application providing UI controls
 * for camera navigation, octree parameters, visualization settings, and shape selection.
 * 
 * @author hal.hildebrand
 */
public class OctreeControlPanel extends VBox {
    
    private final CameraView cameraView;
    private final Runnable resetCamera;
    private final Runnable toggleAxes;
    private final Runnable toggleGrid;
    private final Runnable toggleOctree;
    private final Runnable toggleVoxels;
    private final Runnable toggleRays;
    private final IntConsumer onLevelChanged;
    private final Consumer<ProceduralVoxelGenerator.Shape> onShapeChanged;
    private final Consumer<VoxelRenderer.RenderMode> onRenderModeChanged;
    private final Consumer<VoxelRenderer.MaterialScheme> onMaterialSchemeChanged;
    private final Runnable onCameraPreset;
    
    // Control references for external access
    private CheckBox panModeCheckBox;
    private Slider panSpeedSlider;
    private Slider rotateSpeedSlider;
    private Slider zoomSpeedSlider;
    private CheckBox showAxesCheckBox;
    private CheckBox showGridCheckBox;
    private CheckBox showOctreeCheckBox;
    private CheckBox showVoxelsCheckBox;
    private CheckBox showRaysCheckBox;
    private Slider depthSlider;
    private Label depthValueLabel;
    private ComboBox<ProceduralVoxelGenerator.Shape> shapeSelector;
    private Slider resolutionSlider;
    private Label resolutionValueLabel;
    private Label voxelCountLabel;
    private ToggleGroup renderModeGroup;
    private ComboBox<VoxelRenderer.MaterialScheme> materialSchemeSelector;
    private TextArea performanceMetrics;
    private ProgressIndicator rebuildProgressIndicator;
    private Label rebuildStatusLabel;
    
    public OctreeControlPanel(CameraView cameraView, 
                             Runnable resetCamera,
                             Runnable toggleAxes, 
                             Runnable toggleGrid,
                             Runnable toggleOctree,
                             Runnable toggleVoxels,
                             Runnable toggleRays,
                             IntConsumer onLevelChanged,
                             Consumer<ProceduralVoxelGenerator.Shape> onShapeChanged,
                             Consumer<VoxelRenderer.RenderMode> onRenderModeChanged,
                             Consumer<VoxelRenderer.MaterialScheme> onMaterialSchemeChanged,
                             Runnable onCameraPreset) {
        this.cameraView = cameraView;
        this.resetCamera = resetCamera;
        this.toggleAxes = toggleAxes;
        this.toggleGrid = toggleGrid;
        this.toggleOctree = toggleOctree;
        this.toggleVoxels = toggleVoxels;
        this.toggleRays = toggleRays;
        this.onLevelChanged = onLevelChanged;
        this.onShapeChanged = onShapeChanged;
        this.onRenderModeChanged = onRenderModeChanged;
        this.onMaterialSchemeChanged = onMaterialSchemeChanged;
        this.onCameraPreset = onCameraPreset;
        
        setPadding(new Insets(10));
        setSpacing(10);
        setMinWidth(300);
        setPrefWidth(350);
        setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1;");
        
        buildControls();
    }
    
    private void buildControls() {
        // Title
        Label titleLabel = new Label("ESVO Octree Inspector");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        
        // Octree Parameters Section
        Label octreeLabel = new Label("Octree Parameters");
        octreeLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        Separator octreeSeparator = new Separator();
        
        // Depth slider (1-15 for ESVO)
        GridPane depthPane = new GridPane();
        depthPane.setHgap(10);
        depthPane.setVgap(5);
        
        Label depthLabel = new Label("Depth:");
        depthSlider = new Slider(1, 15, 10);
        depthSlider.setShowTickLabels(true);
        depthSlider.setShowTickMarks(true);
        depthSlider.setMajorTickUnit(2);
        depthSlider.setMinorTickCount(1);
        depthSlider.setSnapToTicks(true);
        depthSlider.setPrefWidth(200);
        
        depthValueLabel = new Label("10");
        depthSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int depth = newVal.intValue();
            depthValueLabel.setText(String.valueOf(depth));
            if (onLevelChanged != null) {
                onLevelChanged.accept(depth);
            }
        });
        
        depthPane.add(depthLabel, 0, 0);
        depthPane.add(depthSlider, 1, 0);
        depthPane.add(depthValueLabel, 2, 0);
        
        // Shape Selection Section
        Separator shapeSeparator = new Separator();
        
        Label shapeLabel = new Label("Shape Selection");
        shapeLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        GridPane shapePane = new GridPane();
        shapePane.setHgap(10);
        shapePane.setVgap(5);
        
        Label shapeTypeLabel = new Label("Shape:");
        shapeSelector = new ComboBox<>();
        shapeSelector.getItems().addAll(ProceduralVoxelGenerator.Shape.values());
        shapeSelector.setValue(ProceduralVoxelGenerator.Shape.SPHERE);
        shapeSelector.setMaxWidth(Double.MAX_VALUE);
        shapeSelector.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ProceduralVoxelGenerator.Shape item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : ProceduralVoxelGenerator.getShapeName(item));
            }
        });
        shapeSelector.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ProceduralVoxelGenerator.Shape item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : ProceduralVoxelGenerator.getShapeName(item));
            }
        });
        shapeSelector.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                updateVoxelCount();
                if (onShapeChanged != null) {
                    onShapeChanged.accept(newVal);
                }
            }
        });
        
        Label resolutionLabel = new Label("Resolution:");
        resolutionSlider = new Slider(16, 128, 64);
        resolutionSlider.setShowTickLabels(true);
        resolutionSlider.setShowTickMarks(true);
        resolutionSlider.setMajorTickUnit(32);
        resolutionSlider.setMinorTickCount(3);
        resolutionSlider.setSnapToTicks(false);
        resolutionSlider.setPrefWidth(200);
        
        resolutionValueLabel = new Label("64");
        resolutionSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int resolution = newVal.intValue();
            resolutionValueLabel.setText(String.valueOf(resolution));
            updateVoxelCount();
        });
        
        Label voxelCountTitleLabel = new Label("Est. Voxels:");
        voxelCountLabel = new Label("138,000");
        voxelCountLabel.setStyle("-fx-font-weight: bold;");
        
        shapePane.add(shapeTypeLabel, 0, 0);
        shapePane.add(shapeSelector, 1, 0, 2, 1);
        shapePane.add(resolutionLabel, 0, 1);
        shapePane.add(resolutionSlider, 1, 1);
        shapePane.add(resolutionValueLabel, 2, 1);
        shapePane.add(voxelCountTitleLabel, 0, 2);
        shapePane.add(voxelCountLabel, 1, 2);
        
        // Initialize voxel count
        updateVoxelCount();
        
        // Rendering Controls Section
        Separator renderingSeparator = new Separator();
        
        Label renderingLabel = new Label("Rendering Controls");
        renderingLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        Label renderModeLabel = new Label("Render Mode:");
        renderModeGroup = new ToggleGroup();
        
        HBox renderModeBox = new HBox(10);
        RadioButton filledButton = new RadioButton("Filled");
        filledButton.setToggleGroup(renderModeGroup);
        filledButton.setSelected(true);
        filledButton.setUserData(VoxelRenderer.RenderMode.FILLED);
        
        RadioButton wireframeButton = new RadioButton("Wireframe");
        wireframeButton.setToggleGroup(renderModeGroup);
        wireframeButton.setUserData(VoxelRenderer.RenderMode.WIREFRAME);
        
        RadioButton pointsButton = new RadioButton("Points");
        pointsButton.setToggleGroup(renderModeGroup);
        pointsButton.setUserData(VoxelRenderer.RenderMode.POINTS);
        
        renderModeBox.getChildren().addAll(filledButton, wireframeButton, pointsButton);
        
        renderModeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && onRenderModeChanged != null) {
                var mode = (VoxelRenderer.RenderMode) newVal.getUserData();
                onRenderModeChanged.accept(mode);
            }
        });
        
        Label materialLabel = new Label("Material:");
        materialSchemeSelector = new ComboBox<>();
        materialSchemeSelector.getItems().addAll(VoxelRenderer.MaterialScheme.values());
        materialSchemeSelector.setValue(VoxelRenderer.MaterialScheme.POSITION_GRADIENT);
        materialSchemeSelector.setMaxWidth(Double.MAX_VALUE);
        materialSchemeSelector.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(VoxelRenderer.MaterialScheme item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                } else {
                    setText(item.name().replace("_", " "));
                }
            }
        });
        materialSchemeSelector.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(VoxelRenderer.MaterialScheme item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                } else {
                    setText(item.name().replace("_", " "));
                }
            }
        });
        materialSchemeSelector.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && onMaterialSchemeChanged != null) {
                onMaterialSchemeChanged.accept(newVal);
            }
        });
        
        // Camera Controls Section
        Separator cameraSeparator = new Separator();
        
        Label cameraLabel = new Label("Camera Controls");
        cameraLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        // Pan Mode
        panModeCheckBox = new CheckBox("Pan Mode (SPACE)");
        panModeCheckBox.setSelected(cameraView.isPanning());
        panModeCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            cameraView.setPanning(newVal);
        });
        
        // Reset Camera Button
        Button resetCameraButton = new Button("Reset Camera (R)");
        resetCameraButton.setMaxWidth(Double.MAX_VALUE);
        resetCameraButton.setOnAction(e -> {
            if (resetCamera != null) resetCamera.run();
            panModeCheckBox.setSelected(cameraView.isPanning());
        });
        
        // Camera Presets
        Label presetsLabel = new Label("Camera Presets:");
        HBox presetsBox = new HBox(5);
        
        Button frontButton = new Button("Front");
        frontButton.setMinWidth(65);
        frontButton.setOnAction(e -> {
            if (onCameraPreset != null) onCameraPreset.run();
        });
        
        Button topButton = new Button("Top");
        topButton.setMinWidth(65);
        topButton.setOnAction(e -> {
            if (onCameraPreset != null) onCameraPreset.run();
        });
        
        Button sideButton = new Button("Side");
        sideButton.setMinWidth(65);
        sideButton.setOnAction(e -> {
            if (onCameraPreset != null) onCameraPreset.run();
        });
        
        Button isoButton = new Button("Iso");
        isoButton.setMinWidth(65);
        isoButton.setOnAction(e -> {
            if (onCameraPreset != null) onCameraPreset.run();
        });
        
        presetsBox.getChildren().addAll(frontButton, topButton, sideButton, isoButton);
        
        // Navigation Speed Controls
        Label speedLabel = new Label("Navigation Speeds");
        speedLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        
        GridPane speedGrid = new GridPane();
        speedGrid.setHgap(10);
        speedGrid.setVgap(5);
        
        // Pan Speed
        Label panSpeedLabel = new Label("Pan:");
        panSpeedSlider = createSpeedSlider(0.1, 5.0, 1.0);
        Label panSpeedValue = new Label(String.format("%.1f", panSpeedSlider.getValue()));
        panSpeedSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            panSpeedValue.setText(String.format("%.1f", newVal.doubleValue()));
            updateNavigationSpeeds();
        });
        
        speedGrid.add(panSpeedLabel, 0, 0);
        speedGrid.add(panSpeedSlider, 1, 0);
        speedGrid.add(panSpeedValue, 2, 0);
        
        // Rotate Speed
        Label rotateSpeedLabel = new Label("Rotate:");
        rotateSpeedSlider = createSpeedSlider(0.1, 5.0, 1.0);
        Label rotateSpeedValue = new Label(String.format("%.1f", rotateSpeedSlider.getValue()));
        rotateSpeedSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            rotateSpeedValue.setText(String.format("%.1f", newVal.doubleValue()));
            updateNavigationSpeeds();
        });
        
        speedGrid.add(rotateSpeedLabel, 0, 1);
        speedGrid.add(rotateSpeedSlider, 1, 1);
        speedGrid.add(rotateSpeedValue, 2, 1);
        
        // Zoom Speed
        Label zoomSpeedLabel = new Label("Zoom:");
        zoomSpeedSlider = createSpeedSlider(1.0, 50.0, 10.0);
        Label zoomSpeedValue = new Label(String.format("%.1f", zoomSpeedSlider.getValue()));
        zoomSpeedSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            zoomSpeedValue.setText(String.format("%.1f", newVal.doubleValue()));
            updateNavigationSpeeds();
        });
        
        speedGrid.add(zoomSpeedLabel, 0, 2);
        speedGrid.add(zoomSpeedSlider, 1, 2);
        speedGrid.add(zoomSpeedValue, 2, 2);
        
        // Display Controls Section
        Separator displaySeparator = new Separator();
        
        Label displayLabel = new Label("Display Controls");
        displayLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        // Show Axes
        showAxesCheckBox = new CheckBox("Show Axes (X)");
        showAxesCheckBox.setSelected(true);
        showAxesCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (toggleAxes != null) toggleAxes.run();
        });
        
        // Show Grid
        showGridCheckBox = new CheckBox("Show Grid (G)");
        showGridCheckBox.setSelected(true);
        showGridCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (toggleGrid != null) toggleGrid.run();
        });
        
        // Show Octree
        showOctreeCheckBox = new CheckBox("Show Octree (O)");
        showOctreeCheckBox.setSelected(false);
        showOctreeCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (toggleOctree != null) toggleOctree.run();
        });
        
        // Show Voxels
        showVoxelsCheckBox = new CheckBox("Show Voxels (V)");
        showVoxelsCheckBox.setSelected(false);
        showVoxelsCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (toggleVoxels != null) toggleVoxels.run();
        });
        
        // Show Rays
        showRaysCheckBox = new CheckBox("Show Rays");
        showRaysCheckBox.setSelected(false);
        showRaysCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (toggleRays != null) toggleRays.run();
        });
        
        // Rebuild Progress Section
        Separator rebuildSeparator = new Separator();
        
        Label rebuildLabel = new Label("Rebuild Status");
        rebuildLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        HBox rebuildBox = new HBox(10);
        rebuildBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        rebuildProgressIndicator = new ProgressIndicator();
        rebuildProgressIndicator.setMaxSize(30, 30);
        rebuildProgressIndicator.setVisible(false);
        
        rebuildStatusLabel = new Label("Ready");
        rebuildStatusLabel.setStyle("-fx-font-weight: bold;");
        
        rebuildBox.getChildren().addAll(rebuildProgressIndicator, rebuildStatusLabel);
        
        // Performance Metrics Section
        Separator performanceSeparator = new Separator();
        
        Label performanceLabel = new Label("Performance Metrics");
        performanceLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        performanceMetrics = new TextArea();
        performanceMetrics.setEditable(false);
        performanceMetrics.setPrefRowCount(6);
        performanceMetrics.setWrapText(true);
        performanceMetrics.setStyle("-fx-font-family: monospace; -fx-font-size: 10; -fx-control-inner-background: #ffffff;");
        performanceMetrics.setText("No metrics available yet.\nGenerate octree to see performance data.");
        
        // Keyboard Shortcuts Section
        Separator shortcutSeparator = new Separator();
        
        Label shortcutLabel = new Label("Keyboard Shortcuts");
        shortcutLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        TextArea shortcutsArea = new TextArea();
        shortcutsArea.setEditable(false);
        shortcutsArea.setPrefRowCount(10);
        shortcutsArea.setWrapText(true);
        shortcutsArea.setText(
            "Movement:\n" +
            "  W/S - Forward/Backward\n" +
            "  A/D - Strafe Left/Right\n" +
            "  Q/E - Up/Down\n" +
            "\n" +
            "Mouse:\n" +
            "  Left Drag - Rotate\n" +
            "  Right Drag - Zoom\n" +
            "  Middle Drag - Pan\n" +
            "  Wheel - Zoom\n" +
            "\n" +
            "Toggles:\n" +
            "  X - Show/Hide Axes\n" +
            "  G - Show/Hide Grid\n" +
            "  O - Show/Hide Octree\n" +
            "  V - Show/Hide Voxels\n" +
            "  R - Reset Camera\n" +
            "\n" +
            "Modifiers:\n" +
            "  SHIFT - Fast movement\n" +
            "  CTRL - Slow movement"
        );
        shortcutsArea.setStyle("-fx-font-family: monospace; -fx-font-size: 10;");
        
        // Add all components to the panel
        getChildren().addAll(
            titleLabel,
            new Separator(),
            octreeLabel,
            octreeSeparator,
            depthPane,
            shapeSeparator,
            shapeLabel,
            shapePane,
            renderingSeparator,
            renderingLabel,
            renderModeLabel,
            renderModeBox,
            materialLabel,
            materialSchemeSelector,
            cameraSeparator,
            cameraLabel,
            panModeCheckBox,
            resetCameraButton,
            presetsLabel,
            presetsBox,
            new Label(), // spacing
            speedLabel,
            speedGrid,
            displaySeparator,
            displayLabel,
            showAxesCheckBox,
            showGridCheckBox,
            showOctreeCheckBox,
            showVoxelsCheckBox,
            showRaysCheckBox,
            rebuildSeparator,
            rebuildLabel,
            rebuildBox,
            performanceSeparator,
            performanceLabel,
            performanceMetrics,
            shortcutSeparator,
            shortcutLabel,
            shortcutsArea
        );
    }
    
    private Slider createSpeedSlider(double min, double max, double value) {
        Slider slider = new Slider(min, max, value);
        slider.setShowTickLabels(false);
        slider.setShowTickMarks(false);
        slider.setPrefWidth(120);
        return slider;
    }
    
    private void updateNavigationSpeeds() {
        cameraView.setNavigationSpeeds(
            panSpeedSlider.getValue(),
            rotateSpeedSlider.getValue(),
            zoomSpeedSlider.getValue()
        );
    }
    
    // Public methods for programmatic control
    
    public void setPanMode(boolean enabled) {
        panModeCheckBox.setSelected(enabled);
    }
    
    public void setShowAxes(boolean show) {
        showAxesCheckBox.setSelected(show);
    }
    
    public void setShowGrid(boolean show) {
        showGridCheckBox.setSelected(show);
    }
    
    public void setShowOctree(boolean show) {
        showOctreeCheckBox.setSelected(show);
    }
    
    public void setShowVoxels(boolean show) {
        showVoxelsCheckBox.setSelected(show);
    }
    
    public void setShowRays(boolean show) {
        showRaysCheckBox.setSelected(show);
    }
    
    public boolean getShowAxesState() {
        return showAxesCheckBox.isSelected();
    }
    
    public boolean getShowGridState() {
        return showGridCheckBox.isSelected();
    }
    
    public boolean getShowOctreeState() {
        return showOctreeCheckBox.isSelected();
    }
    
    public boolean getShowVoxelsState() {
        return showVoxelsCheckBox.isSelected();
    }
    
    public boolean getShowRaysState() {
        return showRaysCheckBox.isSelected();
    }
    
    public int getCurrentDepth() {
        return (int) depthSlider.getValue();
    }
    
    public double getPanSpeed() {
        return panSpeedSlider.getValue();
    }
    
    public double getRotateSpeed() {
        return rotateSpeedSlider.getValue();
    }
    
    public double getZoomSpeed() {
        return zoomSpeedSlider.getValue();
    }
    
    public ProceduralVoxelGenerator.Shape getSelectedShape() {
        return shapeSelector.getValue();
    }
    
    public int getResolution() {
        return (int) resolutionSlider.getValue();
    }
    
    public VoxelRenderer.RenderMode getSelectedRenderMode() {
        var selectedToggle = renderModeGroup.getSelectedToggle();
        if (selectedToggle != null) {
            return (VoxelRenderer.RenderMode) selectedToggle.getUserData();
        }
        return VoxelRenderer.RenderMode.FILLED; // default
    }
    
    public VoxelRenderer.MaterialScheme getSelectedMaterialScheme() {
        return materialSchemeSelector.getValue();
    }
    
    public void updatePerformanceMetrics(String metrics) {
        if (performanceMetrics != null) {
            performanceMetrics.setText(metrics);
        }
    }
    
    /**
     * Update the estimated voxel count label based on current shape and resolution.
     */
    private void updateVoxelCount() {
        var shape = shapeSelector.getValue();
        int resolution = (int) resolutionSlider.getValue();
        
        if (shape != null) {
            int count = ProceduralVoxelGenerator.estimateVoxelCount(shape, resolution);
            voxelCountLabel.setText(String.format("%,d", count));
        }
    }
    
    /**
     * Show the rebuild progress indicator with indeterminate state.
     */
    public void showRebuildProgress() {
        if (rebuildProgressIndicator != null) {
            rebuildProgressIndicator.setVisible(true);
        }
    }
    
    /**
     * Hide the rebuild progress indicator.
     */
    public void hideRebuildProgress() {
        if (rebuildProgressIndicator != null) {
            rebuildProgressIndicator.setVisible(false);
        }
    }
    
    /**
     * Update the rebuild status label.
     * 
     * @param status the status message to display
     */
    public void setRebuildStatus(String status) {
        if (rebuildStatusLabel != null) {
            rebuildStatusLabel.setText(status);
        }
    }
}

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
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.IntConsumer;

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
    
    public OctreeControlPanel(CameraView cameraView, 
                             Runnable resetCamera,
                             Runnable toggleAxes, 
                             Runnable toggleGrid,
                             Runnable toggleOctree,
                             Runnable toggleVoxels,
                             Runnable toggleRays,
                             IntConsumer onLevelChanged) {
        this.cameraView = cameraView;
        this.resetCamera = resetCamera;
        this.toggleAxes = toggleAxes;
        this.toggleGrid = toggleGrid;
        this.toggleOctree = toggleOctree;
        this.toggleVoxels = toggleVoxels;
        this.toggleRays = toggleRays;
        this.onLevelChanged = onLevelChanged;
        
        setPadding(new Insets(10));
        setSpacing(15);
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
            cameraSeparator,
            cameraLabel,
            panModeCheckBox,
            resetCameraButton,
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
}

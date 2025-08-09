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

import com.hellblazer.luciferase.portal.CameraView;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Control panel for the TetreeInspector application providing UI controls
 * for camera navigation, visualization settings, and other parameters.
 *
 * @author hal.hildebrand
 */
public class InspectorControlPanel extends VBox {
    
    private final CameraView cameraView;
    private final Runnable resetCamera;
    private final Runnable toggleAxes;
    private final Runnable toggleGrid;
    private final Runnable toggleVisualization;
    private final java.util.function.IntConsumer onLevelChanged;
    
    // Control references for external access
    private CheckBox panModeCheckBox;
    private Slider panSpeedSlider;
    private Slider rotateSpeedSlider;
    private Slider zoomSpeedSlider;
    private CheckBox showAxesCheckBox;
    private CheckBox showGridCheckBox;
    private CheckBox showVisualizationCheckBox;
    
    public InspectorControlPanel(CameraView cameraView, Runnable resetCamera, 
                                Runnable toggleAxes, Runnable toggleGrid,
                                Runnable toggleVisualization,
                                java.util.function.IntConsumer onLevelChanged) {
        this.cameraView = cameraView;
        this.resetCamera = resetCamera;
        this.toggleAxes = toggleAxes;
        this.toggleGrid = toggleGrid;
        this.toggleVisualization = toggleVisualization;
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
        Label titleLabel = new Label("Inspector Controls");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        
        // Camera Controls Section
        Label cameraLabel = new Label("Camera Controls");
        cameraLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        Separator cameraSeparator = new Separator();
        
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
            // Update pan mode checkbox in case reset changed it
            panModeCheckBox.setSelected(cameraView.isPanning());
        });
        
        // Navigation Speed Controls
        Label speedLabel = new Label("Navigation Speeds");
        speedLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        
        GridPane speedGrid = new GridPane();
        speedGrid.setHgap(10);
        speedGrid.setVgap(5);
        
        // Pan Speed
        Label panSpeedLabel = new Label("Pan Speed:");
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
        Label rotateSpeedLabel = new Label("Rotate Speed:");
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
        Label zoomSpeedLabel = new Label("Zoom Speed:");
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
        
        // Grid Level Control
        Label gridLevelLabel = new Label("Grid Level:");
        Slider gridLevelSlider = new Slider(0, 20, 10);
        gridLevelSlider.setShowTickLabels(true);
        gridLevelSlider.setShowTickMarks(true);
        gridLevelSlider.setMajorTickUnit(5);
        gridLevelSlider.setMinorTickCount(4);
        gridLevelSlider.setSnapToTicks(true);
        gridLevelSlider.setPrefWidth(200);
        
        Label gridLevelValue = new Label("10");
        gridLevelSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int level = newVal.intValue();
            gridLevelValue.setText(String.valueOf(level));
            if (onLevelChanged != null) {
                onLevelChanged.accept(level);
            }
        });
        
        GridPane gridLevelPane = new GridPane();
        gridLevelPane.setHgap(10);
        gridLevelPane.add(gridLevelLabel, 0, 0);
        gridLevelPane.add(gridLevelSlider, 1, 0);
        gridLevelPane.add(gridLevelValue, 2, 0);
        
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
        
        // Show Visualization
        showVisualizationCheckBox = new CheckBox("Show Visualization (V)");
        showVisualizationCheckBox.setSelected(true);
        showVisualizationCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (toggleVisualization != null) toggleVisualization.run();
        });
        
        // Keyboard Shortcuts Section
        Separator shortcutSeparator = new Separator();
        
        Label shortcutLabel = new Label("Keyboard Shortcuts");
        shortcutLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        TextArea shortcutsArea = new TextArea();
        shortcutsArea.setEditable(false);
        shortcutsArea.setPrefRowCount(8);
        shortcutsArea.setWrapText(true);
        shortcutsArea.setText(
            "Movement:\n" +
            "  W/S - Forward/Backward\n" +
            "  A/D - Strafe Left/Right\n" +
            "  Q/E - Up/Down\n" +
            "\n" +
            "Mouse:\n" +
            "  Left Drag - Rotate (or Pan)\n" +
            "  Right Drag - Zoom\n" +
            "  Middle Drag - Pan\n" +
            "  Wheel - Zoom\n" +
            "\n" +
            "Modifiers:\n" +
            "  SHIFT - Fast movement\n" +
            "  CTRL - Slow movement"
        );
        shortcutsArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        
        // Add all components to the panel
        getChildren().addAll(
            titleLabel,
            new Separator(),
            cameraLabel,
            cameraSeparator,
            panModeCheckBox,
            resetCameraButton,
            new Label(), // spacing
            speedLabel,
            speedGrid,
            displaySeparator,
            displayLabel,
            gridLevelPane,
            new Label(), // spacing
            showAxesCheckBox,
            showGridCheckBox,
            showVisualizationCheckBox,
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
    
    public void setShowVisualization(boolean show) {
        showVisualizationCheckBox.setSelected(show);
    }
    
    /**
     * Get the current state of the show axes checkbox.
     */
    public boolean getShowAxesState() {
        return showAxesCheckBox.isSelected();
    }
    
    /**
     * Get the current state of the show grid checkbox.
     */
    public boolean getShowGridState() {
        return showGridCheckBox.isSelected();
    }
    
    public void setShowGrid(boolean show) {
        showGridCheckBox.setSelected(show);
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

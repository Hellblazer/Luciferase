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

import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PointLight;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.transform.Rotate;

import java.util.HashMap;
import java.util.Map;

/**
 * Manager for scene graph groups in inspector applications.
 *
 * <p>Handles creation, organization, and visibility of standard scene groups:
 * <ul>
 *   <li>World group - root container</li>
 *   <li>Axis group - coordinate axes visualization</li>
 *   <li>Grid group - reference grid</li>
 *   <li>Content group - spatial data visualization</li>
 *   <li>Ray group - ray casting visualization</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class SceneGroupManager {

    private final Group worldGroup;
    private final Group axisGroup;
    private final Group gridGroup;
    private final Group contentGroup;
    private final Group rayGroup;

    private final Map<String, Group> namedGroups = new HashMap<>();

    // Default dimensions
    private double axisLength = 200.0;
    private double axisThickness = 2.0;
    private double gridSize = 200.0;
    private int gridLines = 10;

    /**
     * Create a new SceneGroupManager with default configuration.
     */
    public SceneGroupManager() {
        worldGroup = new Group();
        axisGroup = new Group();
        gridGroup = new Group();
        contentGroup = new Group();
        rayGroup = new Group();

        namedGroups.put("world", worldGroup);
        namedGroups.put("axis", axisGroup);
        namedGroups.put("grid", gridGroup);
        namedGroups.put("content", contentGroup);
        namedGroups.put("ray", rayGroup);
    }

    /**
     * Initialize all groups and add them to the world.
     * Sets up standard scene graph hierarchy.
     */
    public void initialize() {
        // Clear any existing content
        worldGroup.getChildren().clear();

        // Add groups in rendering order (back to front)
        worldGroup.getChildren().addAll(gridGroup, axisGroup, contentGroup, rayGroup);

        // Add default lighting
        addDefaultLighting();
    }

    /**
     * Add default lighting to the world group.
     */
    public void addDefaultLighting() {
        var ambientLight = new AmbientLight(Color.WHITE);
        worldGroup.getChildren().add(ambientLight);
    }

    /**
     * Add point light at specified position.
     */
    public void addPointLight(double x, double y, double z, Color color) {
        var pointLight = new PointLight(color);
        pointLight.setTranslateX(x);
        pointLight.setTranslateY(y);
        pointLight.setTranslateZ(z);
        worldGroup.getChildren().add(pointLight);
    }

    /**
     * Build coordinate axes visualization.
     */
    public void buildAxes() {
        axisGroup.getChildren().clear();

        // Create materials
        var redMaterial = new PhongMaterial(Color.RED.brighter());
        redMaterial.setSpecularColor(Color.WHITE);
        redMaterial.setSpecularPower(5);

        var greenMaterial = new PhongMaterial(Color.LIME);
        greenMaterial.setSpecularColor(Color.WHITE);
        greenMaterial.setSpecularPower(5);

        var blueMaterial = new PhongMaterial(Color.CYAN);
        blueMaterial.setSpecularColor(Color.WHITE);
        blueMaterial.setSpecularPower(5);

        // X axis (red)
        var xAxis = new Box(axisLength, axisThickness, axisThickness);
        xAxis.setMaterial(redMaterial);
        axisGroup.getChildren().add(xAxis);

        // Y axis (green)
        var yAxis = new Box(axisThickness, axisLength, axisThickness);
        yAxis.setMaterial(greenMaterial);
        axisGroup.getChildren().add(yAxis);

        // Z axis (blue)
        var zAxis = new Box(axisThickness, axisThickness, axisLength);
        zAxis.setMaterial(blueMaterial);
        axisGroup.getChildren().add(zAxis);

        // Add arrow indicators
        addAxisArrows();

        axisGroup.setVisible(true);
    }

    /**
     * Add arrow indicators at the ends of axes.
     */
    private void addAxisArrows() {
        double distance = axisLength / 2;

        var redMaterial = new PhongMaterial(Color.RED);
        var greenMaterial = new PhongMaterial(Color.GREEN);
        var blueMaterial = new PhongMaterial(Color.BLUE);

        // X axis arrow
        var xArrow = new Cylinder(3, 6);
        xArrow.setMaterial(redMaterial);
        xArrow.setTranslateX(distance);
        xArrow.setRotationAxis(Rotate.Z_AXIS);
        xArrow.setRotate(90);
        axisGroup.getChildren().add(xArrow);

        // Y axis arrow
        var yArrow = new Cylinder(3, 6);
        yArrow.setMaterial(greenMaterial);
        yArrow.setTranslateY(distance);
        axisGroup.getChildren().add(yArrow);

        // Z axis arrow
        var zArrow = new Cylinder(3, 6);
        zArrow.setMaterial(blueMaterial);
        zArrow.setTranslateZ(distance);
        zArrow.setRotationAxis(Rotate.X_AXIS);
        zArrow.setRotate(90);
        axisGroup.getChildren().add(zArrow);
    }

    /**
     * Build reference grid on the XZ plane.
     */
    public void buildGrid() {
        gridGroup.getChildren().clear();

        var gridMaterial = new PhongMaterial(Color.GRAY.deriveColor(0, 1, 1, 0.3));
        double spacing = gridSize / gridLines;

        for (int i = -gridLines / 2; i <= gridLines / 2; i++) {
            double pos = i * spacing;

            // Lines parallel to X axis
            var lineX = new Box(gridSize, 0.5, 0.5);
            lineX.setMaterial(gridMaterial);
            lineX.setTranslateZ(pos);
            gridGroup.getChildren().add(lineX);

            // Lines parallel to Z axis
            var lineZ = new Box(0.5, 0.5, gridSize);
            lineZ.setMaterial(gridMaterial);
            lineZ.setTranslateX(pos);
            gridGroup.getChildren().add(lineZ);
        }

        gridGroup.setVisible(true);
    }

    /**
     * Build a larger grid suitable for wider scenes.
     *
     * @param size Total grid size
     * @param spacing Distance between grid lines
     */
    public void buildGrid(double size, double spacing) {
        gridGroup.getChildren().clear();

        var material = new PhongMaterial(Color.LIGHTGRAY);
        int halfSize = (int) (size / 2);

        for (int i = -halfSize; i <= halfSize; i += (int) spacing) {
            // Lines parallel to X axis
            var lineX = new Cylinder(0.5, size);
            lineX.setMaterial(material);
            lineX.setRotationAxis(Rotate.Z_AXIS);
            lineX.setRotate(90);
            lineX.setTranslateZ(i);
            gridGroup.getChildren().add(lineX);

            // Lines parallel to Z axis
            var lineZ = new Cylinder(0.5, size);
            lineZ.setMaterial(material);
            lineZ.setRotationAxis(Rotate.X_AXIS);
            lineZ.setRotate(90);
            lineZ.setTranslateX(i);
            gridGroup.getChildren().add(lineZ);
        }

        gridGroup.setVisible(true);
    }

    // Group accessors

    public Group getWorldGroup() {
        return worldGroup;
    }

    public Group getAxisGroup() {
        return axisGroup;
    }

    public Group getGridGroup() {
        return gridGroup;
    }

    public Group getContentGroup() {
        return contentGroup;
    }

    public Group getRayGroup() {
        return rayGroup;
    }

    /**
     * Get a group by name.
     *
     * @param name Group name (world, axis, grid, content, ray)
     * @return The group, or null if not found
     */
    public Group getGroup(String name) {
        return namedGroups.get(name);
    }

    /**
     * Register an additional named group.
     *
     * @param name Group name
     * @param group The group to register
     */
    public void registerGroup(String name, Group group) {
        namedGroups.put(name, group);
    }

    // Visibility control

    /**
     * Toggle visibility of a named group.
     *
     * @param name Group name
     * @return New visibility state
     */
    public boolean toggleVisibility(String name) {
        var group = namedGroups.get(name);
        if (group != null) {
            group.setVisible(!group.isVisible());
            return group.isVisible();
        }
        return false;
    }

    /**
     * Set visibility of a named group.
     *
     * @param name Group name
     * @param visible Visibility state
     */
    public void setVisibility(String name, boolean visible) {
        var group = namedGroups.get(name);
        if (group != null) {
            group.setVisible(visible);
        }
    }

    /**
     * Check visibility of a named group.
     *
     * @param name Group name
     * @return true if visible
     */
    public boolean isVisible(String name) {
        var group = namedGroups.get(name);
        return group != null && group.isVisible();
    }

    // Configuration

    /**
     * Set axis dimensions.
     *
     * @param length Axis length
     * @param thickness Axis thickness
     */
    public void setAxisDimensions(double length, double thickness) {
        this.axisLength = length;
        this.axisThickness = thickness;
    }

    /**
     * Set grid dimensions.
     *
     * @param size Grid size
     * @param lines Number of grid lines
     */
    public void setGridDimensions(double size, int lines) {
        this.gridSize = size;
        this.gridLines = lines;
    }

    /**
     * Clear all content from the content group.
     */
    public void clearContent() {
        contentGroup.getChildren().clear();
    }

    /**
     * Clear all content from the ray group.
     */
    public void clearRays() {
        rayGroup.getChildren().clear();
    }
}

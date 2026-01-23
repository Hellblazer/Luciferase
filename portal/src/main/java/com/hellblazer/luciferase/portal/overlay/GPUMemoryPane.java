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
package com.hellblazer.luciferase.portal.overlay;

import javafx.geometry.Insets;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * Displays GPU memory usage with color-coded bar indicator.
 * Color scheme:
 * - Green: < 60% usage
 * - Yellow: 60-80% usage
 * - Red: > 80% usage
 *
 * @author hal.hildebrand
 */
public class GPUMemoryPane extends VBox {
    private static final int BAR_WIDTH = 200;
    private static final int BAR_HEIGHT = 20;
    private static final double GREEN_THRESHOLD = 60.0;
    private static final double YELLOW_THRESHOLD = 80.0;
    private static final long MB = 1024L * 1024L;

    private final Rectangle memoryBar;
    private final Rectangle backgroundBar;
    private final Text percentageLabel;

    private double currentUsagePercent = 0.0;
    private Color currentBarColor = Color.GREEN;

    /**
     * Creates a new GPU memory pane with default styling.
     */
    public GPUMemoryPane() {
        // Create background bar (gray, full width)
        backgroundBar = new Rectangle(BAR_WIDTH, BAR_HEIGHT);
        backgroundBar.setFill(Color.rgb(80, 80, 80));
        backgroundBar.setStroke(Color.WHITE);
        backgroundBar.setStrokeWidth(1);

        // Create foreground bar (colored, variable width)
        memoryBar = new Rectangle(0, BAR_HEIGHT);
        memoryBar.setFill(Color.GREEN);

        // Create percentage label
        percentageLabel = new Text("GPU Memory: 0.0%");
        percentageLabel.setFont(Font.font("Monospace", 12));
        percentageLabel.setFill(Color.WHITE);

        // Layout
        setSpacing(5);
        setPadding(new Insets(5));
        setStyle("-fx-background-color: rgba(0, 0, 0, 0.7);");

        // Stack bars using StackPane for overlay
        var barStack = new javafx.scene.layout.StackPane();
        barStack.getChildren().addAll(backgroundBar, memoryBar);
        javafx.scene.layout.StackPane.setAlignment(memoryBar, javafx.geometry.Pos.CENTER_LEFT);

        getChildren().addAll(percentageLabel, barStack);
    }

    /**
     * Updates memory usage display.
     *
     * @param usedBytes GPU memory currently used (bytes)
     * @param totalBytes Total GPU memory available (bytes)
     */
    public void updateMemoryUsage(long usedBytes, long totalBytes) {
        if (totalBytes <= 0) {
            currentUsagePercent = 0.0;
        } else {
            currentUsagePercent = 100.0 * usedBytes / totalBytes;
        }

        updateBarColor();
        updateBarWidth();
        updateLabel(usedBytes, totalBytes);
    }

    /**
     * Gets the current memory usage percentage.
     *
     * @return Usage percentage (0.0 to 100.0)
     */
    public double getCurrentUsagePercent() {
        return currentUsagePercent;
    }

    /**
     * Gets the memory bar rectangle.
     *
     * @return Memory bar
     */
    public Rectangle getMemoryBar() {
        return memoryBar;
    }

    /**
     * Gets the percentage label.
     *
     * @return Percentage label
     */
    public Text getPercentageLabel() {
        return percentageLabel;
    }

    /**
     * Gets the current bar color based on usage.
     *
     * @return Current bar color
     */
    public Color getBarColor() {
        return currentBarColor;
    }

    /**
     * Updates bar color based on usage percentage.
     */
    private void updateBarColor() {
        if (currentUsagePercent < GREEN_THRESHOLD) {
            currentBarColor = Color.GREEN;
        } else if (currentUsagePercent < YELLOW_THRESHOLD) {
            currentBarColor = Color.YELLOW;
        } else {
            currentBarColor = Color.RED;
        }

        memoryBar.setFill(currentBarColor);
    }

    /**
     * Updates bar width based on usage percentage.
     */
    private void updateBarWidth() {
        var width = BAR_WIDTH * (currentUsagePercent / 100.0);
        memoryBar.setWidth(width);
    }

    /**
     * Updates label with human-readable memory values.
     *
     * @param usedBytes Used memory in bytes
     * @param totalBytes Total memory in bytes
     */
    private void updateLabel(long usedBytes, long totalBytes) {
        var usedMB = usedBytes / (double) MB;
        var totalMB = totalBytes / (double) MB;

        String text;
        if (totalMB >= 1024) {
            // Display in GB
            var usedGB = usedMB / 1024.0;
            var totalGB = totalMB / 1024.0;
            text = String.format("GPU Memory: %.1f / %.1f GB (%.1f%%)",
                usedGB, totalGB, currentUsagePercent);
        } else {
            // Display in MB
            text = String.format("GPU Memory: %.0f / %.0f MB (%.1f%%)",
                usedMB, totalMB, currentUsagePercent);
        }

        percentageLabel.setText(text);
    }
}

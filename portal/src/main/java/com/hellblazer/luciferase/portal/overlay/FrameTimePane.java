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
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * Displays frame time history with line graph visualization.
 * Tracks rolling 60-frame window for FPS calculation.
 *
 * @author hal.hildebrand
 */
public class FrameTimePane extends VBox {
    private static final int ROLLING_WINDOW_SIZE = 60;
    private static final int GRAPH_WIDTH = 240;
    private static final int GRAPH_HEIGHT = 80;
    private static final double NS_TO_MS = 1.0 / 1_000_000.0;

    private final double[] frameTimeHistory = new double[ROLLING_WINDOW_SIZE];
    private final Canvas graph;
    private final Text fpsLabel;
    private int frameIndex = 0;
    private int validFrames = 0;

    /**
     * Creates a new frame time pane with default styling.
     */
    public FrameTimePane() {
        // Create FPS label
        fpsLabel = new Text("FPS: 0.0");
        fpsLabel.setFont(Font.font("Monospace", 14));
        fpsLabel.setFill(Color.WHITE);

        // Create graph canvas
        graph = new Canvas(GRAPH_WIDTH, GRAPH_HEIGHT);

        // Layout
        setSpacing(5);
        setPadding(new Insets(5));
        setStyle("-fx-background-color: rgba(0, 0, 0, 0.7);");

        getChildren().addAll(fpsLabel, graph);

        // Initialize frame time history to zero
        for (int i = 0; i < ROLLING_WINDOW_SIZE; i++) {
            frameTimeHistory[i] = 0.0;
        }
    }

    /**
     * Updates frame time history with a new sample.
     *
     * @param frameTimeNs Frame time in nanoseconds
     */
    public void updateFrameTime(long frameTimeNs) {
        // Convert to milliseconds
        var frameTimeMs = frameTimeNs * NS_TO_MS;

        // Store in rolling window
        frameTimeHistory[frameIndex] = frameTimeMs;
        frameIndex = (frameIndex + 1) % ROLLING_WINDOW_SIZE;

        // Track how many valid frames we have
        if (validFrames < ROLLING_WINDOW_SIZE) {
            validFrames++;
        }

        // Update display
        updateLabel();
        renderGraph();
    }

    /**
     * Gets the current FPS calculated from rolling average.
     *
     * @return Current frames per second
     */
    public double getCurrentFPS() {
        var avgFrameTimeMs = getAverageFrameTimeMs();
        if (avgFrameTimeMs <= 0) {
            return 0.0;
        }
        return 1000.0 / avgFrameTimeMs;
    }

    /**
     * Gets the average frame time in milliseconds from the rolling window.
     *
     * @return Average frame time in ms
     */
    public double getAverageFrameTimeMs() {
        if (validFrames == 0) {
            return 0.0;
        }

        var sum = 0.0;
        for (int i = 0; i < validFrames; i++) {
            sum += frameTimeHistory[i];
        }

        return sum / validFrames;
    }

    /**
     * Gets the graph canvas.
     *
     * @return Graph canvas
     */
    public Canvas getGraph() {
        return graph;
    }

    /**
     * Updates the FPS label with current value.
     */
    private void updateLabel() {
        var fps = getCurrentFPS();
        var avgFrameTime = getAverageFrameTimeMs();
        fpsLabel.setText(String.format("FPS: %.1f (%.2f ms)", fps, avgFrameTime));
    }

    /**
     * Renders the frame time history as a line graph.
     */
    private void renderGraph() {
        var gc = graph.getGraphicsContext2D();
        var width = graph.getWidth();
        var height = graph.getHeight();

        // Clear canvas
        gc.clearRect(0, 0, width, height);

        // Draw background
        gc.setFill(Color.rgb(30, 30, 30));
        gc.fillRect(0, 0, width, height);

        // Draw border
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(1);
        gc.strokeRect(0, 0, width, height);

        if (validFrames < 2) {
            return;  // Need at least 2 points to draw a line
        }

        // Find max frame time for scaling
        var maxFrameTime = 0.0;
        for (int i = 0; i < validFrames; i++) {
            maxFrameTime = Math.max(maxFrameTime, frameTimeHistory[i]);
        }

        // Add 10% headroom
        maxFrameTime *= 1.1;

        if (maxFrameTime <= 0) {
            maxFrameTime = 1.0;  // Avoid division by zero
        }

        // Draw grid lines (every 16.67ms for 60 FPS reference)
        gc.setStroke(Color.rgb(80, 80, 80));
        gc.setLineWidth(0.5);
        var targetFrameTime = 16.67;  // 60 FPS
        if (targetFrameTime < maxFrameTime) {
            var y = height - (targetFrameTime / maxFrameTime) * height;
            gc.strokeLine(0, y, width, y);
        }

        // Draw frame time line
        gc.setStroke(Color.rgb(0, 255, 128));  // Green line
        gc.setLineWidth(2);

        var xStep = width / (double) (ROLLING_WINDOW_SIZE - 1);

        for (int i = 0; i < validFrames - 1; i++) {
            var x1 = i * xStep;
            var y1 = height - (frameTimeHistory[i] / maxFrameTime) * height;

            var x2 = (i + 1) * xStep;
            var y2 = height - (frameTimeHistory[i + 1] / maxFrameTime) * height;

            gc.strokeLine(x1, y1, x2, y2);
        }

        // Draw max frame time label
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("Monospace", 9));
        gc.fillText(String.format("%.1f ms", maxFrameTime), 5, 10);
    }
}

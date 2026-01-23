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

import com.hellblazer.luciferase.esvo.gpu.beam.metrics.CoherenceSnapshot;
import com.hellblazer.luciferase.esvo.gpu.beam.metrics.MetricsSnapshot;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

/**
 * Displays coherence metrics as a color-coded heatmap.
 * Canvas-based rendering with color gradient:
 * - Blue (0.0) -> Green (0.5) -> Yellow (0.75) -> Red (1.0)
 *
 * CRITICAL: getColorForCoherence() must be public for testing.
 *
 * @author hal.hildebrand
 */
public class CoherenceHeatmapPane extends Pane {
    private static final int TILE_SIZE = 16;  // pixels per tile in heatmap
    private static final double BLUE_THRESHOLD = 0.3;
    private static final double GREEN_THRESHOLD = 0.6;
    private static final double YELLOW_THRESHOLD = 0.9;

    private final Canvas canvas;
    private CoherenceSnapshot currentSnapshot;

    /**
     * Creates a new coherence heatmap pane with default canvas size.
     */
    public CoherenceHeatmapPane() {
        this(256, 256);  // Default 256x256 canvas
    }

    /**
     * Creates a new coherence heatmap pane with specified canvas dimensions.
     *
     * @param width Canvas width in pixels
     * @param height Canvas height in pixels
     */
    public CoherenceHeatmapPane(int width, int height) {
        this.canvas = new Canvas(width, height);
        this.currentSnapshot = null;

        // Add canvas to pane
        getChildren().add(canvas);

        // Style the pane
        setStyle("-fx-background-color: rgba(0, 0, 0, 0.7); -fx-padding: 5;");
    }

    /**
     * Updates the heatmap with new metrics.
     * Triggers redraw if snapshot changes.
     *
     * @param snapshot New metrics snapshot
     */
    public void updateMetrics(MetricsSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        this.currentSnapshot = snapshot.coherence();
        renderHeatmap();
    }

    /**
     * Gets the current coherence snapshot.
     * CRITICAL: Used by tests to verify state updates.
     *
     * @return Current snapshot, or null if not yet updated
     */
    public CoherenceSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }

    /**
     * Maps coherence value to color using gradient:
     * - Blue (0.0 to < 0.3)
     * - Green (0.3 to < 0.6)
     * - Yellow (0.6 to < 0.9)
     * - Red (>= 0.9)
     *
     * MUST BE PUBLIC for testing (plan-auditor requirement).
     *
     * @param coherence Coherence value (0.0 to 1.0)
     * @return Color for this coherence level
     */
    public Color getColorForCoherence(double coherence) {
        if (coherence < BLUE_THRESHOLD) {
            return Color.BLUE;
        } else if (coherence < GREEN_THRESHOLD) {
            return Color.GREEN;
        } else if (coherence < YELLOW_THRESHOLD) {
            return Color.YELLOW;
        } else {
            return Color.RED;
        }
    }

    /**
     * Renders the coherence heatmap on the canvas.
     * Uses tiled visualization for spatial coherence distribution.
     */
    private void renderHeatmap() {
        if (currentSnapshot == null) {
            return;
        }

        var gc = canvas.getGraphicsContext2D();
        var width = canvas.getWidth();
        var height = canvas.getHeight();

        // Clear canvas
        gc.clearRect(0, 0, width, height);

        // For initial implementation, render single color based on average coherence
        // TODO: Future enhancement - render per-beam coherence with spatial layout
        var avgColor = getColorForCoherence(currentSnapshot.averageCoherence());

        gc.setFill(avgColor);
        gc.fillRect(0, 0, width, height);

        // Draw border
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2);
        gc.strokeRect(0, 0, width, height);

        // Draw coherence text
        gc.setFill(Color.WHITE);
        gc.setFont(javafx.scene.text.Font.font("Monospace", 14));
        var coherenceText = String.format("Coherence: %.2f", currentSnapshot.averageCoherence());
        gc.fillText(coherenceText, 10, 20);

        // Draw stats
        gc.setFont(javafx.scene.text.Font.font("Monospace", 10));
        var statsText = String.format("Min: %.2f | Max: %.2f | Beams: %d",
            currentSnapshot.minCoherence(),
            currentSnapshot.maxCoherence(),
            currentSnapshot.totalBeams());
        gc.fillText(statsText, 10, 35);
    }
}

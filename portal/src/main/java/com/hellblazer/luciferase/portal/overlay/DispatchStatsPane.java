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

import com.hellblazer.luciferase.esvo.gpu.beam.metrics.DispatchMetrics;
import com.hellblazer.luciferase.esvo.gpu.beam.metrics.MetricsSnapshot;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * Displays kernel dispatch statistics with pie chart visualization.
 * Shows batch vs single-ray dispatch ratio.
 *
 * @author hal.hildebrand
 */
public class DispatchStatsPane extends VBox {
    private static final int PIE_CHART_SIZE = 120;
    private static final Color BATCH_COLOR = Color.rgb(0, 200, 100);      // Green
    private static final Color SINGLE_RAY_COLOR = Color.rgb(255, 100, 50); // Orange

    private final Text batchTilesLabel;
    private final Text singleRayLabel;
    private final Canvas pieChart;
    private DispatchMetrics currentMetrics;

    /**
     * Creates a new dispatch statistics pane with default styling.
     */
    public DispatchStatsPane() {
        // Create labels
        batchTilesLabel = new Text("Batch: 0 (0.0%)");
        batchTilesLabel.setFont(Font.font("Monospace", 12));
        batchTilesLabel.setFill(Color.WHITE);

        singleRayLabel = new Text("Single-Ray: 0 (0.0%)");
        singleRayLabel.setFont(Font.font("Monospace", 12));
        singleRayLabel.setFill(Color.WHITE);

        // Create pie chart canvas
        pieChart = new Canvas(PIE_CHART_SIZE, PIE_CHART_SIZE);

        // Layout
        setSpacing(5);
        setPadding(new Insets(5));
        setStyle("-fx-background-color: rgba(0, 0, 0, 0.7);");

        getChildren().addAll(batchTilesLabel, singleRayLabel, pieChart);

        // Initial state - no metrics
        this.currentMetrics = null;
    }

    /**
     * Updates the dispatch statistics with new metrics.
     *
     * @param snapshot New metrics snapshot
     */
    public void updateMetrics(MetricsSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        this.currentMetrics = snapshot.dispatch();
        updateLabels();
        renderPieChart();
    }

    /**
     * Gets the current dispatch metrics.
     *
     * @return Current metrics, or null if not yet updated
     */
    public DispatchMetrics getCurrentMetrics() {
        return currentMetrics;
    }

    /**
     * Gets the pie chart canvas.
     *
     * @return Pie chart canvas
     */
    public Canvas getCanvas() {
        return pieChart;
    }

    /**
     * Updates text labels with current metrics.
     */
    private void updateLabels() {
        if (currentMetrics == null) {
            return;
        }

        var batch = currentMetrics.batchDispatches();
        var singleRay = currentMetrics.singleRayDispatches();
        var batchPct = currentMetrics.batchPercentage();
        var singleRayPct = 100.0 - batchPct;

        batchTilesLabel.setText(String.format("Batch: %d (%.1f%%)", batch, batchPct));
        singleRayLabel.setText(String.format("Single-Ray: %d (%.1f%%)", singleRay, singleRayPct));
    }

    /**
     * Renders the pie chart showing batch vs single-ray ratio.
     */
    private void renderPieChart() {
        if (currentMetrics == null) {
            return;
        }

        var gc = pieChart.getGraphicsContext2D();
        var width = pieChart.getWidth();
        var height = pieChart.getHeight();

        // Clear canvas
        gc.clearRect(0, 0, width, height);

        // Calculate pie slices
        var total = currentMetrics.totalDispatches();
        if (total == 0) {
            // Draw empty circle
            gc.setFill(Color.GRAY);
            gc.fillOval(10, 10, width - 20, height - 20);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(2);
            gc.strokeOval(10, 10, width - 20, height - 20);
            return;
        }

        var batchAngle = 360.0 * currentMetrics.batchDispatches() / total;
        var singleRayAngle = 360.0 - batchAngle;

        // Draw pie slices
        var centerX = width / 2;
        var centerY = height / 2;
        var radius = Math.min(width, height) / 2 - 10;

        // Batch slice (green)
        gc.setFill(BATCH_COLOR);
        gc.fillArc(centerX - radius, centerY - radius, radius * 2, radius * 2,
            90, -batchAngle, javafx.scene.shape.ArcType.ROUND);

        // Single-ray slice (orange)
        gc.setFill(SINGLE_RAY_COLOR);
        gc.fillArc(centerX - radius, centerY - radius, radius * 2, radius * 2,
            90 - batchAngle, -singleRayAngle, javafx.scene.shape.ArcType.ROUND);

        // Draw border
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2);
        gc.strokeOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
    }
}

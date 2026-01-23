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

import com.hellblazer.luciferase.esvo.gpu.beam.metrics.MetricsSnapshot;
import javafx.geometry.Insets;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Main composite overlay displaying all beam metrics.
 * Combines coherence heatmap, dispatch stats, frame time, and GPU memory panes.
 *
 * CRITICAL: Constructor MUST call setMouseTransparent(true) to allow viewport clicks through.
 *
 * @author hal.hildebrand
 */
public class BeamMetricsOverlay extends StackPane {
    private final CoherenceHeatmapPane heatmap;
    private final DispatchStatsPane stats;
    private final FrameTimePane frameTime;
    private final GPUMemoryPane memory;
    private final VBox content;

    /**
     * Creates a new beam metrics overlay with default position and styling.
     * CRITICAL: Calls setMouseTransparent(true) to allow viewport interaction.
     */
    public BeamMetricsOverlay() {
        // CRITICAL: Allow clicks to pass through to viewport
        setMouseTransparent(true);

        // Create child panes
        heatmap = new CoherenceHeatmapPane();
        stats = new DispatchStatsPane();
        frameTime = new FrameTimePane();
        memory = new GPUMemoryPane();

        // Build composite UI
        content = new VBox(10);  // 10px spacing
        content.setPadding(new Insets(10));
        content.setStyle("-fx-background-color: rgba(0, 0, 0, 0.7);");
        content.getChildren().addAll(heatmap, stats, frameTime, memory);

        // Add to this StackPane
        getChildren().add(content);

        // Default position: top-left
        setPosition(OverlayPosition.TOP_LEFT);

        // Default styling
        setStyle("-fx-padding: 10;");
    }

    /**
     * Updates all child panes with new metrics.
     *
     * @param snapshot New metrics snapshot
     */
    public void updateMetrics(MetricsSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        // Update all child panes
        heatmap.updateMetrics(snapshot);
        stats.updateMetrics(snapshot);
        memory.updateMemoryUsage(snapshot.gpuMemoryUsedBytes(), snapshot.gpuMemoryTotalBytes());

        // Frame time is updated separately via controller or direct calls
        // For testing, we can calculate from avgFrameTimeMs
        var frameTimeNs = (long)(snapshot.avgFrameTimeMs() * 1_000_000.0);
        if (frameTimeNs > 0) {
            frameTime.updateFrameTime(frameTimeNs);
        }
    }

    /**
     * Sets the overlay position in the viewport.
     *
     * @param position Position (TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT)
     */
    public void setPosition(OverlayPosition position) {
        if (position == null) {
            return;
        }

        // Set alignment for this StackPane's child (content VBox)
        StackPane.setAlignment(content, position.getAlignment());
    }

    /**
     * Toggles overlay visibility.
     */
    public void toggleVisibility() {
        setVisible(!isVisible());
    }

    /**
     * Gets the coherence heatmap pane.
     *
     * @return Heatmap pane
     */
    public CoherenceHeatmapPane getHeatmap() {
        return heatmap;
    }

    /**
     * Gets the dispatch stats pane.
     *
     * @return Stats pane
     */
    public DispatchStatsPane getStats() {
        return stats;
    }

    /**
     * Gets the frame time pane.
     *
     * @return Frame time pane
     */
    public FrameTimePane getFrameTime() {
        return frameTime;
    }

    /**
     * Gets the GPU memory pane.
     *
     * @return Memory pane
     */
    public GPUMemoryPane getMemory() {
        return memory;
    }
}

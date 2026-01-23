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
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.function.Supplier;

/**
 * Central controller managing viewport and metrics overlay integration.
 * Wires together metrics source, overlay rendering, and keyboard controls.
 * <p>
 * Keyboard Controls:
 * <ul>
 *   <li>F3 - Toggle overlay visibility</li>
 *   <li>1 - Position overlay at top-left</li>
 *   <li>2 - Position overlay at top-right</li>
 *   <li>3 - Position overlay at bottom-left</li>
 *   <li>4 - Position overlay at bottom-right</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ViewportController {

    private final Supplier<MetricsSnapshot> metricsSource;
    private final BeamMetricsOverlay metricsOverlay;
    private final MetricsOverlayController overlayController;
    private final StackPane root;

    /**
     * Creates a new viewport controller with metrics source.
     *
     * @param metricsSource Supplier that provides current metrics snapshots
     */
    public ViewportController(Supplier<MetricsSnapshot> metricsSource) {
        if (metricsSource == null) {
            throw new IllegalArgumentException("metricsSource cannot be null");
        }

        this.metricsSource = metricsSource;

        // Create overlay components
        this.metricsOverlay = new BeamMetricsOverlay();
        this.overlayController = new MetricsOverlayController(metricsSource);

        // Wire overlay to controller
        this.overlayController.setUpdateCallback(metricsOverlay::updateMetrics);

        // Create root pane with overlay
        this.root = new StackPane();
        this.root.getChildren().add(metricsOverlay);

        // Setup overlay positioning
        StackPane.setAlignment(metricsOverlay, metricsOverlay.getPosition().getAlignment());

        // Setup keyboard bindings
        setupKeyBindings();
    }

    /**
     * Creates a viewport controller with metrics source and additional content.
     *
     * @param metricsSource Supplier that provides current metrics snapshots
     * @param content Content node to place behind overlay
     */
    public ViewportController(Supplier<MetricsSnapshot> metricsSource, Node content) {
        this(metricsSource);
        if (content != null) {
            root.getChildren().add(0, content);  // Add content behind overlay
        }
    }

    /**
     * Gets the root pane containing overlay and content.
     *
     * @return Root pane for scene graph attachment
     */
    public Pane getRoot() {
        return root;
    }

    /**
     * Gets the metrics overlay component.
     *
     * @return Metrics overlay
     */
    public BeamMetricsOverlay getOverlay() {
        return metricsOverlay;
    }

    /**
     * Starts the metrics update timer.
     * Must be called after scene is shown.
     */
    public void start() {
        overlayController.start();
    }

    /**
     * Stops the metrics update timer.
     * Should be called during cleanup.
     */
    public void stop() {
        overlayController.stop();
    }

    /**
     * Checks if the controller is currently running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return overlayController.isRunning();
    }

    /**
     * Sets up keyboard bindings for overlay control.
     */
    private void setupKeyBindings() {
        root.setOnKeyPressed(event -> {
            var code = event.getCode();

            if (code == KeyCode.F3) {
                // Toggle visibility
                metricsOverlay.setVisible(!metricsOverlay.isVisible());
                event.consume();
            } else if (code == KeyCode.DIGIT1) {
                // Top-left
                setOverlayPosition(OverlayPosition.TOP_LEFT);
                event.consume();
            } else if (code == KeyCode.DIGIT2) {
                // Top-right
                setOverlayPosition(OverlayPosition.TOP_RIGHT);
                event.consume();
            } else if (code == KeyCode.DIGIT3) {
                // Bottom-left
                setOverlayPosition(OverlayPosition.BOTTOM_LEFT);
                event.consume();
            } else if (code == KeyCode.DIGIT4) {
                // Bottom-right
                setOverlayPosition(OverlayPosition.BOTTOM_RIGHT);
                event.consume();
            }
        });

        // Make root focusable to receive key events
        root.setFocusTraversable(true);
    }

    /**
     * Sets the overlay position.
     *
     * @param position New overlay position
     */
    private void setOverlayPosition(OverlayPosition position) {
        metricsOverlay.setPosition(position);
        StackPane.setAlignment(metricsOverlay, position.getAlignment());
    }
}

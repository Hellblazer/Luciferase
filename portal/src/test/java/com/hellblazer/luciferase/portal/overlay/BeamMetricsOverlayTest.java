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
import com.hellblazer.luciferase.esvo.gpu.beam.metrics.DispatchMetrics;
import com.hellblazer.luciferase.esvo.gpu.beam.metrics.MetricsSnapshot;
import com.hellblazer.luciferase.portal.JavaFXTestBase;
import javafx.geometry.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BeamMetricsOverlay (T20-T23).
 * Validates main composite overlay with mouse transparency and positioning.
 *
 * @author hal.hildebrand
 */
class BeamMetricsOverlayTest extends JavaFXTestBase {

    /**
     * T20: Test mouse transparent - setMouseTransparent(true) called.
     * CRITICAL: Must be called in constructor to allow viewport clicks through.
     */
    @Test
    void testMouseTransparent() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var overlay = new BeamMetricsOverlay();
            assertTrue(overlay.isMouseTransparent(),
                "Overlay MUST be mouse transparent to allow viewport interaction");
        });
    }

    /**
     * T21: Test integration - All panes render without errors.
     */
    @Test
    void testIntegration() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var overlay = new BeamMetricsOverlay();

            // Verify all child panes exist
            assertNotNull(overlay.getHeatmap(), "Heatmap pane should exist");
            assertNotNull(overlay.getStats(), "Stats pane should exist");
            assertNotNull(overlay.getFrameTime(), "Frame time pane should exist");
            assertNotNull(overlay.getMemory(), "Memory pane should exist");

            // Update with full metrics snapshot
            var coherence = new CoherenceSnapshot(0.75, 0.5, 0.9, 100, 5);
            var dispatch = DispatchMetrics.from(100, 70, 30);
            var snapshot = new MetricsSnapshot(
                60.0, 16.67, 16.0, 17.0,
                coherence,
                dispatch,
                512L * 1024L * 1024L,  // 512 MB
                1024L * 1024L * 1024L,  // 1 GB
                System.nanoTime()
            );

            // Should not throw
            assertDoesNotThrow(() -> overlay.updateMetrics(snapshot));

            // Verify metrics propagated to child panes
            assertEquals(coherence, overlay.getHeatmap().getCurrentSnapshot(),
                "Heatmap should have coherence snapshot");
            assertEquals(dispatch, overlay.getStats().getCurrentMetrics(),
                "Stats should have dispatch metrics");
        });
    }

    /**
     * T22: Test visibility toggle - show/hide works.
     */
    @Test
    void testVisibilityToggle() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var overlay = new BeamMetricsOverlay();

            // Initial state - visible
            assertTrue(overlay.isVisible(), "Overlay should be visible initially");

            // Toggle off
            overlay.toggleVisibility();
            assertFalse(overlay.isVisible(), "Overlay should be hidden after toggle");

            // Toggle on
            overlay.toggleVisibility();
            assertTrue(overlay.isVisible(), "Overlay should be visible after second toggle");
        });
    }

    /**
     * T23: Test positioning - StackPane.setAlignment() works.
     */
    @Test
    void testPositioning() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var overlay = new BeamMetricsOverlay();

            // Test all positions
            overlay.setPosition(OverlayPosition.TOP_LEFT);
            // No direct way to verify alignment, but should not throw
            assertDoesNotThrow(() -> overlay.setPosition(OverlayPosition.TOP_LEFT));

            overlay.setPosition(OverlayPosition.TOP_RIGHT);
            assertDoesNotThrow(() -> overlay.setPosition(OverlayPosition.TOP_RIGHT));

            overlay.setPosition(OverlayPosition.BOTTOM_LEFT);
            assertDoesNotThrow(() -> overlay.setPosition(OverlayPosition.BOTTOM_LEFT));

            overlay.setPosition(OverlayPosition.BOTTOM_RIGHT);
            assertDoesNotThrow(() -> overlay.setPosition(OverlayPosition.BOTTOM_RIGHT));
        });
    }

    /**
     * Test pane initialization - All child panes should be created.
     */
    @Test
    void testPaneInitialization() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var overlay = new BeamMetricsOverlay();

            // Verify structure
            assertNotNull(overlay, "Overlay should be created");
            assertNotNull(overlay.getChildren(), "Should have children");
            assertFalse(overlay.getChildren().isEmpty(), "Should have at least one child");

            // Verify all panes accessible
            assertNotNull(overlay.getHeatmap());
            assertNotNull(overlay.getStats());
            assertNotNull(overlay.getFrameTime());
            assertNotNull(overlay.getMemory());
        });
    }

    /**
     * Test styling - Overlay should have background styling.
     */
    @Test
    void testStyling() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var overlay = new BeamMetricsOverlay();

            // Should have some styling set
            var style = overlay.getStyle();
            assertNotNull(style, "Style should be set");
            // Basic check - style should contain padding or background
            assertTrue(style.contains("padding") || style.contains("background"),
                "Style should contain padding or background");
        });
    }
}

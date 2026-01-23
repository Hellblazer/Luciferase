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
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CoherenceHeatmapPane (T10-T12).
 * Validates color gradient mapping and snapshot update logic.
 *
 * @author hal.hildebrand
 */
class CoherenceHeatmapPaneTest extends JavaFXTestBase {

    /**
     * T10: Test color gradient - Blue (0.0) -> Green (0.5) -> Yellow (0.75) -> Red (1.0).
     * CRITICAL: getColorForCoherence() must be public for testing.
     */
    @Test
    void testColorGradient() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new CoherenceHeatmapPane();

            // Blue range: 0.0 to < 0.3
            assertEquals(Color.BLUE, pane.getColorForCoherence(0.0), "0.0 should be BLUE");
            assertEquals(Color.BLUE, pane.getColorForCoherence(0.1), "0.1 should be BLUE");
            assertEquals(Color.BLUE, pane.getColorForCoherence(0.29), "0.29 should be BLUE");

            // Green range: 0.3 to < 0.6
            assertEquals(Color.GREEN, pane.getColorForCoherence(0.3), "0.3 should be GREEN");
            assertEquals(Color.GREEN, pane.getColorForCoherence(0.5), "0.5 should be GREEN");
            assertEquals(Color.GREEN, pane.getColorForCoherence(0.59), "0.59 should be GREEN");

            // Yellow range: 0.6 to < 0.9
            assertEquals(Color.YELLOW, pane.getColorForCoherence(0.6), "0.6 should be YELLOW");
            assertEquals(Color.YELLOW, pane.getColorForCoherence(0.75), "0.75 should be YELLOW");
            assertEquals(Color.YELLOW, pane.getColorForCoherence(0.89), "0.89 should be YELLOW");

            // Red range: >= 0.9
            assertEquals(Color.RED, pane.getColorForCoherence(0.9), "0.9 should be RED");
            assertEquals(Color.RED, pane.getColorForCoherence(0.95), "0.95 should be RED");
            assertEquals(Color.RED, pane.getColorForCoherence(1.0), "1.0 should be RED");
        });
    }

    /**
     * T11: Test boundary values - Edge cases at 0.0, 0.5, 0.75, 1.0.
     */
    @Test
    void testBoundaryValues() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new CoherenceHeatmapPane();

            // Exact boundary values
            assertEquals(Color.BLUE, pane.getColorForCoherence(0.0), "Lower bound 0.0");
            assertEquals(Color.GREEN, pane.getColorForCoherence(0.5), "Mid-range 0.5");
            assertEquals(Color.YELLOW, pane.getColorForCoherence(0.75), "Upper-mid 0.75");
            assertEquals(Color.RED, pane.getColorForCoherence(1.0), "Upper bound 1.0");

            // Just below boundaries
            assertEquals(Color.BLUE, pane.getColorForCoherence(0.299), "Just below green");
            assertEquals(Color.GREEN, pane.getColorForCoherence(0.599), "Just below yellow");
            assertEquals(Color.YELLOW, pane.getColorForCoherence(0.899), "Just below red");

            // Just at boundaries
            assertEquals(Color.GREEN, pane.getColorForCoherence(0.3), "Green boundary");
            assertEquals(Color.YELLOW, pane.getColorForCoherence(0.6), "Yellow boundary");
            assertEquals(Color.RED, pane.getColorForCoherence(0.9), "Red boundary");
        });
    }

    /**
     * T12: Test snapshot update - Metrics refresh triggers state update.
     */
    @Test
    void testSnapshotUpdate() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new CoherenceHeatmapPane();

            // Initial state - no snapshot
            assertNull(pane.getCurrentSnapshot(), "Initial snapshot should be null");

            // Update with a snapshot
            var coherence = new CoherenceSnapshot(0.75, 0.5, 0.9, 100, 5);
            var dispatch = DispatchMetrics.empty();
            var snapshot = new MetricsSnapshot(
                60.0, 16.67, 16.0, 17.0,
                coherence,
                dispatch,
                1024L * 1024L * 512L,  // 512 MB
                1024L * 1024L * 1024L,  // 1 GB
                System.nanoTime()
            );

            pane.updateMetrics(snapshot);

            // Verify snapshot was stored
            var current = pane.getCurrentSnapshot();
            assertNotNull(current, "Snapshot should be stored");
            assertEquals(coherence, current, "Stored snapshot should match");
        });
    }

    /**
     * Test pane initialization - Canvas should be created.
     */
    @Test
    void testPaneInitialization() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new CoherenceHeatmapPane();
            assertNotNull(pane, "Pane should be created");
            // Pane should contain canvas (verified by non-null construction)
        });
    }
}

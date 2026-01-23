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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DispatchStatsPane (T13-T14).
 * Validates batch/single-ray ratio display and pie chart rendering.
 *
 * @author hal.hildebrand
 */
class DispatchStatsPaneTest extends JavaFXTestBase {

    /**
     * T13: Test batch ratio display - Shows correct batch/single-ray ratio.
     */
    @Test
    void testBatchRatioDisplay() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new DispatchStatsPane();

            // Test 50/50 split
            var dispatch50 = DispatchMetrics.from(100, 50, 50);
            var snapshot50 = createSnapshot(dispatch50);
            pane.updateMetrics(snapshot50);

            var current = pane.getCurrentMetrics();
            assertNotNull(current, "Metrics should be stored");
            assertEquals(50, current.batchDispatches(), "Batch dispatches");
            assertEquals(50, current.singleRayDispatches(), "Single-ray dispatches");
            assertEquals(50.0, current.batchPercentage(), 0.01, "Batch percentage");

            // Test 80/20 split (high batch usage)
            var dispatch80 = DispatchMetrics.from(100, 80, 20);
            var snapshot80 = createSnapshot(dispatch80);
            pane.updateMetrics(snapshot80);

            current = pane.getCurrentMetrics();
            assertEquals(80, current.batchDispatches(), "Batch dispatches");
            assertEquals(20, current.singleRayDispatches(), "Single-ray dispatches");
            assertEquals(80.0, current.batchPercentage(), 0.01, "Batch percentage");

            // Test 20/80 split (low batch usage)
            var dispatch20 = DispatchMetrics.from(100, 20, 80);
            var snapshot20 = createSnapshot(dispatch20);
            pane.updateMetrics(snapshot20);

            current = pane.getCurrentMetrics();
            assertEquals(20, current.batchDispatches(), "Batch dispatches");
            assertEquals(80, current.singleRayDispatches(), "Single-ray dispatches");
            assertEquals(20.0, current.batchPercentage(), 0.01, "Batch percentage");
        });
    }

    /**
     * T14: Test pie chart rendering - Canvas gets updated with dispatch ratio.
     */
    @Test
    void testPieChartRendering() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new DispatchStatsPane();

            // Verify canvas exists
            assertNotNull(pane.getCanvas(), "Canvas should exist");

            // Update with metrics
            var dispatch = DispatchMetrics.from(100, 70, 30);
            var snapshot = createSnapshot(dispatch);
            pane.updateMetrics(snapshot);

            // Verify canvas dimensions are reasonable
            var canvas = pane.getCanvas();
            assertTrue(canvas.getWidth() > 0, "Canvas width should be positive");
            assertTrue(canvas.getHeight() > 0, "Canvas height should be positive");

            // After update, metrics should be stored
            var current = pane.getCurrentMetrics();
            assertNotNull(current, "Metrics should be stored after update");
            assertEquals(70, current.batchDispatches());
            assertEquals(30, current.singleRayDispatches());
        });
    }

    /**
     * Test pane initialization - Labels and canvas should be created.
     */
    @Test
    void testPaneInitialization() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new DispatchStatsPane();
            assertNotNull(pane, "Pane should be created");
            assertNotNull(pane.getCanvas(), "Canvas should be created");
            assertNull(pane.getCurrentMetrics(), "Initial metrics should be null");
        });
    }

    /**
     * Test edge case: zero dispatches.
     */
    @Test
    void testZeroDispatches() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new DispatchStatsPane();

            var dispatch = DispatchMetrics.empty();
            var snapshot = createSnapshot(dispatch);
            pane.updateMetrics(snapshot);

            var current = pane.getCurrentMetrics();
            assertNotNull(current);
            assertEquals(0, current.totalDispatches());
            assertEquals(0.0, current.batchPercentage());
        });
    }

    /**
     * Helper to create a MetricsSnapshot with specific dispatch metrics.
     */
    private MetricsSnapshot createSnapshot(DispatchMetrics dispatch) {
        return new MetricsSnapshot(
            60.0, 16.67, 16.0, 17.0,
            CoherenceSnapshot.empty(),
            dispatch,
            0, 0,
            System.nanoTime()
        );
    }
}

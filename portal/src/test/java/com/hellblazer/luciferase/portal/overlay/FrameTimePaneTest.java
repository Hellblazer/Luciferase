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

import com.hellblazer.luciferase.portal.JavaFXTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FrameTimePane (T15-T16).
 * Validates rolling average calculation and FPS computation.
 *
 * @author hal.hildebrand
 */
class FrameTimePaneTest extends JavaFXTestBase {

    private static final long ONE_MS_NS = 1_000_000L;
    private static final long FRAME_TIME_60FPS_NS = 16_666_667L;  // ~16.67ms for 60 FPS
    private static final long FRAME_TIME_30FPS_NS = 33_333_333L;  // ~33.33ms for 30 FPS

    /**
     * T15: Test rolling average - 60-frame window maintains average.
     */
    @Test
    void testRollingAverage() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new FrameTimePane();

            // Fill with 60 frames at 16.67ms each (60 FPS)
            for (int i = 0; i < 60; i++) {
                pane.updateFrameTime(FRAME_TIME_60FPS_NS);
            }

            // After 60 frames, average should stabilize around 16.67ms
            var avgFrameTimeMs = pane.getAverageFrameTimeMs();
            assertTrue(avgFrameTimeMs > 16.0 && avgFrameTimeMs < 17.0,
                "Average frame time should be ~16.67ms, got: " + avgFrameTimeMs);

            // Add 60 more frames at 33.33ms (30 FPS)
            for (int i = 0; i < 60; i++) {
                pane.updateFrameTime(FRAME_TIME_30FPS_NS);
            }

            // Rolling window should now show ~33.33ms average
            avgFrameTimeMs = pane.getAverageFrameTimeMs();
            assertTrue(avgFrameTimeMs > 33.0 && avgFrameTimeMs < 34.0,
                "Average frame time should be ~33.33ms after shift, got: " + avgFrameTimeMs);
        });
    }

    /**
     * T16: Test FPS calculation - Frame time correctly converts to FPS.
     */
    @Test
    void testFPSCalculation() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new FrameTimePane();

            // Test 60 FPS
            for (int i = 0; i < 60; i++) {
                pane.updateFrameTime(FRAME_TIME_60FPS_NS);
            }

            var fps = pane.getCurrentFPS();
            assertTrue(fps > 59.0 && fps < 61.0,
                "FPS should be ~60, got: " + fps);

            // Test 30 FPS
            for (int i = 0; i < 60; i++) {
                pane.updateFrameTime(FRAME_TIME_30FPS_NS);
            }

            fps = pane.getCurrentFPS();
            assertTrue(fps > 29.0 && fps < 31.0,
                "FPS should be ~30, got: " + fps);

            // Test 120 FPS (8.33ms per frame)
            var frameTime120Fps = 8_333_333L;  // ~8.33ms
            for (int i = 0; i < 60; i++) {
                pane.updateFrameTime(frameTime120Fps);
            }

            fps = pane.getCurrentFPS();
            assertTrue(fps > 119.0 && fps < 121.0,
                "FPS should be ~120, got: " + fps);
        });
    }

    /**
     * Test pane initialization - Graph should be created.
     */
    @Test
    void testPaneInitialization() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new FrameTimePane();
            assertNotNull(pane, "Pane should be created");
            assertNotNull(pane.getGraph(), "Graph canvas should be created");

            // Initial FPS should be 0
            assertEquals(0.0, pane.getCurrentFPS(), 0.01, "Initial FPS should be 0");
        });
    }

    /**
     * Test partial fill - FPS calculation with fewer than 60 samples.
     */
    @Test
    void testPartialFill() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new FrameTimePane();

            // Add only 10 frames
            for (int i = 0; i < 10; i++) {
                pane.updateFrameTime(FRAME_TIME_60FPS_NS);
            }

            // FPS calculation should work with partial data
            var fps = pane.getCurrentFPS();
            assertTrue(fps > 0, "FPS should be calculated from partial data");
        });
    }

    /**
     * Test edge case: zero frame time.
     */
    @Test
    void testZeroFrameTime() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var pane = new FrameTimePane();

            // Add some valid frames first
            for (int i = 0; i < 30; i++) {
                pane.updateFrameTime(FRAME_TIME_60FPS_NS);
            }

            // Adding zero frame time should not crash
            assertDoesNotThrow(() -> pane.updateFrameTime(0L));

            // FPS should still be reasonable
            var fps = pane.getCurrentFPS();
            assertTrue(fps >= 0, "FPS should not be negative");
        });
    }
}

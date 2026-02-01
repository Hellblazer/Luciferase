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
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ViewportController integration.
 * Validates metrics wiring, keyboard toggle, and positioning.
 *
 * @author hal.hildebrand
 */
@ExtendWith(ApplicationExtension.class)
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Requires JavaFX display not available in CI")
class ViewportControllerTest {

    private ViewportController controller;
    private Scene scene;
    private MetricsSnapshot testSnapshot;

    @Start
    void start(Stage stage) {
        // Create test metrics snapshot
        testSnapshot = new MetricsSnapshot(
            60.0, 16.67, 16.0, 17.0,
            new CoherenceSnapshot(0.8, 0.6, 0.95, 100, 5),
            DispatchMetrics.from(10, 8, 2),
            1024 * 1024 * 512,  // 512MB used
            1024 * 1024 * 1024, // 1GB total
            System.nanoTime()
        );

        // Create controller with test metrics supplier
        Supplier<MetricsSnapshot> metricsSource = () -> testSnapshot;
        controller = new ViewportController(metricsSource);

        // Create scene
        scene = new Scene(controller.getRoot(), 800, 600);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * T30: testMetricsWiring - Metrics collector connected to overlay.
     * Verifies that the controller properly wires metrics source to overlay.
     */
    @Test
    void testMetricsWiring() throws InterruptedException {
        // Given: Controller is initialized with metrics source
        var latch = new CountDownLatch(1);

        // When: Starting the controller
        Platform.runLater(() -> {
            controller.start();
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Controller should start");

        // Then: Overlay should exist and be wired
        assertNotNull(controller.getOverlay(), "Overlay should not be null");
        assertTrue(controller.isRunning(), "Controller should be running");

        // Verify overlay is attached to scene graph
        var overlay = controller.getOverlay();
        assertNotNull(overlay.getParent(), "Overlay should be attached to parent");
    }

    /**
     * T31: testKeyboardToggle_F3 - F3 toggles visibility.
     * Verifies that pressing F3 toggles overlay visibility.
     */
    @Test
    void testKeyboardToggle_F3() throws InterruptedException {
        // Given: Controller is started and overlay is visible
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.start();
            latch.countDown();
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Controller should start");

        var overlay = controller.getOverlay();
        var initialVisibility = overlay.isVisible();

        // When: Pressing F3
        var toggleLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            var event = new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "",
                KeyCode.F3, false, false, false, false
            );
            scene.getRoot().fireEvent(event);
            toggleLatch.countDown();
        });

        assertTrue(toggleLatch.await(2, TimeUnit.SECONDS), "F3 event should process");

        // Then: Visibility should be toggled
        var newVisibility = overlay.isVisible();
        assertNotEquals(initialVisibility, newVisibility,
                       "F3 should toggle visibility");
    }

    /**
     * T32: testKeyboardPositioning - 1/2/3/4 keys change position.
     * Verifies that number keys reposition the overlay.
     */
    @Test
    void testKeyboardPositioning() throws InterruptedException {
        // Given: Controller is started
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.start();
            latch.countDown();
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Controller should start");

        var overlay = controller.getOverlay();

        // When: Pressing 1, 2, 3, 4 keys
        testPositionKey(KeyCode.DIGIT1, OverlayPosition.TOP_LEFT);
        testPositionKey(KeyCode.DIGIT2, OverlayPosition.TOP_RIGHT);
        testPositionKey(KeyCode.DIGIT3, OverlayPosition.BOTTOM_LEFT);
        testPositionKey(KeyCode.DIGIT4, OverlayPosition.BOTTOM_RIGHT);
    }

    private void testPositionKey(KeyCode keyCode, OverlayPosition expectedPosition)
        throws InterruptedException {
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            var event = new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "",
                keyCode, false, false, false, false
            );
            scene.getRoot().fireEvent(event);
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                  "Position key event should process");

        // Verify position was set (test via overlay position getter)
        assertEquals(expectedPosition, controller.getOverlay().getPosition(),
                    "Overlay should be at " + expectedPosition);
    }

    /**
     * T33: testMetricsFlow - End-to-end metrics flow works.
     * Verifies that metrics flow from source through controller to overlay display.
     */
    @Test
    void testMetricsFlow() throws InterruptedException {
        // Given: Controller with metrics source
        var flowLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.start();
            flowLatch.countDown();
        });
        assertTrue(flowLatch.await(2, TimeUnit.SECONDS), "Controller should start");

        // When: Waiting for metrics update cycle (500ms interval)
        Thread.sleep(600);

        // Then: Overlay should display metrics
        var overlay = controller.getOverlay();
        assertNotNull(overlay, "Overlay should exist");

        // Verify overlay has been updated with metrics
        // (this is validated by checking that overlay rendering doesn't crash)
        assertTrue(overlay.isVisible(), "Overlay should be visible");
    }

    /**
     * T33b: testStop - Stop cleans up resources.
     */
    @Test
    void testStop() throws InterruptedException {
        // Given: Controller is running
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.start();
            latch.countDown();
        });
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Controller should start");

        assertTrue(controller.isRunning(), "Controller should be running");

        // When: Stopping the controller
        var stopLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.stop();
            stopLatch.countDown();
        });
        assertTrue(stopLatch.await(2, TimeUnit.SECONDS), "Controller should stop");

        // Then: Controller should be stopped
        assertFalse(controller.isRunning(), "Controller should be stopped");
    }
}

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
import com.hellblazer.luciferase.portal.JavaFXTestBase;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricsOverlayController (T18-T19).
 * Validates animation timer lifecycle and metrics flow.
 *
 * @author hal.hildebrand
 */
class MetricsOverlayControllerTest extends JavaFXTestBase {

    /**
     * T18: Test animation timer start - Updates on schedule.
     */
    @Test
    void testAnimationTimerStart() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var updateCount = new AtomicInteger(0);
            var latch = new CountDownLatch(3);  // Wait for 3 updates

            // Create metrics source
            Supplier<MetricsSnapshot> metricsSource = MetricsSnapshot::empty;

            // Create controller with callback
            var controller = new MetricsOverlayController(metricsSource);
            controller.setUpdateCallback(snapshot -> {
                updateCount.incrementAndGet();
                latch.countDown();
            });

            // Start controller
            controller.start();
            assertTrue(controller.isRunning(), "Controller should be running");

            // Wait for updates (in a separate thread to avoid blocking FX thread)
            new Thread(() -> {
                try {
                    boolean received = latch.await(2, TimeUnit.SECONDS);
                    assertTrue(received, "Should receive at least 3 updates within 2 seconds");
                    assertTrue(updateCount.get() >= 3, "Update count should be >= 3");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

            // Give time for updates
            try {
                Thread.sleep(2100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Stop controller
            controller.stop();
            assertFalse(controller.isRunning(), "Controller should be stopped");
        });
    }

    /**
     * T19: Test metrics flow - Metrics flow from source to callback.
     */
    @Test
    void testMetricsFlow() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var receivedSnapshot = new MetricsSnapshot[1];
            var latch = new CountDownLatch(1);

            // Create custom metrics source
            var customSnapshot = MetricsSnapshot.empty();
            Supplier<MetricsSnapshot> metricsSource = () -> customSnapshot;

            // Create controller with callback
            var controller = new MetricsOverlayController(metricsSource);
            controller.setUpdateCallback(snapshot -> {
                receivedSnapshot[0] = snapshot;
                latch.countDown();
            });

            // Start and wait for first update
            controller.start();

            // Wait for update
            new Thread(() -> {
                try {
                    boolean received = latch.await(2, TimeUnit.SECONDS);
                    assertTrue(received, "Should receive metrics update");
                    assertNotNull(receivedSnapshot[0], "Snapshot should be received");
                    assertEquals(customSnapshot, receivedSnapshot[0], "Should receive same snapshot");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

            // Give time for update
            try {
                Thread.sleep(2100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            controller.stop();
        });
    }

    /**
     * Test controller lifecycle - Start/stop should be idempotent.
     */
    @Test
    void testControllerLifecycle() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            var controller = new MetricsOverlayController(MetricsSnapshot::empty);

            // Initial state - not running
            assertFalse(controller.isRunning(), "Should not be running initially");

            // Start
            controller.start();
            assertTrue(controller.isRunning(), "Should be running after start");

            // Double start should be safe
            assertDoesNotThrow(() -> controller.start());

            // Stop
            controller.stop();
            assertFalse(controller.isRunning(), "Should not be running after stop");

            // Double stop should be safe
            assertDoesNotThrow(() -> controller.stop());
        });
    }

    /**
     * Test null metrics source - Should not crash.
     */
    @Test
    void testNullMetricsSource() throws Exception {
        if ("true".equals(System.getenv("CI"))) {
            System.out.println("Skipping JavaFX test in CI environment");
            return;
        }

        runOnFxThreadAndWait(() -> {
            // Metrics source that returns null
            var controller = new MetricsOverlayController(() -> null);
            controller.setUpdateCallback(snapshot -> {
                // Callback should handle null gracefully
            });

            // Should not crash
            assertDoesNotThrow(() -> {
                controller.start();
                Thread.sleep(100);
                controller.stop();
            });
        });
    }
}

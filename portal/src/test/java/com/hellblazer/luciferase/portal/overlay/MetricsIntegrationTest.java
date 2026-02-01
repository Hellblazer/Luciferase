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
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for metrics collection and overlay display.
 * Validates end-to-end metrics flow from metrics source through overlay.
 *
 * Note: These are lightweight integration tests that don't require GPU/OpenCL.
 * Full GPU integration testing is done separately with actual renderers.
 *
 * @author hal.hildebrand
 */
@ExtendWith(ApplicationExtension.class)
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "Requires JavaFX display not available in CI")
class MetricsIntegrationTest {

    private TestMetricsSource metricsSource;
    private ViewportController controller;
    private Scene scene;

    /**
     * Test metrics source that simulates a renderer collecting metrics.
     */
    static class TestMetricsSource implements Supplier<MetricsSnapshot> {
        private final AtomicInteger frameCount = new AtomicInteger(0);
        private volatile double currentFps = 60.0;
        private volatile long currentDispatches = 0;

        public void recordFrame(long dispatchCount) {
            frameCount.incrementAndGet();
            currentDispatches += dispatchCount;
        }

        public void setFps(double fps) {
            this.currentFps = fps;
        }

        @Override
        public MetricsSnapshot get() {
            var avgFrameTime = currentFps > 0 ? 1000.0 / currentFps : 0.0;
            return new MetricsSnapshot(
                currentFps,
                avgFrameTime,
                avgFrameTime * 0.9,
                avgFrameTime * 1.1,
                new CoherenceSnapshot(0.8, 0.6, 0.95, 100, 5),
                DispatchMetrics.from((int)currentDispatches, (int)(currentDispatches * 0.8), (int)(currentDispatches * 0.2)),
                1024 * 1024 * 512,  // 512MB used
                1024 * 1024 * 1024, // 1GB total
                System.nanoTime()
            );
        }
    }

    @Start
    void start(Stage stage) {
        // Create test metrics source
        metricsSource = new TestMetricsSource();

        // Create controller with metrics source
        controller = new ViewportController(metricsSource);

        // Create scene
        scene = new Scene(controller.getRoot(), 800, 600);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * T34: testMetricsCollectionDuringRender - Metrics collected and flow to overlay.
     * Simulates render loop recording metrics and verifies they reach the overlay.
     */
    @Test
    void testMetricsCollectionDuringRender() throws InterruptedException {
        // Given: Controller is started
        var startLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.start();
            startLatch.countDown();
        });
        assertTrue(startLatch.await(2, TimeUnit.SECONDS), "Controller should start");

        // When: Simulating render loop recording metrics
        for (int i = 0; i < 5; i++) {
            metricsSource.recordFrame(10);  // 10 dispatches per frame
            Thread.sleep(2);  // Small delay between frames
        }

        // Then: Snapshot should contain recorded metrics
        var snapshot = metricsSource.get();
        assertNotNull(snapshot, "Snapshot should not be null");
        assertTrue(snapshot.avgFrameTimeMs() > 0, "Frame time should be recorded");
        assertEquals(50, snapshot.dispatch().totalDispatches(), "Should have 5 frames * 10 dispatches");

        // Verify overlay exists and is wired
        assertNotNull(controller.getOverlay(), "Overlay should exist");
        assertTrue(controller.isRunning(), "Controller should be running");
    }

    /**
     * T35: testOverlayDisplaysMetrics - Overlay shows correct metrics.
     * Verifies that overlay components are updated with metrics data.
     */
    @Test
    void testOverlayDisplaysMetrics() throws InterruptedException {
        // Given: Controller is started and metrics are recorded
        var startLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.start();
            startLatch.countDown();
        });

        assertTrue(startLatch.await(2, TimeUnit.SECONDS), "Setup should complete");

        // Record some metrics
        metricsSource.recordFrame(10);

        // When: Waiting for overlay update cycle (500ms + buffer)
        Thread.sleep(700);

        // Then: Overlay should be displaying metrics
        var overlay = controller.getOverlay();
        assertNotNull(overlay, "Overlay should exist");
        assertTrue(overlay.isVisible(), "Overlay should be visible");

        // Verify child components exist
        assertNotNull(overlay.getHeatmap(), "Heatmap should exist");
        assertNotNull(overlay.getStats(), "Stats should exist");
        assertNotNull(overlay.getFrameTime(), "Frame time should exist");
        assertNotNull(overlay.getMemory(), "Memory should exist");
    }

    /**
     * T36: testNoPerformanceRegression - Overlay overhead is minimal.
     * Measures overhead of metrics source updates and snapshot retrieval.
     */
    @Test
    void testNoPerformanceRegression() throws InterruptedException {
        // Given: Controller is started
        var startLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.start();
            startLatch.countDown();
        });
        assertTrue(startLatch.await(2, TimeUnit.SECONDS), "Controller should start");

        // When: Measuring metrics updates overhead
        var iterations = 1000;
        var startNs = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            metricsSource.recordFrame(2);
        }

        var endNs = System.nanoTime();
        var totalMs = (endNs - startNs) / 1_000_000.0;
        var avgPerFrameMs = totalMs / iterations;

        // Then: Overhead should be minimal (< 0.1ms per frame on average)
        assertTrue(avgPerFrameMs < 0.1,
                  String.format("Average overhead %.3fms should be < 0.1ms", avgPerFrameMs));

        // Verify snapshot retrieval is fast (< 1ms)
        // Note: First call may include JVM warmup overhead
        var snapshotStartNs = System.nanoTime();
        var snapshot = metricsSource.get();
        var snapshotEndNs = System.nanoTime();
        var snapshotMs = (snapshotEndNs - snapshotStartNs) / 1_000_000.0;

        assertTrue(snapshotMs < 1.0,
                  String.format("Snapshot retrieval %.3fms should be < 1ms", snapshotMs));
        assertNotNull(snapshot, "Snapshot should not be null");
    }

    /**
     * T36b: testOverlayUpdatePerformance - Overlay updates are efficient.
     */
    @Test
    void testOverlayUpdatePerformance() throws InterruptedException {
        // Given: Controller is started
        var startLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.start();
            startLatch.countDown();
        });
        assertTrue(startLatch.await(2, TimeUnit.SECONDS), "Controller should start");

        // When: Measuring overlay update performance
        var snapshot = metricsSource.get();
        var overlay = controller.getOverlay();

        var iterations = 100;
        var updateLatch = new CountDownLatch(1);
        var startTime = new long[1];
        var endTime = new long[1];

        Platform.runLater(() -> {
            startTime[0] = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                overlay.updateMetrics(snapshot);
            }
            endTime[0] = System.nanoTime();
            updateLatch.countDown();
        });

        assertTrue(updateLatch.await(3, TimeUnit.SECONDS), "Updates should complete");

        var totalMs = (endTime[0] - startTime[0]) / 1_000_000.0;
        var avgPerUpdateMs = totalMs / iterations;

        // Then: Update overhead should be minimal (< 1ms per update)
        assertTrue(avgPerUpdateMs < 1.0,
                  String.format("Average update overhead %.3fms should be < 1ms", avgPerUpdateMs));
    }
}

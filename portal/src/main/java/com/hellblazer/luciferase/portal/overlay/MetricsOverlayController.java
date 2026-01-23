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
import javafx.animation.AnimationTimer;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages the lifecycle of metrics overlay updates.
 * Uses AnimationTimer for smooth, frame-synchronized updates.
 *
 * @author hal.hildebrand
 */
public class MetricsOverlayController {
    private static final long UPDATE_INTERVAL_NS = 500_000_000L;  // 500ms (2 Hz)

    private final Supplier<MetricsSnapshot> metricsSource;
    private AnimationTimer updateTimer;
    private Consumer<MetricsSnapshot> updateCallback;
    private long lastUpdate = 0;

    /**
     * Creates a new metrics overlay controller.
     *
     * @param metricsSource Supplier that provides current metrics
     */
    public MetricsOverlayController(Supplier<MetricsSnapshot> metricsSource) {
        if (metricsSource == null) {
            throw new IllegalArgumentException("metricsSource cannot be null");
        }
        this.metricsSource = metricsSource;
    }

    /**
     * Sets the callback for metrics updates.
     *
     * @param callback Consumer that receives metrics snapshots
     */
    public void setUpdateCallback(Consumer<MetricsSnapshot> callback) {
        this.updateCallback = callback;
    }

    /**
     * Starts the metrics update timer.
     * Safe to call multiple times - will stop existing timer first.
     */
    public void start() {
        // Stop existing timer if running
        if (updateTimer != null) {
            updateTimer.stop();
        }

        // Create new timer
        updateTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Update at fixed interval
                if (now - lastUpdate >= UPDATE_INTERVAL_NS) {
                    updateMetrics();
                    lastUpdate = now;
                }
            }
        };

        // Start timer
        updateTimer.start();
        lastUpdate = System.nanoTime();
    }

    /**
     * Stops the metrics update timer.
     * Safe to call multiple times.
     */
    public void stop() {
        if (updateTimer != null) {
            updateTimer.stop();
            updateTimer = null;
        }
    }

    /**
     * Checks if the controller is currently running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return updateTimer != null;
    }

    /**
     * Fetches metrics from source and invokes callback.
     */
    private void updateMetrics() {
        try {
            // Get metrics from source
            var snapshot = metricsSource.get();

            // Invoke callback if set and snapshot is non-null
            if (updateCallback != null && snapshot != null) {
                updateCallback.accept(snapshot);
            }
        } catch (Exception e) {
            // Log error but don't crash the timer
            System.err.println("Error updating metrics: " + e.getMessage());
        }
    }
}

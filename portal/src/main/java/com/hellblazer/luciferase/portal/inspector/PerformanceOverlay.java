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
package com.hellblazer.luciferase.portal.inspector;

import javafx.animation.AnimationTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Performance monitoring overlay for inspector applications.
 *
 * <p>Tracks and reports:
 * <ul>
 *   <li>Frame rate (FPS)</li>
 *   <li>Memory usage</li>
 *   <li>Custom metrics via callbacks</li>
 * </ul>
 *
 * <p>Uses an AnimationTimer for accurate frame timing.
 *
 * @author hal.hildebrand
 */
public class PerformanceOverlay {

    private static final Logger log = LoggerFactory.getLogger(PerformanceOverlay.class);

    /**
     * Performance metrics snapshot.
     */
    public record Metrics(
        double fps,
        double usedMemoryMB,
        double maxMemoryMB,
        long frameCount
    ) {
        public double memoryUsagePercent() {
            return maxMemoryMB > 0 ? (usedMemoryMB / maxMemoryMB) * 100 : 0;
        }
    }

    private AnimationTimer timer;
    private Consumer<Metrics> metricsCallback;
    private Runnable frameCallback;

    // FPS calculation
    private long lastUpdate = 0;
    private long frameCount = 0;
    private double currentFPS = 0.0;

    // Configuration
    private long updateIntervalNs = 500_000_000L; // 500ms default

    /**
     * Set the callback for metrics updates.
     *
     * @param callback Consumer that receives Metrics snapshots
     */
    public void setMetricsCallback(Consumer<Metrics> callback) {
        this.metricsCallback = callback;
    }

    /**
     * Set a callback that is invoked every frame.
     * Use for recording or other per-frame operations.
     *
     * @param callback Runnable to call each frame
     */
    public void setFrameCallback(Runnable callback) {
        this.frameCallback = callback;
    }

    /**
     * Set the update interval for metrics callbacks.
     *
     * @param intervalMs Update interval in milliseconds
     */
    public void setUpdateIntervalMs(long intervalMs) {
        this.updateIntervalNs = intervalMs * 1_000_000L;
    }

    /**
     * Start performance monitoring.
     */
    public void start() {
        if (timer != null) {
            timer.stop();
        }

        timer = new AnimationTimer() {
            private long[] frameTimes = new long[60];
            private int frameIndex = 0;

            @Override
            public void handle(long now) {
                frameCount++;

                // Track frame time for FPS calculation
                frameTimes[frameIndex] = now;
                frameIndex = (frameIndex + 1) % frameTimes.length;

                // Invoke frame callback
                if (frameCallback != null) {
                    frameCallback.run();
                }

                // Update metrics periodically
                if (now - lastUpdate >= updateIntervalNs) {
                    // Calculate FPS from frame times
                    int validFrames = 0;
                    long oldest = Long.MAX_VALUE;
                    long newest = Long.MIN_VALUE;

                    for (long frameTime : frameTimes) {
                        if (frameTime > 0) {
                            validFrames++;
                            oldest = Math.min(oldest, frameTime);
                            newest = Math.max(newest, frameTime);
                        }
                    }

                    if (validFrames > 1 && newest > oldest) {
                        double elapsed = (newest - oldest) / 1_000_000_000.0;
                        currentFPS = (validFrames - 1) / elapsed;
                    }

                    // Calculate memory
                    var runtime = Runtime.getRuntime();
                    double usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
                    double maxMB = runtime.maxMemory() / (1024.0 * 1024.0);

                    // Invoke callback
                    if (metricsCallback != null) {
                        metricsCallback.accept(new Metrics(currentFPS, usedMB, maxMB, frameCount));
                    }

                    lastUpdate = now;
                }
            }
        };

        timer.start();
        log.info("Performance monitoring started");
    }

    /**
     * Stop performance monitoring.
     */
    public void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
            log.info("Performance monitoring stopped");
        }
    }

    /**
     * Check if monitoring is active.
     *
     * @return true if monitoring is running
     */
    public boolean isRunning() {
        return timer != null;
    }

    /**
     * Get the current FPS.
     *
     * @return Current frames per second
     */
    public double getCurrentFPS() {
        return currentFPS;
    }

    /**
     * Get the total frame count.
     *
     * @return Number of frames since monitoring started
     */
    public long getFrameCount() {
        return frameCount;
    }

    /**
     * Get current memory usage in MB.
     *
     * @return Used heap memory in megabytes
     */
    public double getUsedMemoryMB() {
        var runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
    }

    /**
     * Get maximum available memory in MB.
     *
     * @return Maximum heap memory in megabytes
     */
    public double getMaxMemoryMB() {
        return Runtime.getRuntime().maxMemory() / (1024.0 * 1024.0);
    }

    /**
     * Get a snapshot of current metrics.
     *
     * @return Current Metrics
     */
    public Metrics getMetrics() {
        return new Metrics(currentFPS, getUsedMemoryMB(), getMaxMemoryMB(), frameCount);
    }
}

/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Measures GC pause times with high-precision polling-based detection.
 * <p>
 * Uses a high-priority polling thread that samples GC collection counts at
 * regular intervals (1ms default). When a collection count delta is detected,
 * a GC pause is recorded.
 * <p>
 * Provides percentile statistics (p50, p95, p99) and pause frequency metrics.
 * <p>
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 *
 * @author hal.hildebrand
 */
public class GCPauseMeasurement {

    private static final Logger log = LoggerFactory.getLogger(GCPauseMeasurement.class);
    private static final int DEFAULT_POLL_INTERVAL_MS = 1;

    private final long pollIntervalMs;
    private final CopyOnWriteArrayList<Long> pauseDurations = new CopyOnWriteArrayList<>();
    private volatile Thread pollingThread;
    private volatile boolean running;
    private long measurementStartTime;

    /**
     * Creates a GC pause measurement instance with default 1ms polling interval.
     */
    public GCPauseMeasurement() {
        this(DEFAULT_POLL_INTERVAL_MS);
    }

    /**
     * Creates a GC pause measurement instance with specified polling interval.
     *
     * @param pollIntervalMs polling interval in milliseconds
     */
    public GCPauseMeasurement(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Starts GC pause measurement.
     */
    public synchronized void start() {
        if (running) {
            throw new IllegalStateException("Measurement already running");
        }

        running = true;
        measurementStartTime = System.currentTimeMillis();
        pauseDurations.clear();

        // Start high-priority polling thread
        pollingThread = new Thread(this::pollGCPauses, "gc-pause-measurer");
        pollingThread.setPriority(Thread.MAX_PRIORITY);
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    /**
     * Stops GC pause measurement.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }

        running = false;
        if (pollingThread != null) {
            try {
                pollingThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Gets the measurement duration in milliseconds.
     *
     * @return duration in ms
     */
    public long getDurationMs() {
        return System.currentTimeMillis() - measurementStartTime;
    }

    /**
     * Gets the number of GC pauses detected.
     *
     * @return pause count
     */
    public int getPauseCount() {
        return pauseDurations.size();
    }

    /**
     * Gets the p50 (median) GC pause duration in milliseconds.
     *
     * @return p50 pause duration in ms
     */
    public long p50Ms() {
        return getPercentile(0.50);
    }

    /**
     * Gets the p95 GC pause duration in milliseconds.
     *
     * @return p95 pause duration in ms
     */
    public long p95Ms() {
        return getPercentile(0.95);
    }

    /**
     * Gets the p99 GC pause duration in milliseconds.
     *
     * @return p99 pause duration in ms
     */
    public long p99Ms() {
        return getPercentile(0.99);
    }

    /**
     * Gets the maximum GC pause duration detected.
     *
     * @return maximum pause duration in ms
     */
    public long maxMs() {
        if (pauseDurations.isEmpty()) {
            return 0;
        }
        return Collections.max(pauseDurations);
    }

    /**
     * Gets the average GC pause duration.
     *
     * @return average pause duration in ms
     */
    public double averageMs() {
        if (pauseDurations.isEmpty()) {
            return 0;
        }
        return pauseDurations.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    /**
     * Gets pause frequency (pauses per second).
     *
     * @return pauses per second
     */
    public double getPauseFrequency() {
        var durationSecs = getDurationMs() / 1000.0;
        if (durationSecs == 0) {
            return 0;
        }
        return pauseDurations.size() / durationSecs;
    }

    /**
     * Gets GC pause statistics.
     *
     * @return statistics record
     */
    public GCPauseStats getStats() {
        return new GCPauseStats(
            pauseDurations.size(),
            p50Ms(),
            p95Ms(),
            p99Ms(),
            maxMs(),
            averageMs(),
            getPauseFrequency(),
            getDurationMs()
        );
    }

    private long getPercentile(double percentile) {
        if (pauseDurations.isEmpty()) {
            return 0;
        }

        var sorted = new ArrayList<>(pauseDurations);
        Collections.sort(sorted);

        var index = (int)Math.ceil(sorted.size() * percentile) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    private void pollGCPauses() {
        try {
            var gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            var lastCollectionCounts = new long[gcBeans.size()];

            // Initialize baseline
            for (int i = 0; i < gcBeans.size(); i++) {
                lastCollectionCounts[i] = gcBeans.get(i).getCollectionCount();
            }

            var lastPollTime = System.currentTimeMillis();

            while (running) {
                var now = System.currentTimeMillis();
                var timeSinceLastPoll = now - lastPollTime;

                if (timeSinceLastPoll >= pollIntervalMs) {
                    // Check for GC activity
                    for (int i = 0; i < gcBeans.size(); i++) {
                        var bean = gcBeans.get(i);
                        var currentCount = bean.getCollectionCount();

                        if (currentCount > lastCollectionCounts[i]) {
                            // GC was triggered - record pause duration based on elapsed time
                            // Note: This is an approximation - actual pause may be shorter
                            pauseDurations.add(timeSinceLastPoll);
                            lastCollectionCounts[i] = currentCount;
                        }
                    }

                    lastPollTime = now;
                }

                // Sleep briefly to avoid busy-waiting
                Thread.sleep(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Error in GC pause measurement", e);
        }
    }

    /**
     * GC pause statistics record.
     */
    public record GCPauseStats(
        int pauseCount,
        long p50Ms,
        long p95Ms,
        long p99Ms,
        long maxMs,
        double averageMs,
        double pauseFrequency,
        long durationMs
    ) {
    }
}

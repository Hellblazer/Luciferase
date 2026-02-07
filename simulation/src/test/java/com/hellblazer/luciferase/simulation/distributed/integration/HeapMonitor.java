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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors heap memory usage over time to detect memory leaks through linear
 * growth analysis.
 * <p>
 * Thread-safe: Uses concurrent data structures and scheduled executor.
 *
 * @author hal.hildebrand
 */
public class HeapMonitor {

    private final MemoryMXBean               memoryMXBean;
    private final CopyOnWriteArrayList<MemorySnapshot> snapshots;
    private       ScheduledExecutorService   executor;
    private       long                       peakMemory;
    private volatile Clock clock = Clock.system();

    /**
     * Set the clock for deterministic testing.
     *
     * @param clock Clock instance to use
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Creates a new heap monitor.
     */
    public HeapMonitor() {
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.snapshots = new CopyOnWriteArrayList<>();
        this.peakMemory = 0;
    }

    /**
     * Starts periodic memory snapshots.
     *
     * @param intervalMs snapshot interval in milliseconds
     */
    public void start(long intervalMs) {
        if (executor != null && !executor.isShutdown()) {
            throw new IllegalStateException("Monitor already running");
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "HeapMonitor");
            thread.setDaemon(true);
            return thread;
        });

        executor.scheduleAtFixedRate(this::takeSnapshot, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops monitoring and releases resources.
     */
    public void stop() {
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns true if the monitor is currently running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    /**
     * Returns the peak heap memory observed.
     *
     * @return peak memory in bytes
     */
    public long getPeakMemory() {
        return peakMemory;
    }

    /**
     * Returns the current heap memory usage.
     *
     * @return current heap usage in bytes
     */
    public long getCurrentMemory() {
        return memoryMXBean.getHeapMemoryUsage().getUsed();
    }

    /**
     * Calculates the memory growth rate using linear regression.
     *
     * @return growth rate in bytes per second
     */
    public long getGrowthRate() {
        if (snapshots.size() < 2) {
            return 0;
        }

        // Linear regression: y = mx + b, where m is the slope
        var n = snapshots.size();
        long sumX = 0;
        long sumY = 0;
        long sumXY = 0;
        long sumX2 = 0;

        var firstTime = snapshots.get(0).timestamp();

        for (var snapshot : snapshots) {
            var x = snapshot.timestamp() - firstTime; // Time offset in ms
            var y = snapshot.heapUsage();

            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        var denominator = n * sumX2 - sumX * sumX;
        if (denominator == 0) {
            return 0;
        }

        // Slope in bytes per millisecond
        var slope = (double) (n * sumXY - sumX * sumY) / denominator;

        // Convert to bytes per second
        return (long) (slope * 1000);
    }

    /**
     * Returns all collected memory snapshots.
     *
     * @return list of snapshots in chronological order
     */
    public List<MemorySnapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    /**
     * Detects if there is a memory leak based on growth rate threshold.
     *
     * @param bytesPerSecond the threshold in bytes per second
     * @return true if growth rate exceeds threshold
     */
    public boolean hasLeak(double bytesPerSecond) {
        return getGrowthRate() > bytesPerSecond;
    }

    private void takeSnapshot() {
        var heapUsage = memoryMXBean.getHeapMemoryUsage().getUsed();
        var timestamp = clock.currentTimeMillis();

        snapshots.add(new MemorySnapshot(timestamp, heapUsage));

        if (heapUsage > peakMemory) {
            peakMemory = heapUsage;
        }
    }
}

/**
 * A snapshot of memory usage at a specific time.
 *
 * @param timestamp timestamp in milliseconds since epoch
 * @param heapUsage heap usage in bytes
 */
record MemorySnapshot(long timestamp, long heapUsage) {
}

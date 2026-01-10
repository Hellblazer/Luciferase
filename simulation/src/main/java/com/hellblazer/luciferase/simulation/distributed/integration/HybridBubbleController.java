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

import com.hellblazer.luciferase.simulation.bubble.RealTimeController;
import com.hellblazer.luciferase.simulation.bubble.BucketSynchronizedController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper around BucketSynchronizedController for hybrid timing validation.
 * <p>
 * Provides:
 * <ul>
 *   <li>Bucket boundary synchronization to bound clock drift</li>
 *   <li>Tick overhead measurement via TickListener</li>
 *   <li>Per-bucket metrics aggregation</li>
 *   <li>Simulation time access for drift measurement</li>
 * </ul>
 * <p>
 * Phase 0: Inc7 Go/No-Go Validation Gate - Uses BucketSynchronizedController for drift correction
 *
 * @author hal.hildebrand
 */
public class HybridBubbleController implements RealTimeController.TickListener {

    private static final Logger log = LoggerFactory.getLogger(HybridBubbleController.class);

    private final UUID bubbleId;
    private final String name;
    private final BucketSynchronizedController controller;

    // Per-bucket metrics
    private final AtomicLong tickOverheadNs = new AtomicLong(0);
    private final AtomicLong tickCount = new AtomicLong(0);

    /**
     * Create a hybrid bubble controller with bucket synchronization (Phase 0 validation).
     *
     * @param bubbleId Unique identifier for this bubble
     * @param name     Human-readable name for logging
     */
    public HybridBubbleController(UUID bubbleId, String name) {
        this.bubbleId = bubbleId;
        this.name = name;
        this.controller = new BucketSynchronizedController(bubbleId, name);
        this.controller.addTickListener(this);

        log.debug("HybridBubbleController created with BucketSynchronizedController: bubble={}, name={}", bubbleId, name);
    }

    /**
     * Create a hybrid bubble controller with bucket synchronization and custom tick rate.
     *
     * @param bubbleId Unique identifier for this bubble
     * @param name     Human-readable name for logging
     * @param tickRate Ticks per second (default 100)
     */
    public HybridBubbleController(UUID bubbleId, String name, int tickRate) {
        this.bubbleId = bubbleId;
        this.name = name;
        this.controller = new BucketSynchronizedController(bubbleId, name, tickRate);
        this.controller.addTickListener(this);

        log.debug("HybridBubbleController created with BucketSynchronizedController: bubble={}, name={}, tickRate={}",
                bubbleId, name, tickRate);
    }

    @Override
    public void onTick(long simulationTime, long lamportClock) {
        long start = System.nanoTime();

        // Simulate minimal tick work (in real scenario, entity position updates would go here)
        // For validation purposes, we just measure the callback overhead itself.
        // Any work done here adds to measured overhead.
        simulateTickWork();

        long elapsed = System.nanoTime() - start;
        tickOverheadNs.addAndGet(elapsed);
        tickCount.incrementAndGet();
    }

    /**
     * Simulate minimal tick work for realistic overhead measurement.
     * In production, this would be entity position updates, collision checks, etc.
     */
    private void simulateTickWork() {
        // Minimal work: just a volatile read to prevent optimization
        // This represents the absolute minimum overhead of tick dispatch
        @SuppressWarnings("unused")
        long time = controller.getSimulationTime();
    }

    /**
     * Start the controller (begins autonomous ticking).
     */
    public void start() {
        controller.start();
        log.info("HybridBubbleController started: bubble={}", bubbleId);
    }

    /**
     * Stop the controller.
     */
    public void stop() {
        controller.stop();
        log.info("HybridBubbleController stopped: bubble={}, ticks={}, totalOverheadNs={}",
                bubbleId, tickCount.get(), tickOverheadNs.get());
    }

    /**
     * Check if the controller is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return controller.isRunning();
    }

    /**
     * Get current simulation time (tick count).
     *
     * @return Simulation time from underlying RealTimeController
     */
    public long getSimulationTime() {
        return controller.getSimulationTime();
    }

    /**
     * Get current Lamport clock value.
     *
     * @return Lamport clock from underlying RealTimeController
     */
    public long getLamportClock() {
        return controller.getLamportClock();
    }

    /**
     * Get bubble identifier.
     *
     * @return UUID of this bubble
     */
    public UUID getBubbleId() {
        return bubbleId;
    }

    /**
     * Get controller name.
     *
     * @return Human-readable name
     */
    public String getName() {
        return name;
    }

    /**
     * Get accumulated tick overhead in nanoseconds (since last reset).
     *
     * @return Total tick callback duration in nanoseconds
     */
    public long getTickOverheadNs() {
        return tickOverheadNs.get();
    }

    /**
     * Get tick count since last reset.
     *
     * @return Number of ticks processed
     */
    public long getTickCount() {
        return tickCount.get();
    }

    /**
     * Get average tick overhead in nanoseconds.
     *
     * @return Average overhead per tick, or 0 if no ticks
     */
    public double getAverageTickOverheadNs() {
        long count = tickCount.get();
        return (count > 0) ? (double) tickOverheadNs.get() / count : 0.0;
    }

    /**
     * Reset per-bucket metrics.
     * Call at bucket boundaries to isolate per-bucket measurements.
     */
    public void resetBucketMetrics() {
        tickOverheadNs.set(0);
        tickCount.set(0);
    }

    /**
     * Get the underlying BucketSynchronizedController.
     * Use with caution; prefer wrapper methods.
     *
     * @return BucketSynchronizedController instance
     */
    public BucketSynchronizedController getController() {
        return controller;
    }

    /**
     * Advance to next bucket and synchronize simulation time (Phase 0 validation).
     * Called at bucket boundaries (100ms intervals) to bound clock drift.
     *
     * @param newBucket Bucket number to advance to
     */
    public void advanceBucket(long newBucket) {
        controller.advanceBucket(newBucket);
    }

    /**
     * Get drift from bucket target (diagnostic method).
     *
     * @return Current drift from target in simulation ticks
     */
    public long getDriftFromTarget() {
        return controller.getDriftFromTarget();
    }

    @Override
    public String toString() {
        return String.format("HybridBubbleController{bubble=%s, name=%s, simTime=%d, ticks=%d, overheadNs=%d}",
                bubbleId, name, controller.getSimulationTime(), tickCount.get(), tickOverheadNs.get());
    }
}

/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Test infrastructure that wraps a GhostChannel with simulated packet loss.
 * <p>
 * LossyGhostChannel enables reliability testing by randomly dropping packets
 * at a configurable loss rate. Uses a seeded Random for reproducible failure scenarios.
 * <p>
 * <strong>Features:</strong>
 * <ul>
 *   <li>Configurable loss rate (0.0 = no loss, 0.5 = 50% loss)</li>
 *   <li>Deterministic with seeded Random (reproducible test scenarios)</li>
 *   <li>Loss tracking (counts sent vs dropped)</li>
 *   <li>Optional loss logging</li>
 *   <li>Transparent wrapper (no API changes)</li>
 * </ul>
 * <p>
 * <strong>Usage:</strong>
 * <pre>
 * // Wrap existing channel with 50% loss
 * var baseChannel = new InMemoryGhostChannel&lt;&gt;();
 * var lossyChannel = new LossyGhostChannel&lt;&gt;(baseChannel, 0.5, 42L);
 *
 * // Use normally - 50% of packets will be randomly dropped
 * lossyChannel.queueGhost(targetId, ghost);
 * lossyChannel.flush(bucket);
 *
 * // Check loss statistics
 * System.out.println("Loss rate: " + lossyChannel.getActualLossRate());
 * System.out.println("Dropped: " + lossyChannel.getDroppedCount());
 * </pre>
 * <p>
 * <strong>Architecture:</strong>
 * <ul>
 *   <li>Delegates all methods to wrapped channel</li>
 *   <li>Intercepts queueGhost() to randomly drop packets</li>
 *   <li>Uses Random(seed) for deterministic loss patterns</li>
 *   <li>Tracks sent/dropped counts for metrics</li>
 * </ul>
 * <p>
 * <strong>Phase 7B.6 Reliability Testing:</strong>
 * <ul>
 *   <li>Validates graceful degradation under 50% loss</li>
 *   <li>Measures application-level loss (target &lt; 0.1%)</li>
 *   <li>Verifies system recovery after loss stops</li>
 *   <li>Ensures no state corruption under packet loss</li>
 * </ul>
 *
 * @param <ID>      Entity ID type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public class LossyGhostChannel<ID extends EntityID, Content> implements GhostChannel<ID, Content> {

    private static final Logger log = LoggerFactory.getLogger(LossyGhostChannel.class);

    /**
     * Wrapped delegate channel
     */
    private final GhostChannel<ID, Content> delegate;

    /**
     * Packet loss rate (0.0 = no loss, 1.0 = 100% loss)
     */
    private double lossRate;

    /**
     * Random number generator (seeded for determinism)
     */
    private final Random random;

    /**
     * Total ghosts sent (before loss simulation)
     */
    private final AtomicLong sentCount = new AtomicLong(0);

    /**
     * Total ghosts dropped (simulated packet loss)
     */
    private final AtomicLong droppedCount = new AtomicLong(0);

    /**
     * Enable/disable loss logging
     */
    private volatile boolean logLoss = false;

    /**
     * Create lossy channel with specified loss rate and random seed.
     *
     * @param delegate Channel to wrap
     * @param lossRate Packet loss rate (0.0 = no loss, 1.0 = 100% loss)
     * @param seed     Random seed for deterministic loss patterns
     */
    public LossyGhostChannel(GhostChannel<ID, Content> delegate, double lossRate, long seed) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (lossRate < 0.0 || lossRate > 1.0) {
            throw new IllegalArgumentException("lossRate must be in [0.0, 1.0]");
        }
        this.delegate = delegate;
        this.lossRate = lossRate;
        this.random = new Random(seed);
    }

    @Override
    public void queueGhost(UUID targetBubbleId, SimulationGhostEntity<ID, Content> ghost) {
        sentCount.incrementAndGet();

        // Randomly drop packets based on loss rate
        if (random.nextDouble() < lossRate) {
            droppedCount.incrementAndGet();
            if (logLoss) {
                log.debug("Simulated packet loss: dropped ghost {} to target {}",
                    ghost.entityId(), targetBubbleId);
            }
            return; // Simulate packet loss (don't queue)
        }

        // Not dropped - pass through to delegate
        delegate.queueGhost(targetBubbleId, ghost);
    }

    @Override
    public void sendBatch(UUID targetBubbleId, List<SimulationGhostEntity<ID, Content>> ghosts) {
        // Direct batch send bypasses loss simulation (used internally by flush)
        delegate.sendBatch(targetBubbleId, ghosts);
    }

    @Override
    public void flush(long bucket) {
        delegate.flush(bucket);
    }

    @Override
    public void onReceive(BiConsumer<UUID, List<SimulationGhostEntity<ID, Content>>> handler) {
        delegate.onReceive(handler);
    }

    @Override
    public boolean isConnected(UUID targetBubbleId) {
        return delegate.isConnected(targetBubbleId);
    }

    @Override
    public int getPendingCount(UUID targetBubbleId) {
        return delegate.getPendingCount(targetBubbleId);
    }

    @Override
    public void close() {
        delegate.close();
    }

    // ========== Loss Simulation Control ==========

    /**
     * Set packet loss rate (can be changed dynamically).
     *
     * @param lossRate New loss rate (0.0 = no loss, 1.0 = 100% loss)
     */
    public void setLossRate(double lossRate) {
        if (lossRate < 0.0 || lossRate > 1.0) {
            throw new IllegalArgumentException("lossRate must be in [0.0, 1.0]");
        }
        this.lossRate = lossRate;
    }

    /**
     * Get current loss rate.
     *
     * @return Current loss rate
     */
    public double getLossRate() {
        return lossRate;
    }

    /**
     * Enable or disable loss logging.
     *
     * @param enabled true to log dropped packets
     */
    public void setLogLoss(boolean enabled) {
        this.logLoss = enabled;
    }

    // ========== Loss Statistics ==========

    /**
     * Get total ghosts sent (before loss simulation).
     *
     * @return Total sent count
     */
    public long getSentCount() {
        return sentCount.get();
    }

    /**
     * Get total ghosts dropped (simulated packet loss).
     *
     * @return Total dropped count
     */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /**
     * Get total ghosts delivered (sent - dropped).
     *
     * @return Total delivered count
     */
    public long getDeliveredCount() {
        return sentCount.get() - droppedCount.get();
    }

    /**
     * Get actual loss rate (measured).
     *
     * @return Actual loss rate (dropped / sent)
     */
    public double getActualLossRate() {
        long sent = sentCount.get();
        if (sent == 0) {
            return 0.0;
        }
        return (double) droppedCount.get() / sent;
    }

    /**
     * Reset loss statistics.
     */
    public void resetStats() {
        sentCount.set(0);
        droppedCount.set(0);
    }
}

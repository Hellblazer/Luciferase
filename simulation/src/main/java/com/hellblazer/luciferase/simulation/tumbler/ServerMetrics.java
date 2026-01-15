/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.tumbler;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks server utilization metrics for load balancing decisions.
 * Uses EWMA (Exponential Weighted Moving Average) for stability.
 */
public class ServerMetrics {

    private static final double DEFAULT_EWMA_ALPHA = 0.3;

    private final UUID serverId;
    private final double ewmaAlpha;

    private final AtomicInteger bubbleCount = new AtomicInteger(0);
    private final AtomicInteger entityCount = new AtomicInteger(0);
    private final AtomicLong lastUpdateNanos = new AtomicLong(System.nanoTime());

    // EWMA-smoothed utilization (0.0 to 1.0)
    private volatile double smoothedUtilization = 0.0;

    // Raw frame time tracking (milliseconds)
    private volatile double lastFrameTimeMs = 0.0;
    private final double targetFrameTimeMs;

    public ServerMetrics(UUID serverId, double targetFrameTimeMs) {
        this(serverId, targetFrameTimeMs, DEFAULT_EWMA_ALPHA);
    }

    public ServerMetrics(UUID serverId, double targetFrameTimeMs, double ewmaAlpha) {
        this.serverId = serverId;
        this.targetFrameTimeMs = targetFrameTimeMs;
        this.ewmaAlpha = ewmaAlpha;
    }

    public UUID serverId() {
        return serverId;
    }

    /**
     * Returns smoothed utilization as percentage (0-100).
     */
    public double utilizationPercent() {
        return smoothedUtilization * 100.0;
    }

    /**
     * Returns raw smoothed utilization (0.0-1.0+).
     * Can exceed 1.0 if overloaded.
     */
    public double utilization() {
        return smoothedUtilization;
    }

    public int bubbleCount() {
        return bubbleCount.get();
    }

    public int entityCount() {
        return entityCount.get();
    }

    /**
     * Record a frame time sample and update EWMA utilization.
     */
    public void recordFrameTime(double frameTimeMs) {
        this.lastFrameTimeMs = frameTimeMs;
        double rawUtilization = frameTimeMs / targetFrameTimeMs;
        smoothedUtilization = ewmaAlpha * rawUtilization + (1 - ewmaAlpha) * smoothedUtilization;
        lastUpdateNanos.set(System.nanoTime());
    }

    /**
     * Increment bubble count when a bubble is assigned to this server.
     */
    public void addBubble(int entityCount) {
        bubbleCount.incrementAndGet();
        this.entityCount.addAndGet(entityCount);
    }

    /**
     * Decrement bubble count when a bubble is removed from this server.
     */
    public void removeBubble(int entityCount) {
        bubbleCount.decrementAndGet();
        this.entityCount.addAndGet(-entityCount);
    }

    /**
     * Returns true if metrics are stale (no update in specified duration).
     */
    public boolean isStale(long maxAgeNanos) {
        return (System.nanoTime() - lastUpdateNanos.get()) > maxAgeNanos;
    }

    /**
     * Returns the age of the last update in milliseconds.
     */
    public long ageMs() {
        return (System.nanoTime() - lastUpdateNanos.get()) / 1_000_000;
    }

    @Override
    public String toString() {
        return String.format("ServerMetrics[%s: %.1f%% util, %d bubbles, %d entities]",
                             serverId.toString().substring(0, 8),
                             utilizationPercent(),
                             bubbleCount.get(),
                             entityCount.get());
    }
}

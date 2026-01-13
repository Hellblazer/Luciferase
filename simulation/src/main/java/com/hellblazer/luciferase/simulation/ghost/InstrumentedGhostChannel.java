/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.luciferase.simulation.ghost;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import com.hellblazer.luciferase.simulation.ghost.SimulationGhostEntity;
import com.hellblazer.luciferase.simulation.metrics.LatencyStats;
import com.hellblazer.luciferase.simulation.metrics.LatencyTracker;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Ghost channel wrapper that instruments latency.
 * <p>
 * Delegates all operations to an underlying GhostChannel while tracking latency metrics for sendBatch operations.
 * Supports optional callback for custom latency processing.
 * <p>
 * <strong>Features:</strong>
 * <ul>
 *   <li>Transparent delegation - no behavior changes</li>
 *   <li>Automatic latency tracking for sendBatch</li>
 *   <li>Optional latency callback for custom metrics</li>
 *   <li>Thread-safe latency recording</li>
 * </ul>
 * <p>
 * <strong>Usage:</strong>
 * <pre>
 * // Basic usage
 * var channel = new InMemoryGhostChannel&lt;&gt;();
 * var instrumented = new InstrumentedGhostChannel&lt;&gt;(channel);
 *
 * // With callback
 * var instrumented = new InstrumentedGhostChannel&lt;&gt;(channel,
 *     latencyNs -&gt; metrics.recordGhostLatency(latencyNs));
 *
 * // Get latency stats
 * var stats = instrumented.getLatencyStats();
 * if (stats.exceedsThreshold(100_000_000)) {
 *     log.warn("P99 latency exceeds 100ms: {}ms", stats.p99LatencyMs());
 * }
 * </pre>
 *
 * @param <ID>      Entity ID type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public class InstrumentedGhostChannel<ID extends EntityID, Content> implements GhostChannel<ID, Content> {

    /**
     * Underlying channel to delegate to
     */
    private final GhostChannel<ID, Content> delegate;

    /**
     * Latency tracker for performance monitoring
     */
    private final LatencyTracker latencyTracker;

    /**
     * Optional callback for custom latency processing
     */
    private final Consumer<Long> latencyRecorder;
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
     * Create instrumented channel without callback.
     *
     * @param delegate Underlying channel to wrap
     */
    public InstrumentedGhostChannel(GhostChannel<ID, Content> delegate) {
        this(delegate, null);
    }

    /**
     * Create instrumented channel with optional latency callback.
     *
     * @param delegate        Underlying channel to wrap
     * @param latencyRecorder Optional callback invoked with latency in nanoseconds (may be null)
     */
    public InstrumentedGhostChannel(GhostChannel<ID, Content> delegate, Consumer<Long> latencyRecorder) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.latencyTracker = new LatencyTracker();
        this.latencyRecorder = latencyRecorder;
    }

    @Override
    public void queueGhost(UUID targetBubbleId, SimulationGhostEntity<ID, Content> ghost) {
        delegate.queueGhost(targetBubbleId, ghost);
    }

    @Override
    public void sendBatch(UUID targetBubbleId, List<SimulationGhostEntity<ID, Content>> ghosts) {
        var startNs = clock.nanoTime();
        try {
            delegate.sendBatch(targetBubbleId, ghosts);
        } finally {
            var elapsedNs = clock.nanoTime() - startNs;
            latencyTracker.record(elapsedNs);
            if (latencyRecorder != null) {
                latencyRecorder.accept(elapsedNs);
            }
        }
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
        latencyTracker.reset();
    }

    /**
     * Get latency statistics.
     *
     * @return Current latency statistics
     */
    public LatencyStats getLatencyStats() {
        return latencyTracker.getStats();
    }
}

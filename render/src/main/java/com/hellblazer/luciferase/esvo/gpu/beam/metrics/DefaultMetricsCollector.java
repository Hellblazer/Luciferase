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
package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

/**
 * Default implementation of MetricsCollector that delegates to GPUMetricsCollector.
 * Provides thread-safe metrics collection from GPU rendering pipeline.
 * <p>
 * This adapter allows the render module to use the MetricsCollector interface
 * while maintaining the existing GPUMetricsCollector implementation.
 *
 * @author hal.hildebrand
 */
public class DefaultMetricsCollector implements MetricsCollector {

    private final GPUMetricsCollector delegate;

    /**
     * Creates a default metrics collector with default window size (60 frames).
     */
    public DefaultMetricsCollector() {
        this(MetricsAggregator.DEFAULT_WINDOW_SIZE);
    }

    /**
     * Creates a default metrics collector with specified window size.
     *
     * @param windowSize Number of frames to track in rolling window
     */
    public DefaultMetricsCollector(int windowSize) {
        this.delegate = new GPUMetricsCollector(windowSize);
    }

    @Override
    public void startFrame() {
        delegate.beginFrame();
    }

    @Override
    public void endFrame() {
        delegate.endFrame();
    }

    @Override
    public void recordGPUTiming(long kernelExecutionNs) {
        // Record as kernel timing with generic name
        var startNanos = System.nanoTime() - kernelExecutionNs;
        var endNanos = System.nanoTime();
        delegate.recordKernelTiming("gpu_kernel", startNanos, endNanos);
    }

    @Override
    public void recordDispatchMetrics(DispatchMetrics metrics) {
        delegate.recordKernelSelection(metrics);
    }

    @Override
    public void recordBeamTreeStats(CoherenceSnapshot coherence) {
        delegate.recordBeamTreeStats(coherence);
    }

    @Override
    public MetricsSnapshot getSnapshot() {
        return delegate.getSnapshot();
    }

    @Override
    public void reset() {
        delegate.reset();
    }
}

package com.hellblazer.luciferase.simulation.bubble;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors frame time performance and detects split thresholds.
 * Thread-safe via AtomicLong.
 *
 * @author hal.hildebrand
 */
public class BubbleFrameMonitor {

    private static final float SPLIT_THRESHOLD = 1.2f;

    private final long targetFrameMs;
    private final AtomicLong lastFrameTimeNs;

    /**
     * Create a frame monitor with target frame time budget.
     *
     * @param targetFrameMs Target frame time in milliseconds
     */
    public BubbleFrameMonitor(long targetFrameMs) {
        this.targetFrameMs = targetFrameMs;
        this.lastFrameTimeNs = new AtomicLong(0);
    }

    /**
     * Record the time taken for a simulation frame.
     *
     * @param frameTimeNs Frame time in nanoseconds
     */
    public void recordFrameTime(long frameTimeNs) {
        lastFrameTimeNs.set(frameTimeNs);
    }

    /**
     * Get the current frame utilization as a fraction of the target budget.
     *
     * @return Utilization (0.0 to 1.0+, >1.0 means over budget)
     */
    public float frameUtilization() {
        long frameTimeNs = lastFrameTimeNs.get();
        long targetFrameNs = targetFrameMs * 1_000_000L;
        return (float) frameTimeNs / targetFrameNs;
    }

    /**
     * Check if this bubble needs to split due to frame time overrun.
     * Split threshold: 120% of target frame time (1.2x budget).
     *
     * @return true if bubble should split
     */
    public boolean needsSplit() {
        return frameUtilization() > SPLIT_THRESHOLD;
    }
}

/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe frame counter and time tracking for animation and simulation systems.
 * Provides atomic frame counting and frame time calculation from a start time.
 *
 * @author hal.hildebrand
 */
public class FrameManager {
    private final AtomicLong frameCounter;
    private final long startTimeNanos;

    /**
     * Create a new FrameManager with frame counter starting at 0 and current time as start time
     */
    public FrameManager() {
        this(0L);
    }

    /**
     * Create a new FrameManager with specified initial frame count
     *
     * @param initialFrame the initial frame count
     */
    public FrameManager(long initialFrame) {
        this.frameCounter = new AtomicLong(initialFrame);
        this.startTimeNanos = System.nanoTime();
    }

    /**
     * Get the current frame number
     *
     * @return the current frame count
     */
    public long getCurrentFrame() {
        return frameCounter.get();
    }

    /**
     * Increment the frame counter and return the new value
     *
     * @return the new frame count after incrementing
     */
    public long incrementFrame() {
        return frameCounter.incrementAndGet();
    }

    /**
     * Increment the frame counter and return the previous value
     *
     * @return the frame count before incrementing
     */
    public long getAndIncrementFrame() {
        return frameCounter.getAndIncrement();
    }

    /**
     * Calculate the elapsed time since start in seconds
     *
     * @return elapsed time in seconds as a double
     */
    public double getElapsedTimeSeconds() {
        long elapsedNanos = System.nanoTime() - startTimeNanos;
        return elapsedNanos / 1_000_000_000.0;
    }

    /**
     * Calculate the current frame time in seconds.
     * This represents the time per frame assuming uniform frame distribution.
     *
     * @return frame time in seconds, or 0.0 if no frames have been processed
     */
    public double getFrameTimeSeconds() {
        long frames = frameCounter.get();
        if (frames == 0) {
            return 0.0;
        }
        return getElapsedTimeSeconds() / frames;
    }

    /**
     * Calculate the average frames per second (FPS) since start
     *
     * @return average FPS, or 0.0 if no time has elapsed
     */
    public double getAverageFPS() {
        double elapsedSeconds = getElapsedTimeSeconds();
        if (elapsedSeconds == 0.0) {
            return 0.0;
        }
        return frameCounter.get() / elapsedSeconds;
    }

    /**
     * Reset the frame counter to 0
     */
    public void reset() {
        frameCounter.set(0L);
    }

    /**
     * Set the frame counter to a specific value
     *
     * @param frame the new frame count
     */
    public void setFrame(long frame) {
        frameCounter.set(frame);
    }

    /**
     * Get a string representation of the current frame manager state
     *
     * @return string containing frame count, elapsed time, and average FPS
     */
    @Override
    public String toString() {
        return String.format("FrameManager[frame=%d, elapsed=%.3fs, avgFPS=%.2f]",
                             getCurrentFrame(), getElapsedTimeSeconds(), getAverageFPS());
    }
}
package com.hellblazer.luciferase.portal.web;

import com.hellblazer.luciferase.common.time.Clock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Controllable clock for deterministic portal tests.
 * Supports both millisecond ({@link #currentTimeMillis()}) and
 * nanosecond ({@link #nanoTime()}) resolution.
 */
public class TestClock implements Clock {

    private final AtomicLong timeMs;
    private final AtomicLong timeNs;

    public TestClock(long initialTimeMs) {
        this.timeMs = new AtomicLong(initialTimeMs);
        this.timeNs = new AtomicLong(initialTimeMs * 1_000_000L);
    }

    /** Advance both clocks by the given milliseconds. */
    public void advance(long deltaMs) {
        timeMs.addAndGet(deltaMs);
        timeNs.addAndGet(deltaMs * 1_000_000L);
    }

    /** Advance the nanosecond clock by the given nanoseconds only. */
    public void advanceNanos(long deltaNs) {
        timeNs.addAndGet(deltaNs);
    }

    @Override
    public long currentTimeMillis() {
        return timeMs.get();
    }

    @Override
    public long nanoTime() {
        return timeNs.get();
    }
}

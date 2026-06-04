package com.hellblazer.luciferase.portal.web;

import com.hellblazer.luciferase.common.time.Clock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Controllable clock for deterministic portal tests.
 */
public class TestClock implements Clock {

    private final AtomicLong time;

    public TestClock(long initialTimeMs) {
        this.time = new AtomicLong(initialTimeMs);
    }

    public void advance(long deltaMs) {
        time.addAndGet(deltaMs);
    }

    @Override
    public long currentTimeMillis() {
        return time.get();
    }
}

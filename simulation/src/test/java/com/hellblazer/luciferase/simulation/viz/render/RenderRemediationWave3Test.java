/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.common.time.Clock;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for wave-3 render-package remediation beads:
 * Luciferase-0frcy.68 (EntityStreamConsumer null-deref aborts whole batch),
 * .119 (AdaptiveRegionManager entity-cap check-then-add non-atomic),
 * .121 (RateLimiter check-then-add non-atomic under concurrent load).
 */
class RenderRemediationWave3Test {

    private static RenderingServerConfig configWithCap(int cap) {
        var base = RenderingServerConfig.testing();
        return new RenderingServerConfig(
            base.port(), base.upstreams(), base.regionLevel(), base.security(),
            base.cache(), base.build(), cap, base.streaming(), base.performance(),
            base.worldMin(), base.worldMax());
    }

    private static int totalEntities(AdaptiveRegionManager mgr) {
        int total = 0;
        for (var region : mgr.getAllRegions()) {
            var state = mgr.getRegionState(region);
            if (state != null) {
                total += state.entities().size();
            }
        }
        return total;
    }

    // ---- Luciferase-0frcy.68: one malformed entity does not discard the batch ----

    @Test
    void malformedEntitySkippedNotWholeBatchDropped() {
        var mgr = new AdaptiveRegionManager(configWithCap(1000));
        var uri = URI.create("ws://localhost:65535/stream");
        var consumer = new EntityStreamConsumer(List.of(new UpstreamConfig(uri, "up")), mgr);

        // Middle entity is missing required fields (no x/y/z/type). Pre-fix the
        // NPE aborted the loop and dropped the trailing valid entities too.
        String json = """
            {"entities":[
              {"id":"a","x":1.0,"y":2.0,"z":3.0,"type":"tank"},
              {"id":"bad"},
              {"id":"c","x":4.0,"y":5.0,"z":6.0,"type":"tank"}
            ]}""";

        consumer.onMessage(uri, json);

        assertEquals(2, totalEntities(mgr),
                     "both valid entities must be processed despite one malformed entity in the batch");
    }

    // ---- Luciferase-0frcy.119: concurrent updates never exceed the per-region cap ----

    @Test
    void regionEntityCapNotExceededUnderConcurrency() throws Exception {
        int cap = 8;
        var mgr = new AdaptiveRegionManager(configWithCap(cap));
        // All entities map to the same region (same position).
        float x = 1.0f, y = 1.0f, z = 1.0f;

        int threads = 8;
        int perThread = 50;
        var executor = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var rejects = new AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        try {
                            mgr.updateEntity("e-" + tid + "-" + i, x, y, z, "tank");
                        } catch (IllegalStateException over) {
                            rejects.incrementAndGet(); // expected once at cap
                        }
                    }
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // Invariant: no region ever holds more than the cap, regardless of races.
        for (var region : mgr.getAllRegions()) {
            var state = mgr.getRegionState(region);
            assertTrue(state.entities().size() <= cap,
                       "region " + region + " exceeded cap " + cap + ": " + state.entities().size());
        }
        assertTrue(rejects.get() > 0, "over-cap additions must be rejected, not silently accepted");
    }

    // ---- Luciferase-0frcy.121: rate limiter holds the window under concurrent flood ----

    @Test
    void rateLimiterDoesNotOverAdmitUnderConcurrentFlood() throws Exception {
        int max = 10;
        var limiter = new RateLimiter(max, Clock.fixed(1_000_000L));
        String ip = "10.0.0.1";

        int threads = 16;
        int attemptsPerThread = 100;
        var executor = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < attemptsPerThread; i++) {
                        if (limiter.allowRequest(ip)) {
                            allowed.incrementAndGet();
                        }
                    }
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // With a fixed clock the whole window is "now", so at most `max` requests
        // may be admitted total. Pre-fix the non-atomic check-then-add let the
        // count grow to ~threads * max.
        assertEquals(max, allowed.get(),
                     "rate limiter must admit at most maxRequestsPerMinute under concurrent flood");
    }
}

/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark for ByteBuffer pooling optimization (Luciferase-8db0).
 * <p>
 * Measures GC pressure reduction and pool hit rate from buffer reuse:
 * <ul>
 *   <li>Baseline: Send 100 messages without pooling (allocate every time)</li>
 *   <li>Optimized: Send 100 messages with pooling (reuse buffers)</li>
 * </ul>
 * <p>
 * Expected: ≥70% pool hit rate after warm-up, reduced allocations.
 *
 * @author hal.hildebrand
 */
class RegionStreamerPoolingBenchmark {

    private AdaptiveRegionManager regionManager;
    private ViewportTracker viewportTracker;
    private RegionCache regionCache;
    private TestClock testClock;
    private RegionStreamer streamer;

    @BeforeEach
    void setUp() {
        testClock = new TestClock();
        testClock.setTime(0L);

        var serverConfig = RenderingServerConfig.testing();
        regionManager = new AdaptiveRegionManager(serverConfig);

        var streamingConfig = StreamingConfig.testing();
        viewportTracker = new ViewportTracker(regionManager, streamingConfig);

        // Create cache with pre-built regions for benchmarking
        regionCache = new RegionCache(16 * 1024 * 1024L, Duration.ofMillis(30_000L));

        streamer = new RegionStreamer(viewportTracker, regionCache, regionManager, streamingConfig);
        streamer.setClock(testClock);
    }

    @AfterEach
    void tearDown() {
        if (streamer != null) {
            streamer.close();
        }
    }

    /**
     * Test ByteBuffer pool statistics tracking.
     * <p>
     * Verifies that pool correctly tracks borrows, returns, and allocations.
     */
    @Test
    void testPoolStatistics() throws Exception {
        // Create fake client
        var sentFrames = new ArrayList<ByteBuffer>();
        var sessionId = "stats-test-client";
        var ctx = createFakeContext(sessionId, sentFrames);

        streamer.onConnectInternal(ctx);

        // Register client with viewport
        var viewport = new ClientViewport(
            new javax.vecmath.Point3f(128.0f, 128.0f, 128.0f),
            new javax.vecmath.Point3f(0.0f, 0.0f, 0.0f),
            new javax.vecmath.Vector3f(0.0f, 1.0f, 0.0f),
            1.047f, 1.777f, 1.0f, 1000.0f  // fovY in radians (60 degrees)
        );
        viewportTracker.updateViewport(sessionId, viewport);

        // Pre-populate cache with 25 regions
        var regions = createBenchmarkRegions(25);
        for (var cachedRegion : regions) {
            var builtRegion = cachedRegion.builtRegion();
            var cacheKey = new RegionCache.CacheKey(builtRegion.regionId(), builtRegion.lodLevel());
            regionCache.put(cacheKey, cachedRegion);
        }

        // Get initial stats
        var initialStats = streamer.bufferPool.getStats();
        assertEquals(0, initialStats.borrowCount(), "Initial borrow count should be 0");
        assertEquals(0, initialStats.returnCount(), "Initial return count should be 0");
        assertEquals(0, initialStats.allocCount(), "Initial allocation count should be 0");

        // Send 25 frames (will auto-flush at 10, then at 20, leaving 5 in buffer)
        // Expected: 10 allocated for first 10, reused for next 10, then 5 more allocated
        var session = streamer.sessions.get(sessionId);
        for (int i = 0; i < 25; i++) {
            streamer.sendBinaryFrameAsync(session, regions.get(i));
        }

        // Flush remaining buffer
        synchronized (session) {
            streamer.flushBuffer(session);
        }

        // Get stats after first batch
        // Frames 1-10: allocate 10, auto-flush at 10
        // Frames 11-20: reuse 10 from pool, auto-flush at 20
        // Frames 21-25: reuse 5 from pool, manual flush
        var stats1 = streamer.bufferPool.getStats();
        assertEquals(25, stats1.borrowCount(), "Should have borrowed 25 buffers");
        assertEquals(25, stats1.returnCount(), "Should have returned 25 buffers");
        assertEquals(10, stats1.allocCount(), "Should have allocated 10 buffers (reused after auto-flush)");
        assertEquals(60.0, stats1.hitRate(), 0.01, "Hit rate should be 60% (15/25 from pool)");

        // Send another 25 frames (should reuse all from pool, maxing at 10)
        for (int i = 0; i < 25; i++) {
            streamer.sendBinaryFrameAsync(session, regions.get(i));
        }

        synchronized (session) {
            streamer.flushBuffer(session);
        }

        // Get stats after second batch - all should be reused from pool
        var stats2 = streamer.bufferPool.getStats();
        assertEquals(50, stats2.borrowCount(), "Should have borrowed 50 buffers total");
        assertEquals(50, stats2.returnCount(), "Should have returned 50 buffers total");
        assertEquals(10, stats2.allocCount(), "Should still have only 10 allocations (reused 40)");
        assertEquals(80.0, stats2.hitRate(), 0.01, "Hit rate should be 80% (40/50 from pool)");

        assertEquals(50, sentFrames.size(), "Should have sent 50 frames total");
    }

    /**
     * Benchmark: Measure allocation count with pooling.
     * <p>
     * Sends 100 binary frames with buffer pooling, measures allocation reduction.
     * Expected: After warm-up, allocations << borrows (high pool hit rate).
     */
    @Test
    void benchmarkWithPooling() throws Exception {
        // Create fake client
        var sentFrames = new ArrayList<ByteBuffer>();
        var sessionId = "benchmark-pooling-client";
        var ctx = createFakeContext(sessionId, sentFrames);

        streamer.onConnectInternal(ctx);

        // Register client with viewport
        var viewport = new ClientViewport(
            new javax.vecmath.Point3f(128.0f, 128.0f, 128.0f),
            new javax.vecmath.Point3f(0.0f, 0.0f, 0.0f),
            new javax.vecmath.Vector3f(0.0f, 1.0f, 0.0f),
            1.047f, 1.777f, 1.0f, 1000.0f  // fovY in radians (60 degrees)
        );
        viewportTracker.updateViewport(sessionId, viewport);

        // Pre-populate cache with 100 regions
        var regions = createBenchmarkRegions(100);
        for (var cachedRegion : regions) {
            var builtRegion = cachedRegion.builtRegion();
            var cacheKey = new RegionCache.CacheKey(builtRegion.regionId(), builtRegion.lodLevel());
            regionCache.put(cacheKey, cachedRegion);
        }

        // Warm up: Send 10 frames to populate pool (will auto-flush at 10)
        var session = streamer.sessions.get(sessionId);
        for (int i = 0; i < 10; i++) {
            streamer.sendBinaryFrameAsync(session, regions.get(i));
        }
        // Note: Auto-flush already happened at 10 messages, pool now has 10 buffers

        // Clear sent frames tracking (but keep pool populated)
        sentFrames.clear();

        // Benchmark: Send 100 frames with pooling
        // Note: Auto-flush happens every 10 messages, so pool size stabilizes at 10
        var startNs = System.nanoTime();

        var preStats = streamer.bufferPool.getStats();  // Stats after warm-up

        for (int i = 0; i < 100; i++) {
            streamer.sendBinaryFrameAsync(session, regions.get(i));
            // Auto-flush will trigger every 10 messages
        }

        // Final flush for remaining messages (none, since 100 is multiple of 10)

        var elapsedNs = System.nanoTime() - startNs;
        var throughputMsgPerSec = (100.0 * 1_000_000_000.0) / elapsedNs;

        // Get pool statistics (delta from warm-up)
        var postStats = streamer.bufferPool.getStats();
        var borrowsDelta = postStats.borrowCount() - preStats.borrowCount();
        var returnsDelta = postStats.returnCount() - preStats.returnCount();
        var allocsDelta = postStats.allocCount() - preStats.allocCount();

        var hitRate = allocsDelta == 0 ? 100.0 : 100.0 * (borrowsDelta - allocsDelta) / borrowsDelta;

        System.out.printf("[WITH POOLING] Elapsed: %.2f ms, Throughput: %.0f msg/sec%n",
            elapsedNs / 1_000_000.0, throughputMsgPerSec);
        System.out.printf("  Pool stats: %d borrows, %d returns, %d allocations, %.1f%% hit rate%n",
            borrowsDelta, returnsDelta, allocsDelta, hitRate);

        assertEquals(100, sentFrames.size(), "Should have sent 100 frames");
        assertEquals(100, borrowsDelta, "Should have borrowed 100 buffers");
        assertEquals(100, returnsDelta, "Should have returned 100 buffers");

        // After warm-up, all buffers should come from pool (100% hit rate)
        // Pool has 10 buffers, auto-flush happens every 10 messages, so all reused
        assertEquals(0, allocsDelta, "No new allocations needed (all from pool)");
        assertEquals(100.0, hitRate, 0.01, "Hit rate should be 100% (all from pool)");
    }

    /**
     * Test buffer lifecycle: borrow → encode → buffer → flush → return.
     * <p>
     * Verifies that buffers are correctly returned to pool after send.
     */
    @Test
    void testBufferLifecycle() throws Exception {
        // Create fake client
        var sentFrames = new ArrayList<ByteBuffer>();
        var sessionId = "lifecycle-test-client";
        var ctx = createFakeContext(sessionId, sentFrames);

        streamer.onConnectInternal(ctx);

        // Register client with viewport
        var viewport = new ClientViewport(
            new javax.vecmath.Point3f(128.0f, 128.0f, 128.0f),
            new javax.vecmath.Point3f(0.0f, 0.0f, 0.0f),
            new javax.vecmath.Vector3f(0.0f, 1.0f, 0.0f),
            1.047f, 1.777f, 1.0f, 1000.0f  // fovY in radians (60 degrees)
        );
        viewportTracker.updateViewport(sessionId, viewport);

        // Create one region
        var regions = createBenchmarkRegions(1);
        var cachedRegion = regions.get(0);
        var builtRegion = cachedRegion.builtRegion();
        var cacheKey = new RegionCache.CacheKey(builtRegion.regionId(), builtRegion.lodLevel());
        regionCache.put(cacheKey, cachedRegion);

        var session = streamer.sessions.get(sessionId);

        // Check initial pool state
        var stats0 = streamer.bufferPool.getStats();
        assertEquals(0, stats0.totalPooled(), "Pool should be empty initially");

        // Step 1: borrow → encode → buffer
        streamer.sendBinaryFrameAsync(session, cachedRegion);

        var stats1 = streamer.bufferPool.getStats();
        assertEquals(1, stats1.borrowCount(), "Should have borrowed 1 buffer");
        assertEquals(0, stats1.returnCount(), "Buffer not returned yet (in message buffer)");

        synchronized (session) {
            assertEquals(1, session.messageBuffer.size(), "Buffer should be in message queue");
        }

        // Step 2: flush → send → return
        synchronized (session) {
            streamer.flushBuffer(session);
        }

        var stats2 = streamer.bufferPool.getStats();
        assertEquals(1, stats2.borrowCount(), "Still 1 borrow");
        assertEquals(1, stats2.returnCount(), "Buffer should be returned now");
        assertEquals(1, stats2.totalPooled(), "Pool should have 1 buffer");

        synchronized (session) {
            assertEquals(0, session.messageBuffer.size(), "Message buffer should be empty");
        }

        // Step 3: Reuse pooled buffer
        streamer.sendBinaryFrameAsync(session, cachedRegion);

        var stats3 = streamer.bufferPool.getStats();
        assertEquals(2, stats3.borrowCount(), "Should have borrowed again");
        assertEquals(1, stats3.returnCount(), "Still 1 return");
        assertEquals(0, stats3.totalPooled(), "Pool should be empty (buffer borrowed again)");

        synchronized (session) {
            streamer.flushBuffer(session);
        }

        var stats4 = streamer.bufferPool.getStats();
        assertEquals(2, stats4.borrowCount(), "Still 2 borrows");
        assertEquals(2, stats4.returnCount(), "Now 2 returns");
        assertEquals(1, stats4.totalPooled(), "Pool has 1 buffer again");
        assertEquals(1, stats4.allocCount(), "Should have only allocated once (reused second time)");

        assertEquals(2, sentFrames.size(), "Should have sent 2 frames");
    }

    // --- Helpers ---

    private RegionStreamer.WsContextWrapper createFakeContext(String sessionId, List<ByteBuffer> sentFrames) {
        return new RegionStreamer.WsContextWrapper() {
            @Override
            public String sessionId() {
                return sessionId;
            }

            @Override
            public void send(String message) {
                // Not used in this benchmark
            }

            @Override
            public void sendBinary(ByteBuffer data) {
                // Track sent frames for verification
                sentFrames.add(data);
            }

            @Override
            public void closeSession(int statusCode, String reason) {
                // Not used in this benchmark
            }
        };
    }

    private List<RegionCache.CachedRegion> createBenchmarkRegions(int count) {
        var regions = new ArrayList<RegionCache.CachedRegion>();

        for (int i = 0; i < count; i++) {
            var regionId = new RegionId((long) i, 0);
            var builtRegion = new RegionBuilder.BuiltRegion(
                regionId,
                0,  // LOD level
                RegionBuilder.BuildType.ESVO,
                new byte[1024],  // 1KB dummy serialized data
                false,  // not compressed
                1000L,  // buildTimeNs
                System.currentTimeMillis()  // timestamp
            );

            regions.add(RegionCache.CachedRegion.from(builtRegion, 0L));
        }

        return regions;
    }
}

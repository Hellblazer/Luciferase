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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Streaming integration tests for A.1 and A.2 bugs.
 * <p>
 * Tests are written FIRST (TDD RED phase) to document exactly what is broken
 * and what behavior the fixes must produce.
 * <p>
 * A.1 (Luciferase-06qb): Cache miss must trigger scheduleBuild, not just log.
 * A.2 (Luciferase-ct58): streamToClient must normalize cache key LOD to 0;
 *                         far clients (LOD 3) currently never receive frames
 *                         because the cache only holds LOD 0 entries.
 *
 * @author hal.hildebrand
 */
class RegionStreamerStreamingTest {

    // Config uses regionLevel=2: 4 regions per axis, regionSize=256
    private RenderingServerConfig serverConfig;
    private StreamingConfig streamingConfig;
    private TestClock testClock;
    private RegionCache regionCache;

    /**
     * Tracking subclass that records all scheduleBuild calls without needing
     * a real RegionBuilder wired.  Used for A.1 test.
     */
    static class TrackingRegionManager extends AdaptiveRegionManager {
        final List<RegionId> scheduleBuilds = new CopyOnWriteArrayList<>();

        TrackingRegionManager(RenderingServerConfig config) {
            super(config);
        }

        @Override
        public void scheduleBuild(RegionId region, boolean visible) {
            scheduleBuilds.add(region);
        }
    }

    /** Minimal WsContextWrapper fake for asserting binary frame delivery. */
    static class FakeWsContext implements RegionStreamer.WsContextWrapper {
        final String id;
        final List<String> sentMessages = new ArrayList<>();
        final List<ByteBuffer> sentBinaryFrames = new ArrayList<>();
        boolean wasClosed = false;
        int closeCode = -1;

        FakeWsContext(String id) { this.id = id; }

        @Override public String sessionId() { return id; }
        @Override public void send(String msg) { sentMessages.add(msg); }

        @Override
        public void sendBinary(ByteBuffer data) {
            var copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            sentBinaryFrames.add(copy);
        }

        @Override
        public void closeSession(int code, String reason) {
            wasClosed = true;
            closeCode = code;
        }
    }

    @BeforeEach
    void setUp() {
        testClock = new TestClock();
        testClock.setTime(1000L);
        serverConfig = RenderingServerConfig.testing();  // regionLevel=2
        streamingConfig = StreamingConfig.testing();     // LOD thresholds: {50, 150, 350}

        // Create a real cache (empty by default, populated per test)
        regionCache = new RegionCache(
            16 * 1024 * 1024L,  // 16 MB
            Duration.ofMillis(5000L)
        );
    }

    @AfterEach
    void tearDown() {
        regionCache.close();
    }

    // ===== REGISTER_CLIENT helper =====

    private String buildRegisterJson(float eyeX, float eyeY, float eyeZ,
                                      float lookAtX, float lookAtY, float lookAtZ) {
        return """
            {
                "type": "REGISTER_CLIENT",
                "clientId": "test-client",
                "viewport": {
                    "eye": {"x": %f, "y": %f, "z": %f},
                    "lookAt": {"x": %f, "y": %f, "z": %f},
                    "up": {"x": 0, "y": 1, "z": 0},
                    "fovY": 1.5,
                    "aspectRatio": 1.0,
                    "nearPlane": 1.0,
                    "farPlane": 2000.0
                }
            }
            """.formatted(eyeX, eyeY, eyeZ, lookAtX, lookAtY, lookAtZ);
    }

    // ===== A.2 TEST: Far client receives frame when cache has LOD 0 =====

    /**
     * A.2 (Luciferase-ct58): Far client (LOD 3) must receive frames when
     * the cache holds a LOD 0 entry.
     * <p>
     * Setup:
     * - Entity at (32, 32, 32) → RegionId in region (0,0,0)-(256,256,256)
     * - Cache populated with LOD 0 entry for that region
     * - Client eye far away (>350 units from region center) → LOD 3 assigned
     * <p>
     * Bug: streamToClient uses CacheKey(regionId, lod=3) but cache only
     * has CacheKey(regionId, lod=0).  Far clients NEVER receive frames.
     * <p>
     * Fix: Normalize cache key LOD to 0 → CacheKey(regionId, 0) → cache hit.
     */
    @Test
    void testFarClient_receivesBinaryFrame_whenCacheHasLod0() {
        // --- Setup region manager with an entity in the first region ---
        var regionManager = new AdaptiveRegionManager(serverConfig);
        regionManager.setClock(testClock);

        // Entity at (32,32,32): with regionLevel=2, regionSize=256
        // → region indices (0,0,0) → mortonCode=0 → RegionId(0, 2)
        regionManager.updateEntity("entity-1", 32f, 32f, 32f, "PREY");

        // Get the actual regionId so we can populate the cache with the right key
        var regionId = regionManager.regionForPosition(32f, 32f, 32f);

        // --- Populate cache with LOD 0 entry ---
        var builtRegion = new RegionBuilder.BuiltRegion(
            regionId, 0, RegionBuilder.BuildType.ESVO,
            new byte[]{0x01, 0x02, 0x03, 0x04},  // dummy ESVO payload
            false, 1_000_000L, 1000L
        );
        var cachedRegion = RegionCache.CachedRegion.from(builtRegion, 1000L);
        regionCache.put(new RegionCache.CacheKey(regionId, 0), cachedRegion);

        // --- Build streaming infrastructure ---
        var viewportTracker = new ViewportTracker(regionManager, streamingConfig);
        viewportTracker.setClock(testClock);

        var streamer = new RegionStreamer(viewportTracker, regionCache, regionManager, streamingConfig);
        streamer.setClock(testClock);

        // --- Connect a FAR client (distance > 350 → LOD 3) ---
        // Region center = (128, 128, 128); eye at (128, 128, -1200)
        // Distance = 1328 units → LOD 3 (threshold[2]=350)
        var ctx = new FakeWsContext("far-session");
        streamer.onConnectInternal(ctx);
        streamer.onMessageInternal(ctx, buildRegisterJson(
            128f, 128f, -1200f,  // eye: FAR from region center
            128f, 128f, 128f     // lookAt: region center
        ));

        // Verify session is now STREAMING
        assertFalse(ctx.wasClosed, "Connection should not be closed after register");

        // --- Run one streaming cycle ---
        streamer.streamingCycle();

        // Verify the client was at LOD > 0 during this cycle, confirming A.2 fix is exercised.
        // NOTE: visibleRegions() must be called AFTER streamingCycle() — it updates lastVisible
        // state and calling it before would consume the diff, causing diff.added() to be empty.
        var visible = viewportTracker.visibleRegions("far-session");
        assertFalse(visible.isEmpty(), "Region should be visible to far client");
        assertTrue(visible.stream().anyMatch(vr -> vr.lodLevel() > 0),
            "Far client should be assigned LOD > 0 to exercise the A.2 normalization fix. " +
            "Check StreamingConfig.testing() thresholds vs eye distance.");

        // Flush any batched messages (streaming cycle may buffer them)
        // Since messageBatchSize=5 in testing and we have 1 frame, we need
        // to manually flush via a second cycle at 50ms+ to trigger timeout flush
        testClock.advance(60L);  // Advance past 50ms batch flush timeout
        streamer.streamingCycle();

        // --- Assert: far client received a binary frame ---
        // BUG (before fix): Cache key uses LOD 3, cache has LOD 0 → MISS → no frame
        // FIX (after fix): Cache key normalized to LOD 0 → HIT → frame sent
        assertFalse(ctx.sentBinaryFrames.isEmpty(),
            "Far client (LOD 3) should receive binary frame when cache has LOD 0 entry. " +
            "Bug: streamToClient uses CacheKey(regionId, lodLevel=3) but cache only has LOD 0.");
    }

    // ===== A.1 TEST: Cache miss triggers scheduleBuild =====

    /**
     * A.1 (Luciferase-06qb): A cache miss during streaming must call
     * regionManager.scheduleBuild(), not just log a trace message.
     * <p>
     * Setup:
     * - Entity at (32, 32, 32) → region exists in regionManager
     * - Cache is empty (no built region)
     * - Client is near the region (LOD 0)
     * <p>
     * Bug: When regionCache.get() returns empty, streamToClient only calls
     * log.trace("...would trigger build in Day 7").  No build is ever submitted.
     * <p>
     * Fix: Call regionManager.scheduleBuild(regionId, visible=true) on cache miss.
     */
    @Test
    void testCacheMiss_triggersScheduleBuild() {
        // --- Setup tracking region manager ---
        var trackingManager = new TrackingRegionManager(serverConfig);
        trackingManager.setClock(testClock);

        // Entity at (32,32,32) creates a region in the manager
        trackingManager.updateEntity("entity-1", 32f, 32f, 32f, "PREY");
        var regionId = trackingManager.regionForPosition(32f, 32f, 32f);

        // --- Cache is empty (no LOD 0 or any other entry) ---
        // regionCache is fresh and empty from setUp()

        // --- Build streaming infrastructure ---
        var viewportTracker = new ViewportTracker(trackingManager, streamingConfig);
        viewportTracker.setClock(testClock);

        var streamer = new RegionStreamer(viewportTracker, regionCache, trackingManager, streamingConfig);
        streamer.setClock(testClock);

        // --- Connect a NEAR client (distance < 50 → LOD 0) ---
        // Region center = (128, 128, 128); eye at (128, 128, 90)
        // Distance = 38 units → LOD 0 (threshold[0]=50)
        var ctx = new FakeWsContext("near-session");
        streamer.onConnectInternal(ctx);
        streamer.onMessageInternal(ctx, buildRegisterJson(
            128f, 128f, 90f,   // eye: close to region
            128f, 128f, 128f   // lookAt: region center
        ));

        assertFalse(ctx.wasClosed, "Connection should not be closed after register");

        // --- Run one streaming cycle ---
        streamer.streamingCycle();

        // --- Assert: scheduleBuild was called for the region ---
        // BUG (before fix): streamToClient only logs trace, scheduleBuilds is empty
        // FIX (after fix): regionManager.scheduleBuild(regionId, true) is called
        assertFalse(trackingManager.scheduleBuilds.isEmpty(),
            "Cache miss should trigger scheduleBuild. " +
            "Bug: streamToClient only calls log.trace('...would trigger build in Day 7') on cache miss.");
        assertTrue(trackingManager.scheduleBuilds.contains(regionId),
            "scheduleBuild should be called for region " + regionId + " on cache miss. " +
            "Got calls for: " + trackingManager.scheduleBuilds);
    }

    // ===== I-1 TEST: Build storm deduplication across multiple clients =====

    /**
     * I-1 (Luciferase-bm5e): When multiple clients simultaneously see the same
     * uncached region, {@code scheduleBuild()} must be called exactly once, not
     * once per client.
     * <p>
     * Setup:
     * - 3 clients, all positioned near the same region (LOD 0)
     * - Cache is empty
     * <p>
     * Bug: Each client's {@code diff.added()} triggers a separate
     * {@code scheduleBuild()} call in the same streaming cycle, flooding the
     * build queue with duplicate requests for the same region.
     * <p>
     * Fix: {@code pendingBuilds} ConcurrentHashMap in RegionStreamer deduplicates
     * across clients. Cleared in {@code onRegionBuilt()} when the build completes.
     */
    @Test
    void testMultipleClients_sameUncachedRegion_scheduleBuildCalledOnce() {
        var trackingManager = new TrackingRegionManager(serverConfig);
        trackingManager.setClock(testClock);
        trackingManager.updateEntity("entity-1", 32f, 32f, 32f, "PREY");
        var regionId = trackingManager.regionForPosition(32f, 32f, 32f);

        // Cache stays empty throughout
        var viewportTracker = new ViewportTracker(trackingManager, streamingConfig);
        viewportTracker.setClock(testClock);

        var streamer = new RegionStreamer(viewportTracker, regionCache, trackingManager, streamingConfig);
        streamer.setClock(testClock);

        // Connect 3 clients, all positioned near the same region (LOD 0)
        var contexts = new ArrayList<FakeWsContext>();
        for (int i = 1; i <= 3; i++) {
            var ctx = new FakeWsContext("dedup-session-" + i);
            contexts.add(ctx);
            streamer.onConnectInternal(ctx);
            streamer.onMessageInternal(ctx, buildRegisterJson(128f, 128f, 90f, 128f, 128f, 128f));
            assertFalse(ctx.wasClosed, "Client " + i + " should connect successfully");
        }

        // Run one streaming cycle — all 3 clients see the same uncached region
        streamer.streamingCycle();

        // scheduleBuild should be called EXACTLY ONCE (deduplicated across clients)
        // Bug: without guard, each client's diff.added() triggers a separate scheduleBuild() call
        assertEquals(1, trackingManager.scheduleBuilds.size(),
            "scheduleBuild should be called once per uncached region regardless of client count. " +
            "Bug: each client independently calls scheduleBuild() on cache miss. " +
            "Got " + trackingManager.scheduleBuilds.size() + " calls: " + trackingManager.scheduleBuilds);
        assertTrue(trackingManager.scheduleBuilds.contains(regionId),
            "scheduleBuild should be called for region " + regionId);
    }

    // ===== A.2 TEST: onRegionBuilt push path delivers to far clients =====

    /**
     * A.2 (Luciferase-ct58): The {@code onRegionBuilt()} push path must deliver
     * frames to far clients (LOD 3) when a build completes.
     * <p>
     * This test exercises the third part of the A.2 fix: removing the
     * {@code vr.lodLevel() == builtRegion.lodLevel()} filter in {@code onRegionBuilt()}.
     * <p>
     * Setup:
     * - Far client (LOD 3) is connected, cache is empty
     * - streamingCycle() runs: cache miss, scheduleBuild() called
     * - Build completes: cache populated with LOD 0, onRegionBuilt() called
     * <p>
     * Bug (before fix): {@code onRegionBuilt()} checked
     * {@code vr.lodLevel() == builtRegion.lodLevel()}, so far clients (LOD 3)
     * never matched LOD 0 builds and never received pushed frames.
     * <p>
     * Fix: Match on regionId only — any client viewing the region gets the frame.
     */
    @Test
    void testOnRegionBuilt_farClient_receivesPushedFrame() {
        // --- Setup tracking region manager ---
        var trackingManager = new TrackingRegionManager(serverConfig);
        trackingManager.setClock(testClock);
        trackingManager.updateEntity("entity-1", 32f, 32f, 32f, "PREY");
        var regionId = trackingManager.regionForPosition(32f, 32f, 32f);

        // --- Cache is empty initially ---

        // --- Build streaming infrastructure ---
        var viewportTracker = new ViewportTracker(trackingManager, streamingConfig);
        viewportTracker.setClock(testClock);

        var streamer = new RegionStreamer(viewportTracker, regionCache, trackingManager, streamingConfig);
        streamer.setClock(testClock);

        // --- Connect a FAR client (distance > 350 → LOD 3) ---
        var ctx = new FakeWsContext("far-push-session");
        streamer.onConnectInternal(ctx);
        streamer.onMessageInternal(ctx, buildRegisterJson(
            128f, 128f, -1200f,  // eye: FAR from region center
            128f, 128f, 128f     // lookAt: region center
        ));
        assertFalse(ctx.wasClosed);

        // --- First cycle: cache miss → scheduleBuild called ---
        streamer.streamingCycle();
        assertFalse(trackingManager.scheduleBuilds.isEmpty(),
            "First streaming cycle should trigger scheduleBuild on cache miss");

        // Verify client was at LOD > 0 during this cycle — safe to call after streamingCycle()
        // since lastVisible is already updated and calling it again doesn't affect the next diff.
        var visible = viewportTracker.visibleRegions("far-push-session");
        assertFalse(visible.isEmpty(), "Region should be visible to far client");
        assertTrue(visible.stream().anyMatch(vr -> vr.lodLevel() > 0),
            "Far client should be assigned LOD > 0 to exercise the A.2 onRegionBuilt fix path.");

        // --- Simulate build completion: populate cache then fire callback ---
        var builtRegion = new RegionBuilder.BuiltRegion(
            regionId, 0, RegionBuilder.BuildType.ESVO,
            new byte[]{0x01, 0x02, 0x03, 0x04},
            false, 1_000_000L, 1000L
        );
        var cachedRegion = RegionCache.CachedRegion.from(builtRegion, 1000L);
        regionCache.put(new RegionCache.CacheKey(regionId, 0), cachedRegion);

        // This is the push path: RegionBuilder callback fires onRegionBuilt
        streamer.onRegionBuilt(regionId, builtRegion);

        // --- Flush the batch (1 frame < batchSize=5, needs timeout flush) ---
        testClock.advance(60L);  // Advance past 50ms batchFlushTimeoutMs
        streamer.streamingCycle();

        // --- Assert: far client received frame via push path ---
        // BUG (before fix): onRegionBuilt filtered by vr.lodLevel() == builtRegion.lodLevel()
        //   → LOD 3 client never matched LOD 0 build → no frame pushed
        // FIX (after fix): filter by regionId only → LOD 3 client gets the LOD 0 frame
        assertFalse(ctx.sentBinaryFrames.isEmpty(),
            "Far client (LOD 3) should receive binary frame via onRegionBuilt() push path. " +
            "Bug: onRegionBuilt() filtered vr.lodLevel() == builtRegion.lodLevel() (3 != 0).");
    }
}

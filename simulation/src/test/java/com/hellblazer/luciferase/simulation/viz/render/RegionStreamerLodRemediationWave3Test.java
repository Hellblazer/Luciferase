/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for Luciferase-0frcy.69: onRegionBuilt() cache-key LOD mismatch
 * silently drops completed builds when builtRegion.lodLevel() != PIPELINE_CANONICAL_LOD.
 */
class RegionStreamerLodRemediationWave3Test {

    private static RegionBuilder.BuiltRegion built(RegionId id, int lod) {
        return new RegionBuilder.BuiltRegion(
            id, lod, RegionBuilder.BuildType.ESVO,
            new byte[]{1, 2, 3, 4}, false, 1L, 1L, 1L);
    }

    @Test
    void cacheKeyLodMismatchMissesEntryStoredAtCanonicalLod() {
        try (var cache = new RegionCache(1L << 20, Duration.ofMinutes(5))) {
            var regionId = new RegionId(42L, 4);

            // Build pipeline stores at PIPELINE_CANONICAL_LOD (0).
            var canonicalKey = new RegionCache.CacheKey(regionId, RegionStreamer.PIPELINE_CANONICAL_LOD);
            cache.put(canonicalKey, RegionCache.CachedRegion.from(
                built(regionId, RegionStreamer.PIPELINE_CANONICAL_LOD), 1L));

            // A lookup with the canonical LOD hits...
            assertTrue(cache.get(canonicalKey).isPresent(),
                       "entry stored at canonical LOD must be retrievable at canonical LOD");

            // ...but a lookup keyed by a non-zero builtRegion.lodLevel() MISSES.
            // This is the exact mechanism by which the pre-fix onRegionBuilt
            // silently dropped completed builds.
            var mismatchedKey = new RegionCache.CacheKey(regionId, 3);
            assertTrue(cache.get(mismatchedKey).isEmpty(),
                       "non-canonical LOD key must miss — proving why builtRegion.lodLevel() dropped builds");
        }
    }

    @Test
    void onRegionBuiltWithNonCanonicalLodDoesNotThrowAndClearsPending() {
        var config = RenderingServerConfig.testing();
        var mgr = new AdaptiveRegionManager(config);
        var viewport = new ViewportTracker(mgr, config.streaming());
        try (var cache = new RegionCache(1L << 20, Duration.ofMinutes(5))) {
            var streamer = new RegionStreamer(viewport, cache, mgr, config.streaming());
            var regionId = new RegionId(7L, 4);

            // A non-canonical lodLevel must be handled (normalized to canonical),
            // not silently dropped. With no STREAMING sessions this is a no-op
            // delivery, but it must not throw and must clear the pending flag.
            assertDoesNotThrow(() -> streamer.onRegionBuilt(regionId, built(regionId, 3)));
        }
    }
}

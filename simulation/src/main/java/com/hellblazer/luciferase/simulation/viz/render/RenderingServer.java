/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * GPU-accelerated ESVO/ESVT rendering server.
 * <p>
 * Consumes entity streams from simulation servers, builds compact voxel
 * structures, and streams them to browser clients per-region.
 * <p>
 * Port 7090 by default. Use port 0 for dynamic assignment in tests.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>GET  /api/health  - Health check with component status</li>
 *   <li>GET  /api/info    - Server capabilities and statistics</li>
 *   <li>WS   /ws/render   - Region streaming WebSocket (Phase 3)</li>
 * </ul>
 * <p>
 * Thread-safe: uses AtomicBoolean for lifecycle state.
 *
 * @author hal.hildebrand
 */
public class RenderingServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RenderingServer.class);
    private static final String VERSION = "1.0.0-SNAPSHOT";

    private final RenderingServerConfig config;
    private final AdaptiveRegionManager regionManager;
    private final EntityStreamConsumer entityConsumer;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private Javalin app;
    private volatile long startTimeMs;
    private volatile Clock clock = Clock.system();

    // Phase 2 components
    private RegionBuilder regionBuilder;
    private RegionCache regionCache;

    /**
     * Create rendering server with configuration.
     *
     * @param config Server configuration
     */
    public RenderingServer(RenderingServerConfig config) {
        this.config = config;
        this.regionManager = new AdaptiveRegionManager(config);
        this.entityConsumer = new EntityStreamConsumer(config.upstreams(), regionManager);

        log.info("RenderingServer created with config: port={}, regionLevel={}, upstreams={}",
                 config.port(), config.regionLevel(), config.upstreams().size());
    }

    /**
     * Set the clock for deterministic testing.
     */
    public void setClock(Clock clock) {
        this.clock = clock;
        this.regionManager.setClock(clock);
        this.entityConsumer.setClock(clock);
        if (regionBuilder != null) {
            regionBuilder.setClock(clock);
        }
        // RegionCache doesn't need clock (uses system time for TTL)
    }

    /**
     * Start the rendering server.
     *
     * @throws IllegalStateException if already started
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("RenderingServer already started");
        }

        log.info("Starting RenderingServer");

        // Create Phase 2 components
        var ttl = java.time.Duration.ofMillis(config.regionTtlMs());
        regionBuilder = new RegionBuilder(
            config.buildPoolSize(),
            100,  // maxQueueDepth (sensible default)
            config.maxBuildDepth(),
            config.gridResolution()
        );
        regionBuilder.setClock(clock);

        regionCache = new RegionCache(
            config.maxCacheMemoryBytes(),
            ttl
        );

        // Wire Phase 2 components to region manager
        regionManager.setBuilder(regionBuilder);
        regionManager.setCache(regionCache);

        // Backfill any existing dirty regions
        regionManager.backfillDirtyRegions();

        app = Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
            javalinConfig.http.defaultContentType = "application/json";
        });

        // REST endpoints
        app.get("/api/health", this::handleHealth);
        app.get("/api/info", this::handleInfo);
        app.get("/api/metrics", this::handleMetrics);  // M2: New metrics endpoint

        // WebSocket endpoint (Phase 3 will implement actual streaming)
        app.ws("/ws/render", ws -> {
            ws.onConnect(ctx -> {
                log.info("Client connected to /ws/render: {}", ctx.sessionId());
            });
            ws.onClose(ctx -> {
                log.info("Client disconnected from /ws/render: {}", ctx.sessionId());
            });
            ws.onMessage(ctx -> {
                log.debug("Received message from client {}: {}", ctx.sessionId(), ctx.message());
            });
            // Phase 3 will implement region streaming protocol here
        });

        app.start(config.port());
        startTimeMs = clock.currentTimeMillis();

        // Start consuming upstream entity streams
        entityConsumer.start();

        log.info("RenderingServer started on port {}", port());
    }

    /**
     * Stop the rendering server gracefully.
     */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            log.warn("RenderingServer not started, ignoring stop()");
            return;
        }

        log.info("Stopping RenderingServer");

        // Stop consuming entity streams
        entityConsumer.close();

        // Close Phase 2 components
        if (regionBuilder != null) {
            regionBuilder.close();
            regionBuilder = null;
        }
        if (regionCache != null) {
            regionCache.close();
            regionCache = null;
        }

        // Stop Javalin server
        if (app != null) {
            app.stop();
            app = null;
        }

        log.info("RenderingServer stopped");
    }

    /**
     * Check if server is started.
     */
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Get the actual port the server is listening on.
     *
     * @return port number, or -1 if not started
     */
    public int port() {
        return app != null ? app.port() : -1;
    }

    /**
     * Get the region manager.
     */
    public AdaptiveRegionManager getRegionManager() {
        return regionManager;
    }

    /**
     * Get the entity stream consumer.
     */
    public EntityStreamConsumer getEntityConsumer() {
        return entityConsumer;
    }

    /**
     * Get the region builder (Phase 2).
     *
     * @return RegionBuilder instance, or null if not started
     */
    public RegionBuilder getRegionBuilder() {
        return regionBuilder;
    }

    /**
     * Get the region cache (Phase 2).
     *
     * @return RegionCache instance, or null if not started
     */
    public RegionCache getRegionCache() {
        return regionCache;
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Handle /api/health endpoint.
     * <p>
     * Returns: {"status":"healthy","uptime":12345,"regions":42,"entities":156}
     */
    private void handleHealth(Context ctx) {
        long uptime = clock.currentTimeMillis() - startTimeMs;
        var regions = regionManager.getAllRegions();
        int totalEntities = regions.stream()
            .mapToInt(r -> {
                var state = regionManager.getRegionState(r);
                return state != null ? state.entities().size() : 0;
            })
            .sum();

        ctx.json(Map.of(
            "status", "healthy",
            "uptime", uptime,
            "regions", regions.size(),
            "entities", totalEntities
        ));
    }

    /**
     * Handle /api/info endpoint.
     * <p>
     * Returns server capabilities and configuration.
     */
    private void handleInfo(Context ctx) {
        var upstreamsInfo = config.upstreams().stream()
            .map(u -> Map.of(
                "uri", u.uri().toString(),
                "label", u.label()
            ))
            .collect(Collectors.toList());

        // Use HashMap for >10 entries (Map.of() has max 10 pairs)
        var info = new java.util.HashMap<String, Object>();
        info.put("version", VERSION);
        info.put("port", port());
        info.put("upstreams", upstreamsInfo);
        info.put("regionLevel", config.regionLevel());
        info.put("gridResolution", config.gridResolution());
        info.put("maxBuildDepth", config.maxBuildDepth());
        info.put("buildPoolSize", config.buildPoolSize());
        info.put("structureType", config.defaultStructureType().toString());
        info.put("worldMin", regionManager.worldMin());
        info.put("worldMax", regionManager.worldMax());
        info.put("regionSize", regionManager.regionSize());
        info.put("regionsPerAxis", regionManager.regionsPerAxis());

        ctx.json(info);
    }

    /**
     * Handle /api/metrics endpoint (M2).
     * <p>
     * Returns builder and cache statistics.
     */
    private void handleMetrics(Context ctx) {
        var metrics = new java.util.HashMap<String, Object>();

        // Builder metrics
        if (regionBuilder != null) {
            var builderMetrics = new java.util.HashMap<String, Object>();
            builderMetrics.put("totalBuilds", regionBuilder.getTotalBuilds());
            builderMetrics.put("failedBuilds", regionBuilder.getFailedBuilds());
            builderMetrics.put("queueDepth", regionBuilder.getQueueDepth());
            builderMetrics.put("avgBuildTimeMs",
                (double) regionBuilder.getAverageBuildTimeNs() / 1_000_000.0);
            metrics.put("builder", builderMetrics);
        }

        // Cache metrics
        if (regionCache != null) {
            var cacheStats = regionCache.getStats();
            var cacheMetrics = new java.util.HashMap<String, Object>();
            cacheMetrics.put("pinnedCount", cacheStats.pinnedCount());
            cacheMetrics.put("unpinnedCount", cacheStats.unpinnedCount());
            cacheMetrics.put("totalCount", cacheStats.totalCount());
            cacheMetrics.put("totalMemoryBytes", cacheStats.totalMemoryBytes());
            cacheMetrics.put("caffeineHitRate", cacheStats.caffeineHitRate());
            cacheMetrics.put("caffeineMissRate", cacheStats.caffeineMissRate());
            cacheMetrics.put("caffeineEvictionCount", cacheStats.caffeineEvictionCount());
            cacheMetrics.put("memoryPressure", cacheStats.memoryPressure());
            metrics.put("cache", cacheMetrics);
        }

        ctx.json(metrics);
    }
}

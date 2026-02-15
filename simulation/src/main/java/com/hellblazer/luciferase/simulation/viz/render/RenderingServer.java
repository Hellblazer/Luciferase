/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hellblazer.luciferase.simulation.distributed.integration.Clock;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

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

    // Phase 3 components
    private ViewportTracker viewportTracker;
    private RegionStreamer regionStreamer;

    // w1tk: Rate limiter (null if rate limiting disabled)
    private RateLimiter rateLimiter;

    // rp9u: Endpoint response cache (1s TTL to reduce overhead from frequent polling)
    private Cache<String, String> endpointCache;

    // bfgm: Scheduled executor for periodic dirty region backfill retry
    private ScheduledExecutorService backfillRetryExecutor;

    // vyik: Auth attempt rate limiters per session (prevents brute force attacks)
    private final ConcurrentHashMap<String, AuthAttemptRateLimiter> authLimiters = new ConcurrentHashMap<>();

    /**
     * Create rendering server with configuration.
     *
     * @param config Server configuration
     */
    public RenderingServer(RenderingServerConfig config) {
        this.config = config;
        this.regionManager = new AdaptiveRegionManager(config);
        this.entityConsumer = new EntityStreamConsumer(
            config.upstreams(),
            regionManager,
            config.performance(),
            Clock.system()
        );

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
        if (viewportTracker != null) {
            viewportTracker.setClock(clock);
        }
        if (regionStreamer != null) {
            regionStreamer.setClock(clock);
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
        var ttl = java.time.Duration.ofMillis(config.performance().regionCacheTtlMs());
        regionBuilder = new RegionBuilder(config.build());
        regionBuilder.setClock(clock);

        regionCache = new RegionCache(
            config.cache().maxCacheMemoryBytes(),
            ttl
        );

        // Wire Phase 2 components to region manager
        regionManager.setBuilder(regionBuilder);
        regionManager.setCache(regionCache);

        // Create Phase 3 components
        viewportTracker = new ViewportTracker(
            regionManager,
            config.streaming()
        );
        viewportTracker.setClock(clock);

        regionStreamer = new RegionStreamer(
            viewportTracker,
            regionCache,
            regionManager,
            config.streaming()
        );
        regionStreamer.setClock(clock);

        // Wire RegionStreamer to AdaptiveRegionManager for build completion callbacks
        regionManager.setRegionStreamer(regionStreamer);

        // Backfill any existing dirty regions
        regionManager.backfillDirtyRegions();

        // bfgm: Schedule periodic backfill retry for dirty regions skipped due to queue backpressure
        backfillRetryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "backfill-retry");
            thread.setDaemon(true);
            return thread;
        });
        backfillRetryExecutor.scheduleAtFixedRate(
            () -> {
                try {
                    int skipped = regionManager.backfillDirtyRegions();
                    if (skipped > 0) {
                        log.debug("Periodic backfill retry: {} regions still skipped due to queue backpressure", skipped);
                    }
                } catch (Exception e) {
                    log.error("Error during periodic backfill retry", e);
                }
            },
            10,   // Initial delay: 10 seconds
            10,   // Period: retry every 10 seconds
            TimeUnit.SECONDS
        );

        // w1tk: Initialize rate limiter if enabled
        if (config.security().rateLimitEnabled()) {
            rateLimiter = new RateLimiter(config.security().rateLimitRequestsPerMinute(), clock);
            log.info("Rate limiting enabled: {} requests/minute", config.security().rateLimitRequestsPerMinute());
        }

        // rp9u: Initialize endpoint response cache
        endpointCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(config.performance().endpointCacheExpireSec()))
            .maximumSize(config.performance().endpointCacheMaxSize())
            .build();

        // wwi6: Validate keystore path exists before Jetty starts (for clear error messages)
        if (config.security().tlsEnabled()) {
            var keystorePath = Paths.get(config.security().keystorePath());
            if (!Files.exists(keystorePath)) {
                throw new IllegalStateException(
                    "Keystore file not found: " + config.security().keystorePath() +
                    ". Ensure the keystore file exists and the path is correct."
                );
            }
        }

        app = Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
            javalinConfig.http.defaultContentType = "application/json";

            // jc5f: Configure TLS/HTTPS if enabled
            if (config.security().tlsEnabled()) {
                javalinConfig.jetty.modifyServer(server -> {
                    var sslContextFactory = new org.eclipse.jetty.util.ssl.SslContextFactory.Server();
                    sslContextFactory.setKeyStorePath(config.security().keystorePath());
                    sslContextFactory.setKeyStorePassword(config.security().keystorePassword());
                    sslContextFactory.setKeyManagerPassword(config.security().keyManagerPassword());

                    var httpsConfig = new org.eclipse.jetty.server.HttpConfiguration();
                    httpsConfig.setSecureScheme("https");
                    httpsConfig.setSecurePort(config.port());
                    httpsConfig.addCustomizer(new org.eclipse.jetty.server.SecureRequestCustomizer());

                    var httpsConnector = new org.eclipse.jetty.server.ServerConnector(
                        server,
                        new org.eclipse.jetty.server.SslConnectionFactory(sslContextFactory, "http/1.1"),
                        new org.eclipse.jetty.server.HttpConnectionFactory(httpsConfig)
                    );

                    // Replace HTTP connector with HTTPS connector
                    server.setConnectors(new org.eclipse.jetty.server.Connector[]{httpsConnector});
                });

                log.info("TLS/HTTPS enabled with keystore: {}", config.security().keystorePath());
            }
        });

        // jgpu: Add authentication filter (API key)
        if (config.security().apiKey() != null) {
            app.before("/api/*", ctx -> {
                String authHeader = ctx.header("Authorization");

                // Check for Bearer token format
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    ctx.status(401).json(Map.of("error", "Unauthorized"));
                    return;
                }

                // Extract and validate API key
                String providedKey = authHeader.substring(7); // Remove "Bearer " prefix
                if (!config.security().apiKey().equals(providedKey)) {
                    ctx.status(401).json(Map.of("error", "Unauthorized"));
                    return;
                }

                // Authentication successful - continue to endpoint
            });
        }

        // w1tk: Add rate limiting filter
        if (rateLimiter != null) {
            app.before(ctx -> {
                String clientIp = ctx.ip();
                if (!rateLimiter.allowRequest(clientIp)) {
                    throw new RateLimitExceededException();
                }
            });

            // Handle rate limit exceptions
            app.exception(RateLimitExceededException.class, (e, ctx) -> {
                ctx.status(429).json(Map.of("error", "Too Many Requests"));
            });
        }

        // REST endpoints
        app.get("/api/health", this::handleHealth);
        app.get("/api/info", this::handleInfo);
        app.get("/api/metrics", this::handleMetrics);  // M2: New metrics endpoint

        // WebSocket endpoint - Phase 3 streaming
        app.ws("/ws/render", ws -> {
            ws.onConnect(ctx -> {
                // biom: WebSocket authentication (8gdp: constant-time comparison, vyik: brute force protection)
                var apiKey = config.security().apiKey();
                if (apiKey != null) {
                    var sessionId = ctx.sessionId();
                    // vyik: Track auth attempts by client host (IP-based rate limiting)
                    var clientHost = ctx.host();

                    // vyik: Check if client is blocked due to too many failed auth attempts
                    var authLimiter = authLimiters.computeIfAbsent(clientHost,
                        id -> new AuthAttemptRateLimiter(clock));

                    if (authLimiter.isBlocked()) {
                        log.warn("WebSocket auth failed - rate limit exceeded: sessionId={}", sessionId);
                        ctx.closeSession(4003, "Too many failed authentication attempts");
                        return;  // Do not delegate to regionStreamer
                    }

                    var authHeader = ctx.header("Authorization");
                    if (authHeader == null) {
                        authLimiter.recordFailedAttempt();
                        log.warn("WebSocket auth failed - missing header: sessionId={}", sessionId);
                        ctx.closeSession(4003, "Unauthorized");
                        return;  // Do not delegate to regionStreamer
                    }

                    // Constant-time comparison to prevent timing attacks
                    var expectedAuth = "Bearer " + apiKey;
                    byte[] providedBytes = authHeader.getBytes(StandardCharsets.UTF_8);
                    byte[] expectedBytes = expectedAuth.getBytes(StandardCharsets.UTF_8);

                    if (!MessageDigest.isEqual(providedBytes, expectedBytes)) {
                        if (!authLimiter.recordFailedAttempt()) {
                            log.warn("WebSocket auth failed - rate limit exceeded after failed attempt: sessionId={}", sessionId);
                            ctx.closeSession(4003, "Too many failed authentication attempts");
                        } else {
                            log.warn("WebSocket auth failed - invalid credentials: sessionId={}", sessionId);
                            ctx.closeSession(4003, "Unauthorized");
                        }
                        return;  // Do not delegate to regionStreamer
                    }

                    // vyik: Record successful auth (resets rate limiter)
                    authLimiter.recordSuccess();
                }
                regionStreamer.onConnect(ctx);
            });
            ws.onMessage(msgCtx -> regionStreamer.onMessage(msgCtx, msgCtx.message()));
            ws.onClose(closeCtx -> regionStreamer.onClose(closeCtx, closeCtx.status(), closeCtx.reason()));
            ws.onError(regionStreamer::onError);
        });

        app.start(config.port());
        startTimeMs = clock.currentTimeMillis();

        // Start consuming upstream entity streams
        entityConsumer.start();

        // Start Phase 3 streaming
        regionStreamer.start();

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

        // bfgm: Stop periodic backfill retry executor
        if (backfillRetryExecutor != null) {
            backfillRetryExecutor.shutdown();
            try {
                if (!backfillRetryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    backfillRetryExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                backfillRetryExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            backfillRetryExecutor = null;
        }

        // Stop consuming entity streams
        entityConsumer.close();

        // Stop Phase 3 streaming
        if (regionStreamer != null) {
            regionStreamer.stop();
            regionStreamer.close();
            regionStreamer = null;
        }

        // rp9u: Clear endpoint cache
        if (endpointCache != null) {
            endpointCache.invalidateAll();
        }

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
     * <p>
     * <b>Visibility: Public</b>
     * <p>
     * <b>Rationale:</b> This getter is public to support integration testing and monitoring.
     * The RegionManager is a core component that maintains region state and coordinates
     * entity updates. Tests need access to verify region lifecycle (creation, updates,
     * cleanup) and entity tracking across different scenarios.
     * <p>
     * <b>Usage Guidelines:</b>
     * <ul>
     *   <li><b>Testing:</b> Integration tests use this to verify region state transitions,
     *       entity distribution, and cleanup behavior (see BuildIntegrationTest,
     *       RenderingServerTest, RenderingServerStreamingTest)</li>
     *   <li><b>Monitoring:</b> External monitoring tools can query region statistics
     *       without direct access to internal state</li>
     *   <li><b>NOT for production code:</b> Production code within the same module should
     *       use direct field access or proper dependency injection instead</li>
     * </ul>
     * <p>
     * <b>Constraints:</b>
     * <ul>
     *   <li>RegionManager reference is immutable - created in constructor, never changes</li>
     *   <li>Thread-safe - RegionManager uses internal synchronization for state access</li>
     *   <li>Always non-null - initialized eagerly in constructor</li>
     * </ul>
     *
     * @return The AdaptiveRegionManager instance (never null)
     */
    public AdaptiveRegionManager getRegionManager() {
        return regionManager;
    }

    /**
     * Get the entity stream consumer.
     * <p>
     * <b>Visibility: Public</b>
     * <p>
     * <b>Rationale:</b> Public visibility enables testing of upstream entity consumption
     * and connection management. The EntityStreamConsumer handles gRPC connections to
     * simulation servers and feeds entity updates to the region manager.
     * <p>
     * <b>Usage Guidelines:</b>
     * <ul>
     *   <li><b>Testing:</b> Tests verify upstream connection lifecycle, entity stream
     *       processing, and error handling</li>
     *   <li><b>Monitoring:</b> External monitoring can check upstream connection health
     *       and consumption statistics</li>
     *   <li><b>NOT for production integration:</b> Production code should not directly
     *       manipulate the entity consumer - use RenderingServerConfig.upstreams() instead</li>
     * </ul>
     * <p>
     * <b>Constraints:</b>
     * <ul>
     *   <li>EntityConsumer reference is immutable - created in constructor</li>
     *   <li>Thread-safe - EntityConsumer uses internal synchronization</li>
     *   <li>Always non-null - initialized eagerly in constructor</li>
     *   <li>Lifecycle coupled to RenderingServer - start() and stop() control consumption</li>
     * </ul>
     *
     * @return The EntityStreamConsumer instance (never null)
     */
    public EntityStreamConsumer getEntityConsumer() {
        return entityConsumer;
    }

    /**
     * Get the region builder (Phase 2).
     * <p>
     * <b>Visibility: Public</b>
     * <p>
     * <b>Rationale:</b> Public visibility supports testing of GPU-accelerated ESVO build
     * operations, build queue management, and performance metrics collection. The RegionBuilder
     * is a lazy-initialized component (null before start()) that manages the expensive GPU
     * operations for region construction.
     * <p>
     * <b>Usage Guidelines:</b>
     * <ul>
     *   <li><b>Testing:</b> Integration tests verify build queue behavior, build completion
     *       callbacks, performance metrics, and GPU resource management (see BuildIntegrationTest)</li>
     *   <li><b>Monitoring:</b> External tools can query build statistics (total builds,
     *       failures, average build time, queue depth) via this getter</li>
     *   <li><b>NOT for direct build triggering:</b> Tests should not call RegionBuilder methods
     *       directly - use RegionManager.markDirty() to trigger builds through proper channels</li>
     * </ul>
     * <p>
     * <b>Constraints:</b>
     * <ul>
     *   <li><b>Lifecycle-dependent:</b> Returns null before start() called - always check for null</li>
     *   <li><b>Cleared on stop():</b> Set to null when server stops - reference becomes invalid</li>
     *   <li>Thread-safe - RegionBuilder uses internal synchronization for build operations</li>
     *   <li>GPU operations require proper OpenCL context - test environment must support GPU access</li>
     * </ul>
     *
     * @return RegionBuilder instance, or null if server not started or already stopped
     */
    public RegionBuilder getRegionBuilder() {
        return regionBuilder;
    }

    /**
     * Get the region cache (Phase 2).
     * <p>
     * <b>Visibility: Public</b>
     * <p>
     * <b>Rationale:</b> Public visibility enables testing of cache behavior (pinning,
     * eviction, memory pressure) and monitoring of cache statistics. The RegionCache
     * implements a two-tier caching strategy with pinned regions (in-memory) and
     * unpinned regions (Caffeine-managed with TTL/LRU eviction).
     * <p>
     * <b>Usage Guidelines:</b>
     * <ul>
     *   <li><b>Testing:</b> Integration tests verify cache lifecycle, pinning behavior,
     *       eviction under memory pressure, and cache statistics accuracy (see BuildIntegrationTest)</li>
     *   <li><b>Monitoring:</b> External monitoring tools can query cache health: hit rate,
     *       eviction count, memory usage, pinned/unpinned counts</li>
     *   <li><b>NOT for direct cache manipulation:</b> Tests should not call put()/get()
     *       directly - use RegionManager operations that trigger caching through proper workflow</li>
     * </ul>
     * <p>
     * <b>Constraints:</b>
     * <ul>
     *   <li><b>Lifecycle-dependent:</b> Returns null before start() called - always check for null</li>
     *   <li><b>Cleared on stop():</b> Set to null when server stops - reference becomes invalid</li>
     *   <li>Thread-safe - RegionCache uses ConcurrentHashMap and Caffeine for concurrency</li>
     *   <li>Memory-bounded - Automatically evicts unpinned regions when approaching maxCacheMemoryBytes</li>
     * </ul>
     *
     * @return RegionCache instance, or null if server not started or already stopped
     */
    public RegionCache getRegionCache() {
        return regionCache;
    }

    /**
     * Get the viewport tracker (Phase 3).
     * <p>
     * <b>Visibility: Public</b>
     * <p>
     * <b>Rationale:</b> Public visibility supports testing of client viewport tracking,
     * frustum culling, and visible region calculation. The ViewportTracker maintains
     * per-client viewport state and determines which regions should be streamed based
     * on client camera position and frustum.
     * <p>
     * <b>Usage Guidelines:</b>
     * <ul>
     *   <li><b>Testing:</b> Tests verify viewport registration, viewport updates from
     *       WebSocket messages, frustum culling accuracy, and client lifecycle management</li>
     *   <li><b>Monitoring:</b> External monitoring can query active client count and
     *       per-client viewport state</li>
     *   <li><b>NOT for direct viewport manipulation:</b> Viewport updates should only
     *       come through WebSocket protocol messages, not direct method calls</li>
     * </ul>
     * <p>
     * <b>Constraints:</b>
     * <ul>
     *   <li><b>Lifecycle-dependent:</b> Returns null before start() called - always check for null</li>
     *   <li><b>Cleared on stop():</b> Set to null when server stops - reference becomes invalid</li>
     *   <li>Thread-safe - ViewportTracker uses ConcurrentHashMap for client state</li>
     *   <li>Client state is ephemeral - cleared when WebSocket connection closes</li>
     * </ul>
     *
     * @return ViewportTracker instance, or null if server not started or already stopped
     */
    public ViewportTracker getViewportTracker() {
        return viewportTracker;
    }

    /**
     * Get the region streamer (Phase 3).
     * <p>
     * <b>Visibility: Public</b>
     * <p>
     * <b>Rationale:</b> Public visibility enables testing of WebSocket streaming behavior,
     * client session management, and region delivery verification. The RegionStreamer
     * coordinates region streaming to browser clients based on viewport visibility and
     * region cache availability.
     * <p>
     * <b>Usage Guidelines:</b>
     * <ul>
     *   <li><b>Testing:</b> Integration tests verify WebSocket protocol compliance, region
     *       streaming triggers (viewport updates, build completions), message serialization,
     *       and client session lifecycle (see RenderingServerStreamingTest)</li>
     *   <li><b>Monitoring:</b> External monitoring can query active WebSocket connections,
     *       streaming statistics, and per-client delivery metrics</li>
     *   <li><b>NOT for direct streaming control:</b> Region streaming should be triggered
     *       by proper events (viewport updates, build completions), not direct method calls</li>
     * </ul>
     * <p>
     * <b>Constraints:</b>
     * <ul>
     *   <li><b>Lifecycle-dependent:</b> Returns null before start() called - always check for null</li>
     *   <li><b>Cleared on stop():</b> Set to null when server stops - reference becomes invalid</li>
     *   <li>Thread-safe - RegionStreamer uses internal synchronization and executor for streaming</li>
     *   <li>WebSocket lifecycle coupled to Javalin server - stop() disconnects all clients</li>
     *   <li>Requires RegionCache and ViewportTracker - streaming fails if dependencies unavailable</li>
     * </ul>
     *
     * @return RegionStreamer instance, or null if server not started or already stopped
     */
    public RegionStreamer getRegionStreamer() {
        return regionStreamer;
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Handle /api/health endpoint.
     * <p>
     * Returns: {"status":"healthy","uptime":12345,"regions":42,"entities":156}
     * <p>
     * rp9u: Cached for 1 second to reduce overhead from frequent monitoring polls.
     */
    private void handleHealth(Context ctx) {
        // rp9u: Check cache first
        String cached = endpointCache.getIfPresent("health");
        if (cached != null) {
            ctx.contentType("application/json").result(cached);
            return;
        }

        // Compute fresh response
        long uptime = clock.currentTimeMillis() - startTimeMs;
        var regions = regionManager.getAllRegions();
        int totalEntities = regions.stream()
            .mapToInt(r -> {
                var state = regionManager.getRegionState(r);
                return state != null ? state.entities().size() : 0;
            })
            .sum();

        var response = Map.of(
            "status", "healthy",
            "uptime", uptime,
            "regions", regions.size(),
            "entities", totalEntities
        );

        // Serialize to JSON and cache
        try {
            String jsonString = JSON_MAPPER.writeValueAsString(response);
            endpointCache.put("health", jsonString);
            ctx.contentType("application/json").result(jsonString);
        } catch (Exception e) {
            log.error("Failed to serialize health response", e);
            ctx.json(response);  // Fallback to Javalin's JSON serialization
        }
    }

    /**
     * Handle /api/info endpoint.
     * <p>
     * Returns server capabilities and configuration.
     */
    private void handleInfo(Context ctx) {
        // Use HashMap for >10 entries (Map.of() has max 10 pairs)
        var info = new java.util.HashMap<String, Object>();
        info.put("version", VERSION);
        info.put("port", port());

        // 1sa4: Redact upstream URIs if security enabled
        if (config.security().redactSensitiveInfo()) {
            // Show count only, hide URIs
            info.put("upstreamCount", config.upstreams().size());
        } else {
            // Show full upstream details
            var upstreamsInfo = config.upstreams().stream()
                .map(u -> Map.of(
                    "uri", u.uri().toString(),
                    "label", u.label()
                ))
                .collect(Collectors.toList());
            info.put("upstreams", upstreamsInfo);
        }

        info.put("regionLevel", config.regionLevel());
        info.put("gridResolution", config.build().gridResolution());
        info.put("maxBuildDepth", config.build().maxBuildDepth());
        info.put("buildPoolSize", config.build().buildPoolSize());
        info.put("structureType", "ESVO");  // Default structure type
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
     * <p>
     * rp9u: Cached for 1 second to reduce overhead from frequent monitoring polls.
     */
    private void handleMetrics(Context ctx) {
        // rp9u: Check cache first
        String cached = endpointCache.getIfPresent("metrics");
        if (cached != null) {
            ctx.contentType("application/json").result(cached);
            return;
        }

        // Compute fresh response
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

        // Rate limiter metrics (znlz)
        if (rateLimiter != null) {
            var rateLimitMetrics = new java.util.HashMap<String, Object>();
            rateLimitMetrics.put("rejectionCount", rateLimiter.getRejectionCount());
            metrics.put("rateLimiter", rateLimitMetrics);
        }

        // Serialize to JSON and cache
        try {
            String jsonString = JSON_MAPPER.writeValueAsString(metrics);
            endpointCache.put("metrics", jsonString);
            ctx.contentType("application/json").result(jsonString);
        } catch (Exception e) {
            log.error("Failed to serialize metrics response", e);
            ctx.json(metrics);  // Fallback to Javalin's JSON serialization
        }
    }
}

package com.hellblazer.luciferase.portal.web;

import com.hellblazer.luciferase.common.time.Clock;
import com.hellblazer.luciferase.esvo.io.VOLLoader;
import com.hellblazer.luciferase.portal.web.dto.*;
import com.hellblazer.luciferase.portal.web.service.EntityCapExceededException;
import com.hellblazer.luciferase.portal.web.service.GpuService;
import com.hellblazer.luciferase.portal.web.service.RenderService;
import com.hellblazer.luciferase.portal.web.service.SpatialIndexService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Web server for spatial inspector providing REST API for spatial operations.
 * Follows the ART DemoServer pattern with Javalin framework.
 *
 * <p>Serves web-based versions of:
 * <ul>
 *   <li>Spatial Index operations (Octree, Tetree, SFCArrayIndex)</li>
 *   <li>ESVO/ESVT rendering</li>
 *   <li>GPU OpenCL acceleration</li>
 *   <li>Ray casting and queries</li>
 * </ul>
 */
public class SpatialInspectorServer {

    private static final Logger log = LoggerFactory.getLogger(SpatialInspectorServer.class);
    private static final int DEFAULT_PORT = 7071;
    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes
    static final int MAX_SESSIONS = 100;
    private static final long REAPER_PERIOD_MS = 60_000; // check every minute

    /** Maximum number of entities allowed in a single bulk-insert array. */
    static final int DEFAULT_MAX_BULK_INSERT = 10_000;

    private final Map<String, SpatialSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionCount = new AtomicInteger(0);
    private final SpatialIndexService spatialService;
    private final int maxBulkInsert;
    private final RenderService renderService = new RenderService();
    private final GpuService gpuService = new GpuService();
    private final Javalin app;
    private final int port;
    private final Clock clock;
    private final ScheduledExecutorService reaperExecutor;

    /**
     * Create server with default port.
     */
    public SpatialInspectorServer() {
        this(DEFAULT_PORT);
    }

    /**
     * Create server with specified port.
     * Use port 0 for dynamic port assignment (useful for testing).
     */
    public SpatialInspectorServer(int port) {
        this(port, Clock.system());
    }

    /**
     * Create server with specified port and clock.
     * Use this constructor in tests to inject a {@link com.hellblazer.luciferase.common.time.Clock}
     * for deterministic time control.
     */
    public SpatialInspectorServer(int port, Clock clock) {
        this(port, clock, DEFAULT_MAX_BULK_INSERT, SpatialIndexService.DEFAULT_MAX_SESSION_ENTITIES);
    }

    /**
     * Full constructor allowing cap configuration.
     * Use in tests to lower caps without modifying defaults.
     *
     * @param port             listening port (0 = dynamic)
     * @param clock            injectable clock for deterministic tests
     * @param maxBulkInsert    maximum entities in a single bulk-insert request (→ 413 if exceeded)
     * @param maxSessionEntities maximum total entities per session (→ 413 if exceeded)
     */
    public SpatialInspectorServer(int port, Clock clock, int maxBulkInsert, int maxSessionEntities) {
        this.port = port;
        this.clock = clock;
        this.maxBulkInsert = maxBulkInsert;
        this.spatialService = new SpatialIndexService(maxSessionEntities);
        this.reaperExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "session-reaper");
            t.setDaemon(true);
            return t;
        });
        this.app = createApp();
    }

    private Javalin createApp() {
        var javalin = Javalin.create(config -> {
            config.staticFiles.add("/web");
            config.http.defaultContentType = "application/json";
            config.http.maxRequestSize = 10_000_000L; // 10MB for large meshes like bunny
        });

        // Global exception handler
        javalin.exception(Exception.class, (e, ctx) -> {
            log.error("Request failed: {}", ctx.path(), e);
            ctx.status(500).json(Map.of(
                "error", e.getMessage(),
                "type", e.getClass().getSimpleName(),
                "timestamp", Instant.now().toString()
            ));
        });

        // Register endpoints
        registerHealthEndpoints(javalin);
        registerSessionEndpoints(javalin);
        registerSpatialEndpoints(javalin);
        registerRenderEndpoints(javalin);
        registerGpuEndpoints(javalin);
        registerMeshEndpoints(javalin);

        // Exception handlers for specific types
        javalin.exception(EntityCapExceededException.class, (e, ctx) -> {
            ctx.status(413).json(Map.of(
                "error", e.getMessage(),
                "type", "PayloadTooLarge",
                "timestamp", Instant.now().toString()
            ));
        });

        javalin.exception(NoSuchElementException.class, (e, ctx) -> {
            ctx.status(404).json(Map.of(
                "error", e.getMessage(),
                "type", "NotFound",
                "timestamp", Instant.now().toString()
            ));
        });

        javalin.exception(IllegalArgumentException.class, (e, ctx) -> {
            ctx.status(400).json(Map.of(
                "error", e.getMessage(),
                "type", "BadRequest",
                "timestamp", Instant.now().toString()
            ));
        });

        javalin.exception(IllegalStateException.class, (e, ctx) -> {
            ctx.status(409).json(Map.of(
                "error", e.getMessage(),
                "type", "Conflict",
                "timestamp", Instant.now().toString()
            ));
        });

        return javalin;
    }

    // ========== Health Endpoints ==========

    private void registerHealthEndpoints(Javalin app) {
        app.get("/api/health", this::healthCheck);
        app.get("/api/info", this::serverInfo);
    }

    private void healthCheck(Context ctx) {
        ctx.json(Map.of(
            "status", "ok",
            "timestamp", Instant.now().toString()
        ));
    }

    private void serverInfo(Context ctx) {
        ctx.json(Map.of(
            "name", "Luciferase Spatial Inspector",
            "version", "0.0.3-SNAPSHOT",
            "capabilities", Map.of(
                "spatialIndices", new String[]{"octree", "tetree", "sfc"},
                "rendering", new String[]{"esvo", "esvt"},
                "gpu", "pending"
            ),
            "activeSessions", sessions.size(),
            "timestamp", Instant.now().toString()
        ));
    }

    // ========== Session Endpoints ==========

    private void registerSessionEndpoints(Javalin app) {
        app.post("/api/session/create", this::createSession);
        app.get("/api/session/{id}", this::getSession);
        app.delete("/api/session/{id}", this::deleteSession);
    }

    private void createSession(Context ctx) {
        // Atomically reserve a slot before creating the session to prevent TOCTOU overshoot.
        if (sessionCount.incrementAndGet() > MAX_SESSIONS) {
            sessionCount.decrementAndGet();
            ctx.status(429).json(Map.of(
                "error", "Session limit reached. Maximum " + MAX_SESSIONS + " concurrent sessions allowed.",
                "type", "TooManyRequests",
                "timestamp", Instant.now().toString()
            ));
            return;
        }
        var session = SpatialSession.create(clock);
        sessions.put(session.id(), session);
        log.info("Created session: {}", session.id());
        ctx.status(201).json(Map.of(
            "sessionId", session.id(),
            "created", session.created().toString(),
            "message", "Session created successfully"
        ));
    }

    private void getSession(Context ctx) {
        var sessionId = ctx.pathParam("id");
        var session = sessions.get(sessionId);

        if (session == null) {
            ctx.status(404).json(Map.of(
                "error", "Session not found",
                "sessionId", sessionId
            ));
            return;
        }

        // Touch session to update lastAccessed
        sessions.put(sessionId, session.touch(clock));

        ctx.json(Map.of(
            "sessionId", session.id(),
            "created", session.created().toString(),
            "lastAccessed", session.lastAccessed().toString(),
            "expired", session.isExpired(SESSION_TIMEOUT_MS, clock.currentTimeMillis())
        ));
    }

    private void deleteSession(Context ctx) {
        var sessionId = ctx.pathParam("id");
        var session = sessions.remove(sessionId);

        if (session == null) {
            ctx.status(404).json(Map.of(
                "error", "Session not found",
                "sessionId", sessionId
            ));
            return;
        }

        // Only decrement when a session was actually present/removed — guards against double-decrement.
        sessionCount.decrementAndGet();
        cleanupSessionResources(sessionId);

        log.info("Deleted session: {}", sessionId);
        ctx.json(Map.of(
            "message", "Session deleted",
            "sessionId", sessionId
        ));
    }

    // ========== Spatial Index Endpoints ==========

    private void registerSpatialEndpoints(Javalin app) {
        // Index management
        app.post("/api/spatial/create", this::createSpatialIndex);
        app.get("/api/spatial/info", this::getSpatialIndexInfo);
        app.delete("/api/spatial", this::deleteSpatialIndex);

        // Entity operations
        app.post("/api/spatial/entities/insert", this::insertEntity);
        app.post("/api/spatial/entities/bulk-insert", this::bulkInsertEntities);
        app.delete("/api/spatial/entities/{entityId}", this::removeEntity);
        app.put("/api/spatial/entities/update", this::updateEntity);
        app.get("/api/spatial/entities", this::listEntities);

        // Query operations
        app.post("/api/spatial/query/range", this::rangeQuery);
        app.post("/api/spatial/query/knn", this::knnQuery);
        app.post("/api/spatial/query/ray", this::rayQuery);
    }

    private void createSpatialIndex(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var request = ctx.bodyAsClass(CreateIndexRequest.class);
        var info = spatialService.createIndex(sessionId, request);

        ctx.status(201).json(info);
    }

    private void getSpatialIndexInfo(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var info = spatialService.getIndexInfo(sessionId);
        ctx.json(info);
    }

    private void deleteSpatialIndex(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        spatialService.deleteIndex(sessionId);
        ctx.json(Map.of(
            "message", "Spatial index deleted",
            "sessionId", sessionId
        ));
    }

    private void insertEntity(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var request = ctx.bodyAsClass(InsertEntityRequest.class);
        var entity = spatialService.insertEntity(sessionId, request);

        ctx.status(201).json(entity);
    }

    private void bulkInsertEntities(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var requests = ctx.bodyAsClass(InsertEntityRequest[].class);
        if (requests.length > maxBulkInsert) {
            throw new EntityCapExceededException(
                    "Bulk-insert array length " + requests.length
                    + " exceeds maximum of " + maxBulkInsert + " entities per request.");
        }
        var entities = spatialService.insertEntities(sessionId, List.of(requests));

        ctx.status(201).json(Map.of(
            "inserted", entities.size(),
            "entities", entities
        ));
    }

    private void removeEntity(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var entityId = ctx.pathParam("entityId");
        var removed = spatialService.removeEntity(sessionId, entityId);

        if (!removed) {
            throw new NoSuchElementException("Entity not found: " + entityId);
        }

        ctx.json(Map.of(
            "message", "Entity removed",
            "entityId", entityId
        ));
    }

    private void updateEntity(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var request = ctx.bodyAsClass(UpdateEntityRequest.class);
        var entity = spatialService.updateEntity(sessionId, request);

        ctx.json(entity);
    }

    private void listEntities(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var page = ctx.queryParamAsClass("page", Integer.class).getOrDefault(0);
        var size = ctx.queryParamAsClass("size", Integer.class).getOrDefault(20);

        var response = spatialService.listEntities(sessionId, page, size);
        ctx.json(response);
    }

    // ========== Query Endpoints ==========

    private void rangeQuery(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var request = ctx.bodyAsClass(RangeQueryRequest.class);
        var results = spatialService.rangeQuery(sessionId, request);

        ctx.json(Map.of(
            "count", results.size(),
            "entities", results
        ));
    }

    private void knnQuery(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var request = ctx.bodyAsClass(KnnQueryRequest.class);
        var results = spatialService.knnQuery(sessionId, request);

        ctx.json(Map.of(
            "count", results.size(),
            "entities", results
        ));
    }

    private void rayQuery(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var request = ctx.bodyAsClass(RayQueryRequest.class);
        var results = spatialService.rayQuery(sessionId, request);

        ctx.json(Map.of(
            "count", results.size(),
            "hits", results
        ));
    }

    // ========== Render Endpoints ==========

    private void registerRenderEndpoints(Javalin app) {
        app.post("/api/render/create", this::createRender);
        app.get("/api/render/info", this::getRenderInfo);
        app.delete("/api/render", this::deleteRender);
        app.post("/api/render/camera", this::setCamera);
        app.post("/api/render/raycast", this::raycast);
        app.get("/api/render/stats", this::getRenderStats);
    }

    private void createRender(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        // Check that spatial index exists
        if (!spatialService.hasIndex(sessionId)) {
            throw new IllegalStateException("No spatial index exists for session. Create one first.");
        }

        var request = ctx.bodyAsClass(CreateRenderRequest.class);
        var spatialIndex = spatialService.getIndex(sessionId);
        var info = renderService.createRender(sessionId, spatialIndex, request);

        ctx.status(201).json(info);
    }

    private void getRenderInfo(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var info = renderService.getRenderInfo(sessionId);
        ctx.json(info);
    }

    private void deleteRender(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        renderService.deleteRender(sessionId);
        ctx.json(Map.of(
            "message", "Render structure deleted",
            "sessionId", sessionId
        ));
    }

    private void setCamera(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var request = ctx.bodyAsClass(CameraRequest.class);
        renderService.setCamera(sessionId, request);

        ctx.json(Map.of(
            "message", "Camera updated",
            "sessionId", sessionId
        ));
    }

    private void raycast(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var request = ctx.bodyAsClass(RaycastRequest.class);
        var result = renderService.raycast(sessionId, request);

        ctx.json(result);
    }

    private void getRenderStats(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var stats = renderService.getStats(sessionId);
        ctx.json(stats);
    }

    // ========== GPU Endpoints ==========

    private void registerGpuEndpoints(Javalin app) {
        app.get("/api/gpu/info", this::getGpuInfo);
        app.post("/api/gpu/enable", this::enableGpu);
        app.post("/api/gpu/disable", this::disableGpu);
        app.post("/api/gpu/render", this::gpuRender);
        app.post("/api/gpu/benchmark", this::gpuBenchmark);
        app.get("/api/gpu/stats", this::getGpuStats);
    }

    private void getGpuInfo(Context ctx) {
        try {
            var info = gpuService.getGpuInfo();
            ctx.json(info);
        } catch (Exception | Error e) {
            // Handle case where OpenCL libraries aren't available at all
            log.debug("GPU info unavailable: {}", e.getMessage());
            ctx.json(Map.of(
                "available", false,
                "reason", "OpenCL not available: " + e.getMessage()
            ));
        }
    }

    private void enableGpu(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        // Check that ESVT render structure exists
        if (!renderService.hasRender(sessionId)) {
            throw new IllegalStateException("No ESVT render structure exists. Create one first with POST /api/render/create");
        }

        var request = ctx.bodyAsClass(GpuEnableRequest.class);

        // Get ESVT data from render service - need to expose this
        var esvtData = renderService.getESVTData(sessionId);
        if (esvtData == null) {
            throw new IllegalStateException("Session does not have ESVT data. GPU requires ESVT render type.");
        }

        var stats = gpuService.enableGpu(sessionId, esvtData, request);
        ctx.status(201).json(stats);
    }

    private void disableGpu(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        gpuService.disableGpu(sessionId);
        ctx.json(Map.of(
            "message", "GPU disabled",
            "sessionId", sessionId
        ));
    }

    private void gpuRender(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var request = ctx.bodyAsClass(GpuRenderRequest.class);
        var result = gpuService.render(sessionId, request);

        ctx.json(result);
    }

    private void gpuBenchmark(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var iterations = ctx.queryParamAsClass("iterations", Integer.class).getOrDefault(10);
        var result = gpuService.benchmark(sessionId, iterations);

        ctx.json(result);
    }

    private void getGpuStats(Context ctx) {
        var sessionId = requireSessionId(ctx);
        validateSession(sessionId);

        var stats = gpuService.getStats(sessionId);
        ctx.json(stats);
    }

    // ========== Mesh Endpoints ==========

    private final VOLLoader volLoader = new VOLLoader();
    private volatile List<Map<String, Object>> cachedBunnyVoxels = null;

    private void registerMeshEndpoints(Javalin app) {
        app.get("/api/mesh/bunny", this::getBunnyMesh);
        app.get("/api/mesh/list", this::listMeshes);
    }

    private void getBunnyMesh(Context ctx) {
        // Lazy load and cache the bunny voxels
        if (cachedBunnyVoxels == null) {
            synchronized (this) {
                if (cachedBunnyVoxels == null) {
                    try {
                        var volData = volLoader.loadResource("/voxels/bunny-64.vol");
                        var header = volData.header();
                        float maxDim = Math.max(header.dimX(), Math.max(header.dimY(), header.dimZ()));

                        // Convert to normalized [0,1] coordinates
                        // VOL has: X=up/down, Y=front/back, Z=left/right
                        // Three.js needs: Y=up, so map X->Y, Z->X, Y->Z
                        cachedBunnyVoxels = volData.voxels().stream()
                            .map(v -> {
                                var map = new java.util.HashMap<String, Object>();
                                map.put("x", (v.z + 0.5f) / maxDim);  // Z -> X
                                map.put("y", (v.x + 0.5f) / maxDim);  // X -> Y (head up)
                                map.put("z", (v.y + 0.5f) / maxDim);  // Y -> Z
                                map.put("content", null);
                                return (Map<String, Object>) map;
                            })
                            .toList();

                        log.info("Loaded Stanford Bunny: {} voxels from {}³ grid",
                                cachedBunnyVoxels.size(), (int) maxDim);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load bunny mesh: " + e.getMessage(), e);
                    }
                }
            }
        }

        ctx.json(Map.of(
            "name", "Stanford Bunny",
            "voxelCount", cachedBunnyVoxels.size(),
            "entities", cachedBunnyVoxels
        ));
    }

    private void listMeshes(Context ctx) {
        ctx.json(Map.of(
            "meshes", List.of(
                Map.of("id", "bunny", "name", "Stanford Bunny", "description", "Classic 3D test model")
            )
        ));
    }

    // ========== Helper Methods ==========

    /**
     * Release all per-session service-side resources for the given session ID.
     * Safe to call multiple times — each guard check prevents double-free.
     */
    private void cleanupSessionResources(String sessionId) {
        try {
            if (gpuService.isGpuEnabled(sessionId)) {
                gpuService.disableGpu(sessionId);
            }
        } catch (Exception e) {
            log.warn("Error disabling GPU for session {}: {}", sessionId, e.getMessage());
        }
        try {
            if (renderService.hasRender(sessionId)) {
                renderService.deleteRender(sessionId);
            }
        } catch (Exception e) {
            log.warn("Error deleting render for session {}: {}", sessionId, e.getMessage());
        }
        try {
            if (spatialService.hasIndex(sessionId)) {
                spatialService.deleteIndex(sessionId);
            }
        } catch (Exception e) {
            log.warn("Error deleting spatial index for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Evict all sessions that have expired based on {@code SESSION_TIMEOUT_MS}.
     * Calls {@link #cleanupSessionResources(String)} for each evicted session.
     * <p>
     * Package-private so tests can invoke it directly after advancing the injected clock,
     * without waiting for the scheduler period.
     */
    void runReaper() {
        long now = clock.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired(SESSION_TIMEOUT_MS, now)) {
                sessionCount.decrementAndGet();
                cleanupSessionResources(entry.getKey());
                log.info("Reaped expired session: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    private String requireSessionId(Context ctx) {
        var sessionId = ctx.queryParam("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId query parameter is required");
        }
        return sessionId;
    }

    private void validateSession(String sessionId) {
        var session = sessions.get(sessionId);
        if (session == null) {
            throw new NoSuchElementException("Session not found: " + sessionId);
        }
        // Reject expired sessions rather than silently refreshing them
        if (session.isExpired(SESSION_TIMEOUT_MS, clock.currentTimeMillis())) {
            // Guard: only decrement (and clean up) when the remove actually succeeds —
            // prevents double-decrement if two threads race to validate the same expired id.
            var removed = sessions.remove(sessionId);
            if (removed != null) {
                sessionCount.decrementAndGet();
                cleanupSessionResources(sessionId);
            }
            throw new NoSuchElementException("Session expired: " + sessionId);
        }
        // Touch session to update lastAccessed
        sessions.put(sessionId, session.touch(clock));
    }

    // ========== Server Lifecycle ==========

    /**
     * Start the server.
     */
    public void start() {
        reaperExecutor.scheduleAtFixedRate(this::runReaper, REAPER_PERIOD_MS, REAPER_PERIOD_MS, TimeUnit.MILLISECONDS);
        app.start(port);
        var actualPort = app.port();
        log.info("=".repeat(70));
        log.info("Luciferase Spatial Inspector Server started on http://localhost:{}", actualPort);
        log.info("Endpoints:");
        log.info("  - Health:    GET  /api/health");
        log.info("  - Info:      GET  /api/info");
        log.info("  - Sessions:  POST /api/session/create");
        log.info("               GET  /api/session/{{id}}");
        log.info("               DELETE /api/session/{{id}}");
        log.info("  - Spatial:   POST /api/spatial/create?sessionId={{id}}");
        log.info("               GET  /api/spatial/info?sessionId={{id}}");
        log.info("               DELETE /api/spatial?sessionId={{id}}");
        log.info("  - Entities:  POST /api/spatial/entities/insert?sessionId={{id}}");
        log.info("               POST /api/spatial/entities/bulk-insert?sessionId={{id}}");
        log.info("               DELETE /api/spatial/entities/{{entityId}}?sessionId={{id}}");
        log.info("               PUT  /api/spatial/entities/update?sessionId={{id}}");
        log.info("               GET  /api/spatial/entities?sessionId={{id}}&page=0&size=20");
        log.info("  - Web UI:    http://localhost:{}/index.html", actualPort);
        log.info("=".repeat(70));
    }

    /**
     * Stop the server and cleanup resources.
     */
    public void stop() {
        log.info("Stopping Spatial Inspector Server...");

        // Shut down the reaper so no concurrent cleanup races with our full shutdown
        reaperExecutor.shutdown();
        try {
            reaperExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Free all per-session service resources before clearing the map
        var sessionIds = new ArrayList<>(sessions.keySet());
        for (var id : sessionIds) {
            cleanupSessionResources(id);
        }
        int cleared = sessionIds.size();
        sessions.clear();
        sessionCount.addAndGet(-cleared);

        app.stop();
        log.info("Server stopped");
    }

    /**
     * Get the actual port the server is running on.
     * Useful when started with port 0 for dynamic assignment.
     */
    public int port() {
        return app.port();
    }

    /**
     * Get the Javalin app instance (for testing).
     */
    public Javalin app() {
        return app;
    }

    /** Package-private — test access to service state assertions. */
    SpatialIndexService spatialService() {
        return spatialService;
    }

    /** Package-private — test access to service state assertions. */
    RenderService renderService() {
        return renderService;
    }

    /** Package-private — test access to service state assertions. */
    GpuService gpuService() {
        return gpuService;
    }

    /** Package-private — test access to the session count (atomic counter, not map size). */
    int sessionCount() {
        return sessionCount.get();
    }

    /**
     * Main entry point for standalone server.
     */
    public static void main(String[] args) {
        var port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        var server = new SpatialInspectorServer(port);
        server.start();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}

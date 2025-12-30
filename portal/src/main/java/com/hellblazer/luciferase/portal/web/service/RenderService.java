package com.hellblazer.luciferase.portal.web.service;

import com.hellblazer.luciferase.esvo.core.OctreeBuilder;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvt.builder.ESVTBuilder;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.traversal.ESVTRay;
import com.hellblazer.luciferase.esvt.traversal.ESVTTraversal;
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.portal.web.dto.*;
import com.hellblazer.luciferase.portal.web.dto.CreateRenderRequest.RenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing ESVO/ESVT render structures via REST API.
 * Supports building render structures from spatial indices and performing raycasts.
 */
public class RenderService {

    private static final Logger log = LoggerFactory.getLogger(RenderService.class);
    private static final int DEFAULT_MAX_DEPTH = 10;
    private static final int DEFAULT_GRID_RESOLUTION = 64;

    private final Map<String, RenderHolder> renders = new ConcurrentHashMap<>();
    private final Map<String, CameraState> cameras = new ConcurrentHashMap<>();

    /**
     * Create a render structure from the spatial index data.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RenderInfo createRender(String sessionId, Object spatialIndex, CreateRenderRequest request) {
        if (renders.containsKey(sessionId)) {
            throw new IllegalStateException("Session already has a render structure. Delete it first.");
        }

        var type = request.type();
        var maxDepth = request.maxDepth() != null ? request.maxDepth() : DEFAULT_MAX_DEPTH;
        var gridResolution = request.gridResolution() != null ? request.gridResolution() : DEFAULT_GRID_RESOLUTION;

        RenderHolder holder;
        if (type == RenderType.ESVT) {
            holder = createESVT(spatialIndex, maxDepth, gridResolution);
        } else {
            holder = createESVO(spatialIndex, maxDepth, gridResolution);
        }

        renders.put(sessionId, holder);
        log.info("Created {} render for session {} with maxDepth={}, gridRes={}",
                type, sessionId, maxDepth, gridResolution);

        return getRenderInfo(sessionId);
    }

    /**
     * Get information about the render structure for a session.
     */
    public RenderInfo getRenderInfo(String sessionId) {
        var holder = getHolder(sessionId);
        return new RenderInfo(
                sessionId,
                holder.type().name().toLowerCase(),
                holder.nodeCount(),
                holder.leafCount(),
                holder.internalCount(),
                holder.maxDepth(),
                holder.gridResolution()
        );
    }

    /**
     * Delete the render structure for a session.
     */
    public void deleteRender(String sessionId) {
        var removed = renders.remove(sessionId);
        if (removed == null) {
            throw new NoSuchElementException("No render structure found for session: " + sessionId);
        }
        cameras.remove(sessionId);
        log.info("Deleted render structure for session {}", sessionId);
    }

    /**
     * Set camera position and orientation for a session.
     */
    public void setCamera(String sessionId, CameraRequest request) {
        var camera = new CameraState(
                new Point3f(request.posX(), request.posY(), request.posZ()),
                new Point3f(request.targetX(), request.targetY(), request.targetZ()),
                new Vector3f(request.upX(), request.upY(), request.upZ()),
                request.fov() != null ? request.fov() : 60.0f
        );
        cameras.put(sessionId, camera);
        log.debug("Set camera for session {}: pos=({},{},{})",
                sessionId, request.posX(), request.posY(), request.posZ());
    }

    /**
     * Perform a raycast through the render structure.
     */
    public RaycastResult raycast(String sessionId, RaycastRequest request) {
        var holder = getHolder(sessionId);

        // Normalize direction
        var dirLen = (float) Math.sqrt(
                request.directionX() * request.directionX() +
                request.directionY() * request.directionY() +
                request.directionZ() * request.directionZ()
        );
        if (dirLen < 1e-6f) {
            return RaycastResult.miss();
        }
        var dirX = request.directionX() / dirLen;
        var dirY = request.directionY() / dirLen;
        var dirZ = request.directionZ() / dirLen;

        if (holder.type() == RenderType.ESVO) {
            return raycastESVO(holder, request.originX(), request.originY(), request.originZ(),
                    dirX, dirY, dirZ);
        } else {
            return raycastESVT(holder, request.originX(), request.originY(), request.originZ(),
                    dirX, dirY, dirZ);
        }
    }

    /**
     * Get rendering statistics.
     */
    public RenderStats getStats(String sessionId) {
        var holder = getHolder(sessionId);
        return new RenderStats(
                holder.type().name().toLowerCase(),
                holder.nodeCount(),
                holder.leafCount(),
                holder.internalCount(),
                holder.maxDepth(),
                holder.memoryBytes(),
                holder.farPointerCount()
        );
    }

    /**
     * Check if a session has a render structure.
     */
    public boolean hasRender(String sessionId) {
        return renders.containsKey(sessionId);
    }

    // ===== Private Helpers =====

    private RenderHolder getHolder(String sessionId) {
        var holder = renders.get(sessionId);
        if (holder == null) {
            throw new NoSuchElementException("No render structure found for session: " + sessionId);
        }
        return holder;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RenderHolder createESVT(Object spatialIndex, int maxDepth, int gridResolution) {
        if (!(spatialIndex instanceof Tetree)) {
            throw new IllegalArgumentException("ESVT requires a Tetree spatial index");
        }

        var tetree = (Tetree<UUIDEntityID, Object>) spatialIndex;
        var builder = new ESVTBuilder();
        var data = builder.build(tetree);

        return new RenderHolder(
                RenderType.ESVT,
                null,
                data,
                data.nodes().length,
                data.leafCount(),
                data.internalCount(),
                data.maxDepth(),
                gridResolution,
                (long) data.nodes().length * 8,
                data.farPointers().length
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RenderHolder createESVO(Object spatialIndex, int maxDepth, int gridResolution) {
        // Build ESVO from spatial index positions
        var positions = getPositions(spatialIndex);
        var voxels = new ArrayList<Point3i>();

        // Convert positions to voxel coordinates
        for (var pos : positions) {
            int x = (int) (pos.x * gridResolution);
            int y = (int) (pos.y * gridResolution);
            int z = (int) (pos.z * gridResolution);
            x = Math.max(0, Math.min(gridResolution - 1, x));
            y = Math.max(0, Math.min(gridResolution - 1, y));
            z = Math.max(0, Math.min(gridResolution - 1, z));
            voxels.add(new Point3i(x, y, z));
        }

        try (var builder = new OctreeBuilder(maxDepth)) {
            var octreeData = builder.buildFromVoxels(voxels, maxDepth);

            return new RenderHolder(
                    RenderType.ESVO,
                    octreeData,
                    null,
                    octreeData.nodeCount(),
                    octreeData.leafCount(),
                    octreeData.internalCount(),
                    octreeData.maxDepth(),
                    gridResolution,
                    octreeData.sizeInBytes(),
                    octreeData.getFarPointers().length
            );
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Point3f> getPositions(Object spatialIndex) {
        if (spatialIndex instanceof Octree octree) {
            Map map = octree.getEntitiesWithPositions();
            return new ArrayList<>(map.values());
        } else if (spatialIndex instanceof Tetree tetree) {
            Map map = tetree.getEntitiesWithPositions();
            return new ArrayList<>(map.values());
        } else {
            throw new IllegalArgumentException("Unknown spatial index type: " + spatialIndex.getClass());
        }
    }

    private RaycastResult raycastESVO(RenderHolder holder, float ox, float oy, float oz,
                                       float dx, float dy, float dz) {
        var octreeData = holder.esvoData();
        if (octreeData == null || octreeData.nodeCount() == 0) {
            return RaycastResult.miss();
        }

        // ESVO raycast requires node format conversion - simplified for now
        // Full ESVO GPU raycast will be available in Phase 4
        log.debug("ESVO CPU raycast not yet implemented, returning miss");
        return RaycastResult.miss();
    }

    private RaycastResult raycastESVT(RenderHolder holder, float ox, float oy, float oz,
                                       float dx, float dy, float dz) {
        var esvtData = holder.esvtData();
        if (esvtData == null || esvtData.nodes().length == 0) {
            return RaycastResult.miss();
        }

        var ray = new ESVTRay(ox, oy, oz, dx, dy, dz);
        var traversal = new ESVTTraversal();
        var result = traversal.castRay(ray, esvtData.nodes(), esvtData.rootType());

        if (result != null && result.hit) {
            var normal = result.normal;
            return new RaycastResult(
                    true,
                    result.t,
                    result.x,
                    result.y,
                    result.z,
                    normal != null ? normal.x : 0,
                    normal != null ? normal.y : 0,
                    normal != null ? normal.z : 0,
                    result.scale,
                    result.iterations
            );
        }
        return RaycastResult.miss();
    }

    /**
     * Camera state for a session.
     */
    private record CameraState(
            Point3f position,
            Point3f target,
            Vector3f up,
            float fov
    ) {}

    /**
     * Internal holder for render data with metadata.
     */
    private record RenderHolder(
            RenderType type,
            ESVOOctreeData esvoData,
            ESVTData esvtData,
            int nodeCount,
            int leafCount,
            int internalCount,
            int maxDepth,
            int gridResolution,
            long memoryBytes,
            int farPointerCount
    ) {}
}

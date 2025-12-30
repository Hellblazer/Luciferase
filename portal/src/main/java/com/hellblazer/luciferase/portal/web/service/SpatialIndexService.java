package com.hellblazer.luciferase.portal.web.service;

import com.hellblazer.luciferase.lucien.Ray3D;
import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.SpatialIndex;
import com.hellblazer.luciferase.lucien.entity.UUIDEntityID;
import com.hellblazer.luciferase.lucien.octree.Octree;
import com.hellblazer.luciferase.lucien.sfc.SFCArrayIndex;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.portal.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing spatial indices via REST API.
 * Supports Octree, Tetree, and SFCArrayIndex types.
 */
public class SpatialIndexService {

    private static final Logger log = LoggerFactory.getLogger(SpatialIndexService.class);
    private static final byte DEFAULT_LEVEL = 10;

    // Session ID -> Spatial Index
    private final Map<String, SpatialIndexHolder> indices = new ConcurrentHashMap<>();

    /**
     * Create a new spatial index for the given session.
     */
    public SpatialIndexInfo createIndex(String sessionId, CreateIndexRequest request) {
        if (indices.containsKey(sessionId)) {
            throw new IllegalStateException("Session already has a spatial index. Delete it first.");
        }

        var holder = createIndexHolder(request);
        indices.put(sessionId, holder);

        log.info("Created {} index for session {} with maxDepth={}, maxEntitiesPerNode={}",
                request.indexType(), sessionId, request.maxDepth(), request.maxEntitiesPerNode());

        return getIndexInfo(sessionId);
    }

    /**
     * Get information about the spatial index for a session.
     */
    public SpatialIndexInfo getIndexInfo(String sessionId) {
        var holder = getHolder(sessionId);
        var index = holder.index();

        return new SpatialIndexInfo(
                sessionId,
                holder.type().name().toLowerCase(),
                index.entityCount(),
                index.nodeCount(),
                holder.maxDepth(),
                holder.maxEntitiesPerNode()
        );
    }

    /**
     * Delete the spatial index for a session.
     */
    public void deleteIndex(String sessionId) {
        var removed = indices.remove(sessionId);
        if (removed == null) {
            throw new NoSuchElementException("No spatial index found for session: " + sessionId);
        }
        log.info("Deleted spatial index for session {}", sessionId);
    }

    /**
     * Insert an entity into the spatial index.
     */
    public EntityInfo insertEntity(String sessionId, InsertEntityRequest request) {
        var holder = getHolder(sessionId);
        var index = holder.index();

        var position = new Point3f(request.x(), request.y(), request.z());
        var content = request.content() != null ? request.content() : Map.of();
        var level = holder.maxDepth();

        var entityId = index.insert(position, level, content);

        log.debug("Inserted entity {} at ({}, {}, {}) in session {}",
                entityId, request.x(), request.y(), request.z(), sessionId);

        return new EntityInfo(
                entityId.toString(),
                request.x(),
                request.y(),
                request.z(),
                content
        );
    }

    /**
     * Insert multiple entities in bulk.
     */
    public List<EntityInfo> insertEntities(String sessionId, List<InsertEntityRequest> requests) {
        var holder = getHolder(sessionId);
        var index = holder.index();
        var level = holder.maxDepth();

        var positions = new ArrayList<Point3f>(requests.size());
        var contents = new ArrayList<Object>(requests.size());

        for (var request : requests) {
            positions.add(new Point3f(request.x(), request.y(), request.z()));
            contents.add(request.content() != null ? request.content() : Map.of());
        }

        var entityIds = index.insertBatch(positions, contents, level);

        var results = new ArrayList<EntityInfo>(requests.size());
        for (var i = 0; i < entityIds.size(); i++) {
            var request = requests.get(i);
            results.add(new EntityInfo(
                    entityIds.get(i).toString(),
                    request.x(),
                    request.y(),
                    request.z(),
                    contents.get(i)
            ));
        }

        log.info("Bulk inserted {} entities in session {}", requests.size(), sessionId);
        return results;
    }

    /**
     * Remove an entity by ID.
     */
    public boolean removeEntity(String sessionId, String entityIdStr) {
        var holder = getHolder(sessionId);
        var index = holder.index();

        var entityId = new UUIDEntityID(UUID.fromString(entityIdStr));
        var removed = index.removeEntity(entityId);

        if (removed) {
            log.debug("Removed entity {} from session {}", entityIdStr, sessionId);
        }

        return removed;
    }

    /**
     * Update an entity's position.
     */
    public EntityInfo updateEntity(String sessionId, UpdateEntityRequest request) {
        var holder = getHolder(sessionId);
        var index = holder.index();
        var level = holder.maxDepth();

        var entityId = new UUIDEntityID(UUID.fromString(request.entityId()));
        var newPosition = new Point3f(request.x(), request.y(), request.z());

        // Check if entity exists
        if (!index.containsEntity(entityId)) {
            throw new NoSuchElementException("Entity not found: " + request.entityId());
        }

        // Update by removing and re-inserting with same ID
        var content = index.getEntity(entityId);
        index.removeEntity(entityId);
        index.insert(entityId, newPosition, level, content);

        log.debug("Updated entity {} to ({}, {}, {}) in session {}",
                request.entityId(), request.x(), request.y(), request.z(), sessionId);

        return new EntityInfo(
                request.entityId(),
                request.x(),
                request.y(),
                request.z(),
                content
        );
    }

    /**
     * List entities with pagination.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public EntityListResponse listEntities(String sessionId, int page, int size) {
        var holder = getHolder(sessionId);
        var index = holder.index();

        Map entitiesWithPositions = index.getEntitiesWithPositions();
        var totalCount = entitiesWithPositions.size();
        var totalPages = (int) Math.ceil((double) totalCount / size);

        // Convert to list for pagination
        var entityList = new ArrayList<Map.Entry>(entitiesWithPositions.entrySet());
        var startIndex = page * size;
        var endIndex = Math.min(startIndex + size, totalCount);

        var entities = new ArrayList<EntityInfo>();
        if (startIndex < totalCount) {
            for (var i = startIndex; i < endIndex; i++) {
                var entry = entityList.get(i);
                var entityId = entry.getKey();
                var position = (Point3f) entry.getValue();
                // Using raw getEntity due to type erasure with raw SpatialIndex
                var content = index.getEntity((com.hellblazer.luciferase.lucien.entity.EntityID) entityId);

                entities.add(new EntityInfo(
                        entityId.toString(),
                        position.x,
                        position.y,
                        position.z,
                        content
                ));
            }
        }

        return new EntityListResponse(entities, page, size, totalCount, totalPages);
    }

    /**
     * Range query - find all entities within a bounding box.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<EntityInfo> rangeQuery(String sessionId, RangeQueryRequest request) {
        var holder = getHolder(sessionId);
        var index = holder.index();

        // Calculate center and extent from min/max
        var centerX = (request.minX() + request.maxX()) / 2;
        var centerY = (request.minY() + request.maxY()) / 2;
        var centerZ = (request.minZ() + request.maxZ()) / 2;
        var extent = Math.max(
                Math.max(request.maxX() - request.minX(), request.maxY() - request.minY()),
                request.maxZ() - request.minZ()
        ) / 2;

        var region = new Spatial.Cube(
                centerX - extent, centerY - extent, centerZ - extent, extent * 2
        );

        var entityIds = index.entitiesInRegion(region);
        var results = new ArrayList<EntityInfo>();

        for (var entityId : entityIds) {
            var position = index.getEntityPosition((com.hellblazer.luciferase.lucien.entity.EntityID) entityId);
            var content = index.getEntity((com.hellblazer.luciferase.lucien.entity.EntityID) entityId);
            results.add(new EntityInfo(
                    entityId.toString(),
                    position.x,
                    position.y,
                    position.z,
                    content
            ));
        }

        log.debug("Range query in session {} returned {} entities", sessionId, results.size());
        return results;
    }

    /**
     * K-nearest neighbors query.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<EntityInfo> knnQuery(String sessionId, KnnQueryRequest request) {
        var holder = getHolder(sessionId);
        var index = holder.index();

        var queryPoint = new Point3f(request.x(), request.y(), request.z());
        var maxDistance = request.maxDistance() != null ? request.maxDistance() : Float.MAX_VALUE;

        var entityIds = index.kNearestNeighbors(queryPoint, request.k(), maxDistance);
        var results = new ArrayList<EntityInfo>();

        for (var entityId : entityIds) {
            var position = index.getEntityPosition((com.hellblazer.luciferase.lucien.entity.EntityID) entityId);
            var content = index.getEntity((com.hellblazer.luciferase.lucien.entity.EntityID) entityId);
            results.add(new EntityInfo(
                    entityId.toString(),
                    position.x,
                    position.y,
                    position.z,
                    content
            ));
        }

        log.debug("KNN query in session {} returned {} entities", sessionId, results.size());
        return results;
    }

    /**
     * Ray intersection query.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<RayHitInfo> rayQuery(String sessionId, RayQueryRequest request) {
        var holder = getHolder(sessionId);
        var index = holder.index();

        var origin = new Point3f(request.originX(), request.originY(), request.originZ());
        var direction = new Vector3f(request.directionX(), request.directionY(), request.directionZ());
        direction.normalize();
        var maxDistance = request.maxDistance() != null ? request.maxDistance() : Float.MAX_VALUE;

        var ray = new Ray3D(origin, direction, maxDistance);
        List rawIntersections = index.rayIntersectAll(ray);
        var results = new ArrayList<RayHitInfo>();

        for (var obj : rawIntersections) {
            var intersection = (SpatialIndex.RayIntersection) obj;
            var hit = intersection.intersectionPoint();
            var normal = intersection.normal();
            results.add(new RayHitInfo(
                    intersection.entityId().toString(),
                    intersection.distance(),
                    hit != null ? hit.x : 0, hit != null ? hit.y : 0, hit != null ? hit.z : 0,
                    normal != null ? normal.x : 0, normal != null ? normal.y : 0, normal != null ? normal.z : 0,
                    intersection.content()
            ));
        }

        log.debug("Ray query in session {} returned {} hits", sessionId, results.size());
        return results;
    }

    /**
     * Check if a session has a spatial index.
     */
    public boolean hasIndex(String sessionId) {
        return indices.containsKey(sessionId);
    }

    // ===== Private Helpers =====

    private SpatialIndexHolder getHolder(String sessionId) {
        var holder = indices.get(sessionId);
        if (holder == null) {
            throw new NoSuchElementException("No spatial index found for session: " + sessionId);
        }
        return holder;
    }

    @SuppressWarnings("unchecked")
    private SpatialIndexHolder createIndexHolder(CreateIndexRequest request) {
        var maxDepth = request.maxDepth() != null ? request.maxDepth() : DEFAULT_LEVEL;
        var maxEntitiesPerNode = request.maxEntitiesPerNode() != null ? request.maxEntitiesPerNode() : 10;

        return switch (request.indexType()) {
            case OCTREE -> new SpatialIndexHolder(
                    new Octree<UUIDEntityID, Object>(UUIDEntityID::new, maxEntitiesPerNode, maxDepth),
                    IndexType.OCTREE,
                    maxDepth,
                    maxEntitiesPerNode
            );
            case TETREE -> new SpatialIndexHolder(
                    new Tetree<UUIDEntityID, Object>(UUIDEntityID::new, maxEntitiesPerNode, maxDepth),
                    IndexType.TETREE,
                    maxDepth,
                    maxEntitiesPerNode
            );
            case SFC -> new SpatialIndexHolder(
                    new SFCArrayIndex<UUIDEntityID, Object>(UUIDEntityID::new),
                    IndexType.SFC,
                    maxDepth,
                    maxEntitiesPerNode
            );
        };
    }

    /**
     * Enum for spatial index types.
     */
    public enum IndexType {
        OCTREE, TETREE, SFC
    }

    /**
     * Internal holder for spatial index with metadata.
     */
    @SuppressWarnings("rawtypes")
    private record SpatialIndexHolder(
            SpatialIndex index,
            IndexType type,
            byte maxDepth,
            int maxEntitiesPerNode
    ) {}
}

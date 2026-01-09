package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.simulation.entity.*;

import com.hellblazer.luciferase.simulation.bubble.*;
import com.hellblazer.luciferase.simulation.ghost.GhostChannel;
import com.hellblazer.luciferase.simulation.ghost.InMemoryGhostChannel;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.entity.EntityData;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import javafx.geometry.Point3D;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Enhanced Bubble with tetrahedral bounds, spatial index, and VON integration.
 * <p>
 * Enhancements over basic Bubble:
 * - Internal Tetree spatial index for entity storage
 * - BubbleBounds for tetrahedral bounding volumes
 * - VON neighbor tracking for distributed bubble discovery
 * - Frame time monitoring with split detection
 * <p>
 * Thread-safe for concurrent entity operations.
 *
 * @author hal.hildebrand
 */
public class EnhancedBubble {

    private final UUID id;
    private final byte spatialLevel;
    private final long targetFrameMs;
    private final Tetree<StringEntityID, EntityData> spatialIndex;
    private final Set<UUID> vonNeighbors;
    private final AtomicLong lastFrameTimeNs;
    private final Map<String, StringEntityID> idMapping;  // Map user String IDs to EntityIDs
    private final RealTimeController realTimeController;
    private final GhostChannel<StringEntityID, EntityData> ghostChannel;
    private BubbleBounds bounds;

    /**
     * Create an enhanced bubble with spatial indexing and monitoring.
     *
     * @param id                   Unique bubble identifier
     * @param spatialLevel         Tetree refinement level for spatial index
     * @param targetFrameMs        Target frame time budget in milliseconds
     */
    public EnhancedBubble(UUID id, byte spatialLevel, long targetFrameMs) {
        this(id, spatialLevel, targetFrameMs, new RealTimeController(id, "bubble-" + id.toString().substring(0, 8)));
    }

    /**
     * Create an enhanced bubble with spatial indexing and monitoring.
     *
     * @param id                   Unique bubble identifier
     * @param spatialLevel         Tetree refinement level for spatial index
     * @param targetFrameMs        Target frame time budget in milliseconds
     * @param realTimeController   RealTimeController for simulation time management
     */
    public EnhancedBubble(UUID id, byte spatialLevel, long targetFrameMs, RealTimeController realTimeController) {
        this(id, spatialLevel, targetFrameMs, realTimeController, new InMemoryGhostChannel<>());
    }

    /**
     * Create an enhanced bubble with spatial indexing, monitoring, and custom ghost channel.
     * <p>
     * This constructor allows injection of different GhostChannel implementations:
     * - InMemoryGhostChannel: For testing and single-bubble scenarios (default)
     * - DelosSocketTransport: For distributed multi-bubble simulation (Phase 7B.2)
     * <p>
     * <strong>Phase 7B.2 Integration:</strong>
     * <pre>
     * // Use Delos-based network transport
     * var transport = new DelosSocketTransport(bubbleId);
     * var bubble = new EnhancedBubble(id, level, frameMs, controller, transport);
     * </pre>
     *
     * @param id                   Unique bubble identifier
     * @param spatialLevel         Tetree refinement level for spatial index
     * @param targetFrameMs        Target frame time budget in milliseconds
     * @param realTimeController   RealTimeController for simulation time management
     * @param ghostChannel         GhostChannel for cross-bubble ghost transmission
     */
    @SuppressWarnings("rawtypes") // EntityData used as raw type throughout EnhancedBubble
    public EnhancedBubble(UUID id, byte spatialLevel, long targetFrameMs, RealTimeController realTimeController,
                          GhostChannel<StringEntityID, EntityData> ghostChannel) {
        this.id = id;
        this.spatialLevel = spatialLevel;
        this.targetFrameMs = targetFrameMs;
        this.realTimeController = realTimeController;
        this.ghostChannel = Objects.requireNonNull(ghostChannel, "ghostChannel must not be null");
        this.spatialIndex = new Tetree<>(new StringEntityIDGenerator(), 10, spatialLevel);
        this.vonNeighbors = ConcurrentHashMap.newKeySet();
        this.lastFrameTimeNs = new AtomicLong(0);
        this.idMapping = new ConcurrentHashMap<>();

        // Initialize bounds to root tetrahedron at specified level
        var rootKey = com.hellblazer.luciferase.lucien.tetree.TetreeKey.create(spatialLevel, 0L, 0L);
        this.bounds = BubbleBounds.fromTetreeKey(rootKey);
    }

    /**
     * Get the bubble ID.
     *
     * @return Unique bubble identifier
     */
    public UUID id() {
        return id;
    }

    /**
     * Get the number of entities in this bubble.
     *
     * @return Entity count
     */
    public int entityCount() {
        return spatialIndex.entityCount();
    }

    /**
     * Get the current bounds of this bubble.
     *
     * @return BubbleBounds or null if no entities
     */
    public BubbleBounds bounds() {
        return bounds;
    }

    /**
     * Get the ghost channel for cross-bubble communication.
     *
     * @return GhostChannel instance
     */
    @SuppressWarnings("rawtypes") // EntityData used as raw type
    public GhostChannel<StringEntityID, EntityData> getGhostChannel() {
        return ghostChannel;
    }

    /**
     * Get the set of VON neighbors.
     *
     * @return Set of neighbor bubble UUIDs
     */
    public Set<UUID> getVonNeighbors() {
        return vonNeighbors;
    }

    /**
     * Get all entity IDs in this bubble.
     *
     * @return Set of entity IDs
     */
    public Set<String> getEntities() {
        return new HashSet<>(idMapping.keySet());
    }

    /**
     * Get all entities with their data (position, content, bucket).
     * This avoids coordinate issues with large radius queries.
     *
     * @return List of all entity records
     */
    public List<EntityRecord> getAllEntityRecords() {
        var results = new ArrayList<EntityRecord>();
        for (var entry : idMapping.entrySet()) {
            var originalId = entry.getKey();
            var internalId = entry.getValue();
            var data = spatialIndex.getEntity(internalId);
            if (data != null) {
                results.add(new EntityRecord(originalId, data.position, data.content, data.addedBucket));
            }
        }
        return results;
    }

    /**
     * Add an entity to this bubble.
     * Inserts into spatial index and updates bounds.
     * Uses RealTimeController's simulation time for entity timestamp.
     *
     * @param entityId Entity identifier
     * @param position Entity position
     * @param content  Entity content
     */
    public void addEntity(String entityId, Point3f position, Object content) {
        var internalId = new StringEntityID(entityId);
        idMapping.put(entityId, internalId);

        // Use simulation time instead of wall-clock time for determinism
        var simulationTime = realTimeController.getSimulationTime();
        var entityData = new EntityData(position, content, simulationTime);
        spatialIndex.insert(internalId, position, spatialLevel, entityData);

        // Update bounds
        if (bounds == null) {
            bounds = BubbleBounds.fromEntityPositions(List.of(position));
        } else {
            bounds = bounds.expand(position);
        }
    }

    /**
     * Remove an entity from this bubble.
     * Removes from spatial index and recalculates bounds.
     *
     * @param entityId Entity to remove
     */
    public void removeEntity(String entityId) {
        var internalId = idMapping.remove(entityId);
        if (internalId != null) {
            spatialIndex.removeEntity(internalId);

            // Recalculate bounds if entities remain
            if (spatialIndex.entityCount() > 0) {
                recalculateBounds();
            } else {
                bounds = null;
            }
        }
    }

    /**
     * Update an entity's position.
     * Updates spatial index and bounds.
     *
     * @param entityId    Entity to update
     * @param newPosition New position
     */
    public void updateEntityPosition(String entityId, Point3f newPosition) {
        var internalId = idMapping.get(entityId);
        if (internalId != null) {
            // Get existing entity data
            var oldData = spatialIndex.getEntity(internalId);
            if (oldData != null) {
                // Remove old entity
                spatialIndex.removeEntity(internalId);

                // Re-add with new position (EntityData is immutable record)
                var newData = new EntityData(newPosition, oldData.content(), oldData.addedBucket());
                spatialIndex.insert(internalId, newPosition, spatialLevel, newData);

                // Expand bounds if needed
                if (bounds != null) {
                    bounds = bounds.expand(newPosition);
                }
            }
        }
    }

    /**
     * Query entities within a radius of a center point.
     *
     * @param center Center point
     * @param radius Search radius
     * @return List of entities within radius
     */
    public List<EntityRecord> queryRange(Point3f center, float radius) {
        // Validate radius
        if (radius <= 0) {
            throw new IllegalArgumentException("Radius must be positive: " + radius);
        }

        // Create bounding cube for sphere (origin at sphere's min corner, extent = 2*radius)
        // Clamp to positive coordinates since Tetree requires positive coordinate space
        float minX = Math.max(0, center.x - radius);
        float minY = Math.max(0, center.y - radius);
        float minZ = Math.max(0, center.z - radius);

        // Compute extent that covers the query sphere (accounting for clamping)
        float maxX = center.x + radius;
        float maxY = center.y + radius;
        float maxZ = center.z + radius;
        float extent = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));

        // Early return if extent is non-positive (query entirely out of bounds)
        if (extent <= 0) {
            return List.of();
        }

        var cube = new Spatial.Cube(minX, minY, minZ, extent);

        var entityIds = spatialIndex.entitiesInRegion(cube);

        // Filter to actual sphere (entitiesInRegion returns cube, we need sphere)
        var results = new ArrayList<EntityRecord>();
        for (var entityId : entityIds) {
            var content = spatialIndex.getEntity(entityId);
            if (content != null) {
                var data = content;
                // Distance check for sphere
                float dx = data.position.x - center.x;
                float dy = data.position.y - center.y;
                float dz = data.position.z - center.z;
                float distSq = dx * dx + dy * dy + dz * dz;

                if (distSq <= radius * radius) {
                    // Find the original String ID
                    var originalId = findOriginalId(entityId);
                    if (originalId != null) {
                        results.add(new EntityRecord(originalId, data.position, data.content, data.addedBucket));
                    }
                }
            }
        }

        return results;
    }

    /**
     * Find k nearest neighbors to a query point.
     *
     * @param query Query point
     * @param k     Number of neighbors
     * @return List of k nearest entities
     */
    public List<EntityRecord> kNearestNeighbors(Point3f query, int k) {
        // Use unbounded search (maxDistance = Float.MAX_VALUE)
        var entityIds = spatialIndex.kNearestNeighbors(query, k, Float.MAX_VALUE);

        var results = new ArrayList<EntityRecord>();
        for (var entityId : entityIds) {
            var content = spatialIndex.getEntity(entityId);
            if (content != null) {
                var data = content;
                var originalId = findOriginalId(entityId);
                if (originalId != null) {
                    results.add(new EntityRecord(originalId, data.position, data.content, data.addedBucket));
                }
            }
        }

        return results;
    }

    /**
     * Recalculate bounds from current entity positions.
     */
    public void recalculateBounds() {
        if (spatialIndex.entityCount() == 0) {
            bounds = null;
            return;
        }

        var positions = new ArrayList<Point3f>();
        for (var entityId : idMapping.values()) {
            var data = spatialIndex.getEntity(entityId);
            if (data != null) {
                positions.add(data.position);
            }
        }

        if (!positions.isEmpty()) {
            bounds = BubbleBounds.fromEntityPositions(positions);
        }
    }

    /**
     * Get the centroid of this bubble based on entity positions.
     * Returns the average position of all entities, or tetrahedral centroid if no entities.
     *
     * @return Centroid point or null if no bounds
     */
    public Point3D centroid() {
        if (bounds == null) {
            return null;
        }

        // If there are entities, compute centroid from their positions
        if (spatialIndex.entityCount() > 0) {
            double sumX = 0, sumY = 0, sumZ = 0;
            int count = 0;

            for (var entityId : idMapping.values()) {
                var data = spatialIndex.getEntity(entityId);
                if (data != null) {
                    sumX += data.position.x;
                    sumY += data.position.y;
                    sumZ += data.position.z;
                    count++;
                }
            }

            if (count > 0) {
                return new Point3D(sumX / count, sumY / count, sumZ / count);
            }
        }

        // Fall back to tetrahedral centroid if no entities
        return bounds.centroid();
    }

    /**
     * Add a VON neighbor.
     *
     * @param neighborId Neighbor bubble UUID
     */
    public void addVonNeighbor(UUID neighborId) {
        vonNeighbors.add(neighborId);
    }

    /**
     * Remove a VON neighbor.
     *
     * @param neighborId Neighbor bubble UUID to remove
     */
    public void removeVonNeighbor(UUID neighborId) {
        vonNeighbors.remove(neighborId);
    }

    /**
     * Record the time taken for a simulation frame.
     *
     * @param frameTimeNs Frame time in nanoseconds
     */
    public void recordFrameTime(long frameTimeNs) {
        lastFrameTimeNs.set(frameTimeNs);
    }

    /**
     * Get the current frame utilization as a fraction of the target budget.
     *
     * @return Utilization (0.0 to 1.0+, >1.0 means over budget)
     */
    public float frameUtilization() {
        long frameTimeNs = lastFrameTimeNs.get();
        long targetFrameNs = targetFrameMs * 1_000_000L;
        return (float) frameTimeNs / targetFrameNs;
    }

    /**
     * Check if this bubble needs to split due to frame time overrun.
     * Split threshold: 120% of target frame time (1.2x budget).
     *
     * @return true if bubble should split
     */
    public boolean needsSplit() {
        return frameUtilization() > 1.2f;
    }

    /**
     * Process a single simulation tick for a time bucket.
     *
     * @param bucket Simulation time bucket
     */
    public void tick(long bucket) {
        // Placeholder for simulation tick processing
        // In a full implementation, this would:
        // 1. Process all entities scheduled for this bucket
        // 2. Update entity states
        // 3. Handle interactions
        // 4. Measure frame time
    }

    /**
     * Find original String ID from internal EntityID.
     */
    private String findOriginalId(StringEntityID internalId) {
        for (var entry : idMapping.entrySet()) {
            if (entry.getValue().equals(internalId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Internal entity data storage.
     */
    private record EntityData(Point3f position, Object content, long addedBucket) {
    }

    /**
     * Entity record for query results.
     */
    public record EntityRecord(String id, Point3f position, Object content, long addedBucket) {
    }
}

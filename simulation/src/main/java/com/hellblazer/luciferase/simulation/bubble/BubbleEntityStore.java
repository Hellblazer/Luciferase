package com.hellblazer.luciferase.simulation.bubble;

import com.hellblazer.luciferase.lucien.Spatial;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.simulation.entity.StringEntityID;
import com.hellblazer.luciferase.simulation.entity.StringEntityIDGenerator;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages entity lifecycle and spatial indexing via Tetree.
 * Notifies registered EntityChangeListeners on entity changes.
 * Thread-safe via ConcurrentHashMap and Tetree internal synchronization.
 *
 * @author hal.hildebrand
 */
public class BubbleEntityStore {

    private final Map<String, StringEntityID> idMapping;
    private final Tetree<StringEntityID, BubbleEntityData> spatialIndex;
    private final byte spatialLevel;
    private final RealTimeController realTimeController;
    private final List<EntityChangeListener> listeners;

    /**
     * Create an entity store with spatial indexing.
     *
     * @param spatialLevel       Tetree refinement level
     * @param realTimeController Real-time controller for simulation time
     */
    public BubbleEntityStore(byte spatialLevel, RealTimeController realTimeController) {
        this.spatialLevel = spatialLevel;
        this.realTimeController = realTimeController;
        this.idMapping = new ConcurrentHashMap<>();
        this.spatialIndex = new Tetree<>(new StringEntityIDGenerator(), 10, spatialLevel);
        this.listeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Add an entity change listener.
     *
     * @param listener Listener to register
     */
    public void addEntityChangeListener(EntityChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Add an entity to this store.
     * Inserts into spatial index and notifies listeners.
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
        var entityData = new BubbleEntityData(position, content, simulationTime);
        spatialIndex.insert(internalId, position, spatialLevel, entityData);

        // Notify listeners
        for (var listener : listeners) {
            listener.onEntityAdded(entityId, position);
        }
    }

    /**
     * Remove an entity from this store.
     * Removes from spatial index and notifies listeners.
     *
     * @param entityId Entity to remove
     */
    public void removeEntity(String entityId) {
        var internalId = idMapping.remove(entityId);
        if (internalId != null) {
            spatialIndex.removeEntity(internalId);

            // Notify listeners
            for (var listener : listeners) {
                listener.onEntityRemoved(entityId);
            }
        }
    }

    /**
     * Update an entity's position.
     * Updates spatial index and notifies listeners.
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
                var oldPosition = oldData.position;

                // Remove old entity
                spatialIndex.removeEntity(internalId);

                // Re-add with new position (BubbleEntityData is immutable record)
                var newData = new BubbleEntityData(newPosition, oldData.content, oldData.addedBucket);
                spatialIndex.insert(internalId, newPosition, spatialLevel, newData);

                // Notify listeners
                for (var listener : listeners) {
                    listener.onEntityMoved(entityId, oldPosition, newPosition);
                }
            }
        }
    }

    /**
     * Get the number of entities in this store.
     *
     * @return Entity count
     */
    public int entityCount() {
        return spatialIndex.entityCount();
    }

    /**
     * Get all entity IDs in this store.
     *
     * @return Set of entity IDs
     */
    public Set<String> getEntities() {
        return new HashSet<>(idMapping.keySet());
    }

    /**
     * Get all entities with their data (position, content, bucket).
     *
     * @return List of all entity records
     */
    public List<BubbleEntityRecord> getAllEntityRecords() {
        var results = new ArrayList<BubbleEntityRecord>();
        for (var entry : idMapping.entrySet()) {
            var originalId = entry.getKey();
            var internalId = entry.getValue();
            var data = spatialIndex.getEntity(internalId);
            if (data != null) {
                results.add(new BubbleEntityRecord(originalId, data.position, data.content, data.addedBucket));
            }
        }
        return results;
    }

    /**
     * Query entities within a radius of a center point.
     *
     * @param center Center point
     * @param radius Search radius
     * @return List of entities within radius
     */
    public List<BubbleEntityRecord> queryRange(Point3f center, float radius) {
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
        var results = new ArrayList<BubbleEntityRecord>();
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
                        results.add(new BubbleEntityRecord(originalId, data.position, data.content, data.addedBucket));
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
    public List<BubbleEntityRecord> kNearestNeighbors(Point3f query, int k) {
        // Use unbounded search (maxDistance = Float.MAX_VALUE)
        var entityIds = spatialIndex.kNearestNeighbors(query, k, Float.MAX_VALUE);

        var results = new ArrayList<BubbleEntityRecord>();
        for (var entityId : entityIds) {
            var content = spatialIndex.getEntity(entityId);
            if (content != null) {
                var data = content;
                var originalId = findOriginalId(entityId);
                if (originalId != null) {
                    results.add(new BubbleEntityRecord(originalId, data.position, data.content, data.addedBucket));
                }
            }
        }

        return results;
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
    private record BubbleEntityData(Point3f position, Object content, long addedBucket) {
    }

    /**
     * Entity record for query results.
     */
    public record BubbleEntityRecord(String id, Point3f position, Object content, long addedBucket) {
    }
}

package com.hellblazer.luciferase.simulation.span;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import javax.vecmath.Point3f;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a boundary zone between two adjacent regions.
 * <p>
 * Boundary zones track entities that are near the boundary between regions,
 * enabling efficient cross-region queries for spatial operations like collision
 * detection and neighbor finding.
 * <p>
 * A boundary zone is defined by:
 * - Two adjacent regions (region1, region2)
 * - A width (distance from boundary surface)
 * - Set of entities within the boundary zone
 * <p>
 * Thread-safe: Uses ConcurrentHashMap.newKeySet() for entity storage.
 *
 * @param <ID> Entity ID type
 * @author hal.hildebrand
 */
public record BoundaryZone<ID extends EntityID>(
    TetreeKey<?> region1,        // First region key
    TetreeKey<?> region2,        // Second region key
    float width,                 // Boundary zone width
    Set<ID> entities,            // Entities in boundary zone
    long lastUpdateTime          // Last modification timestamp
) {

    /**
     * Create a new boundary zone between two regions.
     *
     * @param region1 First region key
     * @param region2 Second region key
     * @param width   Boundary zone width
     * @param <ID>    Entity ID type
     * @return New boundary zone with empty entity set
     */
    public static <ID extends EntityID> BoundaryZone<ID> create(
        TetreeKey<?> region1,
        TetreeKey<?> region2,
        float width
    ) {
        return new BoundaryZone<>(
            region1,
            region2,
            width,
            ConcurrentHashMap.newKeySet(),
            System.currentTimeMillis()
        );
    }

    /**
     * Check if this boundary zone contains a specific region.
     *
     * @param regionKey Region key to check
     * @return true if this zone borders the specified region
     */
    public boolean contains(TetreeKey<?> regionKey) {
        return region1.equals(regionKey) || region2.equals(regionKey);
    }

    /**
     * Get the other region in this boundary zone.
     *
     * @param regionKey One region key
     * @return The other region key, or null if regionKey not in this zone
     */
    public TetreeKey<?> getOtherRegion(TetreeKey<?> regionKey) {
        if (region1.equals(regionKey)) {
            return region2;
        } else if (region2.equals(regionKey)) {
            return region1;
        } else {
            return null;
        }
    }

    /**
     * Check if an entity is in this boundary zone.
     *
     * @param entityId Entity ID
     * @return true if entity is tracked in this zone
     */
    public boolean containsEntity(ID entityId) {
        return entities.contains(entityId);
    }

    /**
     * Get entity count in boundary zone.
     *
     * @return Number of entities
     */
    public int entityCount() {
        return entities.size();
    }

    /**
     * Check if entity is near boundary (within width distance).
     * <p>
     * This is a simplified check - proper implementation would use tetrahedral
     * distance calculations based on the actual region boundaries.
     *
     * @param position Entity position
     * @return true if within boundary zone
     */
    public boolean isNearBoundary(Point3f position) {
        // Phase 3: Placeholder - actual implementation requires
        // tetrahedral distance calculation to boundary surface
        // For now, return false (boundary detection implemented in SpatialSpanImpl)
        return false;
    }

    /**
     * Create updated boundary zone with entity added.
     *
     * @param entityId Entity to add
     * @return New boundary zone with entity added
     */
    public BoundaryZone<ID> withEntityAdded(ID entityId) {
        var newEntities = ConcurrentHashMap.<ID>newKeySet();
        newEntities.addAll(entities);
        newEntities.add(entityId);

        return new BoundaryZone<>(
            region1,
            region2,
            width,
            newEntities,
            System.currentTimeMillis()
        );
    }

    /**
     * Create updated boundary zone with entity removed.
     *
     * @param entityId Entity to remove
     * @return New boundary zone with entity removed
     */
    public BoundaryZone<ID> withEntityRemoved(ID entityId) {
        var newEntities = ConcurrentHashMap.<ID>newKeySet();
        newEntities.addAll(entities);
        newEntities.remove(entityId);

        return new BoundaryZone<>(
            region1,
            region2,
            width,
            newEntities,
            System.currentTimeMillis()
        );
    }

    @Override
    public String toString() {
        return String.format("BoundaryZone{%s<->%s, width=%.2f, entities=%d}",
                             region1, region2, width, entities.size());
    }
}

package com.hellblazer.luciferase.simulation.span;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import javax.vecmath.Point3f;
import java.util.Collection;
import java.util.Set;

/**
 * Manages boundary zones between regions for cross-region entity queries.
 * <p>
 * SpatialSpan enables efficient spatial queries that span multiple regions by tracking
 * entities that are near region boundaries. This is essential for:
 * - Collision detection between entities in adjacent regions
 * - Neighbor finding across region boundaries
 * - Load balancing ghost layer management
 * <p>
 * Boundary zones are automatically maintained as entities move and regions split/join.
 * <p>
 * Thread-safe: All operations are safe for concurrent use.
 *
 * @param <ID> Entity ID type
 * @author hal.hildebrand
 */
public interface SpatialSpan<ID extends EntityID> {

    /**
     * Get entities in the boundary zone between two specific regions.
     * <p>
     * Returns entities that are near the boundary between region1 and region2.
     * These entities are visible to both regions for cross-region queries.
     *
     * @param region1 First region key
     * @param region2 Second region key
     * @return Set of entity IDs in the boundary zone (empty if no boundary exists)
     */
    Set<ID> getBoundaryEntities(TetreeKey<?> region1, TetreeKey<?> region2);

    /**
     * Get all entities in all boundary zones touching a region.
     * <p>
     * Returns the union of all entities in boundary zones that include the specified region.
     * Useful for queries that need to consider all nearby entities from adjacent regions.
     *
     * @param regionKey Region key
     * @return Set of all entity IDs in boundary zones touching this region
     */
    Set<ID> getAllBoundaryEntities(TetreeKey<?> regionKey);

    /**
     * Check if an entity is currently in any boundary zone.
     * <p>
     * Boundary status is automatically maintained as entities move.
     *
     * @param entityId Entity ID
     * @return true if entity is in at least one boundary zone
     */
    boolean isInBoundary(ID entityId);

    /**
     * Get all boundary zones for a region.
     * <p>
     * Returns all boundary zones where this region is one of the two adjacent regions.
     *
     * @param regionKey Region key
     * @return Collection of boundary zones (empty if region has no boundaries)
     */
    Collection<BoundaryZone<ID>> getBoundaryZones(TetreeKey<?> regionKey);

    /**
     * Update boundary tracking after entity movement.
     * <p>
     * Checks if entity entered or left any boundary zones based on new position.
     * Called automatically by SpatialTumbler during entity updates.
     *
     * @param entityId Entity ID
     * @param position New entity position
     */
    void updateBoundary(ID entityId, Point3f position);

    /**
     * Remove entity from all boundary zones.
     * <p>
     * Called when entity is removed from tracking or moved far from all boundaries.
     *
     * @param entityId Entity ID
     */
    void removeBoundary(ID entityId);

    /**
     * Recalculate all boundary zones after region topology changes.
     * <p>
     * Called after region split or join operations to update boundary zone geometry
     * and re-classify entities.
     */
    void recalculateBoundaries();

    /**
     * Get number of active boundary zones.
     *
     * @return Count of boundary zones
     */
    int getBoundaryZoneCount();

    /**
     * Get total number of entities tracked in boundary zones.
     * <p>
     * Note: Same entity may be in multiple boundary zones.
     *
     * @return Total entity count across all boundary zones
     */
    int getTotalBoundaryEntities();

    /**
     * Get configuration.
     *
     * @return Span configuration
     */
    SpanConfig getConfig();
}

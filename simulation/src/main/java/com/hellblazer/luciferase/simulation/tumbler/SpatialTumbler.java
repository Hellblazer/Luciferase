package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.span.SpatialSpan;

import javax.vecmath.Point3f;
import java.util.Collection;

/**
 * Manages dynamic tetrahedral volume regions with adaptive split/join.
 * <p>
 * SpatialTumbler provides a region-based abstraction over Tetree spatial indexing,
 * automatically splitting high-density regions and joining low-density regions
 * to maintain optimal query performance and memory usage.
 * <p>
 * Key Features:
 * - **Dynamic Regions**: Regions split when entity count exceeds threshold (default: 5000)
 * - **Automatic Join**: Regions join when combined count falls below threshold (default: 500)
 * - **Tetrahedral Subdivision**: Uses Bey tetrahedral refinement (8-way S0-S5 subdivision)
 * - **Boundary Zones**: SpatialSpan manages cross-region queries at boundaries
 * - **Thread-Safe**: Concurrent region operations with state machine protection
 * <p>
 * Region State Machine:
 * - ACTIVE: Normal operation, accepting entity updates
 * - SPLITTING: Subdividing into child regions (blocks updates)
 * - JOINING: Consolidating sibling regions (blocks updates)
 * - FROZEN: Temporarily immutable (during complex operations)
 * <p>
 * Hysteresis Gap:
 * Split and join thresholds maintain a gap (e.g., 5000 split, 500 join) to prevent
 * oscillation near threshold boundaries.
 *
 * @param <ID>      Entity identifier type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public interface SpatialTumbler<ID extends EntityID, Content> {

    /**
     * Track an entity at the given position.
     * <p>
     * Inserts the entity into the spatial index and returns the region key
     * where the entity was placed. The region may split if this insertion
     * causes the entity count to exceed the split threshold.
     *
     * @param entityId Entity identifier
     * @param position Entity position in world coordinates
     * @param content  Entity content (may be null)
     * @return Region key where entity was placed
     */
    TetreeKey<?> track(ID entityId, Point3f position, Content content);

    /**
     * Update entity position, potentially moving between regions.
     * <p>
     * If the new position falls in a different region, the entity is removed
     * from the old region and added to the new region. Region join may be
     * triggered if the old region falls below the join threshold.
     *
     * @param entityId    Entity identifier
     * @param newPosition New position in world coordinates
     * @return Region key where entity is now located
     */
    TetreeKey<?> update(ID entityId, Point3f newPosition);

    /**
     * Remove entity from tracking.
     * <p>
     * Removes the entity from its region. Region join may be triggered if
     * the region falls below the join threshold.
     *
     * @param entityId Entity to remove
     * @return true if entity was removed, false if not found
     */
    boolean remove(ID entityId);

    /**
     * Get the region containing a position.
     * <p>
     * Returns the leaf region (finest granularity) that contains the given
     * position. Returns null if position is outside the spatial index bounds.
     *
     * @param position Position to query
     * @return Region containing position, or null if out of bounds
     */
    TumblerRegion<ID> getRegion(Point3f position);

    /**
     * Get the region for a specific key.
     * <p>
     * Returns the region associated with the given TetreeKey. The key may
     * refer to a parent region (containing multiple child regions) or a
     * leaf region (no children).
     *
     * @param regionKey Region key to query
     * @return Region for the key, or null if not found
     */
    TumblerRegion<ID> getRegion(TetreeKey<?> regionKey);

    /**
     * Get all regions.
     * <p>
     * Returns all active regions, including both parent and leaf regions.
     * The collection is a snapshot and may not reflect concurrent modifications.
     *
     * @return Collection of all regions (unmodifiable)
     */
    Collection<TumblerRegion<ID>> getAllRegions();

    /**
     * Force split check on all regions.
     * <p>
     * Examines all regions and splits those exceeding the split threshold
     * and not at maximum depth. This is automatically called when
     * {@code autoAdapt} is enabled in TumblerConfig.
     *
     * @return Number of splits performed
     */
    int checkAndSplit();

    /**
     * Force join check on all regions.
     * <p>
     * Examines all regions and joins sibling groups where the combined
     * entity count is below the join threshold. This is automatically
     * called when {@code autoAdapt} is enabled in TumblerConfig.
     *
     * @return Number of joins performed
     */
    int checkAndJoin();

    /**
     * Get the boundary span manager.
     * <p>
     * Returns the SpatialSpan instance managing cross-region boundary zones.
     * Span zones handle queries that intersect multiple regions.
     *
     * @return Boundary span manager
     */
    SpatialSpan<ID> getSpan();

    /**
     * Get current configuration.
     * <p>
     * Returns the immutable configuration controlling split/join behavior,
     * region depth limits, and boundary span parameters.
     *
     * @return Tumbler configuration
     */
    TumblerConfig getConfig();

    /**
     * Get statistics.
     * <p>
     * Returns snapshot of current tumbler statistics including region counts,
     * entity distribution, split/join history, and performance metrics.
     *
     * @return Tumbler statistics
     */
    TumblerStatistics getStatistics();
}

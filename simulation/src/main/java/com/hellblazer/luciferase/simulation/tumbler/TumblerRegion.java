package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a dynamic region within SpatialTumbler.
 * <p>
 * Regions are defined by TetreeKey hierarchies and track entities within
 * their tetrahedral volume. Regions can split (when entity count exceeds
 * threshold) or join (when combined child count falls below threshold).
 * <p>
 * Region State Machine:
 * - ACTIVE: Normal operation, accepting entity updates
 * - SPLITTING: In process of subdividing into child regions
 * - JOINING: In process of consolidating child regions
 * - FROZEN: Temporarily immutable (during complex operations)
 * <p>
 * Thread-safe: All mutable state protected by region-level locks.
 *
 * @param <ID> Entity ID type
 * @author hal.hildebrand
 */
public record TumblerRegion<ID extends EntityID>(
    TetreeKey<?> key,              // Region identifier (ancestor key)
    int entityCount,               // Current entity count
    float density,                 // Entities per unit volume
    Set<ID> entities,              // Entity IDs in this region (concurrent)
    TetreeKey<?> parentKey,        // Parent region (null if root)
    List<TetreeKey<?>> childKeys,  // Child regions (empty if leaf)
    long lastUpdateTime,           // Last modification timestamp (millis)
    RegionState state              // Current operational state
) {

    /**
     * Region operational state.
     */
    public enum RegionState {
        /**
         * Normal operation - accepting entity updates.
         */
        ACTIVE,

        /**
         * In process of splitting into child regions.
         * Entity updates blocked until split complete.
         */
        SPLITTING,

        /**
         * In process of joining with siblings.
         * Entity updates blocked until join complete.
         */
        JOINING,

        /**
         * Temporarily frozen (immutable).
         * Used during complex multi-region operations.
         */
        FROZEN
    }

    /**
     * Create a new region with initial state.
     *
     * @param key Region identifier (TetreeKey)
     * @param <ID> Entity ID type
     * @return New region in ACTIVE state with empty entity set
     */
    public static <ID extends EntityID> TumblerRegion<ID> create(TetreeKey<?> key) {
        return new TumblerRegion<>(
            key,
            0,              // entityCount
            0.0f,           // density
            ConcurrentHashMap.newKeySet(),  // entities
            null,           // parentKey
            List.of(),      // childKeys (empty)
            System.currentTimeMillis(),
            RegionState.ACTIVE
        );
    }

    /**
     * Create a new region with specified parent.
     *
     * @param key Region identifier
     * @param parentKey Parent region key
     * @param <ID> Entity ID type
     * @return New region with parent link
     */
    public static <ID extends EntityID> TumblerRegion<ID> createChild(
        TetreeKey<?> key,
        TetreeKey<?> parentKey
    ) {
        return new TumblerRegion<>(
            key,
            0,
            0.0f,
            ConcurrentHashMap.newKeySet(),
            parentKey,
            List.of(),
            System.currentTimeMillis(),
            RegionState.ACTIVE
        );
    }

    /**
     * Check if this region is a leaf (no children).
     *
     * @return true if region has no child regions
     */
    public boolean isLeaf() {
        return childKeys == null || childKeys.isEmpty();
    }

    /**
     * Check if this region needs splitting based on config.
     *
     * @param config Tumbler configuration
     * @return true if entity count exceeds split threshold and not at max depth
     */
    public boolean needsSplit(TumblerConfig config) {
        return entityCount > config.splitThreshold()
            && key.getLevel() < config.maxRegionLevel()
            && state == RegionState.ACTIVE;
    }

    /**
     * Check if this region can join with siblings based on config.
     *
     * @param config Tumbler configuration
     * @return true if entity count below join threshold and not at min depth
     */
    public boolean canJoin(TumblerConfig config) {
        return entityCount < config.joinThreshold()
            && key.getLevel() > config.minRegionLevel()
            && state == RegionState.ACTIVE;
    }

    /**
     * Check if region is in active state (accepting updates).
     *
     * @return true if state is ACTIVE
     */
    public boolean isActive() {
        return state == RegionState.ACTIVE;
    }

    /**
     * Check if region is root (no parent).
     *
     * @return true if parentKey is null
     */
    public boolean isRoot() {
        return parentKey == null;
    }

    /**
     * Create updated region with new entity added.
     *
     * @param entityId Entity to add
     * @return New region with updated state
     */
    public TumblerRegion<ID> withEntityAdded(ID entityId) {
        var newEntities = ConcurrentHashMap.<ID>newKeySet();
        newEntities.addAll(entities);
        newEntities.add(entityId);

        return new TumblerRegion<>(
            key,
            entityCount + 1,
            density,  // Recalculated externally
            newEntities,
            parentKey,
            childKeys,
            System.currentTimeMillis(),
            state
        );
    }

    /**
     * Create updated region with entity removed.
     *
     * @param entityId Entity to remove
     * @return New region with updated state
     */
    public TumblerRegion<ID> withEntityRemoved(ID entityId) {
        var newEntities = ConcurrentHashMap.<ID>newKeySet();
        newEntities.addAll(entities);
        boolean wasRemoved = newEntities.remove(entityId);

        // Only decrement count if entity was actually present
        int newCount = wasRemoved ? Math.max(0, entityCount - 1) : entityCount;

        return new TumblerRegion<>(
            key,
            newCount,
            density,
            newEntities,
            parentKey,
            childKeys,
            System.currentTimeMillis(),
            state
        );
    }

    /**
     * Create updated region with new density.
     *
     * @param newDensity Recalculated density
     * @return New region with updated density
     */
    public TumblerRegion<ID> withDensity(float newDensity) {
        return new TumblerRegion<>(
            key, entityCount, newDensity, entities, parentKey,
            childKeys, lastUpdateTime, state
        );
    }

    /**
     * Create updated region with new state.
     *
     * @param newState New operational state
     * @return New region with updated state
     */
    public TumblerRegion<ID> withState(RegionState newState) {
        return new TumblerRegion<>(
            key, entityCount, density, entities, parentKey,
            childKeys, System.currentTimeMillis(), newState
        );
    }

    /**
     * Create updated region with child keys.
     *
     * @param newChildKeys List of child region keys
     * @return New region with updated children
     */
    public TumblerRegion<ID> withChildren(List<TetreeKey<?>> newChildKeys) {
        return new TumblerRegion<>(
            key, entityCount, density, entities, parentKey,
            List.copyOf(newChildKeys), System.currentTimeMillis(), state
        );
    }

    @Override
    public String toString() {
        return String.format(
            "TumblerRegion{key=%s, count=%d, density=%.2f, state=%s, children=%d}",
            key, entityCount, density, state, childKeys.size()
        );
    }
}

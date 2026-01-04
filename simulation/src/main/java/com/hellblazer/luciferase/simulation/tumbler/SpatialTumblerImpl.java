package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.span.SpatialSpan;

import javax.vecmath.Point3f;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of SpatialTumbler with Tetree delegation.
 * <p>
 * Provides region-based adaptive volume management over a single Tetree spatial index.
 * Regions split when entity count exceeds threshold and join when count falls below threshold.
 * <p>
 * **Phase 1 Status**: Foundation complete
 * - ✅ Task 1.4: Tetree delegation and skeleton
 * - ✅ Task 1.5: Region registration and tracking
 * - ✅ Task 1.6: Entity count and density calculation
 * <p>
 * **Implementation Details**:
 * - Regions created lazily as entities are tracked
 * - Regions start at minRegionLevel (default: 4)
 * - Entities inserted at maxRegionLevel (default: 12)
 * - Density calculated using approximate volume (1/8^level)
 * <p>
 * Thread-Safety:
 * - All mutable state protected by concurrent data structures
 * - Region state machine prevents concurrent split/join operations
 * - Entity operations delegate to thread-safe Tetree
 * - Atomic region updates via ConcurrentHashMap.compute()
 *
 * @param <ID>      Entity identifier type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public class SpatialTumblerImpl<ID extends EntityID, Content> implements SpatialTumbler<ID, Content> {

    // Core delegated spatial index
    private final Tetree<ID, Content> tetree;

    // Configuration
    private final TumblerConfig config;

    // Region tracking: TetreeKey -> TumblerRegion
    // (Phase 1.5: Region registration and tracking)
    private final Map<TetreeKey<?>, TumblerRegion<ID>> regions;

    // Entity to region mapping: EntityID -> TetreeKey (region containing entity)
    // (Phase 1.5: Region registration and tracking)
    private final Map<ID, TetreeKey<?>> entityRegions;

    // Operation counter for auto-adapt
    private final AtomicInteger operationCounter;

    // Span manager (Phase 3)
    private final SpatialSpan<ID> span;

    /**
     * Create a SpatialTumbler with the given Tetree and configuration.
     *
     * @param tetree Tetree spatial index to delegate to
     * @param config Tumbler configuration
     */
    public SpatialTumblerImpl(Tetree<ID, Content> tetree, TumblerConfig config) {
        this.tetree = tetree;
        this.config = config;
        this.regions = new ConcurrentHashMap<>();
        this.entityRegions = new ConcurrentHashMap<>();
        this.operationCounter = new AtomicInteger(0);
        this.span = null;  // Phase 3

        // Phase 1.5: Regions are created lazily as entities are tracked
        // No need to pre-create root regions
    }

    @Override
    public TetreeKey<?> track(ID entityId, Point3f position, Content content) {
        // Phase 1: Insert into Tetree at region level (minRegionLevel)
        // Later phases will use finer levels for entities and coarser for regions
        tetree.insert(entityId, position, config.minRegionLevel(), content);

        // Phase 1.5: Get region key from entity position
        var regionKey = getOrCreateRegionForPosition(position);
        entityRegions.put(entityId, regionKey);
        incrementRegionCount(regionKey, entityId);

        // Auto-adapt check
        checkAutoAdapt();

        return regionKey;
    }

    @Override
    public TetreeKey<?> update(ID entityId, Point3f newPosition) {
        // Update in Tetree
        tetree.updateEntity(entityId, newPosition, config.minRegionLevel());

        // Phase 1.5: Handle region changes
        var newRegionKey = getOrCreateRegionForPosition(newPosition);
        var oldRegionKey = entityRegions.get(entityId);

        if (oldRegionKey == null || !newRegionKey.equals(oldRegionKey)) {
            // Entity moved to a different region
            entityRegions.put(entityId, newRegionKey);

            if (oldRegionKey != null) {
                decrementRegionCount(oldRegionKey, entityId);
            }

            incrementRegionCount(newRegionKey, entityId);
        }

        // Auto-adapt check
        checkAutoAdapt();

        return newRegionKey;
    }

    @Override
    public boolean remove(ID entityId) {
        var removed = tetree.removeEntity(entityId);

        if (removed) {
            // Phase 1.5: Remove from region tracking
            var regionKey = entityRegions.remove(entityId);
            if (regionKey != null) {
                decrementRegionCount(regionKey, entityId);
            }

            // Auto-adapt check
            checkAutoAdapt();
        }

        return removed;
    }

    @Override
    public TumblerRegion<ID> getRegion(Point3f position) {
        var regionKey = getOrCreateRegionForPosition(position);
        return regions.get(regionKey);
    }

    @Override
    public TumblerRegion<ID> getRegion(TetreeKey<?> regionKey) {
        // Phase 1.5: Implement region lookup by key
        return regions.get(regionKey);
    }

    @Override
    public Collection<TumblerRegion<ID>> getAllRegions() {
        return Collections.unmodifiableCollection(regions.values());
    }

    @Override
    public int checkAndSplit() {
        // Phase 2: Implement split logic
        int splitCount = 0;

        // Find regions that need splitting (avoid ConcurrentModificationException)
        var regionsToSplit = regions.values().stream()
            .filter(region -> region.needsSplit(config))
            .map(TumblerRegion::key)
            .toList();

        // Split each region
        for (var regionKey : regionsToSplit) {
            if (splitRegion(regionKey)) {
                splitCount++;
            }
        }

        return splitCount;
    }

    @Override
    public int checkAndJoin() {
        // Phase 2: Implement join logic
        int joinCount = 0;

        // Find parent regions whose children all need joining
        var parentsToJoin = regions.values().stream()
            .filter(region -> !region.isLeaf())  // Has children
            .filter(region -> region.isActive())  // Is active
            .filter(this::allChildrenNeedJoin)    // All children below threshold
            .map(TumblerRegion::key)
            .toList();

        // Join each parent's children
        for (var parentKey : parentsToJoin) {
            if (joinRegion(parentKey)) {
                joinCount++;
            }
        }

        return joinCount;
    }

    @Override
    public SpatialSpan<ID> getSpan() {
        // Phase 3: Return boundary span manager
        return span;
    }

    @Override
    public TumblerConfig getConfig() {
        return config;
    }

    @Override
    public TumblerStatistics getStatistics() {
        // Phase 5: Implement statistics collection
        // - Region count by level
        // - Entity distribution
        // - Split/join history
        // - Performance metrics
        return null;
    }

    /**
     * Check if auto-adapt should run and trigger split/join if needed.
     */
    private void checkAutoAdapt() {
        if (!config.autoAdapt()) {
            return;
        }

        int count = operationCounter.incrementAndGet();
        if (count % config.adaptCheckInterval() == 0) {
            checkAndSplit();
            checkAndJoin();
        }
    }

    // ===== Phase 1.5/1.6: Region Management Methods =====

    /**
     * Get or create region for a position.
     * <p>
     * Finds the tetrahedral cell at minRegionLevel containing the position,
     * and creates a region if it doesn't exist yet.
     * <p>
     * Phase 1: Simple approach using enclosing() API.
     * Later phases may refine this to support hierarchical region management.
     *
     * @param position Position to query
     * @return Region key containing the position
     */
    private TetreeKey<?> getOrCreateRegionForPosition(Point3f position) {
        // Convert position to integer coordinates at minRegionLevel
        // Tetree coordinate space is [0, 1], scale to integer grid
        int gridSize = 1 << config.minRegionLevel();  // 2^level
        int x = Math.max(0, Math.min(gridSize - 1, (int) (position.x * gridSize)));
        int y = Math.max(0, Math.min(gridSize - 1, (int) (position.y * gridSize)));
        int z = Math.max(0, Math.min(gridSize - 1, (int) (position.z * gridSize)));

        // Use Tetree's enclosing() method to find the cell
        var node = tetree.enclosing(new javax.vecmath.Point3i(x, y, z), config.minRegionLevel());

        TetreeKey<?> regionKey;
        if (node != null) {
            regionKey = node.sfcIndex();
        } else {
            // If enclosing returns null, create a key for origin at minRegionLevel
            // This handles edge cases at boundaries
            regionKey = new com.hellblazer.luciferase.lucien.tetree.CompactTetreeKey(
                config.minRegionLevel(), 0L);
        }

        // Create region if it doesn't exist
        regions.computeIfAbsent(regionKey, key -> TumblerRegion.<ID>create(key));

        return regionKey;
    }

    /**
     * Increment region entity count.
     * <p>
     * Adds entity to region's entity set, updates count, and recalculates density.
     * Thread-safe: uses ConcurrentHashMap.compute() for atomic update.
     *
     * @param regionKey Region to update
     * @param entityId  Entity to add
     */
    private void incrementRegionCount(TetreeKey<?> regionKey, ID entityId) {
        regions.compute(regionKey, (key, region) -> {
            if (region == null) {
                throw new IllegalStateException("Region not found: " + regionKey);
            }

            // Update entity set and count
            var updatedRegion = region.withEntityAdded(entityId);

            // Phase 1.6: Recalculate density
            var density = calculateDensity(updatedRegion);
            return updatedRegion.withDensity(density);
        });
    }

    /**
     * Decrement region entity count.
     * <p>
     * Removes entity from region's entity set, updates count, and recalculates density.
     * Thread-safe: uses ConcurrentHashMap.compute() for atomic update.
     *
     * @param regionKey Region to update
     * @param entityId  Entity to remove
     */
    private void decrementRegionCount(TetreeKey<?> regionKey, ID entityId) {
        regions.compute(regionKey, (key, region) -> {
            if (region == null) {
                // Region already removed (possibly during join)
                return null;
            }

            // Update entity set and count
            var updatedRegion = region.withEntityRemoved(entityId);

            // Phase 1.6: Recalculate density
            var density = calculateDensity(updatedRegion);
            updatedRegion = updatedRegion.withDensity(density);

            // Remove region if empty AND is a leaf (cleanup)
            // Non-leaf regions may temporarily have 0 entities during split/join
            if (updatedRegion.entityCount() == 0 && updatedRegion.isLeaf()) {
                return null;  // Remove from map
            }

            return updatedRegion;
        });
    }

    /**
     * Calculate density for a region.
     * <p>
     * Density = entities per unit volume.
     * Volume is calculated from the tetrahedral region at its level.
     * <p>
     * Phase 1.6: Basic implementation using approximate volume.
     * Can be enhanced with exact tetrahedral volume calculation.
     *
     * @param region Region to calculate density for
     * @return Density (entities per cubic unit)
     */
    private float calculateDensity(TumblerRegion<ID> region) {
        if (region.entityCount() == 0) {
            return 0.0f;
        }

        // Approximate volume based on level
        // Each level subdivides volume by ~8 (octahedral subdivision)
        // Volume at level L ≈ (1/8)^L of root volume
        // For simplicity, assume root volume = 1.0
        byte level = region.key().getLevel();
        float volume = (float) Math.pow(0.125, level);  // 1/8^level

        return region.entityCount() / volume;
    }

    // ===== Phase 2: Split/Join Logic =====

    /**
     * Split a region into 8 child regions using Bey tetrahedral subdivision.
     * <p>
     * Algorithm:
     * 1. Change parent region to SPLITTING state
     * 2. Create 8 child TetreeKeys (Bey subdivision: 1 tet → 8 tets)
     * 3. Create child TumblerRegions
     * 4. Redistribute entities from parent to children
     * 5. Update parent's childKeys list
     * 6. Change parent state to ACTIVE
     *
     * @param parentKey Parent region key to split
     * @return true if split succeeded, false if split was skipped
     */
    private boolean splitRegion(TetreeKey<?> parentKey) {
        var parentRegion = regions.get(parentKey);
        if (parentRegion == null || !parentRegion.isActive()) {
            return false;  // Region doesn't exist or not active
        }

        // Change to SPLITTING state (prevents concurrent splits)
        var splittingRegion = parentRegion.withState(TumblerRegion.RegionState.SPLITTING);
        if (!regions.replace(parentKey, parentRegion, splittingRegion)) {
            return false;  // Another thread changed the region
        }

        try {
            // Create parent Tet for subdivision
            var parentTet = com.hellblazer.luciferase.lucien.tetree.Tet.tetrahedron(parentKey);

            // Create 8 child regions using Bey subdivision
            var childKeys = new java.util.ArrayList<TetreeKey<?>>(8);
            for (int childIndex = 0; childIndex < 8; childIndex++) {
                var childTet = parentTet.child(childIndex);
                var childKey = childTet.tmIndex();
                childKeys.add(childKey);

                // Create child region with parent link
                var childRegion = TumblerRegion.<ID>createChild(childKey, parentKey);
                regions.put(childKey, childRegion);
            }

            // Redistribute entities from parent to children
            redistributeEntities(splittingRegion, childKeys);

            // Update parent: clear entities, add children, return to ACTIVE
            // After split, parent has no entities (all moved to children)
            var emptyParent = TumblerRegion.<ID>create(parentKey)
                .withChildren(childKeys)
                .withState(TumblerRegion.RegionState.ACTIVE);
            regions.put(parentKey, emptyParent);

            return true;
        } catch (Exception e) {
            // If split fails, restore parent to ACTIVE state
            var restoredParent = splittingRegion.withState(TumblerRegion.RegionState.ACTIVE);
            regions.put(parentKey, restoredParent);
            throw new RuntimeException("Split failed for region " + parentKey, e);
        }
    }

    /**
     * Redistribute entities from parent region to child regions.
     * <p>
     * For each entity in the parent:
     * - Get entity position from Tetree
     * - Find which child tetrahedron contains it
     * - Move entity to that child region
     *
     * @param parentRegion Parent region with entities
     * @param childKeys    Child region keys
     */
    private void redistributeEntities(TumblerRegion<ID> parentRegion, java.util.List<TetreeKey<?>> childKeys) {
        for (var entityId : parentRegion.entities()) {
            // Get entity position from Tetree
            var position = tetree.getEntityPosition(entityId);
            if (position == null) {
                continue;  // Entity was removed
            }

            // Find which child contains this entity
            TetreeKey<?> containingChild = null;
            for (var childKey : childKeys) {
                var childTet = com.hellblazer.luciferase.lucien.tetree.Tet.tetrahedron(childKey);
                if (childTet.contains(position)) {
                    containingChild = childKey;
                    break;
                }
            }

            if (containingChild != null) {
                // Update entity-region mapping
                entityRegions.put(entityId, containingChild);

                // Add entity to child region
                incrementRegionCount(containingChild, entityId);
            }
        }
    }

    /**
     * Check if all children of a parent region are below join threshold.
     *
     * @param parentRegion Parent region to check
     * @return true if all children can be joined
     */
    private boolean allChildrenNeedJoin(TumblerRegion<ID> parentRegion) {
        if (parentRegion.childKeys() == null || parentRegion.childKeys().isEmpty()) {
            return false;
        }

        // Check if all children exist and are below threshold
        for (var childKey : parentRegion.childKeys()) {
            var childRegion = regions.get(childKey);
            if (childRegion == null || !childRegion.canJoin(config)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Join child regions back to parent.
     * <p>
     * Algorithm:
     * 1. Change parent and children to JOINING state
     * 2. Consolidate all entities from children to parent
     * 3. Remove child regions
     * 4. Update parent's childKeys to empty
     * 5. Change parent state to ACTIVE
     *
     * @param parentKey Parent region key
     * @return true if join succeeded, false if join was skipped
     */
    private boolean joinRegion(TetreeKey<?> parentKey) {
        var parentRegion = regions.get(parentKey);
        if (parentRegion == null || !parentRegion.isActive() || parentRegion.isLeaf()) {
            return false;
        }

        // Change to JOINING state (prevents concurrent joins)
        var joiningParent = parentRegion.withState(TumblerRegion.RegionState.JOINING);
        if (!regions.replace(parentKey, parentRegion, joiningParent)) {
            return false;  // Another thread changed the region
        }

        try {
            // Change all children to JOINING state
            var childKeys = joiningParent.childKeys();
            for (var childKey : childKeys) {
                var childRegion = regions.get(childKey);
                if (childRegion != null) {
                    var joiningChild = childRegion.withState(TumblerRegion.RegionState.JOINING);
                    regions.put(childKey, joiningChild);
                }
            }

            // Consolidate entities from children to parent
            consolidateEntities(joiningParent, childKeys);

            // Remove child regions
            for (var childKey : childKeys) {
                regions.remove(childKey);
            }

            // Get current parent region (after consolidation updated it with entities)
            // Then update children list and return to ACTIVE state
            var currentParent = regions.get(parentKey);
            if (currentParent != null) {
                var updatedParent = currentParent
                    .withChildren(java.util.List.of())
                    .withState(TumblerRegion.RegionState.ACTIVE);
                regions.put(parentKey, updatedParent);
            }

            return true;
        } catch (Exception e) {
            // If join fails, restore parent to ACTIVE state
            var restoredParent = joiningParent.withState(TumblerRegion.RegionState.ACTIVE);
            regions.put(parentKey, restoredParent);
            throw new RuntimeException("Join failed for region " + parentKey, e);
        }
    }

    /**
     * Consolidate entities from child regions to parent region.
     *
     * @param parentRegion Parent region
     * @param childKeys    Child region keys
     */
    private void consolidateEntities(TumblerRegion<ID> parentRegion, java.util.List<TetreeKey<?>> childKeys) {
        for (var childKey : childKeys) {
            var childRegion = regions.get(childKey);
            if (childRegion == null) {
                continue;
            }

            // Move all entities from child to parent
            for (var entityId : childRegion.entities()) {
                // Update entity-region mapping
                entityRegions.put(entityId, parentRegion.key());

                // Add entity to parent region
                incrementRegionCount(parentRegion.key(), entityId);
            }
        }
    }
}

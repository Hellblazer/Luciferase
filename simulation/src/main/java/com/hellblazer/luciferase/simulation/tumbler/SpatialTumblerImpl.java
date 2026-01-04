package com.hellblazer.luciferase.simulation.tumbler;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import com.hellblazer.luciferase.simulation.span.SpanConfig;
import com.hellblazer.luciferase.simulation.span.SpatialSpan;
import com.hellblazer.luciferase.simulation.span.SpatialSpanImpl;

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

    // Phase 5: Statistics tracking
    private final AtomicInteger totalSplitCount;
    private final AtomicInteger totalJoinCount;
    private final AtomicInteger splitsSinceSnapshot;
    private final AtomicInteger joinsSinceSnapshot;
    private volatile long lastSnapshotTime;

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

        // Phase 3: Initialize boundary span manager
        var spanConfig = SpanConfig.defaults()
            .withSpanWidthRatio(config.spanWidthRatio())
            .withMinSpanDistance(config.minSpanDistance());
        this.span = new SpatialSpanImpl<>(tetree, spanConfig, () -> regions.keySet());

        // Phase 5: Initialize statistics tracking
        this.totalSplitCount = new AtomicInteger(0);
        this.totalJoinCount = new AtomicInteger(0);
        this.splitsSinceSnapshot = new AtomicInteger(0);
        this.joinsSinceSnapshot = new AtomicInteger(0);
        this.lastSnapshotTime = System.currentTimeMillis();

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

        // Phase 3: Update boundary tracking
        span.updateBoundary(entityId, position);

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

        // Phase 3: Update boundary tracking
        span.updateBoundary(entityId, newPosition);

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

            // Phase 3: Remove from boundary tracking
            span.removeBoundary(entityId);

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
                // Phase 5: Track statistics
                totalSplitCount.incrementAndGet();
                splitsSinceSnapshot.incrementAndGet();
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
                // Phase 5: Track statistics
                totalJoinCount.incrementAndGet();
                joinsSinceSnapshot.incrementAndGet();
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
        // Phase 5: Collect comprehensive statistics
        var currentTime = System.currentTimeMillis();
        var timeSinceSnapshot = (currentTime - lastSnapshotTime) / 1000.0f; // seconds

        // Collect region distribution by level
        var regionsByLevel = new java.util.HashMap<Byte, Integer>();
        var entityCounts = new java.util.HashMap<TetreeKey<?>, Integer>();
        int totalEntities = 0;
        int maxEntities = 0;
        int minEntities = Integer.MAX_VALUE;
        TetreeKey<?> mostLoadedRegion = null;
        TetreeKey<?> leastLoadedRegion = null;

        for (var entry : regions.entrySet()) {
            var regionKey = entry.getKey();
            var region = entry.getValue();

            // Count by level
            byte level = regionKey.getLevel();
            regionsByLevel.merge(level, 1, Integer::sum);

            // Entity counts
            int count = region.entityCount();
            entityCounts.put(regionKey, count);
            totalEntities += count;

            // Track max/min
            if (count > maxEntities) {
                maxEntities = count;
                mostLoadedRegion = regionKey;
            }
            if (count < minEntities && count > 0) {  // Ignore empty regions for min
                minEntities = count;
                leastLoadedRegion = regionKey;
            }
        }

        // Calculate averages
        float avgEntitiesPerRegion = regions.isEmpty() ? 0.0f : (float) totalEntities / regions.size();
        if (minEntities == Integer.MAX_VALUE) {
            minEntities = 0;
        }

        // Calculate throughput
        float splitThroughput = timeSinceSnapshot > 0 ? splitsSinceSnapshot.get() / timeSinceSnapshot : 0.0f;
        float joinThroughput = timeSinceSnapshot > 0 ? joinsSinceSnapshot.get() / timeSinceSnapshot : 0.0f;

        // Get boundary metrics from span
        int boundaryZoneCount = span.getBoundaryZoneCount();
        int boundaryEntityCount = span.getTotalBoundaryEntities();

        // Reset snapshot counters
        var splitsSince = splitsSinceSnapshot.getAndSet(0);
        var joinsSince = joinsSinceSnapshot.getAndSet(0);
        lastSnapshotTime = currentTime;

        return new TumblerStatistics(
            regions.size(),
            Map.copyOf(regionsByLevel),
            Map.copyOf(entityCounts),
            totalEntities,
            avgEntitiesPerRegion,
            maxEntities,
            minEntities,
            mostLoadedRegion,
            leastLoadedRegion,
            totalSplitCount.get(),
            totalJoinCount.get(),
            splitsSince,
            joinsSince,
            0L,  // TODO: Track average split time
            0L,  // TODO: Track average join time
            splitThroughput,
            joinThroughput,
            boundaryZoneCount,
            boundaryEntityCount,
            currentTime
        );
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

            // Phase 3: Recalculate boundary zones after split
            span.recalculateBoundaries();

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

            // Phase 3: Recalculate boundary zones after join
            span.recalculateBoundaries();

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

    // ===== Phase 5: Affinity-Based Assignment and Load Balancing =====

    /**
     * Get migration recommendations for load balancing.
     * <p>
     * Phase 5: Affinity-based region assignment.
     * Returns entities that should migrate from over-loaded to under-loaded regions.
     *
     * @param imbalanceThreshold Load imbalance threshold (e.g., 2.0 = max is 2x average)
     * @return Map of entityId -> target region for migration
     */
    public java.util.Map<ID, TetreeKey<?>> getMigrationRecommendations(float imbalanceThreshold) {
        var stats = getStatistics();
        var recommendations = new java.util.HashMap<ID, TetreeKey<?>>();

        // Check if load imbalance exceeds threshold
        if (stats.loadImbalanceRatio() <= imbalanceThreshold) {
            return recommendations;  // Load is balanced
        }

        // Get most and least loaded regions
        var mostLoaded = stats.mostLoadedRegion();
        var leastLoaded = stats.leastLoadedRegion();

        if (mostLoaded == null || leastLoaded == null) {
            return recommendations;  // No migration possible
        }

        // Calculate target migration count to balance load
        int maxLoad = stats.maxEntitiesPerRegion();
        int minLoad = stats.minEntitiesPerRegion();
        int targetMigrationCount = (maxLoad - minLoad) / 2;  // Move half the difference

        // Find entities in most-loaded region to migrate
        var mostLoadedRegion = regions.get(mostLoaded);
        if (mostLoadedRegion != null) {
            int migrated = 0;
            for (var entity : mostLoadedRegion.entities()) {
                if (migrated >= targetMigrationCount) {
                    break;
                }
                recommendations.put(entity, leastLoaded);
                migrated++;
            }
        }

        return recommendations;
    }

    /**
     * Calculate entity affinity with its current region.
     * <p>
     * Phase 5: Support for affinity-based assignment decisions.
     * Affinity based on:
     * - Region load (lower load = higher affinity)
     * - Distance from region center (closer = higher affinity)
     *
     * @param entityId Entity to check
     * @return Affinity score [0.0, 1.0], or 0.0 if entity not tracked
     */
    public float getEntityRegionAffinity(ID entityId) {
        var regionKey = entityRegions.get(entityId);
        if (regionKey == null) {
            return 0.0f;
        }

        var region = regions.get(regionKey);
        if (region == null) {
            return 0.0f;
        }

        // Calculate load-based affinity component
        var stats = getStatistics();
        float avgLoad = stats.averageEntitiesPerRegion();
        int regionLoad = region.entityCount();

        // Inverse relationship: lower load = higher affinity
        float loadAffinity = avgLoad > 0 ? Math.min(1.0f, avgLoad / regionLoad) : 1.0f;

        // For Phase 5, use load-based affinity
        // Future: could add spatial distance component
        return loadAffinity;
    }
}

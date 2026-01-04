package com.hellblazer.luciferase.simulation.span;

import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;

import javax.vecmath.Point3f;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Implementation of SpatialSpan for boundary zone management.
 * <p>
 * Phase 3 Implementation Strategy:
 * - Boundary zones created between adjacent regions (simplified adjacency check)
 * - Entities tracked when within boundary width of region edges
 * - Thread-safe using ConcurrentHashMap for all mutable state
 * <p>
 * Performance Characteristics:
 * - Boundary detection: O(N * R) where N = entities, R = boundary zones
 * - Entity lookup: O(1) via entity-to-boundaries map
 * - Recalculation: O(N * R) where N = entities, R = regions
 *
 * @param <ID>      Entity ID type
 * @param <Content> Entity content type
 * @author hal.hildebrand
 */
public class SpatialSpanImpl<ID extends EntityID, Content> implements SpatialSpan<ID> {

    private final Tetree<ID, Content> tetree;
    private final SpanConfig config;
    private final Supplier<Collection<TetreeKey<?>>> regionSupplier;

    // Boundary zones indexed by sorted region pair
    private final ConcurrentHashMap<String, BoundaryZone<ID>> boundaryZones;

    // Entity to boundary zones mapping (one entity can be in multiple boundaries)
    private final ConcurrentHashMap<ID, Set<String>> entityBoundaries;

    /**
     * Create a new SpatialSpan implementation.
     *
     * @param tetree         Underlying tetree for position lookups
     * @param config         Span configuration
     * @param regionSupplier Supplier for current region list
     */
    public SpatialSpanImpl(
        Tetree<ID, Content> tetree,
        SpanConfig config,
        Supplier<Collection<TetreeKey<?>>> regionSupplier
    ) {
        this.tetree = tetree;
        this.config = config;
        this.regionSupplier = regionSupplier;
        this.boundaryZones = new ConcurrentHashMap<>();
        this.entityBoundaries = new ConcurrentHashMap<>();
    }

    @Override
    public Set<ID> getBoundaryEntities(TetreeKey<?> region1, TetreeKey<?> region2) {
        var zoneKey = makeBoundaryKey(region1, region2);
        var zone = boundaryZones.get(zoneKey);
        return zone != null ? Set.copyOf(zone.entities()) : Set.of();
    }

    @Override
    public Set<ID> getAllBoundaryEntities(TetreeKey<?> regionKey) {
        var result = new HashSet<ID>();

        // Find all boundary zones containing this region
        for (var zone : boundaryZones.values()) {
            if (zone.contains(regionKey)) {
                result.addAll(zone.entities());
            }
        }

        return result;
    }

    @Override
    public boolean isInBoundary(ID entityId) {
        return entityBoundaries.containsKey(entityId);
    }

    @Override
    public Collection<BoundaryZone<ID>> getBoundaryZones(TetreeKey<?> regionKey) {
        var result = new ArrayList<BoundaryZone<ID>>();

        for (var zone : boundaryZones.values()) {
            if (zone.contains(regionKey)) {
                result.add(zone);
            }
        }

        return result;
    }

    @Override
    public void updateBoundary(ID entityId, Point3f position) {
        // Get all current regions
        var regions = new ArrayList<>(regionSupplier.get());

        // Find which boundary zones this entity should be in
        var newBoundaries = new HashSet<String>();

        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
                var region1 = regions.get(i);
                var region2 = regions.get(j);

                if (isNearBoundary(position, region1, region2)) {
                    var zoneKey = makeBoundaryKey(region1, region2);
                    newBoundaries.add(zoneKey);

                    // Ensure boundary zone exists
                    boundaryZones.computeIfAbsent(zoneKey, k -> createBoundaryZone(region1, region2));
                }
            }
        }

        // Get old boundaries for this entity
        var oldBoundaries = entityBoundaries.getOrDefault(entityId, Set.of());

        // Add entity to new boundaries
        for (var zoneKey : newBoundaries) {
            if (!oldBoundaries.contains(zoneKey)) {
                addEntityToBoundary(entityId, zoneKey);
            }
        }

        // Remove entity from old boundaries no longer active
        for (var zoneKey : oldBoundaries) {
            if (!newBoundaries.contains(zoneKey)) {
                removeEntityFromBoundary(entityId, zoneKey);
            }
        }

        // Update entity boundaries tracking
        if (newBoundaries.isEmpty()) {
            entityBoundaries.remove(entityId);
        } else {
            entityBoundaries.put(entityId, newBoundaries);
        }
    }

    @Override
    public void removeBoundary(ID entityId) {
        var boundaries = entityBoundaries.remove(entityId);
        if (boundaries != null) {
            for (var zoneKey : boundaries) {
                removeEntityFromBoundary(entityId, zoneKey);
            }
        }
    }

    @Override
    public void recalculateBoundaries() {
        // Clear all existing boundaries
        boundaryZones.clear();
        entityBoundaries.clear();

        // Recalculate boundaries for all entities
        // Note: This is expensive O(N * R^2) but only called after split/join
        var regions = new ArrayList<>(regionSupplier.get());

        // Create boundary zones between adjacent regions
        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
                var region1 = regions.get(i);
                var region2 = regions.get(j);

                if (areAdjacent(region1, region2)) {
                    var zoneKey = makeBoundaryKey(region1, region2);
                    boundaryZones.put(zoneKey, createBoundaryZone(region1, region2));
                }
            }
        }

        // Re-classify all entities
        // TODO: Get all entities from tetree and update their boundaries
        // For Phase 3, this is deferred - entities will be updated incrementally
    }

    @Override
    public int getBoundaryZoneCount() {
        return boundaryZones.size();
    }

    @Override
    public int getTotalBoundaryEntities() {
        return boundaryZones.values().stream()
            .mapToInt(BoundaryZone::entityCount)
            .sum();
    }

    @Override
    public SpanConfig getConfig() {
        return config;
    }

    // Helper Methods

    /**
     * Create unique key for boundary zone between two regions.
     * Key is sorted to ensure region1-region2 and region2-region1 produce same key.
     */
    private String makeBoundaryKey(TetreeKey<?> region1, TetreeKey<?> region2) {
        // Sort regions to ensure consistent key
        if (region1.compareTo(region2) < 0) {
            return region1.toString() + ":" + region2.toString();
        } else {
            return region2.toString() + ":" + region1.toString();
        }
    }

    /**
     * Create a new boundary zone between two regions.
     */
    private BoundaryZone<ID> createBoundaryZone(TetreeKey<?> region1, TetreeKey<?> region2) {
        // Calculate boundary width based on region size
        var tet1 = Tet.tetrahedron(region1);
        var regionSize = calculateRegionSize(tet1);
        var boundaryWidth = config.calculateBoundaryWidth(regionSize);

        return BoundaryZone.create(region1, region2, boundaryWidth);
    }

    /**
     * Calculate approximate size of a tetrahedral region.
     * Uses bounding box diagonal as size metric.
     */
    private float calculateRegionSize(Tet tet) {
        var coords = tet.coordinates();
        var v0 = coords[0];
        var v1 = coords[1];
        var v2 = coords[2];
        var v3 = coords[3];

        // Find bounding box
        var minX = Math.min(Math.min(v0.x, v1.x), Math.min(v2.x, v3.x));
        var maxX = Math.max(Math.max(v0.x, v1.x), Math.max(v2.x, v3.x));
        var minY = Math.min(Math.min(v0.y, v1.y), Math.min(v2.y, v3.y));
        var maxY = Math.max(Math.max(v0.y, v1.y), Math.max(v2.y, v3.y));
        var minZ = Math.min(Math.min(v0.z, v1.z), Math.min(v2.z, v3.z));
        var maxZ = Math.max(Math.max(v0.z, v1.z), Math.max(v2.z, v3.z));

        // Diagonal length as size metric
        var dx = maxX - minX;
        var dy = maxY - minY;
        var dz = maxZ - minZ;

        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Check if position is near the boundary between two regions.
     * Simplified check for Phase 3: within boundary width of either region's edge.
     */
    private boolean isNearBoundary(Point3f position, TetreeKey<?> region1, TetreeKey<?> region2) {
        var tet1 = Tet.tetrahedron(region1);
        var tet2 = Tet.tetrahedron(region2);

        // Check if position is in either region
        var inRegion1 = tet1.contains(position);
        var inRegion2 = tet2.contains(position);

        if (!inRegion1 && !inRegion2) {
            return false;  // Not in either region
        }

        // Calculate distances from region centroids
        var centroid1 = calculateCentroid(tet1);
        var centroid2 = calculateCentroid(tet2);

        var dist1 = distance(position, centroid1);
        var dist2 = distance(position, centroid2);

        var regionSize = calculateRegionSize(tet1);
        var boundaryWidth = config.calculateBoundaryWidth(regionSize);

        // Simplified: near boundary if relatively close to both centroids
        // Proper implementation would use distance to boundary surface
        var maxDist = regionSize / 2.0f;
        return dist1 < (maxDist - boundaryWidth) || dist2 < (maxDist - boundaryWidth);
    }

    /**
     * Check if two regions are adjacent (share a boundary).
     * Simplified check for Phase 3: within distance threshold.
     */
    private boolean areAdjacent(TetreeKey<?> region1, TetreeKey<?> region2) {
        var tet1 = Tet.tetrahedron(region1);
        var tet2 = Tet.tetrahedron(region2);

        var centroid1 = calculateCentroid(tet1);
        var centroid2 = calculateCentroid(tet2);

        var dist = distance(centroid1, centroid2);
        var regionSize = calculateRegionSize(tet1);

        // Adjacent if centroids within ~2x region size
        return dist < (regionSize * 2.0f);
    }

    /**
     * Calculate centroid of a tetrahedron.
     */
    private Point3f calculateCentroid(Tet tet) {
        var coords = tet.coordinates();
        var cx = (coords[0].x + coords[1].x + coords[2].x + coords[3].x) / 4.0f;
        var cy = (coords[0].y + coords[1].y + coords[2].y + coords[3].y) / 4.0f;
        var cz = (coords[0].z + coords[1].z + coords[2].z + coords[3].z) / 4.0f;
        return new Point3f(cx, cy, cz);
    }

    /**
     * Calculate Euclidean distance between two points.
     */
    private float distance(Point3f p1, Point3f p2) {
        var dx = p1.x - p2.x;
        var dy = p1.y - p2.y;
        var dz = p1.z - p2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Add entity to a boundary zone.
     */
    private void addEntityToBoundary(ID entityId, String zoneKey) {
        boundaryZones.computeIfPresent(zoneKey, (k, zone) -> {
            var newZone = zone.withEntityAdded(entityId);
            return newZone.entityCount() <= config.maxBoundaryEntities() ? newZone : zone;
        });
    }

    /**
     * Remove entity from a boundary zone.
     */
    private void removeEntityFromBoundary(ID entityId, String zoneKey) {
        boundaryZones.computeIfPresent(zoneKey, (k, zone) -> zone.withEntityRemoved(entityId));
    }
}

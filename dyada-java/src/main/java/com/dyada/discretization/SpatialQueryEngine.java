package com.dyada.discretization;

import com.dyada.core.coordinates.*;
import com.dyada.core.linearization.LinearRange;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * High-level spatial query engine that provides efficient spatial operations
 * on top of DyAda's adaptive discretization structure.
 * 
 * The SpatialQueryEngine maintains an in-memory spatial index that maps entities
 * to their grid cells, enabling fast spatial queries like range searches,
 * nearest neighbor queries, and collision detection.
 */
public final class SpatialQueryEngine<T> {
    
    private final SpatialDiscretizer discretizer;
    private final Map<LevelIndex, Set<EntityEntry<T>>> spatialIndex;
    private final Map<T, EntityEntry<T>> entityLookup;
    private final QueryStats stats;
    
    /**
     * Creates a new SpatialQueryEngine with the specified discretizer.
     */
    public SpatialQueryEngine(SpatialDiscretizer discretizer) {
        this.discretizer = Objects.requireNonNull(discretizer, "SpatialDiscretizer cannot be null");
        this.spatialIndex = new ConcurrentHashMap<>();
        this.entityLookup = new ConcurrentHashMap<>();
        this.stats = new QueryStats();
    }
    
    /**
     * Creates a SpatialQueryEngine for the unit hypercube with regular refinement.
     */
    public static <T> SpatialQueryEngine<T> forUnitCube(int dimensions, int maxLevel) {
        var discretizer = SpatialDiscretizer.forUnitCube(dimensions, maxLevel);
        return new SpatialQueryEngine<>(discretizer);
    }
    
    /**
     * Creates a SpatialQueryEngine with custom spatial bounds.
     */
    public static <T> SpatialQueryEngine<T> forBounds(CoordinateInterval bounds, int maxLevel) {
        var discretizer = SpatialDiscretizer.forBounds(bounds, maxLevel);
        return new SpatialQueryEngine<>(discretizer);
    }
    
    /**
     * Inserts an entity at the specified location.
     */
    public void insert(T entity, Coordinate location) {
        Objects.requireNonNull(entity, "Entity cannot be null");
        Objects.requireNonNull(location, "Location cannot be null");
        
        stats.incrementInsertions();
        
        try {
            var cell = discretizer.discretize(location);
            var entry = new EntityEntry<>(entity, location, cell);
            
            // Remove existing entry if present
            remove(entity);
            
            // Add to spatial index
            spatialIndex.computeIfAbsent(cell, k -> ConcurrentHashMap.newKeySet()).add(entry);
            entityLookup.put(entity, entry);
            
        } catch (Exception e) {
            stats.incrementErrors();
            if (e instanceof SpatialQueryException) {
                throw (SpatialQueryException) e;
            }
            throw new SpatialQueryException("Insert operation failed", e);
        }
    }
    
    /**
     * Removes an entity from the spatial index.
     */
    public boolean remove(T entity) {
        Objects.requireNonNull(entity, "Entity cannot be null");
        
        var entry = entityLookup.remove(entity);
        if (entry == null) {
            return false; // Entity not found
        }
        
        var cellEntities = spatialIndex.get(entry.cell());
        if (cellEntities != null) {
            cellEntities.remove(entry);
            if (cellEntities.isEmpty()) {
                spatialIndex.remove(entry.cell());
            }
        }
        
        stats.incrementRemovals();
        return true;
    }
    
    /**
     * Updates an entity's location.
     */
    public void update(T entity, Coordinate newLocation) {
        Objects.requireNonNull(entity, "Entity cannot be null");
        Objects.requireNonNull(newLocation, "New location cannot be null");
        
        // Add validation to check if entity exists before updating
        if (!entityLookup.containsKey(entity)) {
            throw new IllegalArgumentException("Entity not found: " + entity);
        }
        
        remove(entity);
        insert(entity, newLocation);
        stats.incrementUpdates();
    }
    
    /**
     * Returns the current location of an entity, or null if not found.
     */
    public Coordinate getLocation(T entity) {
        var entry = entityLookup.get(entity);
        return entry != null ? entry.location() : null;
    }
    
    /**
     * Returns all entities within the specified spatial range.
     */
    public List<T> rangeQuery(CoordinateInterval range) {
        Objects.requireNonNull(range, "Range cannot be null");
        
        stats.incrementRangeQueries();
        
        try {
            var cells = discretizer.discretizeRegion(range);
            var results = new ArrayList<T>();
            
            for (var cell : cells) {
                var cellEntities = spatialIndex.get(cell);
                if (cellEntities != null) {
                    for (var entry : cellEntities) {
                        if (range.contains(entry.location())) {
                            results.add(entry.entity());
                        }
                    }
                }
            }
            
            return results;
        } catch (Exception e) {
            stats.incrementErrors();
            throw new SpatialQueryException("Range query failed", e);
        }
    }
    
    /**
     * Returns all entities within the specified distance of a point.
     */
    public List<T> radiusQuery(Coordinate center, double radius) {
        Objects.requireNonNull(center, "Center cannot be null");
        if (radius < 0.0) {
            throw new IllegalArgumentException("Radius cannot be negative: " + radius);
        }
        
        stats.incrementRadiusQueries();
        
        try {
            var cells = discretizer.rangeQuery(center, radius);
            var results = new ArrayList<T>();
            
            for (var cell : cells) {
                var cellEntities = spatialIndex.get(cell);
                if (cellEntities != null) {
                    for (var entry : cellEntities) {
                        double distance = entry.location().distance(center);
                        if (distance <= radius) {
                            results.add(entry.entity());
                        }
                    }
                }
            }
            
            return results;
        } catch (Exception e) {
            stats.incrementErrors();
            throw new SpatialQueryException("Radius query failed", e);
        }
    }
    
    /**
     * Finds the k nearest neighbors to the specified point.
     * Returns entities with distance information.
     */
    public List<EntityDistance<T>> kNearestNeighborsWithDistance(Coordinate center, int k) {
        Objects.requireNonNull(center, "Center cannot be null");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        
        stats.incrementKnnQueries();
        
        try {
            // Use a priority queue to maintain the k nearest neighbors
            var heap = new PriorityQueue<EntityDistance<T>>(k + 1, 
                Comparator.<EntityDistance<T>>comparingDouble(ed -> ed.distance()).reversed());
            
            // Start with a small radius and expand if needed
            double searchRadius = discretizer.getMinCellSize();
            var searched = new HashSet<LevelIndex>();
            
            while (heap.size() < k) {
                var cells = discretizer.rangeQuery(center, searchRadius);
                boolean foundNewEntities = false;
                
                for (var cell : cells) {
                    if (searched.contains(cell)) {
                        continue; // Already processed this cell
                    }
                    searched.add(cell);
                    
                    var cellEntities = spatialIndex.get(cell);
                    if (cellEntities != null) {
                        for (var entry : cellEntities) {
                            double distance = entry.location().distance(center);
                            heap.offer(new EntityDistance<>(entry.entity(), distance));
                            foundNewEntities = true;
                            
                            if (heap.size() > k) {
                                heap.poll(); // Remove farthest
                            }
                        }
                    }
                }
                
                // If we found enough entities, exit
                if (heap.size() >= k) {
                    break;
                }
                
                // If no new entities were found and we've expanded beyond a reasonable radius, exit
                if (!foundNewEntities && searchRadius > discretizer.getBounds().size().magnitude()) {
                    break;
                }
                
                searchRadius *= 2.0; // Expand search radius
                
                // Safety check to prevent infinite loops
                if (searchRadius > discretizer.getBounds().size().magnitude() * 2) {
                    break;
                }
            }
            
            // Convert to list and sort by distance
            var results = new ArrayList<>(heap);
            results.sort(Comparator.comparingDouble(EntityDistance::distance));
            
            return results.subList(0, Math.min(k, results.size()));
            
        } catch (Exception e) {
            stats.incrementErrors();
            throw new SpatialQueryException("k-NN query failed", e);
        }
    }
    
    /**
     * Finds the k nearest neighbors to the specified point.
     * Returns just the entities without distance information.
     */
    public List<T> kNearestNeighbors(Coordinate center, int k) {
        return kNearestNeighborsWithDistance(center, k).stream()
            .map(EntityDistance::entity)
            .collect(Collectors.toList());
    }
    
    /**
     * Returns all entities in the same grid cell as the specified point.
     */
    public List<T> cellQuery(Coordinate point) {
        Objects.requireNonNull(point, "Point cannot be null");
        
        try {
            var cell = discretizer.discretize(point);
            var cellEntities = spatialIndex.get(cell);
            
            if (cellEntities == null) {
                return Collections.emptyList();
            }
            
            return cellEntities.stream()
                .map(EntityEntry::entity)
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            stats.incrementErrors();
            throw new SpatialQueryException("Cell query failed", e);
        }
    }
    
    /**
     * Returns entities that might collide with the given entity.
     * Uses the entity's current location and searches neighboring cells.
     */
    public List<T> getCollisionCandidates(T entity) {
        Objects.requireNonNull(entity, "Entity cannot be null");
        
        var entry = entityLookup.get(entity);
        if (entry == null) {
            return Collections.emptyList();
        }
        
        try {
            var neighbors = discretizer.getNeighbors(entry.cell());
            var candidates = new ArrayList<T>();
            
            // Add entities from the same cell
            var cellEntities = spatialIndex.get(entry.cell());
            if (cellEntities != null) {
                cellEntities.stream()
                    .map(EntityEntry::entity)
                    .filter(e -> !e.equals(entity))
                    .forEach(candidates::add);
            }
            
            // Add entities from neighboring cells
            for (var neighborCell : neighbors) {
                var neighborEntities = spatialIndex.get(neighborCell);
                if (neighborEntities != null) {
                    neighborEntities.stream()
                        .map(EntityEntry::entity)
                        .forEach(candidates::add);
                }
            }
            
            return candidates;
            
        } catch (Exception e) {
            stats.incrementErrors();
            throw new SpatialQueryException("Collision candidate query failed", e);
        }
    }
    
    /**
     * Returns all entities currently in the spatial index.
     */
    public Set<T> getAllEntities() {
        return new HashSet<>(entityLookup.keySet());
    }
    
    /**
     * Returns the number of entities in the spatial index.
     */
    public int size() {
        return entityLookup.size();
    }
    
    /**
     * Returns true if the spatial index is empty.
     */
    public boolean isEmpty() {
        return entityLookup.isEmpty();
    }
    
    /**
     * Clears all entities from the spatial index.
     */
    public void clear() {
        spatialIndex.clear();
        entityLookup.clear();
        stats.reset();
    }
    
    /**
     * Returns the underlying spatial discretizer.
     */
    public SpatialDiscretizer getDiscretizer() {
        return discretizer;
    }
    
    /**
     * Returns query performance statistics.
     */
    public QueryStats getStats() {
        return stats;
    }
    
    /**
     * Returns information about the current spatial distribution.
     */
    public SpatialDistribution getDistribution() {
        var cellCounts = new HashMap<Integer, Integer>();
        var maxEntitiesPerCell = 0;
        var totalCells = spatialIndex.size();
        
        for (var cellEntities : spatialIndex.values()) {
            int count = cellEntities.size();
            cellCounts.merge(count, 1, Integer::sum);
            maxEntitiesPerCell = Math.max(maxEntitiesPerCell, count);
        }
        
        double avgEntitiesPerCell = totalCells > 0 ? (double) size() / totalCells : 0.0;
        
        return new SpatialDistribution(
            totalCells,
            maxEntitiesPerCell,
            avgEntitiesPerCell,
            cellCounts
        );
    }
    
    // Helper classes and records
    
    /**
     * Internal record representing an entity with its location and grid cell.
     */
    private record EntityEntry<T>(
        T entity,
        Coordinate location,
        LevelIndex cell
    ) {}
    
    /**
     * Result record for k-NN queries containing an entity and its distance.
     */
    public record EntityDistance<T>(
        T entity,
        double distance
    ) {}
    
    /**
     * Statistics about query performance.
     */
    public static class QueryStats {
        private long insertions = 0;
        private long removals = 0;
        private long updates = 0;
        private long rangeQueries = 0;
        private long radiusQueries = 0;
        private long knnQueries = 0;
        private long errors = 0;
        
        public synchronized void incrementInsertions() { insertions++; }
        public synchronized void incrementRemovals() { removals++; }
        public synchronized void incrementUpdates() { updates++; }
        public synchronized void incrementRangeQueries() { rangeQueries++; }
        public synchronized void incrementRadiusQueries() { radiusQueries++; }
        public synchronized void incrementKnnQueries() { knnQueries++; }
        public synchronized void incrementErrors() { errors++; }
        
        public synchronized void reset() {
            insertions = removals = updates = rangeQueries = radiusQueries = knnQueries = errors = 0;
        }
        
        public synchronized long getInsertions() { return insertions; }
        public synchronized long getRemovals() { return removals; }
        public synchronized long getUpdates() { return updates; }
        public synchronized long getRangeQueries() { return rangeQueries; }
        public synchronized long getRadiusQueries() { return radiusQueries; }
        public synchronized long getKnnQueries() { return knnQueries; }
        public synchronized long getErrors() { return errors; }
        
        @Override
        public synchronized String toString() {
            return String.format(
                "QueryStats{insertions=%d, removals=%d, updates=%d, rangeQueries=%d, radiusQueries=%d, knnQueries=%d, errors=%d}",
                insertions, removals, updates, rangeQueries, radiusQueries, knnQueries, errors);
        }
    }
    
    /**
     * Information about spatial distribution of entities.
     */
    public record SpatialDistribution(
        int totalCells,
        int maxEntitiesPerCell,
        double avgEntitiesPerCell,
        Map<Integer, Integer> entityCountDistribution
    ) {
        @Override
        public String toString() {
            return String.format(
                "SpatialDistribution{cells=%d, maxPerCell=%d, avgPerCell=%.2f}",
                totalCells, maxEntitiesPerCell, avgEntitiesPerCell);
        }
    }
}
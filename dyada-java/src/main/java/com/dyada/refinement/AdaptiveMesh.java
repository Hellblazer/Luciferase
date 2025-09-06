package com.dyada.refinement;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.CoordinateInterval;
import com.dyada.core.coordinates.LevelIndex;
import com.dyada.core.coordinates.Bounds;
import com.dyada.core.descriptors.Grid;
import com.dyada.discretization.SpatialDiscretizer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Adaptive mesh that dynamically refines and coarsens based on refinement strategies.
 * Provides efficient spatial data management with hierarchical cell structure.
 */
public class AdaptiveMesh {
    
    /**
     * Represents a mesh cell in the adaptive mesh hierarchy.
     */
    public static class MeshCell {
        private final LevelIndex index;
        private final Bounds bounds;
        private final Map<String, Double> fieldValues;
        private final Set<MeshCell> children;
        private MeshCell parent;
        private boolean isActive;
        private double lastRefinementScore;
        
        public MeshCell(LevelIndex index, Bounds bounds) {
            this.index = index;
            this.bounds = bounds;
            this.fieldValues = new ConcurrentHashMap<>();
            this.children = ConcurrentHashMap.newKeySet();
            this.isActive = true;
            this.lastRefinementScore = 0.0;
        }
        
        // Getters
        public LevelIndex getIndex() { return index; }
        public Bounds getBounds() { return bounds; }
        public Map<String, Double> getFieldValues() { return fieldValues; }
        public Set<MeshCell> getChildren() { return children; }
        public Optional<MeshCell> getParent() { return Optional.ofNullable(parent); }
        public boolean isActive() { return isActive; }
        public double getLastRefinementScore() { return lastRefinementScore; }
        public boolean isLeaf() { return children.isEmpty(); }
        public boolean hasChildren() { return !children.isEmpty(); }
        
        // Operations
        public void setFieldValue(String fieldName, double value) {
            fieldValues.put(fieldName, value);
        }
        
        public Optional<Double> getFieldValue(String fieldName) {
            return Optional.ofNullable(fieldValues.get(fieldName));
        }
        
        public void setActive(boolean active) {
            this.isActive = active;
        }
        
        public void setRefinementScore(double score) {
            this.lastRefinementScore = score;
        }
        
        void addChild(MeshCell child) {
            children.add(child);
            child.parent = this;
        }
        
        void removeChild(MeshCell child) {
            children.remove(child);
            child.parent = null;
        }
        
        public int getLevel() {
            byte[] levels = index.dLevel();
            byte maxLevel = 0;
            for (byte level : levels) {
                if (level > maxLevel) {
                    maxLevel = level;
                }
            }
            return maxLevel;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof MeshCell other)) return false;
            return index.equals(other.index);
        }
        
        @Override
        public int hashCode() {
            return index.hashCode();
        }
    }
    
    /**
     * Statistics about the adaptive mesh state.
     */
    public record MeshStatistics(
        int totalCells,
        int activeCells,
        int leafCells,
        int maxLevel,
        double averageRefinementScore,
        Map<Integer, Integer> cellsByLevel
    ) {}
    
    /**
     * Result of a refinement operation.
     */
    public record RefinementResult(
        int cellsRefined,
        int cellsCoarsened,
        int newActiveCells,
        long executionTimeMs,
        MeshStatistics statistics
    ) {}
    
    private final SpatialDiscretizer discretizer;
    private final Map<LevelIndex, MeshCell> cellMap;
    private final Set<MeshCell> activeCells;
    private final Bounds meshBounds;
    private final AtomicInteger refinementGeneration;
    private final int maxRefinementLevel;
    private final double minCellSize;
    
    public AdaptiveMesh(Bounds bounds, int initialResolution, int maxRefinementLevel, double minCellSize) {
        if (bounds == null) {
            throw new IllegalArgumentException("Bounds cannot be null");
        }
        if (maxRefinementLevel < 0) {
            throw new IllegalArgumentException("Max refinement level cannot be negative");
        }
        if (initialResolution <= 0) {
            throw new IllegalArgumentException("Initial resolution must be positive");
        }
        if (minCellSize <= 0) {
            throw new IllegalArgumentException("Min cell size must be positive");
        }
        
        this.meshBounds = bounds;
        this.maxRefinementLevel = maxRefinementLevel;
        this.minCellSize = minCellSize;
        // Convert Bounds to CoordinateInterval for SpatialDiscretizer
        var lowerCoord = new Coordinate(bounds.min());
        var upperCoord = new Coordinate(bounds.max());
        var coordinateInterval = new CoordinateInterval(lowerCoord, upperCoord);
        
        // Create basic RefinementDescriptor for uniform grid
        var refinementDescriptor = com.dyada.core.descriptors.RefinementDescriptor.create(bounds.dimensions());
        
        this.discretizer = new SpatialDiscretizer(refinementDescriptor, coordinateInterval);
        this.cellMap = new ConcurrentHashMap<>();
        this.activeCells = ConcurrentHashMap.newKeySet();
        this.refinementGeneration = new AtomicInteger(0);
        
        initializeMesh(initialResolution);
    }
    
    private void initializeMesh(int resolution) {
        // Create initial uniform grid
        var dimensions = meshBounds.dimensions();
        var cellSize = new double[dimensions];
        
        for (int d = 0; d < dimensions; d++) {
            cellSize[d] = (meshBounds.max()[d] - meshBounds.min()[d]) / resolution;
        }
        
        // Generate initial cells
        generateInitialCells(new int[dimensions], 0, cellSize);
    }
    
    private void generateInitialCells(int[] indices, int dimension, double[] cellSize) {
        if (dimension == indices.length) {
            // Create cell at this position
            var cellBounds = createCellBounds(indices, cellSize);
            var levelIndex = createLevelIndex(indices);
            var cell = new MeshCell(levelIndex, cellBounds);
            
            cellMap.put(levelIndex, cell);
            activeCells.add(cell);
            return;
        }
        
        int resolution = (int) Math.round((meshBounds.max()[dimension] - meshBounds.min()[dimension]) / cellSize[dimension]);
        for (int i = 0; i < resolution; i++) {
            indices[dimension] = i;
            generateInitialCells(indices, dimension + 1, cellSize);
        }
    }
    
    private Bounds createCellBounds(int[] indices, double[] cellSize) {
        var dimensions = indices.length;
        var min = new double[dimensions];
        var max = new double[dimensions];
        
        for (int d = 0; d < dimensions; d++) {
            min[d] = meshBounds.min()[d] + indices[d] * cellSize[d];
            max[d] = min[d] + cellSize[d];
        }
        
        return new Bounds(min, max);
    }
    
    private LevelIndex createLevelIndex(int[] indices) {
        var levels = new byte[indices.length];
        var longIndices = Arrays.stream(indices).asLongStream().toArray();
        
        // Calculate the level needed for each dimension based on the maximum index
        for (int d = 0; d < indices.length; d++) {
            if (indices[d] == 0) {
                levels[d] = 0; // Level 0 can hold index 0
            } else {
                // Find the minimum level that can hold this index
                // Level n can hold indices 0 to (2^n - 1)
                levels[d] = (byte) (32 - Integer.numberOfLeadingZeros(indices[d]));
            }
        }
        
        return new LevelIndex(levels, longIndices);
    }
    
    /**
     * Applies adaptive refinement using the specified strategy and criteria.
     */
    public RefinementResult refineAdaptively(AdaptiveRefinementStrategy strategy, RefinementCriteria criteria) {
        var startTime = System.currentTimeMillis();
        var generation = refinementGeneration.incrementAndGet();
        
        int cellsRefined = 0;
        int cellsCoarsened = 0;
        var newCells = new HashSet<MeshCell>();
        var cellsToRemove = new HashSet<MeshCell>();
        
        // Analyze all active leaf cells for refinement
        var leafCells = activeCells.stream()
            .filter(MeshCell::isLeaf)
            .toList();
        
        for (var cell : leafCells) {
            var context = createRefinementContext(cell, generation);
            var decision = strategy.analyzeCell(context, cell.getFieldValues(), criteria);
            
            switch (decision) {
                case REFINE -> {
                    if (canRefine(cell)) {
                        var children = refineCell(cell);
                        newCells.addAll(children);
                        cellsRefined++;
                    }
                }
                case COARSEN -> {
                    if (canCoarsen(cell)) {
                        coarsenCell(cell);
                        cellsToRemove.add(cell);
                        cellsCoarsened++;
                    }
                }
                case MAINTAIN -> {
                    // No action needed
                }
            }
        }
        
        // Update active cells
        activeCells.addAll(newCells);
        activeCells.removeAll(cellsToRemove);
        
        var executionTime = System.currentTimeMillis() - startTime;
        var statistics = computeStatistics();
        
        return new RefinementResult(cellsRefined, cellsCoarsened, newCells.size(), executionTime, statistics);
    }
    
    private AdaptiveRefinementStrategy.RefinementContext createRefinementContext(MeshCell cell, int generation) {
        var cellCenter = computeCellCenter(cell.getBounds());
        var cellSize = computeCellSize(cell.getBounds());
        return new AdaptiveRefinementStrategy.RefinementContext(
            cell.getIndex(),
            cellCenter,
            cellSize,
            cell.getLevel(),
            cell.getFieldValues()
        );
    }
    
    private List<Bounds> findNeighbors(MeshCell cell) {
        var neighbors = new ArrayList<Bounds>();
        var cellBounds = cell.getBounds();
        var tolerance = minCellSize * 0.1; // Small tolerance for floating point comparison
        
        for (var other : activeCells) {
            if (other != cell && areNeighbors(cellBounds, other.getBounds(), tolerance)) {
                neighbors.add(other.getBounds());
            }
        }
        
        return neighbors;
    }
    
    private boolean areNeighbors(Bounds bounds1, Bounds bounds2, double tolerance) {
        var dimensions = bounds1.dimensions();
        
        for (int d = 0; d < dimensions; d++) {
            var gap = Math.max(bounds1.min()[d] - bounds2.max()[d], bounds2.min()[d] - bounds1.max()[d]);
            if (gap > tolerance) {
                return false; // Too far apart in this dimension
            }
        }
        
        // Check if they actually touch (not just overlap)
        for (int d = 0; d < dimensions; d++) {
            if (Math.abs(bounds1.max()[d] - bounds2.min()[d]) <= tolerance ||
                Math.abs(bounds2.max()[d] - bounds1.min()[d]) <= tolerance) {
                return true; // They touch in this dimension
            }
        }
        
        return false;
    }
    
    private boolean canRefine(MeshCell cell) {
        if (cell.getLevel() >= maxRefinementLevel) {
            return false;
        }
        
        var cellSize = computeCellSize(cell.getBounds());
        return cellSize > minCellSize * 2; // Ensure children won't be too small
    }
    
    private boolean canCoarsen(MeshCell cell) {
        var parent = cell.getParent();
        if (parent.isEmpty()) {
            return false; // Can't coarsen root level
        }
        
        // Check if all siblings can be coarsened
        return parent.get().getChildren().stream()
            .allMatch(sibling -> sibling.isLeaf() && shouldCoarsen(sibling));
    }
    
    private boolean shouldCoarsen(MeshCell cell) {
        // Simple heuristic: coarsen if refinement score is very low
        return cell.getLastRefinementScore() < 0.1;
    }
    
    private double computeCellSize(Bounds bounds) {
        var dimensions = bounds.dimensions();
        double minSize = Double.MAX_VALUE;
        
        for (int d = 0; d < dimensions; d++) {
            var size = bounds.max()[d] - bounds.min()[d];
            minSize = Math.min(minSize, size);
        }
        
        return minSize;
    }
    
    private Coordinate computeCellCenter(Bounds bounds) {
        var dimensions = bounds.dimensions();
        var center = new double[dimensions];
        
        for (int d = 0; d < dimensions; d++) {
            center[d] = (bounds.min()[d] + bounds.max()[d]) / 2.0;
        }
        
        return new Coordinate(center);
    }
    
    private Set<MeshCell> refineCell(MeshCell cell) {
        var children = new HashSet<MeshCell>();
        var cellBounds = cell.getBounds();
        var dimensions = cellBounds.dimensions();
        
        // Create 2^dimensions children
        int numChildren = 1 << dimensions;
        
        for (int childIndex = 0; childIndex < numChildren; childIndex++) {
            var childBounds = computeChildBounds(cellBounds, childIndex, dimensions);
            var childLevelIndex = computeChildLevelIndex(cell.getIndex(), childIndex);
            var child = new MeshCell(childLevelIndex, childBounds);
            
            // Inherit parent's field values
            child.getFieldValues().putAll(cell.getFieldValues());
            
            cell.addChild(child);
            cellMap.put(childLevelIndex, child);
            children.add(child);
        }
        
        // Deactivate parent
        cell.setActive(false);
        
        return children;
    }
    
    private Bounds computeChildBounds(Bounds parentBounds, int childIndex, int dimensions) {
        var min = new double[dimensions];
        var max = new double[dimensions];
        
        for (int d = 0; d < dimensions; d++) {
            var parentMin = parentBounds.min()[d];
            var parentMax = parentBounds.max()[d];
            var midpoint = (parentMin + parentMax) / 2.0;
            
            if ((childIndex & (1 << d)) == 0) {
                min[d] = parentMin;
                max[d] = midpoint;
            } else {
                min[d] = midpoint;
                max[d] = parentMax;
            }
        }
        
        return new Bounds(min, max);
    }
    
    private LevelIndex computeChildLevelIndex(LevelIndex parentIndex, int childIndex) {
        var parentIndices = parentIndex.dIndex();
        var parentLevels = parentIndex.dLevel();
        var dimensions = parentIndices.length;
        
        var childIndices = new long[dimensions];
        var childLevels = new byte[dimensions];
        
        for (int d = 0; d < dimensions; d++) {
            childIndices[d] = parentIndices[d] * 2 + ((childIndex >> d) & 1);
            childLevels[d] = (byte) (parentLevels[d] + 1);
        }
        
        return new LevelIndex(childLevels, childIndices);
    }
    
    private void coarsenCell(MeshCell cell) {
        var parent = cell.getParent();
        if (parent.isEmpty()) {
            return; // Can't coarsen root
        }
        
        var parentCell = parent.get();
        
        // Remove all children
        var children = new HashSet<>(parentCell.getChildren());
        for (var child : children) {
            parentCell.removeChild(child);
            cellMap.remove(child.getIndex());
        }
        
        // Reactivate parent
        parentCell.setActive(true);
    }
    
    /**
     * Sets field values for cells containing the given coordinate.
     */
    public void setFieldValue(Coordinate location, String fieldName, double value) {
        var cell = findContainingCell(location);
        if (cell.isPresent()) {
            cell.get().setFieldValue(fieldName, value);
        }
    }
    
    /**
     * Gets field value at the given coordinate.
     */
    public Optional<Double> getFieldValue(Coordinate location, String fieldName) {
        var cell = findContainingCell(location);
        return cell.flatMap(c -> c.getFieldValue(fieldName));
    }
    
    /**
     * Finds the leaf cell containing the given coordinate.
     */
    public Optional<MeshCell> findContainingCell(Coordinate location) {
        return activeCells.stream()
            .filter(cell -> cell.isLeaf() && containsPoint(cell.getBounds(), location))
            .findFirst();
    }
    
    private boolean containsPoint(Bounds bounds, Coordinate point) {
        var coords = point.values();
        var dimensions = bounds.dimensions();
        
        if (coords.length != dimensions) {
            return false;
        }
        
        for (int d = 0; d < dimensions; d++) {
            if (coords[d] < bounds.min()[d] || coords[d] > bounds.max()[d]) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Gets all active cells in the mesh.
     */
    public Set<MeshCell> getActiveCells() {
        return new HashSet<>(activeCells);
    }
    
    /**
     * Gets all leaf cells in the mesh.
     */
    public Set<MeshCell> getLeafCells() {
        return activeCells.stream()
            .filter(MeshCell::isLeaf)
            .collect(HashSet::new, HashSet::add, HashSet::addAll);
    }
    
    /**
     * Computes current mesh statistics.
     */
    public MeshStatistics computeStatistics() {
        var totalCells = cellMap.size();
        var activeCellCount = activeCells.size();
        var leafCells = (int) activeCells.stream().filter(MeshCell::isLeaf).count();
        var maxLevel = activeCells.stream().mapToInt(MeshCell::getLevel).max().orElse(0);
        var avgScore = activeCells.stream().mapToDouble(MeshCell::getLastRefinementScore).average().orElse(0.0);
        
        var cellsByLevel = new HashMap<Integer, Integer>();
        for (var cell : activeCells) {
            cellsByLevel.merge(cell.getLevel(), 1, Integer::sum);
        }
        
        return new MeshStatistics(totalCells, activeCellCount, leafCells, maxLevel, avgScore, cellsByLevel);
    }
    
    /**
     * Gets the bounds of the mesh.
     */
    public Bounds getBounds() {
        return meshBounds;
    }
    
    /**
     * Gets the current refinement generation.
     */
    public int getRefinementGeneration() {
        return refinementGeneration.get();
    }
    
    /**
     * Gets the maximum allowed refinement level.
     */
    public int getMaxRefinementLevel() {
        return maxRefinementLevel;
    }
    
    /**
     * Gets the minimum cell size.
     */
    public double getMinCellSize() {
        return minCellSize;
    }
    
    // Entity management methods
    private final Map<String, Coordinate> entities = new ConcurrentHashMap<>();
    
    /**
     * Gets the number of active nodes in the mesh.
     */
    public Set<MeshCell> getActiveNodes() {
        return getActiveCells();
    }
    
    /**
     * Inserts an entity at the specified position.
     */
    public void insertEntity(String entityId, Coordinate position) {
        if (entityId == null) {
            throw new IllegalArgumentException("Entity ID cannot be null");
        }
        if (position == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }
        
        // Check if position is within bounds
        var posValues = position.values();
        var minBounds = meshBounds.min();
        var maxBounds = meshBounds.max();
        
        for (int i = 0; i < posValues.length && i < minBounds.length; i++) {
            if (posValues[i] < minBounds[i] || posValues[i] > maxBounds[i]) {
                throw new IllegalArgumentException("Position is outside mesh bounds");
            }
        }
        
        entities.put(entityId, position);
    }
    
    /**
     * Checks if the mesh contains the specified entity.
     */
    public boolean containsEntity(String entityId) {
        return entities.containsKey(entityId);
    }
    
    /**
     * Gets the position of the specified entity.
     */
    public Coordinate getEntityPosition(String entityId) {
        var position = entities.get(entityId);
        if (position == null) {
            throw new IllegalArgumentException("Entity not found: " + entityId);
        }
        return position;
    }
    
    /**
     * Removes an entity from the mesh.
     */
    public boolean removeEntity(String entityId) {
        return entities.remove(entityId) != null;
    }
    
    /**
     * Updates the position of an existing entity.
     */
    public void updateEntityPosition(String entityId, Coordinate newPosition) {
        if (!entities.containsKey(entityId)) {
            throw new IllegalArgumentException("Entity not found: " + entityId);
        }
        insertEntity(entityId, newPosition); // Reuse validation logic
    }
    
    /**
     * Gets the total number of entities in the mesh.
     */
    public int getEntityCount() {
        return entities.size();
    }
    
    /**
     * Queries entities within a specified range of a center point.
     */
    public Set<String> queryEntitiesInRange(Coordinate center, double radius) {
        var result = new HashSet<String>();
        double radiusSquared = radius * radius;
        
        for (var entry : entities.entrySet()) {
            if (center.distanceSquared(entry.getValue()) <= radiusSquared) {
                result.add(entry.getKey());
            }
        }
        
        return result;
    }
    
    /**
     * Applies adaptive refinement using the specified strategy and criteria.
     */
    public void refine(AdaptiveRefinementStrategy strategy, RefinementCriteria criteria) {
        refineAdaptively(strategy, criteria);
    }
    
    /**
     * Applies coarsening using the specified strategy and criteria.
     */
    public void coarsen(AdaptiveRefinementStrategy strategy, RefinementCriteria criteria) {
        // Simple coarsening - remove entities that don't meet criteria
        var entitiesToRemove = new ArrayList<String>();
        for (var entry : entities.entrySet()) {
            // Simple heuristic: remove entities in less dense regions
            var nearbyCount = queryEntitiesInRange(entry.getValue(), criteria.minimumDistance()).size();
            if (nearbyCount < criteria.maximumEntitiesPerNode()) {
                entitiesToRemove.add(entry.getKey());
            }
        }
        
        for (var entityId : entitiesToRemove) {
            entities.remove(entityId);
        }
    }
    
}
package com.dyada.discretization;

import com.dyada.core.coordinates.*;
import com.dyada.core.descriptors.RefinementDescriptor;
import com.dyada.core.linearization.Linearization;
import com.dyada.core.linearization.MortonOrderLinearization;
import com.dyada.core.linearization.LinearRange;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core discretization engine that maps continuous coordinates to discrete grid cells.
 * 
 * The SpatialDiscretizer bridges the gap between continuous spatial coordinates and
 * DyAda's discrete tree representation. It uses a RefinementDescriptor to determine
 * the adaptive grid structure and provides methods to:
 * 
 * - Map points to grid cells (discretize)
 * - Convert grid cells back to spatial intervals (undiscretize)
 * - Find all grid cells that intersect with spatial regions
 * - Perform spatial queries using the discrete representation
 */
public final class SpatialDiscretizer {
    
    private final RefinementDescriptor refinement;
    private final CoordinateInterval bounds;
    private final Linearization linearization;
    private final double[] cellSizes;
    private final int maxLevel;
    
    /**
     * Creates a SpatialDiscretizer with the specified refinement structure and spatial bounds.
     */
    public SpatialDiscretizer(RefinementDescriptor refinement, CoordinateInterval bounds) {
        this.refinement = Objects.requireNonNull(refinement, "RefinementDescriptor cannot be null");
        this.bounds = Objects.requireNonNull(bounds, "Spatial bounds cannot be null");
        
        if (refinement.getNumDimensions() != bounds.dimensions()) {
            throw new IllegalArgumentException(
                String.format("Refinement dimensions %d != bounds dimensions %d",
                    refinement.getNumDimensions(), bounds.dimensions()));
        }
        
        this.linearization = new MortonOrderLinearization();
        this.maxLevel = refinement.getMaxDepth();
        this.cellSizes = calculateBaseCellSizes();
        
        validateConfiguration();
    }
    
    /**
     * Creates a SpatialDiscretizer for the unit hypercube with regular refinement.
     */
    public static SpatialDiscretizer forUnitCube(int dimensions, int maxLevel) {
        var levels = new int[dimensions];
        Arrays.fill(levels, maxLevel);
        var refinement = RefinementDescriptor.regular(dimensions, levels);
        var bounds = CoordinateInterval.unitCube(dimensions);
        return new SpatialDiscretizer(refinement, bounds);
    }
    
    /**
     * Creates a SpatialDiscretizer with custom bounds and uniform refinement.
     */
    public static SpatialDiscretizer forBounds(CoordinateInterval bounds, int maxLevel) {
        var levels = new int[bounds.dimensions()];
        Arrays.fill(levels, maxLevel);
        var refinement = RefinementDescriptor.regular(bounds.dimensions(), levels);
        return new SpatialDiscretizer(refinement, bounds);
    }
    
    /**
     * Discretizes a continuous coordinate to its containing grid cell.
     * Returns the LevelIndex of the leaf cell that contains the point.
     */
    public LevelIndex discretize(Coordinate point) {
        validatePointInBounds(point);
        
        // Normalize point to [0,1]^d relative to bounds
        var normalized = normalizePoint(point);
        
        // Find the leaf cell containing this point by traversing the refinement tree
        return findContainingCell(normalized, 0, new int[bounds.dimensions()], (byte) 0);
    }
    
    /**
     * Converts a LevelIndex back to its corresponding spatial interval.
     */
    public CoordinateInterval undiscretize(LevelIndex levelIndex) {
        validateLevelIndex(levelIndex);
        
        // Calculate the spatial bounds of this grid cell
        var cellBounds = calculateCellBounds(levelIndex);
        
        // Transform from normalized [0,1]^d back to actual spatial bounds
        return denormalizeBounds(cellBounds);
    }
    
    /**
     * Finds all grid cells that intersect with the given spatial interval.
     * Returns a list of LevelIndex objects representing the intersecting cells.
     */
    public List<LevelIndex> discretizeRegion(CoordinateInterval region) {
        var intersection = bounds.intersect(region);
        if (intersection == null) {
            return Collections.emptyList(); // Region doesn't intersect our spatial domain
        }
        
        var normalized = normalizeBounds(intersection);
        var cells = new ArrayList<LevelIndex>();
        
        // Recursively find all leaf cells that intersect the normalized region
        findIntersectingCells(normalized, 0, new int[bounds.dimensions()], (byte) 0, cells);
        
        return cells;
    }
    
    /**
     * Performs a spatial range query, returning all grid cells within distance of a point.
     */
    public List<LevelIndex> rangeQuery(Coordinate center, double radius) {
        if (radius < 0.0) {
            throw new IllegalArgumentException("Radius cannot be negative: " + radius);
        }
        
        // Create bounding box around the query point
        var queryBounds = CoordinateInterval.centeredUniform(center, radius);
        var candidates = discretizeRegion(queryBounds);
        
        // Filter candidates to only include cells actually within the circular range
        var results = new ArrayList<LevelIndex>();
        for (var cell : candidates) {
            var cellBounds = undiscretize(cell);
            if (cellBounds.distanceToPoint(center) <= radius) {
                results.add(cell);
            }
        }
        return results;
    }
    
    /**
     * Finds neighboring grid cells around a given cell.
     * Returns cells that share a face, edge, or vertex with the input cell.
     */
    public List<LevelIndex> getNeighbors(LevelIndex cell) {
        var cellBounds = undiscretize(cell);
        var expandedBounds = cellBounds.expand(getMinCellSize() * 0.1); // Small epsilon expansion
        
        var candidates = discretizeRegion(expandedBounds);
        
        // Remove the input cell itself and return neighbors
        return candidates.stream()
            .filter(candidate -> !candidate.equals(cell))
            .collect(Collectors.toList());
    }
    
    /**
     * Returns the spatial bounds covered by this discretizer.
     */
    public CoordinateInterval getBounds() {
        return bounds;
    }
    
    /**
     * Returns the refinement descriptor used by this discretizer.
     */
    public RefinementDescriptor getRefinement() {
        return refinement;
    }
    
    /**
     * Returns the number of dimensions.
     */
    public int getDimensions() {
        return bounds.dimensions();
    }
    
    /**
     * Returns the maximum refinement level.
     */
    public int getMaxLevel() {
        return maxLevel;
    }
    
    /**
     * Returns the minimum cell size across all dimensions.
     */
    public double getMinCellSize() {
        double minSize = Double.MAX_VALUE;
        for (double size : cellSizes) {
            minSize = Math.min(minSize, size / (1 << maxLevel));
        }
        return minSize;
    }
    
    /**
     * Returns the base cell size in each dimension (at level 0).
     */
    public double[] getBaseCellSizes() {
        return cellSizes.clone();
    }
    
    /**
     * Returns statistics about the discretization.
     */
    public DiscretizationStats getStats() {
        return new DiscretizationStats(
            refinement.size(),
            refinement.getNumBoxes(),
            maxLevel,
            getMinCellSize(),
            bounds.volume()
        );
    }
    
    // Private helper methods
    
    private void validateConfiguration() {
        if (maxLevel < 0 || maxLevel > 30) {
            throw new IllegalArgumentException("Max level must be between 0 and 30: " + maxLevel);
        }
        
        if (bounds.volume() <= 0.0) {
            throw new IllegalArgumentException("Spatial bounds must have positive volume");
        }
    }
    
    private double[] calculateBaseCellSizes() {
        var size = bounds.size();
        var sizes = new double[size.dimensions()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = size.get(i);
        }
        return sizes;
    }
    
    private void validatePointInBounds(Coordinate point) {
        if (point.dimensions() != bounds.dimensions()) {
            throw new IllegalArgumentException(
                String.format("Point dimensions %d != bounds dimensions %d",
                    point.dimensions(), bounds.dimensions()));
        }
        
        if (!bounds.contains(point)) {
            throw new SpatialQueryException(
                String.format("Point %s is outside spatial bounds %s", point, bounds));
        }
    }
    
    private void validateLevelIndex(LevelIndex levelIndex) {
        if (levelIndex.dimensions() != bounds.dimensions()) {
            throw new IllegalArgumentException(
                String.format("LevelIndex dimensions %d != bounds dimensions %d",
                    levelIndex.dimensions(), bounds.dimensions()));
        }
        
        // Validate that all levels are within bounds
        for (int i = 0; i < levelIndex.dimensions(); i++) {
            byte level = levelIndex.getLevel(i);
            if (level < 0 || level > maxLevel) {
                throw new IllegalArgumentException(
                    String.format("Level %d at dimension %d is outside valid range [0, %d]",
                        level, i, maxLevel));
            }
        }
    }
    
    private Coordinate normalizePoint(Coordinate point) {
        var normalized = new double[point.dimensions()];
        for (int i = 0; i < normalized.length; i++) {
            double range = bounds.upperBound().get(i) - bounds.lowerBound().get(i);
            normalized[i] = (point.get(i) - bounds.lowerBound().get(i)) / range;
        }
        return Coordinate.of(normalized);
    }
    
    private CoordinateInterval normalizeBounds(CoordinateInterval interval) {
        return new CoordinateInterval(
            normalizePoint(interval.lowerBound()),
            normalizePoint(interval.upperBound())
        );
    }
    
    private CoordinateInterval denormalizeBounds(CoordinateInterval normalized) {
        var lower = new double[normalized.dimensions()];
        var upper = new double[normalized.dimensions()];
        
        for (int i = 0; i < lower.length; i++) {
            double range = bounds.upperBound().get(i) - bounds.lowerBound().get(i);
            lower[i] = bounds.lowerBound().get(i) + normalized.lowerBound().get(i) * range;
            upper[i] = bounds.lowerBound().get(i) + normalized.upperBound().get(i) * range;
        }
        
        return new CoordinateInterval(Coordinate.of(lower), Coordinate.of(upper));
    }
    
    private LevelIndex findContainingCell(Coordinate normalized, int nodeIndex, 
                                        int[] coordinates, byte level) {
        
        if (nodeIndex >= refinement.size()) {
            throw new SpatialQueryException("Refinement tree traversal exceeded bounds");
        }
        
        // Check if this is a leaf node (box)
        if (refinement.isBox(nodeIndex)) {
            // Convert int[] coordinates to long[] indices and create byte[] levels
            var indices = new long[coordinates.length];
            var levels = new byte[coordinates.length];
            for (int i = 0; i < coordinates.length; i++) {
                indices[i] = coordinates[i];
                levels[i] = (byte) level;
            }
            return new LevelIndex(levels, indices);
        }
        
        // This is an internal node - determine which child contains the point
        var refinementPattern = refinement.get(nodeIndex);
        var childCoordinates = coordinates.clone();
        
        // Calculate which child based on the refinement pattern
        int childIndex = 0;
        int bit = 0;
        
        for (int dim = 0; dim < normalized.dimensions(); dim++) {
            if (refinementPattern.get(dim)) {
                // This dimension is refined
                double cellSize = 1.0 / (1 << (level + 1));
                double dimLower = coordinates[dim] * (1.0 / (1 << level));
                double dimCenter = dimLower + cellSize;
                
                if (normalized.get(dim) >= dimCenter) {
                    childCoordinates[dim] = coordinates[dim] * 2 + 1;
                    childIndex |= (1 << bit);
                } else {
                    childCoordinates[dim] = coordinates[dim] * 2;
                }
                bit++;
            }
        }
        
        // Recursively traverse to the appropriate child
        // Get actual child indices from the refinement descriptor
        var children = refinement.getChildren(nodeIndex);
        if (children.isEmpty()) {
            throw new SpatialQueryException("Internal node has no children: " + nodeIndex);
        }
        if (childIndex >= children.size()) {
            throw new SpatialQueryException("Child index " + childIndex + " exceeds available children " + children.size());
        }
        
        int actualChildIndex = children.get(childIndex);
        
        return findContainingCell(normalized, actualChildIndex, 
                                childCoordinates, (byte) (level + 1));
    }
    
    private void findIntersectingCells(CoordinateInterval normalizedRegion, int nodeIndex,
                                     int[] coordinates, byte level, List<LevelIndex> result) {
        
        if (nodeIndex >= refinement.size()) {
            return; // Invalid node index
        }
        
        // Calculate the bounds of the current cell
        var longCoordinates = new long[coordinates.length];
        for (int i = 0; i < coordinates.length; i++) {
            longCoordinates[i] = coordinates[i];
        }
        var cellBounds = calculateNormalizedCellBounds(longCoordinates, level);
        
        // Check if the region intersects this cell
        if (!normalizedRegion.overlaps(cellBounds)) {
            return; // No intersection
        }
        
        // If this is a leaf node, add it to results
        if (refinement.isBox(nodeIndex)) {
            // Convert int[] coordinates to long[] indices and create byte[] levels
            var indices = new long[coordinates.length];
            var levels = new byte[coordinates.length];
            for (int i = 0; i < coordinates.length; i++) {
                indices[i] = coordinates[i];
                levels[i] = (byte) level;
            }
            result.add(new LevelIndex(levels, indices));
            return;
        }
        
        // Recursively check children using proper tree structure
        var children = refinement.getChildren(nodeIndex);
        var refinementPattern = refinement.get(nodeIndex);
        
        for (int i = 0; i < children.size(); i++) {
            var childIndex = children.get(i);
            var childCoords = generateChildCoordinates(coordinates, refinementPattern, i);
            findIntersectingCells(normalizedRegion, childIndex,
                                childCoords, (byte) (level + 1), result);
        }
    }
    
    private int[] generateChildCoordinates(int[] parentCoords, 
                                         com.dyada.core.bitarray.BitArray refinementPattern, 
                                         int childIndex) {
        var childCoords = parentCoords.clone();
        int bit = 0;
        
        for (int dim = 0; dim < refinementPattern.size(); dim++) {
            if (refinementPattern.get(dim)) {
                childCoords[dim] = parentCoords[dim] * 2;
                if ((childIndex & (1 << bit)) != 0) {
                    childCoords[dim]++;
                }
                bit++;
            }
        }
        
        return childCoords;
    }
    
    private CoordinateInterval calculateNormalizedCellBounds(long[] coordinates, byte level) {
        double cellSize = 1.0 / (1 << level);
        var lower = new double[coordinates.length];
        var upper = new double[coordinates.length];
        
        for (int i = 0; i < coordinates.length; i++) {
            lower[i] = coordinates[i] * cellSize;
            upper[i] = lower[i] + cellSize;
        }
        
        return new CoordinateInterval(Coordinate.of(lower), Coordinate.of(upper));
    }
    
    private CoordinateInterval calculateCellBounds(LevelIndex levelIndex) {
        // Convert LevelIndex to coordinates and level for bounds calculation
        var coordinates = levelIndex.getIndices();
        var levels = levelIndex.getLevels();
        // Use the maximum level across dimensions for bounds calculation
        byte maxLevel = 0;
        for (byte level : levels) {
            if (level > maxLevel) maxLevel = level;
        }
        return calculateNormalizedCellBounds(coordinates, maxLevel);
    }
    
    /**
     * Statistics about the discretization structure.
     */
    public record DiscretizationStats(
        int totalNodes,
        int leafCells,
        int maxLevel,
        double minCellSize,
        double totalVolume
    ) {
        @Override
        public String toString() {
            return String.format(
                "DiscretizationStats{nodes=%d, leaves=%d, maxLevel=%d, minCellSize=%.6f, volume=%.6f}",
                totalNodes, leafCells, maxLevel, minCellSize, totalVolume);
        }
    }
}
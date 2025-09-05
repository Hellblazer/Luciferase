package com.dyada.core.linearization;

import com.dyada.core.coordinates.LevelIndex;
import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.CoordinateInterval;

/**
 * Interface for space-filling curve linearization strategies.
 * 
 * Linearization maps multi-dimensional spatial coordinates to a one-dimensional
 * index that preserves spatial locality. This is crucial for DyAda's efficient
 * spatial data structures and enables fast range queries, neighbor finding,
 * and adaptive mesh refinement.
 * 
 * The linearization must support both forward mapping (coordinates to linear index)
 * and reverse mapping (linear index back to coordinates), making it bijective
 * within the supported coordinate space.
 */
public interface Linearization {
    
    /**
     * Converts multi-dimensional level-index coordinates to a linear index.
     * 
     * This is the core operation that maps spatial locations to a space-filling
     * curve position. The resulting linear index preserves spatial locality,
     * meaning nearby points in space map to nearby positions on the curve.
     * 
     * @param levelIndex The multi-dimensional coordinate with refinement levels
     * @return Linear index representing the position on the space-filling curve
     * @throws IllegalArgumentException if levelIndex has incompatible dimensions
     * @throws ArithmeticException if the computation would overflow
     */
    long linearize(LevelIndex levelIndex);
    
    /**
     * Converts a linear index back to multi-dimensional level-index coordinates.
     * 
     * This reverse operation recovers the original spatial coordinates from
     * their linearized representation. Combined with linearize(), this provides
     * a bijective mapping between spatial and linear domains.
     * 
     * @param linearIndex The linear position on the space-filling curve
     * @param dimensions Number of spatial dimensions to reconstruct
     * @return LevelIndex representing the multi-dimensional coordinates
     * @throws IllegalArgumentException if dimensions is invalid or linearIndex is negative
     * @throws ArithmeticException if linearIndex cannot be represented in the coordinate space
     */
    LevelIndex delinearize(long linearIndex, int dimensions);
    
    /**
     * Converts floating-point spatial coordinates to a linear index.
     * 
     * This convenience method handles the discretization of continuous coordinates
     * into the discrete level-index space before linearization. The precision
     * of the discretization depends on the implementation.
     * 
     * @param coordinate The continuous spatial coordinate
     * @param maxLevel Maximum refinement level for discretization
     * @return Linear index for the discretized coordinate
     * @throws IllegalArgumentException if coordinate dimensions don't match or maxLevel is invalid
     */
    long linearize(Coordinate coordinate, byte maxLevel);
    
    /**
     * Converts a linear index to floating-point spatial coordinates.
     * 
     * This recovers continuous coordinates from their linearized representation,
     * providing the spatial location corresponding to the linear index.
     * 
     * @param linearIndex The linear position on the space-filling curve
     * @param dimensions Number of spatial dimensions
     * @param maxLevel Maximum refinement level used for discretization
     * @return Coordinate representing the spatial location
     * @throws IllegalArgumentException if parameters are invalid
     */
    Coordinate delinearize(long linearIndex, int dimensions, byte maxLevel);
    
    /**
     * Linearizes a spatial interval (hyperrectangle) to a range of linear indices.
     * 
     * This method finds the range of linear indices that covers all points
     * within the given spatial interval. This is essential for range queries
     * and spatial filtering operations.
     * 
     * @param interval The spatial interval to linearize
     * @param maxLevel Maximum refinement level for discretization
     * @return LinearRange covering all points in the interval
     * @throws IllegalArgumentException if interval dimensions don't match
     */
    LinearRange linearizeRange(CoordinateInterval interval, byte maxLevel);
    
    /**
     * Returns the maximum number of dimensions supported by this linearization.
     * 
     * Different linearization strategies may have different dimensional limits
     * based on their algorithms and the constraints of linear index representation.
     * 
     * @return Maximum supported dimension count, or Integer.MAX_VALUE if unlimited
     */
    int getMaxDimensions();
    
    /**
     * Returns the maximum refinement level supported for the given number of dimensions.
     * 
     * Higher dimensions typically support fewer refinement levels due to the
     * exponential growth in the number of possible coordinates.
     * 
     * @param dimensions Number of spatial dimensions
     * @return Maximum supported refinement level for the given dimensions
     * @throws IllegalArgumentException if dimensions exceeds getMaxDimensions()
     */
    byte getMaxLevel(int dimensions);
    
    /**
     * Returns the theoretical maximum linear index for the given parameters.
     * 
     * This provides the upper bound for linear indices that can be generated
     * with the specified dimensions and refinement level.
     * 
     * @param dimensions Number of spatial dimensions
     * @param maxLevel Maximum refinement level
     * @return Maximum possible linear index
     * @throws IllegalArgumentException if parameters exceed supported limits
     */
    long getMaxLinearIndex(int dimensions, byte maxLevel);
    
    /**
     * Returns a human-readable name for this linearization strategy.
     * 
     * @return Name of the linearization algorithm (e.g., "Morton Order", "Hilbert Curve")
     */
    String getName();
    
    /**
     * Returns detailed information about this linearization implementation.
     * 
     * This includes algorithm details, performance characteristics, and
     * any implementation-specific notes.
     * 
     * @return Detailed description of the linearization
     */
    String getDescription();
    
    /**
     * Validates that the given parameters are supported by this linearization.
     * 
     * This method checks if the combination of dimensions and refinement level
     * is valid for this implementation without actually performing linearization.
     * 
     * @param dimensions Number of spatial dimensions
     * @param maxLevel Maximum refinement level
     * @return true if the parameters are supported, false otherwise
     */
    boolean isSupported(int dimensions, byte maxLevel);
    
    /**
     * Estimates the memory usage for linearization operations with given parameters.
     * 
     * This helps with capacity planning and performance optimization by providing
     * estimates of the memory overhead for different configuration options.
     * 
     * @param dimensions Number of spatial dimensions
     * @param maxLevel Maximum refinement level
     * @return Estimated memory usage in bytes, or -1 if cannot be estimated
     */
    long estimateMemoryUsage(int dimensions, byte maxLevel);
}
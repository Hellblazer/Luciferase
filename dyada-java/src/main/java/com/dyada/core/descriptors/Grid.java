package com.dyada.core.descriptors;

import com.dyada.core.bitarray.BitArray;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a spatial grid with active/inactive cells.
 * Used for adaptive mesh refinement and spatial discretization.
 */
public record Grid(int[] cellCounts, BitArray activeCells) {
    
    public Grid {
        Objects.requireNonNull(cellCounts, "cellCounts cannot be null");
        Objects.requireNonNull(activeCells, "activeCells cannot be null");
        
        if (cellCounts.length == 0) {
            throw new IllegalArgumentException("cellCounts must have at least one dimension");
        }
        
        for (int count : cellCounts) {
            if (count <= 0) {
                throw new IllegalArgumentException("all cell counts must be positive");
            }
        }
        
        long totalCells = 1;
        for (int count : cellCounts) {
            totalCells *= count;
            if (totalCells > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("total cell count exceeds maximum supported size");
            }
        }
        
        if (activeCells.size() != (int) totalCells) {
            throw new IllegalArgumentException(
                String.format("activeCells size (%d) must match total cell count (%d)", 
                             activeCells.size(), totalCells));
        }
        
        // Defensive copy
        cellCounts = cellCounts.clone();
    }
    
    /**
     * Returns the number of dimensions
     */
    public int dimensions() {
        return cellCounts.length;
    }
    
    /**
     * Returns the total number of cells
     */
    public int totalCells() {
        int total = 1;
        for (int count : cellCounts) {
            total *= count;
        }
        return total;
    }
    
    /**
     * Returns the number of active cells
     */
    public long activeCellCount() {
        return activeCells.count();
    }
    
    /**
     * Converts multi-dimensional cell indices to linear index
     */
    public int cellIndex(int[] indices) {
        Objects.requireNonNull(indices, "indices cannot be null");
        
        if (indices.length != dimensions()) {
            throw new IllegalArgumentException(
                String.format("indices length (%d) must match dimensions (%d)", 
                             indices.length, dimensions()));
        }
        
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] < 0 || indices[i] >= cellCounts[i]) {
                throw new IndexOutOfBoundsException(
                    String.format("index %d out of bounds [0, %d) in dimension %d", 
                                 indices[i], cellCounts[i], i));
            }
        }
        
        int linearIndex = 0;
        int stride = 1;
        
        for (int i = dimensions() - 1; i >= 0; i--) {
            linearIndex += indices[i] * stride;
            stride *= cellCounts[i];
        }
        
        return linearIndex;
    }
    
    /**
     * Converts linear index to multi-dimensional cell indices
     */
    public int[] cellIndices(int linearIndex) {
        if (linearIndex < 0 || linearIndex >= totalCells()) {
            throw new IndexOutOfBoundsException(
                String.format("linear index %d out of bounds [0, %d)", 
                             linearIndex, totalCells()));
        }
        
        var indices = new int[dimensions()];
        int remaining = linearIndex;
        
        for (int i = dimensions() - 1; i >= 0; i--) {
            indices[i] = remaining % cellCounts[i];
            remaining /= cellCounts[i];
        }
        
        return indices;
    }
    
    /**
     * Checks if a cell is active
     */
    public boolean isCellActive(int[] indices) {
        int linearIndex = cellIndex(indices);
        return activeCells.get(linearIndex);
    }
    
    /**
     * Checks if a cell is active by linear index
     */
    public boolean isCellActive(int linearIndex) {
        if (linearIndex < 0 || linearIndex >= totalCells()) {
            throw new IndexOutOfBoundsException(
                String.format("linear index %d out of bounds [0, %d)", 
                             linearIndex, totalCells()));
        }
        return activeCells.get(linearIndex);
    }
    
    /**
     * Creates a new grid with the specified cell activated
     */
    public Grid withActiveCell(int[] indices) {
        int linearIndex = cellIndex(indices);
        var newActiveCells = activeCells.set(linearIndex, true);
        return new Grid(cellCounts.clone(), newActiveCells);
    }
    
    /**
     * Creates a new grid with the specified cell deactivated
     */
    public Grid withInactiveCell(int[] indices) {
        int linearIndex = cellIndex(indices);
        var newActiveCells = activeCells.set(linearIndex, false);
        return new Grid(cellCounts.clone(), newActiveCells);
    }
    
    /**
     * Creates a refined grid with double resolution in each dimension
     */
    public Grid refine() {
        var newCellCounts = new int[dimensions()];
        for (int i = 0; i < dimensions(); i++) {
            newCellCounts[i] = cellCounts[i] * 2;
        }
        
        int newTotalCells = 1;
        for (int count : newCellCounts) {
            newTotalCells *= count;
        }
        
        var newActiveCells = BitArray.of(newTotalCells);
        
        // Map active cells to refined grid (each cell becomes 2^d cells)
        int childrenPerCell = 1 << dimensions();
        
        for (int oldIndex = 0; oldIndex < totalCells(); oldIndex++) {
            if (activeCells.get(oldIndex)) {
                var oldIndices = cellIndices(oldIndex);
                
                // Generate all child cells
                for (int child = 0; child < childrenPerCell; child++) {
                    var newIndices = new int[dimensions()];
                    for (int dim = 0; dim < dimensions(); dim++) {
                        int childOffset = (child >> dim) & 1;
                        newIndices[dim] = oldIndices[dim] * 2 + childOffset;
                    }
                    
                    int newIndex = 0;
                    int stride = 1;
                    for (int i = dimensions() - 1; i >= 0; i--) {
                        newIndex += newIndices[i] * stride;
                        stride *= newCellCounts[i];
                    }
                    
                    newActiveCells = newActiveCells.set(newIndex, true);
                }
            }
        }
        
        return new Grid(newCellCounts, newActiveCells);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Grid other)) return false;
        return Arrays.equals(cellCounts, other.cellCounts) && 
               Objects.equals(activeCells, other.activeCells);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(cellCounts), activeCells);
    }
    
    @Override
    public String toString() {
        return String.format("Grid{cellCounts=%s, activeCells=%d/%d}", 
                            Arrays.toString(cellCounts), 
                            activeCellCount(), totalCells());
    }
}
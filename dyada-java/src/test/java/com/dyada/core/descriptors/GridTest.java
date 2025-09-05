package com.dyada.core.descriptors;

import com.dyada.core.bitarray.BitArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Grid functionality.
 */
@DisplayName("Grid Tests")
class GridTest {

    @Test
    @DisplayName("Create 2D grid")
    void create2DGrid() {
        var cellCounts = new int[]{3, 4};
        var activeCells = BitArray.of(12).set(0, true).set(5, true).set(11, true);
        var grid = new Grid(cellCounts, activeCells);
        
        assertEquals(2, grid.dimensions());
        assertEquals(12, grid.totalCells()); // 3 * 4 = 12
        assertEquals(3, grid.activeCellCount());
    }

    @Test
    @DisplayName("Create 3D grid")
    void create3DGrid() {
        var cellCounts = new int[]{2, 3, 4};
        var activeCells = BitArray.of(24);
        var grid = new Grid(cellCounts, activeCells);
        
        assertEquals(3, grid.dimensions());
        assertEquals(24, grid.totalCells()); // 2 * 3 * 4 = 24
        assertEquals(0, grid.activeCellCount());
    }

    @Test
    @DisplayName("Null parameters throw exception")
    void nullParametersThrowException() {
        var cellCounts = new int[]{3, 3};
        var activeCells = BitArray.of(9);
        
        assertThrows(NullPointerException.class,
            () -> new Grid(null, activeCells));
        assertThrows(NullPointerException.class,
            () -> new Grid(cellCounts, null));
    }

    @Test
    @DisplayName("Empty cell counts throw exception")
    void emptyCellCountsThrowException() {
        var activeCells = BitArray.of(0);
        
        assertThrows(IllegalArgumentException.class,
            () -> new Grid(new int[0], activeCells));
    }

    @Test
    @DisplayName("Non-positive cell counts throw exception")
    void nonPositiveCellCountsThrowException() {
        var activeCells = BitArray.of(6);
        
        // Zero cell count
        assertThrows(IllegalArgumentException.class,
            () -> new Grid(new int[]{3, 0}, activeCells));
        
        // Negative cell count
        assertThrows(IllegalArgumentException.class,
            () -> new Grid(new int[]{3, -2}, activeCells));
    }

    @Test
    @DisplayName("Mismatched active cells size throws exception")
    void mismatchedActiveCellsSizeThrowsException() {
        var cellCounts = new int[]{3, 4}; // Total 12 cells
        var activeCells = BitArray.of(10); // Wrong size
        
        assertThrows(IllegalArgumentException.class,
            () -> new Grid(cellCounts, activeCells));
    }

    @Test
    @DisplayName("Cell count overflow throws exception")
    void cellCountOverflowThrowsException() {
        var cellCounts = new int[]{50000, 50000}; // Would overflow int
        var totalCells = 50000L * 50000L; // 2.5 billion > Integer.MAX_VALUE
        
        // Either IllegalArgumentException or NegativeArraySizeException can be thrown
        assertThrows(RuntimeException.class,
            () -> new Grid(cellCounts, BitArray.of((int)Math.min(totalCells, Integer.MAX_VALUE))));
    }

    @Test
    @DisplayName("Convert multi-dimensional indices to linear index")
    void convertMultiDimensionalIndicesToLinearIndex() {
        var grid = new Grid(new int[]{3, 4}, BitArray.of(12));
        
        // Test various positions
        assertEquals(0, grid.cellIndex(new int[]{0, 0}));   // (0,0) -> 0
        assertEquals(1, grid.cellIndex(new int[]{0, 1}));   // (0,1) -> 1
        assertEquals(4, grid.cellIndex(new int[]{1, 0}));   // (1,0) -> 4
        assertEquals(5, grid.cellIndex(new int[]{1, 1}));   // (1,1) -> 5
        assertEquals(11, grid.cellIndex(new int[]{2, 3}));  // (2,3) -> 11
    }

    @Test
    @DisplayName("Convert multi-dimensional indices for 3D grid")
    void convertMultiDimensionalIndicesFor3DGrid() {
        var grid = new Grid(new int[]{2, 3, 4}, BitArray.of(24));
        
        assertEquals(0, grid.cellIndex(new int[]{0, 0, 0}));   // (0,0,0) -> 0
        assertEquals(1, grid.cellIndex(new int[]{0, 0, 1}));   // (0,0,1) -> 1
        assertEquals(4, grid.cellIndex(new int[]{0, 1, 0}));   // (0,1,0) -> 4
        assertEquals(12, grid.cellIndex(new int[]{1, 0, 0}));  // (1,0,0) -> 12
        assertEquals(23, grid.cellIndex(new int[]{1, 2, 3}));  // (1,2,3) -> 23
    }

    @Test
    @DisplayName("Convert linear index to multi-dimensional indices")
    void convertLinearIndexToMultiDimensionalIndices() {
        var grid = new Grid(new int[]{3, 4}, BitArray.of(12));
        
        assertArrayEquals(new int[]{0, 0}, grid.cellIndices(0));
        assertArrayEquals(new int[]{0, 1}, grid.cellIndices(1));
        assertArrayEquals(new int[]{1, 0}, grid.cellIndices(4));
        assertArrayEquals(new int[]{1, 1}, grid.cellIndices(5));
        assertArrayEquals(new int[]{2, 3}, grid.cellIndices(11));
    }

    @Test
    @DisplayName("Convert linear index for 3D grid")
    void convertLinearIndexFor3DGrid() {
        var grid = new Grid(new int[]{2, 3, 4}, BitArray.of(24));
        
        assertArrayEquals(new int[]{0, 0, 0}, grid.cellIndices(0));
        assertArrayEquals(new int[]{0, 0, 1}, grid.cellIndices(1));
        assertArrayEquals(new int[]{0, 1, 0}, grid.cellIndices(4));
        assertArrayEquals(new int[]{1, 0, 0}, grid.cellIndices(12));
        assertArrayEquals(new int[]{1, 2, 3}, grid.cellIndices(23));
    }

    @Test
    @DisplayName("Cell index validation")
    void cellIndexValidation() {
        var grid = new Grid(new int[]{3, 4}, BitArray.of(12));
        
        // Null indices
        assertThrows(NullPointerException.class,
            () -> grid.cellIndex(null));
        
        // Wrong dimension count
        assertThrows(IllegalArgumentException.class,
            () -> grid.cellIndex(new int[]{1, 2, 3})); // 3D indices for 2D grid
        
        // Out of bounds indices
        assertThrows(IndexOutOfBoundsException.class,
            () -> grid.cellIndex(new int[]{-1, 0}));
        assertThrows(IndexOutOfBoundsException.class,
            () -> grid.cellIndex(new int[]{3, 0}));  // 3 >= cellCounts[0]
        assertThrows(IndexOutOfBoundsException.class,
            () -> grid.cellIndex(new int[]{0, 4}));  // 4 >= cellCounts[1]
    }

    @Test
    @DisplayName("Linear index validation")
    void linearIndexValidation() {
        var grid = new Grid(new int[]{3, 4}, BitArray.of(12));
        
        // Valid indices
        assertDoesNotThrow(() -> grid.cellIndices(0));
        assertDoesNotThrow(() -> grid.cellIndices(11));
        
        // Out of bounds
        assertThrows(IndexOutOfBoundsException.class,
            () -> grid.cellIndices(-1));
        assertThrows(IndexOutOfBoundsException.class,
            () -> grid.cellIndices(12));
    }

    @Test
    @DisplayName("Check cell activity by multi-dimensional indices")
    void checkCellActivityByMultiDimensionalIndices() {
        var activeCells = BitArray.of(12).set(0, true).set(5, true);
        var grid = new Grid(new int[]{3, 4}, activeCells);
        
        assertTrue(grid.isCellActive(new int[]{0, 0}));   // Linear index 0
        assertTrue(grid.isCellActive(new int[]{1, 1}));   // Linear index 5
        assertFalse(grid.isCellActive(new int[]{0, 1}));  // Linear index 1
        assertFalse(grid.isCellActive(new int[]{2, 3}));  // Linear index 11
    }

    @Test
    @DisplayName("Check cell activity by linear index")
    void checkCellActivityByLinearIndex() {
        var activeCells = BitArray.of(12).set(0, true).set(5, true);
        var grid = new Grid(new int[]{3, 4}, activeCells);
        
        assertTrue(grid.isCellActive(0));
        assertTrue(grid.isCellActive(5));
        assertFalse(grid.isCellActive(1));
        assertFalse(grid.isCellActive(11));
        
        // Out of bounds
        assertThrows(IndexOutOfBoundsException.class,
            () -> grid.isCellActive(-1));
        assertThrows(IndexOutOfBoundsException.class,
            () -> grid.isCellActive(12));
    }

    @Test
    @DisplayName("Activate cell creates new grid")
    void activateCellCreatesNewGrid() {
        var grid = new Grid(new int[]{3, 4}, BitArray.of(12));
        var newGrid = grid.withActiveCell(new int[]{1, 2}); // Linear index 6
        
        // Original grid unchanged
        assertFalse(grid.isCellActive(6));
        assertEquals(0, grid.activeCellCount());
        
        // New grid has cell activated
        assertTrue(newGrid.isCellActive(6));
        assertEquals(1, newGrid.activeCellCount());
        
        // Other properties same
        assertEquals(grid.dimensions(), newGrid.dimensions());
        assertEquals(grid.totalCells(), newGrid.totalCells());
    }

    @Test
    @DisplayName("Deactivate cell creates new grid")
    void deactivateCellCreatesNewGrid() {
        var activeCells = BitArray.of(12).set(0, true).set(5, true);
        var grid = new Grid(new int[]{3, 4}, activeCells);
        var newGrid = grid.withInactiveCell(new int[]{0, 0}); // Linear index 0
        
        // Original grid unchanged
        assertTrue(grid.isCellActive(0));
        assertEquals(2, grid.activeCellCount());
        
        // New grid has cell deactivated
        assertFalse(newGrid.isCellActive(0));
        assertTrue(newGrid.isCellActive(5)); // Other active cell preserved
        assertEquals(1, newGrid.activeCellCount());
    }

    @Test
    @DisplayName("Grid refinement doubles resolution")
    void gridRefinementDoublesResolution() {
        var activeCells = BitArray.of(4).set(0, true).set(3, true);
        var grid = new Grid(new int[]{2, 2}, activeCells);
        var refined = grid.refine();
        
        // New grid has double resolution
        assertEquals(16, refined.totalCells());
        
        // Active cells should be mapped to refined cells
        // Cell (0,0) -> cells (0,0), (0,1), (1,0), (1,1)
        // Cell (1,1) -> cells (2,2), (2,3), (3,2), (3,3)
        assertTrue(refined.isCellActive(new int[]{0, 0})); // Original (0,0) -> (0,0)
        assertTrue(refined.isCellActive(new int[]{0, 1})); // Original (0,0) -> (0,1)
        assertTrue(refined.isCellActive(new int[]{1, 0})); // Original (0,0) -> (1,0)
        assertTrue(refined.isCellActive(new int[]{1, 1})); // Original (0,0) -> (1,1)
        
        assertTrue(refined.isCellActive(new int[]{2, 2})); // Original (1,1) -> (2,2)
        assertTrue(refined.isCellActive(new int[]{2, 3})); // Original (1,1) -> (2,3)
        assertTrue(refined.isCellActive(new int[]{3, 2})); // Original (1,1) -> (3,2)
        assertTrue(refined.isCellActive(new int[]{3, 3})); // Original (1,1) -> (3,3)
        
        // Other cells should be inactive
        assertFalse(refined.isCellActive(new int[]{0, 2}));
        assertFalse(refined.isCellActive(new int[]{2, 0}));
        
        assertEquals(8, refined.activeCellCount()); // 2 active cells * 4 children each
    }

    @Test
    @DisplayName("3D grid refinement")
    void threeDGridRefinement() {
        var activeCells = BitArray.of(8).set(0, true); // Only (0,0,0) active
        var grid = new Grid(new int[]{2, 2, 2}, activeCells);
        var refined = grid.refine();
        
        // New grid has double resolution  
        assertEquals(64, refined.totalCells());
        
        // Cell (0,0,0) should map to 8 children: (0,0,0) through (1,1,1)
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int k = 0; k < 2; k++) {
                    assertTrue(refined.isCellActive(new int[]{i, j, k}),
                        String.format("Child cell (%d,%d,%d) should be active", i, j, k));
                }
            }
        }
        
        assertEquals(8, refined.activeCellCount()); // 1 active cell * 8 children
    }

    @Test
    @DisplayName("Constructor makes defensive copy of cell counts")
    void constructorMakesDefensiveCopyOfCellCounts() {
        var cellCounts = new int[]{3, 4};
        var grid = new Grid(cellCounts, BitArray.of(12));
        
        // Modify original array
        cellCounts[0] = 99;
        
        // Grid should be unchanged
        assertArrayEquals(new int[]{3, 4}, grid.cellCounts());
        assertEquals(12, grid.totalCells());
    }

    @Test
    @DisplayName("Round trip conversion consistency")
    void roundTripConversionConsistency() {
        var grid = new Grid(new int[]{3, 4, 5}, BitArray.of(60));
        
        // Test all possible linear indices
        for (int linearIndex = 0; linearIndex < 60; linearIndex++) {
            var multiIndices = grid.cellIndices(linearIndex);
            var backToLinear = grid.cellIndex(multiIndices);
            assertEquals(linearIndex, backToLinear,
                String.format("Round trip failed for linear index %d", linearIndex));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("High-dimensional grids")
    void highDimensionalGrids(int dimensions) {
        var cellCounts = new int[dimensions];
        int totalCells = 1;
        for (int i = 0; i < dimensions; i++) {
            cellCounts[i] = 3; // 3 cells per dimension
            totalCells *= 3;
        }
        
        var grid = new Grid(cellCounts, BitArray.of(totalCells));
        
        assertEquals(dimensions, grid.dimensions());
        assertEquals(totalCells, grid.totalCells());
        assertEquals(0, grid.activeCellCount());
        
        // Test corner indices
        var origin = new int[dimensions]; // All zeros
        var corner = new int[dimensions];
        for (int i = 0; i < dimensions; i++) {
            corner[i] = 2; // Maximum index
        }
        
        assertEquals(0, grid.cellIndex(origin));
        assertEquals(totalCells - 1, grid.cellIndex(corner));
    }

    @Test
    @DisplayName("Equality and hashCode")
    void equalityAndHashCode() {
        var activeCells1 = BitArray.of(6).set(0, true).set(2, true);
        var activeCells2 = BitArray.of(6).set(0, true).set(2, true);
        var activeCells3 = BitArray.of(6).set(1, true).set(2, true);
        
        var grid1 = new Grid(new int[]{2, 3}, activeCells1);
        var grid2 = new Grid(new int[]{2, 3}, activeCells2);
        var grid3 = new Grid(new int[]{2, 3}, activeCells3);
        var grid4 = new Grid(new int[]{3, 2}, activeCells1);
        
        // Equal objects
        assertEquals(grid1, grid2);
        assertEquals(grid1.hashCode(), grid2.hashCode());
        
        // Different active cells
        assertNotEquals(grid1, grid3);
        
        // Different cell counts
        assertNotEquals(grid1, grid4);
        
        // Self-equality
        assertEquals(grid1, grid1);
        
        // Null and different type
        assertNotEquals(grid1, null);
        assertNotEquals(grid1, "not a grid");
    }

    @Test
    @DisplayName("toString format")
    void toStringFormat() {
        var activeCells = BitArray.of(6).set(0, true).set(2, true);
        var grid = new Grid(new int[]{2, 3}, activeCells);
        var str = grid.toString();
        
        assertTrue(str.contains("Grid"));
        assertTrue(str.contains("cellCounts"));
        assertTrue(str.contains("activeCells"));
        assertTrue(str.contains("2/6")); // 2 active out of 6 total
    }

    @Test
    @DisplayName("Large grid handling")
    void largeGridHandling() {
        // Test with relatively large but manageable grid
        var cellCounts = new int[]{100, 100};
        var totalCells = 10000;
        var activeCells = BitArray.of(totalCells)
            .set(0, true)
            .set(5050, true) // Middle
            .set(9999, true); // Last
        
        var grid = new Grid(cellCounts, activeCells);
        
        assertEquals(2, grid.dimensions());
        assertEquals(totalCells, grid.totalCells());
        assertEquals(3, grid.activeCellCount());
        
        // Test specific cell lookups
        assertTrue(grid.isCellActive(0));
        assertTrue(grid.isCellActive(5050));
        assertTrue(grid.isCellActive(9999));
        assertFalse(grid.isCellActive(1000));
        
        // Test conversion
        assertArrayEquals(new int[]{0, 0}, grid.cellIndices(0));
        assertArrayEquals(new int[]{50, 50}, grid.cellIndices(5050));
        assertArrayEquals(new int[]{99, 99}, grid.cellIndices(9999));
    }

    @Test
    @DisplayName("Edge cases for refinement")
    void edgeCasesForRefinement() {
        // Single cell grid
        var singleCellGrid = new Grid(new int[]{1}, BitArray.of(true));
        var refinedSingle = singleCellGrid.refine();
        
        assertArrayEquals(new int[]{2}, refinedSingle.cellCounts());
        assertEquals(2, refinedSingle.totalCells());
        assertEquals(2, refinedSingle.activeCellCount()); // Both children active
        
        // No active cells
        var inactiveGrid = new Grid(new int[]{2, 2}, BitArray.of(4));
        var refinedInactive = inactiveGrid.refine();
        
        assertArrayEquals(new int[]{4, 4}, refinedInactive.cellCounts());
        assertEquals(16, refinedInactive.totalCells());
        assertEquals(0, refinedInactive.activeCellCount()); // No active cells
    }
}
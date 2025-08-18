package com.dyada.visualization.data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Visualization data for spatial grids with refinement levels and cell information.
 */
public record GridVisualizationData(
    String id,
    Instant timestamp,
    int dimensions,
    Bounds bounds,
    Map<String, Object> metadata,
    List<GridCell> gridCells,
    Map<String, double[]> cellData,
    GridStructure structure
) implements VisualizationDataType {
    
    public GridVisualizationData {
        if (gridCells == null) {
            gridCells = List.of();
        }
        if (cellData == null) {
            cellData = Map.of();
        }
        if (structure == null) {
            throw new IllegalArgumentException("Grid structure cannot be null");
        }
        
        // Validate grid cells match dimensions
        for (var cell : gridCells) {
            if (cell.coordinates().length != dimensions) {
                throw new IllegalArgumentException(
                    "Grid cell dimension mismatch: expected " + dimensions + 
                    ", got " + cell.coordinates().length
                );
            }
        }
    }
    
    /**
     * Creates GridVisualizationData from base VisualizationData.
     */
    public static GridVisualizationData from(
        VisualizationData base,
        List<GridCell> gridCells,
        GridStructure structure
    ) {
        return new GridVisualizationData(
            base.id(),
            base.timestamp(),
            base.dimensions(),
            base.bounds(),
            base.metadata(),
            gridCells,
            Map.of(),
            structure
        );
    }
    
    /**
     * Adds scalar data associated with grid cells.
     */
    public GridVisualizationData withCellData(String fieldName, double[] values) {
        if (values.length != gridCells.size()) {
            throw new IllegalArgumentException(
                "Cell data length must match grid cell count: " + 
                values.length + " vs " + gridCells.size()
            );
        }
        
        var newCellData = new java.util.HashMap<>(cellData);
        newCellData.put(fieldName, values);
        return new GridVisualizationData(
            id, timestamp, dimensions, bounds, metadata,
            gridCells, newCellData, structure
        );
    }
    
    /**
     * Gets the number of grid cells.
     */
    public int getCellCount() {
        return gridCells.size();
    }
    
    /**
     * Gets the maximum refinement level in the grid.
     */
    public int getMaxRefinementLevel() {
        return gridCells.stream()
            .mapToInt(GridCell::refinementLevel)
            .max()
            .orElse(0);
    }
    
    /**
     * Gets cells at a specific refinement level.
     */
    public List<GridCell> getCellsAtLevel(int level) {
        return gridCells.stream()
            .filter(cell -> cell.refinementLevel() == level)
            .toList();
    }
    
    /**
     * Gets cells within a specified region.
     */
    public List<GridCell> getCellsInRegion(Bounds region) {
        return gridCells.stream()
            .filter(cell -> region.contains(cell.coordinates()))
            .toList();
    }
    
    /**
     * Gets all available cell data field names.
     */
    public java.util.Set<String> getAvailableFields() {
        return cellData.keySet();
    }
    
    @Override
    public VisualizationData asVisualizationData() {
        return new VisualizationData(id, timestamp, dimensions, bounds, metadata);
    }
}
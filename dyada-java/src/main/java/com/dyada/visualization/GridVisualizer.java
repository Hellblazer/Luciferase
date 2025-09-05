package com.dyada.visualization;

import com.dyada.core.descriptors.Grid;
import com.dyada.visualization.data.GridVisualizationData;
import com.dyada.visualization.data.RenderingOptions;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for visualizing spatial grids with adaptive refinement levels.
 * Provides grid structure display, level coloring, and interactive exploration.
 */
public interface GridVisualizer {
    
    /**
     * Visualizes a spatial grid with refinement level information.
     * 
     * @param grid the spatial grid to visualize
     * @param options rendering configuration
     * @return grid visualization data
     */
    GridVisualizationData visualizeGrid(Grid grid, RenderingOptions options);
    
    /**
     * Asynchronously visualizes grid with progress tracking.
     * 
     * @param grid the spatial grid to visualize
     * @param options rendering configuration
     * @return future containing visualization data
     */
    CompletableFuture<GridVisualizationData> visualizeGridAsync(Grid grid, RenderingOptions options);
    
    /**
     * Visualizes grid with scalar field data overlay.
     * 
     * @param grid the spatial grid
     * @param fieldData scalar values mapped to grid cells
     * @param options rendering configuration
     * @return visualization data with field overlay
     */
    GridVisualizationData visualizeWithFieldData(
        Grid grid,
        Map<com.dyada.core.MultiscaleIndex, Double> fieldData,
        RenderingOptions options
    );
    
    /**
     * Creates interactive grid visualization with cell selection capabilities.
     * 
     * @param grid the spatial grid
     * @param options rendering configuration
     * @return interactive visualization data
     */
    GridVisualizationData createInteractiveVisualization(Grid grid, RenderingOptions options);
    
    /**
     * Updates existing grid visualization with new state.
     * 
     * @param existingData current visualization
     * @param grid updated grid state
     * @param options rendering options
     * @return updated visualization data
     */
    GridVisualizationData updateGridVisualization(
        GridVisualizationData existingData,
        Grid grid,
        RenderingOptions options
    );
    
    /**
     * Visualizes grid boundaries and neighbor relationships.
     * 
     * @param grid the spatial grid
     * @param showNeighborConnections whether to show neighbor links
     * @param options rendering configuration
     * @return visualization with boundary information
     */
    GridVisualizationData visualizeBoundaries(
        Grid grid,
        boolean showNeighborConnections,
        RenderingOptions options
    );
    
    /**
     * Gets supported visualization styles (wireframe, solid, etc.).
     * 
     * @return set of supported visualization styles
     */
    java.util.Set<String> getSupportedStyles();
    
    /**
     * Disposes visualization resources.
     */
    void dispose();
}
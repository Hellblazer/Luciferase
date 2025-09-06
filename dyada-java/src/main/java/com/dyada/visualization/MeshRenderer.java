package com.dyada.visualization;

import com.dyada.refinement.AdaptiveMesh;
import com.dyada.visualization.data.MeshVisualizationData;
import com.dyada.visualization.data.RenderingOptions;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for rendering adaptive meshes in 2D and 3D visualizations.
 * Supports real-time mesh updates, refinement level coloring, and 
 * interactive mesh exploration.
 */
public interface MeshRenderer {
    
    /**
     * Renders the current state of an adaptive mesh.
     * 
     * @param mesh the adaptive mesh to render
     * @param options rendering configuration options
     * @return visualization data ready for display
     */
    MeshVisualizationData renderMesh(AdaptiveMesh mesh, RenderingOptions options);
    
    /**
     * Asynchronously renders mesh with progress updates.
     * 
     * @param mesh the adaptive mesh to render
     * @param options rendering configuration options
     * @return future containing the visualization data
     */
    CompletableFuture<MeshVisualizationData> renderMeshAsync(AdaptiveMesh mesh, RenderingOptions options);
    
    /**
     * Updates an existing visualization with mesh changes.
     * More efficient than full re-rendering for incremental updates.
     * 
     * @param existingData current visualization data
     * @param mesh updated mesh state
     * @param options rendering options
     * @return updated visualization data
     */
    MeshVisualizationData updateMeshVisualization(
        MeshVisualizationData existingData, 
        AdaptiveMesh mesh, 
        RenderingOptions options
    );
    
    /**
     * Renders mesh with highlighted refinement regions.
     * 
     * @param mesh the adaptive mesh
     * @param refinementRegions cells marked for refinement
     * @param options rendering options
     * @return visualization data with refinement highlights
     */
    MeshVisualizationData renderWithRefinementHighlights(
        AdaptiveMesh mesh,
        java.util.Set<com.dyada.core.MultiscaleIndex> refinementRegions,
        RenderingOptions options
    );
    
    /**
     * Gets the supported rendering dimensions (2D, 3D, or both).
     * 
     * @return set of supported dimensions
     */
    java.util.Set<Integer> getSupportedDimensions();
    
    /**
     * Checks if this renderer supports real-time updates.
     * 
     * @return true if real-time rendering is supported
     */
    boolean supportsRealTimeUpdates();
    
    /**
     * Disposes of rendering resources and cleanup.
     */
    void dispose();
}
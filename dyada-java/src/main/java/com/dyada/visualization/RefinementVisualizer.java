package com.dyada.visualization;

import com.dyada.refinement.AdaptiveRefinementStrategy.RefinementDecision;
import com.dyada.refinement.AdaptiveRefinementStrategy.RefinementContext;
import com.dyada.visualization.data.RefinementVisualizationData;
import com.dyada.visualization.data.RenderingOptions;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for visualizing refinement decisions, error distributions,
 * and adaptive mesh refinement processes in real-time.
 */
public interface RefinementVisualizer {
    
    /**
     * Visualizes refinement decisions across the mesh.
     * 
     * @param refinementDecisions map of contexts to their refinement decisions
     * @param options rendering configuration
     * @return refinement visualization data
     */
    RefinementVisualizationData visualizeRefinementDecisions(
        Map<RefinementContext, RefinementDecision> refinementDecisions,
        RenderingOptions options
    );
    
    /**
     * Asynchronously visualizes refinement with progress updates.
     * 
     * @param refinementDecisions map of contexts to decisions
     * @param options rendering configuration
     * @return future containing visualization data
     */
    CompletableFuture<RefinementVisualizationData> visualizeRefinementAsync(
        Map<RefinementContext, RefinementDecision> refinementDecisions,
        RenderingOptions options
    );
    
    /**
     * Visualizes error distribution across mesh cells.
     * 
     * @param errorData error values mapped to cell contexts
     * @param options rendering configuration with color mapping
     * @return visualization showing error distribution
     */
    RefinementVisualizationData visualizeErrorDistribution(
        Map<RefinementContext, Double> errorData,
        RenderingOptions options
    );
    
    /**
     * Creates animation showing refinement process over time.
     * 
     * @param refinementSteps sequence of refinement iterations
     * @param options animation and rendering configuration
     * @return animated visualization data
     */
    RefinementVisualizationData createRefinementAnimation(
        java.util.List<Map<RefinementContext, RefinementDecision>> refinementSteps,
        RenderingOptions options
    );
    
    /**
     * Visualizes gradient fields used for gradient-based refinement.
     * 
     * @param gradientData gradient vectors at cell locations
     * @param options rendering configuration for vector fields
     * @return visualization with gradient vectors
     */
    RefinementVisualizationData visualizeGradientField(
        Map<RefinementContext, double[]> gradientData,
        RenderingOptions options
    );
    
    /**
     * Highlights cells marked for specific refinement actions.
     * 
     * @param refineRegions cells to be refined
     * @param coarsenRegions cells to be coarsened
     * @param options rendering configuration
     * @return visualization with highlighted regions
     */
    RefinementVisualizationData highlightRefinementRegions(
        Set<RefinementContext> refineRegions,
        Set<RefinementContext> coarsenRegions,
        RenderingOptions options
    );
    
    /**
     * Creates comparative visualization showing before/after refinement.
     * 
     * @param beforeState mesh state before refinement
     * @param afterState mesh state after refinement
     * @param options rendering configuration
     * @return side-by-side comparison visualization
     */
    RefinementVisualizationData createBeforeAfterComparison(
        com.dyada.refinement.AdaptiveMesh beforeState,
        com.dyada.refinement.AdaptiveMesh afterState,
        RenderingOptions options
    );
    
    /**
     * Gets supported visualization modes (heatmap, vectors, etc.).
     * 
     * @return set of supported visualization modes
     */
    Set<String> getSupportedVisualizationModes();
    
    /**
     * Checks if real-time refinement visualization is supported.
     * 
     * @return true if real-time updates are supported
     */
    boolean supportsRealTimeVisualization();
    
    /**
     * Disposes visualization resources.
     */
    void dispose();
}
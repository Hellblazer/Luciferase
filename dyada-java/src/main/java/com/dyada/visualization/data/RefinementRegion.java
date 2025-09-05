package com.dyada.visualization.data;

import com.dyada.refinement.AdaptiveRefinementStrategy.RefinementContext;
import com.dyada.refinement.AdaptiveRefinementStrategy.RefinementDecision;

import java.util.Set;

/**
 * Represents a region of cells with the same refinement decision.
 * Used for highlighting and visualization of refinement patterns.
 */
public record RefinementRegion(
    String id,
    RefinementDecision decision,
    Set<RefinementContext> contexts,
    Bounds regionBounds,
    double averageError,
    String description
) {
    
    public RefinementRegion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Region ID cannot be null or blank");
        }
        if (decision == null) {
            throw new IllegalArgumentException("Refinement decision cannot be null");
        }
        if (contexts == null) {
            contexts = Set.of();
        }
        if (regionBounds == null) {
            throw new IllegalArgumentException("Region bounds cannot be null");
        }
        if (averageError < 0) {
            throw new IllegalArgumentException("Average error cannot be negative");
        }
        if (description == null) {
            description = "";
        }
    }
    
    /**
     * Creates a refinement region for cells to be refined.
     */
    public static RefinementRegion forRefinement(
        String id,
        Set<RefinementContext> contexts,
        Bounds bounds,
        double avgError
    ) {
        return new RefinementRegion(
            id,
            RefinementDecision.REFINE,
            contexts,
            bounds,
            avgError,
            "Cells marked for refinement"
        );
    }
    
    /**
     * Creates a coarsening region for cells to be coarsened.
     */
    public static RefinementRegion forCoarsening(
        String id,
        Set<RefinementContext> contexts,
        Bounds bounds,
        double avgError
    ) {
        return new RefinementRegion(
            id,
            RefinementDecision.COARSEN,
            contexts,
            bounds,
            avgError,
            "Cells marked for coarsening"
        );
    }
    
    /**
     * Creates a region for cells that remain unchanged.
     */
    public static RefinementRegion forNoChange(
        String id,
        Set<RefinementContext> contexts,
        Bounds bounds,
        double avgError
    ) {
        return new RefinementRegion(
            id,
            RefinementDecision.MAINTAIN,
            contexts,
            bounds,
            avgError,
            "Cells with no refinement change"
        );
    }
    
    /**
     * Gets the number of contexts in this region.
     */
    public int getContextCount() {
        return contexts.size();
    }
    
    /**
     * Checks if this region contains a specific context.
     */
    public boolean contains(RefinementContext context) {
        return contexts.contains(context);
    }
    
    /**
     * Gets a descriptive label for this region.
     */
    public String getLabel() {
        return switch (decision) {
            case REFINE -> "Refinement Region (" + contexts.size() + " cells)";
            case COARSEN -> "Coarsening Region (" + contexts.size() + " cells)";
            case MAINTAIN -> "Stable Region (" + contexts.size() + " cells)";
        };
    }
}
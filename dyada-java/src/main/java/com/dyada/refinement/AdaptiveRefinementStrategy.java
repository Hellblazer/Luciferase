package com.dyada.refinement;

import com.dyada.core.coordinates.Coordinate;
import com.dyada.core.coordinates.LevelIndex;
import com.dyada.core.descriptors.Grid;
import com.dyada.discretization.SpatialDiscretizer;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Strategy interface for adaptive mesh refinement algorithms.
 * Defines how to make refinement decisions based on computational criteria.
 */
public interface AdaptiveRefinementStrategy {
    
    /**
     * Refinement decision for a spatial cell.
     */
    enum RefinementDecision {
        REFINE,     // Cell should be subdivided
        COARSEN,    // Cell should be merged with siblings
        MAINTAIN    // Cell should remain at current level
    }
    
    /**
     * Context information for refinement decisions.
     */
    record RefinementContext(
        LevelIndex cellIndex,
        Coordinate cellCenter,
        double cellSize,
        int currentLevel,
        Object cellData
    ) {}
    
    /**
     * Analyzes a cell and determines the appropriate refinement action.
     * 
     * @param context The spatial context of the cell
     * @param fieldValues Field values stored in the cell
     * @param criteria The refinement criteria to apply
     * @return The refinement decision for this cell
     */
    RefinementDecision analyzeCell(
        RefinementContext context,
        java.util.Map<String, Double> fieldValues,
        RefinementCriteria criteria
    );
    
    /**
     * Returns the maximum refinement level this strategy allows.
     */
    default int getMaxRefinementLevel() {
        return 10; // Default reasonable limit
    }
    
    /**
     * Returns the minimum refinement level this strategy allows.
     */
    default int getMinRefinementLevel() {
        return 0;
    }
    
    /**
     * Validates that refinement decisions are consistent across neighbor cells.
     * This helps prevent hanging nodes and maintains mesh quality.
     * 
     * @param decisions Map of cell indices to their refinement decisions
     * @return True if the decisions are valid, false otherwise
     */
    default boolean validateRefinementDecisions(java.util.Map<LevelIndex, RefinementDecision> decisions) {
        // Default implementation: allow all decisions
        return true;
    }
    
    /**
     * Returns criteria used for refinement decisions.
     * This can be used for logging, debugging, and visualization.
     */
    RefinementCriteria getCriteria();
}
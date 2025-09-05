package com.dyada.refinement;

import com.dyada.core.coordinates.Coordinate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Error-based adaptive refinement strategy.
 * Refines cells where the estimated error exceeds tolerance and coarsens
 * cells where the error is sufficiently small.
 */
public class ErrorBasedRefinement implements AdaptiveRefinementStrategy {
    
    private final RefinementCriteria criteria;
    private final ErrorEstimator errorEstimator;
    
    /**
     * Interface for estimating computational error in a spatial cell.
     */
    @FunctionalInterface
    public interface ErrorEstimator {
        /**
         * Estimates the error in a spatial cell.
         * 
         * @param context The spatial context of the cell
         * @param dataFunction Function to retrieve data at any spatial location
         * @param neighbors List of neighboring cells for interpolation
         * @return Estimated error magnitude (non-negative)
         */
        double estimateError(
            RefinementContext context,
            Function<Coordinate, Object> dataFunction,
            List<RefinementContext> neighbors
        );
    }
    
    /**
     * Creates an error-based refinement strategy.
     * 
     * @param errorTolerance Maximum acceptable error
     * @param refinementThreshold Error threshold above which cells are refined
     * @param coarseningThreshold Error threshold below which cells can be coarsened
     * @param errorEstimator Function to estimate error in cells
     */
    public ErrorBasedRefinement(
        double errorTolerance,
        double refinementThreshold, 
        double coarseningThreshold,
        ErrorEstimator errorEstimator
    ) {
        this.criteria = RefinementCriteria.errorBased(errorTolerance, refinementThreshold, coarseningThreshold);
        this.errorEstimator = errorEstimator;
    }
    
    /**
     * Creates an error-based refinement strategy with default error estimator.
     */
    public ErrorBasedRefinement(double errorTolerance, double refinementThreshold, double coarseningThreshold) {
        this(errorTolerance, refinementThreshold, coarseningThreshold, new DefaultErrorEstimator());
    }
    
    /**
     * Creates an error-based refinement strategy with default parameters.
     * Uses sensible defaults for testing and quick setup.
     */
    public ErrorBasedRefinement() {
        this(0.01, 0.1, 0.05, new DefaultErrorEstimator());
    }
    
    @Override
    public RefinementDecision analyzeCell(
        RefinementContext context,
        Map<String, Double> fieldValues,
        RefinementCriteria criteria
    ) {
        // Use explicit "error" field if provided, otherwise estimate error
        double estimatedError;
        if (fieldValues.containsKey("error")) {
            estimatedError = fieldValues.get("error");
        } else if (fieldValues.isEmpty()) {
            // Empty field values should maintain current state
            return RefinementDecision.MAINTAIN;
        } else {
            estimatedError = calculateErrorFromFieldValues(fieldValues, context);
        }
        
        // Check level constraints using the passed criteria
        int maxLevel = criteria.maxRefinementLevel().orElse(10);
        int minLevel = criteria.minRefinementLevel().orElse(0);
        
        if (context.currentLevel() >= maxLevel) {
            return RefinementDecision.MAINTAIN;
        }
        
        if (context.currentLevel() <= minLevel) {
            // Cannot coarsen below minimum level
            if (estimatedError >= criteria.refinementThreshold()) {
                return RefinementDecision.REFINE;
            }
            return RefinementDecision.MAINTAIN;
        }
        
        // Make refinement decision based on error
        if (estimatedError >= criteria.refinementThreshold()) {
            return RefinementDecision.REFINE;
        } else if (estimatedError <= criteria.coarseningThreshold()) {
            return RefinementDecision.COARSEN;
        } else {
            return RefinementDecision.MAINTAIN;
        }
    }
    
    /**
     * Calculate error estimate from field values
     */
    private double calculateErrorFromFieldValues(Map<String, Double> fieldValues, RefinementContext context) {
        if (fieldValues.isEmpty()) {
            return 0.0;
        }
        
        // Simple error estimation based on field value magnitudes and variation
        double maxValue = fieldValues.values().stream().mapToDouble(Math::abs).max().orElse(0.0);
        double variance = calculateVariance(fieldValues.values());
        
        // Scale by cell size - smaller cells should have proportionally smaller acceptable errors
        double scalingFactor = Math.pow(context.cellSize(), 2.0);
        
        return (maxValue + Math.sqrt(variance)) * scalingFactor;
    }
    
    /**
     * Calculate variance of field values
     */
    private double calculateVariance(java.util.Collection<Double> values) {
        if (values.size() <= 1) {
            return 0.0;
        }
        
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sumSquaredDiff = values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .sum();
        
        return sumSquaredDiff / (values.size() - 1);
    }
    
    @Override
    public int getMaxRefinementLevel() {
        return criteria.maxRefinementLevel().orElse(10);
    }
    
    @Override
    public int getMinRefinementLevel() {
        return criteria.minRefinementLevel().orElse(0);
    }
    
    @Override
    public boolean validateRefinementDecisions(Map<com.dyada.core.coordinates.LevelIndex, RefinementDecision> decisions) {
        // For error-based refinement, we need to ensure that neighboring cells
        // don't have refinement levels that differ by more than 1 (2:1 constraint)
        
        // This is a simplified validation - in practice, you'd need to check
        // actual neighbor relationships based on the spatial index structure
        for (var entry : decisions.entrySet()) {
            var cellIndex = entry.getKey();
            var decision = entry.getValue();
            
            // Basic validation: ensure we don't violate level constraints
            if (decision == RefinementDecision.REFINE) {
                // Check if we would exceed max level
                int currentLevel = cellIndex.getLevel(0); // Assume uniform level across dimensions
                if (currentLevel >= getMaxRefinementLevel()) {
                    return false;
                }
            } else if (decision == RefinementDecision.COARSEN) {
                // Check if we would go below min level
                int currentLevel = cellIndex.getLevel(0);
                if (currentLevel <= getMinRefinementLevel()) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    @Override
    public RefinementCriteria getCriteria() {
        return criteria;
    }
    
    /**
     * Default error estimator using simple interpolation-based error estimation.
     */
    public static class DefaultErrorEstimator implements ErrorEstimator {
        
        @Override
        public double estimateError(
            RefinementContext context,
            Function<Coordinate, Object> dataFunction,
            List<RefinementContext> neighbors
        ) {
            // Simple error estimation based on local variation
            var cellData = context.cellData();
            
            if (cellData == null) {
                return 0.0; // No data means no error
            }
            
            // Convert cell data to double for numerical analysis
            double cellValue = extractNumericValue(cellData);
            
            if (neighbors.isEmpty()) {
                // No neighbors available - use absolute value as error estimate
                return Math.abs(cellValue);
            }
            
            // Calculate error as the maximum difference from neighbors
            double maxDifference = 0.0;
            int validNeighbors = 0;
            
            for (var neighbor : neighbors) {
                if (neighbor.cellData() != null) {
                    double neighborValue = extractNumericValue(neighbor.cellData());
                    double difference = Math.abs(cellValue - neighborValue);
                    maxDifference = Math.max(maxDifference, difference);
                    validNeighbors++;
                }
            }
            
            if (validNeighbors == 0) {
                return Math.abs(cellValue);
            }
            
            // Scale error by cell size - smaller cells should have proportionally smaller errors
            double scalingFactor = Math.pow(context.cellSize(), 2.0); // Quadratic scaling
            
            return maxDifference * scalingFactor;
        }
        
        /**
         * Extracts numeric value from cell data for error analysis.
         */
        private double extractNumericValue(Object data) {
            if (data instanceof Number number) {
                return number.doubleValue();
            } else if (data instanceof double[] array && array.length > 0) {
                // Use magnitude for vector data
                double sum = 0.0;
                for (double value : array) {
                    sum += value * value;
                }
                return Math.sqrt(sum);
            } else if (data instanceof String str) {
                try {
                    return Double.parseDouble(str);
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }
            
            // Fallback: use hash code as a proxy for "variation"
            return Math.abs(data.hashCode()) % 1000 / 1000.0;
        }
    }
    
    /**
     * Returns the error estimator used by this strategy.
     */
    public ErrorEstimator getErrorEstimator() {
        return errorEstimator;
    }
    
    /**
     * Creates a new error-based refinement strategy with updated criteria.
     */
    public ErrorBasedRefinement withCriteria(RefinementCriteria newCriteria) {
        if (newCriteria.type() != RefinementCriteria.CriteriaType.ERROR_BASED) {
            throw new IllegalArgumentException("Criteria must be error-based");
        }
        
        return new ErrorBasedRefinement(
            newCriteria.errorTolerance().orElse(1e-6),
            newCriteria.refinementThreshold(),
            newCriteria.coarseningThreshold(),
            this.errorEstimator
        );
    }
}
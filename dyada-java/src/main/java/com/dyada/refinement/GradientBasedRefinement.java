package com.dyada.refinement;

import com.dyada.core.coordinates.Coordinate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Gradient-based adaptive refinement strategy.
 * Refines cells where gradients are high (indicating rapid spatial variation)
 * and coarsens cells where gradients are low (indicating smooth variation).
 */
public class GradientBasedRefinement implements AdaptiveRefinementStrategy {
    
    private final RefinementCriteria criteria;
    private final GradientEstimator gradientEstimator;
    
    /**
     * Interface for estimating gradients in a spatial cell.
     */
    @FunctionalInterface
    public interface GradientEstimator {
        /**
         * Estimates the gradient magnitude in a spatial cell.
         * 
         * @param context The spatial context of the cell
         * @param dataFunction Function to retrieve data at any spatial location
         * @param neighbors List of neighboring cells for gradient calculation
         * @return Estimated gradient magnitude (non-negative)
         */
        double estimateGradient(
            RefinementContext context,
            Function<Coordinate, Object> dataFunction,
            List<RefinementContext> neighbors
        );
    }
    
    /**
     * Creates a gradient-based refinement strategy.
     * 
     * @param gradientThreshold Base gradient threshold for analysis
     * @param refinementThreshold Gradient threshold above which cells are refined
     * @param coarseningThreshold Gradient threshold below which cells can be coarsened
     * @param gradientEstimator Function to estimate gradients in cells
     */
    public GradientBasedRefinement(
        double gradientThreshold,
        double refinementThreshold,
        double coarseningThreshold,
        GradientEstimator gradientEstimator
    ) {
        this.criteria = RefinementCriteria.gradientBased(gradientThreshold, refinementThreshold, coarseningThreshold);
        this.gradientEstimator = gradientEstimator;
    }
    
    /**
     * Creates a gradient-based refinement strategy with default gradient estimator.
     */
    public GradientBasedRefinement(double gradientThreshold, double refinementThreshold, double coarseningThreshold) {
        this(gradientThreshold, refinementThreshold, coarseningThreshold, new DefaultGradientEstimator());
    }
    
    @Override
    public RefinementDecision analyzeCell(
        RefinementContext context,
        Map<String, Double> fieldValues,
        RefinementCriteria criteria
    ) {
        // Use explicit "gradient" field if provided, otherwise estimate gradient
        double gradientMagnitude;
        if (fieldValues.containsKey("gradient")) {
            gradientMagnitude = fieldValues.get("gradient");
        } else if (fieldValues.isEmpty()) {
            // Empty field values should maintain current state
            return RefinementDecision.MAINTAIN;
        } else {
            gradientMagnitude = calculateGradientFromFieldValues(fieldValues, context);
        }
        
        // Check level constraints using the passed criteria
        int maxLevel = criteria.maxRefinementLevel().orElse(10);
        int minLevel = criteria.minRefinementLevel().orElse(0);
        
        if (context.currentLevel() >= maxLevel) {
            return RefinementDecision.MAINTAIN;
        }
        
        if (context.currentLevel() <= minLevel) {
            // Cannot coarsen below minimum level
            // Check gradient threshold constraint if present for refinement
            if (criteria.gradientThreshold().isPresent()) {
                double gradientThresholdLimit = criteria.gradientThreshold().get();
                if (gradientMagnitude >= gradientThresholdLimit && gradientMagnitude >= criteria.refinementThreshold()) {
                    return RefinementDecision.REFINE;
                }
            } else {
                if (gradientMagnitude >= criteria.refinementThreshold()) {
                    return RefinementDecision.REFINE;
                }
            }
            return RefinementDecision.MAINTAIN;
        }
        
        
        // Check gradient threshold constraint if present
        if (criteria.gradientThreshold().isPresent()) {
            double gradientThresholdLimit = criteria.gradientThreshold().get();
            // Only consider refinement if gradient exceeds the gradient threshold
            if (gradientMagnitude < gradientThresholdLimit) {
                // Below gradient threshold - check if should coarsen
                if (gradientMagnitude <= criteria.coarseningThreshold()) {
                    return RefinementDecision.COARSEN;
                } else {
                    return RefinementDecision.MAINTAIN;
                }
            }
        }
        
        // Make refinement decision based on gradient
        if (gradientMagnitude >= criteria.refinementThreshold()) {
            return RefinementDecision.REFINE;
        } else if (gradientMagnitude <= criteria.coarseningThreshold()) {
            return RefinementDecision.COARSEN;
        } else {
            return RefinementDecision.MAINTAIN;
        }
    }
    
    /**
     * Calculate gradient estimate from field values variation
     */
    private double calculateGradientFromFieldValues(Map<String, Double> fieldValues, RefinementContext context) {
        if (fieldValues.isEmpty()) {
            return 0.0;
        }
        
        // Calculate spatial gradients by analyzing field value patterns
        var values = fieldValues.values();
        double maxValue = values.stream().mapToDouble(Math::abs).max().orElse(0.0);
        double minValue = values.stream().mapToDouble(Math::abs).min().orElse(0.0);
        double range = maxValue - minValue;
        
        // Estimate gradient magnitude based on value range and cell size
        double cellSize = context.cellSize();
        double gradientMagnitude = range / cellSize;
        
        // Scale by number of field variables (more variables might indicate higher complexity)
        double complexityFactor = Math.log(1 + fieldValues.size()) / Math.log(2);
        
        return gradientMagnitude * complexityFactor;
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
        // For gradient-based refinement, ensure smooth transitions between refinement levels
        // This prevents creating discontinuities in regions of smooth gradients
        
        for (var entry : decisions.entrySet()) {
            var cellIndex = entry.getKey();
            var decision = entry.getValue();
            
            // Basic validation: ensure we don't violate level constraints
            if (decision == RefinementDecision.REFINE) {
                int currentLevel = cellIndex.getLevel(0);
                if (currentLevel >= getMaxRefinementLevel()) {
                    return false;
                }
            } else if (decision == RefinementDecision.COARSEN) {
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
     * Default gradient estimator using finite difference approximation.
     */
    public static class DefaultGradientEstimator implements GradientEstimator {
        
        @Override
        public double estimateGradient(
            RefinementContext context,
            Function<Coordinate, Object> dataFunction,
            List<RefinementContext> neighbors
        ) {
            var cellData = context.cellData();
            
            if (cellData == null || neighbors.isEmpty()) {
                return 0.0; // No data or neighbors means no gradient
            }
            
            double cellValue = extractNumericValue(cellData);
            var cellCenter = context.cellCenter();
            double cellSize = context.cellSize();
            
            // Estimate gradient using central differences where possible
            double[] gradient = estimateGradientVector(cellCenter, cellValue, cellSize, neighbors);
            
            // Return gradient magnitude
            double magnitude = 0.0;
            for (double component : gradient) {
                magnitude += component * component;
            }
            
            return Math.sqrt(magnitude);
        }
        
        /**
         * Estimates gradient vector using neighboring cells.
         */
        private double[] estimateGradientVector(
            Coordinate cellCenter,
            double cellValue,
            double cellSize,
            List<RefinementContext> neighbors
        ) {
            int dimensions = cellCenter.dimensions();
            double[] gradient = new double[dimensions];
            
            // For each dimension, find neighbors in positive and negative directions
            for (int dim = 0; dim < dimensions; dim++) {
                double positiveValue = cellValue;
                double negativeValue = cellValue;
                boolean hasPositive = false;
                boolean hasNegative = false;
                
                // Find closest neighbors in each direction
                for (var neighbor : neighbors) {
                    if (neighbor.cellData() == null) continue;
                    
                    var neighborCenter = neighbor.cellCenter();
                    double[] centerCoords = cellCenter.values();
                    double[] neighborCoords = neighborCenter.values();
                    
                    // Check if neighbor is primarily in this dimension
                    boolean isInDimension = true;
                    for (int otherDim = 0; otherDim < dimensions; otherDim++) {
                        if (otherDim != dim) {
                            double diff = Math.abs(neighborCoords[otherDim] - centerCoords[otherDim]);
                            if (diff > cellSize * 1.5) { // Allow some tolerance
                                isInDimension = false;
                                break;
                            }
                        }
                    }
                    
                    if (isInDimension) {
                        double dimDiff = neighborCoords[dim] - centerCoords[dim];
                        double neighborValue = extractNumericValue(neighbor.cellData());
                        
                        if (dimDiff > cellSize * 0.1) { // Positive direction
                            positiveValue = neighborValue;
                            hasPositive = true;
                        } else if (dimDiff < -cellSize * 0.1) { // Negative direction
                            negativeValue = neighborValue;
                            hasNegative = true;
                        }
                    }
                }
                
                // Calculate gradient component using available data
                if (hasPositive && hasNegative) {
                    // Central difference
                    gradient[dim] = (positiveValue - negativeValue) / (2.0 * cellSize);
                } else if (hasPositive) {
                    // Forward difference
                    gradient[dim] = (positiveValue - cellValue) / cellSize;
                } else if (hasNegative) {
                    // Backward difference
                    gradient[dim] = (cellValue - negativeValue) / cellSize;
                } else {
                    // No neighbors in this dimension
                    gradient[dim] = 0.0;
                }
            }
            
            return gradient;
        }
        
        /**
         * Extracts numeric value from cell data for gradient analysis.
         */
        private double extractNumericValue(Object data) {
            if (data instanceof Number number) {
                return number.doubleValue();
            } else if (data instanceof double[] array && array.length > 0) {
                // Use first component for vector data gradient
                return array[0];
            } else if (data instanceof String str) {
                try {
                    return Double.parseDouble(str);
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }
            
            // Fallback: use normalized hash code
            return (Math.abs(data.hashCode()) % 1000) / 1000.0;
        }
    }
    
    /**
     * Curvature-aware gradient estimator that considers second derivatives.
     */
    public static class CurvatureAwareGradientEstimator implements GradientEstimator {
        
        private final DefaultGradientEstimator baseEstimator = new DefaultGradientEstimator();
        private final double curvatureWeight;
        
        public CurvatureAwareGradientEstimator(double curvatureWeight) {
            this.curvatureWeight = curvatureWeight;
        }
        
        @Override
        public double estimateGradient(
            RefinementContext context,
            Function<Coordinate, Object> dataFunction,
            List<RefinementContext> neighbors
        ) {
            double baseGradient = baseEstimator.estimateGradient(context, dataFunction, neighbors);
            double curvature = estimateCurvature(context, neighbors);
            
            // Combine gradient and curvature information
            return baseGradient + curvatureWeight * curvature;
        }
        
        private double estimateCurvature(RefinementContext context, List<RefinementContext> neighbors) {
            if (context.cellData() == null || neighbors.size() < 2) {
                return 0.0;
            }
            
            double cellValue = baseEstimator.extractNumericValue(context.cellData());
            double curvatureSum = 0.0;
            int validPairs = 0;
            
            // Estimate curvature using second differences
            for (int i = 0; i < neighbors.size(); i++) {
                for (int j = i + 1; j < neighbors.size(); j++) {
                    var neighbor1 = neighbors.get(i);
                    var neighbor2 = neighbors.get(j);
                    
                    if (neighbor1.cellData() != null && neighbor2.cellData() != null) {
                        double value1 = baseEstimator.extractNumericValue(neighbor1.cellData());
                        double value2 = baseEstimator.extractNumericValue(neighbor2.cellData());
                        
                        // Second difference approximation
                        double secondDiff = Math.abs(value1 - 2 * cellValue + value2);
                        curvatureSum += secondDiff;
                        validPairs++;
                    }
                }
            }
            
            return validPairs > 0 ? curvatureSum / validPairs : 0.0;
        }
    }
    
    /**
     * Returns the gradient estimator used by this strategy.
     */
    public GradientEstimator getGradientEstimator() {
        return gradientEstimator;
    }
    
    /**
     * Creates a new gradient-based refinement strategy with updated criteria.
     */
    public GradientBasedRefinement withCriteria(RefinementCriteria newCriteria) {
        if (newCriteria.type() != RefinementCriteria.CriteriaType.GRADIENT_BASED) {
            throw new IllegalArgumentException("Criteria must be gradient-based");
        }
        
        return new GradientBasedRefinement(
            newCriteria.gradientThreshold().orElse(0.1),
            newCriteria.refinementThreshold(),
            newCriteria.coarseningThreshold(),
            this.gradientEstimator
        );
    }
}
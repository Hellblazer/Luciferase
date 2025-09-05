package com.dyada.refinement;

import java.util.Objects;
import java.util.Optional;

/**
 * Defines criteria for adaptive mesh refinement decisions.
 * Contains thresholds and parameters that control when cells should be refined or coarsened.
 */
public record RefinementCriteria(
    double refinementThreshold,
    double coarseningThreshold,
    Optional<Double> gradientThreshold,
    Optional<Double> errorTolerance,
    Optional<Integer> maxRefinementLevel,
    Optional<Integer> minRefinementLevel,
    Optional<Double> minCellSize,
    double adaptivityFactor,
    CriteriaType type,
    String description
) {
    
    /**
     * Type of refinement criteria being used.
     */
    public enum CriteriaType {
        ERROR_BASED("Error-based refinement"),
        GRADIENT_BASED("Gradient-based refinement"),
        CURVATURE_BASED("Curvature-based refinement"),
        FEATURE_BASED("Feature-based refinement"),
        ADAPTIVE_COMBINED("Combined adaptive criteria"),
        CUSTOM("Custom criteria");
        
        private final String description;
        
        CriteriaType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    public RefinementCriteria {
        Objects.requireNonNull(type, "criteria type cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        
        if (refinementThreshold < 0) {
            throw new IllegalArgumentException("refinement threshold must be non-negative");
        }
        
        if (coarseningThreshold < 0) {
            throw new IllegalArgumentException("coarsening threshold must be non-negative");
        }
        
        if (refinementThreshold <= coarseningThreshold) {
            throw new IllegalArgumentException("refinement threshold must be greater than coarsening threshold");
        }
        
        if (adaptivityFactor <= 0) {
            throw new IllegalArgumentException("adaptivity factor must be positive");
        }
        
        // Validate optional parameters
        gradientThreshold.ifPresent(threshold -> {
            if (threshold < 0) {
                throw new IllegalArgumentException("gradient threshold must be non-negative");
            }
        });
        
        errorTolerance.ifPresent(tolerance -> {
            if (tolerance <= 0) {
                throw new IllegalArgumentException("error tolerance must be positive");
            }
        });
        
        maxRefinementLevel.ifPresent(level -> {
            if (level < 0) {
                throw new IllegalArgumentException("max refinement level must be non-negative");
            }
        });
        
        minRefinementLevel.ifPresent(level -> {
            if (level < 0) {
                throw new IllegalArgumentException("min refinement level must be non-negative");
            }
        });
        
        minCellSize.ifPresent(size -> {
            if (size <= 0) {
                throw new IllegalArgumentException("minimum cell size must be positive");
            }
        });
        
        // Check that min <= max if both are present
        if (minRefinementLevel.isPresent() && maxRefinementLevel.isPresent()) {
            if (minRefinementLevel.get() > maxRefinementLevel.get()) {
                throw new IllegalArgumentException("min refinement level cannot exceed max refinement level");
            }
        }
    }
    
    /**
     * Creates error-based refinement criteria.
     */
    public static RefinementCriteria errorBased(double errorTolerance, double refinementThreshold, double coarseningThreshold) {
        return new RefinementCriteria(
            refinementThreshold,
            coarseningThreshold,
            Optional.empty(),
            Optional.of(errorTolerance),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            1.0,
            CriteriaType.ERROR_BASED,
            String.format("Error-based: tolerance=%.3e, refine>%.3e, coarsen<%.3e", 
                         errorTolerance, refinementThreshold, coarseningThreshold)
        );
    }
    
    /**
     * Creates gradient-based refinement criteria.
     */
    public static RefinementCriteria gradientBased(double gradientThreshold, double refinementThreshold, double coarseningThreshold) {
        return new RefinementCriteria(
            refinementThreshold,
            coarseningThreshold,
            Optional.of(gradientThreshold),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            1.0,
            CriteriaType.GRADIENT_BASED,
            String.format("Gradient-based: gradient>%.3e, refine>%.3e, coarsen<%.3e", 
                         gradientThreshold, refinementThreshold, coarseningThreshold)
        );
    }
    
    /**
     * Creates adaptive refinement criteria with specified adaptivity factor.
     */
    public static RefinementCriteria adaptive(double refinementThreshold, double coarseningThreshold, double adaptivityFactor) {
        return new RefinementCriteria(
            refinementThreshold,
            coarseningThreshold,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            adaptivityFactor,
            CriteriaType.ADAPTIVE_COMBINED,
            String.format("Adaptive: refine>%.3e, coarsen<%.3e, factor=%.2f", 
                         refinementThreshold, coarseningThreshold, adaptivityFactor)
        );
    }
    
    /**
     * Creates feature-based refinement criteria.
     */
    public static RefinementCriteria featureBased(double refinementThreshold, double coarseningThreshold, String featureDescription) {
        return new RefinementCriteria(
            refinementThreshold,
            coarseningThreshold,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            1.0,
            CriteriaType.FEATURE_BASED,
            String.format("Feature-based: %s, refine>%.3e, coarsen<%.3e", 
                         featureDescription, refinementThreshold, coarseningThreshold)
        );
    }
    
    /**
     * Creates combined adaptive criteria with multiple thresholds.
     */
    public static RefinementCriteria combined(
        double refinementThreshold, 
        double coarseningThreshold,
        double gradientThreshold,
        double errorTolerance,
        int maxLevel
    ) {
        return new RefinementCriteria(
            refinementThreshold,
            coarseningThreshold,
            Optional.of(gradientThreshold),
            Optional.of(errorTolerance),
            Optional.of(maxLevel),
            Optional.empty(),
            Optional.empty(),
            1.0,
            CriteriaType.ADAPTIVE_COMBINED,
            String.format("Combined: error=%.3e, grad=%.3e, refine>%.3e, coarsen<%.3e, maxLevel=%d", 
                         errorTolerance, gradientThreshold, refinementThreshold, coarseningThreshold, maxLevel)
        );
    }
    
    /**
     * Creates a builder for custom criteria.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates simple refinement criteria with just the basic parameters.
     * This constructor is primarily for test compatibility.
     * 
     * @param refinementThreshold Threshold above which cells are refined
     * @param coarseningThreshold Threshold below which cells are coarsened  
     * @param maxLevel Maximum refinement level
     */
    public static RefinementCriteria simple(double refinementThreshold, double coarseningThreshold, int maxLevel) {
        return new RefinementCriteria(
            refinementThreshold,
            coarseningThreshold,
            Optional.empty(),
            Optional.empty(),
            Optional.of(maxLevel),
            Optional.empty(),
            Optional.empty(),
            1.0,
            CriteriaType.CUSTOM,
            String.format("Simple criteria: refine>%.3f, coarsen<%.3f, maxLevel=%d", 
                         refinementThreshold, coarseningThreshold, maxLevel)
        );
    }
    
    /**
     * Builder for creating custom refinement criteria.
     */
    public static class Builder {
        private double refinementThreshold = 0.1;
        private double coarseningThreshold = 0.01;
        private Optional<Double> gradientThreshold = Optional.empty();
        private Optional<Double> errorTolerance = Optional.empty();
        private Optional<Integer> maxRefinementLevel = Optional.empty();
        private Optional<Integer> minRefinementLevel = Optional.empty();
        private Optional<Double> minCellSize = Optional.empty();
        private double adaptivityFactor = 1.0;
        private CriteriaType type = CriteriaType.CUSTOM;
        private String description = "Custom criteria";
        
        public Builder refinementThreshold(double threshold) {
            this.refinementThreshold = threshold;
            return this;
        }
        
        public Builder coarseningThreshold(double threshold) {
            this.coarseningThreshold = threshold;
            return this;
        }
        
        public Builder gradientThreshold(double threshold) {
            this.gradientThreshold = Optional.of(threshold);
            return this;
        }
        
        public Builder errorTolerance(double tolerance) {
            this.errorTolerance = Optional.of(tolerance);
            return this;
        }
        
        public Builder maxRefinementLevel(int level) {
            this.maxRefinementLevel = Optional.of(level);
            return this;
        }
        
        public Builder minRefinementLevel(int level) {
            this.minRefinementLevel = Optional.of(level);
            return this;
        }
        
        public Builder minCellSize(double size) {
            this.minCellSize = Optional.of(size);
            return this;
        }
        
        public Builder adaptivityFactor(double factor) {
            this.adaptivityFactor = factor;
            return this;
        }
        
        public Builder type(CriteriaType type) {
            this.type = type;
            return this;
        }
        
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        public RefinementCriteria build() {
            return new RefinementCriteria(
                refinementThreshold,
                coarseningThreshold,
                gradientThreshold,
                errorTolerance,
                maxRefinementLevel,
                minRefinementLevel,
                minCellSize,
                adaptivityFactor,
                type,
                description
            );
        }
    }
    
    /**
     * Returns a new criteria with updated refinement threshold.
     */
    public RefinementCriteria withRefinementThreshold(double newThreshold) {
        return new RefinementCriteria(
            newThreshold, coarseningThreshold, gradientThreshold, errorTolerance,
            maxRefinementLevel, minRefinementLevel, minCellSize, adaptivityFactor, type, description
        );
    }
    
    /**
     * Returns a new criteria with updated coarsening threshold.
     */
    public RefinementCriteria withCoarseningThreshold(double newThreshold) {
        return new RefinementCriteria(
            refinementThreshold, newThreshold, gradientThreshold, errorTolerance,
            maxRefinementLevel, minRefinementLevel, minCellSize, adaptivityFactor, type, description
        );
    }
    
    /**
     * Checks if the criteria includes gradient-based analysis.
     */
    public boolean hasGradientCriteria() {
        return gradientThreshold.isPresent();
    }
    
    /**
     * Checks if the criteria includes error-based analysis.
     */
    public boolean hasErrorCriteria() {
        return errorTolerance.isPresent();
    }
    
    /**
     * Checks if refinement level limits are specified.
     */
    public boolean hasLevelLimits() {
        return maxRefinementLevel.isPresent() || minRefinementLevel.isPresent();
    }
    
    /**
     * Returns the minimum distance threshold used for spatial operations.
     * This is a derived value based on the coarsening threshold.
     */
    public double minimumDistance() {
        return coarseningThreshold * 2.0; // Simple heuristic
    }
    
    /**
     * Returns the maximum number of entities per node for spatial subdivision.
     * This is a derived value based on the refinement threshold.
     */
    public int maximumEntitiesPerNode() {
        return Math.max(1, (int) Math.ceil(1.0 / refinementThreshold));
    }
}
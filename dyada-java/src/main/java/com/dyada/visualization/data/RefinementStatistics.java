package com.dyada.visualization.data;

/**
 * Statistics about refinement decisions and mesh adaptation.
 */
public record RefinementStatistics(
    int totalCells,
    int refinedCells,
    int coarsenedCells,
    int unchangedCells,
    double averageError,
    double maxError,
    double minError,
    double errorStandardDeviation,
    int maxRefinementLevel,
    double refinementRatio,
    double adaptationEfficiency
) {
    
    public RefinementStatistics {
        if (totalCells < 0) {
            throw new IllegalArgumentException("Total cells cannot be negative");
        }
        if (refinedCells < 0 || coarsenedCells < 0 || unchangedCells < 0) {
            throw new IllegalArgumentException("Cell counts cannot be negative");
        }
        if (refinedCells + coarsenedCells + unchangedCells != totalCells) {
            throw new IllegalArgumentException("Cell counts must sum to total cells");
        }
        if (maxError < minError) {
            throw new IllegalArgumentException("Max error must be >= min error");
        }
        if (errorStandardDeviation < 0) {
            throw new IllegalArgumentException("Standard deviation cannot be negative");
        }
        if (maxRefinementLevel < 0) {
            throw new IllegalArgumentException("Max refinement level cannot be negative");
        }
        if (refinementRatio < 0 || refinementRatio > 1) {
            throw new IllegalArgumentException("Refinement ratio must be between 0 and 1");
        }
        if (adaptationEfficiency < 0 || adaptationEfficiency > 1) {
            throw new IllegalArgumentException("Adaptation efficiency must be between 0 and 1");
        }
    }
    
    /**
     * Creates empty statistics.
     */
    public static RefinementStatistics empty() {
        return new RefinementStatistics(
            0, 0, 0, 0,
            0.0, 0.0, 0.0, 0.0,
            0, 0.0, 0.0
        );
    }
    
    /**
     * Creates statistics from refinement data.
     */
    public static RefinementStatistics from(
        int total, int refined, int coarsened, int unchanged,
        double avgError, double maxErr, double minErr, double stdDev,
        int maxLevel
    ) {
        double refinementRatio = total > 0 ? (double) refined / total : 0.0;
        double adaptationEfficiency = calculateAdaptationEfficiency(
            refined, coarsened, unchanged, avgError, maxErr
        );
        
        return new RefinementStatistics(
            total, refined, coarsened, unchanged,
            avgError, maxErr, minErr, stdDev,
            maxLevel, refinementRatio, adaptationEfficiency
        );
    }
    
    /**
     * Calculates adaptation efficiency based on refinement patterns and error reduction.
     */
    private static double calculateAdaptationEfficiency(
        int refined, int coarsened, int unchanged,
        double avgError, double maxError
    ) {
        if (refined + coarsened + unchanged == 0) {
            return 0.0;
        }
        
        // Simple efficiency metric: balance between adaptation activity and error reduction
        double adaptationActivity = (double) (refined + coarsened) / (refined + coarsened + unchanged);
        double errorReduction = maxError > 0 ? 1.0 - (avgError / maxError) : 1.0;
        
        return (adaptationActivity + errorReduction) / 2.0;
    }
    
    /**
     * Gets the percentage of cells that were refined.
     */
    public double getRefinementPercentage() {
        return totalCells > 0 ? (refinedCells * 100.0) / totalCells : 0.0;
    }
    
    /**
     * Gets the percentage of cells that were coarsened.
     */
    public double getCoarseningPercentage() {
        return totalCells > 0 ? (coarsenedCells * 100.0) / totalCells : 0.0;
    }
    
    /**
     * Gets the percentage of cells that remained unchanged.
     */
    public double getUnchangedPercentage() {
        return totalCells > 0 ? (unchangedCells * 100.0) / totalCells : 0.0;
    }
    
    /**
     * Checks if significant adaptation occurred.
     */
    public boolean hasSignificantAdaptation() {
        return refinementRatio > 0.05 || getCoarseningPercentage() > 5.0;
    }
    
    /**
     * Gets a summary description of the refinement statistics.
     */
    public String getSummary() {
        return String.format(
            "Refinement Stats: %d total cells, %.1f%% refined, %.1f%% coarsened, " +
            "avg error: %.3f, efficiency: %.2f",
            totalCells,
            getRefinementPercentage(),
            getCoarseningPercentage(),
            averageError,
            adaptationEfficiency
        );
    }
}
package com.dyada.visualization.data;

import com.dyada.refinement.AdaptiveRefinementStrategy.RefinementDecision;
import com.dyada.refinement.AdaptiveRefinementStrategy.RefinementContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Visualization data for refinement decisions, error distributions, and adaptive processes.
 */
public record RefinementVisualizationData(
    String id,
    Instant timestamp,
    int dimensions,
    Bounds bounds,
    Map<String, Object> metadata,
    Map<RefinementContext, RefinementDecision> refinementDecisions,
    Map<RefinementContext, Double> errorValues,
    Map<RefinementContext, double[]> gradientVectors,
    List<RefinementRegion> refinementRegions,
    RefinementStatistics statistics
) implements VisualizationDataType {
    
    public RefinementVisualizationData {
        if (refinementDecisions == null) {
            refinementDecisions = Map.of();
        }
        if (errorValues == null) {
            errorValues = Map.of();
        }
        if (gradientVectors == null) {
            gradientVectors = Map.of();
        }
        if (refinementRegions == null) {
            refinementRegions = List.of();
        }
        if (statistics == null) {
            statistics = RefinementStatistics.empty();
        }
    }
    
    /**
     * Creates RefinementVisualizationData from base VisualizationData.
     */
    public static RefinementVisualizationData from(
        VisualizationData base,
        Map<RefinementContext, RefinementDecision> decisions
    ) {
        return new RefinementVisualizationData(
            base.id(),
            base.timestamp(),
            base.dimensions(),
            base.bounds(),
            base.metadata(),
            decisions,
            Map.of(),
            Map.of(),
            List.of(),
            RefinementStatistics.empty()
        );
    }
    
    /**
     * Adds error distribution data.
     */
    public RefinementVisualizationData withErrorData(Map<RefinementContext, Double> errors) {
        return new RefinementVisualizationData(
            id, timestamp, dimensions, bounds, metadata,
            refinementDecisions, errors, gradientVectors, refinementRegions, statistics
        );
    }
    
    /**
     * Adds gradient field data.
     */
    public RefinementVisualizationData withGradientData(Map<RefinementContext, double[]> gradients) {
        return new RefinementVisualizationData(
            id, timestamp, dimensions, bounds, metadata,
            refinementDecisions, errorValues, gradients, refinementRegions, statistics
        );
    }
    
    /**
     * Adds refinement region highlights.
     */
    public RefinementVisualizationData withRefinementRegions(List<RefinementRegion> regions) {
        return new RefinementVisualizationData(
            id, timestamp, dimensions, bounds, metadata,
            refinementDecisions, errorValues, gradientVectors, regions, statistics
        );
    }
    
    /**
     * Adds refinement statistics.
     */
    public RefinementVisualizationData withStatistics(RefinementStatistics stats) {
        return new RefinementVisualizationData(
            id, timestamp, dimensions, bounds, metadata,
            refinementDecisions, errorValues, gradientVectors, refinementRegions, stats
        );
    }
    
    /**
     * Gets contexts marked for refinement.
     */
    public java.util.Set<RefinementContext> getRefinementContexts() {
        return refinementDecisions.entrySet().stream()
            .filter(entry -> entry.getValue() == RefinementDecision.REFINE)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }
    
    /**
     * Gets contexts marked for coarsening.
     */
    public java.util.Set<RefinementContext> getCoarseningContexts() {
        return refinementDecisions.entrySet().stream()
            .filter(entry -> entry.getValue() == RefinementDecision.COARSEN)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }
    
    /**
     * Gets the maximum error value.
     */
    public double getMaxError() {
        return errorValues.values().stream()
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0.0);
    }
    
    /**
     * Gets the minimum error value.
     */
    public double getMinError() {
        return errorValues.values().stream()
            .mapToDouble(Double::doubleValue)
            .min()
            .orElse(0.0);
    }
    
    @Override
    public VisualizationData asVisualizationData() {
        return new VisualizationData(id, timestamp, dimensions, bounds, metadata);
    }
}
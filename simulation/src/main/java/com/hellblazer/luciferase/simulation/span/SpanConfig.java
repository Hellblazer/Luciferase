package com.hellblazer.luciferase.simulation.span;

/**
 * Configuration for SpatialSpan boundary management.
 * <p>
 * Defines how boundary zones are calculated and maintained between adjacent regions.
 * Boundary zones enable efficient cross-region entity queries for entities near region boundaries.
 * <p>
 * Key Parameters:
 * - spanWidthRatio: Boundary width as fraction of region size (0.0-1.0)
 * - minSpanDistance: Minimum absolute boundary width
 * - maxBoundaryEntities: Maximum entities tracked per boundary zone
 * - boundaryUpdateThreshold: Distance change triggering boundary recalc
 *
 * @author hal.hildebrand
 */
public record SpanConfig(
    float spanWidthRatio,        // Boundary width as fraction of region (default: 0.1 = 10%)
    float minSpanDistance,       // Minimum absolute boundary width (default: 1.0)
    int maxBoundaryEntities,     // Max entities per boundary zone (default: 1000)
    float boundaryUpdateThreshold // Distance threshold for updates (default: 0.1)
) {

    /**
     * Default configuration.
     */
    public static SpanConfig defaults() {
        return new SpanConfig(
            0.1f,   // spanWidthRatio: 10% of region size
            1.0f,   // minSpanDistance: minimum 1.0 units
            1000,   // maxBoundaryEntities
            0.1f    // boundaryUpdateThreshold: 0.1 units
        );
    }

    /**
     * Constructor with validation.
     */
    public SpanConfig {
        if (spanWidthRatio < 0.0f || spanWidthRatio > 1.0f) {
            throw new IllegalArgumentException("spanWidthRatio must be in [0.0, 1.0], got " + spanWidthRatio);
        }
        if (minSpanDistance < 0.0f) {
            throw new IllegalArgumentException("minSpanDistance must be non-negative, got " + minSpanDistance);
        }
        if (maxBoundaryEntities <= 0) {
            throw new IllegalArgumentException("maxBoundaryEntities must be positive, got " + maxBoundaryEntities);
        }
        if (boundaryUpdateThreshold < 0.0f) {
            throw new IllegalArgumentException(
                "boundaryUpdateThreshold must be non-negative, got " + boundaryUpdateThreshold);
        }
    }

    /**
     * Builder: Set span width ratio.
     */
    public SpanConfig withSpanWidthRatio(float ratio) {
        return new SpanConfig(ratio, minSpanDistance, maxBoundaryEntities, boundaryUpdateThreshold);
    }

    /**
     * Builder: Set minimum span distance.
     */
    public SpanConfig withMinSpanDistance(float distance) {
        return new SpanConfig(spanWidthRatio, distance, maxBoundaryEntities, boundaryUpdateThreshold);
    }

    /**
     * Builder: Set maximum boundary entities.
     */
    public SpanConfig withMaxBoundaryEntities(int max) {
        return new SpanConfig(spanWidthRatio, minSpanDistance, max, boundaryUpdateThreshold);
    }

    /**
     * Builder: Set boundary update threshold.
     */
    public SpanConfig withBoundaryUpdateThreshold(float threshold) {
        return new SpanConfig(spanWidthRatio, minSpanDistance, maxBoundaryEntities, threshold);
    }

    /**
     * Calculate actual boundary width for a region size.
     *
     * @param regionSize Size of the region
     * @return Boundary width (max of ratio-based and minimum)
     */
    public float calculateBoundaryWidth(float regionSize) {
        var ratioWidth = regionSize * spanWidthRatio;
        return Math.max(ratioWidth, minSpanDistance);
    }

    @Override
    public String toString() {
        return String.format("SpanConfig{ratio=%.2f, minDist=%.2f, maxEntities=%d, updateThreshold=%.2f}",
                             spanWidthRatio, minSpanDistance, maxBoundaryEntities, boundaryUpdateThreshold);
    }
}

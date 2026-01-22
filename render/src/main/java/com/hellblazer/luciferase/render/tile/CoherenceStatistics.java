package com.hellblazer.luciferase.render.tile;

/**
 * Statistics about coherence scores across all tiles.
 *
 * @param min                  Minimum coherence score
 * @param max                  Maximum coherence score
 * @param average              Average coherence score
 * @param tilesAboveThreshold  Number of tiles above the threshold
 * @param tilesBelowThreshold  Number of tiles below the threshold
 */
public record CoherenceStatistics(double min, double max, double average, int tilesAboveThreshold,
                                  int tilesBelowThreshold) {

    /**
     * Validates the statistics invariants.
     */
    public CoherenceStatistics {
        if (min < 0.0 || min > 1.0 || max < 0.0 || max > 1.0) {
            throw new IllegalArgumentException("Coherence scores must be in [0, 1]");
        }
        if (min > max) {
            throw new IllegalArgumentException("Min coherence cannot exceed max");
        }
        if (average < min || average > max) {
            throw new IllegalArgumentException("Average must be between min and max");
        }
        if (tilesAboveThreshold < 0 || tilesBelowThreshold < 0) {
            throw new IllegalArgumentException("Tile counts must be non-negative");
        }
    }
}

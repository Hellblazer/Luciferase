package com.hellblazer.luciferase.render.tile;

/**
 * Represents a single tile in a tile-based rendering system.
 * Contains tile coordinates, ray indices, and coherence metrics.
 *
 * @param tileX          Tile X coordinate in grid (0-based)
 * @param tileY          Tile Y coordinate in grid (0-based)
 * @param rayStartIndex  Starting index in the global ray array
 * @param rayCount       Number of rays in this tile
 * @param coherenceScore Coherence score for this tile (0.0 to 1.0)
 */
public record Tile(int tileX, int tileY, int rayStartIndex, int rayCount, double coherenceScore) {

    /**
     * Determines if this tile has high coherence based on a threshold.
     *
     * @param threshold Coherence threshold (0.0 to 1.0)
     * @return true if coherenceScore >= threshold
     */
    public boolean isHighCoherence(double threshold) {
        return coherenceScore >= threshold;
    }

    /**
     * Validates the tile invariants.
     */
    public Tile {
        if (tileX < 0 || tileY < 0) {
            throw new IllegalArgumentException("Tile coordinates must be non-negative");
        }
        if (rayStartIndex < 0) {
            throw new IllegalArgumentException("Ray start index must be non-negative");
        }
        if (rayCount <= 0) {
            throw new IllegalArgumentException("Ray count must be positive");
        }
        if (coherenceScore < 0.0 || coherenceScore > 1.0) {
            throw new IllegalArgumentException("Coherence score must be in [0, 1]");
        }
    }
}

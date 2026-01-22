package com.hellblazer.luciferase.render.tile;

/**
 * Tracks coherence scores for tiles in a frame. Used to guide adaptive kernel selection
 * and tile-based rendering strategies.
 */
public class TileCoherenceMap {

    private final TileConfiguration config;
    private final double            defaultCoherence;
    private final double[][]        coherenceScores;
    private final int[][]           sampleCounts;

    /**
     * Creates a new coherence map for the given tile configuration.
     *
     * @param config           Tile configuration
     * @param defaultCoherence Default coherence value for unsampled tiles (0.0 to 1.0)
     */
    public TileCoherenceMap(TileConfiguration config, double defaultCoherence) {
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null");
        }
        if (defaultCoherence < 0.0 || defaultCoherence > 1.0) {
            throw new IllegalArgumentException("Default coherence must be in [0, 1]");
        }

        this.config = config;
        this.defaultCoherence = defaultCoherence;
        this.coherenceScores = new double[config.tilesX()][config.tilesY()];
        this.sampleCounts = new int[config.tilesX()][config.tilesY()];

        // Initialize all tiles to default coherence
        invalidate();
    }

    /**
     * Gets the coherence score for a specific tile.
     *
     * @param tileX Tile X coordinate (0-based)
     * @param tileY Tile Y coordinate (0-based)
     * @return Coherence score in [0, 1]
     */
    public double getTileCoherence(int tileX, int tileY) {
        validateCoordinates(tileX, tileY);
        return coherenceScores[tileX][tileY];
    }

    /**
     * Updates the coherence score for a specific tile.
     * Coherence values are clamped to [0, 1].
     *
     * @param tileX     Tile X coordinate (0-based)
     * @param tileY     Tile Y coordinate (0-based)
     * @param coherence New coherence score
     */
    public void updateTileCoherence(int tileX, int tileY, double coherence) {
        validateCoordinates(tileX, tileY);

        // Clamp to [0, 1]
        double clampedCoherence = Math.max(0.0, Math.min(1.0, coherence));

        coherenceScores[tileX][tileY] = clampedCoherence;
        sampleCounts[tileX][tileY]++;
    }

    /**
     * Resets all tiles to default coherence.
     */
    public void invalidate() {
        for (int x = 0; x < config.tilesX(); x++) {
            for (int y = 0; y < config.tilesY(); y++) {
                coherenceScores[x][y] = defaultCoherence;
                sampleCounts[x][y] = 0;
            }
        }
    }

    /**
     * Resets a single tile to default coherence.
     *
     * @param tileX Tile X coordinate (0-based)
     * @param tileY Tile Y coordinate (0-based)
     */
    public void invalidateTile(int tileX, int tileY) {
        validateCoordinates(tileX, tileY);
        coherenceScores[tileX][tileY] = defaultCoherence;
        sampleCounts[tileX][tileY] = 0;
    }

    /**
     * Computes statistics about coherence scores across all tiles.
     * Uses a fixed threshold of 0.7 for high/low coherence classification.
     *
     * @return CoherenceStatistics summarizing the current state
     */
    public CoherenceStatistics getStatistics() {
        return getStatistics(0.7);
    }

    /**
     * Computes statistics with a custom threshold.
     *
     * @param threshold Threshold for high/low classification
     * @return CoherenceStatistics with tile counts relative to threshold
     */
    private CoherenceStatistics getStatistics(double threshold) {
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        double sum = 0.0;
        int totalTiles = 0;
        int tilesAbove = 0;
        int tilesBelow = 0;

        for (int x = 0; x < config.tilesX(); x++) {
            for (int y = 0; y < config.tilesY(); y++) {
                double coherence = coherenceScores[x][y];
                min = Math.min(min, coherence);
                max = Math.max(max, coherence);
                sum += coherence;
                totalTiles++;

                if (coherence >= threshold) {
                    tilesAbove++;
                } else {
                    tilesBelow++;
                }
            }
        }

        double average = sum / totalTiles;
        return new CoherenceStatistics(min, max, average, tilesAbove, tilesBelow);
    }

    /**
     * Counts tiles with coherence above the given threshold.
     *
     * @param threshold Coherence threshold (0.0 to 1.0)
     * @return Number of tiles with coherence >= threshold
     */
    public int getHighCoherenceTileCount(double threshold) {
        int count = 0;
        for (int x = 0; x < config.tilesX(); x++) {
            for (int y = 0; y < config.tilesY(); y++) {
                if (coherenceScores[x][y] >= threshold) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Gets the average coherence across all tiles.
     *
     * @return Average coherence score
     */
    public double getAverageCoherence() {
        double sum = 0.0;
        int totalTiles = 0;

        for (int x = 0; x < config.tilesX(); x++) {
            for (int y = 0; y < config.tilesY(); y++) {
                sum += coherenceScores[x][y];
                totalTiles++;
            }
        }

        return sum / totalTiles;
    }

    /**
     * Validates tile coordinates are within bounds.
     *
     * @param tileX Tile X coordinate
     * @param tileY Tile Y coordinate
     * @throws IllegalArgumentException if coordinates are out of bounds
     */
    private void validateCoordinates(int tileX, int tileY) {
        if (tileX < 0 || tileX >= config.tilesX() || tileY < 0 || tileY >= config.tilesY()) {
            throw new IllegalArgumentException(
            "Tile coordinates out of bounds: (" + tileX + "," + tileY + ") for " + config.tilesX() + "x"
            + config.tilesY() + " grid");
        }
    }
}

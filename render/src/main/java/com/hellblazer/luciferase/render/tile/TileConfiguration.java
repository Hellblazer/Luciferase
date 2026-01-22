package com.hellblazer.luciferase.render.tile;

/**
 * Configuration for tile-based rendering. Divides a frame into rectangular tiles
 * for adaptive execution strategies.
 *
 * @param tileWidth  Width of each tile in pixels
 * @param tileHeight Height of each tile in pixels
 * @param tilesX     Number of tiles horizontally
 * @param tilesY     Number of tiles vertically
 * @param totalTiles Total number of tiles (tilesX * tilesY)
 */
public record TileConfiguration(int tileWidth, int tileHeight, int tilesX, int tilesY, int totalTiles) {

    /**
     * Creates a tile configuration from frame dimensions and tile size.
     *
     * @param frameWidth  Frame width in pixels
     * @param frameHeight Frame height in pixels
     * @param tileSize    Tile size (width and height) in pixels
     * @return TileConfiguration for the given parameters
     */
    public static TileConfiguration from(int frameWidth, int frameHeight, int tileSize) {
        if (frameWidth <= 0 || frameHeight <= 0 || tileSize <= 0) {
            throw new IllegalArgumentException("Frame dimensions and tile size must be positive");
        }

        // Calculate number of tiles needed (round up to cover entire frame)
        int tilesX = (frameWidth + tileSize - 1) / tileSize;
        int tilesY = (frameHeight + tileSize - 1) / tileSize;
        int totalTiles = tilesX * tilesY;

        return new TileConfiguration(tileSize, tileSize, tilesX, tilesY, totalTiles);
    }

    /**
     * Calculates the number of rays (pixels) in a specific tile, handling edge tiles
     * that may be partially outside the frame bounds.
     *
     * @param tileX       Tile X coordinate (0-based)
     * @param tileY       Tile Y coordinate (0-based)
     * @param frameWidth  Frame width in pixels
     * @param frameHeight Frame height in pixels
     * @return Number of rays in the specified tile
     */
    public int getTileRayCount(int tileX, int tileY, int frameWidth, int frameHeight) {
        if (tileX < 0 || tileX >= tilesX || tileY < 0 || tileY >= tilesY) {
            throw new IllegalArgumentException(
            "Tile coordinates out of bounds: (" + tileX + "," + tileY + ") for " + tilesX + "x" + tilesY + " grid");
        }

        // Calculate pixel bounds of this tile
        int pixelX = tileX * tileWidth;
        int pixelY = tileY * tileHeight;

        // Calculate actual width and height of this tile (may be clipped at edges)
        int actualWidth = Math.min(tileWidth, frameWidth - pixelX);
        int actualHeight = Math.min(tileHeight, frameHeight - pixelY);

        return actualWidth * actualHeight;
    }

    /**
     * Validates the configuration invariants.
     */
    public TileConfiguration {
        if (tileWidth <= 0 || tileHeight <= 0) {
            throw new IllegalArgumentException("Tile dimensions must be positive");
        }
        if (tilesX <= 0 || tilesY <= 0) {
            throw new IllegalArgumentException("Tile counts must be positive");
        }
        if (totalTiles != tilesX * tilesY) {
            throw new IllegalArgumentException("Total tiles must equal tilesX * tilesY");
        }
    }
}

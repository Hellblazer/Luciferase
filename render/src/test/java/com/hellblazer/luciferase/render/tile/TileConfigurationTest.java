package com.hellblazer.luciferase.render.tile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TileConfiguration - tile-based rendering configuration.
 */
class TileConfigurationTest {

    @Test
    void testCreateFromFrameSize() {
        // Standard 800x600 frame with 16x16 tiles
        var config = TileConfiguration.from(800, 600, 16);

        assertEquals(16, config.tileWidth());
        assertEquals(16, config.tileHeight());
        assertEquals(50, config.tilesX(), "800 / 16 = 50 tiles horizontally");
        assertEquals(38, config.tilesY(), "600 / 16 = 37.5, rounded up to 38 tiles vertically");
        assertEquals(1900, config.totalTiles(), "50 * 38 = 1900 total tiles");
    }

    @Test
    void testEdgeTileRayCount() {
        // 800x600 with 16x16 tiles -> 50x38 tiles
        var config = TileConfiguration.from(800, 600, 16);

        // Interior tile: full 16x16 = 256 rays
        int interiorCount = config.getTileRayCount(0, 0, 800, 600);
        assertEquals(256, interiorCount, "Interior tile should have 16*16=256 rays");

        // Edge tile in middle of right column (x=49 is last column, but full height)
        int rightEdgeCount = config.getTileRayCount(49, 10, 800, 600);
        assertEquals(256, rightEdgeCount, "Right edge tile at y=10 should still be full 16x16=256 rays (800 is evenly divisible by 16)");

        // Bottom edge tile (y=37 is last row, 600 % 16 = 8 pixels remaining)
        int bottomEdgeCount = config.getTileRayCount(10, 37, 800, 600);
        assertEquals(128, bottomEdgeCount, "Bottom edge tile should have 16*8=128 rays (8 pixel rows remaining)");

        // Bottom-right corner tile (partial in both dimensions if frame not evenly divisible)
        var config2 = TileConfiguration.from(810, 610, 16);
        int cornerCount = config2.getTileRayCount(50, 38, 810, 610);
        assertEquals(20, cornerCount, "Corner tile: (810%16=10) * (610%16=2) = 20 rays");
    }

    @Test
    void testTileIndices() {
        var config = TileConfiguration.from(800, 600, 16);

        // Verify tile count calculations
        assertEquals(50, config.tilesX());
        assertEquals(38, config.tilesY());

        // Verify bounds
        assertTrue(config.tilesX() > 0);
        assertTrue(config.tilesY() > 0);
        assertTrue(config.totalTiles() == config.tilesX() * config.tilesY());
    }

    @Test
    void testCommonSizes() {
        // 1920x1080 (Full HD) with 32x32 tiles
        var hd = TileConfiguration.from(1920, 1080, 32);
        assertEquals(60, hd.tilesX(), "1920 / 32 = 60");
        assertEquals(34, hd.tilesY(), "1080 / 32 = 33.75, rounded up to 34");
        assertEquals(2040, hd.totalTiles());

        // 1024x768 with 16x16 tiles
        var xga = TileConfiguration.from(1024, 768, 16);
        assertEquals(64, xga.tilesX(), "1024 / 16 = 64");
        assertEquals(48, xga.tilesY(), "768 / 16 = 48");
        assertEquals(3072, xga.totalTiles());

        // Edge cases: very small frame
        var small = TileConfiguration.from(32, 32, 16);
        assertEquals(2, small.tilesX());
        assertEquals(2, small.tilesY());
        assertEquals(4, small.totalTiles());
    }
}

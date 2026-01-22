package com.hellblazer.luciferase.render.tile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TileCoherenceMap - tracks coherence scores for tiles.
 */
class TileCoherenceMapTest {

    private TileConfiguration config;
    private TileCoherenceMap coherenceMap;

    @BeforeEach
    void setUp() {
        // 800x600 with 16x16 tiles = 50x38 tiles
        config = TileConfiguration.from(800, 600, 16);
        coherenceMap = new TileCoherenceMap(config, 0.5);
    }

    @Test
    void testDefaultCoherence() {
        // All tiles should start with default coherence
        for (int y = 0; y < config.tilesY(); y++) {
            for (int x = 0; x < config.tilesX(); x++) {
                assertEquals(0.5, coherenceMap.getTileCoherence(x, y), 1e-6,
                             "Tile (" + x + "," + y + ") should have default coherence");
            }
        }
    }

    @Test
    void testUpdateAndRetrieve() {
        // Update a few tiles
        coherenceMap.updateTileCoherence(0, 0, 0.8);
        coherenceMap.updateTileCoherence(5, 10, 0.3);
        coherenceMap.updateTileCoherence(49, 37, 0.95);

        // Verify updates
        assertEquals(0.8, coherenceMap.getTileCoherence(0, 0), 1e-6);
        assertEquals(0.3, coherenceMap.getTileCoherence(5, 10), 1e-6);
        assertEquals(0.95, coherenceMap.getTileCoherence(49, 37), 1e-6);

        // Verify other tiles still have default
        assertEquals(0.5, coherenceMap.getTileCoherence(1, 1), 1e-6);
        assertEquals(0.5, coherenceMap.getTileCoherence(25, 20), 1e-6);
    }

    @Test
    void testInvalidate() {
        // Set some coherence values
        coherenceMap.updateTileCoherence(10, 10, 0.9);
        coherenceMap.updateTileCoherence(20, 20, 0.7);
        coherenceMap.updateTileCoherence(30, 30, 0.4);

        // Invalidate entire map
        coherenceMap.invalidate();

        // All should be back to default
        assertEquals(0.5, coherenceMap.getTileCoherence(10, 10), 1e-6);
        assertEquals(0.5, coherenceMap.getTileCoherence(20, 20), 1e-6);
        assertEquals(0.5, coherenceMap.getTileCoherence(30, 30), 1e-6);

        // Update again and invalidate single tile
        coherenceMap.updateTileCoherence(15, 15, 0.85);
        coherenceMap.updateTileCoherence(16, 16, 0.75);

        coherenceMap.invalidateTile(15, 15);

        assertEquals(0.5, coherenceMap.getTileCoherence(15, 15), 1e-6, "Should be reset to default");
        assertEquals(0.75, coherenceMap.getTileCoherence(16, 16), 1e-6, "Should remain unchanged");
    }

    @Test
    void testStatistics() {
        // Set varied coherence values
        coherenceMap.updateTileCoherence(0, 0, 0.2);    // Low
        coherenceMap.updateTileCoherence(1, 0, 0.5);    // Medium
        coherenceMap.updateTileCoherence(2, 0, 0.8);    // High
        coherenceMap.updateTileCoherence(3, 0, 0.95);   // Very high
        coherenceMap.updateTileCoherence(4, 0, 0.1);    // Very low

        var stats = coherenceMap.getStatistics();

        // Check min/max
        assertEquals(0.1, stats.min(), 1e-6, "Min should be 0.1");
        assertTrue(stats.max() >= 0.95, "Max should be at least 0.95");

        // Average should be reasonable (most tiles at default 0.5)
        assertTrue(stats.average() >= 0.4 && stats.average() <= 0.6,
                   "Average should be close to default 0.5, got: " + stats.average());

        // With threshold 0.7, should have at least 2 tiles above (0.8, 0.95)
        assertTrue(stats.tilesAboveThreshold() >= 2, "Should have at least 2 tiles above 0.7");
    }

    @Test
    void testHighCoherenceCount() {
        // Set up tiles with known coherence
        coherenceMap.updateTileCoherence(0, 0, 0.85);   // Above 0.7
        coherenceMap.updateTileCoherence(1, 0, 0.75);   // Above 0.7
        coherenceMap.updateTileCoherence(2, 0, 0.95);   // Above 0.7
        coherenceMap.updateTileCoherence(3, 0, 0.65);   // Below 0.7
        coherenceMap.updateTileCoherence(4, 0, 0.3);    // Below 0.7

        int highCount = coherenceMap.getHighCoherenceTileCount(0.7);
        assertEquals(3, highCount, "Should have exactly 3 tiles above 0.7 threshold");

        int veryHighCount = coherenceMap.getHighCoherenceTileCount(0.9);
        assertEquals(1, veryHighCount, "Should have exactly 1 tile above 0.9 threshold");
    }

    @Test
    void testEdgeCases() {
        // Test bounds checking
        assertThrows(IllegalArgumentException.class, () -> coherenceMap.getTileCoherence(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> coherenceMap.getTileCoherence(0, -1));
        assertThrows(IllegalArgumentException.class, () -> coherenceMap.getTileCoherence(config.tilesX(), 0));
        assertThrows(IllegalArgumentException.class, () -> coherenceMap.getTileCoherence(0, config.tilesY()));

        assertThrows(IllegalArgumentException.class, () -> coherenceMap.updateTileCoherence(-1, 0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> coherenceMap.updateTileCoherence(0, config.tilesY(), 0.5));

        // Test coherence value bounds (should clamp to [0, 1])
        coherenceMap.updateTileCoherence(0, 0, -0.5);
        assertEquals(0.0, coherenceMap.getTileCoherence(0, 0), 1e-6, "Should clamp to 0.0");

        coherenceMap.updateTileCoherence(0, 0, 1.5);
        assertEquals(1.0, coherenceMap.getTileCoherence(0, 0), 1e-6, "Should clamp to 1.0");

        // Test average coherence with empty updates
        var freshMap = new TileCoherenceMap(config, 0.7);
        assertEquals(0.7, freshMap.getAverageCoherence(), 1e-6, "Average should equal default when no updates");
    }
}

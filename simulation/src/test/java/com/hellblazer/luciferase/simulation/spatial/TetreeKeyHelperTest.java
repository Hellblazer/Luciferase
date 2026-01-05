package com.hellblazer.luciferase.simulation.spatial;

import com.hellblazer.luciferase.simulation.spatial.*;

import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TetreeKeyHelper - position to TetreeKey conversion for spatial routing.
 * <p>
 * TetreeKeyHelper enables spatial routing via TetreeKeys:
 * - Convert 3D position to tetrahedral spatial key
 * - Used for routing entities to appropriate bubble/node
 * - Supports configurable refinement levels
 *
 * @author hal.hildebrand
 */
class TetreeKeyHelperTest {

    @Test
    void testPositionToKey_Level0() {
        // Level 0 (root) - entire space maps to single root tet
        var key = TetreeKeyHelper.positionToKey(new Point3f(0.5f, 0.5f, 0.5f), (byte) 0);

        assertNotNull(key);
        assertEquals(0, key.getLevel(), "Level 0 should be root");
    }

    @Test
    void testPositionToKey_Level5() {
        var position = new Point3f(0.123f, 0.456f, 0.789f);
        var key = TetreeKeyHelper.positionToKey(position, (byte) 5);

        assertNotNull(key);
        assertEquals(5, key.getLevel());
    }

    @Test
    void testPositionToKey_Level10() {
        var position = new Point3f(0.5f, 0.5f, 0.5f);
        var key = TetreeKeyHelper.positionToKey(position, (byte) 10);

        assertNotNull(key);
        assertEquals(10, key.getLevel());
    }

    @Test
    void testSamePosition_SameLevel_SameKey() {
        var position = new Point3f(0.25f, 0.25f, 0.25f);
        byte level = 5;

        var key1 = TetreeKeyHelper.positionToKey(position, level);
        var key2 = TetreeKeyHelper.positionToKey(position, level);

        assertEquals(key1, key2, "Same position at same level should produce same key");
    }

    @Test
    void testDifferentPositions_DifferentKeys() {
        byte level = 10;  // Higher level for finer granularity

        var key1 = TetreeKeyHelper.positionToKey(new Point3f(0.2f, 0.2f, 0.2f), level);
        var key2 = TetreeKeyHelper.positionToKey(new Point3f(0.8f, 0.8f, 0.8f), level);

        assertNotEquals(key1, key2, "Distant positions should produce different keys");
    }

    @Test
    void testPositionToKey_OriginPoint() {
        var key = TetreeKeyHelper.positionToKey(new Point3f(0, 0, 0), (byte) 5);

        assertNotNull(key);
        assertEquals(5, key.getLevel());
    }

    @Test
    void testPositionToKey_FarCorner() {
        var key = TetreeKeyHelper.positionToKey(new Point3f(1.0f, 1.0f, 1.0f), (byte) 5);

        assertNotNull(key);
        assertEquals(5, key.getLevel());
    }

    @Test
    void testPositionToKey_NegativeCoordinates() {
        // Negative coordinates are outside tetree bounds
        var position = new Point3f(-0.5f, -0.5f, -0.5f);

        // Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class,
                    () -> TetreeKeyHelper.positionToKey(position, (byte) 5),
                    "Negative coordinates should throw exception");
    }

    @Test
    void testPositionToKey_LargeCoordinates() {
        // Coordinates beyond unit cube
        var position = new Point3f(10.0f, 10.0f, 10.0f);

        // May return null or handle based on Tet implementation
        // Test that it doesn't throw
        assertDoesNotThrow(() -> TetreeKeyHelper.positionToKey(position, (byte) 5));
    }

    @Test
    void testPositionToKey_WithPoint3fHelper() {
        // Test the overload that takes (x, y, z) directly
        var key = TetreeKeyHelper.positionToKey(0.5f, 0.5f, 0.5f, (byte) 5);

        assertNotNull(key);
        assertEquals(5, key.getLevel());
    }

    @Test
    void testPositionToKey_CoordinateOverloadsEquivalent() {
        float x = 0.123f, y = 0.456f, z = 0.789f;
        byte level = 5;

        var key1 = TetreeKeyHelper.positionToKey(new Point3f(x, y, z), level);
        var key2 = TetreeKeyHelper.positionToKey(x, y, z, level);

        assertEquals(key1, key2,
                    "Point3f and coordinate overloads should produce same key");
    }

    @Test
    void testSpatialLocalityPreservation() {
        byte level = 10;

        // Nearby positions should have similar (often adjacent) keys
        var key1 = TetreeKeyHelper.positionToKey(new Point3f(0.5f, 0.5f, 0.5f), level);
        var key2 = TetreeKeyHelper.positionToKey(new Point3f(0.50001f, 0.50001f, 0.50001f), level);

        assertNotNull(key1);
        assertNotNull(key2);
        // Keys should be close in SFC ordering (but we can't test this easily without comparing indices)
    }

    @Test
    void testHighLevel_FineGranularity() {
        // Higher level = finer spatial granularity
        byte fineLevel = 10;  // Use level 10 (max compact level)

        var position = new Point3f(0.5f, 0.5f, 0.5f);  // Center of unit cube
        var key = TetreeKeyHelper.positionToKey(position, fineLevel);

        assertNotNull(key, "Position within bounds should produce key");
        assertEquals(fineLevel, key.getLevel());
    }

    @Test
    void testMultiplePositions_Level5() {
        byte level = 5;

        // Test various positions across the unit cube
        var positions = new Point3f[]{
            new Point3f(0.0f, 0.0f, 0.0f),
            new Point3f(0.25f, 0.25f, 0.25f),
            new Point3f(0.5f, 0.5f, 0.5f),
            new Point3f(0.75f, 0.75f, 0.75f),
            new Point3f(1.0f, 1.0f, 1.0f),
        };

        for (var position : positions) {
            var key = TetreeKeyHelper.positionToKey(position, level);
            assertNotNull(key, "Key for position " + position + " should not be null");
            assertEquals(level, key.getLevel());
        }
    }
}

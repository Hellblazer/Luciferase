package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.geometry.MortonCurve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive round-trip tests for MortonCurve encoding/decoding and Constants.toLevel to validate that Morton
 * coordinates correctly encode levels.
 *
 * @author hal.hildebrand
 */
public class MortonCurveRoundTripTest {

    @Test
    @DisplayName("Test calculateMortonIndex round trip")
    void testCalculateMortonIndexRoundTrip() {
        // Test that calculateMortonIndex produces consistent results
        for (byte level = 0; level <= MortonCurve.MAX_REFINEMENT_LEVEL; level++) {
            var length = Constants.lengthAtLevel(level);

            // Test points at grid boundaries
            // Note: At level 0, length = 2097152 which exceeds 21-bit max (2097151)
            // So we need to be careful with test points to avoid overflow
            Point3f[] testPoints;
            if (level == 0) {
                // For level 0, use points that won't overflow when quantized
                testPoints = new Point3f[] { new Point3f(0, 0, 0), new Point3f(1000000, 0, 0), new Point3f(0, 1000000,
                                                                                                           0),
                                             new Point3f(0, 0, 1000000), new Point3f(500000, 500000, 500000) };
            } else {
                // For other levels, use points based on cell length
                // but ensure we don't exceed 21-bit max when quantized
                var maxCoord = (1 << MortonCurve.MAX_REFINEMENT_LEVEL) - 1;
                var maxMultiplier = maxCoord / length;

                testPoints = new Point3f[] { new Point3f(0, 0, 0), new Point3f(length, 0, 0), new Point3f(0, length, 0),
                                             new Point3f(0, 0, length), new Point3f(length, length, length) };

                // Only add larger coordinates if they won't overflow
                if (maxMultiplier >= 2) {
                    testPoints = java.util.Arrays.copyOf(testPoints, 6);
                    testPoints[5] = new Point3f(length * 2, length * 2, length * 2);
                }
                if (maxMultiplier >= 4) {
                    testPoints = java.util.Arrays.copyOf(testPoints, 7);
                    testPoints[6] = new Point3f(length * 3 + 0.1f, length * 2 + 0.1f, length * 4 + 0.1f);
                }
            }

            for (var point : testPoints) {
                var mortonIndex = Constants.calculateMortonIndex(point, level);

                // Decode the Morton index
                var decoded = MortonCurve.decode(mortonIndex);

                // calculateMortonIndex quantizes to grid then multiplies by length
                // So the Morton code represents quantized world coordinates
                var scale = 1 << (Constants.getMaxRefinementLevel() - level);

                // Calculate expected values, handling potential overflow
                // MortonCurve.encode masks to 21 bits, so we need to match that behavior
                var mask = (1 << MortonCurve.MAX_REFINEMENT_LEVEL) - 1;
                var expectedWorldX = (int) (Math.floor(point.x / scale) * scale) & mask;
                var expectedWorldY = (int) (Math.floor(point.y / scale) * scale) & mask;
                var expectedWorldZ = (int) (Math.floor(point.z / scale) * scale) & mask;

                // The decoded coordinates should match the quantized world coordinates
                assertEquals(expectedWorldX, decoded[0],
                             String.format("X mismatch for point (%.1f,%.1f,%.1f) at level %d", point.x, point.y,
                                           point.z, level));
                assertEquals(expectedWorldY, decoded[1],
                             String.format("Y mismatch for point (%.1f,%.1f,%.1f) at level %d", point.x, point.y,
                                           point.z, level));
                assertEquals(expectedWorldZ, decoded[2],
                             String.format("Z mismatch for point (%.1f,%.1f,%.1f) at level %d", point.x, point.y,
                                           point.z, level));
            }
        }
    }

    @Test
    @DisplayName("Test edge cases and boundary conditions")
    void testEdgeCases() {
        // Test maximum coordinates
        var maxCoord = (1 << MortonCurve.MAX_REFINEMENT_LEVEL)
        - 1; // Maximum coordinate for 21-bit precision (2,097,151)

        // Test that coordinates at max value work correctly
        assertDoesNotThrow(() -> {
            var morton = MortonCurve.encode(maxCoord, 0, 0);
            var decoded = MortonCurve.decode(morton);
            assertEquals(maxCoord, decoded[0]);
        });

        // Test that coordinates beyond max wrap around to 0
        var beyondMax = maxCoord + 1; // 2,097,152
        var mortonBeyond = MortonCurve.encode(beyondMax, 0, 0);
        assertEquals(0, mortonBeyond, "Coordinate beyond 21-bit max should produce Morton 0");

        // Test negative coordinates - calculateMortonIndex doesn't validate, it just casts to int
        // which can produce unexpected results
        var negativePoint = new Point3f(-100, 100, 100);
        // This actually doesn't throw, it just produces unexpected results
        var morton = Constants.calculateMortonIndex(negativePoint, (byte) 10);
        assertTrue(morton >= 0, "Morton code should be non-negative even with negative input");

        // Document the overflow behavior at level 0
        // At level 0, length = 2^21 = 2,097,152 which is beyond max coordinate
        var level0 = (byte) 0;
        var level0Length = Constants.lengthAtLevel(level0);
        assertEquals(2097152, level0Length, "Level 0 length should be 2^21");
        assertTrue(level0Length > maxCoord, "Level 0 length exceeds max coordinate");
    }

    @Test
    @DisplayName("Test level determination from Morton codes")
    void testLevelDeterminationLogic() {
        // Test the relationship between coordinate magnitude and determined level
        var testCases = new ArrayList<TestCase>();

        // Origin always produces level 0
        testCases.add(new TestCase(0, 0, 0, 0));

        // Small coordinates produce finest level
        testCases.add(new TestCase(1, 0, 0, MortonCurve.MAX_REFINEMENT_LEVEL));
        testCases.add(new TestCase(0, 1, 0, MortonCurve.MAX_REFINEMENT_LEVEL));
        testCases.add(new TestCase(0, 0, 1, MortonCurve.MAX_REFINEMENT_LEVEL));
        testCases.add(new TestCase(1, 1, 1, MortonCurve.MAX_REFINEMENT_LEVEL));

        // Test the level determination for various coordinate ranges
        // The toLevel method uses bit analysis of the Morton code
        // These expected values are based on actual toLevel behavior
        testCases.add(new TestCase(7, 7, 7, MortonCurve.MAX_REFINEMENT_LEVEL));
        testCases.add(new TestCase(8, 8, 8, 20));
        testCases.add(new TestCase(15, 15, 15, 20));
        testCases.add(new TestCase(16, 16, 16, 19));
        testCases.add(new TestCase(31, 31, 31, 19));
        testCases.add(new TestCase(32, 32, 32, 18));
        testCases.add(new TestCase(63, 63, 63, 18));
        testCases.add(new TestCase(64, 64, 64, 17));

        for (var tc : testCases) {
            var morton = MortonCurve.encode(tc.x, tc.y, tc.z);
            var actualLevel = Constants.toLevel(morton);
            assertEquals(tc.expectedLevel, actualLevel,
                         String.format("Level mismatch for coordinates (%d,%d,%d), morton=%d", tc.x, tc.y, tc.z,
                                       morton));
        }
    }

    @Test
    @DisplayName("Test Morton code uniqueness at each level")
    void testMortonCodeUniqueness() {
        // For a given level, Morton codes should be unique for different grid cells
        for (byte level = 15; level <= 20; level++) {
            var length = Constants.lengthAtLevel(level);
            var mortonCodes = new ArrayList<Long>();

            // Generate Morton codes for a small grid
            for (int x = 0; x < 5 * length; x += length) {
                for (int y = 0; y < 5 * length; y += length) {
                    for (int z = 0; z < 5 * length; z += length) {
                        var point = new Point3f(x, y, z);
                        var morton = Constants.calculateMortonIndex(point, level);

                        // Check uniqueness
                        assertFalse(mortonCodes.contains(morton),
                                    String.format("Duplicate Morton code %d for point (%d,%d,%d) at level %d", morton,
                                                  x, y, z, level));
                        mortonCodes.add(morton);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Test Morton curve encode/decode round trip")
    void testMortonCurveRoundTrip() {
        // Test a range of coordinates
        var testCoords = new int[] { 0, 1, 2, 3, 4, 5, 7, 8, 15, 16, 31, 32, 63, 64, 127, 128, 255, 256, 511, 512, 1023,
                                     1024 };

        for (int x : testCoords) {
            for (int y : testCoords) {
                for (int z : testCoords) {
                    // Skip very large combinations that might overflow
                    if (x > 255 && y > 255 && z > 255) {
                        continue;
                    }

                    // Encode
                    var morton = MortonCurve.encode(x, y, z);

                    // Decode
                    var decoded = MortonCurve.decode(morton);

                    // Verify round trip
                    assertEquals(x, decoded[0], String.format("X mismatch for (%d,%d,%d) morton=%d", x, y, z, morton));
                    assertEquals(y, decoded[1], String.format("Y mismatch for (%d,%d,%d) morton=%d", x, y, z, morton));
                    assertEquals(z, decoded[2], String.format("Z mismatch for (%d,%d,%d) morton=%d", x, y, z, morton));
                }
            }
        }
    }

    @Test
    @DisplayName("Test Spatial.Cube construction from Morton index")
    void testSpatialCubeFromMortonIndex() {
        // Test that Spatial.Cube correctly decodes Morton indices

        // Test Morton 0 - should produce large cube at origin
        var cube0 = new Spatial.Cube(0);
        assertEquals(0, cube0.originX(), "Morton 0 should have origin X = 0");
        assertEquals(0, cube0.originY(), "Morton 0 should have origin Y = 0");
        assertEquals(0, cube0.originZ(), "Morton 0 should have origin Z = 0");
        assertEquals(Constants.lengthAtLevel((byte) 0), cube0.extent(), "Morton 0 should have level 0 extent");

        // Test small Morton codes - should produce small cubes
        var cube7 = new Spatial.Cube(7);
        assertEquals(1, cube7.originX(), "Morton 7 should decode to (1,1,1)");
        assertEquals(1, cube7.originY(), "Morton 7 should decode to (1,1,1)");
        assertEquals(1, cube7.originZ(), "Morton 7 should decode to (1,1,1)");
        assertEquals(1, cube7.extent(), "Morton 7 should have level 21 extent");

        // Test larger Morton code
        var cube56 = new Spatial.Cube(56);
        assertEquals(2, cube56.originX(), "Morton 56 should decode to (2,2,2)");
        assertEquals(2, cube56.originY(), "Morton 56 should decode to (2,2,2)");
        assertEquals(2, cube56.originZ(), "Morton 56 should decode to (2,2,2)");
        assertEquals(1, cube56.extent(), "Morton 56 should have level 21 extent");
    }

    @Test
    @DisplayName("Test spatial locality preservation")
    void testSpatialLocality() {
        // Morton codes should preserve spatial locality - nearby points should have similar codes
        var level = (byte) 15;
        var length = Constants.lengthAtLevel(level);

        // Test adjacent cells
        var origin = new Point3f(100, 100, 100);
        var mortonOrigin = Constants.calculateMortonIndex(origin, level);

        // Adjacent cells
        var adjacent = new Point3f[] { new Point3f(100 + length, 100, 100),  // +X
                                       new Point3f(100, 100 + length, 100),  // +Y
                                       new Point3f(100, 100, 100 + length),  // +Z
                                       new Point3f(100 - length, 100, 100),  // -X
                                       new Point3f(100, 100 - length, 100),  // -Y
                                       new Point3f(100, 100, 100 - length)   // -Z
        };

        for (var adj : adjacent) {
            if (adj.x >= 0 && adj.y >= 0 && adj.z >= 0) {
                var mortonAdj = Constants.calculateMortonIndex(adj, level);
                // Adjacent cells should have different Morton codes
                assertNotEquals(mortonOrigin, mortonAdj,
                                String.format("Adjacent cell at (%.0f,%.0f,%.0f) has same Morton code as origin", adj.x,
                                              adj.y, adj.z));
            }
        }
    }

    @Test
    @DisplayName("Test Constants.toLevel for known Morton codes")
    void testToLevelKnownValues() {
        // Test known Morton codes and their expected levels
        assertEquals(0, Constants.toLevel(0), "Morton 0 should be level 0");

        // Small Morton codes should map to finest level (21)
        assertEquals(MortonCurve.MAX_REFINEMENT_LEVEL, Constants.toLevel(1), "Morton 1 should be level 21");
        assertEquals(MortonCurve.MAX_REFINEMENT_LEVEL, Constants.toLevel(7), "Morton 7 should be level 21");
        assertEquals(MortonCurve.MAX_REFINEMENT_LEVEL, Constants.toLevel(56), "Morton 56 should be level 21");
        assertEquals(MortonCurve.MAX_REFINEMENT_LEVEL, Constants.toLevel(63), "Morton 63 should be level 21");

        // Test some larger values
        var largeMorton = MortonCurve.encode(1000, 1000, 1000);
        var largeLevel = Constants.toLevel(largeMorton);
        assertTrue(largeLevel >= 0 && largeLevel <= MortonCurve.MAX_REFINEMENT_LEVEL, "Level should be in valid range");
    }

    /**
     * Helper class for test cases
     */
    private static class TestCase {
        final int x, y, z;
        final byte expectedLevel;

        TestCase(int x, int y, int z, int expectedLevel) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.expectedLevel = (byte) expectedLevel;
        }
    }
}

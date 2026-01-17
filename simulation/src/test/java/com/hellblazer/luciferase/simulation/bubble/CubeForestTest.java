/*
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.bubble;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests for CubeForest S0-S5 tetrahedral decomposition.
 *
 * @author hal.hildebrand
 */
class CubeForestTest {

    private static final float WORLD_MIN = 0.0f;
    private static final float WORLD_MAX = 200.0f;
    private static final byte MAX_LEVEL = 10;
    private static final long TARGET_FRAME_MS = 16;

    @Test
    void testCreates6S0S5Bubbles() {
        var forest = new CubeForest(WORLD_MIN, WORLD_MAX, MAX_LEVEL, TARGET_FRAME_MS);

        assertEquals(6, forest.getAllBubbles().size(), "Should create exactly 6 S0-S5 bubbles");

        var bubblesByType = forest.getBubblesByType();
        assertEquals(6, bubblesByType.size(), "Should have 6 types mapped");

        // Verify all S0-S5 types present
        for (byte type = 0; type < 6; type++) {
            assertNotNull(bubblesByType.get(type), "Missing type " + type);
        }
    }

    @Test
    void testPointClassificationDeterministic() {
        var forest = new CubeForest(WORLD_MIN, WORLD_MAX, MAX_LEVEL, TARGET_FRAME_MS);

        // Test corner points - should be deterministic
        var origin = new Point3f(0, 0, 0);
        var opposite = new Point3f(200, 200, 200);

        byte typeOrigin = forest.classifyPoint(origin);
        byte typeOpposite = forest.classifyPoint(opposite);

        // Same point should always classify to same type
        assertEquals(typeOrigin, forest.classifyPoint(origin));
        assertEquals(typeOpposite, forest.classifyPoint(opposite));

        // Test center point
        var center = new Point3f(100, 100, 100);
        byte typeCenter = forest.classifyPoint(center);
        assertTrue(typeCenter >= 0 && typeCenter <= 5, "Type should be 0-5");
    }

    @Test
    void testSpatialCoverage() {
        var forest = new CubeForest(WORLD_MIN, WORLD_MAX, MAX_LEVEL, TARGET_FRAME_MS);

        // Sample 1000 random points in cube
        var random = new java.util.Random(42);
        int samples = 1000;
        int[] typeCounts = new int[6];

        for (int i = 0; i < samples; i++) {
            float x = WORLD_MIN + random.nextFloat() * (WORLD_MAX - WORLD_MIN);
            float y = WORLD_MIN + random.nextFloat() * (WORLD_MAX - WORLD_MIN);
            float z = WORLD_MIN + random.nextFloat() * (WORLD_MAX - WORLD_MIN);

            var point = new Point3f(x, y, z);
            byte type = forest.classifyPoint(point);

            assertTrue(type >= 0 && type <= 5, "Invalid type: " + type);
            typeCounts[type]++;
        }

        // All types should have some points (with high probability)
        System.out.println("S0-S5 Type distribution:");
        for (byte type = 0; type < 6; type++) {
            double percentage = (typeCounts[type] * 100.0) / samples;
            System.out.printf("  S%d: %d points (%.1f%%)%n", type, typeCounts[type], percentage);
            assertTrue(typeCounts[type] > 0, "Type " + type + " has zero coverage");
        }

        // Verify reasonable distribution (each type should cover roughly 1/6 of space)
        for (byte type = 0; type < 6; type++) {
            double percentage = (typeCounts[type] * 100.0) / samples;
            assertTrue(percentage > 5.0, "Type " + type + " coverage too low: " + percentage + "%");
            assertTrue(percentage < 30.0, "Type " + type + " coverage too high: " + percentage + "%");
        }
    }

    @Test
    void testGetBubbleForPosition() {
        var forest = new CubeForest(WORLD_MIN, WORLD_MAX, MAX_LEVEL, TARGET_FRAME_MS);

        // Test that getBubbleForPosition returns consistent results
        var testPoints = new Point3f[] {
            new Point3f(50, 50, 50),
            new Point3f(150, 150, 150),
            new Point3f(100, 100, 100),
            new Point3f(25, 75, 125),
            new Point3f(175, 25, 175)
        };

        for (var point : testPoints) {
            byte type = forest.classifyPoint(point);
            var bubble = forest.getBubbleForPosition(point);
            var expectedBubble = forest.getBubble(type);

            assertEquals(expectedBubble.id(), bubble.id(),
                "getBubbleForPosition should return bubble matching classified type");
        }
    }

    @Test
    void testWorldBounds() {
        var forest = new CubeForest(WORLD_MIN, WORLD_MAX, MAX_LEVEL, TARGET_FRAME_MS);

        float[] bounds = forest.getWorldBounds();
        assertEquals(2, bounds.length);
        assertEquals(WORLD_MIN, bounds[0]);
        assertEquals(WORLD_MAX, bounds[1]);
    }

    @Test
    void testClassificationEdgeCases() {
        var forest = new CubeForest(WORLD_MIN, WORLD_MAX, MAX_LEVEL, TARGET_FRAME_MS);

        // Test boundary points
        var boundaryPoints = new Point3f[] {
            new Point3f(0, 0, 0),           // Origin
            new Point3f(200, 200, 200),     // Opposite corner
            new Point3f(0, 100, 100),       // Face centers
            new Point3f(100, 0, 100),
            new Point3f(100, 100, 0),
            new Point3f(200, 100, 100),
            new Point3f(100, 200, 100),
            new Point3f(100, 100, 200)
        };

        for (var point : boundaryPoints) {
            byte type = forest.classifyPoint(point);
            assertTrue(type >= 0 && type <= 5,
                "Boundary point " + point + " classified to invalid type: " + type);
        }

        // Test clamping (points outside cube should clamp to nearest boundary)
        var outsidePoints = new Point3f[] {
            new Point3f(-10, 100, 100),     // Outside X-
            new Point3f(210, 100, 100),     // Outside X+
            new Point3f(100, -10, 100),     // Outside Y-
            new Point3f(100, 210, 100),     // Outside Y+
            new Point3f(100, 100, -10),     // Outside Z-
            new Point3f(100, 100, 210)      // Outside Z+
        };

        for (var point : outsidePoints) {
            byte type = forest.classifyPoint(point);
            assertTrue(type >= 0 && type <= 5,
                "Outside point " + point + " should clamp and classify to valid type, got: " + type);
        }
    }

    @Test
    void testNoOverlappingBubbles() {
        var forest = new CubeForest(WORLD_MIN, WORLD_MAX, MAX_LEVEL, TARGET_FRAME_MS);

        // Sample many points and ensure each maps to exactly one bubble
        var random = new java.util.Random(42);
        int samples = 10000;

        for (int i = 0; i < samples; i++) {
            float x = WORLD_MIN + random.nextFloat() * (WORLD_MAX - WORLD_MIN);
            float y = WORLD_MIN + random.nextFloat() * (WORLD_MAX - WORLD_MIN);
            float z = WORLD_MIN + random.nextFloat() * (WORLD_MAX - WORLD_MIN);

            var point = new Point3f(x, y, z);

            // Get bubble via classification
            var bubble1 = forest.getBubbleForPosition(point);
            byte type = forest.classifyPoint(point);
            var bubble2 = forest.getBubble(type);

            // Should be same bubble
            assertEquals(bubble1.id(), bubble2.id(),
                "Point " + point + " maps to different bubbles via different methods");

            // Count how many bubbles would claim this point
            int claimCount = 0;
            for (byte t = 0; t < 6; t++) {
                if (forest.classifyPoint(point) == t) {
                    claimCount++;
                }
            }

            // Each point should be claimed by exactly one type
            assertEquals(1, claimCount,
                "Point " + point + " claimed by " + claimCount + " types (should be 1)");
        }
    }

    @Test
    void testGetBubbleInvalidType() {
        var forest = new CubeForest(WORLD_MIN, WORLD_MAX, MAX_LEVEL, TARGET_FRAME_MS);

        assertThrows(IllegalArgumentException.class, () -> forest.getBubble((byte) -1));
        assertThrows(IllegalArgumentException.class, () -> forest.getBubble((byte) 6));
        assertThrows(IllegalArgumentException.class, () -> forest.getBubble((byte) 10));
    }
}

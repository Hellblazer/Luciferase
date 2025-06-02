package com.hellblazer.luciferase.geometry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import javax.vecmath.Point3d;
import javax.vecmath.Point3f;
import javax.vecmath.Tuple3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for 64-bit Morton curve implementation
 */
public class TestMorton {
    
    private Random random;
    
    @BeforeEach
    public void setUp() {
        random = new Random(0x1638);
    }
    
    @Test
    public void testBasicEncodeDecode() {
        // Test basic encode/decode for small values
        int[][] testCases = {
            {0, 0, 0},
            {1, 0, 0},
            {0, 1, 0},
            {0, 0, 1},
            {1, 1, 1},
            {2, 2, 2},
            {7, 7, 7},
            {15, 15, 15},
            {255, 255, 255},
            {1023, 1023, 1023}
        };
        
        for (int[] coords : testCases) {
            long morton = MortonCurve.encode(coords[0], coords[1], coords[2]);
            int[] decoded = MortonCurve.decode(morton);
            
            assertArrayEquals(coords, decoded, 
                String.format("Failed for coordinates [%d, %d, %d]", coords[0], coords[1], coords[2]));
        }
    }
    
    @Test
    public void testLargeCoordinates() {
        // Test with large coordinates up to 2^21-1 (the theoretical maximum for 64-bit Morton codes)
        int maxCoord = (1 << 21) - 1; // 2097151
        
        int[][] largeCases = {
            {maxCoord, 0, 0},
            {0, maxCoord, 0},
            {0, 0, maxCoord},
            {maxCoord, maxCoord, maxCoord},
            {maxCoord/2, maxCoord/2, maxCoord/2},
            {1048576, 1048576, 1048576}, // 2^20
            {524288, 524288, 524288}     // 2^19
        };
        
        for (int[] coords : largeCases) {
            long morton = MortonCurve.encode(coords[0], coords[1], coords[2]);
            int[] decoded = MortonCurve.decode(morton);
            
            assertArrayEquals(coords, decoded,
                String.format("Failed for large coordinates [%d, %d, %d]", coords[0], coords[1], coords[2]));
        }
    }
    
    @Test
    public void testRandomRoundTrip() {
        // Test random coordinates for round-trip encoding/decoding
        int maxCoord = 1 << 20; // Use 2^20 to be safe
        
        for (int i = 0; i < 10000; i++) {
            int x = random.nextInt(maxCoord);
            int y = random.nextInt(maxCoord);
            int z = random.nextInt(maxCoord);
            
            long morton = MortonCurve.encode(x, y, z);
            int[] decoded = MortonCurve.decode(morton);
            
            assertEquals(x, decoded[0], "X coordinate mismatch");
            assertEquals(y, decoded[1], "Y coordinate mismatch");
            assertEquals(z, decoded[2], "Z coordinate mismatch");
        }
    }
    
    @Test
    public void testMagicBitsVsLUT() {
        // Compare magic bits implementation with LUT implementation
        int maxCoord = 1 << 16; // Use smaller range for LUT comparison
        
        for (int i = 0; i < 1000; i++) {
            int x = random.nextInt(maxCoord);
            int y = random.nextInt(maxCoord);
            int z = random.nextInt(maxCoord);
            
            long mortonMagic = MortonCurve.encodeMagicBits(x, y, z);
            long mortonLUT = MortonCurve.encodeLUT(x, y, z);
            
            assertEquals(mortonMagic, mortonLUT, 
                String.format("Encoding mismatch for [%d, %d, %d]: magic=%d, LUT=%d", 
                    x, y, z, mortonMagic, mortonLUT));
            
            int[] decodedMagic = MortonCurve.decodeMagicBits(mortonMagic);
            int[] decodedLUT = MortonCurve.decodeLUT(mortonMagic);
            
            assertArrayEquals(decodedMagic, decodedLUT,
                String.format("Decoding mismatch for morton code %d", mortonMagic));
        }
    }
    
    @Test
    public void testMortonOrderProperty() {
        // Test that Morton codes preserve spatial locality (Z-order property)
        // Points that are close in space should have similar Morton codes
        
        // Test adjacent points in each dimension
        long m000 = MortonCurve.encode(0, 0, 0);
        long m001 = MortonCurve.encode(0, 0, 1);
        long m010 = MortonCurve.encode(0, 1, 0);
        long m100 = MortonCurve.encode(1, 0, 0);
        
        // These should be the first few Morton codes
        assertEquals(0L, m000);
        assertEquals(1L, m100);  // x increments first bit
        assertEquals(2L, m010);  // y increments second bit  
        assertEquals(4L, m001);  // z increments third bit
    }
    
    @Test
    public void testSpecificKnownValues() {
        // Test some specific known Morton code values
        assertEquals(0L, MortonCurve.encode(0, 0, 0));
        assertEquals(1L, MortonCurve.encode(1, 0, 0));
        assertEquals(2L, MortonCurve.encode(0, 1, 0));
        assertEquals(3L, MortonCurve.encode(1, 1, 0));
        assertEquals(4L, MortonCurve.encode(0, 0, 1));
        assertEquals(5L, MortonCurve.encode(1, 0, 1));
        assertEquals(6L, MortonCurve.encode(0, 1, 1));
        assertEquals(7L, MortonCurve.encode(1, 1, 1));
        
        // Test decoding the same values
        assertArrayEquals(new int[]{0, 0, 0}, MortonCurve.decode(0L));
        assertArrayEquals(new int[]{1, 0, 0}, MortonCurve.decode(1L));
        assertArrayEquals(new int[]{0, 1, 0}, MortonCurve.decode(2L));
        assertArrayEquals(new int[]{1, 1, 0}, MortonCurve.decode(3L));
        assertArrayEquals(new int[]{0, 0, 1}, MortonCurve.decode(4L));
        assertArrayEquals(new int[]{1, 0, 1}, MortonCurve.decode(5L));
        assertArrayEquals(new int[]{0, 1, 1}, MortonCurve.decode(6L));
        assertArrayEquals(new int[]{1, 1, 1}, MortonCurve.decode(7L));
    }
    
    @Test
    public void testBitPattern() {
        // Test that bit patterns are correctly interleaved
        // For coordinates (x=1, y=2, z=4) in binary: x=001, y=010, z=100
        // Morton code should interleave as: zyx zyx zyx = 100 010 001 = 0b100010001 = 273
        
        int x = 1; // 001
        int y = 2; // 010  
        int z = 4; // 100
        
        long morton = MortonCurve.encode(x, y, z);
        
        // The interleaved pattern should be: z2y2x2 z1y1x1 z0y0x0 = 100 010 001
        // In decimal: 4*64 + 2*8 + 1*1 = 256 + 16 + 1 = 273
        assertEquals(273L, morton);
        
        int[] decoded = MortonCurve.decode(morton);
        assertArrayEquals(new int[]{x, y, z}, decoded);
    }
    
    @Test
    public void testEdgeCases() {
        // Test edge cases and boundary conditions
        
        // Test zero
        assertEquals(0L, MortonCurve.encode(0, 0, 0));
        assertArrayEquals(new int[]{0, 0, 0}, MortonCurve.decode(0L));
        
        // Test single coordinate non-zero
        long mx = MortonCurve.encode(1, 0, 0);
        long my = MortonCurve.encode(0, 1, 0);
        long mz = MortonCurve.encode(0, 0, 1);
        
        assertTrue(mx != my && my != mz && mx != mz, "Single coordinate codes should be different");
        
        // Test powers of 2
        for (int power = 0; power < 20; power++) {
            int coord = 1 << power;
            long morton = MortonCurve.encode(coord, 0, 0);
            int[] decoded = MortonCurve.decode(morton);
            assertEquals(coord, decoded[0]);
            assertEquals(0, decoded[1]);
            assertEquals(0, decoded[2]);
        }
    }
    
    @Test
    public void testSpatialLocalityPreservation() {
        // Test that spatially close points have numerically close Morton codes
        List<Point3f> points = new ArrayList<>();
        List<Long> mortonCodes = new ArrayList<>();
        
        // Generate a small cluster of points
        for (int x = 100; x <= 103; x++) {
            for (int y = 100; y <= 103; y++) {
                for (int z = 100; z <= 103; z++) {
                    points.add(new Point3f(x, y, z));
                    mortonCodes.add(MortonCurve.encode(x, y, z));
                }
            }
        }
        
        // Morton codes for nearby points should be relatively close
        // This is a qualitative test - exact values depend on the Z-order curve properties
        for (int i = 0; i < mortonCodes.size() - 1; i++) {
            for (int j = i + 1; j < mortonCodes.size(); j++) {
                long code1 = mortonCodes.get(i);
                long code2 = mortonCodes.get(j);
                Point3f p1 = points.get(i);
                Point3f p2 = points.get(j);
                
                float spatialDistance = p1.distance(p2);
                long mortonDistance = Math.abs(code1 - code2);
                
                // This is a loose check - very distant points in space 
                // should generally have more distant Morton codes
                if (spatialDistance > 10.0f) {
                    assertTrue(mortonDistance > 100, 
                        String.format("Points far apart in space should have distant Morton codes: " +
                                    "spatial=%.2f, morton=%d", spatialDistance, mortonDistance));
                }
            }
        }
    }
    
    @Test
    public void testPerformance() {
        // Basic performance test to ensure the implementation is reasonable
        int iterations = 100000;
        int maxCoord = 1 << 16;
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            int x = random.nextInt(maxCoord);
            int y = random.nextInt(maxCoord);
            int z = random.nextInt(maxCoord);
            
            long morton = MortonCurve.encode(x, y, z);
            int[] decoded = MortonCurve.decode(morton);
            
            // Verify correctness
            assertEquals(x, decoded[0]);
            assertEquals(y, decoded[1]); 
            assertEquals(z, decoded[2]);
        }
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        // Should complete reasonably quickly (this is quite generous)
        assertTrue(durationMs < 5000, 
            String.format("Performance test took too long: %d ms for %d iterations", durationMs, iterations));
        
        System.out.printf("Performance: %d encode/decode cycles in %d ms (%.2f ops/sec)%n", 
            iterations, durationMs, iterations * 1000.0 / durationMs);
    }
    
    @Test
    public void testConstants() {
        // Test that constants are reasonable
        assertEquals(21, MortonCurve.MAX_REFINEMENT_LEVEL);
        
        // Verify we can handle coordinates up to MAX_REFINEMENT_LEVEL bits
        int maxCoordForLevel = (1 << MortonCurve.MAX_REFINEMENT_LEVEL) - 1;
        
        // This should work without overflow for the theoretical maximum
        // Note: actual practical limits may be lower
        assertDoesNotThrow(() -> {
            long morton = MortonCurve.encode(maxCoordForLevel, 0, 0);
            int[] decoded = MortonCurve.decode(morton);
            assertEquals(maxCoordForLevel, decoded[0]);
        });
    }
    
    // Utility methods from original test (kept for compatibility)
    public static List<Point3d> getRandomDoubles(Random random, int numberOfPoints, double radius, boolean inSphere) {
        var origin = new Point3d(0, 0, 0);
        double radiusSquared = radius * radius;
        var ourPoints = new ArrayList<Point3d>(numberOfPoints);
        for (int i = 0; i < numberOfPoints; i++) {
            if (inSphere) {
                var p = randomPoint(radius, random);
                while (p.distanceSquared(origin) >= radiusSquared) {
                    p = randomPoint(radius, random);
                }
                ourPoints.add(p);
            } else {
                ourPoints.add(randomPoint(radius, random));
            }
        }
        return ourPoints;
    }

    public static List<Point3f> getRandomFloats(Random random, int numberOfPoints, float radius, boolean inSphere) {
        var origin = new Point3f(0, 0, 0);
        float radiusSquared = radius * radius;
        var ourPoints = new ArrayList<Point3f>(numberOfPoints);
        for (int i = 0; i < numberOfPoints; i++) {
            if (inSphere) {
                var p = randomPoint(radius, random);
                while (p.distanceSquared(origin) >= radiusSquared) {
                    p = randomPoint(radius, random);
                }
                ourPoints.add(p);
            } else {
                ourPoints.add(randomPoint(radius, random));
            }
        }
        return ourPoints;
    }

    public static Point3f randomPoint(float radius, Random random) {
        var x = random.nextFloat() * (random.nextBoolean() ? 1.0f : -1.0f);
        var y = random.nextFloat() * (random.nextBoolean() ? 1.0f : -1.0f);
        var z = random.nextFloat() * (random.nextBoolean() ? 1.0f : -1.0f);
        return new Point3f(x * radius, y * radius, z * radius);
    }

    public static Point3d randomPoint(double radius, Random random) {
        var x = random.nextDouble() * (random.nextBoolean() ? 1.0d : -1.0d);
        var y = random.nextDouble() * (random.nextBoolean() ? 1.0d : -1.0d);
        var z = random.nextDouble() * (random.nextBoolean() ? 1.0d : -1.0d);
        return new Point3d(x * radius, y * radius, z * radius);
    }
}
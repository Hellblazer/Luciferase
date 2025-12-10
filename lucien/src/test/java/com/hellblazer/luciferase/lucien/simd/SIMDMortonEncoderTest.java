/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.lucien.simd;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.internal.VectorAPISupport;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for SIMD Morton encoder.
 * Validates correctness against scalar implementation and tests edge cases.
 * 
 * @author hal.hildebrand
 */
class SIMDMortonEncoderTest {
    
    @Test
    void testSingleEncodeCorrectness() {
        // Test various coordinates against scalar implementation
        int[][] testCases = {
            {0, 0, 0},
            {1, 0, 0},
            {0, 1, 0},
            {0, 0, 1},
            {1, 1, 1},
            {255, 255, 255},
            {1000, 1000, 1000},
            {100000, 100000, 100000},
            {MortonCurve.MAX_REFINEMENT_LEVEL, MortonCurve.MAX_REFINEMENT_LEVEL, MortonCurve.MAX_REFINEMENT_LEVEL}
        };
        
        for (int[] coords : testCases) {
            long expected = MortonCurve.encode(coords[0], coords[1], coords[2]);
            long actual = SIMDMortonEncoder.encode(coords[0], coords[1], coords[2]);
            assertEquals(expected, actual, 
                String.format("Mismatch for (%d, %d, %d)", coords[0], coords[1], coords[2]));
        }
    }
    
    @Test
    void testBatchEncodeCorrectness() {
        var random = new Random(42);
        int batchSize = 100;
        
        int[] x = new int[batchSize];
        int[] y = new int[batchSize];
        int[] z = new int[batchSize];
        long[] expected = new long[batchSize];
        long[] actual = new long[batchSize];
        
        // Generate random coordinates
        for (int i = 0; i < batchSize; i++) {
            x[i] = random.nextInt(1_000_000);
            y[i] = random.nextInt(1_000_000);
            z[i] = random.nextInt(1_000_000);
            expected[i] = MortonCurve.encode(x[i], y[i], z[i]);
        }
        
        // Encode using SIMD
        SIMDMortonEncoder.encodeBatch(x, y, z, actual, batchSize);
        
        // Validate
        for (int i = 0; i < batchSize; i++) {
            assertEquals(expected[i], actual[i], 
                String.format("Batch mismatch at index %d for (%d, %d, %d)", i, x[i], y[i], z[i]));
        }
    }
    
    @Test
    void testBatchEncodeVariousSizes() {
        var random = new Random(123);
        
        // Test various batch sizes including edge cases
        int[] batchSizes = {1, 2, 4, 7, 8, 15, 16, 31, 32, 63, 64, 100, 1000};
        
        for (int batchSize : batchSizes) {
            int[] x = new int[batchSize];
            int[] y = new int[batchSize];
            int[] z = new int[batchSize];
            long[] expected = new long[batchSize];
            long[] actual = new long[batchSize];
            
            for (int i = 0; i < batchSize; i++) {
                x[i] = random.nextInt(1_000_000);
                y[i] = random.nextInt(1_000_000);
                z[i] = random.nextInt(1_000_000);
                expected[i] = MortonCurve.encode(x[i], y[i], z[i]);
            }
            
            SIMDMortonEncoder.encodeBatch(x, y, z, actual, batchSize);
            
            assertArrayEquals(expected, actual, 
                String.format("Mismatch for batch size %d", batchSize));
        }
    }
    
    @Test
    void testBoundaryValues() {
        // Test minimum and maximum coordinate values
        int max = (1 << 21) - 1; // 2^21 - 1 (max 21-bit value)
        
        int[][] boundaries = {
            {0, 0, 0},
            {max, 0, 0},
            {0, max, 0},
            {0, 0, max},
            {max, max, max},
            {max / 2, max / 2, max / 2}
        };
        
        for (int[] coords : boundaries) {
            long expected = MortonCurve.encode(coords[0], coords[1], coords[2]);
            long actual = SIMDMortonEncoder.encode(coords[0], coords[1], coords[2]);
            assertEquals(expected, actual,
                String.format("Boundary mismatch for (%d, %d, %d)", coords[0], coords[1], coords[2]));
        }
    }
    
    @Test
    void testPowerOfTwoCoordinates() {
        // Test powers of 2, which exercise different bit patterns
        for (int shift = 0; shift < 21; shift++) {
            int val = 1 << shift;
            
            long expected = MortonCurve.encode(val, val, val);
            long actual = SIMDMortonEncoder.encode(val, val, val);
            assertEquals(expected, actual,
                String.format("Power of 2 mismatch for 2^%d = %d", shift, val));
        }
    }
    
    @Test
    void testZeroCoordinates() {
        // All zeros should encode to 0
        assertEquals(0L, SIMDMortonEncoder.encode(0, 0, 0));
        
        // One non-zero coordinate
        long xOnly = SIMDMortonEncoder.encode(1, 0, 0);
        long yOnly = SIMDMortonEncoder.encode(0, 1, 0);
        long zOnly = SIMDMortonEncoder.encode(0, 0, 1);
        
        // Should match scalar implementation
        assertEquals(MortonCurve.encode(1, 0, 0), xOnly);
        assertEquals(MortonCurve.encode(0, 1, 0), yOnly);
        assertEquals(MortonCurve.encode(0, 0, 1), zOnly);
        
        // Verify bit positions (X=0, Y=1, Z=2 in Morton order)
        assertEquals(1L, xOnly);  // Bit 0
        assertEquals(2L, yOnly);  // Bit 1
        assertEquals(4L, zOnly);  // Bit 2
    }
    
    @Test
    void testBatchSizeQuery() {
        int batchSize = SIMDMortonEncoder.getBatchSize();
        assertTrue(batchSize > 0, "Batch size should be positive");
        // Batch size reflects hardware SIMD capabilities
        // Common values: 2 (128-bit), 4 (256-bit), 8 (512-bit), 16 (AVX-512 on some CPUs)
        assertTrue(batchSize <= 64, "Batch size should be reasonable (≤ 64 for current architectures)");
        // Verify it's a power of 2 (typical for SIMD vector lengths)
        assertTrue((batchSize & (batchSize - 1)) == 0, "Batch size should be a power of 2");
    }
    
    @Test
    void testSIMDAvailability() {
        // Just verify the method works and returns consistent value
        boolean simdAvailable = SIMDMortonEncoder.isSIMDAvailable();
        assertEquals(VectorAPISupport.isAvailable(), simdAvailable,
            "SIMD availability should match VectorAPISupport");
    }
    
    @Test
    void testLargeRandomBatch() {
        var random = new Random(999);
        int batchSize = 10_000;
        
        int[] x = new int[batchSize];
        int[] y = new int[batchSize];
        int[] z = new int[batchSize];
        long[] expected = new long[batchSize];
        long[] actual = new long[batchSize];
        
        for (int i = 0; i < batchSize; i++) {
            x[i] = random.nextInt(1 << 21);
            y[i] = random.nextInt(1 << 21);
            z[i] = random.nextInt(1 << 21);
            expected[i] = MortonCurve.encode(x[i], y[i], z[i]);
        }
        
        SIMDMortonEncoder.encodeBatch(x, y, z, actual, batchSize);
        
        assertArrayEquals(expected, actual, "Large batch encoding mismatch");
    }
    
    @Test
    void testSequentialCoordinates() {
        // Test coordinates in sequence to catch any pattern-related bugs
        int count = 100;
        int[] x = new int[count];
        int[] y = new int[count];
        int[] z = new int[count];
        long[] expected = new long[count];
        long[] actual = new long[count];
        
        for (int i = 0; i < count; i++) {
            x[i] = i * 1000;
            y[i] = i * 1000;
            z[i] = i * 1000;
            expected[i] = MortonCurve.encode(x[i], y[i], z[i]);
        }
        
        SIMDMortonEncoder.encodeBatch(x, y, z, actual, count);
        
        assertArrayEquals(expected, actual, "Sequential encoding mismatch");
    }
    
    @Test
    void testPartialBatch() {
        // Test encoding only part of the arrays
        var random = new Random(777);
        int arraySize = 100;
        int encodeCount = 50;
        
        int[] x = new int[arraySize];
        int[] y = new int[arraySize];
        int[] z = new int[arraySize];
        long[] output = new long[arraySize];
        
        for (int i = 0; i < arraySize; i++) {
            x[i] = random.nextInt(1_000_000);
            y[i] = random.nextInt(1_000_000);
            z[i] = random.nextInt(1_000_000);
        }
        
        // Encode only first 50 elements
        SIMDMortonEncoder.encodeBatch(x, y, z, output, encodeCount);
        
        // Verify first 50 are correct
        for (int i = 0; i < encodeCount; i++) {
            long expected = MortonCurve.encode(x[i], y[i], z[i]);
            assertEquals(expected, output[i],
                String.format("Partial batch mismatch at index %d", i));
        }
        
        // Verify remaining elements are untouched (still 0)
        for (int i = encodeCount; i < arraySize; i++) {
            assertEquals(0L, output[i],
                String.format("Untouched element modified at index %d", i));
        }
    }
    
    @Test
    void testInterleaving() {
        // Specific test to verify bit interleaving is correct
        // Using simple values that make bit patterns obvious
        
        // Binary: x=001, y=010, z=100 should interleave as zyx zyx zyx = 100 010 001 = 0b100010001 = 273
        int x = 0b001;
        int y = 0b010;
        int z = 0b100;
        
        long expected = MortonCurve.encode(x, y, z);
        long actual = SIMDMortonEncoder.encode(x, y, z);
        
        assertEquals(expected, actual, "Bit interleaving incorrect");
    }
}

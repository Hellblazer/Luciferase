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
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated Morton encoding using Java Vector API.
 * 
 * Provides 4-way parallel Morton encoding for 3D coordinates using IntVector/LongVector.
 * Falls back to scalar implementation when SIMD is unavailable or disabled.
 * 
 * Performance target: 2-4x speedup over scalar implementation
 * - Baseline (scalar): ~524M ops/sec
 * - Target (SIMD): 1-2B ops/sec
 * 
 * @author hal.hildebrand
 */
public class SIMDMortonEncoder {
    
    // Magic bits for encoding (same as MortonCurve but accessible for SIMD)
    private static final long[] MAGIC_BITS_ENCODE = {
        0x1fffffL,           // Mask for 21 bits
        0x1f00000000ffffL,   // After 32-bit shift
        0x1f0000ff0000ffL,   // After 16-bit shift
        0x100f00f00f00f00fL, // After 8-bit shift
        0x10c30c30c30c30c3L, // After 4-bit shift
        0x1249249249249249L  // Final pattern (every 3rd bit)
    };
    
    // Preferred vector species for the current architecture
    private static final VectorSpecies<Long> LONG_SPECIES = LongVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> INT_SPECIES = IntVector.SPECIES_PREFERRED;
    
    // Batch size based on INT vector lanes (we load ints, then convert to longs)
    private static final int BATCH_SIZE = INT_SPECIES.length();
    
    /**
     * Encode a single 3D coordinate to Morton code.
     * Automatically uses SIMD if available, otherwise falls back to scalar.
     * 
     * @param x coordinate (0 to 2^21-1)
     * @param y coordinate (0 to 2^21-1)
     * @param z coordinate (0 to 2^21-1)
     * @return 64-bit Morton code
     */
    public static long encode(int x, int y, int z) {
        if (VectorAPISupport.isAvailable()) {
            return encodeSIMD(x, y, z);
        } else {
            return MortonCurve.encode(x, y, z);
        }
    }
    
    /**
     * Encode a batch of 3D coordinates to Morton codes using SIMD.
     * Processes coordinates in parallel batches for maximum throughput.
     * 
     * @param x array of x coordinates
     * @param y array of y coordinates
     * @param z array of z coordinates
     * @param output array to store Morton codes
     * @param count number of coordinates to encode
     */
    public static void encodeBatch(int[] x, int[] y, int[] z, long[] output, int count) {
        if (!VectorAPISupport.isAvailable() || count < BATCH_SIZE) {
            // Scalar fallback for small batches or no SIMD
            encodeBatchScalar(x, y, z, output, count);
            return;
        }
        
        int i = 0;
        
        // Process full vector-sized batches
        for (; i + BATCH_SIZE <= count; i += BATCH_SIZE) {
            encodeBatchSIMD(x, y, z, output, i);
        }
        
        // Handle remaining elements with scalar code
        for (; i < count; i++) {
            output[i] = MortonCurve.encode(x[i], y[i], z[i]);
        }
    }
    
    /**
     * SIMD implementation of Morton encoding for a single coordinate.
     * Uses Vector API for parallel bit interleaving.
     */
    private static long encodeSIMD(int x, int y, int z) {
        // Create vectors with single element
        var xVec = IntVector.broadcast(INT_SPECIES, x);
        var yVec = IntVector.broadcast(INT_SPECIES, y);
        var zVec = IntVector.broadcast(INT_SPECIES, z);
        
        // Convert to long vectors for 64-bit operations
        var xLong = (LongVector) xVec.convert(VectorOperators.I2L, 0);
        var yLong = (LongVector) yVec.convert(VectorOperators.I2L, 0);
        var zLong = (LongVector) zVec.convert(VectorOperators.I2L, 0);
        
        // Split bits by 3 using SIMD
        var xSplit = splitBy3SIMD(xLong);
        var ySplit = splitBy3SIMD(yLong);
        var zSplit = splitBy3SIMD(zLong);
        
        // Interleave: X | (Y << 1) | (Z << 2)
        var yShifted = ySplit.lanewise(VectorOperators.LSHL, 1);
        var zShifted = zSplit.lanewise(VectorOperators.LSHL, 2);
        
        var result = xSplit.lanewise(VectorOperators.OR, yShifted)
                           .lanewise(VectorOperators.OR, zShifted);
        
        return result.lane(0);
    }
    
    /**
     * SIMD batch encoding for a vector-sized chunk of coordinates.
     * Processes BATCH_SIZE coordinates in parallel.
     * 
     * Note: When INT_SPECIES has more lanes than LONG_SPECIES (e.g., 4 vs 2 on ARM),
     * we need to process in chunks that match the long vector size.
     */
    private static void encodeBatchSIMD(int[] x, int[] y, int[] z, long[] output, int offset) {
        // Load coordinate vectors
        var xVec = IntVector.fromArray(INT_SPECIES, x, offset);
        var yVec = IntVector.fromArray(INT_SPECIES, y, offset);
        var zVec = IntVector.fromArray(INT_SPECIES, z, offset);
        
        // Process in chunks that fit in long vectors
        int longLanes = LONG_SPECIES.length();
        int intLanes = INT_SPECIES.length();
        
        for (int part = 0; part < intLanes / longLanes; part++) {
            // Convert chunk to long vectors (part parameter selects which chunk)
            var xLong = (LongVector) xVec.convert(VectorOperators.I2L, part);
            var yLong = (LongVector) yVec.convert(VectorOperators.I2L, part);
            var zLong = (LongVector) zVec.convert(VectorOperators.I2L, part);
            
            // Split bits by 3 using SIMD
            var xSplit = splitBy3SIMD(xLong);
            var ySplit = splitBy3SIMD(yLong);
            var zSplit = splitBy3SIMD(zLong);
            
            // Interleave: X | (Y << 1) | (Z << 2)
            var yShifted = ySplit.lanewise(VectorOperators.LSHL, 1);
            var zShifted = zSplit.lanewise(VectorOperators.LSHL, 2);
            
            var result = xSplit.lanewise(VectorOperators.OR, yShifted)
                               .lanewise(VectorOperators.OR, zShifted);
            
            // Store results at appropriate offset
            result.intoArray(output, offset + part * longLanes);
        }
    }
    
    /**
     * SIMD implementation of splitBy3 using Vector API.
     * Performs bit interleaving in parallel across vector lanes.
     */
    private static LongVector splitBy3SIMD(LongVector a) {
        // Step 0: Mask to 21 bits
        var x = a.and(MAGIC_BITS_ENCODE[0]);
        
        // Step 1: Spread bits using shifts and masks
        x = x.lanewise(VectorOperators.OR, x.lanewise(VectorOperators.LSHL, 32))
             .and(MAGIC_BITS_ENCODE[1]);
        
        // Step 2: 16-bit shift
        x = x.lanewise(VectorOperators.OR, x.lanewise(VectorOperators.LSHL, 16))
             .and(MAGIC_BITS_ENCODE[2]);
        
        // Step 3: 8-bit shift
        x = x.lanewise(VectorOperators.OR, x.lanewise(VectorOperators.LSHL, 8))
             .and(MAGIC_BITS_ENCODE[3]);
        
        // Step 4: 4-bit shift
        x = x.lanewise(VectorOperators.OR, x.lanewise(VectorOperators.LSHL, 4))
             .and(MAGIC_BITS_ENCODE[4]);
        
        // Step 5: 2-bit shift (final)
        x = x.lanewise(VectorOperators.OR, x.lanewise(VectorOperators.LSHL, 2))
             .and(MAGIC_BITS_ENCODE[5]);
        
        return x;
    }
    
    /**
     * Scalar fallback for batch encoding.
     * Used when SIMD is unavailable or for small batches.
     */
    private static void encodeBatchScalar(int[] x, int[] y, int[] z, long[] output, int count) {
        for (int i = 0; i < count; i++) {
            output[i] = MortonCurve.encode(x[i], y[i], z[i]);
        }
    }
    
    /**
     * Get the batch size for optimal SIMD performance.
     * Returns the number of coordinates that can be processed in parallel.
     * 
     * @return batch size (vector lanes)
     */
    public static int getBatchSize() {
        return BATCH_SIZE;
    }
    
    /**
     * Check if SIMD encoding is currently available.
     * 
     * @return true if SIMD can be used
     */
    public static boolean isSIMDAvailable() {
        return VectorAPISupport.isAvailable();
    }
}

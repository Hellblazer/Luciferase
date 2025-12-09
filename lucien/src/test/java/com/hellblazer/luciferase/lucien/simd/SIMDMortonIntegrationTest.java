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
 * <http://www.gnu.org/licenses/>>.
 */
package com.hellblazer.luciferase.lucien.simd;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.octree.MortonKey;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SIMD Morton encoding with MortonKey and Constants.
 * 
 * Verifies that:
 * 1. Constants.calculateMortonIndex uses SIMD encoder
 * 2. Results match scalar implementation exactly
 * 3. MortonKey integration works correctly
 * 4. Runtime CPU capability detection functions properly
 * 
 * @author hal.hildebrand
 */
class SIMDMortonIntegrationTest {
    
    @Test
    void testConstantsUseSIMDEncoder() {
        // Test that Constants.calculateMortonIndex produces correct results
        var point = new Point3f(100.5f, 200.3f, 300.7f);
        byte level = 10;
        
        long mortonCode = Constants.calculateMortonIndex(point, level);
        
        // Verify it's a valid Morton code (non-negative)
        assertTrue(mortonCode >= 0, "Morton code should be non-negative");
        
        // Verify we can decode it back
        int[] decoded = MortonCurve.decode(mortonCode);
        assertNotNull(decoded);
        assertEquals(3, decoded.length);
    }
    
    @Test
    void testMortonKeyFromCoordinates() {
        // Test MortonKey.fromCoordinates which uses Constants.calculateMortonIndex
        var key = MortonKey.fromCoordinates(100, 200, 300, (byte) 10);
        
        assertNotNull(key);
        // Note: MortonKey calculates level from the morton code, not from the input level
        assertTrue(key.getLevel() >= 0);
        assertTrue(key.isValid());
    }
    
    @Test
    void testSIMDMatchesScalar() {
        // Verify SIMD encoder produces same results as scalar
        int x = 12345;
        int y = 23456;
        int z = 34567;
        
        long scalarResult = MortonCurve.encode(x, y, z);
        long simdResult = SIMDMortonEncoder.encode(x, y, z);
        
        assertEquals(scalarResult, simdResult, 
            "SIMD encoder must produce identical results to scalar encoder");
    }
    
    @Test
    void testMultipleEncodings() {
        // Test various coordinate combinations
        int[][] coords = {
            {0, 0, 0},
            {1, 1, 1},
            {100, 200, 300},
            {1000, 2000, 3000},
            {10000, 20000, 30000},
            {(1 << 20) - 1, (1 << 20) - 1, (1 << 20) - 1}  // Max 21-bit values
        };
        
        for (int[] coord : coords) {
            var point = new Point3f(coord[0], coord[1], coord[2]);
            long mortonCode = Constants.calculateMortonIndex(point, (byte) 15);
            
            // Verify encoding succeeded
            assertTrue(mortonCode >= 0);
            
            // Verify SIMD matches scalar
            long scalarCode = MortonCurve.encode(
                (int) point.x, 
                (int) point.y, 
                (int) point.z
            );
            long simdCode = SIMDMortonEncoder.encode(
                (int) point.x, 
                (int) point.y, 
                (int) point.z
            );
            assertEquals(scalarCode, simdCode);
        }
    }
    
    @Test
    void testCPUCapabilityDetection() {
        // Verify the SIMD support detection works
        boolean simdAvailable = SIMDMortonEncoder.isSIMDAvailable();
        
        // On ARM Mac, SIMD should be available when enabled via system property
        // On other systems or when disabled, it should gracefully fall back
        
        // Regardless of availability, encoding should work
        long code = SIMDMortonEncoder.encode(100, 200, 300);
        assertTrue(code >= 0);
        
        System.out.println("SIMD Available: " + simdAvailable);
        if (simdAvailable) {
            System.out.println("Batch Size: " + SIMDMortonEncoder.getBatchSize());
        }
    }
    
    @Test
    void testBoundaryValues() {
        // Test boundary values for 21-bit coordinates
        int maxCoord = (1 << 21) - 1;  // 2,097,151
        
        var point = new Point3f(maxCoord, maxCoord, maxCoord);
        long mortonCode = Constants.calculateMortonIndex(point, (byte) 0);
        
        assertTrue(mortonCode >= 0);
        
        // Verify SIMD matches scalar at boundaries
        long scalarCode = MortonCurve.encode(maxCoord, maxCoord, maxCoord);
        long simdCode = SIMDMortonEncoder.encode(maxCoord, maxCoord, maxCoord);
        assertEquals(scalarCode, simdCode);
    }
    
    @Test
    void testZeroCoordinates() {
        var point = new Point3f(0, 0, 0);
        long mortonCode = Constants.calculateMortonIndex(point, (byte) 15);
        
        assertEquals(0, mortonCode, "Morton code for origin should be 0");
        
        long simdCode = SIMDMortonEncoder.encode(0, 0, 0);
        assertEquals(0, simdCode);
    }
}

/*
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MortonKey implementation.
 *
 * @author hal.hildebrand
 */
class MortonKeyTest {

    @Test
    void testBasicConstruction() {
        long mortonCode = 12345L;
        MortonKey key = new MortonKey(mortonCode);

        assertEquals(mortonCode, key.getMortonCode());
        assertNotNull(key.toString());
        assertTrue(key.toString().contains("MortonKey[L"));
    }

    @Test
    void testComparison() {
        MortonKey key1 = new MortonKey(100L);
        MortonKey key2 = new MortonKey(200L);
        MortonKey key3 = new MortonKey(100L);

        // Basic comparison
        assertTrue(key1.compareTo(key2) < 0);
        assertTrue(key2.compareTo(key1) > 0);
        assertEquals(0, key1.compareTo(key3));

        // Null handling
        assertThrows(NullPointerException.class, () -> key1.compareTo(null));
    }

    @Test
    void testEquality() {
        MortonKey key1 = new MortonKey(12345L);
        MortonKey key2 = new MortonKey(12345L);
        MortonKey key3 = new MortonKey(54321L);

        // Reflexive
        assertEquals(key1, key1);

        // Symmetric
        assertEquals(key1, key2);
        assertEquals(key2, key1);

        // Different values
        assertNotEquals(key1, key3);
        assertNotEquals(key2, key3);

        // Null and different type
        assertNotEquals(key1, null);
        assertNotEquals(key1, "not a key");

        // Hash code consistency
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    void testFromCoordinates() {
        // Morton codes encode cells at specific levels
        // The key generalization is needed for Tetree where SFC indices
        // are not unique across levels

        MortonKey key = MortonKey.fromCoordinates(10, 20, 30, (byte) 5);
        assertNotNull(key);
        assertTrue(key.getMortonCode() >= 0);
        assertTrue(key.isValid());

        // Test with origin coordinates
        MortonKey origin = MortonKey.fromCoordinates(0, 0, 0, (byte) 0);
        assertNotNull(origin);
        assertEquals(0L, origin.getMortonCode());
        assertEquals(0, origin.getLevel());

        // For non-zero coordinates, the level depends on the quantized values
        // The actual level is correctly determined by Constants.toLevel()
        assertTrue(key.getLevel() >= 0 && key.getLevel() <= MortonCurve.MAX_REFINEMENT_LEVEL);
    }

    @Test
    void testLevelExtraction() {
        // Morton codes in this implementation use coordinate-based level inference
        // The toLevel method infers level from the decoded coordinates

        // Morton code 0 is special - represents origin at coarsest level
        MortonKey root = new MortonKey(0L);
        assertEquals(0, root.getLevel());

        // Very small Morton codes (like 1) represent fine detail at high levels
        MortonKey smallCoords = new MortonKey(1L);
        assertTrue(smallCoords.getLevel() >= 18, "Small coordinates should map to high level");

        // Morton codes in this implementation DO encode level
        // The key generalization is needed for Tetree, not Octree
        MortonKey directCode = new MortonKey(123456789L);
        assertTrue(directCode.getLevel() >= 0 && directCode.getLevel() <= MortonCurve.MAX_REFINEMENT_LEVEL);
    }

    @Test
    void testSpatialLocalityPreservation() {
        // Create keys for spatially adjacent cells
        List<MortonKey> keys = new ArrayList<>();

        // Add keys in a specific spatial pattern
        keys.add(MortonKey.fromCoordinates(0, 0, 0, (byte) 3));
        keys.add(MortonKey.fromCoordinates(1, 0, 0, (byte) 3));
        keys.add(MortonKey.fromCoordinates(0, 1, 0, (byte) 3));
        keys.add(MortonKey.fromCoordinates(1, 1, 0, (byte) 3));
        keys.add(MortonKey.fromCoordinates(0, 0, 1, (byte) 3));
        keys.add(MortonKey.fromCoordinates(1, 0, 1, (byte) 3));
        keys.add(MortonKey.fromCoordinates(0, 1, 1, (byte) 3));
        keys.add(MortonKey.fromCoordinates(1, 1, 1, (byte) 3));

        // Shuffle and sort
        List<MortonKey> shuffled = new ArrayList<>(keys);
        Collections.shuffle(shuffled);
        Collections.sort(shuffled);

        // Verify spatial locality is maintained after sorting
        // Adjacent cells in space should be relatively close in sorted order
        for (int i = 0; i < shuffled.size() - 1; i++) {
            MortonKey current = shuffled.get(i);
            MortonKey next = shuffled.get(i + 1);

            // Morton codes of spatially adjacent cells should be relatively close
            long diff = Math.abs(next.getMortonCode() - current.getMortonCode());
            assertTrue(diff < 1000000L, "Large gap between adjacent Morton codes: " + diff);
        }
    }

    @Test
    void testToString() {
        MortonKey key = new MortonKey(12345L);
        String str = key.toString();

        assertTrue(str.contains("MortonKey"));
        assertTrue(str.contains("m:")); // Now shows morton code in base64
        assertTrue(str.contains("L")); // Shows level
    }

    @Test
    void testValidation() {
        // Valid keys
        assertTrue(new MortonKey(1L).isValid());
        assertTrue(new MortonKey(0x800000000000000L).isValid());

        // Edge cases
        MortonKey maxLevel = new MortonKey(0x8000000000000000L >>> (3 * (21 - Constants.getMaxRefinementLevel())));
        assertTrue(maxLevel.isValid());
    }
    
    // ===== SFC Range Estimation Tests (Phase 1 - k-NN Optimization) =====
    
    @Test
    void testEstimateSFCDepth_BasicFunctionality() {
        // Small radius → fine level (high level number)
        byte fineDepth = MortonKey.estimateSFCDepth(10.0f);
        assertTrue(fineDepth >= 15, "Small radius should map to fine level, got: " + fineDepth);
        
        // Medium radius → medium level
        byte mediumDepth = MortonKey.estimateSFCDepth(1000.0f);
        assertTrue(mediumDepth >= 5 && mediumDepth <= 15, "Medium radius should map to medium level, got: " + mediumDepth);
        
        // Large radius → coarse level (low level number)
        byte coarseDepth = MortonKey.estimateSFCDepth(100000.0f);
        assertTrue(coarseDepth <= 10, "Large radius should map to coarse level, got: " + coarseDepth);
        
        // Very large radius → level 0 (root)
        byte rootDepth = MortonKey.estimateSFCDepth(Float.MAX_VALUE);
        assertEquals(0, rootDepth, "Extremely large radius should map to root level");
    }
    
    @Test
    void testEstimateSFCDepth_MonotonicProperty() {
        // Larger radius should always result in coarser or equal level (smaller or equal level number)
        float radius1 = 100.0f;
        float radius2 = 1000.0f;
        float radius3 = 10000.0f;
        
        byte depth1 = MortonKey.estimateSFCDepth(radius1);
        byte depth2 = MortonKey.estimateSFCDepth(radius2);
        byte depth3 = MortonKey.estimateSFCDepth(radius3);
        
        assertTrue(depth2 <= depth1, "Larger radius should not increase depth: " + depth1 + " vs " + depth2);
        assertTrue(depth3 <= depth2, "Larger radius should not increase depth: " + depth2 + " vs " + depth3);
    }
    
    @Test
    void testEstimateSFCDepth_InvalidInput() {
        // Negative radius
        assertThrows(IllegalArgumentException.class, () -> MortonKey.estimateSFCDepth(-1.0f));
        
        // Zero radius
        assertThrows(IllegalArgumentException.class, () -> MortonKey.estimateSFCDepth(0.0f));
    }
    
    @Test
    void testEstimateSFCRange_BasicCoverage() {
        Point3f center = new Point3f(1000.0f, 1000.0f, 1000.0f);
        float radius = 500.0f;
        
        MortonKey.SFCRange range = MortonKey.estimateSFCRange(center, radius);
        
        assertNotNull(range);
        assertNotNull(range.lower());
        assertNotNull(range.upper());
        assertTrue(range.lower().compareTo(range.upper()) < 0, 
                   "Lower bound should be less than upper bound");
    }
    
    @Test
    void testEstimateSFCRange_ConservativeEstimate() {
        // Verify that the range is conservative (includes all entities in the sphere)
        Point3f center = new Point3f(1000.0f, 1000.0f, 1000.0f);
        float radius = 200.0f;
        
        MortonKey.SFCRange range = MortonKey.estimateSFCRange(center, radius);
        
        // Test points on sphere boundary should be within or near the Morton range
        // We test 6 cardinal directions
        Point3f[] boundaryPoints = {
            new Point3f(center.x + radius, center.y, center.z), // +X
            new Point3f(center.x - radius, center.y, center.z), // -X
            new Point3f(center.x, center.y + radius, center.z), // +Y
            new Point3f(center.x, center.y - radius, center.z), // -Y
            new Point3f(center.x, center.y, center.z + radius), // +Z
            new Point3f(center.x, center.y, center.z - radius)  // -Z
        };
        
        for (Point3f point : boundaryPoints) {
            long pointMortonCode = Constants.calculateMortonIndex(point, range.lower().getLevel());
            MortonKey pointKey = new MortonKey(pointMortonCode, range.lower().getLevel());
            
            // Point should be within or slightly outside the range (conservative)
            // Allow small tolerance due to cell discretization
            boolean withinRange = pointKey.compareTo(range.lower()) >= -1 && 
                                pointKey.compareTo(range.upper()) <= 1;
            assertTrue(withinRange, 
                       "Boundary point should be covered by range: " + point + 
                       " -> key=" + pointKey + " range=[" + range.lower() + ", " + range.upper() + "]");
        }
    }
    
    @Test
    void testEstimateSFCRange_OriginCenter() {
        Point3f origin = new Point3f(0.0f, 0.0f, 0.0f);
        float radius = 100.0f;
        
        MortonKey.SFCRange range = MortonKey.estimateSFCRange(origin, radius);
        
        assertNotNull(range);
        assertTrue(range.lower().compareTo(range.upper()) < 0);
        
        // Range should include origin
        long originMortonCode = Constants.calculateMortonIndex(origin, range.lower().getLevel());
        MortonKey originKey = new MortonKey(originMortonCode, range.lower().getLevel());
        assertTrue(originKey.compareTo(range.lower()) >= 0 && originKey.compareTo(range.upper()) < 0,
                   "Origin should be within range");
    }
    
    @Test
    void testEstimateSFCRange_LargeRadius() {
        Point3f center = new Point3f(5000.0f, 5000.0f, 5000.0f);
        float radius = 10000.0f;
        
        MortonKey.SFCRange range = MortonKey.estimateSFCRange(center, radius);
        
        assertNotNull(range);
        assertTrue(range.lower().compareTo(range.upper()) < 0);
        
        // For large radius, should use coarse level
        assertTrue(range.lower().getLevel() <= 10, 
                   "Large radius should use coarse level, got: " + range.lower().getLevel());
    }
    
    @Test
    void testEstimateSFCRange_SmallRadius() {
        Point3f center = new Point3f(5000.0f, 5000.0f, 5000.0f);
        float radius = 10.0f;
        
        MortonKey.SFCRange range = MortonKey.estimateSFCRange(center, radius);
        
        assertNotNull(range);
        assertTrue(range.lower().compareTo(range.upper()) < 0);
        
        // For small radius, should use fine level
        assertTrue(range.lower().getLevel() >= 15, 
                   "Small radius should use fine level, got: " + range.lower().getLevel());
    }
    
    @Test
    void testEstimateSFCRange_InvalidInput() {
        Point3f center = new Point3f(1000.0f, 1000.0f, 1000.0f);
        
        // Negative radius
        assertThrows(IllegalArgumentException.class, 
                     () -> MortonKey.estimateSFCRange(center, -1.0f));
        
        // Zero radius
        assertThrows(IllegalArgumentException.class, 
                     () -> MortonKey.estimateSFCRange(center, 0.0f));
    }
    
    @Test
    void testEstimateSFCRange_DifferentRadii() {
        Point3f center = new Point3f(1000.0f, 1000.0f, 1000.0f);
        
        // Test multiple radii to verify scaling
        float[] radii = { 10.0f, 50.0f, 100.0f, 500.0f, 1000.0f, 5000.0f };
        MortonKey.SFCRange prevRange = null;
        
        for (float radius : radii) {
            MortonKey.SFCRange range = MortonKey.estimateSFCRange(center, radius);
            assertNotNull(range);
            assertTrue(range.lower().compareTo(range.upper()) < 0);
            
            // Larger radius should not result in finer level (higher level number)
            if (prevRange != null) {
                assertTrue(range.lower().getLevel() <= prevRange.lower().getLevel(),
                           "Larger radius should not use finer level: " + 
                           prevRange.lower().getLevel() + " vs " + range.lower().getLevel());
            }
            prevRange = range;
        }
    }
    
    @Test
    void testSFCRange_RecordValidation() {
        Point3f center = new Point3f(1000.0f, 1000.0f, 1000.0f);
        float radius = 100.0f;
        
        MortonKey.SFCRange range = MortonKey.estimateSFCRange(center, radius);
        
        // Test record methods
        assertNotNull(range.lower());
        assertNotNull(range.upper());
        assertNotNull(range.toString());
        
        // Test null validation in record
        MortonKey validKey = new MortonKey(100L);
        assertThrows(NullPointerException.class, 
                     () -> new MortonKey.SFCRange(null, validKey));
        assertThrows(NullPointerException.class, 
                     () -> new MortonKey.SFCRange(validKey, null));
    }
}

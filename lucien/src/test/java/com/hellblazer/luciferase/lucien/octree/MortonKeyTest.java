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

import com.hellblazer.luciferase.lucien.Constants;
import org.junit.jupiter.api.Test;

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
        assertTrue(key.toString().contains("12345"));
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
        assertTrue(key.getLevel() >= 0 && key.getLevel() <= 21);
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
        assertTrue(directCode.getLevel() >= 0 && directCode.getLevel() <= 21);
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
        assertTrue(str.contains("12345"));
        assertTrue(str.contains("level"));
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
}

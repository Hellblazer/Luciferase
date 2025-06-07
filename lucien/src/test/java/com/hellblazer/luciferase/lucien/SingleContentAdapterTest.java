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
package com.hellblazer.luciferase.lucien;

import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test SingleContentAdapter provides a drop-in replacement for Octree
 *
 * @author hal.hildebrand
 */
public class SingleContentAdapterTest {

    @Test
    void testBasicOperations() {
        // Test that SingleContentAdapter can be used in place of Octree
        SingleContentAdapter<String> adapter = new SingleContentAdapter<>();
        
        // Test insert returns Morton index
        Point3f pos = new Point3f(100, 100, 100);
        byte level = 10;
        long mortonIndex = adapter.insert(pos, level, "TestData");
        assertTrue(mortonIndex >= 0); // Morton index can be 0 for origin
        
        // Test lookup
        String content = adapter.lookup(pos, level);
        assertEquals("TestData", content);
        
        // Test single content behavior - replaces existing
        long mortonIndex2 = adapter.insert(pos, level, "NewData");
        assertEquals(mortonIndex, mortonIndex2); // Same position, same Morton index
        
        content = adapter.lookup(pos, level);
        assertEquals("NewData", content);
        
        // Test size
        assertTrue(adapter.size() > 0);
        
        // Test get by Morton index
        String byIndex = adapter.get(mortonIndex);
        assertEquals("NewData", byIndex);
        
        // Test static toCube
        Spatial.Cube cube = SingleContentAdapter.toCube(mortonIndex);
        assertNotNull(cube);
        
        // Test hasNode
        assertTrue(adapter.hasNode(mortonIndex));
        
        // Test remove
        boolean removed = adapter.remove(pos, level);
        assertTrue(removed);
        assertNull(adapter.lookup(pos, level));
        assertFalse(adapter.hasNode(mortonIndex));
        
        // Test getMap
        var map = adapter.getMap();
        assertNotNull(map);
        assertEquals(0, map.size());
    }

    @Test
    void testSpatialQueries() {
        SingleContentAdapter<String> adapter = new SingleContentAdapter<>();
        
        // Insert some data - use level 15 for finer granularity or spread points further apart
        // At level 10, cell size is 2048, so points need to be > 2048 apart
        adapter.insert(new Point3f(1000, 1000, 1000), (byte)10, "Data1");
        adapter.insert(new Point3f(3000, 3000, 3000), (byte)10, "Data2");
        adapter.insert(new Point3f(5000, 5000, 5000), (byte)10, "Data3");
        
        // Test boundedBy with Cube
        Spatial.Cube region = new Spatial.Cube(0, 0, 0, 6000);
        var bounded = adapter.boundedBy(region);
        assertNotNull(bounded);
        assertEquals(3, bounded.size());
        
        // Test boundedBy Stream
        var stream = adapter.boundedBy((Spatial)region);
        assertNotNull(stream);
        
        // Test enclosing
        var enclosing = adapter.enclosing(region);
        assertNotNull(enclosing);
    }

    @Test
    void testCompatibilityWithOctreeInterface() {
        // This test demonstrates that SingleContentAdapter can replace Octree
        // in existing code without changes
        
        // Before: Octree<String> octree = new Octree<>();
        // After:
        SingleContentAdapter<String> octree = new SingleContentAdapter<>();
        
        // All existing Octree operations should work
        Point3f pos = new Point3f(100, 100, 100);
        long index = octree.insert(pos, (byte)10, "Test");
        
        String data = octree.get(index);
        assertEquals("Test", data);
        
        var map = octree.getMap();
        assertNotNull(map);
        
        assertEquals(1, octree.size());
        
        Spatial.Cube cube = SingleContentAdapter.toCube(index);
        assertNotNull(cube);
    }
}
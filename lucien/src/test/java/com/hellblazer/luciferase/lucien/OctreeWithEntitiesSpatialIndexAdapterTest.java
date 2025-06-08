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

import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test OctreeWithEntitiesSpatialIndexAdapter provides a drop-in replacement for Octree
 *
 * @author hal.hildebrand
 */
public class OctreeWithEntitiesSpatialIndexAdapterTest {

    @Test
    void testBasicOperations() {
        // Test that OctreeWithEntitiesSpatialIndexAdapter can be used in place of Octree
        OctreeWithEntitiesSpatialIndexAdapter<LongEntityID, String> adapter = new OctreeWithEntitiesSpatialIndexAdapter<>(new SequentialLongIDGenerator());
        
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
        
        // Test locate (equivalent to toCube)
        Spatial.Cube cube = adapter.locate(mortonIndex);
        assertNotNull(cube);
        
        // Test hasNode
        assertTrue(adapter.hasNode(mortonIndex));
        
        // Note: OctreeWithEntitiesSpatialIndexAdapter doesn't support remove
        // This is a limitation compared to the original SingleContentAdapter
        
        // Test getMap
        var map = adapter.getMap();
        assertNotNull(map);
        assertEquals(1, map.size()); // Should have one entry since we inserted at one position
    }

    @Test
    void testSpatialQueries() {
        OctreeWithEntitiesSpatialIndexAdapter<LongEntityID, String> adapter = new OctreeWithEntitiesSpatialIndexAdapter<>(new SequentialLongIDGenerator());
        
        // Insert some data at level 10
        // At level 10, cell size is 2048
        // Let's calculate what Morton codes we expect:
        // For point (1000,1000,1000) at level 10: scale = 2048, grid = (0,0,0) -> Morton 0
        // For point (3000,3000,3000) at level 10: scale = 2048, grid = (1,1,1) -> Morton 7
        // For point (5000,5000,5000) at level 10: scale = 2048, grid = (2,2,2) -> Morton 56
        
        long morton1 = adapter.insert(new Point3f(1000, 1000, 1000), (byte)10, "Data1");
        long morton2 = adapter.insert(new Point3f(3000, 3000, 3000), (byte)10, "Data2");
        long morton3 = adapter.insert(new Point3f(5000, 5000, 5000), (byte)10, "Data3");
        
        // Debug: check the Morton indices
        assertNotEquals(morton1, morton2, "Morton indices should be different");
        assertNotEquals(morton2, morton3, "Morton indices should be different");
        assertNotEquals(morton1, morton3, "Morton indices should be different");
        
        // Verify we have 3 entries in the map
        var map = adapter.getMap();
        assertEquals(3, map.size());
        
        // Test boundedBy with Cube
        // We need a region that contains the actual cubes stored
        // Morton 0 has extent 2,097,152 at origin (0,0,0) - too big
        // Morton 7 has extent 1 at origin (1,1,1) 
        // Morton 56 has extent 1 at origin (2,2,2)
        // So let's use a small region that contains the two small cubes
        Spatial.Cube region = new Spatial.Cube(0, 0, 0, 3);
        var bounded = adapter.boundedBy(region);
        assertNotNull(bounded);
        assertEquals(2, bounded.count()); // Only the two small cubes at (1,1,1) and (2,2,2)
        
        // Test boundedBy Stream
        var stream = adapter.boundedBy(region);
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
        OctreeWithEntitiesSpatialIndexAdapter<LongEntityID, String> octree = new OctreeWithEntitiesSpatialIndexAdapter<>(new SequentialLongIDGenerator());
        
        // All existing Octree operations should work
        Point3f pos = new Point3f(100, 100, 100);
        long index = octree.insert(pos, (byte)10, "Test");
        
        String data = octree.get(index);
        assertEquals("Test", data);
        
        var map = octree.getMap();
        assertNotNull(map);
        
        assertEquals(1, octree.size());
        
        Spatial.Cube cube = octree.locate(index);
        assertNotNull(cube);
    }
}
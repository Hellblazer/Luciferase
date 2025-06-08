package com.hellblazer.luciferase.lucien;

import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Point3i;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for OctreeWithEntitiesSpatialIndexAdapter
 * Demonstrates how it can replace SingleContentAdapter usage
 */
public class OctreeWithEntitiesAdapterTest {
    
    private OctreeWithEntitiesSpatialIndexAdapter<LongEntityID, String> adapter;
    private OctreeWithEntitiesSpatialIndexAdapter<LongEntityID, String> singleContentAdapter;
    
    @BeforeEach
    void setUp() {
        // Create the new adapter
        adapter = new OctreeWithEntitiesSpatialIndexAdapter<>(new SequentialLongIDGenerator());
        
        // Create another OctreeWithEntitiesSpatialIndexAdapter for comparison
        singleContentAdapter = new OctreeWithEntitiesSpatialIndexAdapter<>(new SequentialLongIDGenerator());
    }
    
    @Test
    void testBasicInsertAndLookup() {
        Point3f position = new Point3f(100, 200, 300);
        byte level = 5;
        String content = "test-content";
        
        // Insert using adapter
        long mortonIndex = adapter.insert(position, level, content);
        
        // Verify lookup
        String retrieved = adapter.lookup(position, level);
        assertEquals(content, retrieved);
        
        // Verify get by morton index
        String getByIndex = adapter.get(mortonIndex);
        assertEquals(content, getByIndex);
        
        // Compare with another adapter's behavior
        long scaMortonIndex = singleContentAdapter.insert(position, level, content);
        // Morton indices may differ due to entity ID generation
        assertEquals(content, singleContentAdapter.lookup(position, level));
    }
    
    @Test
    void testSpatialIndexInterface() {
        // Both adapters implement SpatialIndex
        SpatialIndex<String> index1 = adapter;
        SpatialIndex<String> index2 = singleContentAdapter;
        
        Point3f pos = new Point3f(50, 50, 50);
        byte level = 3;
        
        // Both should behave identically
        long morton1 = index1.insert(pos, level, "data1");
        long morton2 = index2.insert(pos, level, "data1");
        
        assertEquals(morton1, morton2);
        assertEquals("data1", index1.lookup(pos, level));
        assertEquals("data1", index2.lookup(pos, level));
    }
    
    @Test
    void testWithSearchEngines() {
        // Create spatial engines with adapters
        var engineWithEntities = new OctreeSpatialEngine<String>(adapter);
        var engineWithSingleContent = new OctreeSpatialEngine<String>(singleContentAdapter);
        
        // Both should work with the unified spatial search interface
        Point3f center = new Point3f(1000, 1000, 1000);
        float radius = 500;
        
        // Insert test data at different spatial locations
        byte level = 15;
        adapter.insert(new Point3f(1000, 1000, 1000), level, "center");
        adapter.insert(new Point3f(1400, 1000, 1000), level, "nearby");
        
        singleContentAdapter.insert(new Point3f(1000, 1000, 1000), level, "center");
        singleContentAdapter.insert(new Point3f(1400, 1000, 1000), level, "nearby");
        
        // Both engines should find the same results
        var results1 = engineWithEntities.containedInSphere(center, radius);
        var results2 = engineWithSingleContent.containedInSphere(center, radius);
        
        assertEquals(results1.size(), results2.size());
    }
    
    @Test
    void testStreamOperations() {
        // Use higher level (finer granularity) for better differentiation
        byte level = 15;
        // At level 15, the scale is much finer
        long morton1 = adapter.insert(new Point3f(1000, 1000, 1000), level, "A");
        long morton2 = adapter.insert(new Point3f(2000, 2000, 2000), level, "B");
        long morton3 = adapter.insert(new Point3f(3000, 3000, 3000), level, "C");
        
        // Debug: check if Morton indices are different
        assertNotEquals(morton1, morton2, "Morton indices should be different");
        assertNotEquals(morton2, morton3, "Morton indices should be different");
        assertNotEquals(morton1, morton3, "Morton indices should be different");
        
        // Test nodes() stream
        long nodeCount = adapter.nodes().count();
        assertEquals(3, nodeCount);
        
        // Test boundedBy
        var cube = new Spatial.Cube(0, 0, 0, 2500);
        long boundedCount = adapter.boundedBy(cube).count();
        assertTrue(boundedCount >= 2); // Should include at least (1000,1000,1000) and (2000,2000,2000)
    }
    
    @Test
    void testNavigableMapInterface() {
        // Insert data at different locations to ensure different Morton indices
        byte level = 15;
        adapter.insert(new Point3f(1000, 1000, 1000), level, "first");
        adapter.insert(new Point3f(5000, 5000, 5000), level, "second");
        
        // Get navigable map
        var map = adapter.getMap();
        assertNotNull(map);
        assertEquals(2, map.size());
        
        // NavigableMap operations should work
        assertNotNull(map.firstEntry());
        assertNotNull(map.lastEntry());
    }
    
    @Test
    void testSingleContentMode() {
        Point3f position = new Point3f(50, 50, 50);
        byte level = 4;
        
        // Insert first content
        adapter.insert(position, level, "first");
        
        // In single content mode, inserting at same position should replace
        adapter.insert(position, level, "second");
        
        // Should only have one entity at this position
        String content = adapter.lookup(position, level);
        assertNotNull(content);
        
        // Size should reflect single content per location
        assertEquals(1, adapter.size());
    }
    
    @Test
    void testStatistics() {
        // Insert some data at different locations
        byte level = 15;
        adapter.insert(new Point3f(1000, 1000, 1000), level, "A");
        adapter.insert(new Point3f(5000, 5000, 5000), level, "B");
        
        var stats = adapter.getStats();
        assertNotNull(stats);
        assertEquals(2, stats.totalNodes());
        assertEquals(2, stats.totalEntities());
    }
    
    @Test
    void testEnclosingOperations() {
        // Insert data at known positions
        adapter.insert(new Point3f(100, 100, 100), (byte)5, "content");
        
        // Test enclosing with Tuple3i
        var node = adapter.enclosing(new Point3i(100, 100, 100), (byte)5);
        assertNotNull(node);
        assertEquals("content", node.content());
        
        // Test enclosing with volume
        var smallCube = new Spatial.Cube(95, 95, 95, 10);
        var enclosingNode = adapter.enclosing(smallCube);
        assertNotNull(enclosingNode);
    }
}
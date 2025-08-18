package com.dyada.visualization.data;

import com.dyada.TestBase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VisualizationData Tests")
class VisualizationDataTest extends TestBase {
    
    @Test
    @DisplayName("Create visualization data")
    void testCreateVisualizationData() {
        var bounds = new Bounds(
            new double[]{0.0, 0.0},
            new double[]{10.0, 10.0}
        );
        var metadata = Map.<String, Object>of("type", "test", "version", 1);
        var timestamp = Instant.now();
        
        var data = new VisualizationData("test-id", timestamp, 2, bounds, metadata);
        
        assertEquals("test-id", data.id());
        assertEquals(timestamp, data.timestamp());
        assertEquals(2, data.dimensions());
        assertEquals(bounds, data.bounds());
        assertEquals(metadata, data.metadata());
    }
    
    @Test
    @DisplayName("Default timestamp when null")
    void testDefaultTimestamp() {
        var bounds = new Bounds(new double[]{0.0, 0.0}, new double[]{10.0, 10.0});
        var before = Instant.now();
        
        var data = new VisualizationData("test-id", null, 2, bounds, Map.<String, Object>of());
        
        var after = Instant.now();
        assertNotNull(data.timestamp());
        assertTrue(data.timestamp().isAfter(before.minusSeconds(1)));
        assertTrue(data.timestamp().isBefore(after.plusSeconds(1)));
    }
    
    @Test
    @DisplayName("Add metadata")
    void testWithMetadata() {
        var bounds = new Bounds(new double[]{0.0, 0.0}, new double[]{10.0, 10.0});
        var original = new VisualizationData("test-id", Instant.now(), 2, bounds, Map.<String, Object>of("key1", "value1"));
        
        var updated = original.withMetadata(Map.<String, Object>of("key2", "value2", "key3", 42));
        
        // Original unchanged
        assertEquals(1, original.metadata().size());
        assertEquals("value1", original.metadata().get("key1"));
        
        // Updated has both old and new metadata
        assertEquals(3, updated.metadata().size());
        assertEquals("value1", updated.metadata().get("key1"));
        assertEquals("value2", updated.metadata().get("key2"));
        assertEquals(42, updated.metadata().get("key3"));
    }
    
    @Test
    @DisplayName("Get typed metadata")
    void testGetTypedMetadata() {
        var bounds = new Bounds(new double[]{0.0, 0.0}, new double[]{10.0, 10.0});
        var metadata = Map.<String, Object>of(
            "stringValue", "hello",
            "intValue", 42,
            "doubleValue", 3.14
        );
        
        var data = new VisualizationData("test-id", Instant.now(), 2, bounds, metadata);
        
        assertEquals("hello", data.getMetadata("stringValue", String.class));
        assertEquals(Integer.valueOf(42), data.getMetadata("intValue", Integer.class));
        assertEquals(Double.valueOf(3.14), data.getMetadata("doubleValue", Double.class));
        
        // Wrong type returns null
        assertNull(data.getMetadata("stringValue", Integer.class));
        
        // Non-existent key returns null
        assertNull(data.getMetadata("nonexistent", String.class));
    }
}
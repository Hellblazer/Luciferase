package com.dyada.visualization.data;

import com.dyada.TestBase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VisualizationData Tests")
class VisualizationDataTest extends TestBase {
    
    private static final Bounds BOUNDS_2D = new Bounds(new double[]{0.0, 0.0}, new double[]{1.0, 1.0});
    private static final Bounds BOUNDS_3D = new Bounds(new double[]{0.0, 0.0, 0.0}, new double[]{1.0, 1.0, 1.0});
    
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
    @DisplayName("Constructor with null metadata should use empty map")
    void testNullMetadataDefaultsToEmpty() {
        var data = new VisualizationData("test-id", Instant.now(), 2, BOUNDS_2D, null);
        
        assertNotNull(data.metadata());
        assertTrue(data.metadata().isEmpty());
    }

    @Test
    @DisplayName("Invalid ID validation")
    void testInvalidIdValidation() {
        assertThrows(IllegalArgumentException.class, () -> 
            new VisualizationData(null, Instant.now(), 2, BOUNDS_2D, Map.of()));
        
        assertThrows(IllegalArgumentException.class, () -> 
            new VisualizationData("", Instant.now(), 2, BOUNDS_2D, Map.of()));
        
        assertThrows(IllegalArgumentException.class, () -> 
            new VisualizationData("   ", Instant.now(), 2, BOUNDS_2D, Map.of()));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 5, -1, 0})
    @DisplayName("Invalid dimensions validation")
    void testInvalidDimensionsValidation(int dimensions) {
        assertThrows(IllegalArgumentException.class, () -> 
            new VisualizationData("test-id", Instant.now(), dimensions, BOUNDS_2D, Map.of()));
    }

    @Test
    @DisplayName("Valid dimensions (2 and 3)")
    void testValidDimensions() {
        // 2D should work
        assertDoesNotThrow(() -> 
            new VisualizationData("test-2d", Instant.now(), 2, BOUNDS_2D, Map.of()));
        
        // 3D should work
        assertDoesNotThrow(() -> 
            new VisualizationData("test-3d", Instant.now(), 3, BOUNDS_3D, Map.of()));
    }

    @Test
    @DisplayName("Null bounds validation")
    void testNullBoundsValidation() {
        assertThrows(IllegalArgumentException.class, () -> 
            new VisualizationData("test-id", Instant.now(), 2, null, Map.of()));
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
    @DisplayName("withMetadata overwrites existing keys")
    void testWithMetadataOverwrite() {
        var originalMetadata = Map.<String, Object>of("key1", "value1", "key2", 42);
        var data = new VisualizationData("test-id", Instant.now(), 2, BOUNDS_2D, originalMetadata);
        
        var additionalMetadata = Map.<String, Object>of("key3", "value3", "key2", 100); // key2 should overwrite
        var updatedData = data.withMetadata(additionalMetadata);
        
        // Original should be unchanged
        assertEquals(originalMetadata, data.metadata());
        
        // Updated should have merged metadata
        assertEquals("value1", updatedData.metadata().get("key1")); // from original
        assertEquals(100, updatedData.metadata().get("key2"));       // overwritten
        assertEquals("value3", updatedData.metadata().get("key3"));  // new
        assertEquals(3, updatedData.metadata().size());
    }

    @Test
    @DisplayName("withMetadata with empty map")
    void testWithMetadataEmpty() {
        var originalMetadata = Map.<String, Object>of("key1", "value1");
        var data = new VisualizationData("test-id", Instant.now(), 2, BOUNDS_2D, originalMetadata);
        
        var updatedData = data.withMetadata(Map.of());
        
        // Should be identical to original
        assertEquals(originalMetadata, updatedData.metadata());
        assertEquals(data, updatedData);
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

    @Test
    @DisplayName("getMetadata with boolean and complex types")
    void testGetMetadataComplexTypes() {
        var metadata = Map.<String, Object>of(
            "booleanKey", true,
            "listKey", java.util.List.of(1, 2, 3),
            "mapKey", Map.<String, Object>of("nested", "value"),
            "arrayKey", new int[]{1, 2, 3}
        );
        var data = new VisualizationData("test-id", Instant.now(), 2, BOUNDS_2D, metadata);
        
        assertEquals(Boolean.TRUE, data.getMetadata("booleanKey", Boolean.class));
        assertEquals(java.util.List.of(1, 2, 3), data.getMetadata("listKey", java.util.List.class));
        assertEquals(Map.<String, Object>of("nested", "value"), data.getMetadata("mapKey", Map.class));
        assertEquals(java.util.Arrays.toString(new int[]{1, 2, 3}), java.util.Arrays.toString(data.getMetadata("arrayKey", int[].class)));
    }

    @Test
    @DisplayName("Metadata immutability")
    void testMetadataImmutability() {
        var mutableMetadata = new HashMap<String, Object>();
        mutableMetadata.put("key1", "value1");
        
        var data = new VisualizationData("test-id", Instant.now(), 2, BOUNDS_2D, mutableMetadata);
        
        // Modifying original map should not affect the record
        mutableMetadata.put("key2", "value2");
        
        assertEquals(1, data.metadata().size());
        assertFalse(data.metadata().containsKey("key2"));
    }

    @Test
    @DisplayName("Record equality and hashCode")
    void testRecordEquality() {
        var timestamp = Instant.now();
        var metadata = Map.<String, Object>of("key", "value");
        
        var data1 = new VisualizationData("test-id", timestamp, 2, BOUNDS_2D, metadata);
        var data2 = new VisualizationData("test-id", timestamp, 2, BOUNDS_2D, metadata);
        var data3 = new VisualizationData("different-id", timestamp, 2, BOUNDS_2D, metadata);
        
        assertEquals(data1, data2);
        assertEquals(data1.hashCode(), data2.hashCode());
        assertNotEquals(data1, data3);
    }

    @Test
    @DisplayName("Record toString contains expected information")
    void testToString() {
        var data = new VisualizationData("test-id", Instant.now(), 2, BOUNDS_2D, Map.of("key", "value"));
        
        var toString = data.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("VisualizationData"));
        assertTrue(toString.contains("test-id"));
    }

    @Test
    @DisplayName("Edge case: very long ID")
    void testVeryLongId() {
        var longId = "a".repeat(1000);
        
        assertDoesNotThrow(() -> 
            new VisualizationData(longId, Instant.now(), 2, BOUNDS_2D, Map.of()));
    }

    @Test
    @DisplayName("Edge case: future timestamp")
    void testFutureTimestamp() {
        var futureTime = Instant.now().plusSeconds(3600); // 1 hour in future
        
        var data = new VisualizationData("test-id", futureTime, 2, BOUNDS_2D, Map.of());
        
        assertEquals(futureTime, data.timestamp());
    }

    @Test
    @DisplayName("Edge case: very old timestamp")
    void testVeryOldTimestamp() {
        var oldTime = Instant.ofEpochSecond(0); // Unix epoch
        
        var data = new VisualizationData("test-id", oldTime, 2, BOUNDS_2D, Map.of());
        
        assertEquals(oldTime, data.timestamp());
    }
}
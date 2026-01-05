package com.hellblazer.luciferase.simulation.config;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for SimulationConfig record.
 * <p>
 * Tests the immutable configuration record with validation, builder pattern,
 * and properties loading for simulation parameters.
 * <p>
 * Hardcoded values externalized:
 * - Ghost TTL: 500ms (5 buckets @ 100ms)
 * - Ghost memory limit: 1000 entities
 * - Bucket interval: 100ms
 * - NC threshold: 0.9
 * - Partition threshold: 0.5
 * - Frame split threshold: 1.2
 * - Latency alert threshold: 100ms
 *
 * @author hal.hildebrand
 */
class SimulationConfigTest {

    /**
     * Test 1: Verify default configuration values match current hardcoded constants.
     */
    @Test
    void testDefaults() {
        var config = SimulationConfig.defaults();

        // Ghost layer configuration
        assertEquals(500L, config.ghostTtlMs(), "Default ghost TTL should be 500ms");
        assertEquals(1000, config.ghostMemoryLimit(), "Default ghost memory limit should be 1000");

        // Bucket timing
        assertEquals(100L, config.bucketIntervalMs(), "Default bucket interval should be 100ms");

        // Health thresholds
        assertEquals(0.9f, config.ncThreshold(), 0.0001f, "Default NC threshold should be 0.9");
        assertEquals(0.5f, config.partitionThreshold(), 0.0001f, "Default partition threshold should be 0.5");

        // Performance thresholds
        assertEquals(1.2f, config.frameSplitThreshold(), 0.0001f, "Default frame split threshold should be 1.2");
        assertEquals(100L, config.latencyAlertThresholdMs(), "Default latency alert threshold should be 100ms");

        // Derived values
        assertEquals(5, config.ghostTtlBuckets(), "Default ghost TTL should be 5 buckets (500ms / 100ms)");
    }

    /**
     * Test 2: Load configuration from Properties file format.
     */
    @Test
    void testFromProperties() throws Exception {
        var props = new Properties();
        props.setProperty("ghost.ttl.ms", "1000");
        props.setProperty("ghost.memory.limit", "2000");
        props.setProperty("bucket.interval.ms", "200");
        props.setProperty("nc.threshold", "0.85");
        props.setProperty("partition.threshold", "0.4");
        props.setProperty("frame.split.threshold", "1.5");
        props.setProperty("latency.alert.threshold.ms", "150");

        var config = SimulationConfig.fromProperties(props);

        assertEquals(1000L, config.ghostTtlMs());
        assertEquals(2000, config.ghostMemoryLimit());
        assertEquals(200L, config.bucketIntervalMs());
        assertEquals(0.85f, config.ncThreshold(), 0.0001f);
        assertEquals(0.4f, config.partitionThreshold(), 0.0001f);
        assertEquals(1.5f, config.frameSplitThreshold(), 0.0001f);
        assertEquals(150L, config.latencyAlertThresholdMs());
        assertEquals(5, config.ghostTtlBuckets(), "Ghost TTL buckets should be 1000/200 = 5");
    }

    /**
     * Test 3: Builder pattern with fluent API for custom configurations.
     */
    @Test
    void testBuilderWithCustomValues() {
        var config = SimulationConfig.builder()
            .ghostTtlMs(2000)
            .ghostMemoryLimit(5000)
            .bucketIntervalMs(50)
            .ncThreshold(0.95f)
            .partitionThreshold(0.3f)
            .frameSplitThreshold(1.8f)
            .latencyAlertThresholdMs(200)
            .build();

        assertEquals(2000L, config.ghostTtlMs());
        assertEquals(5000, config.ghostMemoryLimit());
        assertEquals(50L, config.bucketIntervalMs());
        assertEquals(0.95f, config.ncThreshold(), 0.0001f);
        assertEquals(0.3f, config.partitionThreshold(), 0.0001f);
        assertEquals(1.8f, config.frameSplitThreshold(), 0.0001f);
        assertEquals(200L, config.latencyAlertThresholdMs());
        assertEquals(40, config.ghostTtlBuckets(), "Ghost TTL buckets should be 2000/50 = 40");
    }

    /**
     * Test 4: Validation ensures all values are positive.
     */
    @Test
    void testValidatePositiveValues() {
        // Valid config should not throw
        assertDoesNotThrow(() -> SimulationConfig.defaults().validate());

        // Invalid: negative ghostTtlMs
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .ghostTtlMs(-1)
                .build()
        );

        // Invalid: zero ghostMemoryLimit
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .ghostMemoryLimit(0)
                .build()
        );

        // Invalid: negative bucketIntervalMs
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .bucketIntervalMs(-100)
                .build()
        );

        // Invalid: negative latencyAlertThresholdMs
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .latencyAlertThresholdMs(-50)
                .build()
        );
    }

    /**
     * Test 5: Validation ensures thresholds are in valid range [0, 1] for NC and partition,
     * and positive for frame split.
     */
    @Test
    void testValidateThresholdRange() {
        // Invalid: NC threshold > 1.0
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .ncThreshold(1.5f)
                .build()
        );

        // Invalid: NC threshold < 0.0
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .ncThreshold(-0.1f)
                .build()
        );

        // Invalid: partition threshold > 1.0
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .partitionThreshold(2.0f)
                .build()
        );

        // Invalid: partition threshold < 0.0
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .partitionThreshold(-0.5f)
                .build()
        );

        // Invalid: frame split threshold <= 0
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .frameSplitThreshold(0.0f)
                .build()
        );

        // Valid boundary values
        assertDoesNotThrow(() ->
            SimulationConfig.builder()
                .ncThreshold(0.0f)
                .partitionThreshold(1.0f)
                .frameSplitThreshold(0.1f)
                .build()
        );
    }

    /**
     * Test 6: Derived ghostTtlBuckets calculation is correct.
     */
    @Test
    void testGhostTtlBucketsCalculation() {
        var config1 = SimulationConfig.builder()
            .ghostTtlMs(500)
            .bucketIntervalMs(100)
            .build();
        assertEquals(5, config1.ghostTtlBuckets(), "500ms / 100ms = 5 buckets");

        var config2 = SimulationConfig.builder()
            .ghostTtlMs(1000)
            .bucketIntervalMs(250)
            .build();
        assertEquals(4, config2.ghostTtlBuckets(), "1000ms / 250ms = 4 buckets");

        var config3 = SimulationConfig.builder()
            .ghostTtlMs(300)
            .bucketIntervalMs(50)
            .build();
        assertEquals(6, config3.ghostTtlBuckets(), "300ms / 50ms = 6 buckets");
    }

    /**
     * Test 7: Record is immutable (all fields final via record).
     */
    @Test
    void testImmutability() {
        var config = SimulationConfig.defaults();

        // Record fields are implicitly final, verify via reflection
        var fields = SimulationConfig.class.getDeclaredFields();
        for (var field : fields) {
            if (!field.isSynthetic()) {  // Skip synthetic fields
                assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                    "Field " + field.getName() + " should be final");
            }
        }
    }

    /**
     * Test 8: equals() and hashCode() work correctly (provided by record).
     */
    @Test
    void testEqualsAndHashCode() {
        var config1 = SimulationConfig.builder()
            .ghostTtlMs(500)
            .ghostMemoryLimit(1000)
            .bucketIntervalMs(100)
            .build();

        var config2 = SimulationConfig.builder()
            .ghostTtlMs(500)
            .ghostMemoryLimit(1000)
            .bucketIntervalMs(100)
            .build();

        var config3 = SimulationConfig.builder()
            .ghostTtlMs(1000)  // Different value
            .ghostMemoryLimit(1000)
            .bucketIntervalMs(100)
            .build();

        // Same values should be equal
        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());

        // Different values should not be equal
        assertNotEquals(config1, config3);
    }

    /**
     * Test 9: toString() provides useful representation (provided by record).
     */
    @Test
    void testToString() {
        var config = SimulationConfig.defaults();
        var str = config.toString();

        // Should contain record name
        assertTrue(str.contains("SimulationConfig"), "toString should contain record name");

        // Should contain key fields
        assertTrue(str.contains("ghostTtlMs"), "toString should contain ghostTtlMs field");
        assertTrue(str.contains("500"), "toString should contain ghostTtlMs value");
        assertTrue(str.contains("ghostMemoryLimit"), "toString should contain ghostMemoryLimit field");
        assertTrue(str.contains("1000"), "toString should contain ghostMemoryLimit value");
    }

    /**
     * Test 10: Invalid configurations throw exceptions.
     */
    @Test
    void testInvalidConfigThrows() {
        // ghostTtlMs not evenly divisible by bucketIntervalMs should work (integer division)
        var config = SimulationConfig.builder()
            .ghostTtlMs(550)
            .bucketIntervalMs(100)
            .build();
        assertEquals(5, config.ghostTtlBuckets(), "550ms / 100ms = 5 buckets (integer division)");

        // But warn if bucketIntervalMs > ghostTtlMs (results in 0 buckets)
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .ghostTtlMs(50)
                .bucketIntervalMs(100)
                .build(),
            "ghostTtlBuckets must be > 0"
        );

        // Combination validation: ensure all validation rules work together
        assertThrows(IllegalArgumentException.class, () ->
            SimulationConfig.builder()
                .ghostTtlMs(-1)
                .ghostMemoryLimit(0)
                .ncThreshold(2.0f)
                .build(),
            "Should fail on first validation error"
        );
    }
}

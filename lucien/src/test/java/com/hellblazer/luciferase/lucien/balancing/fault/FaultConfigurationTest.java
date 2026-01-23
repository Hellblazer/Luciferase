package com.hellblazer.luciferase.lucien.balancing.fault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FaultConfiguration} record.
 */
class FaultConfigurationTest {

    @Test
    void testDefaultValues() {
        var config = FaultConfiguration.defaultConfig();

        assertEquals(500, config.heartbeatIntervalMs());
        assertEquals(2000, config.heartbeatTimeoutMs());
        assertEquals(5000, config.barrierTimeoutMs());
        assertEquals(3, config.maxRetries());
        assertEquals(2, config.cascadingThreshold());
    }

    @Test
    void testCustomConfiguration() {
        var config = new FaultConfiguration(
            1000,  // heartbeatIntervalMs
            3000,  // heartbeatTimeoutMs
            10000, // barrierTimeoutMs
            5,     // maxRetries
            3      // cascadingThreshold
        );

        assertEquals(1000, config.heartbeatIntervalMs());
        assertEquals(3000, config.heartbeatTimeoutMs());
        assertEquals(10000, config.barrierTimeoutMs());
        assertEquals(5, config.maxRetries());
        assertEquals(3, config.cascadingThreshold());
    }

    @Test
    void testBuilderStyleMethods() {
        var config = FaultConfiguration.defaultConfig()
            .withHeartbeatInterval(1000)
            .withHeartbeatTimeout(3000)
            .withBarrierTimeout(10000)
            .withMaxRetries(5)
            .withCascadingThreshold(3);

        assertEquals(1000, config.heartbeatIntervalMs());
        assertEquals(3000, config.heartbeatTimeoutMs());
        assertEquals(10000, config.barrierTimeoutMs());
        assertEquals(5, config.maxRetries());
        assertEquals(3, config.cascadingThreshold());
    }

    @Test
    void testValidationPositiveTimeouts() {
        // Positive timeouts should be valid
        assertDoesNotThrow(() -> new FaultConfiguration(100, 200, 300, 1, 1));
    }

    @Test
    void testValidationNegativeHeartbeatInterval() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
            new FaultConfiguration(-1, 2000, 5000, 3, 2));
        assertTrue(ex.getMessage().contains("heartbeatIntervalMs"));
    }

    @Test
    void testValidationNegativeHeartbeatTimeout() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
            new FaultConfiguration(500, -1, 5000, 3, 2));
        assertTrue(ex.getMessage().contains("heartbeatTimeoutMs"));
    }

    @Test
    void testValidationNegativeBarrierTimeout() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
            new FaultConfiguration(500, 2000, -1, 3, 2));
        assertTrue(ex.getMessage().contains("barrierTimeoutMs"));
    }

    @Test
    void testValidationNegativeMaxRetries() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
            new FaultConfiguration(500, 2000, 5000, -1, 2));
        assertTrue(ex.getMessage().contains("maxRetries"));
    }

    @Test
    void testValidationNegativeCascadingThreshold() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
            new FaultConfiguration(500, 2000, 5000, 3, -1));
        assertTrue(ex.getMessage().contains("cascadingThreshold"));
    }

    @Test
    void testValidationZeroValues() {
        // Zero is valid (disable feature)
        assertDoesNotThrow(() -> new FaultConfiguration(0, 0, 0, 0, 0));
    }

    @Test
    void testRecordEquality() {
        var config1 = new FaultConfiguration(500, 2000, 5000, 3, 2);
        var config2 = new FaultConfiguration(500, 2000, 5000, 3, 2);
        var config3 = new FaultConfiguration(1000, 2000, 5000, 3, 2);

        assertEquals(config1, config2);
        assertNotEquals(config1, config3);
        assertEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    void testRecordToString() {
        var config = FaultConfiguration.defaultConfig();
        var str = config.toString();

        assertTrue(str.contains("FaultConfiguration"));
        assertTrue(str.contains("heartbeatIntervalMs"));
        assertTrue(str.contains("500"));
    }

    @Test
    void testImmutability() {
        var config = FaultConfiguration.defaultConfig();
        var modified = config.withHeartbeatInterval(1000);

        // Original unchanged
        assertEquals(500, config.heartbeatIntervalMs());
        // New instance modified
        assertEquals(1000, modified.heartbeatIntervalMs());
    }
}

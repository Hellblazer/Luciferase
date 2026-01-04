package com.hellblazer.luciferase.simulation.tumbler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TumblerConfig builder pattern and validation.
 *
 * @author hal.hildebrand
 */
class TumblerConfigTest {

    @Test
    void testDefaults() {
        var config = TumblerConfig.defaults();

        assertEquals(5000, config.splitThreshold());
        assertEquals(500, config.joinThreshold());
        assertEquals(4, config.minRegionLevel());
        assertEquals(10, config.maxRegionLevel());  // CompactTetreeKey limit
        assertEquals(0.1f, config.spanWidthRatio(), 0.001f);
        assertEquals(1.0f, config.minSpanDistance(), 0.001f);
        assertTrue(config.autoAdapt());
        assertEquals(100, config.adaptCheckInterval());
        assertEquals(TumblerConfig.RegionSplitStrategy.OCTANT, config.splitStrategy());
    }

    @Test
    void testWithSplitThreshold() {
        var config = TumblerConfig.defaults().withSplitThreshold(10000);

        assertEquals(10000, config.splitThreshold());
        assertEquals(500, config.joinThreshold()); // Unchanged
    }

    @Test
    void testWithJoinThreshold() {
        var config = TumblerConfig.defaults().withJoinThreshold(1000);

        assertEquals(5000, config.splitThreshold()); // Unchanged
        assertEquals(1000, config.joinThreshold());
    }

    @Test
    void testWithRegionLevels() {
        var config = TumblerConfig.defaults().withRegionLevels((byte) 6, (byte) 15);

        assertEquals(6, config.minRegionLevel());
        assertEquals(15, config.maxRegionLevel());
    }

    @Test
    void testWithSpanParameters() {
        var config = TumblerConfig.defaults().withSpanParameters(0.2f, 2.0f);

        assertEquals(0.2f, config.spanWidthRatio(), 0.001f);
        assertEquals(2.0f, config.minSpanDistance(), 0.001f);
    }

    @Test
    void testWithoutAutoAdapt() {
        var config = TumblerConfig.defaults().withoutAutoAdapt();

        assertFalse(config.autoAdapt());
    }

    @Test
    void testWithAdaptCheckInterval() {
        var config = TumblerConfig.defaults().withAdaptCheckInterval(200);

        assertEquals(200, config.adaptCheckInterval());
    }

    @Test
    void testWithSplitStrategy() {
        var config = TumblerConfig.defaults().withSplitStrategy(TumblerConfig.RegionSplitStrategy.OCTANT);

        assertEquals(TumblerConfig.RegionSplitStrategy.OCTANT, config.splitStrategy());
    }

    // Validation tests

    @Test
    void testValidationSplitThresholdNegative() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(-1, 500, (byte) 4, (byte) 12, 0.1f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationSplitThresholdZero() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(0, 500, (byte) 4, (byte) 12, 0.1f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationJoinThresholdNegative() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, -1, (byte) 4, (byte) 12, 0.1f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationJoinGreaterThanSplit() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(500, 5000, (byte) 4, (byte) 12, 0.1f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT),
            "Join threshold must be less than split threshold (hysteresis gap)"
        );
    }

    @Test
    void testValidationJoinEqualSplit() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, 5000, (byte) 4, (byte) 12, 0.1f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT),
            "Join threshold must be less than split threshold (hysteresis gap)"
        );
    }

    @Test
    void testValidationMinRegionLevelNegative() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, 500, (byte) -1, (byte) 12, 0.1f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationMinRegionLevelTooHigh() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, 500, (byte) 22, (byte) 12, 0.1f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationMaxRegionLevelLessThanMin() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, 500, (byte) 12, (byte) 4, 0.1f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationMaxRegionLevelTooHigh() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, 500, (byte) 4, (byte) 22, 0.1f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationSpanWidthRatioNegative() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, 500, (byte) 4, (byte) 12, -0.1f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationSpanWidthRatioTooLarge() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, 500, (byte) 4, (byte) 12, 1.5f, 1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationMinSpanDistanceNegative() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, 500, (byte) 4, (byte) 12, 0.1f, -1.0f, true, 100,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationAdaptCheckIntervalNegative() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, 500, (byte) 4, (byte) 12, 0.1f, 1.0f, true, -1,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidationAdaptCheckIntervalZero() {
        assertThrows(IllegalArgumentException.class, () ->
            new TumblerConfig(5000, 500, (byte) 4, (byte) 12, 0.1f, 1.0f, true, 0,
                TumblerConfig.RegionSplitStrategy.OCTANT)
        );
    }

    @Test
    void testValidConfig() {
        // Should not throw
        var config = new TumblerConfig(5000, 500, (byte) 4, (byte) 12, 0.1f, 1.0f, true, 100,
            TumblerConfig.RegionSplitStrategy.OCTANT);

        assertNotNull(config);
    }
}

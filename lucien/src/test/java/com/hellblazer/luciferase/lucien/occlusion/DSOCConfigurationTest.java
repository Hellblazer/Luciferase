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
package com.hellblazer.luciferase.lucien.occlusion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for DSOCConfiguration
 *
 * @author hal.hildebrand
 */
public class DSOCConfigurationTest {

    @Test
    void testDefaultConfiguration() {
        var config = DSOCConfiguration.defaultConfig();

        // Verify default values
        assertNotNull(config.getTbvStrategy());
        assertTrue(config.getTbvStrategy() instanceof AdaptiveTBVStrategy);
        assertTrue(config.isEnableAdaptiveStrategySwitching());
        assertEquals(2, config.getMaxTBVsPerEntity());
        assertEquals(100000, config.getMaxTotalTBVs());
        assertEquals(0.3f, config.getTbvRefreshThreshold());
        assertEquals(1000, config.getBatchUpdateSize());
        assertEquals(0.1f, config.getMinAcceptableQuality());
        assertTrue(config.isEnableQualityBasedCulling());
        assertEquals(0.2f, config.getQualityCullingThreshold());
        assertEquals(10, config.getUpdateCheckInterval());
        assertTrue(config.isEnableLazyUpdates());
        assertTrue(config.isEnablePredictiveUpdates());
        assertEquals(30, config.getPredictiveUpdateLookahead());
        assertTrue(config.isEnableTBVPooling());
        assertEquals(10000, config.getTbvPoolSize());
        assertFalse(config.isEnableAggressiveCleanup());
        assertEquals(300, config.getCleanupInterval());
        assertTrue(config.isEnableSpatialCoherence());
        assertEquals(50.0f, config.getSpatialCoherenceRadius());
        assertTrue(config.isEnableHierarchicalTBVs());
        assertEquals(3, config.getMaxHierarchyDepth());
        assertTrue(config.isEnableStatistics());
        assertFalse(config.isEnableDetailedProfiling());
        assertEquals(60, config.getStatisticsUpdateInterval());
    }

    @Test
    void testHighPerformanceConfiguration() {
        var config = DSOCConfiguration.highPerformance();

        // Verify high performance optimizations
        assertTrue(config.getTbvStrategy() instanceof FixedDurationTBVStrategy);
        assertEquals(1, config.getMaxTBVsPerEntity());
        assertFalse(config.isEnableQualityBasedCulling());
        assertTrue(config.isEnableLazyUpdates());
        assertTrue(config.isEnableAggressiveCleanup());
        assertFalse(config.isEnableDetailedProfiling());
    }

    @Test
    void testHighQualityConfiguration() {
        var config = DSOCConfiguration.highQuality();

        // Verify high quality settings
        assertTrue(config.getTbvStrategy() instanceof AdaptiveTBVStrategy);
        assertEquals(3, config.getMaxTBVsPerEntity());
        assertEquals(0.5f, config.getTbvRefreshThreshold());
        assertEquals(5, config.getUpdateCheckInterval());
        assertTrue(config.isEnablePredictiveUpdates());
        assertEquals(60, config.getPredictiveUpdateLookahead());
    }

    @Test
    void testStaticSceneConfiguration() {
        var config = DSOCConfiguration.staticScene();

        // Verify static scene optimizations
        assertTrue(config.getTbvStrategy() instanceof AdaptiveTBVStrategy);
        assertEquals(30, config.getUpdateCheckInterval());
        assertTrue(config.isEnableLazyUpdates());
        assertEquals(0.1f, config.getTbvRefreshThreshold());
        assertEquals(600, config.getCleanupInterval());
    }

    @Test
    void testDynamicSceneConfiguration() {
        var config = DSOCConfiguration.dynamicScene();

        // Verify dynamic scene settings
        assertTrue(config.getTbvStrategy() instanceof AdaptiveTBVStrategy);
        assertEquals(3, config.getUpdateCheckInterval());
        assertEquals(0.4f, config.getTbvRefreshThreshold());
        assertTrue(config.isEnablePredictiveUpdates());
        assertEquals(2000, config.getBatchUpdateSize());
    }

    @Test
    void testFluentAPISetters() {
        var config = new DSOCConfiguration();

        // Test TBV Strategy
        var newStrategy = FixedDurationTBVStrategy.defaultStrategy();
        config.withTBVStrategy(newStrategy);
        assertEquals(newStrategy, config.getTbvStrategy());
        assertThrows(IllegalArgumentException.class, () -> config.withTBVStrategy(null));

        // Test boolean settings
        config.withAdaptiveStrategySwitching(false);
        assertFalse(config.isEnableAdaptiveStrategySwitching());

        config.withQualityBasedCulling(false);
        assertFalse(config.isEnableQualityBasedCulling());

        config.withLazyUpdates(false);
        assertFalse(config.isEnableLazyUpdates());

        config.withPredictiveUpdates(false);
        assertFalse(config.isEnablePredictiveUpdates());

        config.withTBVPooling(false);
        assertFalse(config.isEnableTBVPooling());

        config.withAggressiveCleanup(true);
        assertTrue(config.isEnableAggressiveCleanup());

        config.withSpatialCoherence(false);
        assertFalse(config.isEnableSpatialCoherence());

        config.withHierarchicalTBVs(false);
        assertFalse(config.isEnableHierarchicalTBVs());

        config.withStatistics(false);
        assertFalse(config.isEnableStatistics());

        config.withDetailedProfiling(true);
        assertTrue(config.isEnableDetailedProfiling());
    }

    @Test
    void testNumericSettersValidation() {
        var config = new DSOCConfiguration();

        // Test maxTBVsPerEntity
        config.withMaxTBVsPerEntity(5);
        assertEquals(5, config.getMaxTBVsPerEntity());
        assertThrows(IllegalArgumentException.class, () -> config.withMaxTBVsPerEntity(0));
        assertThrows(IllegalArgumentException.class, () -> config.withMaxTBVsPerEntity(-1));

        // Test maxTotalTBVs
        config.withMaxTotalTBVs(50000);
        assertEquals(50000, config.getMaxTotalTBVs());
        assertThrows(IllegalArgumentException.class, () -> config.withMaxTotalTBVs(0));
        assertThrows(IllegalArgumentException.class, () -> config.withMaxTotalTBVs(-1));

        // Test tbvRefreshThreshold
        config.withTBVRefreshThreshold(0.5f);
        assertEquals(0.5f, config.getTbvRefreshThreshold());
        assertThrows(IllegalArgumentException.class, () -> config.withTBVRefreshThreshold(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> config.withTBVRefreshThreshold(1.1f));

        // Test batchUpdateSize
        config.withBatchUpdateSize(500);
        assertEquals(500, config.getBatchUpdateSize());
        assertThrows(IllegalArgumentException.class, () -> config.withBatchUpdateSize(0));
        assertThrows(IllegalArgumentException.class, () -> config.withBatchUpdateSize(-1));

        // Test minAcceptableQuality
        config.withMinAcceptableQuality(0.2f);
        assertEquals(0.2f, config.getMinAcceptableQuality());
        assertThrows(IllegalArgumentException.class, () -> config.withMinAcceptableQuality(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> config.withMinAcceptableQuality(1.1f));

        // Test qualityCullingThreshold
        config.withQualityCullingThreshold(0.3f);
        assertEquals(0.3f, config.getQualityCullingThreshold());
        assertThrows(IllegalArgumentException.class, () -> config.withQualityCullingThreshold(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> config.withQualityCullingThreshold(1.1f));

        // Test updateCheckInterval
        config.withUpdateCheckInterval(20);
        assertEquals(20, config.getUpdateCheckInterval());
        assertThrows(IllegalArgumentException.class, () -> config.withUpdateCheckInterval(0));
        assertThrows(IllegalArgumentException.class, () -> config.withUpdateCheckInterval(-1));

        // Test predictiveUpdateLookahead
        config.withPredictiveUpdateLookahead(45);
        assertEquals(45, config.getPredictiveUpdateLookahead());
        assertThrows(IllegalArgumentException.class, () -> config.withPredictiveUpdateLookahead(0));
        assertThrows(IllegalArgumentException.class, () -> config.withPredictiveUpdateLookahead(-1));

        // Test tbvPoolSize
        config.withTBVPoolSize(5000);
        assertEquals(5000, config.getTbvPoolSize());
        assertThrows(IllegalArgumentException.class, () -> config.withTBVPoolSize(0));
        assertThrows(IllegalArgumentException.class, () -> config.withTBVPoolSize(-1));

        // Test cleanupInterval
        config.withCleanupInterval(600);
        assertEquals(600, config.getCleanupInterval());
        assertThrows(IllegalArgumentException.class, () -> config.withCleanupInterval(0));
        assertThrows(IllegalArgumentException.class, () -> config.withCleanupInterval(-1));

        // Test spatialCoherenceRadius
        config.withSpatialCoherenceRadius(100.0f);
        assertEquals(100.0f, config.getSpatialCoherenceRadius());
        assertThrows(IllegalArgumentException.class, () -> config.withSpatialCoherenceRadius(0.0f));
        assertThrows(IllegalArgumentException.class, () -> config.withSpatialCoherenceRadius(-1.0f));

        // Test maxHierarchyDepth
        config.withMaxHierarchyDepth(5);
        assertEquals(5, config.getMaxHierarchyDepth());
        assertThrows(IllegalArgumentException.class, () -> config.withMaxHierarchyDepth(0));
        assertThrows(IllegalArgumentException.class, () -> config.withMaxHierarchyDepth(-1));

        // Test statisticsUpdateInterval
        config.withStatisticsUpdateInterval(120);
        assertEquals(120, config.getStatisticsUpdateInterval());
        assertThrows(IllegalArgumentException.class, () -> config.withStatisticsUpdateInterval(0));
        assertThrows(IllegalArgumentException.class, () -> config.withStatisticsUpdateInterval(-1));
    }

    @Test
    void testFluentAPIChaining() {
        var config = new DSOCConfiguration()
            .withMaxTBVsPerEntity(3)
            .withMaxTotalTBVs(50000)
            .withTBVRefreshThreshold(0.4f)
            .withBatchUpdateSize(2000)
            .withMinAcceptableQuality(0.15f)
            .withQualityBasedCulling(true)
            .withQualityCullingThreshold(0.25f)
            .withUpdateCheckInterval(15)
            .withLazyUpdates(true)
            .withPredictiveUpdates(true)
            .withPredictiveUpdateLookahead(45)
            .withTBVPooling(true)
            .withTBVPoolSize(15000)
            .withAggressiveCleanup(false)
            .withCleanupInterval(450)
            .withSpatialCoherence(true)
            .withSpatialCoherenceRadius(75.0f)
            .withHierarchicalTBVs(true)
            .withMaxHierarchyDepth(4)
            .withStatistics(true)
            .withDetailedProfiling(false)
            .withStatisticsUpdateInterval(90);

        // Verify all values were set correctly
        assertEquals(3, config.getMaxTBVsPerEntity());
        assertEquals(50000, config.getMaxTotalTBVs());
        assertEquals(0.4f, config.getTbvRefreshThreshold());
        assertEquals(2000, config.getBatchUpdateSize());
        assertEquals(0.15f, config.getMinAcceptableQuality());
        assertTrue(config.isEnableQualityBasedCulling());
        assertEquals(0.25f, config.getQualityCullingThreshold());
        assertEquals(15, config.getUpdateCheckInterval());
        assertTrue(config.isEnableLazyUpdates());
        assertTrue(config.isEnablePredictiveUpdates());
        assertEquals(45, config.getPredictiveUpdateLookahead());
        assertTrue(config.isEnableTBVPooling());
        assertEquals(15000, config.getTbvPoolSize());
        assertFalse(config.isEnableAggressiveCleanup());
        assertEquals(450, config.getCleanupInterval());
        assertTrue(config.isEnableSpatialCoherence());
        assertEquals(75.0f, config.getSpatialCoherenceRadius());
        assertTrue(config.isEnableHierarchicalTBVs());
        assertEquals(4, config.getMaxHierarchyDepth());
        assertTrue(config.isEnableStatistics());
        assertFalse(config.isEnableDetailedProfiling());
        assertEquals(90, config.getStatisticsUpdateInterval());
    }

    @Test
    void testConfigurationIndependence() {
        // Create multiple configurations and ensure they don't affect each other
        var config1 = DSOCConfiguration.defaultConfig();
        var config2 = DSOCConfiguration.highPerformance();
        var config3 = DSOCConfiguration.highQuality();

        // Modify config1
        config1.withMaxTBVsPerEntity(10);
        config1.withTBVRefreshThreshold(0.8f);

        // Ensure other configs are unaffected
        assertEquals(1, config2.getMaxTBVsPerEntity());
        assertEquals(3, config3.getMaxTBVsPerEntity());
        assertNotEquals(0.8f, config2.getTbvRefreshThreshold());
        assertEquals(0.5f, config3.getTbvRefreshThreshold());
    }

    @Test
    void testEdgeCaseValues() {
        var config = new DSOCConfiguration();

        // Test boundary values for float parameters
        config.withTBVRefreshThreshold(0.0f);
        assertEquals(0.0f, config.getTbvRefreshThreshold());
        
        config.withTBVRefreshThreshold(1.0f);
        assertEquals(1.0f, config.getTbvRefreshThreshold());

        config.withMinAcceptableQuality(0.0f);
        assertEquals(0.0f, config.getMinAcceptableQuality());
        
        config.withMinAcceptableQuality(1.0f);
        assertEquals(1.0f, config.getMinAcceptableQuality());

        config.withQualityCullingThreshold(0.0f);
        assertEquals(0.0f, config.getQualityCullingThreshold());
        
        config.withQualityCullingThreshold(1.0f);
        assertEquals(1.0f, config.getQualityCullingThreshold());

        // Test large values for integer parameters
        config.withMaxTBVsPerEntity(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, config.getMaxTBVsPerEntity());

        config.withMaxTotalTBVs(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, config.getMaxTotalTBVs());
    }

    @Test
    void testStrategyIntegration() {
        var config = new DSOCConfiguration();

        // Test with different strategy types
        var fixedStrategy = new FixedDurationTBVStrategy(120);
        config.withTBVStrategy(fixedStrategy);
        assertEquals(fixedStrategy, config.getTbvStrategy());

        var adaptiveStrategy = AdaptiveTBVStrategy.defaultStrategy();
        config.withTBVStrategy(adaptiveStrategy);
        assertEquals(adaptiveStrategy, config.getTbvStrategy());

        // Test with custom strategies
        var customFixed = new FixedDurationTBVStrategy(90, 0.2f, 0.3f);
        config.withTBVStrategy(customFixed);
        assertEquals(customFixed, config.getTbvStrategy());

        var customAdaptive = new AdaptiveTBVStrategy(20, 200, 15.0f, 0.4f, 0.15f, 0.2f);
        config.withTBVStrategy(customAdaptive);
        assertEquals(customAdaptive, config.getTbvStrategy());
    }

    @Test
    void testPerformanceProfiles() {
        // Test that preset configurations have reasonable relationships
        var defaultConfig = DSOCConfiguration.defaultConfig();
        var highPerfConfig = DSOCConfiguration.highPerformance();
        var highQualityConfig = DSOCConfiguration.highQuality();

        // High performance should have fewer TBVs per entity than high quality
        assertTrue(highPerfConfig.getMaxTBVsPerEntity() < highQualityConfig.getMaxTBVsPerEntity());

        // High quality should have more frequent update checks than default
        assertTrue(highQualityConfig.getUpdateCheckInterval() < defaultConfig.getUpdateCheckInterval());

        // High performance should have more aggressive cleanup than default
        assertTrue(highPerfConfig.isEnableAggressiveCleanup());
        assertFalse(defaultConfig.isEnableAggressiveCleanup());

        // High quality should have higher refresh threshold than high performance
        assertTrue(highQualityConfig.getTbvRefreshThreshold() > 
                  DSOCConfiguration.highPerformance().getTbvRefreshThreshold());
    }
}
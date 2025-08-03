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

import com.hellblazer.luciferase.lucien.entity.EntityBounds;
import org.junit.jupiter.api.Test;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for TBVStrategy implementations
 *
 * @author hal.hildebrand
 */
public class TBVStrategyTest {

    @Test
    void testTBVParametersValidation() {
        // Test valid parameters
        var params1 = TBVStrategy.TBVParameters.withDuration(60);
        assertEquals(60, params1.validityDuration());
        assertEquals(0.0f, params1.expansionFactor());

        var params2 = TBVStrategy.TBVParameters.with(120, 2.5f);
        assertEquals(120, params2.validityDuration());
        assertEquals(2.5f, params2.expansionFactor());

        // Test invalid duration
        assertThrows(IllegalArgumentException.class, () -> TBVStrategy.TBVParameters.withDuration(0));
        assertThrows(IllegalArgumentException.class, () -> TBVStrategy.TBVParameters.withDuration(-10));

        // Test invalid expansion
        assertThrows(IllegalArgumentException.class, () -> TBVStrategy.TBVParameters.with(60, -1.0f));
    }

    @Test
    void testFixedDurationStrategyConstructor() {
        // Test full constructor
        var strategy = new FixedDurationTBVStrategy(120, 0.5f, 0.2f);
        assertEquals(120, strategy.getValidityDuration());
        assertEquals(0.5f, strategy.getBaseExpansionFactor());
        assertEquals(0.2f, strategy.getVelocityExpansionMultiplier());

        // Test simple constructor
        var simpleStrategy = new FixedDurationTBVStrategy(60);
        assertEquals(60, simpleStrategy.getValidityDuration());
        assertEquals(0.1f, simpleStrategy.getBaseExpansionFactor());
        assertEquals(0.1f, simpleStrategy.getVelocityExpansionMultiplier());

        // Test invalid parameters
        assertThrows(IllegalArgumentException.class, () -> new FixedDurationTBVStrategy(0, 0.1f, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new FixedDurationTBVStrategy(60, -0.1f, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new FixedDurationTBVStrategy(60, 0.1f, -0.1f));
    }

    @Test
    void testFixedDurationStrategyCalculation() {
        var strategy = new FixedDurationTBVStrategy(60, 1.0f, 0.1f);
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));

        // Test with zero velocity
        var zeroVelocity = new Vector3f(0, 0, 0);
        var params1 = strategy.calculateTBVParameters(bounds, zeroVelocity);
        assertEquals(60, params1.validityDuration());
        assertEquals(1.0f, params1.expansionFactor()); // Base expansion only

        // Test with moderate velocity
        var moderateVelocity = new Vector3f(5, 0, 0);
        var params2 = strategy.calculateTBVParameters(bounds, moderateVelocity);
        assertEquals(60, params2.validityDuration());
        assertEquals(1.5f, params2.expansionFactor(), 0.01f); // 1.0 + 5 * 0.1

        // Test with high velocity
        var highVelocity = new Vector3f(20, 0, 0);
        var params3 = strategy.calculateTBVParameters(bounds, highVelocity);
        assertEquals(60, params3.validityDuration());
        assertEquals(3.0f, params3.expansionFactor(), 0.01f); // 1.0 + 20 * 0.1

        // Test with 3D velocity
        var velocity3D = new Vector3f(3, 4, 0); // Magnitude = 5
        var params4 = strategy.calculateTBVParameters(bounds, velocity3D);
        assertEquals(60, params4.validityDuration());
        assertEquals(1.5f, params4.expansionFactor(), 0.01f); // 1.0 + 5 * 0.1
    }

    @Test
    void testFixedDurationStrategyPresets() {
        // Test default strategy
        var defaultStrategy = FixedDurationTBVStrategy.defaultStrategy();
        assertEquals(60, defaultStrategy.getValidityDuration());
        assertEquals(0.1f, defaultStrategy.getBaseExpansionFactor());
        assertEquals(0.1f, defaultStrategy.getVelocityExpansionMultiplier());

        // Test conservative strategy
        var conservativeStrategy = FixedDurationTBVStrategy.conservativeStrategy(120);
        assertEquals(120, conservativeStrategy.getValidityDuration());
        assertEquals(0.5f, conservativeStrategy.getBaseExpansionFactor());
        assertEquals(0.2f, conservativeStrategy.getVelocityExpansionMultiplier());

        // Test aggressive strategy
        var aggressiveStrategy = FixedDurationTBVStrategy.aggressiveStrategy(30);
        assertEquals(30, aggressiveStrategy.getValidityDuration());
        assertEquals(0.05f, aggressiveStrategy.getBaseExpansionFactor());
        assertEquals(0.05f, aggressiveStrategy.getVelocityExpansionMultiplier());
    }

    @Test
    void testFixedDurationStrategyMinMax() {
        var strategy = new FixedDurationTBVStrategy(90);
        assertEquals(90, strategy.getMinValidityDuration());
        assertEquals(90, strategy.getMaxValidityDuration());
    }

    @Test
    void testFixedDurationStrategyName() {
        var strategy = new FixedDurationTBVStrategy(120);
        assertEquals("FixedDuration[120 frames]", strategy.getName());
    }

    @Test
    void testFixedDurationStrategyToString() {
        var strategy = new FixedDurationTBVStrategy(60, 0.2f, 0.15f);
        var str = strategy.toString();
        assertTrue(str.contains("FixedDurationTBVStrategy"));
        assertTrue(str.contains("duration=60"));
        assertTrue(str.contains("baseExpansion=0.200"));
        assertTrue(str.contains("velocityMultiplier=0.150"));
    }

    @Test
    void testAdaptiveTBVStrategyConstructor() {
        // Test valid construction
        var strategy = new AdaptiveTBVStrategy(30, 300, 10.0f, 0.3f, 0.1f, 0.15f);
        assertEquals(30, strategy.getMinValidityDuration());
        assertEquals(300, strategy.getMaxValidityDuration());
        assertEquals(10.0f, strategy.getVelocityThreshold());
        assertEquals(0.3f, strategy.getSizeInfluenceFactor());
        assertEquals(0.1f, strategy.getBaseExpansionFactor());
        assertEquals(0.15f, strategy.getAdaptiveExpansionRate());

        // Test invalid parameters
        assertThrows(IllegalArgumentException.class, 
            () -> new AdaptiveTBVStrategy(0, 300, 10.0f, 0.3f, 0.1f, 0.15f));
        assertThrows(IllegalArgumentException.class, 
            () -> new AdaptiveTBVStrategy(300, 30, 10.0f, 0.3f, 0.1f, 0.15f));
        assertThrows(IllegalArgumentException.class, 
            () -> new AdaptiveTBVStrategy(30, 300, -10.0f, 0.3f, 0.1f, 0.15f));
        assertThrows(IllegalArgumentException.class, 
            () -> new AdaptiveTBVStrategy(30, 300, 10.0f, -0.1f, 0.1f, 0.15f));
        assertThrows(IllegalArgumentException.class, 
            () -> new AdaptiveTBVStrategy(30, 300, 10.0f, 1.5f, 0.1f, 0.15f));
        assertThrows(IllegalArgumentException.class, 
            () -> new AdaptiveTBVStrategy(30, 300, 10.0f, 0.3f, -0.1f, 0.15f));
        assertThrows(IllegalArgumentException.class, 
            () -> new AdaptiveTBVStrategy(30, 300, 10.0f, 0.3f, 0.1f, -0.15f));
    }

    @Test
    void testAdaptiveTBVStrategyCalculation() {
        var strategy = new AdaptiveTBVStrategy(30, 300, 10.0f, 0.3f, 0.1f, 0.15f);
        
        // Small entity bounds (10x10x10)
        var smallBounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));

        // Test with zero velocity (should get max duration)
        var zeroVelocity = new Vector3f(0, 0, 0);
        var params1 = strategy.calculateTBVParameters(smallBounds, zeroVelocity);
        assertEquals(300, params1.validityDuration()); // Max duration for stationary
        assertEquals(0.6f, params1.expansionFactor(), 0.01f); // 0.1 + 0 + 10*0.05

        // Test with velocity at threshold
        var thresholdVelocity = new Vector3f(10, 0, 0);
        var params2 = strategy.calculateTBVParameters(smallBounds, thresholdVelocity);
        assertEquals(39, params2.validityDuration()); // Min duration * size factor
        assertEquals(2.1f, params2.expansionFactor(), 0.01f); // 0.1 + 10*0.15 + 10*0.05

        // Test with velocity at half threshold
        var halfVelocity = new Vector3f(5, 0, 0);
        var params3 = strategy.calculateTBVParameters(smallBounds, halfVelocity);
        assertTrue(params3.validityDuration() > 30 && params3.validityDuration() < 300);
        assertEquals(1.35f, params3.expansionFactor(), 0.01f); // 0.1 + 5*0.15 + 10*0.05

        // Large entity bounds (100x100x100)
        var largeBounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(100, 100, 100));
        var params4 = strategy.calculateTBVParameters(largeBounds, halfVelocity);
        assertTrue(params4.validityDuration() > params3.validityDuration()); // Larger entities get longer validity
        assertEquals(5.85f, params4.expansionFactor(), 0.01f); // 0.1 + 5*0.15 + 100*0.05
    }

    @Test
    void testAdaptiveTBVStrategyPresets() {
        // Test default strategy
        var defaultStrategy = AdaptiveTBVStrategy.defaultStrategy();
        assertEquals(30, defaultStrategy.getMinValidityDuration());
        assertEquals(300, defaultStrategy.getMaxValidityDuration());
        assertEquals(10.0f, defaultStrategy.getVelocityThreshold());
        assertEquals(0.3f, defaultStrategy.getSizeInfluenceFactor());
        assertEquals(0.1f, defaultStrategy.getBaseExpansionFactor());
        assertEquals(0.15f, defaultStrategy.getAdaptiveExpansionRate());

        // Test static scene strategy
        var staticStrategy = AdaptiveTBVStrategy.staticSceneStrategy();
        assertEquals(60, staticStrategy.getMinValidityDuration());
        assertEquals(600, staticStrategy.getMaxValidityDuration());
        assertEquals(5.0f, staticStrategy.getVelocityThreshold());
        assertEquals(0.5f, staticStrategy.getSizeInfluenceFactor());
        assertEquals(0.05f, staticStrategy.getBaseExpansionFactor());
        assertEquals(0.1f, staticStrategy.getAdaptiveExpansionRate());

        // Test dynamic scene strategy
        var dynamicStrategy = AdaptiveTBVStrategy.dynamicSceneStrategy();
        assertEquals(15, dynamicStrategy.getMinValidityDuration());
        assertEquals(120, dynamicStrategy.getMaxValidityDuration());
        assertEquals(20.0f, dynamicStrategy.getVelocityThreshold());
        assertEquals(0.1f, dynamicStrategy.getSizeInfluenceFactor());
        assertEquals(0.2f, dynamicStrategy.getBaseExpansionFactor());
        assertEquals(0.25f, dynamicStrategy.getAdaptiveExpansionRate());
    }

    @Test
    void testAdaptiveTBVStrategyName() {
        var strategy = new AdaptiveTBVStrategy(15, 150, 10.0f, 0.3f, 0.1f, 0.15f);
        assertEquals("Adaptive[15-150 frames]", strategy.getName());
    }

    @Test
    void testAdaptiveTBVStrategyToString() {
        var strategy = new AdaptiveTBVStrategy(30, 300, 10.0f, 0.3f, 0.1f, 0.15f);
        var str = strategy.toString();
        assertTrue(str.contains("AdaptiveTBVStrategy"));
        assertTrue(str.contains("duration=30-300"));
        assertTrue(str.contains("velocityThreshold=10.00"));
        assertTrue(str.contains("sizeInfluence=0.30"));
        assertTrue(str.contains("baseExpansion=0.100"));
        assertTrue(str.contains("adaptiveRate=0.150"));
    }

    @Test
    void testStrategyComparison() {
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(20, 20, 20));
        var velocity = new Vector3f(8, 0, 0);

        // Compare fixed vs adaptive for same conditions
        var fixedStrategy = new FixedDurationTBVStrategy(60, 0.1f, 0.15f);
        var adaptiveStrategy = AdaptiveTBVStrategy.defaultStrategy();

        var fixedParams = fixedStrategy.calculateTBVParameters(bounds, velocity);
        var adaptiveParams = adaptiveStrategy.calculateTBVParameters(bounds, velocity);

        // Fixed should always return same duration
        assertEquals(60, fixedParams.validityDuration());

        // Adaptive should vary based on velocity
        assertTrue(adaptiveParams.validityDuration() >= 30);
        assertTrue(adaptiveParams.validityDuration() <= 300);

        // Both should have reasonable expansion factors
        assertTrue(fixedParams.expansionFactor() > 0);
        assertTrue(adaptiveParams.expansionFactor() > 0);
    }

    @Test
    void testAdaptiveStrategyEdgeCases() {
        var strategy = new AdaptiveTBVStrategy(30, 300, 10.0f, 0.3f, 0.1f, 0.15f);

        // Test with very large velocity (should clamp to min duration)
        var hugeVelocity = new Vector3f(1000, 0, 0);
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        var params1 = strategy.calculateTBVParameters(bounds, hugeVelocity);
        assertEquals(39, params1.validityDuration()); // 30 * 1.3 size factor

        // Test with tiny bounds
        var tinyBounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(0.1f, 0.1f, 0.1f));
        var params2 = strategy.calculateTBVParameters(tinyBounds, new Vector3f(5, 0, 0));
        assertTrue(params2.validityDuration() >= 30);
        assertTrue(params2.expansionFactor() >= 0.1f);

        // Test with huge bounds
        var hugeBounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(1000, 1000, 1000));
        var params3 = strategy.calculateTBVParameters(hugeBounds, new Vector3f(5, 0, 0));
        assertTrue(params3.validityDuration() <= 300);
        assertTrue(params3.expansionFactor() > 50.0f); // Large size contribution
    }

    @Test
    void testStrategyIntegrationWithTBV() {
        // Test that strategies work correctly when used with TemporalBoundingVolume
        var bounds = new EntityBounds(new Point3f(0, 0, 0), new Point3f(10, 10, 10));
        var velocity = new Vector3f(5, 0, 0);
        var entityId = "TestEntity";
        var creationFrame = 100;

        // Test with fixed strategy
        var fixedStrategy = new FixedDurationTBVStrategy(60);
        var tbv1 = new TemporalBoundingVolume(entityId, bounds, velocity, creationFrame, fixedStrategy);
        assertEquals(60, tbv1.getValidityDuration());

        // Test with adaptive strategy
        var adaptiveStrategy = AdaptiveTBVStrategy.defaultStrategy();
        var tbv2 = new TemporalBoundingVolume(entityId, bounds, velocity, creationFrame, adaptiveStrategy);
        assertTrue(tbv2.getValidityDuration() >= 30 && tbv2.getValidityDuration() <= 300);
    }
}
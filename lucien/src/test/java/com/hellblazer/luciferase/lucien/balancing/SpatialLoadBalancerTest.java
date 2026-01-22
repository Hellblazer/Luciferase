/**
 * Copyright (C) 2026 Hal Hildebrand. All rights reserved.
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
package com.hellblazer.luciferase.lucien.balancing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SpatialLoadBalancer.
 *
 * <p>Validates:
 * <ul>
 *   <li>Load analysis and skew calculation</li>
 *   <li>Imbalance detection (overloaded/underloaded partitions)</li>
 *   <li>Redistribution planning</li>
 *   <li>50%+ skew reduction target</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class SpatialLoadBalancerTest {

    private static final Logger log = LoggerFactory.getLogger(SpatialLoadBalancerTest.class);

    private SpatialLoadBalancer balancer;

    @BeforeEach
    public void setup() {
        balancer = new SpatialLoadBalancer(0.5); // 50% skew reduction target
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testBalancedLoad() {
        log.info("Testing perfectly balanced load distribution");

        var load = new HashMap<Integer, Integer>();
        load.put(0, 100);
        load.put(1, 100);
        load.put(2, 100);
        load.put(3, 100);

        var analysis = balancer.analyzeLoad(load);

        assertEquals(400, analysis.totalLoad(), "Total load should be 400");
        assertEquals(100.0, analysis.meanLoad(), "Mean load should be 100");
        assertEquals(0.0, analysis.skew(), 0.01, "Skew should be ~0 for balanced load");
        assertTrue(analysis.isBalanced(), "Should detect balanced state");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testHighlySkewedLoad() {
        log.info("Testing highly skewed load distribution");

        var load = new HashMap<Integer, Integer>();
        load.put(0, 1000); // Heavily loaded
        load.put(1, 100);
        load.put(2, 100);
        load.put(3, 100);

        var analysis = balancer.analyzeLoad(load);

        assertEquals(1300, analysis.totalLoad(), "Total load should be 1300");
        assertEquals(325.0, analysis.meanLoad(), "Mean load should be 325");
        assertTrue(analysis.skew() > 0, "Skew should be > 0 for imbalanced load");
        assertFalse(analysis.isBalanced(), "Should detect imbalance");
        assertFalse(analysis.overloaded().isEmpty(), "Should find overloaded partitions");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testLoadAnalysisDetectsOverloaded() {
        log.info("Testing detection of overloaded partitions");

        var load = new HashMap<Integer, Integer>();
        load.put(0, 500); // Overloaded
        load.put(1, 200);
        load.put(2, 200);
        load.put(3, 200);

        var analysis = balancer.analyzeLoad(load);

        assertEquals(1, analysis.overloaded().size(), "Should detect 1 overloaded partition");
        assertEquals(0, analysis.overloaded().get(0).partitionIdx(), "Overloaded partition should be 0");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testLoadAnalysisDetectsUnderloaded() {
        log.info("Testing detection of underloaded partitions");

        var load = new HashMap<Integer, Integer>();
        load.put(0, 500);
        load.put(1, 50); // Underloaded
        load.put(2, 500);
        load.put(3, 500);

        var analysis = balancer.analyzeLoad(load);

        assertEquals(1, analysis.underloaded().size(), "Should detect 1 underloaded partition");
        assertEquals(1, analysis.underloaded().get(0).partitionIdx(), "Underloaded partition should be 1");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testPlannedRedistribution() {
        log.info("Testing redistribution plan generation");

        var load = new HashMap<Integer, Integer>();
        load.put(0, 600); // Overloaded
        load.put(1, 100); // Underloaded
        load.put(2, 200);
        load.put(3, 300);

        var plan = balancer.planBalancing(load);

        assertFalse(plan.analysis().isBalanced(), "Should identify imbalance");
        assertTrue(plan.redistributionCount() > 0, "Should generate redistribution plan");
        assertTrue(plan.totalEntitiesRedistributed() > 0, "Should identify entities to redistribute");

        log.info("Plan: {} redistributions, {} entities moved",
                Integer.toString(plan.redistributionCount()), Integer.toString(plan.totalEntitiesRedistributed()));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testSkewReductionValidation() {
        log.info("Testing 50%+ skew reduction target validation");

        var originalLoad = new HashMap<Integer, Integer>();
        originalLoad.put(0, 600); // Heavily skewed
        originalLoad.put(1, 100);
        originalLoad.put(2, 150);
        originalLoad.put(3, 150);

        // Simulated balanced load after redistribution
        var balancedLoad = new HashMap<Integer, Integer>();
        balancedLoad.put(0, 300);
        balancedLoad.put(1, 300);
        balancedLoad.put(2, 300);
        balancedLoad.put(3, 300);

        var validation = balancer.validateBalancing(originalLoad, balancedLoad);

        assertTrue(validation.successful(), "Balancing should reduce skew by 50%+");
        assertTrue(validation.skewReduction() >= 0.5, "Skew reduction should be >= 50%");
        log.info("Validation: original skew={}%, new skew={}%, reduction={}%",
                String.format("%.2f", validation.originalSkew() * 100),
                String.format("%.2f", validation.newSkew() * 100),
                String.format("%.1f", validation.skewReduction() * 100));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testPartialBalancing() {
        log.info("Testing partial load balancing (doesn't achieve 50%+ reduction)");

        var originalLoad = new HashMap<Integer, Integer>();
        originalLoad.put(0, 500);
        originalLoad.put(1, 100);
        originalLoad.put(2, 100);
        originalLoad.put(3, 100);

        // Partial balancing - doesn't fully balance
        var partiallyBalancedLoad = new HashMap<Integer, Integer>();
        partiallyBalancedLoad.put(0, 350);
        partiallyBalancedLoad.put(1, 150);
        partiallyBalancedLoad.put(2, 150);
        partiallyBalancedLoad.put(3, 150);

        var validation = balancer.validateBalancing(originalLoad, partiallyBalancedLoad);

        // Depending on initial skew, might not meet 50% target
        log.info("Partial balance: reduction={}%, successful={}",
                String.format("%.1f", validation.skewReduction() * 100), validation.successful());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testEmptyLoad() {
        log.info("Testing empty load distribution");

        var load = new HashMap<Integer, Integer>();

        var analysis = balancer.analyzeLoad(load);

        assertEquals(0, analysis.totalLoad(), "Total load should be 0");
        assertEquals(0.0, analysis.meanLoad(), "Mean load should be 0");
        assertTrue(analysis.isBalanced(), "Empty load should be considered balanced");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testManyPartitions() {
        log.info("Testing load balancing with many partitions");

        var load = new HashMap<Integer, Integer>();
        // Create 32 partitions with skewed distribution
        for (int i = 0; i < 32; i++) {
            if (i == 0) {
                load.put(i, 3200); // Heavily overloaded
            } else {
                load.put(i, 100);
            }
        }

        var analysis = balancer.analyzeLoad(load);

        log.info("32-partition analysis: mean={}, skew={}%, overloaded={}",
                String.format("%.0f", analysis.meanLoad()), String.format("%.2f", analysis.skew() * 100), analysis.overloaded().size());

        assertTrue(analysis.skew() > 0, "Should detect high skew");
        assertEquals(1, analysis.overloaded().size(), "Should detect single overloaded partition");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testCustomSkewTarget() {
        log.info("Testing custom skew reduction target");

        var strictBalancer = new SpatialLoadBalancer(0.8); // 80% target

        var load = new HashMap<Integer, Integer>();
        load.put(0, 600);
        load.put(1, 100);
        load.put(2, 100);
        load.put(3, 100);

        // Partial balancing
        var partialLoad = new HashMap<Integer, Integer>();
        partialLoad.put(0, 300);
        partialLoad.put(1, 300);
        partialLoad.put(2, 100);
        partialLoad.put(3, 100);

        var validation = strictBalancer.validateBalancing(load, partialLoad);

        log.info("Strict (80%) validation: reduction={}%, successful={}",
                String.format("%.1f", validation.skewReduction() * 100), validation.successful());
    }
}

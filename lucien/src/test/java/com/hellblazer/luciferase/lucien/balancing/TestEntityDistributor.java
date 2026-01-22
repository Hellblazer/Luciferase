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

import com.hellblazer.luciferase.lucien.entity.EntityID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;

/**
 * Generates test entities with various distribution patterns across partitions.
 *
 * <p>Supports distributions:
 * <ul>
 *   <li><b>UNIFORM</b>: Entities evenly spread across spatial domain</li>
 *   <li><b>SKEWED_80_20</b>: 80% of entities in 20% of space</li>
 *   <li><b>BOUNDARY_HEAVY</b>: Entities concentrated at partition boundaries</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class TestEntityDistributor {

    private static final Logger log = LoggerFactory.getLogger(TestEntityDistributor.class);

    private static final float DOMAIN_MIN = 0.0f;
    private static final float DOMAIN_MAX = 2097152.0f;
    private static final float DOMAIN_RANGE = DOMAIN_MAX - DOMAIN_MIN;

    private final Random random;
    private final int partitionCount;

    public TestEntityDistributor(int partitionCount, long seed) {
        this.partitionCount = partitionCount;
        this.random = new Random(seed);
    }

    /**
     * Generate entities with uniform distribution.
     *
     * <p>Entities are evenly spread across the spatial domain and partitions.
     *
     * @param totalEntities total number of entities to generate
     * @return map of partition rank to entity positions
     */
    public Map<Integer, List<Point3f>> generateUniformDistribution(int totalEntities) {
        log.info("Generating uniform distribution of {} entities across {} partitions",
                totalEntities, partitionCount);

        var distribution = new HashMap<Integer, List<Point3f>>();
        for (int i = 0; i < partitionCount; i++) {
            distribution.put(i, new ArrayList<>());
        }

        for (int i = 0; i < totalEntities; i++) {
            var x = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;
            var y = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;
            var z = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;

            int partition = (int) (Math.abs((x + y + z) % partitionCount));
            distribution.get(partition).add(new Point3f(x, y, z));
        }

        logDistributionStats(distribution);
        return distribution;
    }

    /**
     * Generate entities with skewed 80/20 distribution.
     *
     * <p>80% of entities are placed in 20% of the space (one partition cluster),
     * while 20% are scattered uniformly.
     *
     * @param totalEntities total number of entities to generate
     * @return map of partition rank to entity positions
     */
    public Map<Integer, List<Point3f>> generateSkewedDistribution(int totalEntities) {
        log.info("Generating skewed 80/20 distribution of {} entities across {} partitions",
                totalEntities, partitionCount);

        var distribution = new HashMap<Integer, List<Point3f>>();
        for (int i = 0; i < partitionCount; i++) {
            distribution.put(i, new ArrayList<>());
        }

        // 80% in primary partition cluster
        var primaryCluster = totalEntities * 80 / 100;
        for (int i = 0; i < primaryCluster; i++) {
            var x = DOMAIN_MIN + random.nextFloat() * (DOMAIN_RANGE * 0.2f);
            var y = DOMAIN_MIN + random.nextFloat() * (DOMAIN_RANGE * 0.2f);
            var z = DOMAIN_MIN + random.nextFloat() * (DOMAIN_RANGE * 0.2f);
            distribution.get(0).add(new Point3f(x, y, z));
        }

        // 20% scattered uniformly
        for (int i = primaryCluster; i < totalEntities; i++) {
            var x = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;
            var y = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;
            var z = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;
            int partition = (int) (Math.abs((x + y + z) % partitionCount));
            distribution.get(partition).add(new Point3f(x, y, z));
        }

        logDistributionStats(distribution);
        return distribution;
    }

    /**
     * Generate entities with boundary-heavy distribution.
     *
     * <p>Entities are concentrated at partition boundaries to create challenging
     * balancing scenarios with ghost element exchanges.
     *
     * @param totalEntities total number of entities to generate
     * @return map of partition rank to entity positions
     */
    public Map<Integer, List<Point3f>> generateBoundaryHeavyDistribution(int totalEntities) {
        log.info("Generating boundary-heavy distribution of {} entities across {} partitions",
                totalEntities, partitionCount);

        var distribution = new HashMap<Integer, List<Point3f>>();
        for (int i = 0; i < partitionCount; i++) {
            distribution.put(i, new ArrayList<>());
        }

        // Calculate partition boundaries
        float partitionWidth = DOMAIN_RANGE / partitionCount;

        // Place 70% at boundaries between partitions
        var boundaryEntities = totalEntities * 70 / 100;
        for (int i = 0; i < boundaryEntities; i++) {
            int partitionIndex = random.nextInt(partitionCount);
            float boundaryX = DOMAIN_MIN + (partitionIndex + 1) * partitionWidth;
            // Add small jitter around boundary
            boundaryX += (random.nextFloat() - 0.5f) * partitionWidth * 0.1f;

            var y = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;
            var z = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;
            int targetPartition = Math.min(partitionIndex + 1, partitionCount - 1);
            distribution.get(targetPartition).add(new Point3f(boundaryX, y, z));
        }

        // Place 30% uniformly elsewhere
        for (int i = boundaryEntities; i < totalEntities; i++) {
            var x = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;
            var y = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;
            var z = DOMAIN_MIN + random.nextFloat() * DOMAIN_RANGE;
            int partition = (int) (Math.abs((x + y + z) % partitionCount));
            distribution.get(partition).add(new Point3f(x, y, z));
        }

        logDistributionStats(distribution);
        return distribution;
    }

    /**
     * Log distribution statistics for debugging.
     */
    private void logDistributionStats(Map<Integer, List<Point3f>> distribution) {
        int total = distribution.values().stream().mapToInt(List::size).sum();
        distribution.forEach((partition, entities) ->
            log.debug("Partition {}: {} entities ({:.1f}%)",
                     partition, entities.size(), 100.0 * entities.size() / total)
        );
    }
}

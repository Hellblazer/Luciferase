/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.simulation.distributed.integration;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Skewed distribution strategy for load-imbalanced testing.
 * <p>
 * Distributes entities with an 80/20 split:
 * - Heavy bubbles receive 80% of entities
 * - Light bubbles receive 20% of entities
 * <p>
 * Uses deterministic seeding for reproducibility across test runs.
 * Tracks entity counts per bubble for validation.
 * <p>
 * Phase 6B6: 8-Process Scaling & GC Benchmarking
 *
 * @author hal.hildebrand
 */
public class SkewedDistributionStrategy implements EntityDistributionStrategy {

    private final List<UUID> heavyBubbles;
    private final List<UUID> lightBubbles;
    private final Random random;
    private final AtomicInteger[] bubbleEntityCounts;
    private final Map<UUID, Integer> bubbleIndexMap;
    private final double heavyWeight;

    /**
     * Creates a skewed distribution strategy.
     *
     * @param topology         the process topology
     * @param heavyIndices     set of bubble indices that are "heavy" (0-based)
     * @param heavyWeight      weight for heavy bubbles (typically 0.8 for 80%)
     * @param seed             random seed for deterministic distribution
     */
    public SkewedDistributionStrategy(TestProcessTopology topology, Set<Integer> heavyIndices,
                                     double heavyWeight, long seed) {
        this.heavyWeight = heavyWeight;
        this.random = new Random(seed);

        var allBubbles = new ArrayList<>(topology.getAllBubbleIds());
        var heavyBubbleIds = new ArrayList<UUID>();
        var lightBubbleIds = new ArrayList<UUID>();

        bubbleIndexMap = new HashMap<>();
        for (int i = 0; i < allBubbles.size(); i++) {
            bubbleIndexMap.put(allBubbles.get(i), i);
        }

        // Separate bubbles into heavy and light
        for (int i = 0; i < allBubbles.size(); i++) {
            if (heavyIndices.contains(i)) {
                heavyBubbleIds.add(allBubbles.get(i));
            } else {
                lightBubbleIds.add(allBubbles.get(i));
            }
        }

        this.heavyBubbles = Collections.unmodifiableList(heavyBubbleIds);
        this.lightBubbles = Collections.unmodifiableList(lightBubbleIds);
        this.bubbleEntityCounts = new AtomicInteger[allBubbles.size()];

        // Initialize counters
        for (int i = 0; i < bubbleEntityCounts.length; i++) {
            bubbleEntityCounts[i] = new AtomicInteger(0);
        }
    }

    @Override
    public UUID selectBubble(UUID entityId) {
        // Use entity ID hash to determine if heavy or light
        var hashValue = Math.abs(entityId.hashCode());
        var probability = (double)(hashValue % 100) / 100.0;

        UUID selectedBubble;
        if (probability < heavyWeight && !heavyBubbles.isEmpty()) {
            // Select from heavy bubbles
            var index = Math.abs(entityId.hashCode()) % heavyBubbles.size();
            selectedBubble = heavyBubbles.get(index);
        } else if (!lightBubbles.isEmpty()) {
            // Select from light bubbles
            var index = Math.abs(entityId.hashCode()) % lightBubbles.size();
            selectedBubble = lightBubbles.get(index);
        } else if (!heavyBubbles.isEmpty()) {
            // Fallback to heavy if light empty
            var index = Math.abs(entityId.hashCode()) % heavyBubbles.size();
            selectedBubble = heavyBubbles.get(index);
        } else {
            // This shouldn't happen if topology is valid
            throw new IllegalStateException("No bubbles available for distribution");
        }

        // Track the count
        var bubbleIndex = bubbleIndexMap.get(selectedBubble);
        bubbleEntityCounts[bubbleIndex].incrementAndGet();

        return selectedBubble;
    }

    /**
     * Gets the entity count for a specific bubble.
     *
     * @param bubbleId the bubble UUID
     * @return entity count in the bubble
     */
    public int getEntityCount(UUID bubbleId) {
        var index = bubbleIndexMap.get(bubbleId);
        return index != null ? bubbleEntityCounts[index].get() : 0;
    }

    /**
     * Gets the total entity count across all bubbles.
     *
     * @return total entity count
     */
    public int getTotalEntityCount() {
        var total = 0;
        for (var counter : bubbleEntityCounts) {
            total += counter.get();
        }
        return total;
    }

    /**
     * Gets the number of heavy bubbles.
     *
     * @return heavy bubble count
     */
    public int getHeavyBubbleCount() {
        return heavyBubbles.size();
    }

    /**
     * Gets the number of light bubbles.
     *
     * @return light bubble count
     */
    public int getLightBubbleCount() {
        return lightBubbles.size();
    }

    /**
     * Gets the distribution statistics.
     *
     * @return distribution stats
     */
    public DistributionStats getStats() {
        var heavySum = heavyBubbles.stream()
            .mapToInt(this::getEntityCount)
            .sum();
        var lightSum = lightBubbles.stream()
            .mapToInt(this::getEntityCount)
            .sum();
        var total = heavySum + lightSum;

        var heavyAvg = heavyBubbles.isEmpty() ? 0 : (double)heavySum / heavyBubbles.size();
        var lightAvg = lightBubbles.isEmpty() ? 0 : (double)lightSum / lightBubbles.size();

        return new DistributionStats(total, heavySum, lightSum, heavyAvg, lightAvg);
    }

    /**
     * Distribution statistics record.
     */
    public record DistributionStats(int total, int heavyTotal, int lightTotal,
                                   double heavyAverage, double lightAverage) {
    }
}

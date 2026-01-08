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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin distribution strategy that cycles through bubbles evenly.
 * <p>
 * Ensures balanced distribution by assigning entities to bubbles in
 * sequential order, cycling back to the first bubble after the last.
 * <p>
 * Phase 6B5.3: Entity Distribution & Initialization
 *
 * @author hal.hildebrand
 */
public class RoundRobinDistributionStrategy implements EntityDistributionStrategy {

    private final List<UUID> bubbleIds;
    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * Creates a round-robin strategy for the given topology.
     *
     * @param topology the process topology
     */
    public RoundRobinDistributionStrategy(TestProcessTopology topology) {
        this.bubbleIds = new ArrayList<>(topology.getAllBubbleIds());
    }

    @Override
    public UUID selectBubble(UUID entityId) {
        var index = counter.getAndIncrement() % bubbleIds.size();
        return bubbleIds.get(index);
    }
}

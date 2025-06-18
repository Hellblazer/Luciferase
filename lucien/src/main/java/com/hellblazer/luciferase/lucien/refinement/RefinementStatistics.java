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
package com.hellblazer.luciferase.lucien.refinement;

/**
 * Statistics tracking for refinement operations.
 *
 * @author hal.hildebrand
 */
public class RefinementStatistics {
    private long nodesRefined = 0;
    private long nodesCoarsened = 0;
    private long nodesVisited = 0;
    private long entitiesRelocated = 0;
    private long startTime;
    private long endTime;
    private int maxLevelReached = 0;
    private int minLevelReached = Integer.MAX_VALUE;

    /**
     * Start tracking refinement operation.
     */
    public void startOperation() {
        this.startTime = System.currentTimeMillis();
    }

    /**
     * End tracking refinement operation.
     */
    public void endOperation() {
        this.endTime = System.currentTimeMillis();
    }

    /**
     * Record a node refinement.
     */
    public void recordRefinement(int level) {
        nodesRefined++;
        updateLevelStats(level);
    }

    /**
     * Record a node coarsening.
     */
    public void recordCoarsening(int level) {
        nodesCoarsened++;
        updateLevelStats(level);
    }

    /**
     * Record a node visit.
     */
    public void recordVisit() {
        nodesVisited++;
    }

    /**
     * Record entity relocation.
     */
    public void recordEntityRelocation(int count) {
        entitiesRelocated += count;
    }

    private void updateLevelStats(int level) {
        maxLevelReached = Math.max(maxLevelReached, level);
        minLevelReached = Math.min(minLevelReached, level);
    }

    // Getters
    public long getNodesRefined() {
        return nodesRefined;
    }

    public long getNodesCoarsened() {
        return nodesCoarsened;
    }

    public long getNodesVisited() {
        return nodesVisited;
    }

    public long getEntitiesRelocated() {
        return entitiesRelocated;
    }

    public long getOperationTimeMillis() {
        return endTime - startTime;
    }

    public int getMaxLevelReached() {
        return maxLevelReached;
    }

    public int getMinLevelReached() {
        return minLevelReached == Integer.MAX_VALUE ? 0 : minLevelReached;
    }

    /**
     * Get a summary string of the statistics.
     */
    public String getSummary() {
        return String.format(
            "Refinement Statistics:\n" +
            "  Nodes refined: %d\n" +
            "  Nodes coarsened: %d\n" +
            "  Nodes visited: %d\n" +
            "  Entities relocated: %d\n" +
            "  Level range: %d-%d\n" +
            "  Time: %d ms",
            nodesRefined, nodesCoarsened, nodesVisited, entitiesRelocated,
            getMinLevelReached(), maxLevelReached, getOperationTimeMillis()
        );
    }
}
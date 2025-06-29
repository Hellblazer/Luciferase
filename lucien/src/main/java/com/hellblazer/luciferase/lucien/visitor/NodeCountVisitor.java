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
package com.hellblazer.luciferase.lucien.visitor;

import com.hellblazer.luciferase.lucien.SpatialIndex.SpatialNode;
import com.hellblazer.luciferase.lucien.SpatialKey;
import com.hellblazer.luciferase.lucien.entity.EntityID;

import java.util.HashMap;
import java.util.Map;

/**
 * Visitor that counts nodes and entities at each level of the tree.
 *
 * @param <Key>     The type of spatial key used in the index
 * @param <ID>      The type of EntityID used for entity identification
 * @param <Content> The type of content stored with each entity
 * @author hal.hildebrand
 */
public class NodeCountVisitor<Key extends SpatialKey<Key>, ID extends EntityID, Content>
extends AbstractTreeVisitor<Key, ID, Content> {

    private final Map<Integer, Integer> nodesPerLevel    = new HashMap<>();
    private final Map<Integer, Integer> entitiesPerLevel = new HashMap<>();
    private       int                   totalNodes       = 0;
    private       int                   totalEntities    = 0;
    private       int                   maxLevelObserved = 0;

    /**
     * Get the number of entities at a specific level.
     *
     * @param level the level to query
     * @return number of entities at that level
     */
    public int getEntitiesAtLevel(int level) {
        return entitiesPerLevel.getOrDefault(level, 0);
    }

    /**
     * Get the maximum level observed.
     *
     * @return maximum level
     */
    public int getMaxLevelObserved() {
        return maxLevelObserved;
    }

    /**
     * Get the number of nodes at a specific level.
     *
     * @param level the level to query
     * @return number of nodes at that level
     */
    public int getNodesAtLevel(int level) {
        return nodesPerLevel.getOrDefault(level, 0);
    }

    /**
     * Get statistics as a formatted string.
     *
     * @return formatted statistics
     */
    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tree Statistics:\n");
        sb.append("  Total nodes: ").append(totalNodes).append("\n");
        sb.append("  Total entities: ").append(totalEntities).append("\n");
        sb.append("  Max level: ").append(maxLevelObserved).append("\n");
        sb.append("  Nodes per level:\n");

        for (int level = 0; level <= maxLevelObserved; level++) {
            int nodes = getNodesAtLevel(level);
            int entities = getEntitiesAtLevel(level);
            if (nodes > 0) {
                sb.append("    Level ")
                  .append(level)
                  .append(": ")
                  .append(nodes)
                  .append(" nodes, ")
                  .append(entities)
                  .append(" entities")
                  .append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Get total number of entities encountered.
     *
     * @return total entity count
     */
    public int getTotalEntities() {
        return totalEntities;
    }

    /**
     * Get total number of nodes visited.
     *
     * @return total node count
     */
    public int getTotalNodes() {
        return totalNodes;
    }

    /**
     * Reset all counters.
     */
    public void reset() {
        nodesPerLevel.clear();
        entitiesPerLevel.clear();
        totalNodes = 0;
        totalEntities = 0;
        maxLevelObserved = 0;
    }

    @Override
    public boolean visitNode(SpatialNode<Key, ID> node, int level, Key parentIndex) {
        totalNodes++;
        nodesPerLevel.merge(level, 1, Integer::sum);
        entitiesPerLevel.merge(level, node.entityIds().size(), Integer::sum);
        totalEntities += node.entityIds().size();
        maxLevelObserved = Math.max(maxLevelObserved, level);
        return true;
    }
}

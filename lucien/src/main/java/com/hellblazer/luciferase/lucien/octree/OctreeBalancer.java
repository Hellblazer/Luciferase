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
package com.hellblazer.luciferase.lucien.octree;

import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.geometry.MortonCurve;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityManager;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancingStrategy;
import com.hellblazer.luciferase.lucien.SpatialNodeImpl;

import java.util.*;

/**
 * Octree-specific tree balancer implementation.
 * 
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class OctreeBalancer<ID extends EntityID> implements TreeBalancer<MortonKey, ID> {
    
    private final Octree<ID, ?> octree;
    private final EntityManager<MortonKey, ID, ?> entityManager;
    private final byte maxDepth;
    private final int maxEntitiesPerNode;
    private TreeBalancingStrategy<ID> balancingStrategy;
    private boolean autoBalancingEnabled = false;
    
    public OctreeBalancer(Octree<ID, ?> octree, EntityManager<MortonKey, ID, ?> entityManager, 
                          byte maxDepth, int maxEntitiesPerNode) {
        this.octree = octree;
        this.entityManager = entityManager;
        this.maxDepth = maxDepth;
        this.maxEntitiesPerNode = maxEntitiesPerNode;
        this.balancingStrategy = new com.hellblazer.luciferase.lucien.balancing.DefaultBalancingStrategy<>();
    }

    @Override
    public TreeBalancer.BalancingAction checkNodeBalance(MortonKey nodeIndex) {
        var node = octree.getSpatialIndex().get(nodeIndex);
        if (node == null) {
            return TreeBalancer.BalancingAction.NONE;
        }

        var level = nodeIndex.getLevel();
        var entityCount = node.getEntityCount();

        // Check split condition
        if (balancingStrategy.shouldSplit(entityCount, level, maxEntitiesPerNode)) {
            return TreeBalancer.BalancingAction.SPLIT;
        }

        // Check merge condition
        var siblingCounts = getSiblingEntityCounts(nodeIndex);
        if (balancingStrategy.shouldMerge(entityCount, level, siblingCounts.stream().mapToInt(Integer::intValue).toArray())) {
            return TreeBalancer.BalancingAction.MERGE;
        }

        return TreeBalancer.BalancingAction.NONE;
    }

    @Override
    public TreeBalancingStrategy.TreeBalancingStats getBalancingStats() {
        // Calculate stats from current tree state
        var spatialIndex = octree.getSpatialIndex();
        int totalNodes = spatialIndex.size();
        int emptyNodes = 0;
        int underpopulatedNodes = 0;
        int overpopulatedNodes = 0;
        double totalLoad = 0;
        int maxDepthFound = 0;
        
        for (var entry : spatialIndex.entrySet()) {
            var node = entry.getValue();
            var level = entry.getKey().getLevel();
            var entityCount = node.getEntityCount();
            
            totalLoad += entityCount;
            maxDepthFound = Math.max(maxDepthFound, level);
            
            if (entityCount == 0) {
                emptyNodes++;
            } else if (entityCount < balancingStrategy.getMergeThreshold(level, maxEntitiesPerNode)) {
                underpopulatedNodes++;
            } else if (entityCount > balancingStrategy.getSplitThreshold(level, maxEntitiesPerNode)) {
                overpopulatedNodes++;
            }
        }
        
        double averageLoad = totalNodes > 0 ? totalLoad / totalNodes : 0;
        
        // Calculate variance
        double variance = 0;
        if (totalNodes > 0) {
            for (var node : spatialIndex.values()) {
                var diff = node.getEntityCount() - averageLoad;
                variance += diff * diff;
            }
            variance /= totalNodes;
        }
        
        return new TreeBalancingStrategy.TreeBalancingStats(
            totalNodes, underpopulatedNodes, overpopulatedNodes, emptyNodes,
            maxDepthFound, averageLoad, variance
        );
    }

    @Override
    public boolean isAutoBalancingEnabled() {
        return autoBalancingEnabled;
    }

    @Override
    public boolean mergeNodes(Set<MortonKey> nodeIndices, MortonKey parentIndex) {
        if (nodeIndices.isEmpty()) {
            return false;
        }

        // Collect all entities from nodes to be merged
        var allEntities = new HashSet<ID>();
        for (var nodeIndex : nodeIndices) {
            var node = octree.getSpatialIndex().get(nodeIndex);
            if (node != null && !node.isEmpty()) {
                allEntities.addAll(node.getEntityIds());
            }
        }

        if (allEntities.isEmpty()) {
            // Just remove empty nodes
            for (var nodeIndex : nodeIndices) {
                octree.getSpatialIndex().remove(nodeIndex);
                octree.getSortedSpatialIndices().remove(nodeIndex);
            }
            return true;
        }

        // Get or create parent node
        var parentNode = octree.getSpatialIndex().computeIfAbsent(parentIndex, k -> {
            octree.getSortedSpatialIndices().add(parentIndex);
            return new SpatialNodeImpl<>(maxEntitiesPerNode);
        });

        // Add all entities to parent
        for (var entityId : allEntities) {
            parentNode.addEntity(entityId);
            entityManager.addEntityLocation(entityId, parentIndex);
        }

        // Remove child nodes
        for (var nodeIndex : nodeIndices) {
            octree.getSpatialIndex().remove(nodeIndex);
            octree.getSortedSpatialIndices().remove(nodeIndex);
            for (var entityId : allEntities) {
                entityManager.removeEntityLocation(entityId, nodeIndex);
            }
        }

        // Record merge operation if strategy supports it
        return true;
    }

    @Override
    public int rebalanceSubtree(MortonKey rootNodeIndex) {
        // Not implemented for octree - could recursively balance subtree
        return 0;
    }

    @Override
    public TreeBalancer.RebalancingResult rebalanceTree() {
        var startTime = System.nanoTime();
        var nodesCreated = 0;
        var nodesRemoved = 0;
        var nodesMerged = 0;
        var nodesSplit = 0;
        var entitiesRelocated = 0;

        try {
            // Get current nodes (snapshot to avoid concurrent modification)
            var currentNodes = new ArrayList<>(octree.getSpatialIndex().keySet());

            // Check each node for balance violations
            var needsSplit = new ArrayList<MortonKey>();

            for (var nodeKey : currentNodes) {
                var action = checkNodeBalance(nodeKey);
                if (action == TreeBalancer.BalancingAction.SPLIT) {
                    needsSplit.add(nodeKey);
                }
                // Note: MERGE operations are handled separately and not part of Phase 1
            }

            // Perform splits (creates finer granularity for overpopulated nodes)
            for (var nodeKey : needsSplit) {
                var childrenCreated = splitNode(nodeKey, nodeKey.getLevel());
                if (!childrenCreated.isEmpty()) {
                    nodesSplit++;
                    nodesCreated += childrenCreated.size();

                    // Count entities relocated
                    var node = octree.getSpatialIndex().get(nodeKey);
                    if (node != null) {
                        entitiesRelocated += node.getEntityCount();
                    }
                }
            }

            var timeTaken = System.nanoTime() - startTime;
            return new TreeBalancer.RebalancingResult(
                nodesCreated,
                nodesRemoved,
                nodesMerged,
                nodesSplit,
                entitiesRelocated,
                timeTaken,
                true
            );

        } catch (Exception e) {
            var timeTaken = System.nanoTime() - startTime;
            return new TreeBalancer.RebalancingResult(0, 0, 0, 0, 0, timeTaken, false);
        }
    }

    @Override
    public void setAutoBalancingEnabled(boolean enabled) {
        this.autoBalancingEnabled = enabled;
    }

    @Override
    public void setBalancingStrategy(TreeBalancingStrategy<ID> strategy) {
        this.balancingStrategy = strategy;
    }

    @Override
    public List<MortonKey> splitNode(MortonKey nodeIndex, byte nodeLevel) {
        if (nodeLevel >= maxDepth) {
            return Collections.emptyList();
        }

        var node = octree.getSpatialIndex().get(nodeIndex);
        if (node == null || node.isEmpty()) {
            return Collections.emptyList();
        }

        // Get entities to redistribute
        var entities = new HashSet<>(node.getEntityIds());

        // Calculate child coordinates
        var parentCoords = MortonCurve.decode(nodeIndex.getMortonCode());
        var parentCellSize = Constants.lengthAtLevel(nodeLevel);
        var childCellSize = parentCellSize / 2;
        var childLevel = (byte) (nodeLevel + 1);

        // Create child nodes
        var createdChildren = new ArrayList<MortonKey>();
        var childEntityMap = new HashMap<MortonKey, Set<ID>>();

        // Distribute entities to children based on their positions
        for (var entityId : entities) {
            var pos = entityManager.getEntityPosition(entityId);
            if (pos == null) {
                continue;
            }

            // Determine which child octant this entity belongs to
            var octant = 0;
            if (pos.x >= parentCoords[0] + childCellSize) {
                octant |= 1;
            }
            if (pos.y >= parentCoords[1] + childCellSize) {
                octant |= 2;
            }
            if (pos.z >= parentCoords[2] + childCellSize) {
                octant |= 4;
            }

            var childX = parentCoords[0] + ((octant & 1) != 0 ? childCellSize : 0);
            var childY = parentCoords[1] + ((octant & 2) != 0 ? childCellSize : 0);
            var childZ = parentCoords[2] + ((octant & 4) != 0 ? childCellSize : 0);

            var childIndex = new MortonKey(MortonCurve.encode(childX, childY, childZ), (byte) (nodeLevel + 1));
            childEntityMap.computeIfAbsent(childIndex, k -> new HashSet<>()).add(entityId);
        }

        // Check if all entities map to the same child - if so, don't split
        if (childEntityMap.size() == 1 && childEntityMap.containsKey(nodeIndex)) {
            // All entities map to the same cell as the parent - splitting won't help
            return Collections.emptyList();
        }

        // Create child nodes and add entities
        for (var entry : childEntityMap.entrySet()) {
            var childIndex = entry.getKey();
            var childEntities = entry.getValue();

            if (!childEntities.isEmpty()) {
                var childNode = octree.getSpatialIndex().computeIfAbsent(childIndex, k -> {
                    octree.getSortedSpatialIndices().add(childIndex);
                    return new SpatialNodeImpl<>(maxEntitiesPerNode);
                });

                for (var entityId : childEntities) {
                    childNode.addEntity(entityId);
                    entityManager.addEntityLocation(entityId, childIndex);
                }

                createdChildren.add(childIndex);
            }
        }

        // Clear parent node and update entity locations
        node.clearEntities();
        node.setHasChildren(true);
        for (var entityId : entities) {
            entityManager.removeEntityLocation(entityId, nodeIndex);
        }

        return createdChildren;
    }

    protected Set<MortonKey> findSiblings(MortonKey nodeIndex) {
        var level = nodeIndex.getLevel();
        if (level == 0) {
            return Collections.emptySet(); // Root has no siblings
        }

        // Calculate parent coordinates
        var coords = MortonCurve.decode(nodeIndex.getMortonCode());
        var cellSize = Constants.lengthAtLevel(level);
        var parentCellSize = cellSize * 2;

        // Find parent cell coordinates
        var parentX = (coords[0] / parentCellSize) * parentCellSize;
        var parentY = (coords[1] / parentCellSize) * parentCellSize;
        var parentZ = (coords[2] / parentCellSize) * parentCellSize;

        var siblings = new HashSet<MortonKey>();

        // Check all 8 children of the parent
        for (var dx = 0; dx < 2; dx++) {
            for (var dy = 0; dy < 2; dy++) {
                for (var dz = 0; dz < 2; dz++) {
                    var siblingX = parentX + dx * cellSize;
                    var siblingY = parentY + dy * cellSize;
                    var siblingZ = parentZ + dz * cellSize;

                    var siblingIndex = new MortonKey(MortonCurve.encode(siblingX, siblingY, siblingZ), level);

                    // Add if it's not the current node and exists
                    if (!siblingIndex.equals(nodeIndex) && octree.getSpatialIndex().containsKey(siblingIndex)) {
                        siblings.add(siblingIndex);
                    }
                }
            }
        }

        return siblings;
    }


    
    private List<Integer> getSiblingEntityCounts(MortonKey nodeIndex) {
        var siblings = findSiblings(nodeIndex);
        var counts = new ArrayList<Integer>();
        
        for (var sibling : siblings) {
            var siblingNode = octree.getSpatialIndex().get(sibling);
            if (siblingNode != null) {
                counts.add(siblingNode.getEntityCount());
            }
        }
        
        return counts;
    }
}
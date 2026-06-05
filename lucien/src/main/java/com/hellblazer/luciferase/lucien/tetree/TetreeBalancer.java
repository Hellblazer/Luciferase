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
package com.hellblazer.luciferase.lucien.tetree;

import com.hellblazer.luciferase.lucien.SpatialNodeImpl;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.EntityManager;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancer;
import com.hellblazer.luciferase.lucien.balancing.TreeBalancingStrategy;

import java.util.*;

/**
 * Tetree-specific tree balancer implementation.
 * 
 * @param <ID> The type of EntityID used
 * @author hal.hildebrand
 */
public class TetreeBalancer<ID extends EntityID> implements TreeBalancer<TetreeKey<? extends TetreeKey<?>>, ID> {
    
    private final Tetree<ID, ?> tetree;
    private final EntityManager<TetreeKey<? extends TetreeKey<?>>, ID, ?> entityManager;
    private final byte maxDepth;
    private final int maxEntitiesPerNode;
    private TreeBalancingStrategy<ID> balancingStrategy;
    private boolean autoBalancingEnabled = false;
    
    public TetreeBalancer(Tetree<ID, ?> tetree, EntityManager<TetreeKey<? extends TetreeKey<?>>, ID, ?> entityManager, 
                          byte maxDepth, int maxEntitiesPerNode) {
        this.tetree = tetree;
        this.entityManager = entityManager;
        this.maxDepth = maxDepth;
        this.maxEntitiesPerNode = maxEntitiesPerNode;
        this.balancingStrategy = new com.hellblazer.luciferase.lucien.balancing.DefaultBalancingStrategy<>();
    }

    @Override
    public TreeBalancer.BalancingAction checkNodeBalance(TetreeKey<? extends TetreeKey<?>> nodeIndex) {
        var node = tetree.getSpatialIndex().get(nodeIndex);
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
        var spatialIndex = tetree.getSpatialIndex();
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
    public boolean mergeNodes(Set<TetreeKey<? extends TetreeKey<?>>> tetIndices,
                              TetreeKey<? extends TetreeKey<?>> parentIndex) {
        if (tetIndices.isEmpty()) {
            return false;
        }

        // Collect all entities from nodes to be merged
        Set<ID> allEntities = new HashSet<>();
        for (TetreeKey<? extends TetreeKey<?>> tetIndex : tetIndices) {
            SpatialNodeImpl<ID> node = tetree.getSpatialIndex().get(tetIndex);
            if (node != null && !node.isEmpty()) {
                allEntities.addAll(node.getEntityIds());
            }
        }

        if (allEntities.isEmpty()) {
            // Just remove empty nodes
            for (TetreeKey<? extends TetreeKey<?>> tetIndex : tetIndices) {
                tetree.getSpatialIndex().remove(tetIndex);
                tetree.getSortedSpatialIndices().remove(tetIndex);
            }
            return true;
        }

        // Get or create parent node
        SpatialNodeImpl<ID> parentNode = tetree.getSpatialIndex().computeIfAbsent(parentIndex, k -> {
            tetree.getSortedSpatialIndices().add(parentIndex);
            return new SpatialNodeImpl<>(maxEntitiesPerNode);
        });

        // Add all entities to parent
        for (ID entityId : allEntities) {
            parentNode.addEntity(entityId);
            entityManager.addEntityLocation(entityId, parentIndex);
        }

        // Remove child nodes
        for (TetreeKey<? extends TetreeKey<?>> tetIndex : tetIndices) {
            tetree.getSpatialIndex().remove(tetIndex);
            tetree.getSortedSpatialIndices().remove(tetIndex);
            for (ID entityId : allEntities) {
                entityManager.removeEntityLocation(entityId, tetIndex);
            }
        }

        // Record merge operation if strategy supports it
        return true;
    }

    /**
     * Rebalance the subtree rooted at {@code rootNodeIndex}.
     *
     * <p>Returns the number of <em>logical node operations</em> performed in this pass.
     * The unit is consistent across both operation types:
     * <ul>
     *   <li>SPLIT: +1 (one split of a parent, regardless of how many children were created).
     *       After a successful split the now-empty parent is removed from the spatial index —
     *       all entities have been redistributed to children and {@code getChildNodesInIndex}
     *       probes child tet keys directly (it does not rely on the parent key being present).</li>
     *   <li>MERGE: +1 (one merge collapse of N siblings into their parent).</li>
     * </ul>
     * A return of 0 means "no operations were necessary" (tree is already balanced in this
     * subtree), not "unsupported". Callers can use non-zero as the convergence signal in an
     * outer loop.
     */
    @Override
    public int rebalanceSubtree(TetreeKey<? extends TetreeKey<?>> rootNodeIndex) {
        return rebalanceSubtreeImpl(rootNodeIndex, new HashSet<>());
    }

    private int rebalanceSubtreeImpl(TetreeKey<? extends TetreeKey<?>> rootNodeIndex, Set<TetreeKey<? extends TetreeKey<?>>> visited) {
        if (!visited.add(rootNodeIndex)) {
            return 0;
        }

        int modifications = 0;

        var node = tetree.getSpatialIndex().get(rootNodeIndex);
        if (node == null) {
            return 0;
        }

        var level = rootNodeIndex.getLevel();

        var action = checkNodeBalance(rootNodeIndex);
        switch (action) {
            case SPLIT -> {
                if (level < maxDepth) {
                    var children = splitNode(rootNodeIndex, level);
                    if (!children.isEmpty()) {
                        // +1 logical split operation (unit: one node operation, not child count)
                        modifications++;
                        // splitNode redistributed all parent entities into children and
                        // cleared the parent node; remove the now-empty parent so it doesn't
                        // persist in the spatial index inflating traversals and stats.
                        tetree.getSpatialIndex().remove(rootNodeIndex);
                        tetree.getSortedSpatialIndices().remove(rootNodeIndex);
                        for (var child : children) {
                            modifications += rebalanceSubtreeImpl(child, visited);
                        }
                    }
                }
            }
            case MERGE -> {
                var siblings = findSiblings(rootNodeIndex);
                if (!siblings.isEmpty()) {
                    Tet tet = Tet.tetrahedron(rootNodeIndex);
                    TetreeKey<? extends TetreeKey<?>> parentIndex = tet.parent().tmIndex();
                    var allNodes = new HashSet<>(siblings);
                    allNodes.add(rootNodeIndex);
                    if (mergeNodes(allNodes, parentIndex)) {
                        // +1 logical merge operation
                        modifications++;
                        visited.addAll(allNodes);
                    }
                }
            }
            default -> {
                // NONE or REDISTRIBUTE: recurse into children present in the index
                var children = getChildNodesInIndex(rootNodeIndex);
                for (var child : children) {
                    modifications += rebalanceSubtreeImpl(child, visited);
                }
            }
        }

        return modifications;
    }

    /**
     * Find child tet nodes of the given node that exist in the spatial index.
     */
    private List<TetreeKey<? extends TetreeKey<?>>> getChildNodesInIndex(TetreeKey<? extends TetreeKey<?>> nodeIndex) {
        var level = nodeIndex.getLevel();
        if (level >= maxDepth) {
            return Collections.emptyList();
        }

        Tet tet = Tet.tetrahedron(nodeIndex);
        List<TetreeKey<? extends TetreeKey<?>>> children = new ArrayList<>();
        // Loop index 0-7 is always valid (no IllegalArgumentException from child()).
        // The level < maxDepth guard above prevents IllegalStateException from max-depth refinement.
        // Any other exception here is a genuine bug and must propagate, not be silently swallowed.
        for (int i = 0; i < 8; i++) {
            var childTet = tet.child(i);
            var childIndex = childTet.tmIndex();
            if (tetree.getSpatialIndex().containsKey(childIndex)) {
                children.add(childIndex);
            }
        }
        return children;
    }

    @Override
    public TreeBalancer.RebalancingResult rebalanceTree() {
        // Not implemented for tetree
        return new TreeBalancer.RebalancingResult(0, 0, 0, 0, 0, 0, true);
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
    public List<TetreeKey<? extends TetreeKey<?>>> splitNode(TetreeKey<? extends TetreeKey<?>> tetIndex, byte nodeLevel) {
        if (nodeLevel >= maxDepth) {
            return Collections.emptyList();
        }

        SpatialNodeImpl<ID> node = tetree.getSpatialIndex().get(tetIndex);
        if (node == null || node.isEmpty()) {
            return Collections.emptyList();
        }

        // Get entities to redistribute
        Set<ID> entities = new HashSet<>(node.getEntityIds());

        // Get parent tetrahedron
        Tet parentTet = Tet.tetrahedron(tetIndex);

        // Create child nodes
        List<TetreeKey<? extends TetreeKey<?>>> createdChildren = new ArrayList<>();
        Map<TetreeKey<? extends TetreeKey<?>>, Set<ID>> childEntityMap = new HashMap<>();

        // Distribute entities to children based on their positions
        for (ID entityId : entities) {
            var pos = entityManager.getEntityPosition(entityId);
            if (pos == null) {
                continue;
            }

            // Find which child contains this position
            for (int i = 0; i < 8; i++) {
                try {
                    Tet child = parentTet.child(i);
                    if (child.contains(pos)) {
                        TetreeKey<? extends TetreeKey<?>> childIndex = child.tmIndex();
                        childEntityMap.computeIfAbsent(childIndex, k -> new HashSet<>()).add(entityId);
                        break; // Entity placed in correct child
                    }
                } catch (Exception e) {
                    // Skip invalid children
                }
            }
        }

        // Check if all entities map to the same child - if so, don't split
        if (childEntityMap.size() <= 1) {
            // All entities map to the same cell as the parent - splitting won't help
            return Collections.emptyList();
        }

        // Create child nodes and add entities
        for (Map.Entry<TetreeKey<? extends TetreeKey<?>>, Set<ID>> entry : childEntityMap.entrySet()) {
            TetreeKey<? extends TetreeKey<?>> childIndex = entry.getKey();
            Set<ID> childEntities = entry.getValue();

            if (!childEntities.isEmpty()) {
                SpatialNodeImpl<ID> childNode = tetree.getSpatialIndex().computeIfAbsent(childIndex, k -> {
                    tetree.getSortedSpatialIndices().add(childIndex);
                    return new SpatialNodeImpl<>(maxEntitiesPerNode);
                });

                for (ID entityId : childEntities) {
                    childNode.addEntity(entityId);
                    entityManager.addEntityLocation(entityId, childIndex);
                }

                createdChildren.add(childIndex);
            }
        }

        // Clear parent node and update entity locations
        node.clearEntities();

        // Mark parent node as having children
        for (TetreeKey<? extends TetreeKey<?>> childTetIndex : createdChildren) {
            for (int i = 0; i < 8; i++) {
                try {
                    Tet expectedChild = parentTet.child(i);
                    if (expectedChild.tmIndex() == childTetIndex) {
                        node.setChildBit(i);
                        break;
                    }
                } catch (Exception e) {
                    // Skip invalid children
                }
            }
        }

        for (ID entityId : entities) {
            entityManager.removeEntityLocation(entityId, tetIndex);
        }

        return createdChildren;
    }

    protected Set<TetreeKey<? extends TetreeKey<?>>> findSiblings(TetreeKey<? extends TetreeKey<?>> tetIndex) {
        Tet tet = Tet.tetrahedron(tetIndex);
        if (tet.l() == 0) {
            return Collections.emptySet(); // Root has no siblings
        }

        // Use the t8code-compliant TetreeFamily algorithm for finding siblings
        Tet[] siblings = TetreeFamily.getSiblings(tet);
        Set<TetreeKey<? extends TetreeKey<?>>> result = new HashSet<>();

        for (Tet sibling : siblings) {
            TetreeKey<? extends TetreeKey<?>> siblingIndex = sibling.tmIndex();
            // Add if it's not the current node and exists in the spatial index
            if (siblingIndex != tetIndex && tetree.getSpatialIndex().containsKey(siblingIndex)) {
                result.add(siblingIndex);
            }
        }

        return result;
    }

    private List<Integer> getSiblingEntityCounts(TetreeKey<? extends TetreeKey<?>> nodeIndex) {
        var siblings = findSiblings(nodeIndex);
        var counts = new ArrayList<Integer>();
        
        for (var sibling : siblings) {
            var siblingNode = tetree.getSpatialIndex().get(sibling);
            if (siblingNode != null) {
                counts.add(siblingNode.getEntityCount());
            }
        }
        
        return counts;
    }
}
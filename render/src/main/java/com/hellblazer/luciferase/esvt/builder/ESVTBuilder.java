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
package com.hellblazer.luciferase.esvt.builder;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Builds ESVT GPU-ready data structure from a Tetree spatial index.
 *
 * The builder performs breadth-first traversal of the Tetree to allocate nodes
 * in memory order suitable for GPU cache efficiency. Each Tetree node is converted
 * to an 8-byte ESVTNodeUnified with:
 * - Child mask (8 bits for Bey 8-way subdivision)
 * - Leaf mask (8 bits)
 * - Child pointer (14 bits, relative offset)
 * - Tetrahedron type (3 bits, 0-5 for S0-S5)
 *
 * The builder handles:
 * - Node allocation in breadth-first order
 * - Child pointer computation
 * - Type propagation from parent to children
 * - Sparse child indexing using popcount
 *
 * @author hal.hildebrand
 */
public class ESVTBuilder {

    private static final Logger log = LoggerFactory.getLogger(ESVTBuilder.class);

    /**
     * Build ESVT data from a Tetree.
     *
     * @param tetree The source Tetree spatial index
     * @param <ID> Entity ID type
     * @param <Content> Content type
     * @return ESVTData ready for GPU transfer
     */
    public <ID extends EntityID, Content> ESVTData build(Tetree<ID, Content> tetree) {
        log.debug("Building ESVT from Tetree with {} nodes", tetree.size());

        // Phase 1: Collect all nodes in breadth-first order
        var nodeList = collectNodesBreadthFirst(tetree);
        if (nodeList.isEmpty()) {
            log.warn("Empty Tetree, returning empty ESVT");
            return new ESVTData(new ESVTNodeUnified[0], 0, 0, 0, 0);
        }

        // Phase 2: Build index map for pointer computation
        var indexMap = buildIndexMap(nodeList);

        // Phase 3: Create ESVT nodes with correct pointers
        var nodes = createNodes(nodeList, indexMap, tetree);

        // Phase 4: Compute statistics
        int leafCount = 0;
        int internalCount = 0;
        int maxDepth = 0;

        for (var entry : nodeList) {
            int level = entry.key.getLevel();
            maxDepth = Math.max(maxDepth, level);
            if (entry.isLeaf) {
                leafCount++;
            } else {
                internalCount++;
            }
        }

        // Root type is always 0 (S0) for a standard Tetree starting point
        int rootType = nodeList.isEmpty() ? 0 : nodeList.get(0).tetType;

        log.info("Built ESVT: {} nodes, depth {}, {} leaves, {} internal",
                nodes.length, maxDepth, leafCount, internalCount);

        return new ESVTData(nodes, rootType, maxDepth, leafCount, internalCount);
    }

    /**
     * Entry representing a node during building
     */
    private record NodeEntry(
        TetreeKey<? extends TetreeKey<?>> key,
        Tet tet,
        byte tetType,
        boolean isLeaf
    ) {}

    /**
     * Collect all nodes in breadth-first order
     */
    private <ID extends EntityID, Content> List<NodeEntry> collectNodesBreadthFirst(
            Tetree<ID, Content> tetree) {

        var result = new ArrayList<NodeEntry>();
        var queue = new ArrayDeque<TetreeKey<? extends TetreeKey<?>>>();
        var visited = new HashSet<TetreeKey<? extends TetreeKey<?>>>();

        // Start from root
        @SuppressWarnings("unchecked")
        var root = (TetreeKey<? extends TetreeKey<?>>) TetreeKey.getRoot();
        queue.add(root);

        while (!queue.isEmpty()) {
            var key = queue.poll();
            if (visited.contains(key)) {
                continue;
            }
            visited.add(key);

            // Check if this node exists in the Tetree
            if (!tetree.hasNode(key)) {
                continue;
            }

            // Convert key to Tet for geometry operations
            var tet = Tet.tetrahedron(key);
            byte tetType = tet.type();

            // Find children that exist
            var childKeys = findExistingChildren(tetree, tet);
            boolean isLeaf = childKeys.isEmpty();

            // Add children to queue for BFS
            for (var childKey : childKeys) {
                if (!visited.contains(childKey)) {
                    queue.add(childKey);
                }
            }

            result.add(new NodeEntry(key, tet, tetType, isLeaf));
        }

        return result;
    }

    /**
     * Build a map from TetreeKey to node index
     */
    private Map<TetreeKey<? extends TetreeKey<?>>, Integer> buildIndexMap(List<NodeEntry> nodeList) {
        var map = new HashMap<TetreeKey<? extends TetreeKey<?>>, Integer>();
        for (int i = 0; i < nodeList.size(); i++) {
            map.put(nodeList.get(i).key, i);
        }
        return map;
    }

    /**
     * Create ESVT nodes from collected entries
     */
    private <ID extends EntityID, Content> ESVTNodeUnified[] createNodes(
            List<NodeEntry> nodeList,
            Map<TetreeKey<? extends TetreeKey<?>>, Integer> indexMap,
            Tetree<ID, Content> tetree) {

        var nodes = new ESVTNodeUnified[nodeList.size()];

        for (int i = 0; i < nodeList.size(); i++) {
            var entry = nodeList.get(i);
            var node = new ESVTNodeUnified(entry.tetType);
            node.setValid(true);

            if (!entry.isLeaf) {
                // Find children and build child mask
                int childMask = 0;
                int leafMask = 0;
                int firstChildIdx = -1;

                // Check all 8 possible Bey children
                for (int childIdx = 0; childIdx < 8; childIdx++) {
                    var childTet = entry.tet.child(childIdx);
                    @SuppressWarnings("unchecked")
                    var childKey = (TetreeKey<? extends TetreeKey<?>>) childTet.tmIndex();

                    if (indexMap.containsKey(childKey)) {
                        childMask |= (1 << childIdx);
                        int childNodeIdx = indexMap.get(childKey);

                        if (firstChildIdx < 0) {
                            firstChildIdx = childNodeIdx;
                        }

                        // Check if child is a leaf
                        var childEntry = nodeList.get(childNodeIdx);
                        if (childEntry.isLeaf) {
                            leafMask |= (1 << childIdx);
                        }
                    }
                }

                node.setChildMask(childMask);
                node.setLeafMask(leafMask);

                if (firstChildIdx >= 0) {
                    // Child pointer is the absolute index in the node array
                    node.setChildPtr(firstChildIdx);
                }
            } else {
                // Leaf node - set leaf-specific data
                node.setLeafMask(0xFF); // All positions are "leaf" (no children)
                node.setChildMask(0);   // No children
            }

            nodes[i] = node;
        }

        return nodes;
    }

    /**
     * Find existing children of a node in the Tetree
     */
    @SuppressWarnings("unchecked")
    private <ID extends EntityID, Content> List<TetreeKey<? extends TetreeKey<?>>> findExistingChildren(
            Tetree<ID, Content> tetree,
            Tet parentTet) {

        var children = new ArrayList<TetreeKey<? extends TetreeKey<?>>>();

        // Check if we can create children (not at max refinement)
        if (parentTet.l() >= Constants.getMaxRefinementLevel()) {
            return children;
        }

        // Check all 8 possible Bey children
        for (int childIdx = 0; childIdx < 8; childIdx++) {
            try {
                var childTet = parentTet.child(childIdx);
                var childKey = (TetreeKey<? extends TetreeKey<?>>) childTet.tmIndex();
                if (tetree.hasNode(childKey)) {
                    children.add(childKey);
                }
            } catch (Exception e) {
                // Child doesn't exist or is invalid
                log.trace("Could not get child {} of tet at level {}", childIdx, parentTet.l());
            }
        }

        return children;
    }
}

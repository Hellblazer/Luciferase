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
import com.hellblazer.luciferase.geometry.Point3i;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.vecmath.Point3f;
import java.util.*;

/**
 * Builds ESVT GPU-ready data structure from a Tetree spatial index.
 *
 * The builder collects all nodes with entities from the Tetree and constructs
 * the full tree hierarchy by creating virtual parent nodes. Nodes are allocated
 * in breadth-first order for GPU cache efficiency. Each node is converted to an
 * 8-byte ESVTNodeUnified with:
 * - Child mask (8 bits for Bey 8-way subdivision)
 * - Leaf mask (8 bits)
 * - Child pointer (14 bits, relative offset)
 * - Tetrahedron type (3 bits, 0-5 for S0-S5)
 *
 * The builder handles:
 * - Bottom-up tree construction from sparse leaf nodes
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
        log.debug("Building ESVT from Tetree with {} entities", tetree.entityCount());

        // Phase 1: Collect all leaf nodes and build complete tree structure
        var allNodes = buildTreeFromLeaves(tetree);
        if (allNodes.isEmpty()) {
            log.warn("Empty Tetree, returning empty ESVT");
            return new ESVTData(new ESVTNodeUnified[0], 0, 0, 0, 0);
        }

        // Phase 2: Sort nodes in breadth-first order (by level, then by key)
        var nodeList = sortBreadthFirst(allNodes);

        // Phase 3: Build index map for pointer computation
        var indexMap = buildIndexMap(nodeList);

        // Phase 4: Create ESVT nodes with correct pointers
        var nodes = createNodes(nodeList, indexMap, allNodes);

        // Phase 5: Compute statistics
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
     * Convenience method to build ESVT data directly from voxel coordinates.
     * Creates a Tetree internally, populates it with voxels, then builds the ESVT.
     *
     * @param voxels   List of voxel coordinates (Point3i with x, y, z)
     * @param maxDepth Maximum tree depth (determines resolution)
     * @return ESVTData ready for GPU transfer
     */
    public ESVTData buildFromVoxels(List<Point3i> voxels, int maxDepth) {
        if (voxels == null || voxels.isEmpty()) {
            log.warn("Empty voxel list, returning empty ESVT");
            return new ESVTData(new ESVTNodeUnified[0], 0, 0, 0, 0);
        }

        log.debug("Building ESVT from {} voxels at maxDepth {}", voxels.size(), maxDepth);

        // Create Tetree with appropriate configuration
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 100, (byte) maxDepth);

        // Enable bulk loading for better performance with large voxel sets
        tetree.enableBulkLoading();

        // Insert all voxels as point entities at the specified depth
        byte level = (byte) maxDepth;
        int inserted = 0;
        for (var voxel : voxels) {
            // Convert Point3i to Point3f - voxels are integer grid coordinates
            var position = new Point3f(voxel.x, voxel.y, voxel.z);
            try {
                tetree.insert(position, level, "voxel_" + inserted);
                inserted++;
            } catch (Exception e) {
                log.trace("Skipping voxel at ({},{},{}): {}", voxel.x, voxel.y, voxel.z, e.getMessage());
            }
        }

        // Finalize bulk loading
        tetree.finalizeBulkLoading();

        log.debug("Inserted {} of {} voxels into Tetree", inserted, voxels.size());

        // Build ESVT from the populated Tetree
        return build(tetree);
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
     * Build complete tree structure from leaf nodes.
     * This creates virtual parent nodes for all ancestors of leaf nodes.
     */
    @SuppressWarnings("unchecked")
    private <ID extends EntityID, Content> Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> buildTreeFromLeaves(
            Tetree<ID, Content> tetree) {

        var allNodes = new HashMap<TetreeKey<? extends TetreeKey<?>>, NodeEntry>();
        var leafKeys = tetree.getSortedSpatialIndices();

        log.debug("Building tree from {} leaf nodes", leafKeys.size());

        // First pass: add all leaf nodes
        for (var leafKey : leafKeys) {
            var tet = Tet.tetrahedron(leafKey);
            allNodes.put(leafKey, new NodeEntry(
                (TetreeKey<? extends TetreeKey<?>>) leafKey,
                tet,
                tet.type(),
                true  // Initially mark as leaf
            ));
        }

        // Second pass: trace up from each leaf to create parent nodes
        for (var leafKey : leafKeys) {
            var current = Tet.tetrahedron(leafKey);

            while (current.l() > 0) {
                var parent = current.parent();
                var parentKey = (TetreeKey<? extends TetreeKey<?>>) parent.tmIndex();

                if (!allNodes.containsKey(parentKey)) {
                    // Create parent node (not a leaf since it has children)
                    allNodes.put(parentKey, new NodeEntry(
                        parentKey,
                        parent,
                        parent.type(),
                        false
                    ));
                } else {
                    // Parent already exists - mark it as internal if needed
                    var existing = allNodes.get(parentKey);
                    if (existing.isLeaf) {
                        allNodes.put(parentKey, new NodeEntry(
                            parentKey,
                            parent,
                            parent.type(),
                            false
                        ));
                    }
                }

                current = parent;
            }
        }

        // Third pass: update leaf status based on whether node has children
        var finalNodes = new HashMap<TetreeKey<? extends TetreeKey<?>>, NodeEntry>();
        for (var entry : allNodes.entrySet()) {
            var key = entry.getKey();
            var nodeEntry = entry.getValue();
            boolean hasChildren = hasChildrenInSet(nodeEntry.tet, allNodes);
            finalNodes.put(key, new NodeEntry(
                nodeEntry.key,
                nodeEntry.tet,
                nodeEntry.tetType,
                !hasChildren
            ));
        }

        return finalNodes;
    }

    /**
     * Check if a tet has any children in the node set
     */
    @SuppressWarnings("unchecked")
    private boolean hasChildrenInSet(Tet tet, Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> nodes) {
        for (int childIdx = 0; childIdx < 8; childIdx++) {
            try {
                var childTet = tet.child(childIdx);
                var childKey = (TetreeKey<? extends TetreeKey<?>>) childTet.tmIndex();
                if (nodes.containsKey(childKey)) {
                    return true;
                }
            } catch (Exception e) {
                // Child doesn't exist
            }
        }
        return false;
    }

    /**
     * Sort nodes in breadth-first order (by level ascending, then by key)
     */
    private List<NodeEntry> sortBreadthFirst(Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> allNodes) {
        var nodeList = new ArrayList<>(allNodes.values());

        // Sort by level (ascending - root first), then by key for deterministic ordering
        nodeList.sort((a, b) -> {
            int levelCmp = Byte.compare(a.key.getLevel(), b.key.getLevel());
            if (levelCmp != 0) return levelCmp;
            return a.key.compareTo(b.key);
        });

        return nodeList;
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
    @SuppressWarnings("unchecked")
    private ESVTNodeUnified[] createNodes(
            List<NodeEntry> nodeList,
            Map<TetreeKey<? extends TetreeKey<?>>, Integer> indexMap,
            Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> allNodes) {

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
                    try {
                        var childTet = entry.tet.child(childIdx);
                        var childKey = (TetreeKey<? extends TetreeKey<?>>) childTet.tmIndex();

                        if (indexMap.containsKey(childKey)) {
                            childMask |= (1 << childIdx);
                            int childNodeIdx = indexMap.get(childKey);

                            if (firstChildIdx < 0) {
                                firstChildIdx = childNodeIdx;
                            }

                            // Check if child is a leaf
                            var childEntry = allNodes.get(childKey);
                            if (childEntry != null && childEntry.isLeaf) {
                                leafMask |= (1 << childIdx);
                            }
                        }
                    } catch (Exception e) {
                        // Child doesn't exist
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
}

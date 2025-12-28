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
 * <p>The builder collects all nodes with entities from the Tetree and constructs
 * the full tree hierarchy by creating virtual parent nodes. Nodes are allocated
 * in breadth-first order for GPU cache efficiency. Each node is converted to an
 * 8-byte ESVTNodeUnified with:
 * <ul>
 *   <li>Child mask (8 bits for Bey 8-way subdivision)</li>
 *   <li>Leaf mask (8 bits)</li>
 *   <li>Child pointer (14 bits, relative offset)</li>
 *   <li>Tetrahedron type (3 bits, 0-5 for S0-S5)</li>
 * </ul>
 *
 * <p><b>Key Design Principle:</b> Type is derived directly from the TetreeKey via
 * {@code Tet.tetrahedron(key).type()}. The TetreeKey encodes 6 bits per level
 * (3 type bits + 3 coordinate bits), so type information is already available
 * in the key. No separate type propagation is needed.
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
        log.debug("After buildTreeFromLeaves: {} nodes", allNodes.size());

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

        // Root type comes from the key
        int rootType = nodeList.isEmpty() ? 0 : nodeList.get(0).tetType;

        log.info("Built ESVT: {} nodes, depth {}, {} leaves, {} internal",
                nodes.length, maxDepth, leafCount, internalCount);

        return new ESVTData(nodes, rootType, maxDepth, leafCount, internalCount);
    }

    /**
     * Convenience method to build ESVT data directly from voxel coordinates.
     * Creates a Tetree internally, populates it with voxels, then builds the ESVT.
     *
     * <p><b>Coordinate Transformation:</b> Input voxels are automatically transformed
     * to the Tetree's coordinate space (which uses integer Morton coordinates up to 2^21).
     * The transformation maps the voxel bounding box to fill most of the coordinate space
     * while preserving aspect ratio. ESVT ray traversal then interprets this tree in
     * normalized [0,1] space.
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

        // Compute bounding box for coordinate transformation
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var voxel : voxels) {
            minX = Math.min(minX, voxel.x);
            minY = Math.min(minY, voxel.y);
            minZ = Math.min(minZ, voxel.z);
            maxX = Math.max(maxX, voxel.x);
            maxY = Math.max(maxY, voxel.y);
            maxZ = Math.max(maxZ, voxel.z);
        }

        // Tetree uses coordinates up to 2^21, but we scale to fit within the usable range
        // based on maxDepth. At level L, cell size = 2^(21-L), so we want coordinates
        // that map cleanly to cells at the target depth.
        float rangeX = maxX - minX + 1;
        float rangeY = maxY - minY + 1;
        float rangeZ = maxZ - minZ + 1;
        float maxRange = Math.max(rangeX, Math.max(rangeY, rangeZ));

        // Target range: use 80% of the coordinate space to leave margin
        int tetreeMaxCoord = (1 << 21) - 1;
        float targetMin = tetreeMaxCoord * 0.1f;
        float targetMax = tetreeMaxCoord * 0.9f;
        float targetRange = targetMax - targetMin;

        float scale = targetRange / maxRange;
        float offsetX = targetMin - minX * scale;
        float offsetY = targetMin - minY * scale;
        float offsetZ = targetMin - minZ * scale;

        // Center smaller dimensions within the target range
        offsetX += (targetRange - rangeX * scale) / 2.0f;
        offsetY += (targetRange - rangeY * scale) / 2.0f;
        offsetZ += (targetRange - rangeZ * scale) / 2.0f;

        log.debug("Transforming voxels: bbox=[{},{},{}]-[{},{},{}], scale={}, target=[{},{}]",
                minX, minY, minZ, maxX, maxY, maxZ, scale, targetMin, targetMax);

        // Create Tetree with appropriate configuration
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 100, (byte) maxDepth);

        // Enable bulk loading for better performance with large voxel sets
        tetree.enableBulkLoading();

        // Insert all voxels as point entities at the specified depth with transformed coordinates
        byte level = (byte) maxDepth;
        int inserted = 0;
        for (var voxel : voxels) {
            // Transform voxel coordinates to Tetree coordinate space
            float tx = voxel.x * scale + offsetX;
            float ty = voxel.y * scale + offsetY;
            float tz = voxel.z * scale + offsetZ;
            var position = new Point3f(tx, ty, tz);
            try {
                tetree.insert(position, level, "voxel_" + inserted);
                inserted++;
            } catch (Exception e) {
                log.trace("Skipping voxel at ({},{},{}) -> ({},{},{}): {}",
                        voxel.x, voxel.y, voxel.z, tx, ty, tz, e.getMessage());
            }
        }

        // Finalize bulk loading
        tetree.finalizeBulkLoading();

        log.debug("Inserted {} of {} voxels into Tetree (scaled to [{},{}] range)",
                inserted, voxels.size(), targetMin, targetMax);

        // Build ESVT from the populated Tetree
        return build(tetree);
    }

    /**
     * Entry representing a node during building.
     * Type is derived from the TetreeKey via Tet.tetrahedron(key).type().
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
     *
     * <p>Type is derived from TetreeKey for each node - no manual propagation needed.
     * The key insight is that TetreeKey encodes 6 bits per level (3 type + 3 coord),
     * and Tet.tetrahedron(key).type() correctly decodes the type.</p>
     */
    @SuppressWarnings("unchecked")
    private <ID extends EntityID, Content> Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> buildTreeFromLeaves(
            Tetree<ID, Content> tetree) {

        var allNodes = new HashMap<TetreeKey<? extends TetreeKey<?>>, NodeEntry>();
        var leafKeys = tetree.getSortedSpatialIndices();

        log.debug("Building tree from {} leaf nodes", leafKeys.size());

        // First pass: add all leaf nodes
        // Type is derived from the key itself via Tet.tetrahedron(key).type()
        for (var leafKey : leafKeys) {
            var tet = Tet.tetrahedron(leafKey);
            allNodes.put(leafKey, new NodeEntry(
                (TetreeKey<? extends TetreeKey<?>>) leafKey,
                tet,
                tet.type(),  // Type derived from key
                true
            ));
        }

        // Second pass: trace up from each leaf to create parent nodes
        for (var leafKey : leafKeys) {
            var current = Tet.tetrahedron(leafKey);

            while (current.l() > 0) {
                var parent = current.parent();
                var parentKey = (TetreeKey<? extends TetreeKey<?>>) parent.tmIndex();

                if (!allNodes.containsKey(parentKey)) {
                    // Create parent node - type is from parent.type() which was computed
                    // consistently via parent() chain
                    allNodes.put(parentKey, new NodeEntry(
                        parentKey,
                        parent,
                        parent.type(),  // Type from parent() computation
                        false
                    ));
                }
                // If parent already exists, keep it as-is (it has the correct type)

                current = parent;
            }
        }

        // Third pass: update leaf status based on whether node has children
        var finalNodes = new HashMap<TetreeKey<? extends TetreeKey<?>>, NodeEntry>();
        for (var entry : allNodes.entrySet()) {
            var key = entry.getKey();
            var nodeEntry = entry.getValue();
            boolean hasChildren = hasChildrenInMap(nodeEntry.tet, allNodes);
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
     * Check if a tet has any children in the node map.
     * Uses TetreeKey (tmIndex) for lookup.
     */
    @SuppressWarnings("unchecked")
    private boolean hasChildrenInMap(Tet tet, Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> nodeMap) {
        for (int childIdx = 0; childIdx < 8; childIdx++) {
            try {
                var childTet = tet.child(childIdx);
                var childKey = (TetreeKey<? extends TetreeKey<?>>) childTet.tmIndex();
                if (nodeMap.containsKey(childKey)) {
                    return true;
                }
            } catch (Exception e) {
                // Child doesn't exist
            }
        }
        return false;
    }

    /**
     * Sort nodes in breadth-first order with siblings in Morton order.
     *
     * <p>Critical for sparse child indexing: ESVTNodeUnified.getChildIndex() uses
     * popcount-based sparse indexing that assumes children are stored contiguously
     * in Morton order.</p>
     *
     * <p>Sort order: level (ascending) → parent key → Morton index (consecutiveIndex)</p>
     */
    @SuppressWarnings("unchecked")
    private List<NodeEntry> sortBreadthFirst(Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> allNodes) {
        var nodeList = new ArrayList<>(allNodes.values());

        // Sort by level, then parent key, then Morton index (consecutiveIndex)
        nodeList.sort((a, b) -> {
            // 1. Sort by level (root first)
            int levelCmp = Byte.compare(a.key.getLevel(), b.key.getLevel());
            if (levelCmp != 0) return levelCmp;

            // 2. Group siblings by parent key
            TetreeKey<?> aParent = (a.tet.l() > 0) ? (TetreeKey<?>) a.tet.parent().tmIndex() : null;
            TetreeKey<?> bParent = (b.tet.l() > 0) ? (TetreeKey<?>) b.tet.parent().tmIndex() : null;
            if (aParent == null && bParent == null) return 0;
            if (aParent == null) return -1;
            if (bParent == null) return 1;
            int parentCmp = aParent.compareTo((TetreeKey) bParent);
            if (parentCmp != 0) return parentCmp;

            // 3. Sort siblings by Morton index (consecutiveIndex is unique within level)
            return Long.compare(a.tet.consecutiveIndex(), b.tet.consecutiveIndex());
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
     * Create ESVT nodes from collected entries.
     *
     * <p>Uses TetreeKey (tmIndex) for finding children. The child's tmIndex should
     * match what was stored during buildTreeFromLeaves because both use the same
     * consistent path for key computation.</p>
     */
    @SuppressWarnings("unchecked")
    private ESVTNodeUnified[] createNodes(
            List<NodeEntry> nodeList,
            Map<TetreeKey<? extends TetreeKey<?>>, Integer> indexMap,
            Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> nodeMap) {

        var nodes = new ESVTNodeUnified[nodeList.size()];

        for (int i = 0; i < nodeList.size(); i++) {
            var entry = nodeList.get(i);
            var node = new ESVTNodeUnified(entry.tetType);
            node.setValid(true);

            if (!entry.isLeaf) {
                // Find children and build child mask
                int childMask = 0;
                int leafMask = 0;
                int minChildIdx = Integer.MAX_VALUE;

                // Check all 8 possible children using TetreeKey lookup
                for (int childIdx = 0; childIdx < 8; childIdx++) {
                    try {
                        var childTet = entry.tet.child(childIdx);
                        var childKey = (TetreeKey<? extends TetreeKey<?>>) childTet.tmIndex();

                        var childIdxInArray = indexMap.get(childKey);
                        if (childIdxInArray != null) {
                            childMask |= (1 << childIdx);
                            minChildIdx = Math.min(minChildIdx, childIdxInArray);

                            // Check if child is a leaf
                            var childEntry = nodeMap.get(childKey);
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

                if (minChildIdx != Integer.MAX_VALUE) {
                    node.setChildPtr(minChildIdx);
                }
            } else {
                // Leaf node - set leaf-specific data
                node.setLeafMask(0xFF);  // All positions are "leaf" (no children)
                node.setChildMask(0);    // No children
            }

            nodes[i] = node;
        }

        return nodes;
    }
}

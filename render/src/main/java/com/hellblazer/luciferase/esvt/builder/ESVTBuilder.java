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
import com.hellblazer.luciferase.lucien.Constants;
import com.hellblazer.luciferase.lucien.entity.EntityID;
import com.hellblazer.luciferase.lucien.entity.LongEntityID;
import com.hellblazer.luciferase.lucien.entity.SequentialLongIDGenerator;
import com.hellblazer.luciferase.lucien.tetree.Tet;
import com.hellblazer.luciferase.lucien.tetree.Tetree;
import com.hellblazer.luciferase.lucien.tetree.TetreeConnectivity;
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

        // Phase 4: Propagate types top-down from root
        // This is critical: types must be derived from parent's type + Morton child index,
        // NOT from bottom-up computation which may be inconsistent
        var correctedTypes = propagateTypesTopDown(nodeList, indexMap, allNodes);

        // Phase 5: Create ESVT nodes with correct pointers and corrected types
        var nodes = createNodes(nodeList, correctedTypes, indexMap, allNodes);

        // Phase 6: Compute statistics
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
     * Stores TetreeKey for unique identification (includes type).
     */
    private record NodeEntry(
        TetreeKey<? extends TetreeKey<?>> key,
        Tet tet,
        byte tetType,
        boolean isLeaf,
        TetreeKey<? extends TetreeKey<?>> parentKey  // Explicit parent reference
    ) {}

    /**
     * Build complete tree structure from leaf nodes.
     * Uses TetreeKey (with type) for unique identification.
     * Stores explicit parent-child relationships instead of recomputing via child().
     *
     * <p><b>S0 Tree Filtering:</b> Only nodes that are valid in the S0 Bey tree are included.
     * The Tetree is defined to be rooted in S0, so any nodes with types that don't match
     * the S0 Bey traversal are filtered out. This handles legacy data or any edge cases
     * where non-S0 nodes might exist.
     */
    @SuppressWarnings("unchecked")
    private <ID extends EntityID, Content> Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> buildTreeFromLeaves(
            Tetree<ID, Content> tetree) {

        var allNodes = new HashMap<TetreeKey<? extends TetreeKey<?>>, NodeEntry>();
        var leafKeys = tetree.getSortedSpatialIndices();
        // Track children for each parent: parentKey -> list of child keys
        var parentToChildren = new HashMap<TetreeKey<? extends TetreeKey<?>>, List<TetreeKey<? extends TetreeKey<?>>>>();

        log.debug("Building tree from {} leaf nodes", leafKeys.size());

        // First pass: add all leaf nodes, using S0 tree canonical form
        // We use Tet.locatePointS0Tree to get the canonical S0 representation
        // This deduplicates nodes that have same (x,y,z,level) but different stored types
        var seenPositions = new HashSet<String>();
        int skipped = 0;

        for (var leafKey : leafKeys) {
            var tet = Tet.tetrahedron(leafKey);

            // Get the canonical S0 tree representation for this position
            var s0Tet = Tet.locatePointS0Tree((float) tet.x, (float) tet.y, (float) tet.z, tet.l());

            // Deduplicate by position (x,y,z,level) - only keep first occurrence
            var posKey = s0Tet.x + "," + s0Tet.y + "," + s0Tet.z + "," + s0Tet.l();
            if (seenPositions.contains(posKey)) {
                skipped++;
                continue;
            }
            seenPositions.add(posKey);

            // Use the S0 canonical tet and its key
            var s0Key = (TetreeKey<? extends TetreeKey<?>>) s0Tet.tmIndex();
            allNodes.put(s0Key, new NodeEntry(s0Key, s0Tet, s0Tet.type(), true, null));
        }

        if (skipped > 0) {
            log.debug("Filtered out {} duplicate/non-S0 tree nodes", skipped);
        }

        // Second pass: trace up from each included leaf to create parent nodes
        // Store explicit parent-child relationships
        // Only trace from leaves that were included (exist in allNodes)
        for (var entry : new ArrayList<>(allNodes.values())) {
            var current = entry.tet;
            var currentKey = entry.key;

            while (current.l() > 0) {
                var parent = current.parent();
                var parentKey = (TetreeKey<? extends TetreeKey<?>>) parent.tmIndex();

                // Register this child with its parent
                parentToChildren.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(currentKey);

                // Update current node's parent reference
                var existing = allNodes.get(currentKey);
                if (existing != null && existing.parentKey == null) {
                    allNodes.put(currentKey, new NodeEntry(
                        existing.key, existing.tet, existing.tetType, existing.isLeaf, parentKey));
                }

                // Create parent node if it doesn't exist
                if (!allNodes.containsKey(parentKey)) {
                    allNodes.put(parentKey, new NodeEntry(parentKey, parent, parent.type(), false, null));
                }

                current = parent;
                currentKey = parentKey;
            }
        }

        // Third pass: update leaf status based on whether node has children
        var finalNodes = new HashMap<TetreeKey<? extends TetreeKey<?>>, NodeEntry>();
        for (var entry : allNodes.entrySet()) {
            var key = entry.getKey();
            var nodeEntry = entry.getValue();
            boolean hasChildren = parentToChildren.containsKey(key);
            finalNodes.put(key, new NodeEntry(
                nodeEntry.key, nodeEntry.tet, nodeEntry.tetType, !hasChildren, nodeEntry.parentKey));
        }

        return finalNodes;
    }

    /**
     * Check if a tet has any children in the node map.
     * Uses TetreeKey for lookup.
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
     * Sort nodes in breadth-first order with siblings CONTIGUOUS in Morton order.
     *
     * <p>Uses explicit parent-child relationships from tree building, not recomputed
     * via child(). Children are sorted by their Morton child index within the parent.</p>
     */
    @SuppressWarnings("unchecked")
    private List<NodeEntry> sortBreadthFirst(Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> allNodes) {
        if (allNodes.isEmpty()) {
            return new ArrayList<>();
        }

        // Build parent -> children map from explicit parentKey references
        var parentToChildren = new HashMap<TetreeKey<? extends TetreeKey<?>>, List<NodeEntry>>();
        for (var entry : allNodes.values()) {
            if (entry.parentKey != null) {
                parentToChildren.computeIfAbsent(entry.parentKey, k -> new ArrayList<>()).add(entry);
            }
        }

        // Sort each parent's children by Morton index
        for (var children : parentToChildren.values()) {
            children.sort(Comparator.comparingInt(e -> computeMortonChildIndex(e.tet)));
        }

        // Find root (level 0)
        NodeEntry root = null;
        for (var entry : allNodes.values()) {
            if (entry.key.getLevel() == 0) {
                root = entry;
                break;
            }
        }

        if (root == null) {
            log.warn("No root node found, falling back to simple sort");
            var list = new ArrayList<>(allNodes.values());
            list.sort(Comparator.comparingInt(e -> e.key.getLevel()));
            return list;
        }

        // BFS traversal using explicit parent-child relationships
        var result = new ArrayList<NodeEntry>(allNodes.size());
        var processed = new HashSet<TetreeKey<?>>();

        result.add(root);
        processed.add(root.key);

        int currentIdx = 0;
        while (currentIdx < result.size()) {
            var parent = result.get(currentIdx);
            var children = parentToChildren.get(parent.key);

            if (children != null) {
                for (var child : children) {
                    if (!processed.contains(child.key)) {
                        result.add(child);
                        processed.add(child.key);
                    }
                }
            }

            currentIdx++;
        }

        // Verify all nodes were placed
        if (result.size() != allNodes.size()) {
            log.warn("BFS placed {} of {} nodes - {} orphaned nodes not connected to root",
                result.size(), allNodes.size(), allNodes.size() - result.size());
            for (var entry : allNodes.values()) {
                if (!processed.contains(entry.key)) {
                    result.add(entry);
                }
            }
        }

        return result;
    }

    /**
     * Compute the Morton child index of a Tet within its parent.
     * Uses cubeId and parent type only - NOT child type (which would create circular dependency).
     *
     * <p>The mapping is:
     * <ol>
     *   <li>cubeId + parentType → beyId (via TYPE_CID_TO_BEYID)</li>
     *   <li>beyId + parentType → Morton index (via BEY_NUMBER_TO_INDEX)</li>
     * </ol>
     */
    private byte computeMortonChildIndex(Tet tet) {
        if (tet.l() == 0) {
            return 0; // Root has no parent, return 0
        }
        byte childCubeId = tet.cubeId(tet.l());
        byte parentType = tet.computeType((byte) (tet.l() - 1));
        // Get Bey child ID from cubeId and parent type
        byte beyId = TetreeConnectivity.TYPE_CID_TO_BEYID[parentType][childCubeId];
        // Convert Bey to Morton
        return TetreeConnectivity.BEY_NUMBER_TO_INDEX[parentType][beyId];
    }

    /**
     * Build a map from TetreeKey to node index.
     */
    private Map<TetreeKey<? extends TetreeKey<?>>, Integer> buildIndexMap(List<NodeEntry> nodeList) {
        var map = new HashMap<TetreeKey<? extends TetreeKey<?>>, Integer>();
        for (int i = 0; i < nodeList.size(); i++) {
            map.put(nodeList.get(i).key, i);
        }
        return map;
    }

    /**
     * Propagate types top-down from root.
     *
     * <p>Uses explicit parent-child relationships. Children's types are derived from
     * parent type + Morton child index via TYPE_TO_TYPE_OF_CHILD_MORTON.</p>
     *
     * @return Array of corrected types indexed by node position in nodeList
     */
    private byte[] propagateTypesTopDown(
            List<NodeEntry> nodeList,
            Map<TetreeKey<? extends TetreeKey<?>>, Integer> indexMap,
            Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> nodeMap) {

        byte[] types = new byte[nodeList.size()];

        // Build parent -> children map from explicit parentKey references
        var parentToChildren = new HashMap<TetreeKey<? extends TetreeKey<?>>, List<NodeEntry>>();
        for (var entry : nodeList) {
            if (entry.parentKey != null) {
                parentToChildren.computeIfAbsent(entry.parentKey, k -> new ArrayList<>()).add(entry);
            }
        }

        // Root is always type 0
        if (!nodeList.isEmpty()) {
            types[0] = 0;
        }

        // Debug: show first 15 nodes with their levels
        for (int i = 0; i < Math.min(15, nodeList.size()); i++) {
            var e = nodeList.get(i);
            log.debug("Sorted nodeList[{}]: level={}, type={}, key.level={}",
                i, e.tet.l(), e.tet.type(), e.key.getLevel());
        }

        // Process nodes in BFS order (already sorted this way)
        for (int i = 0; i < nodeList.size(); i++) {
            var entry = nodeList.get(i);
            byte parentType = types[i];

            if (i == 0) {
                log.debug("Root entry.tet: x={}, y={}, z={}, level={}, type={}, parentType from types[0]={}",
                    entry.tet.x, entry.tet.y, entry.tet.z, entry.tet.l(), entry.tet.type(), parentType);
            }

            // Get children for this node from explicit relationships
            var children = parentToChildren.get(entry.key);
            if (children != null) {
                for (var child : children) {
                    var childIdxInArray = indexMap.get(child.key);
                    if (childIdxInArray != null) {
                        // Compute Morton index for this child
                        int mortonIdx = computeMortonChildIndex(child.tet);
                        byte derivedType = Constants.TYPE_TO_TYPE_OF_CHILD_MORTON[parentType][mortonIdx];
                        types[childIdxInArray] = derivedType;
                        if (i == 0) {
                            log.debug("Root child Morton {}: parentType={}, derived type={}, storing at index {}",
                                mortonIdx, parentType, derivedType, childIdxInArray);
                        }
                    }
                }
            }
        }

        return types;
    }

    /**
     * Create ESVT nodes from collected entries.
     *
     * <p>Uses explicit parent-child relationships for finding children.
     * Children are stored contiguously in Morton order.</p>
     *
     * @param nodeList List of node entries in breadth-first order
     * @param correctedTypes Array of types corrected via top-down propagation
     * @param indexMap Map from TetreeKey to node index
     * @param nodeMap Map from TetreeKey to NodeEntry
     * @return Array of ESVTNodeUnified ready for GPU transfer
     */
    private ESVTNodeUnified[] createNodes(
            List<NodeEntry> nodeList,
            byte[] correctedTypes,
            Map<TetreeKey<? extends TetreeKey<?>>, Integer> indexMap,
            Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> nodeMap) {

        // Build parent -> children map from explicit parentKey references
        var parentToChildren = new HashMap<TetreeKey<? extends TetreeKey<?>>, List<NodeEntry>>();
        for (var entry : nodeList) {
            if (entry.parentKey != null) {
                parentToChildren.computeIfAbsent(entry.parentKey, k -> new ArrayList<>()).add(entry);
            }
        }

        // Sort each parent's children by Morton index
        for (var children : parentToChildren.values()) {
            children.sort(Comparator.comparingInt(e -> computeMortonChildIndex(e.tet)));
        }

        var nodes = new ESVTNodeUnified[nodeList.size()];

        for (int i = 0; i < nodeList.size(); i++) {
            var entry = nodeList.get(i);
            var node = new ESVTNodeUnified(correctedTypes[i]);
            node.setValid(true);

            if (!entry.isLeaf) {
                int childMask = 0;
                int leafMask = 0;
                int minChildIdx = Integer.MAX_VALUE;

                // Get children from explicit relationships
                var children = parentToChildren.get(entry.key);
                if (children != null) {
                    for (var child : children) {
                        var childIdxInArray = indexMap.get(child.key);
                        if (childIdxInArray != null) {
                            int mortonIdx = computeMortonChildIndex(child.tet);
                            childMask |= (1 << mortonIdx);
                            minChildIdx = Math.min(minChildIdx, childIdxInArray);

                            if (i == 0) {
                                log.debug("createNodes root child Morton {}: found at index {}",
                                    mortonIdx, childIdxInArray);
                            }

                            if (child.isLeaf) {
                                leafMask |= (1 << mortonIdx);
                            }
                        }
                    }
                }

                node.setChildMask(childMask);
                node.setLeafMask(leafMask);

                if (minChildIdx != Integer.MAX_VALUE) {
                    node.setChildPtr(minChildIdx);
                }
            } else {
                node.setLeafMask(0xFF);
                node.setChildMask(0);
            }

            nodes[i] = node;
        }

        return nodes;
    }
}

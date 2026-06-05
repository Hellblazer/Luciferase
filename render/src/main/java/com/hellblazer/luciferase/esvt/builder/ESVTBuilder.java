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
 * in a child-contiguous depth-first order (parent, then its Morton-ordered child block, then each child's
 * subtree) so child-pointer offsets stay subtree-local and far pointers stay rare. Each node is converted to an
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
     * <p><b>Precondition (S0-root invariant):</b> all leaf nodes in {@code tetree} must have been
     * inserted via {@link com.hellblazer.luciferase.lucien.tetree.Tet#locatePointS0Tree} so that
     * the root is always tet type 0. Pyramid-rooted tetrahedra (where
     * {@code Tet.minTetLevel() != Tet.NO_TET_ANCESTOR}) are not supported; construction is
     * deferred to Luciferase-q3p (RDR-012 D2 shallow-only-live contract). Violation fails loud
     * inside {@link #propagateTypesTopDown} (Luciferase-7wzml.167).
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

        // Phase 2: Lay nodes out child-contiguous (parent, then its Morton-ordered child block, then recurse)
        // so child-pointer offsets stay subtree-local — keeps far pointers rare (Luciferase-yhue6)
        var nodeList = sortChildContiguous(allNodes);

        // Phase 3: Build index map for pointer computation
        var indexMap = buildIndexMap(nodeList);

        // Phase 4: Propagate types top-down from root
        // This is critical: types must be derived from parent's type + Morton child index,
        // NOT from bottom-up computation which may be inconsistent
        var correctedTypes = propagateTypesTopDown(nodeList, indexMap, allNodes);

        // Phase 5: Create ESVT nodes with correct pointers and corrected types
        var buildResult = createNodes(nodeList, correctedTypes, indexMap, allNodes);

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

        log.info("Built ESVT: {} nodes, depth {}, {} leaves, {} internal, {} far pointers",
                buildResult.nodes.length, maxDepth, leafCount, internalCount, buildResult.farPointers.length);

        return new ESVTData(buildResult.nodes, new int[0], buildResult.farPointers,
                           rootType, maxDepth, leafCount, internalCount);
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
        // Default: use voxel bounding box for scaling
        return buildFromVoxels(voxels, maxDepth, -1);
    }

    /**
     * Build ESVT from voxel coordinates with explicit grid resolution.
     *
     * <p>When gridResolution is positive, coordinates are scaled relative to
     * [0, gridResolution-1] bounds, preserving spatial relationships. When negative,
     * coordinates are scaled to fit the actual voxel bounding box (legacy behavior).
     *
     * <p><b>Precondition (S0-root invariant):</b> nodes are located internally via
     * {@link com.hellblazer.luciferase.lucien.tetree.Tet#locatePointS0Tree}, guaranteeing
     * a type-0 root. Pyramid-rooted tetrahedra ({@code Tet.minTetLevel() != Tet.NO_TET_ANCESTOR})
     * are not supported; construction is deferred to Luciferase-q3p (RDR-012 D2
     * shallow-only-live contract). Violation fails loud inside
     * {@link #propagateTypesTopDown} (Luciferase-7wzml.167).
     *
     * @param voxels         List of voxel coordinates (Point3i with x, y, z)
     * @param maxDepth       Maximum tree depth (determines resolution)
     * @param gridResolution Full grid size (e.g., 64 for 64x64x64), or -1 for auto
     * @return ESVTData ready for GPU transfer
     */
    public ESVTData buildFromVoxels(List<Point3i> voxels, int maxDepth, int gridResolution) {
        if (voxels == null || voxels.isEmpty()) {
            log.warn("Empty voxel list, returning empty ESVT");
            return new ESVTData(new ESVTNodeUnified[0], 0, 0, 0, 0);
        }

        log.debug("Building ESVT from {} voxels at maxDepth {}, gridResolution {}",
                voxels.size(), maxDepth, gridResolution);

        // Determine coordinate bounds
        int minX, minY, minZ, maxX, maxY, maxZ;
        int effectiveGridResolution = gridResolution;
        if (gridResolution > 0) {
            // Use explicit grid bounds - preserves spatial relationships
            minX = minY = minZ = 0;
            maxX = maxY = maxZ = gridResolution - 1;
        } else {
            // Compute bounding box from actual voxels (legacy behavior)
            minX = minY = minZ = Integer.MAX_VALUE;
            maxX = maxY = maxZ = Integer.MIN_VALUE;
            for (var voxel : voxels) {
                minX = Math.min(minX, voxel.x);
                minY = Math.min(minY, voxel.y);
                minZ = Math.min(minZ, voxel.z);
                maxX = Math.max(maxX, voxel.x);
                maxY = Math.max(maxY, voxel.y);
                maxZ = Math.max(maxZ, voxel.z);
            }
            // For legacy mode, compute effective grid resolution
            effectiveGridResolution = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) + 1;
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

        log.debug("Transforming voxels: grid=[{},{},{}]-[{},{},{}], scale={}, target=[{},{}]",
                minX, minY, minZ, maxX, maxY, maxZ, scale, targetMin, targetMax);

        // Create Tetree with appropriate configuration
        var idGenerator = new SequentialLongIDGenerator();
        var tetree = new Tetree<LongEntityID, String>(idGenerator, 100, (byte) maxDepth);

        // Enable bulk loading for better performance with large voxel sets
        tetree.enableBulkLoading();

        // Track mapping from Tetree position (as string key) to original voxel
        // Key is "x,y,z,level" of the Tet position in Tetree coordinates
        var positionToVoxel = new HashMap<String, Point3i>();

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
                // Get the Tet for this position to use as lookup key
                var tet = Tet.locatePointS0Tree(tx, ty, tz, level);
                var posKey = tet.x + "," + tet.y + "," + tet.z + "," + tet.l();
                positionToVoxel.put(posKey, voxel);
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

        // Build ESVT from the populated Tetree with voxel position tracking
        return buildWithVoxelTracking(tetree, positionToVoxel, effectiveGridResolution);
    }

    /**
     * Build ESVT with voxel position tracking.
     * Like build() but also captures original voxel positions for leaves.
     *
     * @param tetree The source Tetree spatial index
     * @param positionToVoxel Map from Tet position key to original voxel
     * @param gridResolution The original voxel grid resolution
     * @return ESVTData with voxel coordinate info
     */
    private <ID extends EntityID, Content> ESVTData buildWithVoxelTracking(
            Tetree<ID, Content> tetree,
            Map<String, Point3i> positionToVoxel,
            int gridResolution) {

        log.debug("Building ESVT with voxel tracking from Tetree with {} entities", tetree.entityCount());

        // Phase 1: Collect all leaf nodes and build complete tree structure
        var allNodes = buildTreeFromLeaves(tetree);
        if (allNodes.isEmpty()) {
            log.warn("Empty Tetree, returning empty ESVT");
            return new ESVTData(new ESVTNodeUnified[0], 0, 0, 0, 0);
        }
        log.debug("After buildTreeFromLeaves: {} nodes", allNodes.size());

        // Phase 2: Lay nodes out child-contiguous (parent, then its Morton-ordered child block, then recurse)
        // so child-pointer offsets stay subtree-local — keeps far pointers rare (Luciferase-yhue6)
        var nodeList = sortChildContiguous(allNodes);

        // Phase 3: Build index map for pointer computation
        var indexMap = buildIndexMap(nodeList);

        // Phase 4: Propagate types top-down from root
        var correctedTypes = propagateTypesTopDown(nodeList, indexMap, allNodes);

        // Phase 5: Create ESVT nodes with correct pointers and corrected types
        var buildResult = createNodes(nodeList, correctedTypes, indexMap, allNodes);

        // Phase 6: Compute statistics and collect leaf voxel positions
        int leafCount = 0;
        int internalCount = 0;
        int maxDepth = 0;

        // First pass: count leaves
        for (var entry : nodeList) {
            int level = entry.key.getLevel();
            maxDepth = Math.max(maxDepth, level);
            if (entry.isLeaf) {
                leafCount++;
            } else {
                internalCount++;
            }
        }

        // Second pass: collect leaf voxel positions
        int[] leafVoxelCoords = new int[leafCount * 3];
        int leafIdx = 0;
        for (var entry : nodeList) {
            if (entry.isLeaf) {
                // Look up original voxel position
                var posKey = entry.tet.x + "," + entry.tet.y + "," + entry.tet.z + "," + entry.tet.l();
                var voxel = positionToVoxel.get(posKey);
                if (voxel != null) {
                    leafVoxelCoords[leafIdx * 3] = voxel.x;
                    leafVoxelCoords[leafIdx * 3 + 1] = voxel.y;
                    leafVoxelCoords[leafIdx * 3 + 2] = voxel.z;
                } else {
                    // Fallback: use tet position (should not happen if mapping is correct)
                    log.warn("No voxel mapping found for leaf at {}", posKey);
                    leafVoxelCoords[leafIdx * 3] = 0;
                    leafVoxelCoords[leafIdx * 3 + 1] = 0;
                    leafVoxelCoords[leafIdx * 3 + 2] = 0;
                }
                leafIdx++;
            }
        }

        // Root type comes from the key
        int rootType = nodeList.isEmpty() ? 0 : nodeList.get(0).tetType;

        log.info("Built ESVT with voxel tracking: {} nodes, depth {}, {} leaves, {} internal, grid={}, {} far pointers",
                buildResult.nodes.length, maxDepth, leafCount, internalCount, gridResolution, buildResult.farPointers.length);

        return new ESVTData(buildResult.nodes, new int[0], buildResult.farPointers,
                           rootType, maxDepth, leafCount, internalCount,
                           gridResolution, leafVoxelCoords);
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
     * Lay nodes out child-contiguous: a parent is immediately followed by its Morton-ordered child block, then
     * each child's subtree is emitted (depth-first). Siblings stay CONTIGUOUS in Morton order (required by the
     * childMask + childPtr addressing), while child-pointer offsets stay subtree-local — so only a handful of
     * near-root nodes (whose earlier siblings have huge subtrees) need far pointers, instead of nearly every
     * internal node as in the old breadth-first layout (Luciferase-yhue6). Parents always precede their children,
     * so the downstream top-down type propagation remains valid.
     *
     * <p>Uses explicit parent-child relationships from tree building, not recomputed via child(). Children are
     * sorted by their Morton child index within the parent.</p>
     */
    @SuppressWarnings("unchecked")
    private List<NodeEntry> sortChildContiguous(Map<TetreeKey<? extends TetreeKey<?>>, NodeEntry> allNodes) {
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

        // Child-contiguous depth-first layout: emit each node's children as one Morton-ordered block, then recurse
        // into each child's subtree. An explicit stack avoids recursion depth concerns on deep trees.
        var result = new ArrayList<NodeEntry>(allNodes.size());
        var processed = new HashSet<TetreeKey<?>>();

        result.add(root);
        processed.add(root.key);

        var stack = new ArrayDeque<NodeEntry>();
        stack.push(root);
        while (!stack.isEmpty()) {
            var parent = stack.pop();
            var children = parentToChildren.get(parent.key);
            if (children == null) {
                continue;
            }
            // Append this parent's children as a contiguous block (already Morton-sorted above).
            var freshChildren = new ArrayList<NodeEntry>(children.size());
            for (var child : children) {
                if (processed.add(child.key)) {
                    result.add(child);
                    freshChildren.add(child);
                }
            }
            // Recurse into the children left-to-right: push in reverse so child 0's subtree is laid out first,
            // keeping its child block closest to it (smallest offset).
            for (int j = freshChildren.size() - 1; j >= 0; j--) {
                stack.push(freshChildren.get(j));
            }
        }

        // Verify all nodes were placed
        if (result.size() != allNodes.size()) {
            log.warn("Child-contiguous layout placed {} of {} nodes - {} orphaned nodes not connected to root",
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
    // Package-private for direct unit testing of the pyramid-rooted precondition guard (Luciferase-rzn79).
    byte computeMortonChildIndex(Tet tet) {
        if (tet.l() == 0) {
            return 0; // Root has no parent, return 0
        }
        if (tet.minTetLevel() != Tet.NO_TET_ANCESTOR) {
            // Pyramid-rooted tet: computeType's ancestor walk is undefined above the tet/pyramid
            // boundary (deferred to Luciferase-q3p). Fail loud at the call site rather than let a
            // deep, unattributed IllegalStateException surface from Tet.computeType.
            throw new IllegalArgumentException(
            "ESVTBuilder.computeMortonChildIndex does not support pyramid-rooted tetrahedra: tet has "
            + "minTetLevel=" + tet.minTetLevel() + " (!= NO_TET_ANCESTOR). Pyramid ancestor-type "
            + "resolution is deferred to Luciferase-q3p.");
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

        // Seed the root type from the actual key-derived tet type (same source build() and
        // buildWithVoxelTracking use at lines 112 and 331).  All current callers insert only
        // S0-canonical tets via Tet.locatePointS0Tree, so the root is invariably type 0 —
        // but fail loud if that invariant is ever violated rather than silently corrupting
        // every descendant type.  (RDR-012 D2: the S0-root invariant is the shallow-only-live
        // contract enforced by PyramidBoundaryPinningTest; Luciferase-7wzml.167.)
        if (!nodeList.isEmpty()) {
            byte rootTetType = nodeList.get(0).tetType;
            if (rootTetType != 0) {
                throw new IllegalStateException(
                    "ESVTBuilder.propagateTypesTopDown: root node has tet type " + rootTetType
                    + " but the S0-root invariant requires type 0. All nodes must be inserted via "
                    + "Tet.locatePointS0Tree to guarantee a type-0 root. (Luciferase-7wzml.167)");
            }
            types[0] = rootTetType; // always 0 by the invariant asserted above; assigned for clarity / future non-S0 root support
        }

        // Debug: show first 15 nodes with their levels
        for (int i = 0; i < Math.min(15, nodeList.size()); i++) {
            var e = nodeList.get(i);
            log.debug("Sorted nodeList[{}]: level={}, type={}, key.level={}",
                i, e.tet.l(), e.tet.type(), e.key.getLevel());
        }

        // Process nodes parent-first: the child-contiguous layout guarantees parents precede their children,
        // so types[i] is set before any child reads it.
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

    /** Maximum child pointer value that fits in 15 bits */
    private static final int MAX_CHILD_PTR = (1 << 15) - 1; // 32767

    /** Result of createNodes containing both nodes and any far pointers needed */
    private record NodeBuildResult(ESVTNodeUnified[] nodes, int[] farPointers) {}

    /**
     * Create ESVT nodes from collected entries.
     *
     * <p>Uses explicit parent-child relationships for finding children.
     * Children are stored contiguously in Morton order.</p>
     *
     * <p>When child pointers exceed 15 bits (32767), far pointers are used:
     * the actual pointer is stored in a separate array and the node's childPtr
     * becomes an index into that array with the far flag set.</p>
     *
     * @param nodeList List of node entries in child-contiguous order (parents precede their children)
     * @param correctedTypes Array of types corrected via top-down propagation
     * @param indexMap Map from TetreeKey to node index
     * @param nodeMap Map from TetreeKey to NodeEntry
     * @return NodeBuildResult containing nodes and any far pointers
     */
    private NodeBuildResult createNodes(
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
        var farPointersList = new ArrayList<Integer>();

        for (int i = 0; i < nodeList.size(); i++) {
            var entry = nodeList.get(i);
            var node = new ESVTNodeUnified(correctedTypes[i]);

            if (!entry.isLeaf) {
                int childMask = 0;
                int leafMask = 0;
                int minChildIdx = Integer.MAX_VALUE;

                // Get children from explicit relationships
                var children = parentToChildren.get(entry.key);

                if (i == 0) {
                    log.debug("createNodes root: entry.key={}, entry.isLeaf={}, children found={}",
                        entry.key, entry.isLeaf, children != null ? children.size() : "null");
                }

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

                if (i == 0) {
                    log.debug("createNodes root: final childMask=0x{}, leafMask=0x{}, minChildIdx={}",
                        Integer.toHexString(childMask), Integer.toHexString(leafMask), minChildIdx);
                }

                if (minChildIdx != Integer.MAX_VALUE) {
                    // Relative offset from the current node index. The child-contiguous layout keeps children
                    // after their parent, so this is always >= 1 and stays subtree-local (Luciferase-yhue6).
                    int relativeOffset = minChildIdx - i;

                    if (relativeOffset <= MAX_CHILD_PTR) {
                        // Relative offset fits in 15 bits (the common case under the child-contiguous layout)
                        node.setChildPtr(relativeOffset);
                    } else {
                        // Far pointer: the actual offset goes in a side array; the node stores the far-array INDEX
                        // in its 15-bit child-ptr field. That index must itself fit in 15 bits, so the far table is
                        // capped at MAX_CHILD_PTR entries. Fail loud and attributable if a pathologically wide tree
                        // exhausts it, rather than surfacing the opaque setChildPtr overflow (Luciferase-yhue6).
                        int farIndex = farPointersList.size();
                        if (farIndex > MAX_CHILD_PTR) {
                            throw new IllegalStateException(
                                "ESVT far-pointer table overflow: " + (farIndex + 1) + " far pointers needed but the "
                                + "15-bit far index caps at " + MAX_CHILD_PTR + ". The tree is too wide for the "
                                + "current far-pointer encoding (node " + i + ", relativeOffset " + relativeOffset
                                + "). A wider far index or a paged layout is required for models this large.");
                        }
                        farPointersList.add(relativeOffset);
                        node.setChildPtr(farIndex);
                        node.setFar(true);
                        log.debug("Node {} using far pointer: farIdx={} -> relativeOffset={}",
                            i, farIndex, relativeOffset);
                    }
                }
            } else {
                node.setLeafMask(0xFF);
                node.setChildMask(0);
            }

            nodes[i] = node;
        }

        // Convert far pointers list to array
        int[] farPointers = farPointersList.stream().mapToInt(Integer::intValue).toArray();
        if (farPointers.length > 0) {
            log.info("Created {} far pointers for large tree", farPointers.length);
        }

        return new NodeBuildResult(nodes, farPointers);
    }
}

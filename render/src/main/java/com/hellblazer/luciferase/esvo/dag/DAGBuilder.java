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
package com.hellblazer.luciferase.esvo.dag;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builder for constructing DAG (Directed Acyclic Graph) octrees from SVO octrees.
 *
 * <p>Performs hash-based deduplication to identify and merge duplicate subtrees,
 * converting an SVO (Sparse Voxel Octree) with relative addressing into a DAG
 * with absolute addressing and shared nodes.
 *
 * <h3>Build Phases</h3>
 * <ol>
 * <li><b>HASHING (0-33%)</b>: Compute subtree hashes bottom-up</li>
 * <li><b>DEDUPLICATION (33-66%)</b>: Identify duplicate subtrees via hash comparison</li>
 * <li><b>COMPACTION (66-90%)</b>: Build compacted node pool with pointer rewriting</li>
 * <li><b>VALIDATION (90-100%)</b>: Optional structural validation</li>
 * </ol>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * var svo = loadSparseVoxelOctree();
 * var dag = DAGBuilder.from(svo)
 *     .withHashAlgorithm(HashAlgorithm.SHA256)
 *     .withCompressionStrategy(CompressionStrategy.BALANCED)
 *     .withProgressCallback(progress -> updateUI(progress))
 *     .withValidation(true)
 *     .build();
 *
 * System.out.printf("Compression ratio: %.2fx%n", dag.getCompressionRatio());
 * }</pre>
 *
 * @author hal.hildebrand
 */
public final class DAGBuilder {

    private final ESVOOctreeData source;
    private HashAlgorithm hashAlgorithm = HashAlgorithm.SHA256;
    private CompressionStrategy strategy = CompressionStrategy.BALANCED;
    private Consumer<BuildProgress> progressCallback = null;
    private boolean validateResult = true;
    // Package-private: test injection point to override hasher creation (e.g. collision-forcing stubs).
    Supplier<Hasher> hasherFactory = null;

    // Build state (computed during build())
    // nodeDigests stores the FULL digest bytes (not truncated) per node so that
    // hashToCanonical keyed on DigestKey uses the complete hash for collision safety.
    private byte[][] nodeDigests;
    private Map<DigestKey, Integer> hashToCanonical;
    private int[] oldToNew;

    /**
     * Private constructor. Use {@link #from(ESVOOctreeData)} to create instances.
     */
    private DAGBuilder(ESVOOctreeData source) {
        this.source = source;
    }

    /**
     * Create a new DAG builder from the given SVO octree.
     *
     * @param source source SVO octree (must not be null)
     * @return new DAGBuilder instance
     * @throws DAGBuildException.InvalidInputException if source is null
     */
    public static DAGBuilder from(ESVOOctreeData source) {
        if (source == null) {
            throw new DAGBuildException.InvalidInputException("Source octree must not be null");
        }
        return new DAGBuilder(source);
    }

    /**
     * Set the hash algorithm for deduplication.
     *
     * @param algorithm hash algorithm (default: SHA256)
     * @return this builder for chaining
     */
    public DAGBuilder withHashAlgorithm(HashAlgorithm algorithm) {
        this.hashAlgorithm = algorithm != null ? algorithm : HashAlgorithm.SHA256;
        return this;
    }

    /**
     * Set the compression strategy.
     *
     * @param strategy compression strategy (default: BALANCED)
     * @return this builder for chaining
     */
    public DAGBuilder withCompressionStrategy(CompressionStrategy strategy) {
        this.strategy = strategy != null ? strategy : CompressionStrategy.BALANCED;
        return this;
    }

    /**
     * Set the progress callback for async build operations.
     *
     * @param callback progress callback (null to disable)
     * @return this builder for chaining
     */
    public DAGBuilder withProgressCallback(Consumer<BuildProgress> callback) {
        this.progressCallback = callback;
        return this;
    }

    /**
     * Enable or disable result validation.
     *
     * @param validate true to enable validation (default: true)
     * @return this builder for chaining
     */
    public DAGBuilder withValidation(boolean validate) {
        this.validateResult = validate;
        return this;
    }

    /**
     * Package-private test hook: override the hasher factory used for subtree hashing.
     * Allows tests to inject deterministic or collision-forcing hashers without modifying
     * production {@link HashAlgorithm} or {@link Hasher} implementations.
     *
     * @param factory supplier that creates a fresh Hasher per node (null reverts to hashAlgorithm)
     * @return this builder for chaining
     */
    DAGBuilder withHasherFactory(Supplier<Hasher> factory) {
        this.hasherFactory = factory;
        return this;
    }

    /**
     * Build the DAG from the source SVO.
     *
     * <p>This method can be called multiple times on the same builder instance
     * to produce equivalent DAGs.
     *
     * @return constructed DAG octree
     * @throws DAGBuildException.InvalidInputException if source is empty
     * @throws DAGBuildException.ValidationFailedException if validation fails
     */
    public DAGOctreeData build() {
        var startTime = Instant.now();

        // Validate input
        if (source.getNodeCount() == 0) {
            throw new DAGBuildException.InvalidInputException("Source octree is empty (no nodes)");
        }

        // Phase 1: Hash computation (0-33%)
        reportProgress(BuildPhase.HASHING, 0);
        computeSubtreeHashes();
        reportProgress(BuildPhase.HASHING, 33);

        // Phase 2: Deduplication (33-66%)
        reportProgress(BuildPhase.DEDUPLICATION, 33);
        identifyDuplicates();
        reportProgress(BuildPhase.DEDUPLICATION, 66);

        // Phase 3: Compaction (66-90%)
        reportProgress(BuildPhase.COMPACTION, 66);
        var dagResult = buildCompactedDAG();
        reportProgress(BuildPhase.COMPACTION, 90);

        // Phase 4: Optional validation (90-100%)
        if (validateResult) {
            reportProgress(BuildPhase.VALIDATION, 90);
            validateDAG(dagResult.nodes());
            reportProgress(BuildPhase.VALIDATION, 100);
        }

        // Build metadata
        var buildTime = Duration.between(startTime, Instant.now());
        var metadata = buildMetadata(dagResult.nodes(), dagResult.childPointers(), buildTime);

        // Complete
        reportProgress(BuildPhase.COMPLETE, 100);

        return new DAGOctreeDataImpl(dagResult.nodes(), dagResult.childPointers(), metadata);
    }

    /**
     * Phase 1: Compute subtree hashes bottom-up.
     *
     * <p>Visit each node in post-order (children before parents) and compute
     * a hash that represents the entire subtree rooted at that node.
     *
     * <p>Hash includes:
     * <ul>
     * <li>Node's child descriptor (structure)</li>
     * <li>Node's contour descriptor (attributes)</li>
     * <li>Hashes of all children (recursive structure)</li>
     * </ul>
     */
    private void computeSubtreeHashes() {
        var indices = source.getNodeIndices();

        // Find max index to size the hash array appropriately
        var maxIdx = 0;
        for (var idx : indices) {
            maxIdx = Math.max(maxIdx, idx);
        }

        nodeDigests = new byte[maxIdx + 1][];

        // Process nodes in reverse order to ensure children are hashed before parents
        for (int i = indices.length - 1; i >= 0; i--) {
            var nodeIdx = indices[i];
            var node = source.getNode(nodeIdx);

            if (node == null) continue;

            // Create fresh hasher for each node (test hook overrides hashAlgorithm if set)
            var hasher = (hasherFactory != null) ? hasherFactory.get() : hashAlgorithm.createHasher();

            // Start with node's own data
            hasher.update(node.getChildDescriptor());
            hasher.update(node.getContourDescriptor());

            // Include full digest bytes of all children to propagate structural identity.
            // Using the full bytes (not a truncated long) ensures two subtrees with
            // different structures cannot match even if their 64-bit truncations collide.
            var childMask = node.getChildMask();
            if (childMask != 0) {
                for (int octant = 0; octant < 8; octant++) {
                    if (node.hasChild(octant)) {
                        var childIdx = node.getChildIndex(octant, nodeIdx, source.getFarPointers());
                        if (childIdx >= 0 && childIdx < nodeDigests.length && nodeDigests[childIdx] != null) {
                            for (byte b : nodeDigests[childIdx]) {
                                hasher.update(b);
                            }
                        }
                    }
                }
            }

            nodeDigests[nodeIdx] = hasher.digestBytes();
        }
    }

    /**
     * Phase 2: Identify duplicate subtrees using hash comparison.
     *
     * <p>Build a map from hash values to canonical node indices. The first
     * occurrence of each hash becomes the canonical representation; subsequent
     * occurrences are marked as duplicates.
     */
    private void identifyDuplicates() {
        hashToCanonical = new HashMap<>();
        var indices = source.getNodeIndices();

        for (var nodeIdx : indices) {
            var key = new DigestKey(nodeDigests[nodeIdx]);

            // First occurrence becomes canonical
            hashToCanonical.putIfAbsent(key, nodeIdx);
        }
    }

    /**
     * Phase 3: Build compacted DAG with pointer rewriting.
     *
     * <p>Create a new node pool containing only canonical nodes, and rewrite
     * all child pointers to use absolute addressing.
     *
     * @return array of compacted nodes with absolute pointers
     */
    /**
     * Result of DAG compaction: nodes + child pointer indirection array.
     */
    private record CompactedDAGResult(ESVONodeUnified[] nodes, int[] childPointers) {}

    private CompactedDAGResult buildCompactedDAG() {
        var indices = source.getNodeIndices();

        // Find max index for array sizing
        var maxIdx = 0;
        for (var idx : indices) {
            maxIdx = Math.max(maxIdx, idx);
        }

        oldToNew = new int[maxIdx + 1];
        Arrays.fill(oldToNew, -1);

        // Step 1: Assign new indices to canonical nodes
        var canonicalNodes = new ArrayList<Integer>();
        for (var nodeIdx : indices) {
            var key = new DigestKey(nodeDigests[nodeIdx]);
            var canonical = hashToCanonical.get(key);

            if (canonical == nodeIdx) {
                // This is a canonical node
                oldToNew[nodeIdx] = canonicalNodes.size();
                canonicalNodes.add(nodeIdx);
            } else {
                // This is a duplicate - map to canonical
                oldToNew[nodeIdx] = oldToNew[canonical];
            }
        }

        // Step 2: Count total child pointers needed
        var childPointerList = new ArrayList<Integer>();

        // Step 3: Build compacted node array with child pointer indirection
        var compacted = new ESVONodeUnified[canonicalNodes.size()];
        for (int newIdx = 0; newIdx < canonicalNodes.size(); newIdx++) {
            var oldIdx = canonicalNodes.get(newIdx);
            var oldNode = source.getNode(oldIdx);

            // Create new node with absolute addressing
            var newNode = new ESVONodeUnified(
                oldNode.getChildDescriptor(),
                oldNode.getContourDescriptor()
            );

            // Build child pointer indirection
            if (oldNode.getChildMask() != 0) {
                // childPtr points to the start of this node's children in the childPointers array
                newNode.setChildPtr(childPointerList.size());
                newNode.setFar(false);

                // Add all children to the child pointer array
                for (int octant = 0; octant < 8; octant++) {
                    if (oldNode.hasChild(octant)) {
                        var oldChildIdx = oldNode.getChildIndex(octant, oldIdx, source.getFarPointers());

                        // Bounds check
                        if (oldChildIdx < 0 || oldChildIdx >= oldToNew.length) {
                            throw new DAGBuildException.InvalidInputException(
                                "Child index " + oldChildIdx + " out of bounds [0, " + oldToNew.length + ")"
                            );
                        }

                        var newChildIdx = oldToNew[oldChildIdx];
                        if (newChildIdx < 0) {
                            throw new DAGBuildException.ValidationFailedException(
                                "Child node " + oldChildIdx + " was not assigned a new index (unmapped node)"
                            );
                        }

                        childPointerList.add(newChildIdx);
                    }
                }
            }

            compacted[newIdx] = newNode;
        }

        // Convert child pointer list to array
        var childPointers = childPointerList.stream().mapToInt(Integer::intValue).toArray();

        return new CompactedDAGResult(compacted, childPointers);
    }

    /**
     * Phase 4: Validate the constructed DAG.
     *
     * @param nodes DAG nodes to validate
     * @throws DAGBuildException.ValidationFailedException if validation fails
     */
    private void validateDAG(ESVONodeUnified[] nodes) {
        // Basic structural validation
        if (nodes.length == 0) {
            throw new DAGBuildException.ValidationFailedException("DAG has no nodes");
        }

        // Validate all nodes are valid
        for (var node : nodes) {
            if (node == null) {
                throw new DAGBuildException.ValidationFailedException("DAG contains null nodes");
            }
            if (!node.isValid()) {
                throw new DAGBuildException.ValidationFailedException("DAG contains invalid nodes");
            }
        }

        // Validate root exists
        if (nodes[0] == null) {
            throw new DAGBuildException.ValidationFailedException("DAG root node is null");
        }
    }

    /**
     * Build comprehensive metadata for the constructed DAG.
     *
     * <p>Per-depth sharing is computed by a BFS over the <em>source</em> nodes
     * (before compaction) that tracks each node's depth and counts, per depth
     * level, how many source nodes were deduplicated to a canonical node at a
     * shallower index.  The result has one entry per depth level that contained
     * at least one shared subtree.
     *
     * <p>{@code sourceHash} is the first 8 bytes (little-endian) of the SHA-256
     * subtree hash already computed for the root node during Phase 1 – a real
     * content hash that changes whenever the source structure changes.
     */
    private DAGMetadata buildMetadata(ESVONodeUnified[] compactedNodes, int[] childPointers, Duration buildTime) {
        var uniqueCount = compactedNodes.length;
        var originalCount = source.getNodeCount();

        // Count shared subtrees (nodes that were deduplicated)
        var sharedCount = originalCount - uniqueCount;

        // Compute real per-depth sharing via BFS over source nodes.
        // For each source node that is NOT its own canonical (i.e. was deduplicated),
        // record a miss at its depth.
        var sharingByDepth = new HashMap<Integer, Integer>();
        if (sharedCount > 0) {
            var indices = source.getNodeIndices();
            // Build a depth map: nodeIdx -> depth, BFS from root (index 0)
            var depthMap = new HashMap<Integer, Integer>();
            var bfsQueue = new java.util.ArrayDeque<int[]>(); // [nodeIdx, depth]
            bfsQueue.offer(new int[] { indices[0], 0 });
            while (!bfsQueue.isEmpty()) {
                var cur = bfsQueue.poll();
                var nIdx = cur[0];
                var depth = cur[1];
                if (depthMap.containsKey(nIdx)) continue;
                depthMap.put(nIdx, depth);
                var node = source.getNode(nIdx);
                if (node == null || node.getChildMask() == 0) continue;
                for (int oct = 0; oct < 8; oct++) {
                    if (node.hasChild(oct)) {
                        var childIdx = node.getChildIndex(oct, nIdx, source.getFarPointers());
                        if (childIdx >= 0 && !depthMap.containsKey(childIdx)) {
                            bfsQueue.offer(new int[] { childIdx, depth + 1 });
                        }
                    }
                }
            }
            // For every source node that is a duplicate (not canonical), count it at its depth.
            for (var nodeIdx : indices) {
                if (nodeDigests == null || nodeIdx >= nodeDigests.length || nodeDigests[nodeIdx] == null) continue;
                var key = new DigestKey(nodeDigests[nodeIdx]);
                var canonical = hashToCanonical.get(key);
                if (canonical != null && canonical != nodeIdx) {
                    // This node was deduplicated; credit the sharing at its depth.
                    // Skip orphaned/unreachable nodes: they are not part of the rooted DAG
                    // and must not corrupt per-depth sharing attribution (they would all
                    // silently land at depth 0 via getOrDefault, which is wrong).
                    if (!depthMap.containsKey(nodeIdx)) continue;
                    var depth = depthMap.get(nodeIdx);
                    sharingByDepth.merge(depth, 1, Integer::sum);
                }
            }
        }

        // Compute real sourceHash: first 8 bytes (little-endian) of the root's
        // subtree digest computed during Phase 1.  Falls back to node-count if
        // the root digest is unavailable (should not happen in normal flow).
        long sourceHash;
        var indices = source.getNodeIndices();
        if (nodeDigests != null && indices.length > 0) {
            var rootDigest = nodeDigests[indices[0]];
            if (rootDigest != null && rootDigest.length >= 8) {
                long h = 0L;
                for (int i = 0; i < 8; i++) {
                    h |= ((long) (rootDigest[i] & 0xFF)) << (i * 8);
                }
                sourceHash = h;
            } else {
                sourceHash = (long) source.getNodeCount();
            }
        } else {
            sourceHash = (long) source.getNodeCount();
        }

        // Estimate max depth
        var maxDepth = estimateMaxDepth(compactedNodes, childPointers);

        return new DAGMetadata(
            uniqueCount,
            originalCount,
            maxDepth,
            sharedCount,
            sharingByDepth,
            buildTime,
            hashAlgorithm,
            strategy,
            sourceHash
        );
    }

    /**
     * Canonical sparse-offset helper: the number of set bits in {@code childMask}
     * that are strictly below {@code octant}.  This is the compacted-array index
     * of {@code octant}'s child pointer relative to the node's {@code childPtr}
     * base.
     *
     * <p>All three sites that navigate the compacted {@code childPointers} array
     * ({@link #buildCompactedDAG}, {@link #estimateMaxDepth}, and
     * {@link DAGOctreeDataImpl#resolveChildIndex}) must use this method so that a
     * future change to the layout only needs to be made in one place.
     *
     * @param childMask the node's 8-bit child presence mask
     * @param octant    the octant being looked up (0–7)
     * @return sparse offset in [0, popcount(childMask))
     */
    private static int sparseOffset(int childMask, int octant) {
        return Integer.bitCount(childMask & ((1 << octant) - 1));
    }

    /**
     * Compute the true maximum (longest) root-to-leaf depth in the DAG.
     *
     * <p>The DAG may share nodes: the same node can be reached via a short path
     * <em>and</em> a longer path.  A visited-set BFS would record only the first
     * (shortest) arrival depth and never revisit the node, causing it to
     * underestimate the true longest path.  Instead this method tracks the
     * <em>maximum</em> depth at which each node has been seen and re-enqueues
     * the node's children whenever a longer path arrives — guaranteed to
     * terminate because depths are bounded by the number of nodes and the DAG
     * is acyclic.  Uses {@link #sparseOffset} to index into {@code childPointers}
     * — the single canonical site for this arithmetic so that layout and
     * traversal cannot diverge.
     *
     * <p>Consumers of {@link DAGMetadata#maxDepth()} (GPU workgroup sizing,
     * traversal stack allocation, LOD decisions) require a true upper bound;
     * underestimation would silently truncate traversal stacks.
     */
    private int estimateMaxDepth(ESVONodeUnified[] nodes, int[] childPointers) {
        if (nodes.length == 0) return 0;
        if (nodes.length == 1) return 0;

        // maxDepthAtNode[i] = deepest depth reached so far when visiting node i.
        // -1 means not yet visited.
        var maxDepthAtNode = new int[nodes.length];
        java.util.Arrays.fill(maxDepthAtNode, -1);

        var queue = new java.util.ArrayDeque<int[]>(); // [nodeIdx, depth]
        queue.offer(new int[]{0, 0});
        maxDepthAtNode[0] = 0;

        var maxDepth = 0;

        while (!queue.isEmpty()) {
            var current = queue.poll();
            var nodeIdx = current[0];
            var depth = current[1];

            // Stale entry: a longer path was already processed for this node at a
            // greater depth. Skip — the children were (or will be) enqueued from
            // that longer path.
            if (depth < maxDepthAtNode[nodeIdx]) {
                continue;
            }

            maxDepth = Math.max(maxDepth, depth);

            var node = nodes[nodeIdx];
            if (node == null || node.getChildMask() == 0) {
                continue; // Leaf node
            }

            // Propagate to children; re-enqueue if a longer path reaches a child
            var childMask = node.getChildMask();
            for (int octant = 0; octant < 8; octant++) {
                if (node.hasChild(octant)) {
                    var childPtrArrayIdx = node.getChildPtr() + sparseOffset(childMask, octant);

                    if (childPtrArrayIdx >= 0 && childPtrArrayIdx < childPointers.length) {
                        var childIdx = childPointers[childPtrArrayIdx];
                        var childDepth = depth + 1;

                        if (childIdx >= 0 && childIdx < nodes.length
                                && childDepth > maxDepthAtNode[childIdx]) {
                            maxDepthAtNode[childIdx] = childDepth;
                            queue.offer(new int[]{childIdx, childDepth});
                        }
                    }
                }
            }
        }

        return maxDepth;
    }

    /**
     * Report progress to callback if configured.
     */
    private void reportProgress(BuildPhase phase, int percent) {
        if (progressCallback != null) {
            progressCallback.accept(BuildProgress.of(phase, percent));
        }
    }

    /**
     * Internal implementation of DAGOctreeData.
     */
    private static class DAGOctreeDataImpl implements DAGOctreeData {
        private final ESVONodeUnified[] nodes;
        private final int[] childPointers;  // Indirection array for child node indices
        private final DAGMetadata metadata;

        DAGOctreeDataImpl(ESVONodeUnified[] nodes, int[] childPointers, DAGMetadata metadata) {
            this.nodes = nodes;
            this.childPointers = childPointers;
            this.metadata = metadata;
        }

        @Override
        public ESVONodeUnified[] nodes() {
            return nodes;
        }

        @Override
        public DAGMetadata getMetadata() {
            return metadata;
        }

        @Override
        public float getCompressionRatio() {
            return metadata.compressionRatio();
        }

        @Override
        public int[] getContours() {
            return new int[0]; // Contours stored separately
        }

        @Override
        public java.nio.ByteBuffer nodesToByteBuffer() {
            var buffer = java.nio.ByteBuffer.allocateDirect(nodes.length * ESVONodeUnified.SIZE_BYTES)
                                            .order(java.nio.ByteOrder.nativeOrder());
            for (var node : nodes) {
                node.writeTo(buffer);
            }
            buffer.flip();
            return buffer;
        }

        @Override
        public com.hellblazer.luciferase.sparse.core.CoordinateSpace getCoordinateSpace() {
            return com.hellblazer.luciferase.sparse.core.CoordinateSpace.UNIT_CUBE;
        }

        @Override
        public int[] getFarPointers() {
            return childPointers; // Repurpose for child pointer indirection array
        }

        @Override
        public int resolveChildIndex(int parentIdx, ESVONodeUnified node, int octant) {
            if (octant < 0 || octant > 7) {
                throw new IndexOutOfBoundsException("Octant must be in [0, 7], got: " + octant);
            }

            // Compute sparse index via the canonical helper (same formula as buildCompactedDAG
            // and estimateMaxDepth — single source of truth so layout cannot diverge).
            int sparseIdx = sparseOffset(node.getChildMask(), octant);

            // childPtr is an index into the childPointers array
            // childPointers[childPtr + sparseIdx] contains the actual node index
            int childPtrArrayIdx = node.getChildPtr() + sparseIdx;

            if (childPtrArrayIdx < 0 || childPtrArrayIdx >= childPointers.length) {
                throw new IndexOutOfBoundsException(
                    "Child pointer index " + childPtrArrayIdx + " out of bounds [0, " + childPointers.length + ")"
                );
            }

            return childPointers[childPtrArrayIdx];
        }

        // SpatialData interface methods
        @Override
        public int nodeCount() {
            return nodes.length;
        }

        @Override
        public int maxDepth() {
            return metadata.maxDepth();
        }

        @Override
        public int leafCount() {
            int count = 0;
            for (var node : nodes) {
                if (node.getChildMask() == 0) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int internalCount() {
            int count = 0;
            for (var node : nodes) {
                if (node.getChildMask() != 0) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int sizeInBytes() {
            return nodes.length * ESVONodeUnified.SIZE_BYTES;
        }
    }
}

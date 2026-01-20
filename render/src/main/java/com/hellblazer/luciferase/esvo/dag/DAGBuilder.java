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

    // Build state (computed during build())
    private long[] nodeHashes;
    private Map<Long, Integer> hashToCanonical;
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

        nodeHashes = new long[maxIdx + 1];

        // Process nodes in reverse order to ensure children are hashed before parents
        for (int i = indices.length - 1; i >= 0; i--) {
            var nodeIdx = indices[i];
            var node = source.getNode(nodeIdx);

            if (node == null) continue;

            // Create fresh hasher for each node
            var hasher = hashAlgorithm.createHasher();

            // Start with node's own data
            hasher.update(node.getChildDescriptor());
            hasher.update(node.getContourDescriptor());

            // Include hashes of all children
            var childMask = node.getChildMask();
            if (childMask != 0) {
                for (int octant = 0; octant < 8; octant++) {
                    if (node.hasChild(octant)) {
                        var childIdx = node.getChildIndex(octant, nodeIdx, source.getFarPointers());
                        if (childIdx >= 0 && childIdx < nodeHashes.length) {
                            hasher.update(nodeHashes[childIdx]);
                        }
                    }
                }
            }

            nodeHashes[nodeIdx] = hasher.digest();
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
            var hash = nodeHashes[nodeIdx];

            // First occurrence becomes canonical
            hashToCanonical.putIfAbsent(hash, nodeIdx);
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
            var hash = nodeHashes[nodeIdx];
            var canonical = hashToCanonical.get(hash);

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
     */
    private DAGMetadata buildMetadata(ESVONodeUnified[] compactedNodes, int[] childPointers, Duration buildTime) {
        var uniqueCount = compactedNodes.length;
        var originalCount = source.getNodeCount();

        // Count shared subtrees (nodes that were deduplicated)
        var sharedCount = originalCount - uniqueCount;

        // Build sharing-by-depth map (simplified - estimate from node structure)
        var sharingByDepth = new HashMap<Integer, Integer>();
        // In a real implementation, would track depth during traversal
        if (sharedCount > 0) {
            sharingByDepth.put(0, sharedCount);
        }

        // Compute source hash (simplified - use node count as proxy)
        var sourceHash = (long) source.getNodeCount();

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
     * Estimate maximum tree depth from node structure.
     *
     * <p>Traverses from root to find the deepest path using iterative approach
     * to avoid stack overflow with shared nodes.
     */
    private int estimateMaxDepth(ESVONodeUnified[] nodes, int[] childPointers) {
        if (nodes.length == 0) return 0;
        if (nodes.length == 1) return 0;

        // BFS to find maximum depth, tracking visited nodes to avoid cycles
        var queue = new java.util.ArrayDeque<int[]>(); // [nodeIdx, depth]
        var visited = new java.util.HashSet<Integer>();

        queue.offer(new int[]{0, 0}); // Start at root with depth 0
        var maxDepth = 0;

        while (!queue.isEmpty()) {
            var current = queue.poll();
            var nodeIdx = current[0];
            var depth = current[1];

            if (nodeIdx < 0 || nodeIdx >= nodes.length || visited.contains(nodeIdx)) {
                continue;
            }

            visited.add(nodeIdx);
            maxDepth = Math.max(maxDepth, depth);

            var node = nodes[nodeIdx];
            if (node == null || node.getChildMask() == 0) {
                continue; // Leaf node
            }

            // Add all children to queue using child pointer indirection
            for (int octant = 0; octant < 8; octant++) {
                if (node.hasChild(octant)) {
                    // Use child pointer indirection array
                    var sparseIdx = node.getChildOffset(octant);
                    var childPtrArrayIdx = node.getChildPtr() + sparseIdx;

                    if (childPtrArrayIdx >= 0 && childPtrArrayIdx < childPointers.length) {
                        var childIdx = childPointers[childPtrArrayIdx];

                        if (childIdx >= 0 && childIdx < nodes.length && !visited.contains(childIdx)) {
                            queue.offer(new int[]{childIdx, depth + 1});
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

            // Compute sparse index (how many children come before this octant)
            int sparseIdx = Integer.bitCount(node.getChildMask() & ((1 << octant) - 1));

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

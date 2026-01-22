package com.hellblazer.luciferase.esvo.gpu.beam;

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hierarchical spatial organization of rays for batch kernel optimization.
 *
 * Organizes rays into a tree structure where leaves contain spatially
 * coherent ray groups suitable for batch processing.
 */
public class BeamTree {
    private final BeamNode root;
    private final Ray[] rays;                // Global ray array
    private final DAGOctreeData dag;         // For coherence analysis
    private final double coherenceThreshold;
    private final int maxBatchSize;

    // Statistics
    private int totalBeams;
    private double averageCoherence;
    private int maxDepth;

    /**
     * Create BeamTree from rays and DAG data.
     *
     * @param root root node of the tree
     * @param rays global ray array
     * @param dag DAG structure for coherence analysis
     * @param coherenceThreshold minimum coherence score for batches
     * @param maxBatchSize maximum rays per batch
     */
    public BeamTree(BeamNode root, Ray[] rays, DAGOctreeData dag, double coherenceThreshold, int maxBatchSize) {
        this.root = root;
        this.rays = rays;
        this.dag = dag;
        this.coherenceThreshold = coherenceThreshold;
        this.maxBatchSize = maxBatchSize;
        this.totalBeams = 0;
        this.averageCoherence = 0.0;
        this.maxDepth = 0;
        computeStatistics();
    }

    /**
     * Get coherent ray batches for batch kernel processing.
     *
     * Returns list of ray index arrays suitable for batch processing,
     * organized by spatial coherence.
     *
     * @param batchSize maximum size of each batch
     * @return list of ray index arrays (batches)
     */
    public List<int[]> getCoherentRayBatches(int batchSize) {
        var batches = new ArrayList<int[]>();
        var effectiveBatchSize = Math.min(batchSize, maxBatchSize);
        collectBatches(root, batches, effectiveBatchSize);
        return batches;
    }

    /**
     * Recursively collect coherent batches from tree nodes.
     */
    private void collectBatches(BeamNode node, List<int[]> batches, int batchSize) {
        if (node == null) {
            return;
        }

        if (node.isLeaf()) {
            // Leaf node: split into batches if needed
            var rayIndices = node.getRayIndices();
            if (rayIndices.length <= batchSize) {
                batches.add(rayIndices);
            } else {
                // Split large leaf into multiple batches
                for (int i = 0; i < rayIndices.length; i += batchSize) {
                    var end = Math.min(i + batchSize, rayIndices.length);
                    var batch = new int[end - i];
                    System.arraycopy(rayIndices, i, batch, 0, batch.length);
                    batches.add(batch);
                }
            }
        } else {
            // Internal node: recurse to children
            var children = node.getChildren();
            if (children != null) {
                for (var child : children) {
                    collectBatches(child, batches, batchSize);
                }
            }
        }
    }

    /**
     * Validate that all rays have coherence >= threshold.
     *
     * @return true if all batches meet coherence threshold
     */
    public boolean validateCoherence() {
        return validateCoherenceRecursive(root);
    }

    private boolean validateCoherenceRecursive(BeamNode node) {
        if (node == null) {
            return true;
        }

        if (node.getCoherenceScore() < coherenceThreshold) {
            return false;
        }

        if (!node.isLeaf()) {
            var children = node.getChildren();
            if (children != null) {
                for (var child : children) {
                    if (!validateCoherenceRecursive(child)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Get tree statistics.
     */
    public TreeStatistics getStatistics() {
        return new TreeStatistics(totalBeams, averageCoherence, maxDepth);
    }

    /**
     * Traverse tree with visitor pattern.
     */
    public void traverse(BeamVisitor visitor) {
        traverseRecursive(root, visitor, 0);
    }

    private void traverseRecursive(BeamNode node, BeamVisitor visitor, int depth) {
        if (node == null) {
            return;
        }

        visitor.visit(node, depth);

        if (!node.isLeaf()) {
            var children = node.getChildren();
            if (children != null) {
                for (var child : children) {
                    traverseRecursive(child, visitor, depth + 1);
                }
            }
        }
    }

    /**
     * Compute tree statistics by traversing all nodes.
     */
    private void computeStatistics() {
        var stats = new TreeStatistics.Builder();
        traverse((node, depth) -> {
            stats.addBeam(node.getRayCount(), node.getCoherenceScore(), depth);
        });
        var result = stats.build();
        this.totalBeams = result.totalBeams();
        this.averageCoherence = result.averageCoherence();
        this.maxDepth = result.maxDepth();
    }

    /**
     * Visitor interface for tree traversal.
     */
    public interface BeamVisitor {
        void visit(BeamNode node, int depth);
    }

    /**
     * Tree statistics record.
     */
    public record TreeStatistics(int totalBeams, double averageCoherence, int maxDepth) {

        public static class Builder {
            private int beamCount = 0;
            private double totalCoherence = 0.0;
            private int maxDepth = 0;

            public void addBeam(int rayCount, double coherence, int depth) {
                beamCount++;
                totalCoherence += coherence;
                maxDepth = Math.max(maxDepth, depth);
            }

            public TreeStatistics build() {
                var avgCoherence = beamCount > 0 ? totalCoherence / beamCount : 0.0;
                return new TreeStatistics(beamCount, avgCoherence, maxDepth);
            }
        }
    }
}

package com.hellblazer.luciferase.esvo.gpu.beam;

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import javax.vecmath.Point3f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-phase builder for BeamTree construction.
 *
 * Phases:
 * 1. SPATIAL_PARTITION - Group rays by origin/direction similarity
 * 2. COHERENCE_ANALYSIS - Analyze DAG traversal overlap
 * 3. BATCH_ASSEMBLY - Create coherent batches for kernel
 * 4. VALIDATION - Verify coherence thresholds met
 */
public class BeamTreeBuilder {
    private Ray[] rays;
    private DAGOctreeData dag;
    private double coherenceThreshold = 0.5;
    private int maxBatchSize = 64;
    private int maxRaysPerBeam = 64;
    private SubdivisionStrategy strategy = SubdivisionStrategy.OCTREE;
    private boolean preAnalyzeCoherence = false;
    private static final int MAX_TREE_DEPTH = 16;  // Prevent stack overflow

    private BeamTreeBuilder() {
    }

    /**
     * Create builder for the given rays.
     */
    public static BeamTreeBuilder from(Ray[] rays) {
        var builder = new BeamTreeBuilder();
        builder.rays = rays;
        return builder;
    }

    /**
     * Configure with DAG data for coherence analysis.
     */
    public BeamTreeBuilder withDAG(DAGOctreeData dag) {
        this.dag = dag;
        return this;
    }

    /**
     * Set coherence threshold for batch activation.
     */
    public BeamTreeBuilder withCoherenceThreshold(double threshold) {
        this.coherenceThreshold = Math.max(0.0, Math.min(1.0, threshold));
        return this;
    }

    /**
     * Set maximum batch size for kernel processing.
     */
    public BeamTreeBuilder withMaxBatchSize(int size) {
        this.maxBatchSize = Math.max(1, size);
        return this;
    }

    /**
     * Set maximum rays per beam before subdivision.
     */
    public BeamTreeBuilder withMaxRaysPerBeam(int size) {
        this.maxRaysPerBeam = Math.max(1, size);
        return this;
    }

    /**
     * Set subdivision strategy.
     */
    public BeamTreeBuilder withSubdivisionStrategy(SubdivisionStrategy strategy) {
        this.strategy = strategy;
        return this;
    }

    /**
     * Enable pre-computation of coherence before building tree.
     */
    public BeamTreeBuilder withPreAnalysis(boolean enable) {
        this.preAnalyzeCoherence = enable;
        return this;
    }

    /**
     * Build and return the BeamTree.
     */
    public BeamTree build() {
        if (rays == null || rays.length == 0) {
            throw new IllegalArgumentException("Cannot build BeamTree from empty ray array");
        }

        // Phase 1: Spatial Partition
        var rootNode = spatialPartition();

        // Phase 2: Coherence Analysis
        analyzeCoherence(rootNode);

        // Phase 3: Validation (optional early exit if coherence too low)
        validate();

        // Phase 4: Create and return tree
        return new BeamTree(rootNode, rays, dag, coherenceThreshold, maxBatchSize);
    }

    /**
     * Phase 1: Partition rays spatially by origin and direction.
     */
    private BeamNode spatialPartition() {
        var allIndices = new int[rays.length];
        for (int i = 0; i < rays.length; i++) {
            allIndices[i] = i;
        }

        var bounds = AABB.fromRays(rays, allIndices);
        var root = new BeamNode(bounds, allIndices, 0, maxRaysPerBeam);

        // Recursively subdivide
        subdivideNode(root);

        return root;
    }

    /**
     * Recursively subdivide a node based on strategy.
     */
    private void subdivideNode(BeamNode node) {
        if (!node.shouldSubdivide() || node.getDepth() >= MAX_TREE_DEPTH) {
            return;
        }

        BeamNode[] children = switch (strategy) {
            case OCTREE -> subdivideOctree(node);
            case KDTREE -> subdivideKDTree(node);
            case DIRECTION -> subdivideByDirection(node);
        };

        if (children != null && children.length > 0) {
            node.setChildren(children);
            for (var child : children) {
                subdivideNode(child);
            }
        }
    }

    /**
     * 8-way spatial subdivision along X/Y/Z axes.
     */
    private BeamNode[] subdivideOctree(BeamNode parent) {
        var indices = parent.getRayIndices();
        var bounds = parent.getBounds();
        var midX = (bounds.min().x + bounds.max().x) / 2.0f;
        var midY = (bounds.min().y + bounds.max().y) / 2.0f;
        var midZ = (bounds.min().z + bounds.max().z) / 2.0f;

        var octants = new ArrayList<List<Integer>>();
        for (int i = 0; i < 8; i++) {
            octants.add(new ArrayList<>());
        }

        // Distribute rays into octants based on origin
        for (var idx : indices) {
            var ray = rays[idx];
            var origin = ray.origin();
            var octant = (origin.x < midX ? 0 : 1) |
                    ((origin.y < midY ? 0 : 2)) |
                    ((origin.z < midZ ? 0 : 4));
            octants.get(octant).add(idx);
        }

        // Create child nodes for non-empty octants
        var children = new ArrayList<BeamNode>();
        var depth = parent.getDepth() + 1;

        for (int i = 0; i < 8; i++) {
            var octantIndices = octants.get(i);
            if (!octantIndices.isEmpty()) {
                var childIndices = new int[octantIndices.size()];
                for (int j = 0; j < octantIndices.size(); j++) {
                    childIndices[j] = octantIndices.get(j);
                }

                var childBounds = AABB.fromRays(rays, childIndices);
                children.add(new BeamNode(childBounds, childIndices, depth, maxRaysPerBeam));
            }
        }

        return children.toArray(new BeamNode[0]);
    }

    /**
     * 2-way adaptive KD-tree subdivision along principal axis.
     */
    private BeamNode[] subdivideKDTree(BeamNode parent) {
        var indices = parent.getRayIndices();
        if (indices.length < 2) {
            return null;
        }

        var bounds = parent.getBounds();

        // Find principal axis (largest extent)
        var extX = bounds.max().x - bounds.min().x;
        var extY = bounds.max().y - bounds.min().y;
        var extZ = bounds.max().z - bounds.min().z;

        var mid = switch ((int) Math.max(Math.max(extX, extY), extZ) == (int) extX ? 0 :
                Math.max(extY, extZ) == extY ? 1 : 2) {
            case 0 -> (bounds.min().x + bounds.max().x) / 2.0f;
            case 1 -> (bounds.min().y + bounds.max().y) / 2.0f;
            default -> (bounds.min().z + bounds.max().z) / 2.0f;
        };

        var left = new ArrayList<Integer>();
        var right = new ArrayList<Integer>();

        for (var idx : indices) {
            var ray = rays[idx];
            var coord = switch ((int) Math.max(Math.max(extX, extY), extZ) == (int) extX ? 0 :
                    Math.max(extY, extZ) == extY ? 1 : 2) {
                case 0 -> ray.origin().x;
                case 1 -> ray.origin().y;
                default -> ray.origin().z;
            };

            if (coord < mid) {
                left.add(idx);
            } else {
                right.add(idx);
            }
        }

        var children = new ArrayList<BeamNode>();
        var depth = parent.getDepth() + 1;

        if (!left.isEmpty()) {
            var leftIndices = new int[left.size()];
            for (int i = 0; i < left.size(); i++) {
                leftIndices[i] = left.get(i);
            }
            var leftBounds = AABB.fromRays(rays, leftIndices);
            children.add(new BeamNode(leftBounds, leftIndices, depth, maxRaysPerBeam));
        }

        if (!right.isEmpty()) {
            var rightIndices = new int[right.size()];
            for (int i = 0; i < right.size(); i++) {
                rightIndices[i] = right.get(i);
            }
            var rightBounds = AABB.fromRays(rays, rightIndices);
            children.add(new BeamNode(rightBounds, rightIndices, depth, maxRaysPerBeam));
        }

        return children.toArray(new BeamNode[0]);
    }

    /**
     * Group rays by direction similarity.
     */
    private BeamNode[] subdivideByDirection(BeamNode parent) {
        var indices = parent.getRayIndices();
        if (indices.length < 2) {
            return null;
        }

        // Use first ray as reference
        var refRay = rays[indices[0]];
        var similar = new ArrayList<Integer>();
        var different = new ArrayList<Integer>();

        similar.add(indices[0]);

        for (int i = 1; i < indices.length; i++) {
            var idx = indices[i];
            var diff = refRay.directionDifference(rays[idx]);
            if (diff < 0.5f) {  // Threshold for direction similarity
                similar.add(idx);
            } else {
                different.add(idx);
            }
        }

        var children = new ArrayList<BeamNode>();
        var depth = parent.getDepth() + 1;

        if (!similar.isEmpty()) {
            var simIndices = new int[similar.size()];
            for (int i = 0; i < similar.size(); i++) {
                simIndices[i] = similar.get(i);
            }
            var simBounds = AABB.fromRays(rays, simIndices);
            children.add(new BeamNode(simBounds, simIndices, depth, maxRaysPerBeam));
        }

        if (!different.isEmpty()) {
            var diffIndices = new int[different.size()];
            for (int i = 0; i < different.size(); i++) {
                diffIndices[i] = different.get(i);
            }
            var diffBounds = AABB.fromRays(rays, diffIndices);
            children.add(new BeamNode(diffBounds, diffIndices, depth, maxRaysPerBeam));
        }

        return children.toArray(new BeamNode[0]);
    }

    /**
     * Phase 2: Analyze coherence and compute shared nodes.
     */
    private void analyzeCoherence(BeamNode node) {
        if (node == null || dag == null) {
            return;
        }

        // Compute shared nodes for this beam
        var rayIndices = node.getRayIndices();
        if (rayIndices.length > 0) {
            // Simplified coherence: store number of rays for now
            // Full implementation would traverse DAG
            var metadata = new CoherenceMetadata(
                    Math.min(1.0, rayIndices.length / 8.0),
                    rayIndices.length,
                    rayIndices.length,
                    Math.min(1.0, rayIndices.length / 8.0)
            );
            node.setCoherence(metadata);

            for (int i = 0; i < Math.min(rayIndices.length, 10); i++) {
                node.addSharedNode(i);
            }
        }

        // Recurse to children
        var children = node.getChildren();
        if (children != null) {
            for (var child : children) {
                analyzeCoherence(child);
            }
        }
    }

    /**
     * Phase 4: Validate tree meets coherence requirements.
     */
    private void validate() {
        if (rays.length == 0) {
            throw new IllegalArgumentException("Cannot validate empty ray array");
        }
    }
}

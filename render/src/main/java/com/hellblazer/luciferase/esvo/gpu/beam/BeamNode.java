package com.hellblazer.luciferase.esvo.gpu.beam;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents a hierarchical group of spatially coherent rays in the BeamTree.
 *
 * Coherence is measured by:
 * - Spatial proximity (origin + direction similarity)
 * - Shared DAG node traversal
 * - Cache locality benefits
 */
public class BeamNode {
    // Spatial bounds of rays in this beam
    private final AABB bounds;

    // Ray indices in global ray array
    private final int[] rayIndices;

    // Child beams (hierarchical subdivision)
    private BeamNode[] children;

    // Pre-computed coherence metrics
    private CoherenceMetadata coherence;

    // Shared DAG nodes visited by all rays in beam
    private final Set<Integer> sharedNodes;

    // Beam properties
    private final int depth;
    private final int maxRaysPerBeam;

    /**
     * Create a new beam node with the given spatial bounds and ray indices.
     *
     * @param bounds spatial AABB encompassing ray origins
     * @param rayIndices indices into global ray array
     * @param depth hierarchical depth in tree
     * @param maxRaysPerBeam maximum rays per node before subdivision
     */
    public BeamNode(AABB bounds, int[] rayIndices, int depth, int maxRaysPerBeam) {
        this.bounds = bounds;
        this.rayIndices = rayIndices.clone();
        this.depth = depth;
        this.maxRaysPerBeam = maxRaysPerBeam;
        this.children = null;
        this.coherence = CoherenceMetadata.low();
        this.sharedNodes = new HashSet<>();
    }

    /**
     * Create a new beam node with default max rays (64).
     */
    public BeamNode(AABB bounds, int[] rayIndices, int depth) {
        this(bounds, rayIndices, depth, 64);
    }

    /**
     * Check if this node is a leaf (has no children).
     */
    public boolean isLeaf() {
        return children == null || children.length == 0;
    }

    /**
     * Get number of rays in this beam.
     */
    public int getRayCount() {
        return rayIndices.length;
    }

    /**
     * Get the ray indices stored in this beam.
     */
    public int[] getRayIndices() {
        return rayIndices.clone();
    }

    /**
     * Get the spatial bounds of this beam.
     */
    public AABB getBounds() {
        return bounds;
    }

    /**
     * Get hierarchical depth of this node in the tree.
     */
    public int getDepth() {
        return depth;
    }

    /**
     * Get coherence score for rays in this beam (0.0-1.0).
     */
    public double getCoherenceScore() {
        return coherence.coherenceScore();
    }

    /**
     * Get number of shared DAG nodes traversed by all rays in beam.
     */
    public int getSharedNodeCount() {
        return sharedNodes.size();
    }

    /**
     * Get the shared DAG nodes.
     */
    public Set<Integer> getSharedNodes() {
        return new HashSet<>(sharedNodes);
    }

    /**
     * Add shared DAG node.
     */
    public void addSharedNode(int nodeId) {
        sharedNodes.add(nodeId);
    }

    /**
     * Update coherence metadata.
     */
    public void setCoherence(CoherenceMetadata coherence) {
        this.coherence = coherence;
    }

    /**
     * Set child nodes after subdivision.
     */
    public void setChildren(BeamNode[] children) {
        this.children = children;
    }

    /**
     * Get child nodes (may be null or empty for leaf nodes).
     */
    public BeamNode[] getChildren() {
        return children;
    }

    /**
     * Check if this node should be subdivided.
     */
    public boolean shouldSubdivide() {
        return rayIndices.length > maxRaysPerBeam;
    }

    @Override
    public String toString() {
        return String.format("BeamNode[depth=%d, rays=%d, children=%s, coherence=%.2f]",
                depth, rayIndices.length, (children != null ? children.length + "" : "leaf"),
                coherence.coherenceScore());
    }
}

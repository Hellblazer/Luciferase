package com.hellblazer.luciferase.esvo.gpu.beam;

/**
 * Strategy for subdividing rays in BeamTree.
 *
 * Different strategies optimize for different ray distributions and scene characteristics.
 */
public enum SubdivisionStrategy {
    /**
     * Octree: 8-way spatial subdivision along X/Y/Z axes.
     *
     * Best for: Regular ray distributions (camera frustums)
     * Properties: Balanced trees, predictable depth
     */
    OCTREE,

    /**
     * KDTree: 2-way adaptive split along principal axis.
     *
     * Best for: Non-uniform ray distributions
     * Properties: Minimizes node overlap, deeper trees
     */
    KDTREE,

    /**
     * Direction: Group rays by direction similarity.
     *
     * Best for: Rays with high directional coherence
     * Properties: Flat trees, direction-based batches
     */
    DIRECTION
}

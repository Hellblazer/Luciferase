package com.hellblazer.luciferase.esvo.gpu.beam;

/**
 * Pre-computed coherence metrics for a BeamNode.
 *
 * Stores coherence analysis results to avoid recomputation during tree traversal.
 */
public record CoherenceMetadata(
        /** Coherence score 0.0-1.0 (1.0 = perfect coherence) */
        double coherenceScore,

        /** Number of shared DAG nodes traversed by all rays in beam */
        int sharedNodeCount,

        /** Total DAG nodes accessed by rays in beam */
        int totalNodeAccesses,

        /** Cache efficiency metric 0.0-1.0 */
        double cacheEfficiency
) {

    public CoherenceMetadata {
        if (coherenceScore < 0.0 || coherenceScore > 1.0) {
            throw new IllegalArgumentException("coherenceScore must be between 0.0 and 1.0, got " + coherenceScore);
        }
        if (sharedNodeCount < 0) {
            throw new IllegalArgumentException("sharedNodeCount cannot be negative");
        }
        if (totalNodeAccesses < 0) {
            throw new IllegalArgumentException("totalNodeAccesses cannot be negative");
        }
        if (cacheEfficiency < 0.0 || cacheEfficiency > 1.0) {
            throw new IllegalArgumentException("cacheEfficiency must be between 0.0 and 1.0, got " + cacheEfficiency);
        }
    }

    /**
     * Create a default low-coherence metadata.
     */
    public static CoherenceMetadata low() {
        return new CoherenceMetadata(0.0, 0, 0, 0.0);
    }

    /**
     * Create perfect coherence metadata.
     */
    public static CoherenceMetadata perfect(int nodeCount) {
        return new CoherenceMetadata(1.0, nodeCount, nodeCount, 1.0);
    }
}

package com.hellblazer.luciferase.esvo.gpu.beam.metrics;

/**
 * Snapshot of coherence state from BeamTree.
 * All coherence values are in the range [0.0, 1.0].
 * Immutable and thread-safe.
 *
 * @param averageCoherence Average coherence across all beams (0.0 to 1.0)
 * @param minCoherence Minimum coherence observed
 * @param maxCoherence Maximum coherence observed
 * @param totalBeams Total number of beams in the tree
 * @param maxDepth Maximum depth of the beam tree
 */
public record CoherenceSnapshot(
    double averageCoherence,
    double minCoherence,
    double maxCoherence,
    int totalBeams,
    int maxDepth
) {
    /**
     * Compact constructor with validation.
     */
    public CoherenceSnapshot {
        if (totalBeams < 0) {
            throw new IllegalArgumentException("totalBeams cannot be negative: " + totalBeams);
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth cannot be negative: " + maxDepth);
        }
        if (minCoherence > maxCoherence) {
            throw new IllegalArgumentException(
                "minCoherence (" + minCoherence + ") cannot be greater than maxCoherence (" + maxCoherence + ")"
            );
        }
        if (averageCoherence < minCoherence || averageCoherence > maxCoherence) {
            throw new IllegalArgumentException(
                "averageCoherence (" + averageCoherence + ") must be in range [" + minCoherence + ", " + maxCoherence + "]"
            );
        }
    }

    /**
     * Creates an empty coherence snapshot with all values set to zero.
     */
    public static CoherenceSnapshot empty() {
        return new CoherenceSnapshot(0.0, 0.0, 0.0, 0, 0);
    }
}

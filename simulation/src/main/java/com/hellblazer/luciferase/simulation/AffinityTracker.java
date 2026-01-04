package com.hellblazer.luciferase.simulation;

/**
 * Tracks entity affinity metric for bubble membership classification.
 * <p>
 * Affinity = internal / (internal + external)
 * - Measures the ratio of internal interactions (within bubble) to total interactions
 * - Used to determine if an entity should remain in current bubble or migrate
 * <p>
 * Classification:
 * - Core: > 0.8 (strong internal affinity, stable member)
 * - Boundary: 0.5 - 0.8 (balanced, monitor for migration)
 * - Drifting: < 0.5 (weak internal affinity, migration candidate)
 * - Edge case: 0/0 = 0.5 (no data, treat as boundary)
 * <p>
 * Immutable record - use recordInternal() / recordExternal() to create updated instances.
 *
 * @param internal Count of interactions with entities in the same bubble
 * @param external Count of interactions with entities in other bubbles
 * @author hal.hildebrand
 */
public record AffinityTracker(int internal, int external) {

    /**
     * Calculate affinity metric.
     *
     * @return Affinity value in range [0.0, 1.0]
     *         - 1.0 = all internal interactions
     *         - 0.5 = balanced or no data (0/0 edge case)
     *         - 0.0 = all external interactions
     */
    public float affinity() {
        int total = internal + external;
        return total == 0 ? 0.5f : internal / (float) total;
    }

    /**
     * Check if entity is drifting (migration candidate).
     *
     * @return true if affinity < 0.5 (more external than internal interactions)
     */
    public boolean isDrifting() {
        return affinity() < 0.5f;
    }

    /**
     * Check if entity is on boundary (monitor for migration).
     *
     * @return true if 0.5 <= affinity <= 0.8 (balanced or edge case)
     */
    public boolean isBoundary() {
        float a = affinity();
        return a >= 0.5f && a <= 0.8f;
    }

    /**
     * Check if entity is core member (stable).
     *
     * @return true if affinity > 0.8 (strong internal affinity)
     */
    public boolean isCore() {
        return affinity() > 0.8f;
    }

    /**
     * Record an internal interaction (within bubble).
     *
     * @return New AffinityTracker with incremented internal count
     */
    public AffinityTracker recordInternal() {
        return new AffinityTracker(internal + 1, external);
    }

    /**
     * Record an external interaction (with other bubble).
     *
     * @return New AffinityTracker with incremented external count
     */
    public AffinityTracker recordExternal() {
        return new AffinityTracker(internal, external + 1);
    }
}

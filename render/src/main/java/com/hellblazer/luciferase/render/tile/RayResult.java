package com.hellblazer.luciferase.render.tile;

/**
 * Result of a ray intersection query from kernel execution.
 *
 * @param hitX    X coordinate of ray intersection
 * @param hitY    Y coordinate of ray intersection
 * @param hitZ    Z coordinate of ray intersection
 * @param distance Distance to intersection point
 */
public record RayResult(float hitX, float hitY, float hitZ, float distance) {

    /**
     * Creates a miss result (no intersection).
     *
     * @return RayResult representing a miss
     */
    public static RayResult miss() {
        return new RayResult(Float.NaN, Float.NaN, Float.NaN, Float.POSITIVE_INFINITY);
    }

    /**
     * Determines if this result represents a hit (valid intersection).
     *
     * @return true if distance is finite
     */
    public boolean isHit() {
        return Float.isFinite(distance);
    }
}

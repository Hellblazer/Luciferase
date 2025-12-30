package com.hellblazer.luciferase.portal.web.dto;

/**
 * Result of a raycast operation.
 */
public record RaycastResult(
        boolean hit,
        float distance,
        float hitX, float hitY, float hitZ,
        float normalX, float normalY, float normalZ,
        int depth,
        int iterations
) {
    public static RaycastResult miss() {
        return new RaycastResult(false, -1, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}

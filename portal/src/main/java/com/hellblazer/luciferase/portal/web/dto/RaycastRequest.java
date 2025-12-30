package com.hellblazer.luciferase.portal.web.dto;

/**
 * Request for ray casting through the render structure.
 */
public record RaycastRequest(
        float originX, float originY, float originZ,
        float directionX, float directionY, float directionZ,
        Float maxDistance
) {}

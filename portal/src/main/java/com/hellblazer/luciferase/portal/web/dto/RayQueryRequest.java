package com.hellblazer.luciferase.portal.web.dto;

/**
 * Request for ray intersection query.
 */
public record RayQueryRequest(
        float originX, float originY, float originZ,
        float directionX, float directionY, float directionZ,
        Float maxDistance
) {}

package com.hellblazer.luciferase.portal.web.dto;

/**
 * Information about a ray intersection hit.
 */
public record RayHitInfo(
        String entityId,
        float distance,
        float hitX, float hitY, float hitZ,
        float normalX, float normalY, float normalZ,
        Object content
) {}

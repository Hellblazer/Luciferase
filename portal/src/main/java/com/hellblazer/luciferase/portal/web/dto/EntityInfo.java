package com.hellblazer.luciferase.portal.web.dto;

/**
 * Information about an entity in the spatial index.
 */
public record EntityInfo(
        String entityId,
        float x,
        float y,
        float z,
        Object content
) {}

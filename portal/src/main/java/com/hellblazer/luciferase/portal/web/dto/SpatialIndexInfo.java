package com.hellblazer.luciferase.portal.web.dto;

/**
 * Information about a spatial index.
 */
public record SpatialIndexInfo(
        String sessionId,
        String indexType,
        int entityCount,
        int nodeCount,
        byte maxDepth,
        int maxEntitiesPerNode
) {}

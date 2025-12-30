package com.hellblazer.luciferase.portal.web.dto;

/**
 * Information about a render structure.
 */
public record RenderInfo(
        String sessionId,
        String type,
        int nodeCount,
        int leafCount,
        int internalCount,
        int maxDepth,
        int gridResolution
) {}

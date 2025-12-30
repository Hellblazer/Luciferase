package com.hellblazer.luciferase.portal.web.dto;

/**
 * Rendering statistics.
 */
public record RenderStats(
        String type,
        int nodeCount,
        int leafCount,
        int internalCount,
        int maxDepth,
        long memoryBytes,
        int farPointerCount
) {}

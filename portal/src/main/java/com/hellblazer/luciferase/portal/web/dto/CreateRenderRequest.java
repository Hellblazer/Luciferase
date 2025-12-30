package com.hellblazer.luciferase.portal.web.dto;

/**
 * Request to create a render structure (ESVO or ESVT) from the spatial index.
 */
public record CreateRenderRequest(
        RenderType type,
        Integer maxDepth,
        Integer gridResolution
) {
    public enum RenderType {
        ESVO, ESVT
    }
}

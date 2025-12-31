package com.hellblazer.luciferase.portal.web.dto;

/**
 * Request for a spatial range query.
 */
public record RangeQueryRequest(
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ
) {}

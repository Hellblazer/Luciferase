package com.hellblazer.luciferase.portal.web.dto;

/**
 * Request for k-nearest neighbors query.
 */
public record KnnQueryRequest(
        float x, float y, float z,
        int k,
        Float maxDistance
) {}

package com.hellblazer.luciferase.portal.web.dto;

import java.util.Map;

/**
 * Request to insert an entity into the spatial index.
 *
 * @param x       X coordinate
 * @param y       Y coordinate
 * @param z       Z coordinate
 * @param content Optional content to store with the entity
 */
public record InsertEntityRequest(
        float x,
        float y,
        float z,
        Map<String, Object> content
) {}

package com.hellblazer.luciferase.portal.web.dto;

/**
 * Request to update an entity's position.
 *
 * @param entityId The entity ID to update
 * @param x        New X coordinate
 * @param y        New Y coordinate
 * @param z        New Z coordinate
 */
public record UpdateEntityRequest(
        String entityId,
        float x,
        float y,
        float z
) {
    public UpdateEntityRequest {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityId is required");
        }
    }
}

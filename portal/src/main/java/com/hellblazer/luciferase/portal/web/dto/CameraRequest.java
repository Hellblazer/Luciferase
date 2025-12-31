package com.hellblazer.luciferase.portal.web.dto;

/**
 * Request to set camera position and orientation.
 */
public record CameraRequest(
        float posX, float posY, float posZ,
        float targetX, float targetY, float targetZ,
        float upX, float upY, float upZ,
        Float fov
) {}

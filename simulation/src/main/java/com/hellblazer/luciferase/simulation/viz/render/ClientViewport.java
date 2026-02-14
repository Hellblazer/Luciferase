/*
 * Copyright (c) 2026, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.simulation.viz.render;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;

/**
 * Camera parameters for a client viewport.
 * <p>
 * Defines the camera frustum for frustum culling and LOD determination.
 * Eye position and look-at point define the camera's position and orientation.
 * FOV, aspect ratio, near, and far define the frustum shape.
 * <p>
 * Thread-safe: immutable record.
 *
 * @param eye         Camera eye position
 * @param lookAt      Look-at target position
 * @param up          Up vector
 * @param fovY        Vertical field of view in radians
 * @param aspectRatio Width / Height ratio
 * @param nearPlane   Near clipping plane distance (positive)
 * @param farPlane    Far clipping plane distance (positive, > nearPlane)
 * @author hal.hildebrand
 */
public record ClientViewport(
    Point3f eye,
    Point3f lookAt,
    Vector3f up,
    float fovY,
    float aspectRatio,
    float nearPlane,
    float farPlane
) {
    /**
     * Compact constructor with validation.
     */
    public ClientViewport {
        if (nearPlane <= 0) {
            throw new IllegalArgumentException("nearPlane must be positive");
        }
        if (farPlane <= nearPlane) {
            throw new IllegalArgumentException("farPlane must be > nearPlane");
        }
        if (fovY <= 0 || fovY >= Math.PI) {
            throw new IllegalArgumentException("fovY must be in (0, pi)");
        }
        if (aspectRatio <= 0) {
            throw new IllegalArgumentException("aspectRatio must be positive");
        }
    }

    /**
     * Calculate distance from eye to a point.
     */
    public float distanceTo(float x, float y, float z) {
        var dx = x - eye.x;
        var dy = y - eye.y;
        var dz = z - eye.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Create default viewport for testing.
     * Camera at (512, 512, 100) looking at center (512, 512, 512).
     */
    public static ClientViewport testDefault() {
        return new ClientViewport(
            new Point3f(512f, 512f, 100f),    // eye at front of world
            new Point3f(512f, 512f, 512f),    // looking at center
            new Vector3f(0f, 1f, 0f),         // Y-up
            (float) (Math.PI / 3),            // 60 degree FOV
            16f / 9f,                         // 16:9 aspect
            0.1f,                             // near
            2000f                             // far
        );
    }
}

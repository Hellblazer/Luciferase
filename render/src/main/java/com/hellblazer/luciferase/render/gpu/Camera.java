/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.hellblazer.luciferase.render.gpu;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;

/**
 * Camera with view and projection matrices.
 */
public class Camera {

    private static final float FOV = 45.0f;
    private static final float Z_NEAR = 0.1f;
    private static final float Z_FAR = 1000.0f;

    private final Vector3f position = new Vector3f(0, 0, 0);
    private final Vector3f target = new Vector3f(0, 0, 0);
    private final Vector3f up = new Vector3f(0, 1, 0);

    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f projMatrix = new Matrix4f();
    private final Matrix4f viewProj = new Matrix4f();

    private final int width;
    private final int height;
    private boolean dirty = true;

    /**
     * Create a camera for a viewport.
     *
     * @param width  Viewport width
     * @param height Viewport height
     */
    public Camera(int width, int height) {
        this.width = width;
        this.height = height;
        updateProjection();
    }

    /**
     * Set camera position.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void setPosition(float x, float y, float z) {
        position.x = x;
        position.y = y;
        position.z = z;
        dirty = true;
    }

    /**
     * Look at a target point.
     *
     * @param x Target X
     * @param y Target Y
     * @param z Target Z
     */
    public void lookAt(float x, float y, float z) {
        target.x = x;
        target.y = y;
        target.z = z;
        dirty = true;
    }

    /**
     * Get the combined view-projection matrix.
     *
     * @return Matrix4f
     */
    public Matrix4f getViewProj() {
        if (dirty) {
            updateMatrices();
        }
        return viewProj;
    }

    /**
     * Get the view matrix.
     *
     * @return Matrix4f
     */
    public Matrix4f getView() {
        if (dirty) {
            updateMatrices();
        }
        return viewMatrix;
    }

    /**
     * Get the projection matrix.
     *
     * @return Matrix4f
     */
    public Matrix4f getProjection() {
        return projMatrix;
    }

    private void updateMatrices() {
        // Compute view matrix manually (camera space)
        computeLookAt(position, target, up, viewMatrix);

        // Compute view-projection
        viewProj.mul(projMatrix, viewMatrix);

        dirty = false;
    }

    /**
     * Compute look-at matrix (camera transformation).
     */
    private static void computeLookAt(Vector3f eye, Vector3f center, Vector3f up, Matrix4f result) {
        Vector3f f = new Vector3f(center);
        f.sub(eye);
        f.normalize();

        Vector3f s = new Vector3f();
        s.cross(f, up);
        s.normalize();

        Vector3f u = new Vector3f();
        u.cross(s, f);

        result.setIdentity();
        result.m00 = s.x;
        result.m01 = u.x;
        result.m02 = -f.x;
        result.m10 = s.y;
        result.m11 = u.y;
        result.m12 = -f.y;
        result.m20 = s.z;
        result.m21 = u.z;
        result.m22 = -f.z;

        result.m03 = -s.dot(eye);
        result.m13 = -u.dot(eye);
        result.m23 = f.dot(eye);
    }

    private void updateProjection() {
        // Perspective projection
        float aspect = (float) width / height;
        float fovRad = (float) Math.toRadians(FOV);
        float f = (float) (1.0 / Math.tan(fovRad / 2.0));

        projMatrix.setIdentity();
        projMatrix.m00 = f / aspect;
        projMatrix.m11 = f;
        projMatrix.m22 = (Z_FAR + Z_NEAR) / (Z_NEAR - Z_FAR);
        projMatrix.m23 = -1;
        projMatrix.m32 = (2 * Z_FAR * Z_NEAR) / (Z_NEAR - Z_FAR);
        projMatrix.m33 = 0;

        dirty = true;
    }
}

/**
 * Copyright (C) 2025 Hal Hildebrand. All rights reserved.
 *
 * This file is part of the Luciferase.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.hellblazer.luciferase.sparse.gpu;

import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;

/**
 * Matrix utilities for GPU rendering operations.
 *
 * <p>Extracted from ESVO/ESVT OpenCL renderers as part of code consolidation.
 * These methods create view and projection matrices for ray generation.
 *
 * <p><b>Thread Safety:</b> All methods are stateless and thread-safe.
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.esvo.gpu.ESVOOpenCLRenderer
 * @see com.hellblazer.luciferase.esvt.gpu.ESVTOpenCLRenderer
 */
public final class MatrixUtils {

    private MatrixUtils() {
        // Prevent instantiation
    }

    /**
     * Create a look-at view matrix.
     *
     * <p>Creates a view matrix that positions the camera at {@code eye} looking
     * toward {@code target} with the given {@code up} vector.
     *
     * @param eye    camera position in world coordinates
     * @param target point the camera is looking at
     * @param up     up direction vector (typically (0,1,0))
     * @return view matrix for transforming world to view coordinates
     */
    public static Matrix4f createLookAtMatrix(Vector3f eye, Vector3f target, Vector3f up) {
        var forward = new Vector3f();
        forward.sub(target, eye);
        forward.normalize();

        var right = new Vector3f();
        right.cross(forward, up);
        right.normalize();

        var newUp = new Vector3f();
        newUp.cross(right, forward);

        var matrix = new Matrix4f();
        matrix.m00 = right.x;
        matrix.m01 = right.y;
        matrix.m02 = right.z;
        matrix.m03 = -right.dot(eye);
        matrix.m10 = newUp.x;
        matrix.m11 = newUp.y;
        matrix.m12 = newUp.z;
        matrix.m13 = -newUp.dot(eye);
        matrix.m20 = -forward.x;
        matrix.m21 = -forward.y;
        matrix.m22 = -forward.z;
        matrix.m23 = forward.dot(eye);
        matrix.m30 = 0;
        matrix.m31 = 0;
        matrix.m32 = 0;
        matrix.m33 = 1;

        return matrix;
    }

    /**
     * Create a perspective projection matrix.
     *
     * <p>Creates a perspective projection matrix with the given field of view,
     * aspect ratio, and near/far clipping planes.
     *
     * @param fovDegrees vertical field of view in degrees
     * @param aspect     aspect ratio (width / height)
     * @param near       distance to near clipping plane (must be > 0)
     * @param far        distance to far clipping plane (must be > near)
     * @return perspective projection matrix
     * @throws IllegalArgumentException if near <= 0 or far <= near
     */
    public static Matrix4f createPerspectiveMatrix(float fovDegrees, float aspect, float near, float far) {
        if (near <= 0) {
            throw new IllegalArgumentException("Near plane must be positive: " + near);
        }
        if (far <= near) {
            throw new IllegalArgumentException("Far plane must be greater than near: far=" + far + ", near=" + near);
        }

        float fovRad = (float) Math.toRadians(fovDegrees);
        float f = 1.0f / (float) Math.tan(fovRad / 2.0f);

        var matrix = new Matrix4f();
        matrix.m00 = f / aspect;
        matrix.m11 = f;
        matrix.m22 = (far + near) / (near - far);
        matrix.m23 = (2 * far * near) / (near - far);
        matrix.m32 = -1;
        matrix.m33 = 0;

        return matrix;
    }

    /**
     * Create an orthographic projection matrix.
     *
     * @param left   left clipping plane
     * @param right  right clipping plane
     * @param bottom bottom clipping plane
     * @param top    top clipping plane
     * @param near   near clipping plane
     * @param far    far clipping plane
     * @return orthographic projection matrix
     */
    public static Matrix4f createOrthographicMatrix(float left, float right, float bottom, float top,
                                                     float near, float far) {
        var matrix = new Matrix4f();
        matrix.m00 = 2.0f / (right - left);
        matrix.m03 = -(right + left) / (right - left);
        matrix.m11 = 2.0f / (top - bottom);
        matrix.m13 = -(top + bottom) / (top - bottom);
        matrix.m22 = -2.0f / (far - near);
        matrix.m23 = -(far + near) / (far - near);
        matrix.m33 = 1.0f;

        return matrix;
    }

    /**
     * Invert a 4x4 matrix.
     *
     * @param m matrix to invert
     * @return inverted matrix, or null if matrix is singular
     */
    public static Matrix4f invert(Matrix4f m) {
        var result = new Matrix4f(m);
        result.invert();
        return result;
    }

    /**
     * Multiply two matrices: result = a * b
     *
     * @param a left matrix
     * @param b right matrix
     * @return product matrix
     */
    public static Matrix4f multiply(Matrix4f a, Matrix4f b) {
        var result = new Matrix4f();
        result.mul(a, b);
        return result;
    }
}

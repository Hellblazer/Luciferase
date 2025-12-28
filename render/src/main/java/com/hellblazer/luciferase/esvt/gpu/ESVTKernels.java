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
package com.hellblazer.luciferase.esvt.gpu;

import com.hellblazer.luciferase.esvo.gpu.ShaderResourceLoader;

/**
 * ESVT (Efficient Sparse Voxel Tetrahedra) GPU kernel definitions.
 * Loads shader and kernel code from external resource files.
 *
 * @author hal.hildebrand
 */
public class ESVTKernels {

    /**
     * OpenCL kernel for ESVT ray traversal through sparse voxel tetrahedra.
     * Tetrahedral adaptation of ESVO using Bey 8-way subdivision.
     */
    public static final String OPENCL_RAY_TRAVERSAL = ShaderResourceLoader.loadShader("kernels/esvt_ray_traversal.cl");

    /**
     * GLSL compute shader for ESVT ray traversal.
     */
    public static final String GLSL_RAY_TRAVERSAL = ShaderResourceLoader.loadShader("shaders/raycast_esvt.comp");

    /**
     * Returns the OpenCL kernel source code for ESVT ray traversal.
     */
    public static String getOpenCLKernel() {
        return OPENCL_RAY_TRAVERSAL;
    }

    /**
     * Returns the GLSL compute shader source code for ESVT ray traversal.
     */
    public static String getGLSLKernel() {
        return GLSL_RAY_TRAVERSAL;
    }
}

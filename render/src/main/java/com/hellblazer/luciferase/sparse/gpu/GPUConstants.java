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

/**
 * Common GPU constants for sparse voxel rendering.
 *
 * <p>These constants are shared between ESVO and ESVT GPU renderers
 * (both OpenCL and compute shader implementations).
 *
 * <p><b>Memory Alignment:</b> Constants are chosen to optimize GPU memory
 * access patterns and cache utilization across different GPU architectures.
 *
 * <p><b>Thread Safety:</b> All fields are constant and thread-safe.
 *
 * @author hal.hildebrand
 * @see com.hellblazer.luciferase.esvo.gpu.ESVOOpenCLRenderer
 * @see com.hellblazer.luciferase.esvt.gpu.ESVTOpenCLRenderer
 */
public final class GPUConstants {

    private GPUConstants() {
        // Prevent instantiation
    }

    // === Workgroup Configuration ===

    /**
     * Default local work size for OpenCL kernels.
     *
     * <p>64 threads is a good balance for most GPUs (2 warps on NVIDIA,
     * 1 wavefront on AMD).
     */
    public static final int LOCAL_WORK_SIZE = 64;

    /**
     * Default workgroup size X dimension for compute shaders.
     */
    public static final int WORKGROUP_SIZE_X = 8;

    /**
     * Default workgroup size Y dimension for compute shaders.
     */
    public static final int WORKGROUP_SIZE_Y = 8;

    /**
     * Default workgroup size Z dimension for compute shaders.
     */
    public static final int WORKGROUP_SIZE_Z = 1;

    /**
     * Total threads per workgroup (8 * 8 * 1 = 64).
     */
    public static final int THREADS_PER_WORKGROUP = WORKGROUP_SIZE_X * WORKGROUP_SIZE_Y * WORKGROUP_SIZE_Z;

    // === GPU Architecture Constants ===

    /**
     * GPU warp size (NVIDIA) / wavefront size minimum (AMD).
     *
     * <p>Most GPU architectures execute threads in groups of 32 or 64.
     * 32 is the common denominator.
     */
    public static final int WARP_SIZE = 32;

    /**
     * CPU cache line size in bytes.
     *
     * <p>64 bytes is standard for most modern CPUs.
     */
    public static final int CPU_CACHE_LINE_SIZE = 64;

    /**
     * GPU L2 cache line size in bytes.
     *
     * <p>128 bytes is typical for GPU L2 caches.
     */
    public static final int GPU_CACHE_LINE_SIZE = 128;

    /**
     * Memory alignment for GPU buffers.
     *
     * <p>64-byte alignment ensures proper cache line alignment.
     */
    public static final int GPU_ALIGNMENT = 64;

    // === Buffer Binding Points ===

    /**
     * Default binding point for the sparse voxel data buffer.
     */
    public static final int VOXEL_BUFFER_BINDING = 0;

    /**
     * Default binding point for the output image.
     */
    public static final int OUTPUT_IMAGE_BINDING = 1;

    /**
     * Default binding point for the camera uniform buffer.
     */
    public static final int CAMERA_UBO_BINDING = 2;

    // === Memory Sizes ===

    /**
     * Size of the camera UBO in bytes.
     *
     * <p>Contains view matrix (64 bytes) + projection matrix (64 bytes)
     * + camera position (16 bytes) + additional parameters.
     */
    public static final int CAMERA_UBO_SIZE = 64 * 4 + 16 * 4; // 320 bytes

    /**
     * Node size in bytes (both ESVO and ESVT use 8-byte nodes).
     */
    public static final int NODE_SIZE_BYTES = 8;

    /**
     * Number of nodes that fit in a CPU cache line.
     */
    public static final int NODES_PER_CPU_CACHE_LINE = CPU_CACHE_LINE_SIZE / NODE_SIZE_BYTES; // 8

    /**
     * Number of nodes that fit in a GPU cache line.
     */
    public static final int NODES_PER_GPU_CACHE_LINE = GPU_CACHE_LINE_SIZE / NODE_SIZE_BYTES; // 16

    // === Optimization Thresholds ===

    /**
     * Maximum workgroup size supported by most GPUs.
     */
    public static final int MAX_WORKGROUP_SIZE = 1024;

    /**
     * Minimum workgroup size for efficient GPU execution.
     */
    public static final int MIN_WORKGROUP_SIZE = 32;

    /**
     * Default local memory per compute unit (64KB typical).
     */
    public static final int DEFAULT_LOCAL_MEMORY_PER_CU = 65536;

    /**
     * Maximum rays per coherent group for optimization.
     */
    public static final int MAX_RAYS_PER_GROUP = 32;

    /**
     * Coherence threshold for ray grouping.
     */
    public static final float COHERENCE_THRESHOLD = 0.1f;
}

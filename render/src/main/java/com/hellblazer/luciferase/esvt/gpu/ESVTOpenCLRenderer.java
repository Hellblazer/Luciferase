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

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer.BufferAccess;
import com.hellblazer.luciferase.sparse.gpu.AbstractOpenCLRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * OpenCL-based GPU Renderer for ESVT (Efficient Sparse Voxel Tetrahedra).
 *
 * <p>Extends {@link AbstractOpenCLRenderer} to provide ESVT-specific rendering.
 *
 * <p><b>Key features:</b>
 * <ul>
 *   <li>Stack-based tetrahedral ray traversal</li>
 *   <li>Moller-Trumbore intersection</li>
 *   <li>Contour refinement</li>
 *   <li>Normal-based surface shading</li>
 * </ul>
 *
 * <p><b>Coordinate Space:</b> [0, 1] normalized tetree space
 *
 * @author hal.hildebrand
 * @see ESVTTraversal CPU-based ray traversal
 */
public final class ESVTOpenCLRenderer extends AbstractOpenCLRenderer<ESVTNodeUnified, ESVTData> {
    private static final Logger log = LoggerFactory.getLogger(ESVTOpenCLRenderer.class);

    // Normal: xyz + hit_flag = 4 floats = 16 bytes
    private static final int NORMAL_SIZE_FLOATS = 4;

    // Raw cl_mem handles for ByteBuffer upload
    private long clNodeBuffer;
    private long clFarPointerBuffer;
    private long clContourBuffer;

    // Additional GPU buffers for ESVT
    private OpenCLBuffer normalBuffer;
    private OpenCLBuffer sceneMinBuffer;
    private OpenCLBuffer sceneMaxBuffer;

    // Additional CPU buffer for normals
    private FloatBuffer cpuNormalBuffer;

    // Reusable array for readPixelExtraData() to avoid per-pixel allocation
    private final float[] normalData = new float[NORMAL_SIZE_FLOATS];

    // Contour tracking
    private int contourCount = 0;

    /**
     * Create OpenCL renderer with specified output resolution.
     *
     * @param frameWidth  Output width in pixels
     * @param frameHeight Output height in pixels
     */
    public ESVTOpenCLRenderer(int frameWidth, int frameHeight) {
        super(frameWidth, frameHeight);
    }

    // ========== Test Accessors ==========
    // These methods expose internal buffers for GPU/CPU cross-validation tests.

    /**
     * Get the result buffer containing hit positions and distances.
     * <p>Buffer format: 4 floats per ray [x, y, z, distance]
     * <p><b>For testing only.</b>
     *
     * @return result buffer, rewound to position 0
     */
    public FloatBuffer getResultBufferForTesting() {
        cpuResultBuffer.rewind();
        return cpuResultBuffer;
    }

    /**
     * Get the normal buffer containing normals and hit flags.
     * <p>Buffer format: 4 floats per ray [nx, ny, nz, hitFlag]
     * <p><b>For testing only.</b>
     *
     * @return normal buffer, rewound to position 0
     */
    public FloatBuffer getNormalBufferForTesting() {
        cpuNormalBuffer.rewind();
        return cpuNormalBuffer;
    }

    @Override
    protected String getRendererName() {
        return "ESVTOpenCLRenderer";
    }

    @Override
    protected String getKernelSource() {
        return ESVTKernels.getOpenCLKernel();
    }

    @Override
    protected String getKernelEntryPoint() {
        return "traverseESVT";
    }

    @Override
    protected boolean hasDataUploaded() {
        return clNodeBuffer != 0;
    }

    @Override
    protected void allocateTypeSpecificBuffers() {
        // Allocate CPU buffer for normals
        cpuNormalBuffer = memAllocFloat(rayCount * NORMAL_SIZE_FLOATS);

        // Allocate GPU buffers
        normalBuffer = OpenCLBuffer.create(rayCount * NORMAL_SIZE_FLOATS, BufferAccess.WRITE_ONLY);

        // Scene bounds buffers (vec4: 4 floats each)
        sceneMinBuffer = OpenCLBuffer.create(4, BufferAccess.READ_ONLY);
        sceneMaxBuffer = OpenCLBuffer.create(4, BufferAccess.READ_ONLY);

        // Upload default scene bounds (0,0,0) to (1,1,1) for normalized tetree
        sceneMinBuffer.upload(new float[]{0.0f, 0.0f, 0.0f, 0.0f});
        sceneMaxBuffer.upload(new float[]{1.0f, 1.0f, 1.0f, 0.0f});

        // Initialize empty node/contour/far-pointer buffers (will be replaced when data is uploaded)
        clNodeBuffer = 0;
        clFarPointerBuffer = 0;
        clContourBuffer = 0;
    }

    @Override
    protected void uploadDataBuffers(ESVTData data) {
        // Release old node buffer if exists (G4: include untrackRawHandle to prevent double-free on dispose)
        if (clNodeBuffer != 0) {
            clReleaseMemObject(clNodeBuffer);
            untrackRawHandle(clNodeBuffer);
            clNodeBuffer = 0;
        }

        // Upload node data using raw ByteBuffer
        var nodeData = data.nodesToByteBuffer();
        clNodeBuffer = createRawBuffer(nodeData, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR);

        // Upload far-pointer table (arg slot always bound; may be a 4-byte stub when empty)
        if (clFarPointerBuffer != 0) {
            clReleaseMemObject(clFarPointerBuffer);
            untrackRawHandle(clFarPointerBuffer);
            clFarPointerBuffer = 0;
        }
        var farPointers = data.farPointers();
        if (farPointers != null && farPointers.length > 0) {
            log.debug("Uploading {} far pointer(s) to GPU", farPointers.length);
            var fpData = memAlloc(farPointers.length * Integer.BYTES);
            fpData.order(ByteOrder.nativeOrder());
            for (int fp : farPointers) {
                fpData.putInt(fp);
            }
            fpData.flip();
            try {
                clFarPointerBuffer = createRawBuffer(fpData, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR);
            } finally {
                memFree(fpData);
            }
        } else {
            // No far pointers — supply a minimal placeholder buffer so the kernel arg slot is always bound
            clFarPointerBuffer = createEmptyRawBuffer(Integer.BYTES, CL_MEM_READ_ONLY);
        }

        // Upload contour data if present (G4: include untrackRawHandle to prevent double-free on dispose)
        if (clContourBuffer != 0) {
            clReleaseMemObject(clContourBuffer);
            untrackRawHandle(clContourBuffer);
            clContourBuffer = 0;
        }

        if (data.hasContours()) {
            var contourData = data.contoursToByteBuffer();
            this.contourCount = data.contourCount();
            clContourBuffer = createRawBuffer(contourData, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR);
        } else {
            this.contourCount = 0;
            // Create minimal empty buffer
            clContourBuffer = createEmptyRawBuffer(4, CL_MEM_READ_ONLY);
        }

        log.debug("Uploaded ESVT data: {} nodes, {} far pointers, {} contours",
                  data.nodeCount(), data.farPointerCount(), contourCount);
    }

    @Override
    protected void setKernelArguments() {
        // arg 0: rays — set by base class (AbstractOpenCLRenderer.executeKernel)

        // arg 1: node buffer
        setRawBufferArg(1, clNodeBuffer);

        // arg 2: far-pointer indirection table (always bound; may be a 4-byte stub when no far pointers)
        setRawBufferArg(2, clFarPointerBuffer);

        // arg 3: contour buffer
        setRawBufferArg(3, clContourBuffer);

        // arg 4: result buffer (write)
        kernel.setBufferArg(4, resultBuffer, ComputeKernel.BufferAccess.WRITE);

        // arg 5: normal buffer (write)
        kernel.setBufferArg(5, normalBuffer, ComputeKernel.BufferAccess.WRITE);

        // arg 6: maxDepth
        kernel.setIntArg(6, maxDepth);

        // Note: sceneMin/sceneMax removed from kernel - not used, was causing type mismatch
    }

    @Override
    protected void readTypeSpecificResults() {
        cpuNormalBuffer.clear();
        normalBuffer.download(cpuNormalBuffer);
        cpuNormalBuffer.rewind();
    }

    /**
     * Read normal data for current pixel from the normal buffer.
     * Returns [nx, ny, nz, hitFlag] for use in computePixelColor.
     *
     * <p>Reuses a single array instance to avoid per-pixel allocation overhead.
     * For a 1920x1080 frame, this eliminates ~2M allocations per frame.
     */
    @Override
    protected float[] readPixelExtraData() {
        normalData[0] = cpuNormalBuffer.get();  // nx
        normalData[1] = cpuNormalBuffer.get();  // ny
        normalData[2] = cpuNormalBuffer.get();  // nz
        normalData[3] = cpuNormalBuffer.get();  // hitFlag
        return normalData;
    }

    @Override
    protected int computePixelColor(float hitX, float hitY, float hitZ,
                                     float distance, float[] extraData) {
        // extraData: [nx, ny, nz, hitFlag]
        float nx = extraData[0];
        float ny = extraData[1];
        float nz = extraData[2];
        float hitFlag = extraData[3];

        if (hitFlag > 0.5f && distance > 0) {
            // DEBUG MODE: Output normal directly as RGB color (no shading)
            // This allows kernel to output depth colors directly
            int r = (int) (255 * Math.min(1.0f, Math.max(0.0f, nx)));
            int g = (int) (255 * Math.min(1.0f, Math.max(0.0f, ny)));
            int b = (int) (255 * Math.min(1.0f, Math.max(0.0f, nz)));
            return (r << 24) | (g << 16) | (b << 8) | 255;
        } else {
            // Miss - background color
            return (20 << 24) | (20 << 16) | (30 << 8) | 255;
        }
    }

    @Override
    protected void disposeTypeSpecificBuffers() {
        // Release raw OpenCL buffers
        if (clNodeBuffer != 0) {
            clReleaseMemObject(clNodeBuffer);
            untrackRawHandle(clNodeBuffer);
            clNodeBuffer = 0;
        }
        if (clFarPointerBuffer != 0) {
            clReleaseMemObject(clFarPointerBuffer);
            untrackRawHandle(clFarPointerBuffer);
            clFarPointerBuffer = 0;
        }
        if (clContourBuffer != 0) {
            clReleaseMemObject(clContourBuffer);
            untrackRawHandle(clContourBuffer);
            clContourBuffer = 0;
        }

        // Close OpenCLBuffer objects
        if (normalBuffer != null) normalBuffer.close();
        if (sceneMinBuffer != null) sceneMinBuffer.close();
        if (sceneMaxBuffer != null) sceneMaxBuffer.close();

        // Free CPU buffer
        if (cpuNormalBuffer != null) memFree(cpuNormalBuffer);
    }
}

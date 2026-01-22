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
package com.hellblazer.luciferase.esvo.gpu;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.sparse.core.CoordinateSpace;
import com.hellblazer.luciferase.sparse.gpu.AbstractOpenCLRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * OpenCL-based GPU Renderer for ESVO (Efficient Sparse Voxel Octrees).
 *
 * <p>Extends {@link AbstractOpenCLRenderer} to provide ESVO-specific rendering.
 *
 * <p><b>Key features:</b>
 * <ul>
 *   <li>Stack-based octree ray traversal</li>
 *   <li>Sparse voxel indexing via child masks</li>
 *   <li>Far pointer support for large octrees</li>
 *   <li>Depth-based coloring</li>
 * </ul>
 *
 * <p><b>Coordinate Space:</b> [0, 1] normalized voxel space
 *
 * @author hal.hildebrand
 */
public final class ESVOOpenCLRenderer extends AbstractOpenCLRenderer<ESVONodeUnified, ESVOOctreeData> {
    private static final Logger log = LoggerFactory.getLogger(ESVOOpenCLRenderer.class);

    // Raw cl_mem handle for ByteBuffer upload
    private long clNodeBuffer;

    // Scene bounds derived from coordinate space (ESVO now uses [0,1] normalized coordinates)
    private static final CoordinateSpace COORD_SPACE = CoordinateSpace.UNIT_CUBE;
    private final float[] sceneMin = {COORD_SPACE.getMin(), COORD_SPACE.getMin(), COORD_SPACE.getMin()};
    private final float[] sceneMax = {COORD_SPACE.getMax(), COORD_SPACE.getMax(), COORD_SPACE.getMax()};

    /**
     * Create OpenCL renderer with specified output resolution.
     *
     * @param frameWidth  Output width in pixels
     * @param frameHeight Output height in pixels
     */
    public ESVOOpenCLRenderer(int frameWidth, int frameHeight) {
        super(frameWidth, frameHeight);
    }

    @Override
    protected String getRendererName() {
        return "ESVOOpenCLRenderer";
    }

    @Override
    protected String getKernelSource() {
        return ESVOKernels.getOpenCLKernel();
    }

    @Override
    protected String getKernelEntryPoint() {
        return "traverseOctree";
    }

    @Override
    protected boolean hasDataUploaded() {
        return clNodeBuffer != 0;
    }

    @Override
    protected void allocateTypeSpecificBuffers() {
        // Initialize empty node buffer
        clNodeBuffer = 0;
    }

    @Override
    protected void uploadDataBuffers(ESVOOctreeData data) {
        // Release old node buffer if exists
        if (clNodeBuffer != 0) {
            clReleaseMemObject(clNodeBuffer);
        }

        // Convert octree data to ByteBuffer
        var nodeData = octreeToByteBuffer(data);

        // Create buffer with raw OpenCL API for ByteBuffer compatibility
        clNodeBuffer = createRawBuffer(nodeData, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR);
    }

    /**
     * Convert ESVOOctreeData to ByteBuffer for GPU upload.
     * Each node is 8 bytes (2 ints: childDescriptor + contourDescriptor).
     */
    private ByteBuffer octreeToByteBuffer(ESVOOctreeData data) {
        int nodeCount = data.getNodeCount();
        var buffer = memAlloc(nodeCount * ESVONodeUnified.SIZE_BYTES);
        buffer.order(ByteOrder.nativeOrder());

        // Get sorted node indices
        var indices = data.getNodeIndices();

        for (int idx : indices) {
            var node = data.getNode(idx);
            if (node != null) {
                buffer.putInt(node.getChildDescriptor());
                buffer.putInt(node.getContourDescriptor());
            } else {
                // Empty node
                buffer.putInt(0);
                buffer.putInt(0);
            }
        }

        buffer.flip();
        return buffer;
    }

    @Override
    protected void setKernelArguments() {
        // Set node buffer (arg 1)
        setRawBufferArg(1, clNodeBuffer);

        // Set result buffer (arg 2)
        kernel.setBufferArg(2, resultBuffer, ComputeKernel.BufferAccess.WRITE);

        // Set maxDepth (arg 3)
        kernel.setIntArg(3, maxDepth);

        // Set scene bounds (args 4, 5)
        setFloat3Arg(4, sceneMin[0], sceneMin[1], sceneMin[2]);
        setFloat3Arg(5, sceneMax[0], sceneMax[1], sceneMax[2]);
    }

    @Override
    protected void readTypeSpecificResults() {
        // No additional results to read for ESVO
    }

    @Override
    protected int computePixelColor(float hitX, float hitY, float hitZ,
                                     float distance, float[] extraData) {
        if (distance > 0 && distance < 100.0f) {
            // Hit - compute color based on depth/distance
            // Use depth coloring: close = red, far = blue
            float normalizedDist = Math.min(1.0f, distance / 2.0f);
            int r = (int) (255 * (1.0f - normalizedDist));
            int g = (int) (100 * (1.0f - normalizedDist * 0.5f));
            int b = (int) (255 * normalizedDist);
            return (r << 24) | (g << 16) | (b << 8) | 255;
        } else {
            // Miss - background color
            return (20 << 24) | (20 << 16) | (30 << 8) | 255;
        }
    }

    /**
     * Build OpenCL options for ESVO traversal kernel.
     *
     * Returns preprocessor defines and compiler flags optimized for ESVO relative addressing:
     * - ESVO_MODE=1 (enables ESVO-specific code paths in kernel)
     * - RELATIVE_ADDRESSING=1 (uses childPtr relative to parent node index)
     * - MAX_DEPTH=maxDepth (configures stack depth for relative addressing)
     * - Vendor-specific compiler flags for GPU optimization
     *
     * @return OpenCL build options string for ESVO-optimized kernel
     */
    public String buildOptionsForESVOTraversal() {
        var options = new StringBuilder();

        // ESVO-specific defines
        options.append("-DESVO_MODE=1 ");
        options.append("-DRELATIVE_ADDRESSING=1 ");
        options.append("-DMAX_DEPTH=").append(maxDepth).append(" ");

        // Add vendor-specific compiler flags for GPU optimization
        // (No tuning config in ESVO renderer currently, just vendor-specific)
        options.append("-cl-fast-relaxed-math ");

        return options.toString().trim();
    }

    @Override
    protected String getBuildOptions() {
        return buildOptionsForESVOTraversal();
    }

    @Override
    protected void disposeTypeSpecificBuffers() {
        if (clNodeBuffer != 0) {
            clReleaseMemObject(clNodeBuffer);
            clNodeBuffer = 0;
        }
    }
}

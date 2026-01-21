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

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.sparse.core.CoordinateSpace;
import com.hellblazer.luciferase.sparse.core.PointerAddressingMode;
import com.hellblazer.luciferase.sparse.gpu.AbstractOpenCLRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * F3.1: GPU-Accelerated DAG Ray Traversal Renderer
 *
 * Implements ray traversal for Sparse Voxel DAGs using OpenCL.
 * Uses absolute addressing (direct child pointer lookup) for performance.
 *
 * @author hal.hildebrand
 */
public class DAGOpenCLRenderer extends AbstractOpenCLRenderer<ESVONodeUnified, DAGOctreeData> {
    private static final Logger log = LoggerFactory.getLogger(DAGOpenCLRenderer.class);

    // Raw cl_mem handle for ByteBuffer upload
    private long clNodeBuffer;

    // Scene bounds derived from coordinate space (ESVO uses [0,1] normalized coordinates)
    private static final CoordinateSpace COORD_SPACE = CoordinateSpace.UNIT_CUBE;
    private final float[] sceneMin = {COORD_SPACE.getMin(), COORD_SPACE.getMin(), COORD_SPACE.getMin()};
    private final float[] sceneMax = {COORD_SPACE.getMax(), COORD_SPACE.getMax(), COORD_SPACE.getMax()};

    /**
     * Create a DAG-aware GPU renderer with specified output dimensions
     */
    public DAGOpenCLRenderer(int width, int height) {
        super(width, height);
    }

    @Override
    protected String getRendererName() {
        return "DAGOpenCLRenderer";
    }

    @Override
    protected String getKernelSource() {
        return DAGKernels.getOpenCLKernel();
    }

    @Override
    protected String getKernelEntryPoint() {
        return "rayTraverseDAG";
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
    protected void uploadDataBuffers(DAGOctreeData data) {
        // Validate absolute addressing
        if (data.getAddressingMode() != PointerAddressingMode.ABSOLUTE) {
            throw new IllegalArgumentException("DAG must use absolute addressing");
        }

        // Release old node buffer if exists
        if (clNodeBuffer != 0) {
            clReleaseMemObject(clNodeBuffer);
        }

        // Convert DAG data to ByteBuffer
        var nodeData = dagToByteBuffer(data);

        // Create buffer with raw OpenCL API for ByteBuffer compatibility
        clNodeBuffer = createRawBuffer(nodeData, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR);
    }

    /**
     * Convert DAGOctreeData to ByteBuffer for GPU upload.
     * Each node is 8 bytes (2 ints: childDescriptor + attributes).
     */
    private ByteBuffer dagToByteBuffer(DAGOctreeData data) {
        int nodeCount = data.nodeCount();
        var buffer = memAlloc(nodeCount * ESVONodeUnified.SIZE_BYTES);
        buffer.order(ByteOrder.nativeOrder());

        // Get all nodes from DAG
        var nodes = data.nodes();

        for (var node : nodes) {
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
        // DAG traversal produces same result format as ESVO
        // No additional per-pixel data to read
    }

    @Override
    protected int computePixelColor(float hitX, float hitY, float hitZ, float distance, float[] extraData) {
        // Depth-based coloring similar to ESVO
        if (distance < 0.0f || distance > 1000.0f) {
            // Miss: return black with semi-transparent
            return 0x00000080;
        }

        // Normalize distance to [0, 1] range
        float normalizedDist = Math.min(1.0f, distance / 100.0f);

        // Color based on depth: closer = brighter
        float brightness = 1.0f - normalizedDist;
        int colorValue = (int) (brightness * 255);
        colorValue = Math.max(0, Math.min(255, colorValue));

        // Return as RGBA (R, G, B, A)
        return (colorValue << 24) | (colorValue << 16) | (colorValue << 8) | 0xFF;
    }

    @Override
    protected void disposeTypeSpecificBuffers() {
        if (clNodeBuffer != 0) {
            clReleaseMemObject(clNodeBuffer);
            clNodeBuffer = 0;
        }
    }
}

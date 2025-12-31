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
import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import com.hellblazer.luciferase.resource.opengl.BufferResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * GPU Memory Management for ESVT (Efficient Sparse Voxel Tetrahedra)
 *
 * Manages CPU and GPU buffers for ESVT nodes, following the same pattern as
 * OctreeGPUMemory but specialized for tetrahedral data.
 *
 * CRITICAL: Uses LWJGL MemoryUtil.memAlignedAlloc() for GPU-optimal memory alignment.
 *
 * @author hal.hildebrand
 */
public final class ESVTGPUMemory {
    private static final Logger log = LoggerFactory.getLogger(ESVTGPUMemory.class);

    // GPU memory alignment requirements
    private static final int GPU_ALIGNMENT = 64; // Cache line alignment
    private static final int NODE_SIZE_BYTES = ESVTNodeUnified.SIZE_BYTES; // 8 bytes

    private ByteBuffer nodeBuffer;
    private final long bufferSize;
    private final int nodeCount;
    private final int rootType;
    private boolean disposed = false;

    // Resource manager for GPU resources
    private final UnifiedResourceManager resourceManager = UnifiedResourceManager.getInstance();

    // Managed GPU buffer
    private BufferResource nodeSSBO;

    /**
     * Create GPU memory for ESVT nodes
     *
     * @param nodeCount Number of ESVT nodes to allocate
     * @param rootType Root tetrahedron type (0-5)
     */
    public ESVTGPUMemory(int nodeCount, int rootType) {
        if (nodeCount <= 0) {
            throw new IllegalArgumentException("Node count must be positive");
        }
        if (rootType < 0 || rootType > 5) {
            throw new IllegalArgumentException("Root type must be 0-5");
        }

        this.nodeCount = nodeCount;
        this.rootType = rootType;
        this.bufferSize = (long) nodeCount * NODE_SIZE_BYTES;

        if (bufferSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                String.format("ESVT too large for single allocation: %d bytes", bufferSize));
        }

        // Use LWJGL aligned allocation for GPU performance
        nodeBuffer = memAlignedAlloc(GPU_ALIGNMENT, (int) bufferSize);
        if (nodeBuffer == null) {
            throw new OutOfMemoryError(
                String.format("Failed to allocate GPU buffer of size: %d bytes", bufferSize));
        }

        // Initialize buffer to zero
        memSet(nodeBuffer, (byte) 0);

        log.debug("Allocated ESVT GPU memory: {} nodes, {} bytes, rootType={}", nodeCount, bufferSize, rootType);
    }

    /**
     * Create GPU memory from ESVTData
     *
     * @param esvtData The ESVT data to upload
     */
    public ESVTGPUMemory(ESVTData esvtData) {
        this(esvtData.nodeCount(), esvtData.rootType());

        // Copy node data
        var sourceBuffer = esvtData.toByteBuffer();
        nodeBuffer.put(sourceBuffer);
        nodeBuffer.flip();
    }

    /**
     * Upload node data to GPU as Shader Storage Buffer Object
     */
    public void uploadToGPU() {
        if (disposed) {
            throw new IllegalStateException("GPU memory has been disposed");
        }

        if (nodeSSBO == null) {
            nodeSSBO = resourceManager.createStorageBuffer((int) bufferSize, "ESVTNodeSSBO");
        }

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, nodeSSBO.getOpenGLId());
        glBufferData(GL_SHADER_STORAGE_BUFFER, nodeBuffer, GL_STATIC_DRAW);

        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new RuntimeException(
                String.format("OpenGL error during buffer upload: 0x%X", error));
        }

        log.debug("Uploaded {} bytes to GPU SSBO {}", bufferSize, nodeSSBO.getOpenGLId());
    }

    /**
     * Bind the node buffer to a shader storage binding point
     *
     * @param bindingPoint Binding point index (matches shader layout binding)
     */
    public void bindToShader(int bindingPoint) {
        if (disposed) {
            throw new IllegalStateException("GPU memory has been disposed");
        }

        if (nodeSSBO == null) {
            throw new IllegalStateException("Buffer not uploaded to GPU yet");
        }

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, nodeSSBO.getOpenGLId());
    }

    /**
     * Write node data directly to CPU buffer
     *
     * @param nodeIndex Index of the node to write
     * @param node The node to write
     */
    public void writeNode(int nodeIndex, ESVTNodeUnified node) {
        if (disposed) {
            throw new IllegalStateException("GPU memory has been disposed");
        }

        if (nodeIndex < 0 || nodeIndex >= nodeCount) {
            throw new IndexOutOfBoundsException(
                String.format("Node index %d out of range [0, %d)", nodeIndex, nodeCount));
        }

        int offset = nodeIndex * NODE_SIZE_BYTES;
        nodeBuffer.position(offset);
        node.writeTo(nodeBuffer);
    }

    /**
     * Read node data from CPU buffer
     *
     * @param nodeIndex Index of the node to read
     * @return The node at the given index
     */
    public ESVTNodeUnified readNode(int nodeIndex) {
        if (disposed) {
            throw new IllegalStateException("GPU memory has been disposed");
        }

        if (nodeIndex < 0 || nodeIndex >= nodeCount) {
            throw new IndexOutOfBoundsException(
                String.format("Node index %d out of range [0, %d)", nodeIndex, nodeCount));
        }

        int offset = nodeIndex * NODE_SIZE_BYTES;
        nodeBuffer.position(offset);
        return ESVTNodeUnified.fromByteBuffer(nodeBuffer);
    }

    /**
     * Get direct access to the underlying ByteBuffer (read-only)
     */
    public ByteBuffer getNodeBuffer() {
        if (disposed) {
            throw new IllegalStateException("GPU memory has been disposed");
        }
        return nodeBuffer.asReadOnlyBuffer();
    }

    /**
     * Get the GPU buffer object ID
     */
    public int getSSBO() {
        return nodeSSBO != null ? nodeSSBO.getOpenGLId() : 0;
    }

    /**
     * Get number of nodes allocated
     */
    public int getNodeCount() {
        return nodeCount;
    }

    /**
     * Get the root tetrahedron type
     */
    public int getRootType() {
        return rootType;
    }

    /**
     * Get total buffer size in bytes
     */
    public long getBufferSize() {
        return bufferSize;
    }

    /**
     * Check if memory has been disposed
     */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Dispose GPU memory - MUST be called to avoid memory leaks
     */
    public synchronized void dispose() {
        if (disposed) {
            return;
        }

        try {
            if (nodeSSBO != null) {
                nodeSSBO.close();
                nodeSSBO = null;
            }
        } catch (Exception e) {
            log.error("Error disposing GPU buffer", e);
        }

        if (nodeBuffer != null) {
            memAlignedFree(nodeBuffer);
            nodeBuffer = null;
        }

        disposed = true;
        log.debug("Disposed ESVT GPU memory: {} nodes, {} bytes", nodeCount, bufferSize);
    }

    @Override
    protected void finalize() throws Throwable {
        if (!disposed) {
            log.warn("ESVTGPUMemory was not properly disposed - memory leak detected!");
            dispose();
        }
        super.finalize();
    }
}

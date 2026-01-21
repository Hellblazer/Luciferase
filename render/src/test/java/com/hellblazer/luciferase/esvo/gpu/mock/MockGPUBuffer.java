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
package com.hellblazer.luciferase.esvo.gpu.mock;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Mock GPU Buffer for testing without GPU hardware
 *
 * Simulates GPU buffer operations (allocation, upload, download)
 * for TDD testing phase before real GPU implementation.
 *
 * @author hal.hildebrand
 */
public class MockGPUBuffer {
    private final ByteBuffer data;
    private final int sizeBytes;
    private final BufferAccess access;

    public enum BufferAccess {
        READ_ONLY,
        WRITE_ONLY,
        READ_WRITE
    }

    /**
     * Create a mock GPU buffer
     *
     * @param sizeBytes  Total buffer size in bytes
     * @param access     Buffer access mode (read/write/both)
     */
    public MockGPUBuffer(int sizeBytes, BufferAccess access) {
        this.sizeBytes = sizeBytes;
        this.access = access;
        this.data = ByteBuffer.allocateDirect(sizeBytes);
    }

    /**
     * Upload data to mock GPU buffer (copy from CPU buffer)
     *
     * @param cpuBuffer  Source buffer on CPU
     */
    public void upload(ByteBuffer cpuBuffer) {
        if (access == BufferAccess.READ_ONLY) {
            throw new IllegalStateException("Cannot upload to READ_ONLY buffer");
        }

        cpuBuffer.rewind();
        data.rewind();
        data.put(cpuBuffer);
        data.rewind();
    }

    /**
     * Upload float array to mock GPU buffer
     *
     * @param floatData  Float array to upload
     */
    public void uploadFloats(float[] floatData) {
        if (access == BufferAccess.READ_ONLY) {
            throw new IllegalStateException("Cannot upload to READ_ONLY buffer");
        }

        data.rewind();
        data.asFloatBuffer().put(floatData);
        data.rewind();
    }

    /**
     * Download data from mock GPU buffer (copy to CPU buffer)
     *
     * @param cpuBuffer  Destination buffer on CPU
     */
    public void download(ByteBuffer cpuBuffer) {
        if (access == BufferAccess.WRITE_ONLY) {
            throw new IllegalStateException("Cannot download from WRITE_ONLY buffer");
        }

        data.rewind();
        cpuBuffer.rewind();
        cpuBuffer.put(data);
        cpuBuffer.rewind();
    }

    /**
     * Download as float array from mock GPU buffer
     *
     * @param floatData  Destination float array
     */
    public void downloadFloats(float[] floatData) {
        if (access == BufferAccess.WRITE_ONLY) {
            throw new IllegalStateException("Cannot download from WRITE_ONLY buffer");
        }

        data.rewind();
        FloatBuffer floatView = data.asFloatBuffer();
        floatView.get(floatData);
        data.rewind();
    }

    /**
     * Get the underlying ByteBuffer (for testing)
     *
     * @return  Internal buffer
     */
    public ByteBuffer getBuffer() {
        return data;
    }

    /**
     * Get buffer size in bytes
     *
     * @return  Size in bytes
     */
    public int getSize() {
        return sizeBytes;
    }

    /**
     * Get buffer access mode
     *
     * @return  Access mode
     */
    public BufferAccess getAccess() {
        return access;
    }

    /**
     * Clear buffer contents
     */
    public void clear() {
        data.rewind();
        data.clear();
    }
}

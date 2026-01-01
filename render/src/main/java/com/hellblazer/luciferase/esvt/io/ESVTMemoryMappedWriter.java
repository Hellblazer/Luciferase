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
package com.hellblazer.luciferase.esvt.io;

import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Memory-mapped file writer for ESVT tetrahedral data.
 *
 * <p>Uses memory mapping for efficient writing of large ESVT files,
 * allowing the OS to manage page flushing.
 *
 * @author hal.hildebrand
 * @see ESVTMemoryMappedReader
 */
public class ESVTMemoryMappedWriter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVTMemoryMappedWriter.class);

    private final int version;
    private boolean closed = false;

    public ESVTMemoryMappedWriter() {
        this(ESVTFileFormat.CURRENT_VERSION);
    }

    public ESVTMemoryMappedWriter(int version) {
        this.version = version;
    }

    /**
     * Write ESVT data using memory-mapped file.
     *
     * @param data The ESVT data to write
     * @param outputFile Path to the output file
     * @throws IOException if writing fails
     */
    public void write(ESVTData data, Path outputFile) throws IOException {
        ensureNotClosed();

        int headerSize = version >= ESVTFileFormat.VERSION_2 ?
            ESVTFileFormat.HEADER_SIZE_V2 : ESVTFileFormat.HEADER_SIZE_V1;

        // Calculate file size
        long fileSize = headerSize;
        fileSize += (long) data.nodeCount() * ESVTNodeUnified.SIZE_BYTES;
        fileSize += (long) data.contourCount() * 4;
        fileSize += (long) data.farPointerCount() * 4;
        if (version >= ESVTFileFormat.VERSION_2 && data.hasVoxelCoords()) {
            fileSize += (long) data.leafVoxelCoords().length * 4;
        }

        try (var raf = new RandomAccessFile(outputFile.toFile(), "rw");
             var channel = raf.getChannel()) {

            // Set file size
            raf.setLength(fileSize);

            // Map entire file
            MappedByteBuffer buffer = channel.map(
                FileChannel.MapMode.READ_WRITE, 0, fileSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // Write header
            buffer.putInt(ESVTFileFormat.MAGIC_NUMBER);
            buffer.putInt(version);
            buffer.putInt(data.nodeCount());
            buffer.putInt(data.contourCount());
            buffer.putInt(data.farPointerCount());
            buffer.putInt(data.rootType());
            buffer.putInt(data.maxDepth());
            buffer.putInt(0); // reserved

            if (version >= ESVTFileFormat.VERSION_2) {
                buffer.putLong(0); // metadata offset (not used in memory-mapped write)
                buffer.putLong(0); // metadata size
                buffer.putInt(data.leafCount());
                buffer.putInt(data.internalCount());
                buffer.putInt(data.gridResolution());
                buffer.putInt(data.hasVoxelCoords() ? data.leafVoxelCoords().length : 0);
            }

            // Write nodes
            for (var node : data.nodes()) {
                node.writeTo(buffer);
            }

            // Write contours
            if (data.hasContours()) {
                for (int c : data.contours()) {
                    buffer.putInt(c);
                }
            }

            // Write far pointers
            if (data.hasFarPointers()) {
                for (int ptr : data.farPointers()) {
                    buffer.putInt(ptr);
                }
            }

            // Write voxel coords (V2)
            if (version >= ESVTFileFormat.VERSION_2 && data.hasVoxelCoords()) {
                for (int coord : data.leafVoxelCoords()) {
                    buffer.putInt(coord);
                }
            }

            // Force write to disk
            buffer.force();

            log.debug("Memory-mapped write to {}: {} nodes, {} bytes",
                    outputFile, data.nodeCount(), fileSize);
        }
    }

    /**
     * Update a single node at a specific index in an existing file.
     *
     * <p>Efficient for incremental updates without rewriting the entire file.
     *
     * @param outputFile Path to the ESVT file
     * @param nodeIndex Index of the node to update
     * @param node The new node value
     * @throws IOException if writing fails
     */
    public void updateNode(Path outputFile, int nodeIndex, ESVTNodeUnified node) throws IOException {
        ensureNotClosed();

        try (var raf = new RandomAccessFile(outputFile.toFile(), "rw");
             var channel = raf.getChannel()) {

            // Read header to get version
            MappedByteBuffer headerBuffer = channel.map(
                FileChannel.MapMode.READ_ONLY, 0, ESVTFileFormat.HEADER_SIZE_V2);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);

            int magic = headerBuffer.getInt();
            if (magic != ESVTFileFormat.MAGIC_NUMBER) {
                throw new IOException("Invalid ESVT file");
            }

            int fileVersion = headerBuffer.getInt();
            int headerSize = fileVersion >= ESVTFileFormat.VERSION_2 ?
                ESVTFileFormat.HEADER_SIZE_V2 : ESVTFileFormat.HEADER_SIZE_V1;

            // Calculate node offset
            long nodeOffset = headerSize + (long) nodeIndex * ESVTNodeUnified.SIZE_BYTES;

            // Map just the node
            MappedByteBuffer nodeBuffer = channel.map(
                FileChannel.MapMode.READ_WRITE, nodeOffset, ESVTNodeUnified.SIZE_BYTES);
            nodeBuffer.order(ByteOrder.LITTLE_ENDIAN);

            node.writeTo(nodeBuffer);
            nodeBuffer.force();
        }
    }

    /**
     * Update a range of nodes in an existing file.
     *
     * @param outputFile Path to the ESVT file
     * @param startIndex First node index to update
     * @param nodes Array of nodes to write
     * @throws IOException if writing fails
     */
    public void updateNodes(Path outputFile, int startIndex, ESVTNodeUnified[] nodes) throws IOException {
        ensureNotClosed();

        try (var raf = new RandomAccessFile(outputFile.toFile(), "rw");
             var channel = raf.getChannel()) {

            // Read header for version
            MappedByteBuffer headerBuffer = channel.map(
                FileChannel.MapMode.READ_ONLY, 0, ESVTFileFormat.HEADER_SIZE_V2);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);

            int magic = headerBuffer.getInt();
            if (magic != ESVTFileFormat.MAGIC_NUMBER) {
                throw new IOException("Invalid ESVT file");
            }

            int fileVersion = headerBuffer.getInt();
            int headerSize = fileVersion >= ESVTFileFormat.VERSION_2 ?
                ESVTFileFormat.HEADER_SIZE_V2 : ESVTFileFormat.HEADER_SIZE_V1;

            // Calculate range offset
            long offset = headerSize + (long) startIndex * ESVTNodeUnified.SIZE_BYTES;
            long size = (long) nodes.length * ESVTNodeUnified.SIZE_BYTES;

            // Map the node range
            MappedByteBuffer buffer = channel.map(
                FileChannel.MapMode.READ_WRITE, offset, size);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            for (var node : nodes) {
                node.writeTo(buffer);
            }

            buffer.force();
        }
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("ESVTMemoryMappedWriter has been closed");
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}

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
 * Memory-mapped file reader for ESVT tetrahedral data.
 *
 * <p>Uses memory mapping for efficient reading of large ESVT files,
 * avoiding the need to load the entire file into heap memory.
 *
 * @author hal.hildebrand
 * @see ESVTMemoryMappedWriter
 */
public class ESVTMemoryMappedReader implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVTMemoryMappedReader.class);

    private boolean closed = false;

    /**
     * Read ESVT data using memory-mapped file.
     *
     * @param inputFile Path to the ESVT file
     * @return ESVTData read from the file
     * @throws IOException if reading fails
     */
    public ESVTData read(Path inputFile) throws IOException {
        ensureNotClosed();

        try (var raf = new RandomAccessFile(inputFile.toFile(), "r");
             var channel = raf.getChannel()) {

            long fileSize = channel.size();

            // Map entire file
            MappedByteBuffer buffer = channel.map(
                FileChannel.MapMode.READ_ONLY, 0, fileSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // Read and validate magic
            int magic = buffer.getInt();
            if (magic != ESVTFileFormat.MAGIC_NUMBER) {
                throw new IOException("Invalid ESVT file: bad magic number 0x" +
                    Integer.toHexString(magic));
            }

            // Read header
            int version = buffer.getInt();
            int nodeCount = buffer.getInt();
            int contourCount = buffer.getInt();
            int farPtrCount = buffer.getInt();
            int rootType = buffer.getInt();
            int maxDepth = buffer.getInt();
            buffer.getInt(); // reserved

            int leafCount = 0;
            int internalCount = 0;
            int gridResolution = 0;
            int voxelCoordsCount = 0;

            if (version >= ESVTFileFormat.VERSION_2) {
                buffer.getLong(); // metadata offset
                buffer.getLong(); // metadata size
                leafCount = buffer.getInt();
                internalCount = buffer.getInt();
                gridResolution = buffer.getInt();
                voxelCoordsCount = buffer.getInt();
            }

            // Read nodes
            var nodes = new ESVTNodeUnified[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                nodes[i] = ESVTNodeUnified.fromByteBuffer(buffer);
            }

            // Read contours
            var contours = new int[contourCount];
            for (int i = 0; i < contourCount; i++) {
                contours[i] = buffer.getInt();
            }

            // Read far pointers
            var farPointers = new int[farPtrCount];
            for (int i = 0; i < farPtrCount; i++) {
                farPointers[i] = buffer.getInt();
            }

            // Read voxel coords (V2)
            var voxelCoords = new int[voxelCoordsCount];
            for (int i = 0; i < voxelCoordsCount; i++) {
                voxelCoords[i] = buffer.getInt();
            }

            log.debug("Memory-mapped read from {}: {} nodes, {} bytes",
                    inputFile, nodeCount, fileSize);

            return new ESVTData(
                nodes,
                contours,
                farPointers,
                rootType,
                maxDepth,
                leafCount,
                internalCount,
                gridResolution,
                voxelCoords
            );
        }
    }

    /**
     * Read a single node at a specific index.
     *
     * <p>Efficient for random access to individual nodes without
     * loading the entire file.
     *
     * @param inputFile Path to the ESVT file
     * @param nodeIndex Index of the node to read
     * @return The node at the specified index
     * @throws IOException if reading fails
     */
    public ESVTNodeUnified readNode(Path inputFile, int nodeIndex) throws IOException {
        ensureNotClosed();

        try (var raf = new RandomAccessFile(inputFile.toFile(), "r");
             var channel = raf.getChannel()) {

            // First read header to get version
            MappedByteBuffer headerBuffer = channel.map(
                FileChannel.MapMode.READ_ONLY, 0, ESVTFileFormat.HEADER_SIZE_V2);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);

            int magic = headerBuffer.getInt();
            if (magic != ESVTFileFormat.MAGIC_NUMBER) {
                throw new IOException("Invalid ESVT file");
            }

            int version = headerBuffer.getInt();
            int headerSize = version >= ESVTFileFormat.VERSION_2 ?
                ESVTFileFormat.HEADER_SIZE_V2 : ESVTFileFormat.HEADER_SIZE_V1;

            // Calculate node offset
            long nodeOffset = headerSize + (long) nodeIndex * ESVTNodeUnified.SIZE_BYTES;

            // Map just the node
            MappedByteBuffer nodeBuffer = channel.map(
                FileChannel.MapMode.READ_ONLY, nodeOffset, ESVTNodeUnified.SIZE_BYTES);
            nodeBuffer.order(ByteOrder.LITTLE_ENDIAN);

            return ESVTNodeUnified.fromByteBuffer(nodeBuffer);
        }
    }

    /**
     * Read a range of nodes.
     *
     * @param inputFile Path to the ESVT file
     * @param startIndex First node index to read
     * @param count Number of nodes to read
     * @return Array of nodes
     * @throws IOException if reading fails
     */
    public ESVTNodeUnified[] readNodes(Path inputFile, int startIndex, int count) throws IOException {
        ensureNotClosed();

        try (var raf = new RandomAccessFile(inputFile.toFile(), "r");
             var channel = raf.getChannel()) {

            // Read header for version
            MappedByteBuffer headerBuffer = channel.map(
                FileChannel.MapMode.READ_ONLY, 0, ESVTFileFormat.HEADER_SIZE_V2);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);

            int magic = headerBuffer.getInt();
            if (magic != ESVTFileFormat.MAGIC_NUMBER) {
                throw new IOException("Invalid ESVT file");
            }

            int version = headerBuffer.getInt();
            int headerSize = version >= ESVTFileFormat.VERSION_2 ?
                ESVTFileFormat.HEADER_SIZE_V2 : ESVTFileFormat.HEADER_SIZE_V1;

            // Calculate range offset
            long offset = headerSize + (long) startIndex * ESVTNodeUnified.SIZE_BYTES;
            long size = (long) count * ESVTNodeUnified.SIZE_BYTES;

            // Map the node range
            MappedByteBuffer buffer = channel.map(
                FileChannel.MapMode.READ_ONLY, offset, size);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            var nodes = new ESVTNodeUnified[count];
            for (int i = 0; i < count; i++) {
                nodes[i] = ESVTNodeUnified.fromByteBuffer(buffer);
            }

            return nodes;
        }
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("ESVTMemoryMappedReader has been closed");
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}

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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Deserializer for ESVT (Efficient Sparse Voxel Tetrahedra) data.
 *
 * <p>Reads ESVTData from files written by {@link ESVTSerializer}.
 *
 * @author hal.hildebrand
 * @see ESVTSerializer
 * @see ESVTFileFormat
 */
public class ESVTDeserializer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVTDeserializer.class);

    private boolean closed = false;

    public ESVTDeserializer() {
        log.debug("ESVTDeserializer created");
    }

    /**
     * Deserialize ESVT data from a file.
     *
     * @param inputFile Path to the ESVT file
     * @return Deserialized ESVTData
     * @throws IOException if deserialization fails
     */
    public ESVTData deserialize(Path inputFile) throws IOException {
        ensureNotClosed();

        try (var channel = FileChannel.open(inputFile, StandardOpenOption.READ)) {
            // Read and validate header
            var header = readHeader(channel);

            // Read nodes
            var nodes = readNodes(channel, header);

            // Read contours
            var contours = readContours(channel, header);

            // Read far pointers
            var farPointers = readFarPointers(channel, header);

            // Read voxel coords (V2)
            int[] voxelCoords = new int[0];
            int gridResolution = 0;
            if (header.version >= ESVTFileFormat.VERSION_2) {
                voxelCoords = readVoxelCoords(channel, header);
                gridResolution = header.gridResolution;
            }

            log.info("Deserialized ESVT from {}: {} nodes, depth={}, rootType={}",
                    inputFile, header.nodeCount, header.maxDepth, header.rootType);

            return new ESVTData(
                nodes,
                contours,
                farPointers,
                header.rootType,
                header.maxDepth,
                header.leafCount,
                header.internalCount,
                gridResolution,
                voxelCoords
            );
        }
    }

    /**
     * Deserialize ESVT data and metadata from a file.
     *
     * @param inputFile Path to the ESVT file
     * @return Result containing both data and metadata
     * @throws IOException if deserialization fails
     */
    public DeserializeResult deserializeWithMetadata(Path inputFile) throws IOException {
        ensureNotClosed();

        try (var channel = FileChannel.open(inputFile, StandardOpenOption.READ)) {
            var header = readHeader(channel);
            var nodes = readNodes(channel, header);
            var contours = readContours(channel, header);
            var farPointers = readFarPointers(channel, header);

            int[] voxelCoords = new int[0];
            int gridResolution = 0;
            ESVTMetadata metadata = null;

            if (header.version >= ESVTFileFormat.VERSION_2) {
                voxelCoords = readVoxelCoords(channel, header);
                gridResolution = header.gridResolution;

                if (header.metadataSize > 0) {
                    metadata = readMetadata(channel, header);
                }
            }

            var data = new ESVTData(
                nodes,
                contours,
                farPointers,
                header.rootType,
                header.maxDepth,
                header.leafCount,
                header.internalCount,
                gridResolution,
                voxelCoords
            );

            return new DeserializeResult(data, metadata, header);
        }
    }

    private ESVTFileFormat.Header readHeader(FileChannel channel) throws IOException {
        // First read minimal header to get version
        var minBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(minBuffer);
        minBuffer.flip();

        int magic = minBuffer.getInt();
        if (magic != ESVTFileFormat.MAGIC_NUMBER) {
            throw new IOException("Invalid ESVT file: bad magic number 0x" +
                Integer.toHexString(magic));
        }

        int version = minBuffer.getInt();

        // Now read full header based on version
        int headerSize = version >= ESVTFileFormat.VERSION_2 ?
            ESVTFileFormat.HEADER_SIZE_V2 : ESVTFileFormat.HEADER_SIZE_V1;

        channel.position(0);
        var headerBuffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(headerBuffer);
        headerBuffer.flip();

        var header = new ESVTFileFormat.Header(version);
        header.magic = headerBuffer.getInt();
        header.version = headerBuffer.getInt();
        header.nodeCount = headerBuffer.getInt();
        header.contourCount = headerBuffer.getInt();
        header.farPtrCount = headerBuffer.getInt();
        header.rootType = headerBuffer.getInt();
        header.maxDepth = headerBuffer.getInt();
        header.reserved = headerBuffer.getInt();

        if (version >= ESVTFileFormat.VERSION_2) {
            header.metadataOffset = headerBuffer.getLong();
            header.metadataSize = headerBuffer.getLong();
            header.leafCount = headerBuffer.getInt();
            header.internalCount = headerBuffer.getInt();
            header.gridResolution = headerBuffer.getInt();
            header.voxelCoordsCount = headerBuffer.getInt();
        }

        log.debug("Read header: {}", header);
        return header;
    }

    private ESVTNodeUnified[] readNodes(FileChannel channel, ESVTFileFormat.Header header) throws IOException {
        if (header.nodeCount == 0) {
            return new ESVTNodeUnified[0];
        }

        int size = header.nodeCount * ESVTNodeUnified.SIZE_BYTES;
        var buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(header.getNodesOffset());
        channel.read(buffer);
        buffer.flip();

        var nodes = new ESVTNodeUnified[header.nodeCount];
        for (int i = 0; i < header.nodeCount; i++) {
            nodes[i] = ESVTNodeUnified.readFrom(buffer);
        }

        return nodes;
    }

    private int[] readContours(FileChannel channel, ESVTFileFormat.Header header) throws IOException {
        if (header.contourCount == 0) {
            return new int[0];
        }

        int size = header.contourCount * 4;
        var buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(header.getContoursOffset());
        channel.read(buffer);
        buffer.flip();

        var contours = new int[header.contourCount];
        for (int i = 0; i < header.contourCount; i++) {
            contours[i] = buffer.getInt();
        }

        return contours;
    }

    private int[] readFarPointers(FileChannel channel, ESVTFileFormat.Header header) throws IOException {
        if (header.farPtrCount == 0) {
            return new int[0];
        }

        int size = header.farPtrCount * 4;
        var buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(header.getFarPointersOffset());
        channel.read(buffer);
        buffer.flip();

        var farPtrs = new int[header.farPtrCount];
        for (int i = 0; i < header.farPtrCount; i++) {
            farPtrs[i] = buffer.getInt();
        }

        return farPtrs;
    }

    private int[] readVoxelCoords(FileChannel channel, ESVTFileFormat.Header header) throws IOException {
        if (header.voxelCoordsCount == 0) {
            return new int[0];
        }

        int size = header.voxelCoordsCount * 4;
        var buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(header.getVoxelCoordsOffset());
        channel.read(buffer);
        buffer.flip();

        var coords = new int[header.voxelCoordsCount];
        for (int i = 0; i < header.voxelCoordsCount; i++) {
            coords[i] = buffer.getInt();
        }

        return coords;
    }

    private ESVTMetadata readMetadata(FileChannel channel, ESVTFileFormat.Header header) throws IOException {
        if (header.metadataSize <= 0) {
            return null;
        }

        var buffer = ByteBuffer.allocate((int) header.metadataSize);
        channel.position(header.metadataOffset);
        channel.read(buffer);
        buffer.flip();

        try (var bais = new ByteArrayInputStream(buffer.array());
             var ois = new ObjectInputStream(bais)) {
            return (ESVTMetadata) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Failed to deserialize metadata", e);
        }
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("ESVTDeserializer has been closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            log.debug("ESVTDeserializer closed");
        }
    }

    /**
     * Result of deserialization including data and optional metadata.
     */
    public record DeserializeResult(
        ESVTData data,
        ESVTMetadata metadata,
        ESVTFileFormat.Header header
    ) {
        public boolean hasMetadata() {
            return metadata != null;
        }
    }
}

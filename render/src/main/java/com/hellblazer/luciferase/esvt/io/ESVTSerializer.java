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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serializer for ESVT (Efficient Sparse Voxel Tetrahedra) data.
 *
 * <p>Writes ESVTData to files in a binary format optimized for fast loading
 * and memory mapping.
 *
 * @author hal.hildebrand
 * @see ESVTDeserializer
 * @see ESVTFileFormat
 */
public class ESVTSerializer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVTSerializer.class);

    private final int version;
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private boolean closed = false;

    public ESVTSerializer() {
        this(ESVTFileFormat.CURRENT_VERSION);
    }

    public ESVTSerializer(int version) {
        this.version = version;
        log.debug("ESVTSerializer created with version {}", version);
    }

    /**
     * Serialize ESVT data to a file.
     *
     * @param data       The ESVT data to serialize
     * @param outputFile Path to the output file
     * @throws IOException if serialization fails
     */
    public void serialize(ESVTData data, Path outputFile) throws IOException {
        serialize(data, null, outputFile);
    }

    /**
     * Serialize ESVT data with metadata to a file.
     *
     * @param data       The ESVT data to serialize
     * @param metadata   Optional metadata (may be null)
     * @param outputFile Path to the output file
     * @throws IOException if serialization fails
     */
    public void serialize(ESVTData data, ESVTMetadata metadata, Path outputFile) throws IOException {
        ensureNotClosed();

        try (var channel = FileChannel.open(outputFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            // Build header
            var header = buildHeader(data);

            // Write header (placeholder - will update metadata offset later)
            var headerBuffer = ByteBuffer.allocate(header.getHeaderSize())
                                         .order(ByteOrder.LITTLE_ENDIAN);
            writeHeader(headerBuffer, header);
            headerBuffer.flip();
            totalBytesWritten.addAndGet(channel.write(headerBuffer));

            // Write nodes
            writeNodes(channel, data);

            // Write contours
            writeContours(channel, data);

            // Write far pointers
            writeFarPointers(channel, data);

            // Write voxel coords (V2)
            if (version >= ESVTFileFormat.VERSION_2 && data.hasVoxelCoords()) {
                writeVoxelCoords(channel, data);
            }

            // Write metadata if provided (V2)
            if (version >= ESVTFileFormat.VERSION_2 && metadata != null) {
                long metadataOffset = channel.position();
                byte[] metadataBytes = serializeMetadata(metadata);
                channel.write(ByteBuffer.wrap(metadataBytes));

                // Update header with metadata location
                header.metadataOffset = metadataOffset;
                header.metadataSize = metadataBytes.length;

                headerBuffer.clear();
                writeHeader(headerBuffer, header);
                headerBuffer.flip();
                channel.position(0);
                channel.write(headerBuffer);
            }

            log.info("Serialized ESVT to {}: {} nodes, {} bytes",
                    outputFile, data.nodeCount(), totalBytesWritten.get());
        }
    }

    private ESVTFileFormat.Header buildHeader(ESVTData data) {
        var header = new ESVTFileFormat.Header(version);
        header.nodeCount = data.nodeCount();
        header.contourCount = data.contourCount();
        header.farPtrCount = data.farPointerCount();
        header.rootType = data.rootType();
        header.maxDepth = data.maxDepth();

        if (version >= ESVTFileFormat.VERSION_2) {
            header.leafCount = data.leafCount();
            header.internalCount = data.internalCount();
            header.gridResolution = data.gridResolution();
            header.voxelCoordsCount = data.hasVoxelCoords() ? data.leafVoxelCoords().length : 0;
        }

        return header;
    }

    private void writeHeader(ByteBuffer buffer, ESVTFileFormat.Header header) {
        buffer.putInt(header.magic);
        buffer.putInt(header.version);
        buffer.putInt(header.nodeCount);
        buffer.putInt(header.contourCount);
        buffer.putInt(header.farPtrCount);
        buffer.putInt(header.rootType);
        buffer.putInt(header.maxDepth);
        buffer.putInt(header.reserved);

        if (header.version >= ESVTFileFormat.VERSION_2) {
            buffer.putLong(header.metadataOffset);
            buffer.putLong(header.metadataSize);
            buffer.putInt(header.leafCount);
            buffer.putInt(header.internalCount);
            buffer.putInt(header.gridResolution);
            buffer.putInt(header.voxelCoordsCount);
        }
    }

    private void writeNodes(FileChannel channel, ESVTData data) throws IOException {
        var nodes = data.nodes();
        if (nodes.length == 0) return;

        var buffer = ByteBuffer.allocate(nodes.length * ESVTNodeUnified.SIZE_BYTES)
                               .order(ByteOrder.LITTLE_ENDIAN);

        for (var node : nodes) {
            node.writeTo(buffer);
        }

        buffer.flip();
        totalBytesWritten.addAndGet(channel.write(buffer));
    }

    private void writeContours(FileChannel channel, ESVTData data) throws IOException {
        var contours = data.contours();
        if (contours == null || contours.length == 0) return;

        var buffer = ByteBuffer.allocate(contours.length * 4)
                               .order(ByteOrder.LITTLE_ENDIAN);

        for (int c : contours) {
            buffer.putInt(c);
        }

        buffer.flip();
        totalBytesWritten.addAndGet(channel.write(buffer));
    }

    private void writeFarPointers(FileChannel channel, ESVTData data) throws IOException {
        var farPtrs = data.farPointers();
        if (farPtrs == null || farPtrs.length == 0) return;

        var buffer = ByteBuffer.allocate(farPtrs.length * 4)
                               .order(ByteOrder.LITTLE_ENDIAN);

        for (int ptr : farPtrs) {
            buffer.putInt(ptr);
        }

        buffer.flip();
        totalBytesWritten.addAndGet(channel.write(buffer));
    }

    private void writeVoxelCoords(FileChannel channel, ESVTData data) throws IOException {
        var coords = data.leafVoxelCoords();
        if (coords == null || coords.length == 0) return;

        var buffer = ByteBuffer.allocate(coords.length * 4)
                               .order(ByteOrder.LITTLE_ENDIAN);

        for (int coord : coords) {
            buffer.putInt(coord);
        }

        buffer.flip();
        totalBytesWritten.addAndGet(channel.write(buffer));
    }

    private byte[] serializeMetadata(ESVTMetadata metadata) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var oos = new ObjectOutputStream(baos)) {
            oos.writeObject(metadata);
        }
        return baos.toByteArray();
    }

    /**
     * Get total bytes written across all serialize calls.
     */
    public long getTotalBytesWritten() {
        return totalBytesWritten.get();
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("ESVTSerializer has been closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            log.debug("ESVTSerializer closed. Total bytes written: {}", totalBytesWritten.get());
        }
    }
}

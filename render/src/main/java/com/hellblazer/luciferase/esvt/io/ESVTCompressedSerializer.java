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

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * Compressed serializer for ESVT data using GZIP compression.
 *
 * <p>Provides ~50% size reduction for typical ESVT data while maintaining
 * fast decompression.
 *
 * @author hal.hildebrand
 * @see ESVTCompressedDeserializer
 */
public class ESVTCompressedSerializer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVTCompressedSerializer.class);

    // Compressed file magic: "EVTC" (ESVT Compressed)
    public static final int COMPRESSED_MAGIC = 0x43545645;

    private boolean closed = false;

    /**
     * Serialize ESVT data with GZIP compression.
     *
     * @param data       The ESVT data to serialize
     * @param outputFile Path to the output file
     * @throws IOException if serialization fails
     */
    public void serialize(ESVTData data, Path outputFile) throws IOException {
        serialize(data, null, outputFile);
    }

    /**
     * Serialize ESVT data with metadata and GZIP compression.
     *
     * @param data       The ESVT data to serialize
     * @param metadata   Optional metadata
     * @param outputFile Path to the output file
     * @throws IOException if serialization fails
     */
    public void serialize(ESVTData data, ESVTMetadata metadata, Path outputFile) throws IOException {
        ensureNotClosed();

        long startTime = System.nanoTime();

        try (var fos = new BufferedOutputStream(Files.newOutputStream(outputFile));
             var dos = new DataOutputStream(fos)) {

            // Write compressed magic
            dos.writeInt(Integer.reverseBytes(COMPRESSED_MAGIC));

            // Write uncompressed size estimate for progress tracking
            long uncompressedSize = estimateUncompressedSize(data);
            dos.writeLong(Long.reverseBytes(uncompressedSize));

            // Compress the rest with GZIP
            try (var gzos = new GZIPOutputStream(fos)) {
                writeCompressedData(new DataOutputStream(gzos), data, metadata);
            }
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        long fileSize = Files.size(outputFile);
        long uncompressed = estimateUncompressedSize(data);
        double ratio = (1.0 - (double) fileSize / uncompressed) * 100;

        log.info("Compressed ESVT to {}: {} nodes, {}KB -> {}KB ({}% reduction, {}ms)",
                outputFile.getFileName(), data.nodeCount(),
                uncompressed / 1024, fileSize / 1024, String.format("%.1f", ratio), elapsed);
    }

    private void writeCompressedData(DataOutputStream dos, ESVTData data, ESVTMetadata metadata)
            throws IOException {

        // Write header info
        dos.writeInt(ESVTFileFormat.CURRENT_VERSION);
        dos.writeInt(data.nodeCount());
        dos.writeInt(data.contourCount());
        dos.writeInt(data.farPointerCount());
        dos.writeInt(data.rootType());
        dos.writeInt(data.maxDepth());
        dos.writeInt(data.leafCount());
        dos.writeInt(data.internalCount());
        dos.writeInt(data.gridResolution());
        dos.writeInt(data.hasVoxelCoords() ? data.leafVoxelCoords().length : 0);
        dos.writeBoolean(metadata != null);

        // Write nodes
        var nodeBuffer = ByteBuffer.allocate(ESVTNodeUnified.SIZE_BYTES)
                                   .order(ByteOrder.LITTLE_ENDIAN);
        for (var node : data.nodes()) {
            nodeBuffer.clear();
            node.writeTo(nodeBuffer);
            nodeBuffer.flip();
            dos.write(nodeBuffer.array(), 0, ESVTNodeUnified.SIZE_BYTES);
        }

        // Write contours
        for (int c : data.contours()) {
            dos.writeInt(Integer.reverseBytes(c));
        }

        // Write far pointers
        for (int fp : data.farPointers()) {
            dos.writeInt(Integer.reverseBytes(fp));
        }

        // Write voxel coords
        if (data.hasVoxelCoords()) {
            for (int coord : data.leafVoxelCoords()) {
                dos.writeInt(Integer.reverseBytes(coord));
            }
        }

        // Write metadata
        if (metadata != null) {
            var baos = new ByteArrayOutputStream();
            try (var oos = new ObjectOutputStream(baos)) {
                oos.writeObject(metadata);
            }
            byte[] metaBytes = baos.toByteArray();
            dos.writeInt(metaBytes.length);
            dos.write(metaBytes);
        }
    }

    private long estimateUncompressedSize(ESVTData data) {
        long size = ESVTFileFormat.HEADER_SIZE_V2;
        size += (long) data.nodeCount() * ESVTNodeUnified.SIZE_BYTES;
        size += (long) data.contourCount() * 4;
        size += (long) data.farPointerCount() * 4;
        if (data.hasVoxelCoords()) {
            size += (long) data.leafVoxelCoords().length * 4;
        }
        return size;
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("ESVTCompressedSerializer has been closed");
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}

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
import java.util.zip.GZIPInputStream;

/**
 * Decompresses and deserializes ESVT data from GZIP-compressed files.
 *
 * <p>Reads files created by {@link ESVTCompressedSerializer}.
 *
 * @author hal.hildebrand
 * @see ESVTCompressedSerializer
 */
public class ESVTCompressedDeserializer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVTCompressedDeserializer.class);

    private boolean closed = false;

    /**
     * Deserialize ESVT data from a GZIP-compressed file.
     *
     * @param inputFile Path to the compressed ESVT file
     * @return Deserialized ESVTData
     * @throws IOException if deserialization fails
     */
    public ESVTData deserialize(Path inputFile) throws IOException {
        return deserializeWithMetadata(inputFile).data();
    }

    /**
     * Deserialize ESVT data and metadata from a GZIP-compressed file.
     *
     * @param inputFile Path to the compressed ESVT file
     * @return Result containing both data and optional metadata
     * @throws IOException if deserialization fails
     */
    public DeserializeResult deserializeWithMetadata(Path inputFile) throws IOException {
        ensureNotClosed();

        long startTime = System.nanoTime();
        long fileSize = Files.size(inputFile);

        try (var fis = new BufferedInputStream(Files.newInputStream(inputFile));
             var dis = new DataInputStream(fis)) {

            // Read and validate compressed magic
            int magic = Integer.reverseBytes(dis.readInt());
            if (magic != ESVTCompressedSerializer.COMPRESSED_MAGIC) {
                throw new IOException("Invalid compressed ESVT file: bad magic number 0x" +
                    Integer.toHexString(magic) + ", expected 0x" +
                    Integer.toHexString(ESVTCompressedSerializer.COMPRESSED_MAGIC));
            }

            // Read uncompressed size estimate (for progress tracking)
            long uncompressedSize = Long.reverseBytes(dis.readLong());

            // Decompress and read the rest
            try (var gzis = new GZIPInputStream(fis);
                 var gdis = new DataInputStream(gzis)) {

                var result = readCompressedData(gdis);

                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                log.info("Decompressed ESVT from {}: {} nodes, {}KB compressed, {}ms",
                        inputFile.getFileName(), result.data().nodeCount(),
                        fileSize / 1024, elapsed);

                return result;
            }
        }
    }

    private DeserializeResult readCompressedData(DataInputStream dis) throws IOException {
        // Read header info
        int version = dis.readInt();
        int nodeCount = dis.readInt();
        int contourCount = dis.readInt();
        int farPointerCount = dis.readInt();
        int rootType = dis.readInt();
        int maxDepth = dis.readInt();
        int leafCount = dis.readInt();
        int internalCount = dis.readInt();
        int gridResolution = dis.readInt();
        int voxelCoordsCount = dis.readInt();
        boolean hasMetadata = dis.readBoolean();

        // Read nodes
        var nodes = new ESVTNodeUnified[nodeCount];
        var nodeBuffer = ByteBuffer.allocate(ESVTNodeUnified.SIZE_BYTES)
                                   .order(ByteOrder.LITTLE_ENDIAN);
        byte[] nodeBytes = new byte[ESVTNodeUnified.SIZE_BYTES];

        for (int i = 0; i < nodeCount; i++) {
            dis.readFully(nodeBytes);
            nodeBuffer.clear();
            nodeBuffer.put(nodeBytes);
            nodeBuffer.flip();
            nodes[i] = ESVTNodeUnified.readFrom(nodeBuffer);
        }

        // Read contours
        var contours = new int[contourCount];
        for (int i = 0; i < contourCount; i++) {
            contours[i] = Integer.reverseBytes(dis.readInt());
        }

        // Read far pointers
        var farPointers = new int[farPointerCount];
        for (int i = 0; i < farPointerCount; i++) {
            farPointers[i] = Integer.reverseBytes(dis.readInt());
        }

        // Read voxel coords
        var voxelCoords = new int[voxelCoordsCount];
        for (int i = 0; i < voxelCoordsCount; i++) {
            voxelCoords[i] = Integer.reverseBytes(dis.readInt());
        }

        // Read metadata if present
        ESVTMetadata metadata = null;
        if (hasMetadata) {
            int metaSize = dis.readInt();
            byte[] metaBytes = new byte[metaSize];
            dis.readFully(metaBytes);

            try (var bais = new ByteArrayInputStream(metaBytes);
                 var ois = new ObjectInputStream(bais)) {
                metadata = (ESVTMetadata) ois.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException("Failed to deserialize metadata", e);
            }
        }

        var data = new ESVTData(
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

        return new DeserializeResult(data, metadata);
    }

    /**
     * Check if a file is a compressed ESVT file.
     *
     * @param file Path to check
     * @return true if file has compressed ESVT magic number
     */
    public static boolean isCompressedESVTFile(Path file) {
        try (var fis = new BufferedInputStream(Files.newInputStream(file));
             var dis = new DataInputStream(fis)) {
            int magic = Integer.reverseBytes(dis.readInt());
            return magic == ESVTCompressedSerializer.COMPRESSED_MAGIC;
        } catch (IOException e) {
            return false;
        }
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("ESVTCompressedDeserializer has been closed");
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    /**
     * Result of deserialization including data and optional metadata.
     */
    public record DeserializeResult(
        ESVTData data,
        ESVTMetadata metadata
    ) {
        public boolean hasMetadata() {
            return metadata != null;
        }
    }
}

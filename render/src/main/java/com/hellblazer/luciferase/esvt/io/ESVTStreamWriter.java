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

import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming writer for ESVT tetrahedral data.
 *
 * <p>Allows incremental construction of ESVT files without holding
 * all data in memory. The header is updated with final counts when closed.
 *
 * @author hal.hildebrand
 * @see ESVTStreamReader
 */
public class ESVTStreamWriter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVTStreamWriter.class);

    private final FileChannel channel;
    private final Path outputFile;
    private final int version;
    private final List<Integer> contours;
    private final List<Integer> farPointers;
    private final List<Integer> voxelCoords;

    private int nodeCount;
    private int rootType;
    private int maxDepth;
    private int leafCount;
    private int internalCount;
    private int gridResolution;
    private boolean closed = false;

    /**
     * Create a streaming writer for an ESVT file.
     *
     * @param outputFile Path to the output file
     * @throws IOException if the file cannot be created
     */
    public ESVTStreamWriter(Path outputFile) throws IOException {
        this(outputFile, ESVTFileFormat.CURRENT_VERSION);
    }

    /**
     * Create a streaming writer with a specific version.
     *
     * @param outputFile Path to the output file
     * @param version File format version
     * @throws IOException if the file cannot be created
     */
    public ESVTStreamWriter(Path outputFile, int version) throws IOException {
        this.outputFile = outputFile;
        this.version = version;
        this.contours = new ArrayList<>();
        this.farPointers = new ArrayList<>();
        this.voxelCoords = new ArrayList<>();
        this.nodeCount = 0;

        this.channel = FileChannel.open(outputFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);

        // Write placeholder header
        writeHeader();

        log.debug("ESVTStreamWriter created for {}", outputFile);
    }

    /**
     * Set the root type for the ESVT structure.
     */
    public void setRootType(int rootType) {
        this.rootType = rootType;
    }

    /**
     * Set the maximum depth of the ESVT structure.
     */
    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /**
     * Set the leaf count.
     */
    public void setLeafCount(int leafCount) {
        this.leafCount = leafCount;
    }

    /**
     * Set the internal node count.
     */
    public void setInternalCount(int internalCount) {
        this.internalCount = internalCount;
    }

    /**
     * Set the grid resolution (for voxel-based ESVT).
     */
    public void setGridResolution(int gridResolution) {
        this.gridResolution = gridResolution;
    }

    /**
     * Write a single node.
     *
     * @param node The node to write
     * @throws IOException if writing fails
     */
    public void writeNode(ESVTNodeUnified node) throws IOException {
        ensureNotClosed();

        var nodeBuffer = ByteBuffer.allocate(ESVTNodeUnified.SIZE_BYTES)
                                   .order(ByteOrder.LITTLE_ENDIAN);
        node.writeTo(nodeBuffer);
        nodeBuffer.flip();
        channel.write(nodeBuffer);
        nodeCount++;
    }

    /**
     * Write a batch of nodes.
     *
     * @param nodes List of nodes to write
     * @throws IOException if writing fails
     */
    public void writeNodeBatch(List<ESVTNodeUnified> nodes) throws IOException {
        ensureNotClosed();

        var nodeBuffer = ByteBuffer.allocate(nodes.size() * ESVTNodeUnified.SIZE_BYTES)
                                   .order(ByteOrder.LITTLE_ENDIAN);

        for (var node : nodes) {
            node.writeTo(nodeBuffer);
        }

        nodeBuffer.flip();
        channel.write(nodeBuffer);
        nodeCount += nodes.size();
    }

    /**
     * Write a batch of nodes from an array.
     *
     * @param nodes Array of nodes to write
     * @throws IOException if writing fails
     */
    public void writeNodeBatch(ESVTNodeUnified[] nodes) throws IOException {
        ensureNotClosed();

        var nodeBuffer = ByteBuffer.allocate(nodes.length * ESVTNodeUnified.SIZE_BYTES)
                                   .order(ByteOrder.LITTLE_ENDIAN);

        for (var node : nodes) {
            node.writeTo(nodeBuffer);
        }

        nodeBuffer.flip();
        channel.write(nodeBuffer);
        nodeCount += nodes.length;
    }

    /**
     * Add a contour value (will be written when closed).
     *
     * @param contour The contour value
     */
    public void addContour(int contour) {
        contours.add(contour);
    }

    /**
     * Add contour values (will be written when closed).
     *
     * @param contourArray Array of contour values
     */
    public void addContours(int[] contourArray) {
        for (int c : contourArray) {
            contours.add(c);
        }
    }

    /**
     * Add a far pointer (will be written when closed).
     *
     * @param farPointer The far pointer value
     */
    public void addFarPointer(int farPointer) {
        farPointers.add(farPointer);
    }

    /**
     * Add far pointers (will be written when closed).
     *
     * @param farPointerArray Array of far pointer values
     */
    public void addFarPointers(int[] farPointerArray) {
        for (int ptr : farPointerArray) {
            farPointers.add(ptr);
        }
    }

    /**
     * Add voxel coordinates (will be written when closed).
     *
     * @param coords Array of voxel coordinates
     */
    public void addVoxelCoords(int[] coords) {
        for (int coord : coords) {
            voxelCoords.add(coord);
        }
    }

    /**
     * Get the number of nodes written so far.
     */
    public int getNodeCount() {
        return nodeCount;
    }

    /**
     * Get the number of contours added.
     */
    public int getContourCount() {
        return contours.size();
    }

    /**
     * Get the number of far pointers added.
     */
    public int getFarPointerCount() {
        return farPointers.size();
    }

    private void writeHeader() throws IOException {
        var header = new ESVTFileFormat.Header(version);
        header.nodeCount = nodeCount;
        header.contourCount = contours.size();
        header.farPtrCount = farPointers.size();
        header.rootType = rootType;
        header.maxDepth = maxDepth;

        int headerSize = header.getHeaderSize();
        var headerBuffer = ByteBuffer.allocate(headerSize)
                                     .order(ByteOrder.LITTLE_ENDIAN);

        headerBuffer.putInt(header.magic);
        headerBuffer.putInt(header.version);
        headerBuffer.putInt(header.nodeCount);
        headerBuffer.putInt(header.contourCount);
        headerBuffer.putInt(header.farPtrCount);
        headerBuffer.putInt(header.rootType);
        headerBuffer.putInt(header.maxDepth);
        headerBuffer.putInt(0); // reserved

        if (version >= ESVTFileFormat.VERSION_2) {
            headerBuffer.putLong(0); // metadata offset
            headerBuffer.putLong(0); // metadata size
            headerBuffer.putInt(leafCount);
            headerBuffer.putInt(internalCount);
            headerBuffer.putInt(gridResolution);
            headerBuffer.putInt(voxelCoords.size());
        }

        headerBuffer.flip();
        channel.write(headerBuffer);
    }

    private void writeContours() throws IOException {
        if (contours.isEmpty()) return;

        var buffer = ByteBuffer.allocate(contours.size() * 4)
                               .order(ByteOrder.LITTLE_ENDIAN);
        for (int c : contours) {
            buffer.putInt(c);
        }
        buffer.flip();
        channel.write(buffer);
    }

    private void writeFarPointers() throws IOException {
        if (farPointers.isEmpty()) return;

        var buffer = ByteBuffer.allocate(farPointers.size() * 4)
                               .order(ByteOrder.LITTLE_ENDIAN);
        for (int ptr : farPointers) {
            buffer.putInt(ptr);
        }
        buffer.flip();
        channel.write(buffer);
    }

    private void writeVoxelCoords() throws IOException {
        if (voxelCoords.isEmpty()) return;

        var buffer = ByteBuffer.allocate(voxelCoords.size() * 4)
                               .order(ByteOrder.LITTLE_ENDIAN);
        for (int coord : voxelCoords) {
            buffer.putInt(coord);
        }
        buffer.flip();
        channel.write(buffer);
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("ESVTStreamWriter has been closed");
        }
    }

    /**
     * Close the writer, writing remaining data and updating the header.
     */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;

            // Write contours, far pointers, and voxel coords
            writeContours();
            writeFarPointers();
            if (version >= ESVTFileFormat.VERSION_2) {
                writeVoxelCoords();
            }

            // Update header with final counts
            long currentPos = channel.position();
            channel.position(0);
            writeHeader();
            channel.position(currentPos);

            channel.close();

            log.info("ESVTStreamWriter closed {}: {} nodes, {} contours, {} farPtrs",
                    outputFile, nodeCount, contours.size(), farPointers.size());
        }
    }
}

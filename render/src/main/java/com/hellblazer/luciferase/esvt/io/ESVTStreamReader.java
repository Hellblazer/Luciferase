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
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Streaming reader for ESVT tetrahedral data.
 *
 * <p>Provides an iterator-style interface for reading large ESVT files
 * without loading all nodes into memory at once.
 *
 * @author hal.hildebrand
 * @see ESVTStreamWriter
 */
public class ESVTStreamReader implements AutoCloseable, Iterable<ESVTNodeUnified> {
    private static final Logger log = LoggerFactory.getLogger(ESVTStreamReader.class);

    private final FileChannel channel;
    private final ESVTFileFormat.Header header;
    private int currentNode;
    private boolean closed = false;

    /**
     * Create a streaming reader for an ESVT file.
     *
     * @param inputFile Path to the ESVT file
     * @throws IOException if the file cannot be opened or has invalid format
     */
    public ESVTStreamReader(Path inputFile) throws IOException {
        this.channel = FileChannel.open(inputFile, StandardOpenOption.READ);

        // Read header
        var headerBuffer = ByteBuffer.allocate(ESVTFileFormat.HEADER_SIZE_V2)
                                     .order(ByteOrder.LITTLE_ENDIAN);
        channel.read(headerBuffer);
        headerBuffer.flip();

        int magic = headerBuffer.getInt();
        if (magic != ESVTFileFormat.MAGIC_NUMBER) {
            channel.close();
            throw new IOException("Invalid ESVT file: bad magic number 0x" +
                Integer.toHexString(magic));
        }

        int version = headerBuffer.getInt();
        this.header = new ESVTFileFormat.Header(version);
        header.magic = magic;
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

        this.currentNode = 0;

        log.debug("ESVTStreamReader opened {}: {} nodes", inputFile, header.nodeCount);
    }

    /**
     * Check if there are more nodes to read.
     */
    public boolean hasNext() {
        return currentNode < header.nodeCount;
    }

    /**
     * Read the next node.
     *
     * @return The next node, or null if no more nodes
     * @throws IOException if reading fails
     */
    public ESVTNodeUnified readNext() throws IOException {
        ensureNotClosed();

        if (!hasNext()) {
            return null;
        }

        var nodeBuffer = ByteBuffer.allocate(ESVTNodeUnified.SIZE_BYTES)
                                   .order(ByteOrder.LITTLE_ENDIAN);
        channel.read(nodeBuffer);
        nodeBuffer.flip();

        currentNode++;
        return ESVTNodeUnified.fromByteBuffer(nodeBuffer);
    }

    /**
     * Read a batch of nodes.
     *
     * @param count Maximum number of nodes to read
     * @return Array of nodes read (may be smaller than count if EOF reached)
     * @throws IOException if reading fails
     */
    public ESVTNodeUnified[] readBatch(int count) throws IOException {
        ensureNotClosed();

        int remaining = header.nodeCount - currentNode;
        int toRead = Math.min(count, remaining);

        if (toRead <= 0) {
            return new ESVTNodeUnified[0];
        }

        var buffer = ByteBuffer.allocate(toRead * ESVTNodeUnified.SIZE_BYTES)
                               .order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buffer);
        buffer.flip();

        var nodes = new ESVTNodeUnified[toRead];
        for (int i = 0; i < toRead; i++) {
            nodes[i] = ESVTNodeUnified.fromByteBuffer(buffer);
        }

        currentNode += toRead;
        return nodes;
    }

    /**
     * Skip a number of nodes.
     *
     * @param count Number of nodes to skip
     * @return Actual number of nodes skipped
     * @throws IOException if seeking fails
     */
    public int skip(int count) throws IOException {
        ensureNotClosed();

        int remaining = header.nodeCount - currentNode;
        int toSkip = Math.min(count, remaining);

        if (toSkip > 0) {
            channel.position(channel.position() + (long) toSkip * ESVTNodeUnified.SIZE_BYTES);
            currentNode += toSkip;
        }

        return toSkip;
    }

    /**
     * Get the file header information.
     */
    public ESVTFileFormat.Header getHeader() {
        return header;
    }

    /**
     * Get the total number of nodes in the file.
     */
    public int getTotalNodes() {
        return header.nodeCount;
    }

    /**
     * Get the number of nodes read so far.
     */
    public int getNodesRead() {
        return currentNode;
    }

    /**
     * Get the number of nodes remaining to read.
     */
    public int getNodesRemaining() {
        return header.nodeCount - currentNode;
    }

    /**
     * Reset to the beginning of the node data.
     *
     * @throws IOException if seeking fails
     */
    public void reset() throws IOException {
        ensureNotClosed();
        channel.position(header.getNodesOffset());
        currentNode = 0;
    }

    @Override
    public Iterator<ESVTNodeUnified> iterator() {
        return new NodeIterator();
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("ESVTStreamReader has been closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            channel.close();
            log.debug("ESVTStreamReader closed after reading {} nodes", currentNode);
        }
    }

    private class NodeIterator implements Iterator<ESVTNodeUnified> {
        @Override
        public boolean hasNext() {
            return ESVTStreamReader.this.hasNext();
        }

        @Override
        public ESVTNodeUnified next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            try {
                return readNext();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read node", e);
            }
        }
    }
}

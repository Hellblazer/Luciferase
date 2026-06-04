package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.resource.UnifiedResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serializer for ESVO octree data with resource management.
 *
 * <p>Writes VERSION_3 format by default: sparse (index, node) pairs + farPointers section.
 * Legacy VERSION_1/VERSION_2 files can still be read by {@link ESVODeserializer}.
 */
public class ESVOSerializer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ESVOSerializer.class);

    private final int version;
    private final UnifiedResourceManager resourceManager;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private final List<ByteBuffer> allocatedBuffers = new ArrayList<>();

    public ESVOSerializer() {
        this(ESVOFileFormat.VERSION_3);
    }

    public ESVOSerializer(int version) {
        this.version = version;
        this.resourceManager = UnifiedResourceManager.getInstance();
        log.debug("ESVOSerializer created with version {}", version);
    }

    /**
     * Serialize octree data to file using the configured format version.
     */
    public void serialize(ESVOOctreeData octree, Path outputFile) throws IOException {
        ensureNotClosed();

        try (FileChannel channel = FileChannel.open(outputFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            int[] indices = octree.getNodeIndices();
            int[] farPointers = octree.getFarPointers();

            ESVOFileFormat.Header header = new ESVOFileFormat.Header();
            header.version = version;
            header.nodeCount = indices.length;            // true sparse count
            header.farPtrCount = farPointers.length;

            ByteBuffer headerBuffer = allocateBuffer(header.getHeaderSize(), "header");
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            writeHeader(headerBuffer, header);
            headerBuffer.flip();
            totalBytesWritten.addAndGet(channel.write(headerBuffer));

            writeNodesV3(channel, octree, indices);
            writeFarPointers(channel, farPointers);

            log.info("Serialized {} nodes ({} far-ptrs) to {}, {} bytes",
                    indices.length, farPointers.length, outputFile, totalBytesWritten.get());
        }
    }

    /**
     * Serialize with metadata appended after node and far-pointer sections.
     */
    public void serializeWithMetadata(ESVOOctreeData octree, ESVOMetadata metadata, Path outputFile)
            throws IOException {
        ensureNotClosed();

        try (FileChannel channel = FileChannel.open(outputFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            int[] indices = octree.getNodeIndices();
            int[] farPointers = octree.getFarPointers();

            ESVOFileFormat.Header header = new ESVOFileFormat.Header();
            header.version = ESVOFileFormat.VERSION_3;
            header.nodeCount = indices.length;
            header.farPtrCount = farPointers.length;

            // Write placeholder header (metadataOffset unknown yet)
            ByteBuffer headerBuffer = allocateBuffer(header.getHeaderSize(), "header");
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            writeHeader(headerBuffer, header);
            headerBuffer.flip();
            totalBytesWritten.addAndGet(channel.write(headerBuffer));

            writeNodesV3(channel, octree, indices);
            writeFarPointers(channel, farPointers);

            // Serialize metadata
            long metadataOffset = channel.position();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(metadata);
            }
            byte[] metadataBytes = baos.toByteArray();
            channel.write(ByteBuffer.wrap(metadataBytes));

            // Patch header with metadata location
            header.metadataOffset = metadataOffset;
            header.metadataSize = metadataBytes.length;
            headerBuffer.clear();
            writeHeader(headerBuffer, header);
            headerBuffer.flip();
            channel.position(0);
            channel.write(headerBuffer);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void writeHeader(ByteBuffer buffer, ESVOFileFormat.Header header) {
        buffer.putInt(header.magic);
        buffer.putInt(header.version);
        buffer.putInt(header.nodeCount);
        buffer.putInt(header.reserved);

        if (header.version >= ESVOFileFormat.VERSION_2) {
            buffer.putLong(header.metadataOffset);
            buffer.putLong(header.metadataSize);
        }
        if (header.version >= ESVOFileFormat.VERSION_3) {
            buffer.putInt(header.farPtrCount);
            buffer.putInt(header.reserved2);
        }
    }

    /**
     * Write sparse (index, childDescriptor, contourDescriptor) triples — 12 bytes each.
     * Only actually-present nodes are written; no zero-padding for gaps.
     */
    private void writeNodesV3(FileChannel channel, ESVOOctreeData octree, int[] indices)
            throws IOException {
        if (indices.length == 0) {
            return;
        }
        ByteBuffer buf = allocateBuffer(indices.length * 12, "nodes");
        buf.order(ByteOrder.LITTLE_ENDIAN);
        for (int idx : indices) {
            ESVONodeUnified node = octree.getNode(idx);
            buf.putInt(idx);
            buf.putInt(node.getChildDescriptor());
            buf.putInt(node.getContourDescriptor());
        }
        buf.flip();
        totalBytesWritten.addAndGet(channel.write(buf));
    }

    /**
     * Write the far-pointer array (4 bytes per entry).
     */
    private void writeFarPointers(FileChannel channel, int[] farPointers) throws IOException {
        if (farPointers.length == 0) {
            return;
        }
        ByteBuffer buf = allocateBuffer(farPointers.length * 4, "farPointers");
        buf.order(ByteOrder.LITTLE_ENDIAN);
        for (int fp : farPointers) {
            buf.putInt(fp);
        }
        buf.flip();
        totalBytesWritten.addAndGet(channel.write(buf));
    }

    private ByteBuffer allocateBuffer(int size, String name) {
        ByteBuffer buffer = resourceManager.allocateMemory(size);
        allocatedBuffers.add(buffer);
        log.trace("Allocated {} buffer of {} bytes", name, size);
        return buffer;
    }

    public long getTotalBytesWritten() {
        return totalBytesWritten.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.debug("Closing ESVOSerializer, releasing {} buffers", allocatedBuffers.size());
            for (ByteBuffer buffer : allocatedBuffers) {
                try {
                    resourceManager.releaseMemory(buffer);
                } catch (Exception e) {
                    log.error("Error releasing buffer", e);
                }
            }
            allocatedBuffers.clear();
            log.info("ESVOSerializer closed. Total bytes written: {}", totalBytesWritten.get());
        }
    }

    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("ESVOSerializer has been closed");
        }
    }
}
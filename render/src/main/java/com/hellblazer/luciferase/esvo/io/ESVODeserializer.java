package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Deserializer for ESVO octree data.
 *
 * <p>Reads VERSION_3 (sparse pairs + farPointers) natively, and provides
 * backward-compatible reads for VERSION_1 and VERSION_2 dense-array files.
 *
 * <p>Security guards applied before any allocation:
 * <ul>
 *   <li>nodeCount validated: non-negative, {@code nodeCount * 12L <= channel.size()},
 *       and {@code <= MAX_SAFE_NODE_COUNT}</li>
 *   <li>farPtrCount validated similarly</li>
 *   <li>node/far-pointer reads use a fill loop; premature EOF throws {@link EOFException}</li>
 * </ul>
 */
public class ESVODeserializer {

    /** Result containing both octree and optional metadata. */
    public static class Result {
        public final ESVOOctreeData octree;
        public final ESVOMetadata metadata;

        public Result(ESVOOctreeData octree, ESVOMetadata metadata) {
            this.octree = octree;
            this.metadata = metadata;
        }
    }

    /** Deserialize octree data from file. */
    public ESVOOctreeData deserialize(Path inputFile) throws IOException {
        try (FileChannel channel = FileChannel.open(inputFile, StandardOpenOption.READ)) {
            ESVOFileFormat.Header header = readHeader(channel);
            ESVOOctreeData octree = new ESVOOctreeData(Math.max(header.nodeCount * 8 + 1024, 100000));
            readNodes(channel, octree, header);
            return octree;
        }
    }

    /** Deserialize with optional metadata. */
    public Result deserializeWithMetadata(Path inputFile) throws IOException {
        try (FileChannel channel = FileChannel.open(inputFile, StandardOpenOption.READ)) {
            ESVOFileFormat.Header header = readHeader(channel);
            ESVOOctreeData octree = new ESVOOctreeData(Math.max(header.nodeCount * 8 + 1024, 100000));
            readNodes(channel, octree, header);

            ESVOMetadata metadata = null;
            if (header.version >= ESVOFileFormat.VERSION_2 && header.metadataOffset > 0) {
                metadata = readMetadata(channel, header);
            }
            return new Result(octree, metadata);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ESVOFileFormat.Header readHeader(FileChannel channel) throws IOException {
        long fileSize = channel.size();
        ESVOFileFormat.Header header = new ESVOFileFormat.Header();

        // Read V1 base (16 bytes)
        ByteBuffer buf = ByteBuffer.allocate(ESVOFileFormat.HEADER_SIZE_V1);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        fillBuffer(channel, buf);

        header.magic = buf.getInt();
        if (header.magic != ESVOFileFormat.MAGIC_NUMBER) {
            throw new IOException("Invalid ESVO file: bad magic number 0x" + Integer.toHexString(header.magic));
        }
        header.version = buf.getInt();
        header.nodeCount = buf.getInt();
        header.reserved = buf.getInt();

        if (header.version >= ESVOFileFormat.VERSION_2) {
            ByteBuffer ext = ByteBuffer.allocate(16);
            ext.order(ByteOrder.LITTLE_ENDIAN);
            fillBuffer(channel, ext);
            header.metadataOffset = ext.getLong();
            header.metadataSize = ext.getLong();
        }
        if (header.version >= ESVOFileFormat.VERSION_3) {
            ByteBuffer ext = ByteBuffer.allocate(8);
            ext.order(ByteOrder.LITTLE_ENDIAN);
            fillBuffer(channel, ext);
            header.farPtrCount = ext.getInt();
            header.reserved2 = ext.getInt();
        }

        // Validate nodeCount before any allocation
        validateCount("nodeCount", header.nodeCount, 12, fileSize,
                ESVOFileFormat.MAX_SAFE_NODE_COUNT);

        if (header.version >= ESVOFileFormat.VERSION_3) {
            validateCount("farPtrCount", header.farPtrCount, 4, fileSize,
                    ESVOFileFormat.MAX_SAFE_FAR_PTR_COUNT);
            // Combined data-section bound: node + far-pointer sections together must
            // fit within the file. Each per-field check above compares against the
            // whole file size, which would individually pass for a header that
            // over-claims one section at the expense of another; this catches that.
            long dataBytes = (long) header.nodeCount * 12 + (long) header.farPtrCount * 4;
            if (dataBytes > fileSize) {
                throw new IOException(
                        "Corrupt ESVO file: node+farPointer sections imply " + dataBytes
                        + " bytes but file is only " + fileSize + " bytes");
            }
        }

        return header;
    }

    /**
     * Validate that {@code count} is non-negative, {@code count * bytesEach} fits in
     * a long without overflow, is within the file size, and does not exceed
     * {@code maxSafe}.
     */
    private static void validateCount(String field, int count, int bytesEach,
                                       long fileSize, int maxSafe) throws IOException {
        if (count < 0) {
            throw new IOException("Corrupt ESVO file: " + field + " is negative (" + count + ")");
        }
        if (count > maxSafe) {
            throw new IOException(
                    "Corrupt ESVO file: " + field + " " + count + " exceeds safety cap " + maxSafe);
        }
        long required = (long) count * bytesEach;
        if (required > fileSize) {
            throw new IOException(
                    "Corrupt ESVO file: " + field + " " + count + " implies " + required
                    + " bytes but file is only " + fileSize + " bytes");
        }
    }

    private void readNodes(FileChannel channel, ESVOOctreeData octree,
                           ESVOFileFormat.Header header) throws IOException {
        if (header.version >= ESVOFileFormat.VERSION_3) {
            readNodesV3(channel, octree, header);
        } else {
            readNodesLegacy(channel, octree, header.nodeCount);
        }
    }

    /** VERSION_3: read sparse (index, childDescriptor, contourDescriptor) triples. */
    private void readNodesV3(FileChannel channel, ESVOOctreeData octree,
                             ESVOFileFormat.Header header) throws IOException {
        int nodeCount = header.nodeCount;
        if (nodeCount > 0) {
            ByteBuffer buf = ByteBuffer.allocate(nodeCount * 12);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            fillBuffer(channel, buf);
            for (int i = 0; i < nodeCount; i++) {
                int index = buf.getInt();
                int childDesc = buf.getInt();
                int contourDesc = buf.getInt();
                octree.setNode(index, new ESVONodeUnified(childDesc, contourDesc));
            }
        }

        // Far pointers
        int fpCount = header.farPtrCount;
        if (fpCount > 0) {
            ByteBuffer fpBuf = ByteBuffer.allocate(fpCount * 4);
            fpBuf.order(ByteOrder.LITTLE_ENDIAN);
            fillBuffer(channel, fpBuf);
            int[] fps = new int[fpCount];
            for (int i = 0; i < fpCount; i++) {
                fps[i] = fpBuf.getInt();
            }
            octree.setFarPointers(fps);
        }
    }

    /**
     * VERSION_1/VERSION_2 backward-compat: dense array of
     * (childDescriptor, contourDescriptor, padding) triples.
     * Absent nodes (all-zero) are skipped so they are not materialised
     * as present in the sparse map.
     */
    private void readNodesLegacy(FileChannel channel, ESVOOctreeData octree,
                                  int nodeCount) throws IOException {
        if (nodeCount == 0) {
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(nodeCount * 12);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        fillBuffer(channel, buf);
        for (int i = 0; i < nodeCount; i++) {
            int childDesc = buf.getInt();
            int contourDesc = buf.getInt();
            buf.getInt(); // padding
            // Nodes with childDescriptor==0 AND contourDescriptor==0 are treated as
            // absent gaps in the legacy dense layout (not materialised in the sparse
            // map). A meaningful legacy node must have at least one non-zero descriptor;
            // a default-constructed valid-flag=0 node is indistinguishable from a gap in
            // V1/V2 — this ambiguity is exactly why VERSION_3 stores explicit indices.
            if (childDesc != 0 || contourDesc != 0) {
                octree.setNode(i, new ESVONodeUnified(childDesc, contourDesc));
            }
        }
    }

    private ESVOMetadata readMetadata(FileChannel channel, ESVOFileFormat.Header header)
            throws IOException {
        channel.position(header.metadataOffset);
        ByteBuffer buf = ByteBuffer.allocate((int) header.metadataSize);
        fillBuffer(channel, buf);
        ByteArrayInputStream bais = new ByteArrayInputStream(buf.array());
        try (ObjectInputStream ois = new ObjectInputStream(bais)) {
            // Restrict deserialization to the metadata class plus the JDK
            // collection / primitive wrappers it may transitively pull in.
            // Untrusted .esvo files would otherwise be a remote-code-
            // execution vector via standard ObjectInputStream gadget chains.
            ois.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "com.hellblazer.luciferase.esvo.io.ESVOMetadata;"
                    + "java.util.*;java.lang.*;java.time.*;java.math.*;"
                    + "javax.vecmath.*;!*"));
            return (ESVOMetadata) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Failed to deserialize metadata", e);
        }
    }

    /**
     * Fill {@code buf} to capacity from {@code channel}, looping on short reads.
     * Throws {@link EOFException} if the channel is exhausted before the buffer
     * is full.
     */
    static void fillBuffer(FileChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = channel.read(buf);
            if (n < 0) {
                throw new EOFException(
                        "Unexpected end of ESVO file (need " + buf.remaining() + " more bytes)");
            }
        }
        buf.flip();
    }
}

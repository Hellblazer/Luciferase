package com.hellblazer.luciferase.simulation.viz.render;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvt.core.ESVTData;
import com.hellblazer.luciferase.esvt.core.ESVTNodeUnified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Stateless utility class for serializing and compressing ESVO/ESVT render structures.
 *
 * <p>Provides GZIP compression with smart threshold (A3) and version-tagged binary serialization
 * for both ESVO octree and ESVT tetrahedral structures.
 *
 * <p><strong>Compression Threshold (A3):</strong> Raw data less than 200 bytes is not compressed
 * to avoid GZIP header expansion overhead.
 *
 * <p><strong>ESVO Format:</strong>
 * <pre>
 * [Version: 1 byte] [NodeCount: 4 bytes] [MaxDepth: 4 bytes] [LeafCount: 4 bytes]
 * [InternalCount: 4 bytes] [FarPointerCount: 4 bytes]
 * [Nodes: NodeCount * 8 bytes] [FarPointers: FarPointerCount * 4 bytes]
 * </pre>
 *
 * <p><strong>ESVT Format:</strong>
 * <pre>
 * [Version: 1 byte] [NodeCount: 4 bytes] [RootType: 4 bytes] [MaxDepth: 4 bytes]
 * [LeafCount: 4 bytes] [InternalCount: 4 bytes] [GridResolution: 4 bytes]
 * [ContourCount: 4 bytes] [FarPointerCount: 4 bytes]
 * [Nodes: NodeCount * 8 bytes] [Contours: ContourCount * 4 bytes]
 * [FarPointers: FarPointerCount * 4 bytes]
 * </pre>
 */
public final class SerializationUtils {
    private static final Logger log = LoggerFactory.getLogger(SerializationUtils.class);

    /** ESVO serialization format version */
    private static final byte ESVO_VERSION = 1;

    /** ESVT serialization format version */
    private static final byte ESVT_VERSION = 1;

    /** GZIP magic number (first 2 bytes) */
    private static final byte GZIP_MAGIC_1 = (byte) 0x1F;
    private static final byte GZIP_MAGIC_2 = (byte) 0x8B;

    /** Compression threshold: skip GZIP for data < 200 bytes (A3) */
    private static final int COMPRESSION_THRESHOLD = 200;

    private SerializationUtils() {
        // Utility class - no instances
    }

    // ===== GZIP Compression =====

    /**
     * Compress data using GZIP with smart threshold (A3).
     * Data less than {@value #COMPRESSION_THRESHOLD} bytes is returned uncompressed.
     *
     * @param data Raw data to compress
     * @return Compressed data, or original data if below threshold
     * @throws IOException if compression fails
     */
    public static byte[] compress(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return data;
        }

        // A3: Skip compression for small data to avoid header expansion
        if (data.length < COMPRESSION_THRESHOLD) {
            log.debug("Skipping compression for {} bytes (below {} byte threshold)",
                    data.length, COMPRESSION_THRESHOLD);
            return data;
        }

        var baos = new ByteArrayOutputStream(data.length / 2); // Estimate 50% compression
        try (var gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(data);
        }

        byte[] compressed = baos.toByteArray();
        log.debug("Compressed {} bytes → {} bytes ({} ratio)",
                data.length, compressed.length,
                String.format("%.2f", (double) compressed.length / data.length));

        return compressed;
    }

    /**
     * Decompress GZIP data with automatic detection.
     * If data is not GZIP-compressed (no magic number), returns original data.
     *
     * @param data Potentially compressed data
     * @return Decompressed data
     * @throws IOException if decompression fails
     */
    public static byte[] decompress(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return data;
        }

        // Check GZIP magic number
        if (!isCompressed(data)) {
            log.debug("Data is not GZIP compressed ({} bytes), returning as-is", data.length);
            return data;
        }

        var bais = new ByteArrayInputStream(data);
        var baos = new ByteArrayOutputStream(data.length * 2); // Estimate 2x expansion
        try (var gzipIn = new GZIPInputStream(bais)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = gzipIn.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
        }

        byte[] decompressed = baos.toByteArray();
        log.debug("Decompressed {} bytes → {} bytes",
                data.length, decompressed.length);

        return decompressed;
    }

    /**
     * Check if data is GZIP-compressed by examining magic number (0x1F 0x8B).
     *
     * @param data Data to check
     * @return true if data starts with GZIP magic number
     */
    public static boolean isCompressed(byte[] data) {
        return data != null && data.length >= 2 &&
                data[0] == GZIP_MAGIC_1 && data[1] == GZIP_MAGIC_2;
    }

    // ===== ESVO Serialization =====

    /**
     * Serialize ESVOOctreeData to binary format.
     *
     * @param octreeData ESVO octree data
     * @return Serialized bytes
     */
    public static byte[] serializeESVO(ESVOOctreeData octreeData) {
        var nodeIndices = octreeData.getNodeIndices();
        int nodeCount = nodeIndices.length;
        int farPointerCount = octreeData.getFarPointers().length;

        // Calculate total size
        int headerSize = 1 + 4 + 4 + 4 + 4 + 4; // version + nodeCount + maxDepth + leafCount + internalCount + farPointerCount
        int nodeDataSize = nodeCount * ESVONodeUnified.SIZE_BYTES;
        int farPointerSize = farPointerCount * 4;
        int totalSize = headerSize + nodeDataSize + farPointerSize;

        var buffer = ByteBuffer.allocate(totalSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Write header
        buffer.put(ESVO_VERSION);
        buffer.putInt(nodeCount);
        buffer.putInt(octreeData.maxDepth());
        buffer.putInt(octreeData.leafCount());
        buffer.putInt(octreeData.internalCount());
        buffer.putInt(farPointerCount);

        // Write nodes in index order
        for (int idx : nodeIndices) {
            var node = octreeData.getNode(idx);
            if (node != null) {
                node.writeTo(buffer);
            } else {
                // Write empty node
                new ESVONodeUnified().writeTo(buffer);
            }
        }

        // Write far pointers
        for (int farPtr : octreeData.getFarPointers()) {
            buffer.putInt(farPtr);
        }

        log.debug("Serialized ESVO: {} nodes, {} far pointers, {} bytes",
                nodeCount, farPointerCount, totalSize);

        return buffer.array();
    }

    /**
     * Deserialize ESVOOctreeData from binary format.
     *
     * @param data Serialized bytes
     * @return ESVOOctreeData instance
     * @throws IllegalArgumentException if version is unsupported
     */
    public static ESVOOctreeData deserializeESVO(byte[] data) {
        var buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Read header
        byte version = buffer.get();
        if (version != ESVO_VERSION) {
            throw new IllegalArgumentException("Unsupported ESVO version: " + version);
        }

        int nodeCount = buffer.getInt();
        int maxDepth = buffer.getInt();
        int leafCount = buffer.getInt();
        int internalCount = buffer.getInt();
        int farPointerCount = buffer.getInt();

        // Create octree data container
        var octreeData = new ESVOOctreeData(nodeCount * ESVONodeUnified.SIZE_BYTES + farPointerCount * 4);
        octreeData.setMaxDepth(maxDepth);
        octreeData.setLeafCount(leafCount);
        octreeData.setInternalCount(internalCount);

        // Read nodes
        for (int i = 0; i < nodeCount; i++) {
            var node = ESVONodeUnified.fromByteBuffer(buffer);
            octreeData.setNode(i, node);
        }

        // Read far pointers
        if (farPointerCount > 0) {
            int[] farPointers = new int[farPointerCount];
            for (int i = 0; i < farPointerCount; i++) {
                farPointers[i] = buffer.getInt();
            }
            octreeData.setFarPointers(farPointers);
        }

        log.debug("Deserialized ESVO: {} nodes, {} far pointers",
                nodeCount, farPointerCount);

        return octreeData;
    }

    // ===== ESVT Serialization =====

    /**
     * Serialize ESVTData to binary format.
     *
     * @param esvtData ESVT tetrahedral data
     * @return Serialized bytes
     */
    public static byte[] serializeESVT(ESVTData esvtData) {
        int nodeCount = esvtData.nodes().length;
        int contourCount = esvtData.contours().length;
        int farPointerCount = esvtData.farPointers().length;

        // Calculate total size
        int headerSize = 1 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4; // version + nodeCount + rootType + maxDepth + leafCount + internalCount + gridRes + contourCount + farPointerCount
        int nodeDataSize = nodeCount * ESVTNodeUnified.SIZE_BYTES;
        int contourDataSize = contourCount * 4;
        int farPointerSize = farPointerCount * 4;
        int totalSize = headerSize + nodeDataSize + contourDataSize + farPointerSize;

        var buffer = ByteBuffer.allocate(totalSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Write header
        buffer.put(ESVT_VERSION);
        buffer.putInt(nodeCount);
        buffer.putInt(esvtData.rootType());
        buffer.putInt(esvtData.maxDepth());
        buffer.putInt(esvtData.leafCount());
        buffer.putInt(esvtData.internalCount());
        buffer.putInt(esvtData.gridResolution());
        buffer.putInt(contourCount);
        buffer.putInt(farPointerCount);

        // Write nodes
        for (var node : esvtData.nodes()) {
            node.writeTo(buffer);
        }

        // Write contours
        for (int contour : esvtData.contours()) {
            buffer.putInt(contour);
        }

        // Write far pointers
        for (int farPtr : esvtData.farPointers()) {
            buffer.putInt(farPtr);
        }

        log.debug("Serialized ESVT: {} nodes, {} contours, {} far pointers, {} bytes",
                nodeCount, contourCount, farPointerCount, totalSize);

        return buffer.array();
    }

    /**
     * Deserialize ESVTData from binary format.
     *
     * @param data Serialized bytes
     * @return ESVTData instance
     * @throws IllegalArgumentException if version is unsupported
     */
    public static ESVTData deserializeESVT(byte[] data) {
        var buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // Read header
        byte version = buffer.get();
        if (version != ESVT_VERSION) {
            throw new IllegalArgumentException("Unsupported ESVT version: " + version);
        }

        int nodeCount = buffer.getInt();
        int rootType = buffer.getInt();
        int maxDepth = buffer.getInt();
        int leafCount = buffer.getInt();
        int internalCount = buffer.getInt();
        int gridResolution = buffer.getInt();
        int contourCount = buffer.getInt();
        int farPointerCount = buffer.getInt();

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
        var farPointers = new int[farPointerCount];
        for (int i = 0; i < farPointerCount; i++) {
            farPointers[i] = buffer.getInt();
        }

        log.debug("Deserialized ESVT: {} nodes, {} contours, {} far pointers",
                nodeCount, contourCount, farPointerCount);

        return new ESVTData(nodes, contours, farPointers, rootType,
                maxDepth, leafCount, internalCount, gridResolution, new int[0]);
    }
}

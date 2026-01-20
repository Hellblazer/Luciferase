package com.hellblazer.luciferase.esvo.dag.io;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.dag.types.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Deserializer for DAG octree data from .dag file format.
 *
 * <p>Validates file format and reconstructs DAG octree data structures.
 * Supports version 1 of the .dag format.
 */
public class DAGDeserializer {
    private static final int MAGIC_NUMBER = 0x44414721;

    /**
     * Deserialize a DAG octree from a .dag file.
     *
     * @param file the input file path
     * @return the reconstructed DAG octree data
     * @throws IOException if I/O error occurs
     * @throws DAGFormatException if file format validation fails
     */
    public static DAGOctreeData deserialize(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            // Read header
            var headerBuffer = ByteBuffer.allocate(32)
                .order(ByteOrder.LITTLE_ENDIAN);
            int headerRead = channel.read(headerBuffer);
            if (headerRead != 32) {
                throw new DAGFormatException("File too small for header (got " + headerRead + " bytes, expected 32)");
            }
            headerBuffer.flip();

            int magic = headerBuffer.getInt();
            if (magic != MAGIC_NUMBER) {
                throw new DAGFormatException("Invalid magic number: 0x" + Integer.toHexString(magic));
            }

            byte version = headerBuffer.get();
            if (version != 1) {
                throw new DAGFormatException("Unsupported DAG format version: " + version);
            }

            byte hashAlgoOrd = headerBuffer.get();
            byte strategyOrd = headerBuffer.get();
            headerBuffer.get(); // skip reserved

            int nodeCount = headerBuffer.getInt();
            int originalNodeCount = headerBuffer.getInt();
            short maxDepth = headerBuffer.getShort();
            short sharedSubtreeCount = headerBuffer.getShort();
            int buildTimeMs = headerBuffer.getInt();
            long sourceHash = headerBuffer.getLong();

            // Validate node count
            if (nodeCount <= 0) {
                throw new DAGFormatException("Invalid node count: " + nodeCount);
            }
            if (nodeCount > 100_000_000) {
                throw new DAGFormatException("Node count too large (possible corruption): " + nodeCount);
            }

            // Read nodes
            var nodes = new ESVONodeUnified[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                var nodeBuffer = ByteBuffer.allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN);
                int read = channel.read(nodeBuffer);
                if (read != 8) {
                    throw new DAGFormatException("Incomplete node data at index " + i);
                }
                nodeBuffer.flip();
                int childDescriptor = nodeBuffer.getInt();
                int contourDescriptor = nodeBuffer.getInt();
                nodes[i] = new ESVONodeUnified(childDescriptor, contourDescriptor);
            }

            // Read metadata JSON (length prefix)
            var jsonLengthBuffer = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN);
            int lengthRead = channel.read(jsonLengthBuffer);
            if (lengthRead != 4) {
                throw new DAGFormatException("Missing JSON metadata length");
            }
            jsonLengthBuffer.flip();
            int jsonLength = jsonLengthBuffer.getInt();

            if (jsonLength < 0 || jsonLength > 1_000_000) {
                throw new DAGFormatException("Invalid JSON length: " + jsonLength);
            }

            var jsonBuffer = ByteBuffer.allocate(jsonLength);
            int jsonRead = channel.read(jsonBuffer);
            if (jsonRead != jsonLength) {
                throw new DAGFormatException("Incomplete JSON metadata");
            }
            String json = new String(jsonBuffer.array(), StandardCharsets.UTF_8);

            // Reconstruct metadata
            var hashAlgorithm = HashAlgorithm.fromOrdinal(hashAlgoOrd);
            var strategy = CompressionStrategy.fromOrdinal(strategyOrd);

            // Parse JSON to extract additional metadata fields
            long memorySavedBytes = parseMemorySaved(json);
            float compressionRatio = parseCompressionRatio(json);

            var metadata = new DAGMetadata(
                nodeCount,
                originalNodeCount,
                compressionRatio,
                memorySavedBytes,
                Duration.ofMillis(buildTimeMs),
                sharedSubtreeCount,
                hashAlgorithm,
                strategy,
                sourceHash
            );

            return new DAGOctreeData(nodes, metadata, maxDepth);
        } catch (IOException e) {
            throw new DAGFormatException("Failed to deserialize DAG file: " + e.getMessage(), e);
        }
    }

    /**
     * Parse memorySavedBytes from JSON metadata.
     */
    private static long parseMemorySaved(String json) {
        // Simple JSON parsing (production would use proper JSON library)
        var match = json.indexOf("\"memorySavedBytes\":");
        if (match == -1) {
            return 0;
        }
        var start = match + "\"memorySavedBytes\":".length();
        var end = json.indexOf(',', start);
        if (end == -1) {
            end = json.indexOf('}', start);
        }
        return Long.parseLong(json.substring(start, end).trim());
    }

    /**
     * Parse compressionRatio from JSON metadata.
     */
    private static float parseCompressionRatio(String json) {
        var match = json.indexOf("\"compressionRatio\":");
        if (match == -1) {
            return 1.0f;
        }
        var start = match + "\"compressionRatio\":".length();
        var end = json.indexOf(',', start);
        if (end == -1) {
            end = json.indexOf('}', start);
        }
        return Float.parseFloat(json.substring(start, end).trim());
    }
}

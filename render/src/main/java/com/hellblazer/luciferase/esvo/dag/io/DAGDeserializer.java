package com.hellblazer.luciferase.esvo.dag.io;

import com.hellblazer.luciferase.esvo.core.ESVONodeUnified;
import com.hellblazer.luciferase.esvo.dag.DAGMetadata;
import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;
import com.hellblazer.luciferase.esvo.dag.CompressionStrategy;
import com.hellblazer.luciferase.esvo.dag.HashAlgorithm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import com.hellblazer.luciferase.sparse.core.CoordinateSpace;

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

            // Reconstruct metadata from header values
            var hashAlgorithm = HashAlgorithm.values()[hashAlgoOrd];
            var strategy = CompressionStrategy.values()[strategyOrd];

            var metadata = new DAGMetadata(
                nodeCount,
                originalNodeCount,
                maxDepth,
                sharedSubtreeCount,
                Map.of(),  // sharingByDepth - empty for deserialized DAGs
                Duration.ofMillis(buildTimeMs),
                hashAlgorithm,
                strategy,
                sourceHash
            );

            // Create a concrete implementation wrapper for DAGOctreeData interface
            return new DAGOctreeDataImpl(nodes, metadata);
        } catch (IOException e) {
            throw new DAGFormatException("Failed to deserialize DAG file: " + e.getMessage(), e);
        }
    }

    /**
     * Concrete implementation of DAGOctreeData for deserialized DAGs.
     */
    private static class DAGOctreeDataImpl implements DAGOctreeData {
        private final ESVONodeUnified[] nodes;
        private final DAGMetadata metadata;

        DAGOctreeDataImpl(ESVONodeUnified[] nodes, DAGMetadata metadata) {
            this.nodes = nodes;
            this.metadata = metadata;
        }

        @Override
        public ESVONodeUnified[] nodes() {
            return nodes;
        }

        @Override
        public int[] getFarPointers() {
            return new int[0];
        }

        @Override
        public int[] getContours() {
            return new int[0];
        }

        @Override
        public ByteBuffer nodesToByteBuffer() {
            var buffer = ByteBuffer.allocateDirect(nodes.length * 8)
                .order(ByteOrder.nativeOrder());
            for (var node : nodes) {
                buffer.putInt(node.getChildDescriptor());
                buffer.putInt(node.getContourDescriptor());
            }
            buffer.flip();
            return buffer;
        }

        @Override
        public CoordinateSpace getCoordinateSpace() {
            return CoordinateSpace.UNIT_CUBE;
        }

        @Override
        public int leafCount() {
            int count = 0;
            for (var node : nodes) {
                if (node.getChildDescriptor() == 0) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public int nodeCount() {
            return nodes.length;
        }

        @Override
        public int sizeInBytes() {
            return nodes.length * 8;
        }

        /**
         * Get metadata for this DAG.
         */
        public DAGMetadata getMetadata() {
            return metadata;
        }

        /**
         * Get compression ratio.
         */
        public float getCompressionRatio() {
            return metadata.compressionRatio();
        }

        @Override
        public int maxDepth() {
            return metadata.maxDepth();
        }

        @Override
        public int internalCount() {
            return nodeCount() - leafCount();
        }
    }
}

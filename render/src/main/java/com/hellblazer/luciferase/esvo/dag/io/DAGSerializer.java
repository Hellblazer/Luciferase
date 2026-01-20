package com.hellblazer.luciferase.esvo.dag.io;

import com.hellblazer.luciferase.esvo.dag.DAGOctreeData;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Serializer for DAG octree data to .dag file format.
 *
 * <p>File format:
 * <ul>
 *   <li>32-byte header (magic, version, metadata)</li>
 *   <li>Node pool (8 bytes per node)</li>
 *   <li>JSON metadata section (length-prefixed)</li>
 * </ul>
 *
 * <p>All multi-byte integers use little-endian byte order for cross-platform compatibility.
 */
public class DAGSerializer {
    private static final int MAGIC_NUMBER = 0x44414721; // "DAG!"
    private static final byte VERSION = 1;

    /**
     * Serialize a DAG octree to a .dag file.
     *
     * @param dag the DAG octree data to serialize
     * @param file the output file path
     * @throws IOException if I/O error occurs
     */
    public static void serialize(DAGOctreeData dag, Path file) throws IOException {
        try (var channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {

            // Write 32-byte header
            var header = ByteBuffer.allocate(32)
                .order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(MAGIC_NUMBER);
            header.put(VERSION);
            header.put((byte) dag.getMetadata().hashAlgorithm().ordinal());
            header.put((byte) dag.getMetadata().strategy().ordinal());
            header.put((byte) 0); // reserved
            header.putInt(dag.nodeCount());
            header.putInt(dag.getMetadata().originalNodeCount());
            header.putShort((short) dag.maxDepth());
            header.putShort((short) dag.getMetadata().sharedSubtreeCount());
            header.putInt((int) dag.getMetadata().buildTime().toMillis());
            header.putLong(dag.getMetadata().sourceHash());
            header.flip();
            channel.write(header);

            // Write node pool
            for (int i = 0; i < dag.nodeCount(); i++) {
                var node = dag.getNode(i);
                var nodeBuffer = ByteBuffer.allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN);
                nodeBuffer.putInt(node.getChildDescriptor());
                nodeBuffer.putInt(node.getContourDescriptor());
                nodeBuffer.flip();
                channel.write(nodeBuffer);
            }

            // Write metadata JSON
            var metadata = dag.getMetadata();
            var json = """
                {
                  "uniqueNodeCount": %d,
                  "originalNodeCount": %d,
                  "compressionRatio": %.2f,
                  "memorySavedBytes": %d,
                  "buildTime": "%s"
                }
                """.formatted(
                    metadata.uniqueNodeCount(),
                    metadata.originalNodeCount(),
                    metadata.compressionRatio(),
                    metadata.memorySavedBytes(),
                    metadata.buildTime()
                );
            var jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            var jsonLengthBuffer = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(jsonBytes.length);
            jsonLengthBuffer.flip();
            channel.write(jsonLengthBuffer);
            channel.write(ByteBuffer.wrap(jsonBytes));
        }
    }
}

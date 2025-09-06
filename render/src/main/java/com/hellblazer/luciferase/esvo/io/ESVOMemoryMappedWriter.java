package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Memory-mapped file writer for ESVO octree data
 */
public class ESVOMemoryMappedWriter {
    
    /**
     * Write octree data using memory-mapped file
     */
    public void write(ESVOOctreeData octree, Path outputFile) throws IOException {
        // Count nodes
        int nodeCount = countNodes(octree);
        
        // Calculate file size (12 bytes per node: childMask(1) + padding(3) + contour(4) + farPointer(4))
        int headerSize = ESVOFileFormat.HEADER_SIZE_V2;
        int nodeDataSize = nodeCount * 12;
        long fileSize = headerSize + nodeDataSize;
        
        try (RandomAccessFile raf = new RandomAccessFile(outputFile.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            
            // Set file size
            raf.setLength(fileSize);
            
            // Map entire file
            MappedByteBuffer buffer = channel.map(
                FileChannel.MapMode.READ_WRITE, 0, fileSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            // Write header
            buffer.putInt(ESVOFileFormat.MAGIC_NUMBER);
            buffer.putInt(ESVOFileFormat.VERSION_2);
            buffer.putInt(nodeCount);
            buffer.putInt(0); // reserved
            buffer.putLong(0); // metadata offset
            buffer.putLong(0); // metadata size
            
            // Write nodes
            for (int i = 0; i < nodeCount; i++) {
                ESVOOctreeNode node = octree.getNode(i);
                if (node != null) {
                    buffer.put(node.childMask);
                    buffer.put((byte)0); // padding
                    buffer.putShort((short)0); // padding
                    buffer.putInt(node.contour);
                    buffer.putInt(node.farPointer);
                } else {
                    // Empty node: 12 bytes of zeros
                    buffer.putLong(0);
                    buffer.putInt(0);
                }
            }
            
            // Force write to disk
            buffer.force();
        }
    }
    
    private int countNodes(ESVOOctreeData octree) {
        // Get the actual stored node indices and return the max index + 1
        int[] indices = octree.getNodeIndices();
        if (indices.length == 0) {
            return 0;
        }
        // Return max index + 1 to ensure we have space for all nodes
        return indices[indices.length - 1] + 1;
    }
}
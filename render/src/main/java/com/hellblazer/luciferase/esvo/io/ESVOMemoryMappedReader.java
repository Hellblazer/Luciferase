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
 * Memory-mapped file reader for ESVO octree data
 */
public class ESVOMemoryMappedReader {
    
    /**
     * Read octree data using memory-mapped file
     */
    public ESVOOctreeData read(Path inputFile) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(inputFile.toFile(), "r");
             FileChannel channel = raf.getChannel()) {
            
            long fileSize = channel.size();
            
            // Map entire file
            MappedByteBuffer buffer = channel.map(
                FileChannel.MapMode.READ_ONLY, 0, fileSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            // Read header
            int magic = buffer.getInt();
            if (magic != ESVOFileFormat.MAGIC_NUMBER) {
                throw new IOException("Invalid ESVO file: bad magic number");
            }
            
            int version = buffer.getInt();
            int nodeCount = buffer.getInt();
            buffer.getInt(); // skip reserved
            
            if (version >= ESVOFileFormat.VERSION_2) {
                buffer.getLong(); // skip metadata offset
                buffer.getLong(); // skip metadata size
            }
            
            // Create octree
            ESVOOctreeData octree = new ESVOOctreeData(nodeCount * 8 + 1024);
            
            // Read nodes
            for (int i = 0; i < nodeCount; i++) {
                ESVOOctreeNode node = new ESVOOctreeNode();
                node.childMask = buffer.get();
                buffer.get(); // skip padding
                buffer.getShort(); // skip padding
                node.contour = buffer.getInt();
                node.farPointer = buffer.getInt();
                
                octree.setNode(i, node);
            }
            
            return octree;
        }
    }
    
    /**
     * Read a single node at a specific index
     */
    public ESVOOctreeNode readNode(Path inputFile, int nodeIndex) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(inputFile.toFile(), "r");
             FileChannel channel = raf.getChannel()) {
            
            // Calculate node offset (12 bytes per node)
            int headerSize = ESVOFileFormat.HEADER_SIZE_V2;
            long nodeOffset = headerSize + (nodeIndex * 12L);
            
            // Map just the node
            MappedByteBuffer buffer = channel.map(
                FileChannel.MapMode.READ_ONLY, nodeOffset, 12);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            // Read node
            ESVOOctreeNode node = new ESVOOctreeNode();
            node.childMask = buffer.get();
            buffer.get(); // skip padding
            buffer.getShort(); // skip padding
            node.contour = buffer.getInt();
            node.farPointer = buffer.getInt();
            
            return node;
        }
    }
}
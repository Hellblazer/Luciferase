package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeData;
import com.hellblazer.luciferase.esvo.core.ESVOOctreeNode;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Serializer for ESVO octree data
 */
public class ESVOSerializer {
    
    private final int version;
    
    public ESVOSerializer() {
        this(ESVOFileFormat.VERSION_2);
    }
    
    public ESVOSerializer(int version) {
        this.version = version;
    }
    
    /**
     * Serialize octree data to file
     */
    public void serialize(ESVOOctreeData octree, Path outputFile) throws IOException {
        try (FileChannel channel = FileChannel.open(outputFile, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            // Count actual nodes
            int nodeCount = countNodes(octree);
            
            // Write header
            ESVOFileFormat.Header header = new ESVOFileFormat.Header();
            header.version = version;
            header.nodeCount = nodeCount;
            
            ByteBuffer headerBuffer = ByteBuffer.allocate(header.getHeaderSize());
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            writeHeader(headerBuffer, header);
            headerBuffer.flip();
            channel.write(headerBuffer);
            
            // Write nodes
            writeNodes(channel, octree, nodeCount);
        }
    }
    
    /**
     * Serialize with metadata
     */
    public void serializeWithMetadata(ESVOOctreeData octree, ESVOMetadata metadata, Path outputFile) 
            throws IOException {
        
        try (FileChannel channel = FileChannel.open(outputFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            int nodeCount = countNodes(octree);
            
            // Prepare header
            ESVOFileFormat.Header header = new ESVOFileFormat.Header();
            header.version = ESVOFileFormat.VERSION_2;
            header.nodeCount = nodeCount;
            
            // Write header (will update metadata offset later)
            ByteBuffer headerBuffer = ByteBuffer.allocate(header.getHeaderSize());
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            writeHeader(headerBuffer, header);
            headerBuffer.flip();
            channel.write(headerBuffer);
            
            // Write nodes
            writeNodes(channel, octree, nodeCount);
            
            // Write metadata
            long metadataOffset = channel.position();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(metadata);
            }
            byte[] metadataBytes = baos.toByteArray();
            
            ByteBuffer metadataBuffer = ByteBuffer.wrap(metadataBytes);
            channel.write(metadataBuffer);
            
            // Update header with metadata info
            header.metadataOffset = metadataOffset;
            header.metadataSize = metadataBytes.length;
            
            headerBuffer.clear();
            writeHeader(headerBuffer, header);
            headerBuffer.flip();
            channel.position(0);
            channel.write(headerBuffer);
        }
    }
    
    private void writeHeader(ByteBuffer buffer, ESVOFileFormat.Header header) {
        buffer.putInt(header.magic);
        buffer.putInt(header.version);
        buffer.putInt(header.nodeCount);
        buffer.putInt(header.reserved);
        
        if (header.version >= ESVOFileFormat.VERSION_2) {
            buffer.putLong(header.metadataOffset);
            buffer.putLong(header.metadataSize);
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
    
    private void writeNodes(FileChannel channel, ESVOOctreeData octree, int nodeCount) 
            throws IOException {
        
        // Allocate buffer for nodes (8 bytes per node)
        ByteBuffer nodeBuffer = ByteBuffer.allocate(nodeCount * 8);
        nodeBuffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Write each node
        for (int i = 0; i < nodeCount; i++) {
            ESVOOctreeNode node = octree.getNode(i);
            if (node != null) {
                nodeBuffer.put(node.childMask);
                nodeBuffer.put((byte)0); // padding
                nodeBuffer.putShort((short)0); // padding
                nodeBuffer.putInt(node.contour);
            } else {
                nodeBuffer.putLong(0); // Empty node
            }
        }
        
        nodeBuffer.flip();
        channel.write(nodeBuffer);
    }
}
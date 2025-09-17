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
 * Deserializer for ESVO octree data
 */
public class ESVODeserializer {
    
    /**
     * Result containing both octree and metadata
     */
    public static class Result {
        public final ESVOOctreeData octree;
        public final ESVOMetadata metadata;
        
        public Result(ESVOOctreeData octree, ESVOMetadata metadata) {
            this.octree = octree;
            this.metadata = metadata;
        }
    }
    
    /**
     * Deserialize octree data from file
     */
    public ESVOOctreeData deserialize(Path inputFile) throws IOException {
        try (FileChannel channel = FileChannel.open(inputFile, StandardOpenOption.READ)) {
            
            // Read header
            ESVOFileFormat.Header header = readHeader(channel);
            
            // Create octree - ensure minimum capacity
            int capacity = Math.max(header.nodeCount * 8 + 1024, 100000);
            ESVOOctreeData octree = new ESVOOctreeData(capacity);
            
            // Read nodes
            readNodes(channel, octree, header.nodeCount);
            
            return octree;
        }
    }
    
    /**
     * Deserialize with metadata
     */
    public Result deserializeWithMetadata(Path inputFile) throws IOException {
        try (FileChannel channel = FileChannel.open(inputFile, StandardOpenOption.READ)) {
            
            // Read header
            ESVOFileFormat.Header header = readHeader(channel);
            
            // Create octree - ensure minimum capacity
            int capacity = Math.max(header.nodeCount * 8 + 1024, 100000);
            ESVOOctreeData octree = new ESVOOctreeData(capacity);
            
            // Read nodes
            readNodes(channel, octree, header.nodeCount);
            
            // Read metadata if present
            ESVOMetadata metadata = null;
            if (header.version >= ESVOFileFormat.VERSION_2 && header.metadataOffset > 0) {
                channel.position(header.metadataOffset);
                ByteBuffer metadataBuffer = ByteBuffer.allocate((int)header.metadataSize);
                channel.read(metadataBuffer);
                metadataBuffer.flip();
                
                ByteArrayInputStream bais = new ByteArrayInputStream(metadataBuffer.array());
                try (ObjectInputStream ois = new ObjectInputStream(bais)) {
                    metadata = (ESVOMetadata) ois.readObject();
                } catch (ClassNotFoundException e) {
                    throw new IOException("Failed to deserialize metadata", e);
                }
            }
            
            return new Result(octree, metadata);
        }
    }
    
    private ESVOFileFormat.Header readHeader(FileChannel channel) throws IOException {
        ESVOFileFormat.Header header = new ESVOFileFormat.Header();
        
        // Read basic header
        ByteBuffer headerBuffer = ByteBuffer.allocate(ESVOFileFormat.HEADER_SIZE_V1);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(headerBuffer);
        headerBuffer.flip();
        
        header.magic = headerBuffer.getInt();
        if (header.magic != ESVOFileFormat.MAGIC_NUMBER) {
            throw new IOException("Invalid ESVO file: bad magic number");
        }
        
        header.version = headerBuffer.getInt();
        header.nodeCount = headerBuffer.getInt();
        header.reserved = headerBuffer.getInt();
        
        // Read extended header for v2
        if (header.version >= ESVOFileFormat.VERSION_2) {
            ByteBuffer extBuffer = ByteBuffer.allocate(16);
            extBuffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.read(extBuffer);
            extBuffer.flip();
            
            header.metadataOffset = extBuffer.getLong();
            header.metadataSize = extBuffer.getLong();
        }
        
        return header;
    }
    
    private void readNodes(FileChannel channel, ESVOOctreeData octree, int nodeCount) 
            throws IOException {
        
        // Read all nodes
        ByteBuffer nodeBuffer = ByteBuffer.allocate(nodeCount * 8);
        nodeBuffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(nodeBuffer);
        nodeBuffer.flip();
        
        // Parse nodes
        for (int i = 0; i < nodeCount; i++) {
            ESVOOctreeNode node = new ESVOOctreeNode();
            node.childMask = nodeBuffer.get();
            nodeBuffer.get(); // skip padding
            nodeBuffer.getShort(); // skip padding
            node.contour = nodeBuffer.getInt();
            
            octree.setNode(i, node);
        }
    }
}
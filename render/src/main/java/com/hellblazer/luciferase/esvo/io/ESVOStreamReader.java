package com.hellblazer.luciferase.esvo.io;

import com.hellblazer.luciferase.esvo.core.ESVOOctreeNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Streaming reader for ESVO octree data
 */
public class ESVOStreamReader {
    
    private final FileChannel channel;
    private final int totalNodes;
    private int currentNode;
    
    public ESVOStreamReader(Path inputFile) throws IOException {
        this.channel = FileChannel.open(inputFile, StandardOpenOption.READ);
        
        // Read header
        ByteBuffer headerBuffer = ByteBuffer.allocate(ESVOFileFormat.HEADER_SIZE_V2);
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(headerBuffer);
        headerBuffer.flip();
        
        int magic = headerBuffer.getInt();
        if (magic != ESVOFileFormat.MAGIC_NUMBER) {
            throw new IOException("Invalid ESVO file: bad magic number");
        }
        
        int version = headerBuffer.getInt();
        this.totalNodes = headerBuffer.getInt();
        headerBuffer.getInt(); // skip reserved
        
        if (version >= ESVOFileFormat.VERSION_2) {
            headerBuffer.getLong(); // skip metadata offset
            headerBuffer.getLong(); // skip metadata size
        }
        
        this.currentNode = 0;
    }
    
    /**
     * Check if there are more nodes to read
     */
    public boolean hasNext() {
        return currentNode < totalNodes;
    }
    
    /**
     * Read the next node
     */
    public ESVOOctreeNode readNext() throws IOException {
        if (!hasNext()) {
            return null;
        }
        
        ByteBuffer nodeBuffer = ByteBuffer.allocate(8);
        nodeBuffer.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(nodeBuffer);
        nodeBuffer.flip();
        
        ESVOOctreeNode node = new ESVOOctreeNode();
        node.childMask = nodeBuffer.get();
        nodeBuffer.get(); // skip padding
        nodeBuffer.getShort(); // skip padding
        node.contour = nodeBuffer.getInt();
        
        currentNode++;
        return node;
    }
    
    /**
     * Close the reader
     */
    public void close() throws IOException {
        channel.close();
    }
}